#!/usr/bin/env bash
# Minimal adb-compatible wrapper for the CI-only, local Android container.
set -euo pipefail
container=bloco-android
command="${1:-}"
shift || true
case "$command" in
  shell) exec docker exec "$container" "$@" ;;
  logcat) exec docker exec "$container" logcat "$@" ;;
  install)
    [[ "${1:-}" == '-r' && $# -eq 2 ]] || { echo 'install inválido' >&2; exit 2; }
    docker cp "$2" "$container:/data/local/tmp/bloco-ci.apk" >/dev/null
    docker exec "$container" pm install -r /data/local/tmp/bloco-ci.apk
    docker exec "$container" rm -f /data/local/tmp/bloco-ci.apk
    ;;
  *) echo "Comando de teste não suportado: $command" >&2; exit 2 ;;
esac
