#!/usr/bin/env bash
# Pure Java tests use the public-domain org.json implementation in place of
# Android's bundled org.json. The dependency is test-only and never packaged.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
JSON_SOURCE="${BLOCO_JSON_SOURCE:-}"
if [[ -z "$JSON_SOURCE" ]]; then
  git clone --quiet https://github.com/stleary/JSON-java.git "$WORK/json"
  git -C "$WORK/json" checkout -q 2cd0a8c2112a3bc17d33ba8546c54ecb613613f1
  JSON_SOURCE="$WORK/json"
fi
mkdir -p "$WORK/classes"
mapfile -t JSON < <(find "$JSON_SOURCE/src/main/java" -name '*.java' | sort)
SRC=mobile/android/app/src/main/java/br/com/bloco/servidor
javac --release 8 -encoding UTF-8 -d "$WORK/classes" "${JSON[@]}" \
  "$SRC/SafeZip.java" "$SRC/RuntimeInstaller.java" "$SRC/LocalFiles.java" "$SRC/BedrockInstaller.java" \
  "$SRC/RakNetProbe.java" "$SRC/NetherNetProbe.java" "$SRC/NetworkSettings.java" "$SRC/PortDiagnostics.java" \
  "$SRC/AppState.java" "$SRC/RuntimeCommand.java" "$SRC/JavaRuntimeInstaller.java" \
  "$SRC/PersistentLog.java" "$SRC/PerformanceSettings.java" "$SRC/ConsoleCommand.java" \
  mobile/test/SafeZipTest.java mobile/test/CoreTest.java mobile/test/PanelCoreTest.java mobile/test/MobileIntegrationTest.java
java -cp "$WORK/classes" br.com.bloco.servidor.SafeZipTest
java -cp "$WORK/classes" br.com.bloco.servidor.CoreTest
java -cp "$WORK/classes" br.com.bloco.servidor.PanelCoreTest
if [[ $# -ge 1 ]]; then
  [[ "$(uname -m)" == aarch64 ]] || { echo 'O teste de integração requer Linux ARM64.' >&2; exit 1; }
  mkdir -p "$WORK/real-integration"
  java -cp "$WORK/classes" br.com.bloco.servidor.MobileIntegrationTest "$1" "$WORK/real-integration"
fi
