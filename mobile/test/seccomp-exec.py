#!/usr/bin/env python3
"""CI-only: run a child with a simulated inherited Android-like filter.

Model selected syscall traps and, optionally, denial of AF_NETLINK sockets in
one filter before exec. This is not an Android SELinux or OEM policy emulator.
"""
import ctypes
import errno
import os
import platform
import signal
import subprocess
import sys


def install_filter(deny_netlink):
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

    class Program(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]

    libc = ctypes.CDLL(None, use_errno=True)
    # If the Java test is forcibly stopped, do not leave its tracer running.
    if libc.prctl(1, signal.SIGKILL, 0, 0, 0) or os.getppid() == 1:
        os._exit(120)
    arch = platform.machine()
    numbers = {'aarch64': (99, 293, 435), 'arm64': (99, 293, 435),
               'x86_64': (273, 334, 435)}  # set_robust_list, rseq, clone3
    instructions = [Filter(0x20, 0, 0, 0)]
    for number in numbers[arch]:
        instructions += [Filter(0x15, 0, 1, number), Filter(0x06, 0, 0, 0x00030000)]
    if deny_netlink:
        socket_number = 41 if arch == 'x86_64' else 198
        instructions += [Filter(0x15, 0, 3, socket_number),
                         Filter(0x20, 0, 0, 16),  # first argument (domain)
                         Filter(0x15, 0, 1, 16),  # AF_NETLINK
                         Filter(0x06, 0, 0, 0x00050000 | errno.EACCES)]
    instructions.append(Filter(0x06, 0, 0, 0x7fff0000))
    filters = (Filter * len(instructions))(*instructions)
    program = Program(len(filters), filters)
    if libc.prctl(38, 1, 0, 0, 0) or libc.prctl(22, 2, ctypes.byref(program), 0, 0):
        os._exit(120)


def main():
    args = sys.argv[1:]
    deny_netlink = bool(args and args[0] == '--deny-netlink')
    if deny_netlink:
        args = args[1:]
    if platform.machine() not in ('aarch64', 'arm64', 'x86_64') or not args:
        raise SystemExit('O teste de filtro requer Linux ARM64/x86-64 e um comando.')
    result = subprocess.run(args, preexec_fn=lambda: install_filter(deny_netlink))
    raise SystemExit(result.returncode if result.returncode >= 0 else 128 - result.returncode)


if __name__ == '__main__':
    main()
