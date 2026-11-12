#!/usr/bin/env python3
"""Build a standalone, static-PIE Termux PRoot for Bloco.

Adapted from OpenCodePhone's MIT-licensed tools/build_proot.py. The Android
application seccomp policy may kill a glibc-linked tracer before it can handle
its guest. This builds the Android-aware Termux fork with musl instead. It does
not require an installed Termux, root, or a change to Android's sandbox.

Usage: build-mobile-proot.py --abi arm64-v8a --output /path/to/proot
       [--sources-dir /path/to/legal-sources] [--zig /path/to/zig]
Scratch files are kept in a temporary directory unless --work is specified.
"""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile

SUPPORT = Path(__file__).resolve().parent / "native-proot"
PROOT_COMMIT = "52d65a4dc140ca554d241a1438ddd4f6b00c9813"  # v5.1.107.102
STATIC_COMMIT = "aa7ec720453edd8245476c6e1e61977f7c30e95c"
TALLOC_SHA256 = "3e29ce6c3ba3c4f7c2d57ce8cf0fbc24c86618c519f2b2fb6a459025488b6174"
ZIG_RELEASES = {
    "x86_64": ("x64", "I4csxVyW7ck+iwkt7xHeI+0NmHFV2yYEhu/mAKyeJU5TUvf36OH3Z8oUsosQokPMQw0kQbQDcUuajXi90Wsulg=="),
    "aarch64": ("arm64", "ct0AZx/knfNZBc2BZjCS0iPAKPJZzvHMPDiq6e2UrMvQoLH/+d5fjqYS5fYovqSq8MQSyJalqybTP5cLizturQ=="),
}
TARGETS = {
    "arm64-v8a": ("aarch64-linux-musl", "arm-linux-musleabihf", "aarch64", "0x5400000000", "0x20000000"),
    # For native Linux seccomp regression tests only. Not included in the APK.
    "x86_64": ("x86_64-linux-musl", "x86-linux-musl", "x86_64", "0x600000000000", "0xa0000000"),
}


