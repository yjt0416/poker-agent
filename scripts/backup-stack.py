"""Back up the Compose PostgreSQL database; optionally restore it to an isolated test DB.

The archive contains private hands and sessions. Keep it in a protected location.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
import subprocess
import uuid


ROOT = Path(__file__).resolve().parents[1]


def compose(*args, stdin=None, stdout=None):
    return subprocess.run(['docker', 'compose', *args], cwd=ROOT, stdin=stdin,
                          stdout=stdout, stderr=subprocess.PIPE, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path,
                        help='New archive file; defaults to backups/agent-tavern-<UTC>.dump')
    parser.add_argument('--verify', action='store_true',
                        help='Restore into a temporary database and check the session table')
    args = parser.parse_args()
    if shutil.which('docker') is None:
        parser.error('Docker with Compose is required for this backup script')
    destination = args.output or ROOT / 'backups' / (
        'agent-tavern-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '.dump')
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    created_archive = False
    try:
        with destination.open('xb') as archive:
            created_archive = True
            compose('exec', '-T', 'db', 'pg_dump', '-U', 'tavern', '-d', 'agent_tavern',
                    '--format=custom', '--no-owner', '--no-acl', stdout=archive)
    except Exception:
        if created_archive:
            destination.unlink()
        raise
    if destination.stat().st_size == 0:
        destination.unlink()
        raise RuntimeError('pg_dump returned an empty archive')
    print(f'Backup saved: {destination}')

    if not args.verify:
        return
    restore_db = 'agent_tavern_restore_' + uuid.uuid4().hex[:12]
    created = False
    try:
        compose('exec', '-T', 'db', 'createdb', '-U', 'tavern', '-O', 'tavern', restore_db)
        created = True
        with destination.open('rb') as archive:
            compose('exec', '-T', 'db', 'pg_restore', '-U', 'tavern', '-d', restore_db,
                    '--exit-on-error', '--no-owner', '--no-acl', stdin=archive)
        result = compose('exec', '-T', 'db', 'psql', '-U', 'tavern', '-d', restore_db,
                         '-Atqc', "select to_regclass('public.table_sessions') is not null",
                         stdout=subprocess.PIPE)
        if result.stdout is None or result.stdout.decode().strip() != 't':
            raise RuntimeError('Restored archive is missing table_sessions')
        print('Restore verification passed in an isolated database')
    finally:
        if created:
            compose('exec', '-T', 'db', 'dropdb', '-U', 'tavern', '--if-exists', restore_db)


if __name__ == '__main__':
    main()
