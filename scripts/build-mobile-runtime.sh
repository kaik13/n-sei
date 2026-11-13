#!/usr/bin/env bash
# Build a self-contained ARM64 translation environment, without Mojang files.
# Adapted from Pondok's MIT-licensed build_runtime_pack.sh (lunarjjo/pondok).
set -euo pipefail
[[ "$(uname -m)" == aarch64 ]] || { echo 'Este build precisa de Linux arm64.' >&2; exit 1; }
SCRIPT="$(realpath "$0")"
ROOT="$(cd "$(dirname "$SCRIPT")/.." && pwd)"
OUT="$(realpath -m "${1:-.mobile-runtime}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT" "$OUT/fontes"

BOX64_TAG=v0.4.4
BASE=24.04.5
BASE_HASH=a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
LIBS_HASH=1d4a8787a8a92267c1be673ec194c69e3bb33d14c800575d94131084142fcff0

sudo apt-get update -qq
sudo apt-get install -y -qq build-essential cmake pkg-config gcc-x86-64-linux-gnu > /dev/null

# Termux's Android-aware tracer is compiled as static PIE with musl. Ship its
# exact sources, patches and static libraries' source alongside the APK.
if ! python3 "$ROOT/scripts/build-mobile-proot.py" --abi arm64-v8a \
  --output "$WORK/proot" --sources-dir "$OUT/fontes" >"$WORK/proot.log" 2>&1; then
  tail -100 "$WORK/proot.log"; exit 1
fi
python3 "$ROOT/mobile/test/test-proot-seccomp.py" "$WORK/proot"
cp "$SCRIPT" "$OUT/fontes/compilar-runtime.sh"
{ echo 'Pacotes do ambiente de compilação:'; dpkg-query -W -f='${Package} ${Version}\n' libc6 libc6-dev gcc libgcc-s1; } > "$OUT/fontes/pacotes-de-compilacao.txt"

git clone --quiet --depth 1 --branch "$BOX64_TAG" https://github.com/ptitSeb/box64 "$WORK/box64-src"
[[ "$(git -C "$WORK/box64-src" rev-parse HEAD)" == 2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a ]]
# Limit address reuse to the Bedrock Box64 process when RuntimeCommand opts in.
# This does not enable SO_REUSEPORT and does not change UDP sockets.
mkdir -p "$OUT/fontes/native-box64"
cp "$ROOT/mobile/native-box64/box64-tcp-reuse.patch" "$ROOT/mobile/native-box64/bloco-tcp-reuse.h" \
  "$OUT/fontes/native-box64/"
install -m 0644 "$ROOT/mobile/native-box64/bloco-tcp-reuse.h" "$WORK/box64-src/src/wrapped/"
git -C "$WORK/box64-src" apply --unidiff-zero "$ROOT/mobile/native-box64/box64-tcp-reuse.patch"
cmake -S "$WORK/box64-src" -B "$WORK/box64-build" -DARM_DYNAREC=ON -DCMAKE_BUILD_TYPE=Release > /dev/null
if ! cmake --build "$WORK/box64-build" -j"$(nproc)" >"$WORK/box64.log" 2>&1; then
  tail -100 "$WORK/box64.log"; exit 1
fi
# Test the native helper and, separately, the x86 guest's actual bind calls
# through the freshly patched ARM64 Box64. Both baseline and opt-in must pass.
cc -std=c11 -O2 -Wall -Wextra -Werror -DBLOCO_TEST_HELPER -I"$ROOT/mobile/native-box64" \
  "$ROOT/mobile/test/tcp-restart-test.c" -o "$WORK/tcp-native-test"
"$WORK/tcp-native-test" baseline
"$WORK/tcp-native-test" enabled
x86_64-linux-gnu-gcc -std=c11 -O2 -Wall -Wextra -Werror \
  "$ROOT/mobile/test/tcp-restart-test.c" -o "$WORK/tcp-guest-test"
env -u BLOCO_REUSE_TCP BOX64_LOG=0 "$WORK/box64-build/box64" "$WORK/tcp-guest-test" baseline
env -u BLOCO_REUSE_TCP BOX64_LOG=0 "$WORK/box64-build/box64" "$WORK/tcp-guest-test" enabled

