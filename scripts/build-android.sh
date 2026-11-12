#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(node -p "require('./package.json').version")"
if [[ ! "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then echo "Versão deve usar major.minor.patch." >&2; exit 1; fi
MAJOR=${BASH_REMATCH[1]}; MINOR=${BASH_REMATCH[2]}; PATCH=${BASH_REMATCH[3]}
if (( MAJOR > 200 || MINOR > 99 || PATCH > 99 )); then echo "Versão fora do intervalo suportado." >&2; exit 1; fi
VERSION_CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH))
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
TOOLS="${SDK_ROOT:+$SDK_ROOT/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-${SDK_ROOT:+$SDK_ROOT/platforms/android-34/android.jar}}"
AAPT2_BIN="${AAPT2_BIN:-${TOOLS:+$TOOLS/aapt2}}"
D8_JAR="${D8_JAR:-${TOOLS:+$TOOLS/lib/d8.jar}}"
APKSIGNER_JAR="${APKSIGNER_JAR:-${TOOLS:+$TOOLS/lib/apksigner.jar}}"
ZIPALIGN_BIN="${ZIPALIGN_BIN:-${TOOLS:+$TOOLS/zipalign}}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
KEYTOOL_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
for item in "$ANDROID_JAR" "$AAPT2_BIN" "$D8_JAR" "$APKSIGNER_JAR"; do
  [[ -n "$item" && -f "$item" ]] || { echo "Falta ferramenta Android: ${item:-ANDROID_HOME não configurado}" >&2; exit 1; }
done
command -v "$JAVA_BIN" >/dev/null || { echo "Java não encontrado." >&2; exit 1; }
command -v "$KEYTOOL_BIN" >/dev/null || { echo "keytool não encontrado." >&2; exit 1; }
WORK="$ROOT/.android-build/work"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$ROOT/.android-build"
"$AAPT2_BIN" compile --dir android/app/src/main/res -o "$WORK/res.zip"
"$AAPT2_BIN" link -I "$ANDROID_JAR" --manifest android/app/src/main/AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION" \
  -o "$WORK/base.apk" "$WORK/res.zip"
mapfile -t SOURCES < <(find android/app/src/main/java -name '*.java' -type f | sort)
(( ${#SOURCES[@]} > 0 )) || { echo "Nenhum código Android encontrado." >&2; exit 1; }
if command -v "$JAVAC_BIN" >/dev/null; then
  "$JAVAC_BIN" -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
elif [[ -n "${ECJ_JAR:-}" && -f "$ECJ_JAR" ]]; then
  "$JAVA_BIN" -jar "$ECJ_JAR" -source 1.8 -target 1.8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
else
  echo "javac ou ECJ_JAR é necessário." >&2; exit 1
fi
mapfile -t CLASSES < <(find "$WORK/classes" -name '*.class' -type f | sort)
"$JAVA_BIN" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 24 --lib "$ANDROID_JAR" --output "$WORK/dex" "${CLASSES[@]}"
cp "$WORK/base.apk" "$WORK/unaligned.apk"
(cd "$WORK/dex" && zip -q -0 "$WORK/unaligned.apk" classes.dex)
if [[ -n "$ZIPALIGN_BIN" && -x "$ZIPALIGN_BIN" ]]; then
  "$ZIPALIGN_BIN" -f 4 "$WORK/unaligned.apk" "$WORK/aligned.apk"
else
  python3 scripts/zipalign.py "$WORK/unaligned.apk" "$WORK/aligned.apk"
fi
python3 scripts/zipalign.py --verify "$WORK/aligned.apk"
KEYDIR="$(mktemp -d)"
trap 'rm -rf "$KEYDIR"' EXIT
if [[ -n "${BLOCO_KEYSTORE:-}" ]]; then
  : "${BLOCO_KEY_ALIAS:?Defina BLOCO_KEY_ALIAS}"
  : "${BLOCO_KEYSTORE_PASSWORD:?Defina BLOCO_KEYSTORE_PASSWORD}"
  : "${BLOCO_KEY_PASSWORD:?Defina BLOCO_KEY_PASSWORD}"
  KEYSTORE="$BLOCO_KEYSTORE"; ALIAS="$BLOCO_KEY_ALIAS"; STORE_PASS="$BLOCO_KEYSTORE_PASSWORD"; KEY_PASS="$BLOCO_KEY_PASSWORD"
else
  # Development signing key: ephemeral and never included in the ZIP or source tree.
  KEYSTORE="$KEYDIR/debug.jks"; ALIAS=bloco; STORE_PASS=android; KEY_PASS=android
  "$KEYTOOL_BIN" -genkeypair -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Bloco Development, O=Bloco, C=BR' >/dev/null 2>&1
fi
APK="$ROOT/.android-build/Bloco-$VERSION.apk"
export STORE_PASS KEY_PASS
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass "env:STORE_PASS" --key-pass "env:KEY_PASS" --min-sdk-version 24 --out "$APK" "$WORK/aligned.apk"
"$JAVA_BIN" -jar "$APKSIGNER_JAR" verify --verbose --min-sdk-version 24 "$APK"
python3 scripts/zipalign.py --verify "$APK"
"$AAPT2_BIN" dump badging "$APK" | head -8
printf '\nAPK criado: %s\n' "$APK"
