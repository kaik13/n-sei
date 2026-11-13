#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(node -p "require('./package.json').version")"
[[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo 'Versão inválida.' >&2; exit 1; }
MAJOR=${BASH_REMATCH[1]}; MINOR=${BASH_REMATCH[2]}; PATCH=${BASH_REMATCH[3]}
(( MAJOR <= 200 && MINOR <= 99 && PATCH <= 99 )) || { echo 'Versão fora do intervalo.' >&2; exit 1; }
VERSION_CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH))
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
TOOLS="${SDK_ROOT:+$SDK_ROOT/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-${SDK_ROOT:+$SDK_ROOT/platforms/android-34/android.jar}}"
AAPT2_BIN="${AAPT2_BIN:-${TOOLS:+$TOOLS/aapt2}}"
D8_JAR="${D8_JAR:-${TOOLS:+$TOOLS/lib/d8.jar}}"
APKSIGNER_JAR="${APKSIGNER_JAR:-${TOOLS:+$TOOLS/lib/apksigner.jar}}"
ZIPALIGN_BIN="${ZIPALIGN_BIN:-${TOOLS:+$TOOLS/zipalign}}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"; KEYTOOL_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"; JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
for item in "$ANDROID_JAR" "$AAPT2_BIN" "$D8_JAR" "$APKSIGNER_JAR"; do
  [[ -n "$item" && -f "$item" ]] || { echo "Falta ferramenta Android: ${item:-ANDROID_HOME não configurado}" >&2; exit 1; }
done
for item in "$JAVA_BIN" "$KEYTOOL_BIN"; do command -v "$item" >/dev/null || { echo "Ferramenta Java não encontrada: $item" >&2; exit 1; }; done
ASSETS=mobile/android/app/src/main/assets
[[ -f "$ASSETS/runtime.zip" ]] || { echo 'Falta o runtime ARM64 verificado em assets/runtime.zip.' >&2; exit 1; }
python3 - "$ASSETS/runtime.zip" "${BLOCO_ALLOW_TEST_RUNTIME:-0}" <<'PY'
import hashlib, json, os, posixpath, sys, zipfile
path, test = sys.argv[1:]
with zipfile.ZipFile(path) as z:
    names = set(z.namelist())
    ca = 'rootfs/etc/ssl/certs/ca-certificates.crt'
    for required in ('proot', 'rootfs/opt/box64/box64', 'runtime-manifest.json', ca):
        if required not in names: raise SystemExit('Runtime incompleto: ' + required)
    if z.testzip() is not None: raise SystemExit('Runtime corrompido.')
    if test != '1':
        import struct
        if os.path.getsize(path) < 5_000_000: raise SystemExit('Runtime pequeno demais para uma versão utilizável.')
        if hashlib.sha256(z.read(ca)).hexdigest() != '2ef52ad56b4803e264045c915e55c236cbfa045b84b81efefc218a19eb44bd2b':
            raise SystemExit('Pacote de certificados ausente ou inesperado.')
        manifest = json.loads(z.read('runtime-manifest.json'))
        if manifest.get('proot') != 'termux-v5.1.107.102-musl-static-pie':
            raise SystemExit('Versão do PRoot inesperada.')
        if manifest.get('box64-tcp-reuse') != 1:
            raise SystemExit('Runtime sem a compatibilidade TCP verificada no Box64.')
        if manifest.get('box64-cleanup-thread-safety') != 1:
            raise SystemExit('Runtime sem a proteção concorrente do Box64.')
        elf = z.read('proot')
        if len(elf) < 64 or elf[:6] != b'\x7fELF\x02\x01': raise SystemExit('PRoot não é ELF64 little-endian.')
        hdr = struct.unpack_from('<16sHHIQQQIHHHHHH', elf)
        if hdr[1] != 3 or hdr[2] != 183: raise SystemExit('PRoot não é PIE ARM64.')
        for i in range(hdr[10]):
            ph = struct.unpack_from('<IIQQQQQQ', elf, hdr[5] + i * hdr[9])
            if ph[0] == 3: raise SystemExit('PRoot depende de carregador dinâmico.')
            if ph[0] == 1 and ph[2] % 16384 != ph[3] % 16384: raise SystemExit('Alinhamento do PRoot inválido.')
        links = dict(manifest['links'])
        libgcc = 'rootfs/usr/lib/box64-x86_64-linux-gnu/libgcc_s.so.1'
        target = links.get(libgcc)
        if target is None or posixpath.normpath(posixpath.join(posixpath.dirname(libgcc), target)) not in names:
            raise SystemExit('Runtime sem o alvo de libgcc_s.so.1.')