curl --fail --location --retry 3 -o "$WORK/base.tar.gz" \
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${BASE}-base-arm64.tar.gz"
echo "$BASE_HASH  $WORK/base.tar.gz" | sha256sum -c -
mkdir -p "$WORK/tree/rootfs" && tar -xzf "$WORK/base.tar.gz" -C "$WORK/tree/rootfs"
cp "$WORK/tree/rootfs/var/lib/dpkg/status" "$OUT/fontes/pacotes-ubuntu-base.txt"
mkdir -p "$WORK/tree/rootfs/opt/box64"
install -m 0755 "$WORK/box64-build/box64" "$WORK/tree/rootfs/opt/box64/box64"
install -m 0755 "$WORK/proot" "$WORK/tree/proot"
rm -f "$WORK/tree/rootfs/etc/resolv.conf"
# The Android service fills this with DNS addresses from the active network.
# Do not silently send DNS queries to an unrelated public resolver.
printf '# DNS configurado pela rede ativa do Android\n' > "$WORK/tree/rootfs/etc/resolv.conf"

# Ubuntu Base does not include a CA bundle. Without one the guest cannot
# verify HTTPS endpoints required by online mode. Use a pinned, unmodified
# Mozilla-derived PEM bundle and ship its MPL notice and source form.
CERTIFI_VERSION=2026.10.23
CERTIFI_WHEEL_SHA256=e9b54de76e530bb6cf0ccd036787103acb58fd42ba930ea8bf948ddbaa2f61d1
CERTIFI_PEM_SHA256=2ef52ad56b4803e264045c915e55c236cbfa045b84b81efefc218a19eb44bd2b
curl --fail --location --retry 3 -o "$WORK/certifi.whl" \
  'https://files.pythonhosted.org/packages/32/d1/a3652fe2c19134d0d15922177821d863cbd7e842d53a21bbe7e8e3a1b5ac/certifi-2026.10.23-py3-none-any.whl'
echo "$CERTIFI_WHEEL_SHA256  $WORK/certifi.whl" | sha256sum -c -
mkdir -p "$WORK/tree/rootfs/etc/ssl/certs"
unzip -p "$WORK/certifi.whl" certifi/cacert.pem > "$WORK/tree/rootfs/etc/ssl/certs/ca-certificates.crt"
echo "$CERTIFI_PEM_SHA256  $WORK/tree/rootfs/etc/ssl/certs/ca-certificates.crt" | sha256sum -c -
cp "$WORK/tree/rootfs/etc/ssl/certs/ca-certificates.crt" "$OUT/fontes/certifi-$CERTIFI_VERSION-cacert.pem"

curl --fail --location --retry 3 -o "$WORK/x86libs.tar.gz" \
  "https://github.com/ptitSeb/box64/releases/download/${BOX64_TAG}/box64-bundle-x86-libs-${BOX64_TAG}.tar.gz"
echo "$LIBS_HASH  $WORK/x86libs.tar.gz" | sha256sum -c -
tar -xzf "$WORK/x86libs.tar.gz" -C "$WORK/tree/rootfs"
rm -rf "$WORK/tree/rootfs/usr/lib/box64-i386-linux-gnu"
# The official Bedrock server needs these non-wrapped x86-64 C++ runtime
# libraries. Do not distribute the bundle's unrelated desktop/audio libraries.
find "$WORK/tree/rootfs/usr/lib/box64-x86_64-linux-gnu" -mindepth 1 -maxdepth 1 \
  ! -name 'libstdc++.so.6*' ! -name 'libgcc_s*' -exec rm -rf -- {} +
# AlmaLinux's libgcc_s.so.1 points to a versioned libgcc_s-11-*.so.1;
# retain both the link and its target.
ldd "$WORK/box64-build/box64" | awk '/=> \//{print $3}' | while read -r so; do
  real="$(readlink -f "$so")"
  if [ ! -e "$WORK/tree/rootfs$real" ]; then
    install -D -m 0755 "$real" "$WORK/tree/rootfs$real"
    dpkg-query -S "$real" >> "$OUT/fontes/bibliotecas-adicionais.txt" || true
  fi
