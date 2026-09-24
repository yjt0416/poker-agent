#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
command -v docker >/dev/null || { echo 'Install Docker with Compose first.' >&2; exit 1; }
docker compose version >/dev/null
docker info >/dev/null
if [ ! -f .env ]; then
    umask 077
    password=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    sed "s/^DATABASE_PASSWORD=$/DATABASE_PASSWORD=$password/" .env.example > .env
fi
docker compose config --quiet
docker compose up --build --detach --wait --wait-timeout 180
echo 'Ready. Default URL: http://localhost:8088 (or WEB_PORT from .env).'
echo 'Stop with docker compose down; the database volume is retained.'
