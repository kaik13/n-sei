#!/usr/bin/env bash
# Minimal adb-compatible wrapper for the CI-only, local Android container.
set -euo pipefail
container=bloco-android
command="${1:-}"
shift || true
case "$command" in
  shell) exec docker exec "$container" "$@" ;;
  # Real adb shell normally uses Android's shell UID; keep this available for
  # UI automation tools that require the shell identity rather than root.
  shell-as-shell) exec docker exec --user 2000:2000 "$container" "$@" ;;
  logcat) exec docker exec "$container" logcat "$@" ;;
  install)
    [[ "${1:-}" == '-r' && $# -eq 2 ]] || { echo 'install inválido' >&2; exit 2; }
    docker cp "$2" "$container:/data/local/tmp/bloco-ci.apk" >/dev/null
    docker exec "$container" pm install -r /data/local/tmp/bloco-ci.apk
    docker exec "$container" rm -f /data/local/tmp/bloco-ci.apk
    ;;
  push-proot-test)
    [[ $# -eq 2 && -f "$1" && "$2" =~ ^[A-Za-z][A-Za-z0-9_.]*$ ]] || { echo 'PRoot ou pacote de teste inválido' >&2; exit 2; }
    docker cp "$1" "$container:/data/local/tmp/bloco-ci-proot" >/dev/null
    docker exec -e "BLOCO_TEST_PACKAGE=$2" "$container" sh -c 'set -e; a="/data/data/$BLOCO_TEST_PACKAGE"; p="$a/files/bloco/seccomp-teste"; owner=$(stat -c "%u:%g" "$a"); mkdir -p "$p"; cp /data/local/tmp/bloco-ci-proot "$p/proot"; chown "$owner" "$a/files/bloco" "$p" "$p/proot"; chmod 700 "$a/files/bloco" "$p" "$p/proot"; rm /data/local/tmp/bloco-ci-proot'
    ;;
  *) echo "Comando de teste não suportado: $command" >&2; exit 2 ;;
esac
