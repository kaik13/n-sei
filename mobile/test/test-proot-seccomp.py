#!/usr/bin/env python3
"""Regression test for Bloco's actual musl PRoot under inherited filters.

Based on OpenCodePhone's MIT-licensed tools/test_proot_seccomp.py. Models
SECCOMP_RET_TRAP on selected Android-like syscalls, not OEM policy; no root
needed. Execute on native Linux after building PRoot for the host architecture.
"""
import ctypes
import os
from pathlib import Path
import platform
import select
import signal
import struct
import subprocess
import sys
import tempfile
import unittest

PROOT = Path(sys.argv.pop(1)).resolve()
MACHINE = platform.machine()
NUMBERS = {
    'x86_64': (57, 273, 334, 435),  # fork, set_robust_list, rseq, clone3
    'aarch64': (99, 293, 435),      # set_robust_list, rseq, clone3; no fork syscall
}

class SockFilter(ctypes.Structure):
    _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

class SockFprog(ctypes.Structure):
    _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(SockFilter))]

def install_filter():
    instructions = [SockFilter(0x20, 0, 0, 0)]
    for number in NUMBERS[MACHINE]:
        instructions += [SockFilter(0x15, 0, 1, number), SockFilter(0x06, 0, 0, 0x00030000)]
    instructions.append(SockFilter(0x06, 0, 0, 0x7fff0000))
    filters = (SockFilter * len(instructions))(*instructions)
    program = SockFprog(len(filters), filters)
    libc = ctypes.CDLL(None, use_errno=True)
    if (libc.prctl(38, 1, 0, 0, 0) != 0 or
            libc.prctl(22, 2, ctypes.byref(program), 0, 0) != 0):
        os._exit(120)

def env(tmp, tunables='glibc.pthread.rseq=0'):
    return dict(os.environ, HOME=tmp, TMPDIR=tmp, PROOT_TMP_DIR=tmp,
                PROOT_NO_SECCOMP='1', GLIBC_TUNABLES=tunables)

def command(tmp, *guest):
    return [str(PROOT), '--kill-on-exit', '-0', '-r', '/', '-w', '/tmp', *guest]

class SeccompTest(unittest.TestCase):
    def test_static_pie_elf(self):
        data = PROOT.read_bytes()
        self.assertEqual(b'\x7fELF\x02\x01', data[:6])
        hdr = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
        self.assertEqual(3, hdr[1], 'tracer must be PIE')
        self.assertEqual({'x86_64': 62, 'aarch64': 183}[MACHINE], hdr[2])
        for i in range(hdr[10]):
            ph = struct.unpack_from('<IIQQQQQQ', data, hdr[5] + i * hdr[9])
            self.assertNotEqual(3, ph[0], 'no interpreter: static PIE')
            if ph[0] == 1:
                self.assertEqual(ph[2] % 16384, ph[3] % 16384)
            if ph[0] == 0x6474e551:
                self.assertEqual(0, ph[1] & 1, 'non-executable stack')

    def test_filter_blocks_untraced_glibc(self):
        proc = subprocess.run(['/bin/true'], env=dict(os.environ, GLIBC_TUNABLES='glibc.pthread.rseq=0'),
                              preexec_fn=install_filter, capture_output=True, timeout=15)
        self.assertEqual(-signal.SIGSYS, proc.returncode, proc.stderr)

    def run_guest(self, *guest, tunables='glibc.pthread.rseq=0'):
        with tempfile.TemporaryDirectory(prefix='bloco-seccomp-') as tmp:
            proc = subprocess.run(command(tmp, *guest), env=env(tmp, tunables),
                                  preexec_fn=install_filter, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(120, proc.returncode, 'seccomp filter could not be installed')
            self.assertEqual(0, proc.returncode, proc.stdout + '\n' + proc.stderr)
            return proc

    def test_guest_and_diagnostic(self):
        proc = self.run_guest('/bin/sh', '-c', '/bin/true && /bin/echo guest-ok', tunables='glibc.pthread.rseq=1')
        self.assertIn('guest-ok', proc.stdout)
        robust = 273 if MACHINE == 'x86_64' else 99
        self.assertIn('Android/seccomp: chamada %d ' % robust, proc.stderr)
        # Frequent traps should be reported once rather than filling the app console.
        self.assertEqual(1, proc.stderr.count('Android/seccomp: chamada %d ' % robust))

    def test_threads_and_subprocesses(self):
        proc = self.run_guest('/usr/bin/python3', '-c',
            "import threading,subprocess; t=threading.Thread(target=lambda:print('thread-ok')); "
            "t.start(); t.join(); subprocess.run(['/bin/true'],check=True)")
        self.assertIn('thread-ok', proc.stdout)

    def test_sigterm_reaps_child(self):
        with tempfile.TemporaryDirectory(prefix='bloco-proot-stop-') as tmp:
            proc = subprocess.Popen(command(tmp, '/bin/sh', '-c', 'echo ready; exec /bin/sleep 30'),
                env=env(tmp), preexec_fn=install_filter, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            children = []
            try:
                self.assertTrue(select.select([proc.stdout], [], [], 10)[0], 'guest did not start')
                self.assertEqual('ready', proc.stdout.readline().strip())
                path = Path(f'/proc/{proc.pid}/task/{proc.pid}/children')
                children = [int(pid) for pid in path.read_text().split()]
                self.assertTrue(children)
                proc.terminate(); proc.wait(timeout=10)
                for pid in children:
                    self.assertFalse(Path(f'/proc/{pid}').exists(), f'guest orphan {pid}')
            finally:
                if proc.poll() is None:
                    proc.kill(); proc.wait()
                for pid in children:
                    try: os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                proc.stdout.close(); proc.stderr.close()

if __name__ == '__main__':
    if MACHINE not in NUMBERS or platform.system() != 'Linux':
        raise SystemExit('Linux x86_64 ou aarch64 nativo é necessário')
    unittest.main(verbosity=2)