PY
WORK="$ROOT/.android-build/work-mobile"
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/dex" "$ROOT/.android-build"
"$AAPT2_BIN" compile --dir mobile/android/app/src/main/res -o "$WORK/res.zip"
"$AAPT2_BIN" link -I "$ANDROID_JAR" --manifest mobile/android/app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 28 --version-code "$VERSION_CODE" --version-name "$VERSION" \
  -A "$ASSETS" -o "$WORK/base.apk" "$WORK/res.zip"
mapfile -t SOURCES < <(find mobile/android/app/src/main/java -name '*.java' -type f | sort)
(( ${#SOURCES[@]} > 0 )) || { echo 'Código Android ausente.' >&2; exit 1; }
if command -v "$JAVAC_BIN" >/dev/null; then
  # D8 34.0.0 cannot read unnamed synthetic MethodParameters from javac 21.
  "$JAVAC_BIN" -encoding UTF-8 --release 8 -parameters -classpath "$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
elif [[ -n "${ECJ_JAR:-}" && -f "$ECJ_JAR" ]]; then
  "$JAVA_BIN" -jar "$ECJ_JAR" -source 1.8 -target 1.8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
else echo 'javac ou ECJ_JAR é necessário.' >&2; exit 1; fi
mapfile -t CLASSES < <(find "$WORK/classes" -name '*.class' -type f | sort)
"$JAVA_BIN" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output "$WORK/dex" "${CLASSES[@]}"
cp "$WORK/base.apk" "$WORK/unaligned.apk"
(cd "$WORK/dex" && zip -q -0 "$WORK/unaligned.apk" classes.dex)
if [[ -n "$ZIPALIGN_BIN" && -x "$ZIPALIGN_BIN" ]]; then "$ZIPALIGN_BIN" -f 4 "$WORK/unaligned.apk" "$WORK/aligned.apk"
else python3 scripts/zipalign.py "$WORK/unaligned.apk" "$WORK/aligned.apk"; fi
python3 scripts/zipalign.py --verify "$WORK/aligned.apk"
KEYDIR="$(mktemp -d)"; trap 'rm -rf "$KEYDIR"' EXIT
if [[ -n "${BLOCO_KEYSTORE:-}" ]]; then
  : "${BLOCO_KEY_ALIAS:?Defina BLOCO_KEY_ALIAS}"; : "${BLOCO_KEYSTORE_PASSWORD:?Defina BLOCO_KEYSTORE_PASSWORD}"; : "${BLOCO_KEY_PASSWORD:?Defina BLOCO_KEY_PASSWORD}"
  KEYSTORE="$BLOCO_KEYSTORE"; ALIAS="$BLOCO_KEY_ALIAS"; STORE_PASS="$BLOCO_KEYSTORE_PASSWORD"; KEY_PASS="$BLOCO_KEY_PASSWORD"
else
  KEYSTORE="$KEYDIR/debug.jks"; ALIAS=bloco; STORE_PASS=android; KEY_PASS=android
  "$KEYTOOL_BIN" -genkeypair -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Bloco Development, O=Bloco, C=BR' >/dev/null 2>&1
fi
APK="$ROOT/.android-build/Bloco-$VERSION.apk"; export STORE_PASS KEY_PASS
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass env:STORE_PASS --key-pass env:KEY_PASS --min-sdk-version 26 --out "$APK" "$WORK/aligned.apk"
"$JAVA_BIN" -jar "$APKSIGNER_JAR" verify --verbose --min-sdk-version 26 "$APK"
python3 scripts/zipalign.py --verify "$APK"
"$AAPT2_BIN" dump badging "$APK" > "$WORK/badging.txt"
grep -Fq "package: name='br.com.bloco.servidor.teste'" "$WORK/badging.txt" || { echo 'APK não está isolado da instalação anterior.' >&2; exit 1; }
grep -Fq "launchable-activity: name='br.com.bloco.servidor.MainActivity'" "$WORK/badging.txt" || { echo 'Activity do APK de teste inválida.' >&2; exit 1; }
sed -n '1,8p' "$WORK/badging.txt"
echo "APK criado: $APK"
