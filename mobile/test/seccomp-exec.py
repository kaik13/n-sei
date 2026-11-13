#!/usr/bin/env python3
"""CI-only: run a child with a simulated inherited Android-like ARM64 filter.

The filter is deliberately limited to three known syscalls. It does not model
all Android versions, SELinux, or a particular manufacturer's policies.
"""
import ctypes
import os
import platform
import signal
import subprocess
import sys


def install_filter():
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

    class Program(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]

    libc = ctypes.CDLL(None, use_errno=True)
    # If the Java test is forcibly stopped, do not leave its tracer running.
    if libc.prctl(1, signal.SIGKILL, 0, 0, 0) or os.getppid() == 1:
        os._exit(120)
    instructions = [Filter(0x20, 0, 0, 0)]
    for number in (99, 293, 435):  # set_robust_list, rseq, clone3
        instructions += [Filter(0x15, 0, 1, number), Filter(0x06, 0, 0, 0x00030000)]
    instructions.append(Filter(0x06, 0, 0, 0x7fff0000))
    filters = (Filter * len(instructions))(*instructions)
    program = Program(len(filters), filters)
    if libc.prctl(38, 1, 0, 0, 0) or libc.prctl(22, 2, ctypes.byref(program), 0, 0):
        os._exit(120)


def main():
    if platform.machine() not in ('aarch64', 'arm64') or len(sys.argv) < 2:
        raise SystemExit('O teste de filtro requer ARM64 e um comando.')
    result = subprocess.run(sys.argv[1:], preexec_fn=install_filter)
    raise SystemExit(result.returncode if result.returncode >= 0 else 128 - result.returncode)


if __name__ == '__main__':
    main()
