#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
scope=${1:-all}
case "$scope" in
  repo|backend|frontend|all) ;;
  *) echo "Usage: $0 [repo|backend|frontend|all]" >&2; exit 2 ;;
esac

if [ "$scope" = repo ] || [ "$scope" = all ]; then
  python3 scripts/check_repo.py
  python3 -B -m unittest discover -s scripts -p 'test_*.py'
fi
if [ "$scope" = backend ] || [ "$scope" = all ]; then
  (cd backend && ./mvnw -B -ntp verify)
fi
if [ "$scope" = frontend ] || [ "$scope" = all ]; then
  (cd frontend && bun run lint && bun run build)
fi
