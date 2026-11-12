#!/usr/bin/env bash
# Build a self-contained ARM64 translation environment, without Mojang files.
# Adapted from Pondok's MIT-licensed build_runtime_pack.sh (lunarjjo/pondok).
set -euo pipefail
[[ "$(uname -m)" == aarch64 ]] || { echo 'Este build precisa de Linux arm64.' >&2; exit 1; }
OUT="$(realpath -m "${1:-.mobile-runtime}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

PROOT_TAG=v5.5.0
BOX64_TAG=v0.4.4
BASE=24.04.5
BASE_HASH=a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
LIBS_HASH=1d4a8787a8a92267c1be673ec194c69e3bb33d14c800575d94131084142fcff0

sudo apt-get update -qq
sudo apt-get install -y -qq build-essential cmake pkg-config libtalloc-dev uthash-dev > /dev/null

git clone --quiet --depth 1 --recurse-submodules --branch "$PROOT_TAG" https://github.com/proot-me/proot "$WORK/proot-src"
if ! LDFLAGS=-static make -C "$WORK/proot-src/src" -j"$(nproc)" GIT=false WITHOUT_PYTHON=1 >"$WORK/proot.log" 2>&1; then
  tail -100 "$WORK/proot.log"; exit 1
fi
file "$WORK/proot-src/src/proot" | grep -q 'statically linked'

git clone --quiet --depth 1 --branch "$BOX64_TAG" https://github.com/ptitSeb/box64 "$WORK/box64-src"
cmake -S "$WORK/box64-src" -B "$WORK/box64-build" -DARM_DYNAREC=ON -DCMAKE_BUILD_TYPE=Release > /dev/null
if ! cmake --build "$WORK/box64-build" -j"$(nproc)" >"$WORK/box64.log" 2>&1; then
  tail -100 "$WORK/box64.log"; exit 1
fi

curl --fail --location --retry 3 -o "$WORK/base.tar.gz" \
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${BASE}-base-arm64.tar.gz"
echo "$BASE_HASH  $WORK/base.tar.gz" | sha256sum -c -
mkdir -p "$WORK/tree/rootfs" && tar -xzf "$WORK/base.tar.gz" -C "$WORK/tree/rootfs"
mkdir -p "$WORK/tree/rootfs/opt/box64"
install -m 0755 "$WORK/box64-build/box64" "$WORK/tree/rootfs/opt/box64/box64"
install -m 0755 "$WORK/proot-src/src/proot" "$WORK/tree/proot"
rm -f "$WORK/tree/rootfs/etc/resolv.conf"
printf 'nameserver 1.1.1.1\n' > "$WORK/tree/rootfs/etc/resolv.conf"

curl --fail --location --retry 3 -o "$WORK/x86libs.tar.gz" \
  "https://github.com/ptitSeb/box64/releases/download/${BOX64_TAG}/box64-bundle-x86-libs-${BOX64_TAG}.tar.gz"
echo "$LIBS_HASH  $WORK/x86libs.tar.gz" | sha256sum -c -
tar -xzf "$WORK/x86libs.tar.gz" -C "$WORK/tree/rootfs"
rm -rf "$WORK/tree/rootfs/usr/lib/box64-i386-linux-gnu"
ldd "$WORK/box64-build/box64" | awk '/=> \//{print $3}' | while read -r so; do
  real="$(readlink -f "$so")"
  if [ ! -e "$WORK/tree/rootfs$real" ]; then install -D -m 0755 "$real" "$WORK/tree/rootfs$real"; fi
done

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
            'proot': 'v5.5.0', 'box64': 'v0.4.4',
            'links': links, 'executable': executable}
    z.writestr('runtime-manifest.json', json.dumps(meta, separators=(',', ':')))
print('Runtime entries: %s links, %s executables' % (len(links), len(executable)))
print('Runtime ZIP size: %.1f MB' % (os.path.getsize(out) / 1_000_000))
PY
unzip -tq "$OUT/runtime.zip"
sha256sum "$OUT/runtime.zip"
