#!/usr/bin/env python3
"""Test-only seccomp simulation: deny creation of AF_NETLINK sockets.

This is a deliberately stronger/simpler restriction than particular Android
SELinux policies. It does not emulate an OEM, all netlink rules, or a phone.
The filter is inherited by both PRoot and its guests, but permits IPv4 ioctls.
"""
import ctypes
import errno
import os
import platform
import sys


class Filter(ctypes.Structure):
    _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]


class Program(ctypes.Structure):
    _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]


def main():
    arch = platform.machine()
    numbers = {'x86_64': 41, 'aarch64': 198, 'arm64': 198}
    if arch not in numbers or len(sys.argv) < 2:
        raise SystemExit('Expected Linux x86-64/ARM64 and a command')
    # seccomp_data.nr is at offset 0 and args[0] (socket domain) at 16.
    rules = [
        Filter(0x20, 0, 0, 0),
        Filter(0x15, 0, 3, numbers[arch]),
        Filter(0x20, 0, 0, 16),
        Filter(0x15, 0, 1, 16),  # AF_NETLINK
        Filter(0x06, 0, 0, 0x00050000 | errno.EACCES),
        Filter(0x06, 0, 0, 0x7fff0000),  # ALLOW
    ]
    storage = (Filter * len(rules))(*rules)
    program = Program(len(storage), storage)
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) or libc.prctl(22, 2, ctypes.byref(program), 0, 0):
        raise OSError(ctypes.get_errno(), 'could not apply test seccomp filter')
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == '__main__':
    main()
