"""Bounded concurrent acceptance check for a directly reachable backend.

Creates independent throwaway sessions. Prints only aggregate timing and status.
Run against a dedicated test database, never a live public table.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import http.cookiejar
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8080')
    parser.add_argument('--tables', type=int, default=12)
    parser.add_argument('--workers', type=int, default=6)
    parser.add_argument('--turn-timeout', type=float, default=45)
    args = parser.parse_args()
    endpoint = urllib.parse.urlsplit(args.url)
    if endpoint.hostname not in ('localhost', '127.0.0.1', '::1'):
        parser.error('This script only targets a local acceptance backend')
    if not 2 <= args.tables <= 40 or not 1 <= args.workers <= args.tables:
        parser.error('Use 2-40 tables and 1-tables workers')

    def request(opener, path, data=None):
        req = urllib.request.Request(args.url + path,
            data=json.dumps(data).encode() if data is not None else None,
            headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=12) as response:
            return json.load(response)

    def run_table(index):
        opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        started = time.monotonic()
        created = request(opener, '/api/tables',
                          {'mode': 'PLAYER', 'displayName': f'并发验收{index}'})
        created_in = time.monotonic() - started
        assert created['selfSeat'] == 5 and len(created['holeCards']) == 2
        assert all('holeCards' not in seat for seat in created['seats'])
        deadline = time.monotonic() + args.turn_timeout
        skipped_hands = 0
        while True:
            view = request(opener, '/api/tables/current')
            if view['actorSeat'] == view['selfSeat']:
                break
            if view['status'] == 'BETWEEN_HANDS':
                # A player posting the big blind can win when everyone else folds.
                # The hand is valid even though the player never got an action.
                skipped_hands += 1
                if skipped_hands > 5:
                    raise AssertionError('Too many hands ended without a human turn')
                request(opener, '/api/tables/current/next-hand',
                        {'tableId': view['tableId'], 'expectedVersion': view['version'],
                         'commandId': str(uuid.uuid4())})
                continue
            if time.monotonic() >= deadline:
                raise AssertionError('Human turn timeout: status=' + str(view['status'])
                                     + ', actor=' + str(view['actorSeat'])
                                     + ', version=' + str(view['version']))
            time.sleep(.15)
        turn_in = time.monotonic() - started
        chat = {'tableId': view['tableId'], 'expectedVersion': view['version'],
                'commandId': str(uuid.uuid4()), 'text': f'第{index}桌准备好了。'}
        chatted = request(opener, '/api/tables/current/chat', chat)
        assert request(opener, '/api/tables/current/chat', chat) == chatted
        stream_url = (args.url + '/api/tables/current/events?' + urllib.parse.urlencode(
            {'tableId': chatted['tableId'], 'after': chatted['sequence'] - 1}))
        with opener.open(stream_url, timeout=12) as stream:
            assert stream.headers.get('Content-Type', '').startswith('text/event-stream')
            for _ in range(20):
                line = stream.readline().decode().strip()
                if line.startswith('data:'):
                    assert json.loads(line[5:]) == chatted
                    break
            else:
                raise AssertionError('No immediate SSE frame')
        legal = chatted['legalActions']['types']
        action_type = next(kind for kind in ('CHECK', 'CALL', 'FOLD') if kind in legal)
        acted = request(opener, '/api/tables/current/actions',
                        {'tableId': chatted['tableId'], 'expectedVersion': chatted['version'],
                         'commandId': str(uuid.uuid4()), 'type': action_type})
        assert acted['version'] > chatted['version']
        return opener, created['tableId'], created_in, turn_in

    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(run_table, index) for index in range(args.tables)]
        results = [future.result() for future in as_completed(futures)]
    assert len({result[1] for result in results}) == args.tables
    opener, _table, *_ = results[0]
    other_table = results[1][1]
    try:
        request(opener, '/api/tables/current/replay?' + urllib.parse.urlencode(
            {'tableId': other_table, 'after': 0}))
        raise AssertionError('A different session could read this replay')
    except urllib.error.HTTPError as error:
        assert error.code == 403, f'Expected cross-session 403, got {error.code}'

    def p95(values):
        ordered = sorted(values)
        return ordered[math.ceil(len(ordered) * .95) - 1]

    print(f'PASS: {args.tables} isolated tables, chat idempotency, SSE, action and replay isolation')
    print(f'Elapsed {time.monotonic() - started:.1f}s; create p95 {p95([r[2] for r in results]):.2f}s; '
          f'human turn p95 {p95([r[3] for r in results]):.2f}s')


if __name__ == '__main__':
    main()