def run(args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def checkout(repository, commit, dest):
    if not dest.exists():
        run(["git", "init", "--quiet", dest])
        run(["git", "-C", dest, "fetch", "--quiet", "--depth", "1",
             f"https://github.com/{repository}.git", commit])
        run(["git", "-C", dest, "checkout", "--quiet", "--detach", "FETCH_HEAD"])
    actual = subprocess.check_output(["git", "-C", dest, "rev-parse", "HEAD"], text=True).strip()
    if actual != commit:
        raise RuntimeError(f"Revisão inesperada: {dest}: {actual}")


def verify(path, algorithm, expected):
    h = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    if h.digest() != expected:
        raise RuntimeError(f"Hash incorreto: {path}")


def executable(path, text):
    path.write_text(text)
    path.chmod(0o755)


def get_zig(work):
    machine = platform.machine().lower()
    if machine not in ZIG_RELEASES:
        raise RuntimeError(f"Compilador não disponível para {machine}")
    suffix, integrity = ZIG_RELEASES[machine]
    package = work / "zig-package"
    zig = package / "package/zig"
    if not zig.exists():
        package.mkdir(parents=True, exist_ok=True)
        archive = work / f"zig-linux-{suffix}-0.13.0.tgz"
        url = f"https://registry.npmjs.org/@ryoppippi/zig-linux-{suffix}/-/zig-linux-{suffix}-0.13.0.tgz"
        print(f"Obtendo Zig 0.13.0 para {machine}...", flush=True)
        if not archive.exists():
            subprocess.run(["curl", "--fail", "--location", "--retry", "3", "-o", str(archive), url], check=True)
        verify(archive, "sha512", base64.b64decode(integrity))
        # Only extract the pinned, hash-verified archive.
        with tarfile.open(archive) as tar:
            tar.extractall(package)
    return zig.resolve()


def archive_sources(source, talloc_archive, zig, dest):
    dest.mkdir(parents=True, exist_ok=True)
    proot_archive = dest / "proot-termux-5.1.107.102.tar.gz"
    with proot_archive.open("wb") as stream:
        run(["git", "-C", source, "archive", "--format=tar.gz", "--prefix=proot-termux/", "HEAD"], stdout=stream)
    shutil.copy2(talloc_archive, dest / "talloc-2.1.1.tar.gz")
    # Exact musl headers, sources and Zig compiler-rt used for static linking.
    # The separately distributed, hash-verified Zig compiler provides the build tools.
    ziglib = zig.parent / "lib"
    with tarfile.open(dest / "musl-e-compiler-rt-zig-0.13.0.tar.gz", "w:gz") as tar:
        for rel in ("libc/musl", "libc/include/generic-musl", "libc/include/aarch64-linux-musl",
                    "libc/include/arm-linux-musl", "libc/include/arm-linux-any",
                    "libc/include/aarch64-linux-any", "libc/include/any-linux-any", "compiler_rt"):
            tar.add(ziglib / rel, arcname="zig-lib/" + rel)
    # Preserve the exact locally modified musl fork source, not only the original.
    original_fork = (ziglib / "libc/musl/src/process/_Fork.c").read_text()
    (dest / "musl-Fork-modificado.c").write_text(
        original_fork.replace("#ifdef SYS_fork", "#if 0 /* use Android-allowed clone */"))
    shutil.copy2(Path(__file__), dest / "compilar-proot.py")
    shutil.copytree(SUPPORT, dest / "native-proot", dirs_exist_ok=True)


def build(abi, zig, source, talloc, work, output):
    target, target32, arch, address, address32 = TARGETS[abi]
    obj = work / f"build-{abi}"
    if obj.exists():
        shutil.rmtree(obj)
    obj.mkdir(parents=True)
    src = obj / "src"
    shutil.copytree(source / "src", src)
    for patch in ("zig-loader.patch", "android-seccomp-log.patch", "android-shutdown.patch"):
        run(["patch", "-p1", "-i", SUPPORT / patch], cwd=obj)
    # The guest loader is freestanding and must map at PRoot's fixed address.
    # The tracer itself is a separate static PIE, not this fixed-address loader.
    for suffix, addr in [("", address), ("-m32", address32)]:
        (src / f"loader{suffix}.ld").write_text(
            "ENTRY(_start)\n"
            "PHDRS { text PT_LOAD FLAGS(5); data PT_LOAD FLAGS(6); "
            "stack PT_GNU_STACK FLAGS(6); }\nSECTIONS {\n"
            f" . = {addr};\n .text : {{ *(.text*) }} :text\n"
            " .rodata : { *(.rodata*) } :text\n"
            " .ARM.extab : { *(.ARM.extab*) } :text\n"
            " .ARM.exidx : { *(.ARM.exidx*) } :text\n"
            " . = ALIGN(0x4000);\n .data : { *(.data*) *(.got*) } :data\n"
            " .bss : { *(.bss*) *(COMMON) } :data\n"
            " /DISCARD/ : { *(.eh_frame*) *(.comment) }\n}\n")
    cc = obj / "cc"
    executable(cc, f'''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
target = {target!r}
if '-m32' in args:
    args.remove('-m32')
    target = {target32!r}
os.execv({str(zig)!r}, ['zig', 'cc', '-target', target, *args])
''')
    # The source makefile uses GNU objcopy to embed the loader. Assembly
    # .incbin produces the same symbols even during cross-compilation.
    embed = obj / "embed"
    executable(embed, f'''#!/usr/bin/env python3
import hashlib, pathlib, subprocess, sys
src, dst = sys.argv[-2:]
sym = src.replace('.', '_').replace('-', '_').replace('/', '_')
asm = pathlib.Path(dst + '.S')
asm.write_text('/* ' + hashlib.sha256(pathlib.Path(src).read_bytes()).hexdigest() + ' */\\n'
    + '.section .rodata\\n.global _binary_' + sym + '_start\\n.global _binary_' + sym + '_end\\n'
    + '_binary_' + sym + '_start:\\n.incbin "' + src + '"\\n_binary_' + sym + '_end:\\n'
    + '.section .note.GNU-stack,"",%progbits\\n')
subprocess.run([{str(zig)!r}, 'cc', '-target', {target!r}, '-c', str(asm), '-o', dst], check=True)
''')
    strip = obj / "strip"
    executable(strip, f'''#!/usr/bin/env python3
import os, subprocess, sys
path = sys.argv[1]
subprocess.run([{str(zig)!r}, 'objcopy', '--strip-all', path, path + '.stripped'], check=True)
os.replace(path + '.stripped', path)
''')
    info = obj / "loader-info"
    executable(info, '''#!/usr/bin/env python3
import subprocess, sys
symbols = subprocess.check_output(['readelf', '-s', sys.argv[1]], text=True)
values = {}
for line in symbols.splitlines():
    fields = line.split()
    if fields and fields[-1] in ('_start', 'pokedata_workaround'):
        values[fields[-1]] = int(fields[1], 16)
print('#include <unistd.h>')
print('const ssize_t offset_to_pokedata_workaround=%d;' %
      (values['pokedata_workaround'] - values['_start']))
''')
    makefile = src / "GNUmakefile"
    content = makefile.read_text()
    before = "readelf -s $< | awk -f loader/loader-info.awk > $@"
    if content.count(before) != 1:
        raise RuntimeError("Makefile do PRoot inesperado")
    makefile.write_text(content.replace(before, f"{info} $< > $@"))
    run([zig, "cc", "-target", target, "-O2", "-fPIE", "-c", talloc / "talloc.c",
         f"-I{talloc}", "-o", obj / "talloc.o"])
    run([zig, "ar", "rcs", obj / "libtalloc.a", obj / "talloc.o"])
    version_script = obj / "version"
    version_text = f'#undef VERSION\n#define VERSION "termux-5.1.107.102-bloco"'
    executable(version_script, f"#!/usr/bin/env python3\nprint({version_text!r})\n")
    # On x86_64 Android may trap fork(). Keep musl's fork preparation and use
    # its existing clone() implementation. ARM64 already uses clone().
    musl = zig.parent / "lib/libc/musl"
    fork_source = obj / "musl-Fork.c"
    text = (musl / "src/process/_Fork.c").read_text()
    if text.count("#ifdef SYS_fork") != 1:
        raise RuntimeError("Fonte musl _Fork inesperada")
    fork_source.write_text(text.replace("#ifdef SYS_fork", "#if 0 /* use Android-allowed clone */"))
    shutil.copy2(fork_source, src / "bloco-musl-Fork.c")
    run([zig, "cc", "-target", target, "-O2", "-fPIE", "-c", fork_source,
         f"-I{musl}/src/internal", f"-I{musl}/arch/{arch}",
         f"-I{musl}/arch/generic", f"-I{musl}/src/include", "-o", obj / "musl-Fork.o"])
    flags = (f"-O2 -fPIE -D_LARGEFILE64_SOURCE -include {SUPPORT}/musl-compat.h "
             f"-I{talloc} -I{SUPPORT} -fno-ident -ffile-prefix-map={work}=/build")
    run(["make", "-j4", "proot", f"CC={cc}", f"STRIP={strip}", f"OBJCOPY={embed}",
         "OBJDUMP=objdump", f"CFLAGS={flags}",
         f"LDFLAGS=-static -fPIE -pie {obj}/musl-Fork.o -L{obj} -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384",
         f"CHECK_VERSION={version_script}"], cwd=src)
    output.parent.mkdir(parents=True, exist_ok=True)
    run([zig, "objcopy", "--strip-all", src / "proot", output])
    output.chmod(0o755)
    print(f"PRoot {abi}: {hashlib.sha256(output.read_bytes()).hexdigest()}", flush=True)


def main(work, args):
    work.mkdir(parents=True, exist_ok=True)
    zig = args.zig.resolve() if args.zig else get_zig(work)
    if subprocess.check_output([zig, "version"], text=True).strip() != "0.13.0":
        raise RuntimeError("É necessário Zig 0.13.0")
    source, static = work / "proot-source", work / "static-source"
    checkout("termux/proot", PROOT_COMMIT, source)
    checkout("proot-me/proot-static-build", STATIC_COMMIT, static)
    archive = static / "packages/talloc-2.1.1.tar.gz"
    verify(archive, "sha256", bytes.fromhex(TALLOC_SHA256))
    talloc = work / "talloc-2.1.1"
    if not talloc.is_dir():
        with tarfile.open(archive) as tar:
            tar.extractall(work)  # verified, pinned source archive
    shutil.copy2(SUPPORT / "talloc-replace.h", talloc / "replace.h")
    build(args.abi, zig, source, talloc, work, args.output.resolve())
    if args.sources_dir:
        dest = args.sources_dir.resolve()
        archive_sources(source, archive, zig, dest)
        # Relinkable application objects, including both embedded loaders and
        # the changed musl fork object, for LGPL talloc static-link compliance.
        objects = work / f"build-{args.abi}"
        with tarfile.open(dest / f"proot-objetos-religaveis-{args.abi}.tar.gz", "w:gz") as tar:
            for obj in sorted((objects / "src").rglob("*.o")):
                tar.add(obj, arcname="objetos/" + str(obj.relative_to(objects / "src")))
            tar.add(objects / "musl-Fork.o", arcname="objetos/musl-Fork.o")
            tar.add(objects / "musl-Fork.c", arcname="musl-Fork.c")
        (dest / "RELIGAR-TALLOC.txt").write_text(
            "Os objetos ARM64 de PRoot e os loaders estão em proot-objetos-religaveis-arm64-v8a.tar.gz.\n"
            "Com Zig 0.13.0 para Linux e uma biblioteca libtalloc.a compatível, extraia os objetos e rode:\n"
            "zig cc -target aarch64-linux-musl -static -fPIE -pie $(find objetos -name '*.o' ! -name 'loader.o' ! -name 'loader-m32.o' ! -name 'assembly.o' ! -name 'assembly-m32.o') -L<diretorio-da-biblioteca> -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384 -o proot\n"
            "Para reconstruir desde os fontes e conferir os comandos de compilação, execute compilar-proot.py.\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=TARGETS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sources-dir", type=Path)
    parser.add_argument("--zig", type=Path, help="compilador Zig 0.13.0 já instalado")
    parser.add_argument("--work", type=Path, help="manter arquivos temporários nesta pasta")
    args = parser.parse_args()
    if args.work:
        main(args.work.resolve(), args)
    else:
        with tempfile.TemporaryDirectory(prefix="bloco-proot-") as temp:
            main(Path(temp), args)