done

# Keep the execution libraries, shell, configuration, certificates and license
# notices, but omit package managers and unrelated Ubuntu command-line tools.
ROOTFS="$WORK/tree/rootfs"
find "$ROOTFS/usr/bin" -mindepth 1 -maxdepth 1 ! -name dash ! -name sh -exec rm -rf -- {} +
rm -rf "$ROOTFS/usr/sbin" "$ROOTFS/usr/libexec" "$ROOTFS/usr/include" "$ROOTFS/usr/local" \
  "$ROOTFS/usr/lib/apt" "$ROOTFS/usr/lib/dpkg" "$ROOTFS/usr/lib/systemd" "$ROOTFS/usr/lib/udev" \
  "$ROOTFS/var" "$ROOTFS/root"
if [[ -d "$ROOTFS/usr/share" ]]; then
  find "$ROOTFS/usr/share" -mindepth 1 -maxdepth 1 ! -name doc ! -name common-licenses ! -name ca-certificates ! -name zoneinfo -exec rm -rf -- {} +
fi
mkdir -p "$ROOTFS/var" "$ROOTFS/root" "$ROOTFS/tmp" "$ROOTFS/srv" "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
# Exercise the same x86 socket regression through the shipped PRoot + rootfs,
# including the limited inherited seccomp simulation used by the Bedrock test.
install -m 0755 "$WORK/tcp-guest-test" "$ROOTFS/tmp/bloco-tcp-test"
run_guest_test() {
  env -u BLOCO_REUSE_TCP HOME="$WORK" TMPDIR="$WORK" PROOT_TMP_DIR="$WORK" \
    PROOT_NO_SECCOMP=1 GLIBC_TUNABLES=glibc.pthread.rseq=0 BOX64_LOG=0 "$@"
}
PROOT_ARGS=(--kill-on-exit -r "$ROOTFS" -b /dev -b /proc -b /sys -w /tmp /opt/box64/box64 ./bloco-tcp-test)
run_guest_test "$WORK/proot" "${PROOT_ARGS[@]}" baseline
run_guest_test "$WORK/proot" "${PROOT_ARGS[@]}" enabled
run_guest_test python3 "$ROOT/mobile/test/seccomp-exec.py" "$WORK/proot" "${PROOT_ARGS[@]}" enabled
rm -f "$ROOTFS/tmp/bloco-tcp-test"

# Store links separately, creating them after extracting files avoids archive
# traversal through links and works with Android's ZipInputStream.
python3 - "$WORK/tree" "$OUT/runtime.zip" <<'PY'
import json, os, stat, sys, zipfile
root, out = sys.argv[1:]
links, executable = [], []
with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as z:
    for base, dirs, files in os.walk(root, followlinks=False):
        dirs.sort(); files.sort()
        for name in dirs[:]:
            path = os.path.join(base, name); rel = os.path.relpath(path, root).replace(os.sep, '/')
            if os.path.islink(path):
                links.append([rel, os.readlink(path)]); dirs.remove(name)
            else:
                z.writestr(rel + '/', b'')
        for name in files:
            path = os.path.join(base, name); rel = os.path.relpath(path, root).replace(os.sep, '/')
            s = os.lstat(path)
            if stat.S_ISLNK(s.st_mode):
                links.append([rel, os.readlink(path)])
            elif stat.S_ISREG(s.st_mode):
                z.write(path, rel)
                if s.st_mode & 0o111: executable.append(rel)
    meta = {'format': 1, 'base': 'ubuntu-base-24.04.5-arm64',
            'proot': 'termux-v5.1.107.102-musl-static-pie', 'box64': 'v0.4.4',
            'box64-tcp-reuse': 1, 'links': links, 'executable': executable}
    z.writestr('runtime-manifest.json', json.dumps(meta, separators=(',', ':')))
print('Runtime entries: %s links, %s executables' % (len(links), len(executable)))
print('Runtime ZIP size: %.1f MB' % (os.path.getsize(out) / 1_000_000))
PY
unzip -tq "$OUT/runtime.zip"
sha256sum "$OUT/runtime.zip"
