"""Verify the actual proxy + database stack, optionally restarting its backend.

Only prints test names/status, never cookies, private cards or configured secrets.
Run against the dedicated Compose acceptance stack, not a user's live table.
"""
import argparse
import http.cookiejar
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:8088')
    parser.add_argument('--health-path', default='/api/health',
                        help='Use /actuator/health/readiness for a directly started backend')
    parser.add_argument('--restart', action='store_true')
    args = parser.parse_args()
    if not args.health_path.startswith('/') or args.health_path.startswith('//'):
        parser.error('--health-path must be a local absolute URL path')
    endpoint = urllib.parse.urlsplit(args.url)
    if endpoint.hostname not in ('localhost', '127.0.0.1', '::1'):
        parser.error('This acceptance script only targets a local stack.')
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(path, data=None):
        req = urllib.request.Request(args.url + path,
            data=json.dumps(data).encode() if data is not None else None,
            headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=10) as response:
            return json.load(response)

    def wait_for(check, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = check()
                if value:
                    return value
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(.3)
        raise AssertionError('Stack did not reach expected state before timeout')

    wait_for(lambda: request(args.health_path)['status'] == 'UP')
    unauthorized_stream = urllib.request.Request(
        args.url + '/api/tables/current/events?tableId=' + str(uuid.uuid4()),
        headers={'Accept': 'text/event-stream'})
    try:
        urllib.request.urlopen(unauthorized_stream, timeout=10)
        raise AssertionError('Unauthenticated event stream was accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 401, f'Expected 401 for an expired stream, got {error.code}'
    created = request('/api/tables', {'mode': 'PLAYER', 'displayName': '发布验收'})

    def human_turn():
        view = request('/api/tables/current')
        return view if view['actorSeat'] == view['selfSeat'] else None

    current = wait_for(human_turn)
    assert len(current['holeCards']) == 2
    assert all('holeCards' not in seat for seat in current['seats'])
    chat = {'tableId': created['tableId'], 'expectedVersion': current['version'],
            'commandId': str(uuid.uuid4()), 'text': '重启前后，同一桌茶。'}
    saved = request('/api/tables/current/chat', chat)
    assert request('/api/tables/current/chat', chat) == saved
    query = urllib.parse.urlencode({'tableId': saved['tableId'], 'after': saved['sequence'] - 1})
    with opener.open(args.url + '/api/tables/current/events?' + query, timeout=10) as stream:
        assert stream.headers.get('Content-Type', '').startswith('text/event-stream')
        event = []
        for _ in range(20):
            line = stream.readline().decode().strip()
            event.append(line)
            if line.startswith('data:'):
                break
        assert f"id:{saved['sequence']}" in event
        assert 'event:table' in event
        projection = json.loads(next(line[5:] for line in event if line.startswith('data:')))
        assert projection == saved
    print('PASS: health, expired SSE, table, chat, duplicate command and unbuffered SSE')

    if args.restart:
        subprocess.run(['docker', 'compose', 'restart', 'backend'], check=True)
        wait_for(lambda: request(args.health_path)['status'] == 'UP', timeout=120)
        assert request('/api/tables/current') == saved
        assert request('/api/tables/current/chat', chat) == saved
        print('PASS: same cookie, snapshot and command receipt after backend restart')

    replay = request('/api/tables/current/replay?' + query)
    assert replay['frames'] == [saved]
    print('PASS: persistent session-scoped replay')


if __name__ == '__main__':
    main()
