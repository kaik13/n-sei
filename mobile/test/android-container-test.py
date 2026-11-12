#!/usr/bin/env python3
"""Test the release APK and a debuggable diagnostic on ARM64 Android in CI.

The container's Android SELinux is disabled and root is used only to control
preferences/commands. The service and Bedrock process run as the app UID.
This is NOT a physical-device, enforced-SELinux, or 10-player test.
"""
import os
import socket
import struct
import subprocess
import sys
import time

ADB = os.environ.get('ADB', 'adb')
PACKAGE = 'br.com.bloco.servidor'
ROOT = '/data/data/' + PACKAGE
START = PACKAGE + '.START'
STOP = PACKAGE + '.STOP'

def cmd(*args, timeout=90, check=True, input_text=None):
    p = subprocess.run([ADB, *args], input=input_text, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=timeout, text=True)
    if check and p.returncode:
        raise RuntimeError('adb %s: %s' % (' '.join(args), p.stdout[-4000:]))
    return p.stdout

def configure(auth):
    # The diagnostic accepts the EULA itself and configures local test mode;
    # release installations still require explicit consent in the UI.
    cmd('shell', 'am', 'force-stop', PACKAGE)
    auth_setting = '' if auth is None else '<boolean name="autenticacao" value="%s" />' % str(auth).lower()
    xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map>' \
          '<boolean name="eula" value="true" />' + auth_setting + '</map>'
    script = ('set -e; p="%s"; own=$(stat -c "%%u:%%g" "$p"); '
              'mkdir -p "$p/shared_prefs"; chown "$own" "$p/shared_prefs"; '
              'echo \'%s\' > "$p/shared_prefs/bloco_config.xml"; '
              'chown "$own" "$p/shared_prefs/bloco_config.xml"; chmod 660 "$p/shared_prefs/bloco_config.xml"') % (ROOT, xml)
    cmd('shell', 'sh', '-c', script)

def logs():
    return cmd('logcat', '-d', '-s', 'BlocoDiag:D', '*:S', timeout=40, check=False)

def wait_online(timeout=300, release=False):
    end = time.monotonic() + timeout
    last = ''
    while time.monotonic() < end:
        if release:
            # The release APK does not log the in-app console. Test its
            # externally reachable UDP endpoint directly instead.
            try:
                ping(timeout=2, quiet=True)
                return True
            except (socket.timeout, ConnectionRefusedError):
                pass
            except RuntimeError as error:
                # During initialization a partial/unexpected UDP response is
                # not readiness. Keep waiting, but never count it as a pong.
                print('Resposta inicial ignorada:', error, flush=True)
        else:
            result = logs()
            if result != last:
                print(result[len(last):] if result.startswith(last) else result[-1000:], flush=True)
                last = result
            if 'Porta local 19132 respondeu ao teste RakNet' in result:
                return True
            if 'ERRO:' in result:
                return False
        time.sleep(4)
    return False

def start():
    cmd('shell', 'logcat', '-c')
    output = cmd('shell', 'am', 'start-foreground-service', '-n', PACKAGE + '/.HostService', '-a', START)
    print('START:', output.strip(), flush=True)

def stop():
    output = cmd('shell', 'am', 'startservice', '-n', PACKAGE + '/.HostService', '-a', STOP)
    print('STOP:', output.strip(), flush=True)
    end = time.monotonic() + 150
    while time.monotonic() < end:
        state = cmd('shell', 'dumpsys', 'activity', 'services', PACKAGE, timeout=45, check=False)
        if 'HostService' not in state:
            return
        time.sleep(3)
    raise RuntimeError('Serviço não parou no prazo.\n' + logs()[-4000:])

def ping(timeout=8, quiet=False):
    magic = bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
    stamp = int(time.time()*1000)
    payload = b'\x01' + struct.pack('>Q', stamp) + magic + struct.pack('>Q', 42)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout); sock.connect(('127.0.0.1', 19132)); sock.send(payload)
        reply = sock.recv(4096)
    if not (len(reply) >= 35 and reply[0] == 0x1c and reply[1:9] == struct.pack('>Q', stamp) and reply[17:33] == magic):
        raise RuntimeError('Resposta UDP não corresponde a um pong RakNet (primeiros bytes: ' + reply[:35].hex() + ')')
    if not quiet: print('PASS: pong RakNet pela porta UDP mapeada', flush=True)

def main():
    release = len(sys.argv) == 3 and sys.argv[1] == '--release'
    if not ((release and len(sys.argv) == 3) or len(sys.argv) == 2):
        raise RuntimeError('Uso: android-container-test.py [--release] <apk>')
    apk = sys.argv[-1]
    abi = cmd('shell', 'getprop', 'ro.product.cpu.abilist').strip()
    selinux = cmd('shell', 'getenforce', check=False).strip()
    print('ABI:', abi, 'SELinux:', selinux, flush=True)
    if 'arm64-v8a' not in abi: raise RuntimeError('Este teste exige um Android ARM64')
    cmd('install', '-r', apk, timeout=120)
    cmd('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity', timeout=120)
    time.sleep(5)
    activities = cmd('shell', 'dumpsys', 'activity', 'activities', timeout=60)
    if PACKAGE not in activities: raise RuntimeError('A tela principal não foi iniciada')
    print('PASS: Activity iniciou no Android ARM64 em contêiner', flush=True)

    # Never expose offline-mode to a public network. This is an isolated CI
    # network, explicitly opting out of Microsoft auth for the protocol test.
    # For the release APK, leave the setting unset to exercise its actual default.
    configure(None if release else False)
    start()
    if not wait_online(timeout=420 if release else 300, release=release):
        raise RuntimeError('Servidor não ficou online.\n' + logs()[-8000:])
    if release: time.sleep(10)  # let the in-app readiness probe complete
    ping()
    cmd('shell', 'grep', '-q', '^online-mode=false$', ROOT + '/files/bloco/servidor/server.properties')
    stop()
    cmd('shell', 'test', '-f', ROOT + '/files/bloco/servidor/worlds/Bloco/level.dat')
    cmd('shell', 'test', '-d', ROOT + '/files/bloco/servidor/worlds/Bloco/db')
    print('PASS: mundo criado e parada concluída no Android ARM64 em contêiner', flush=True)
    if release:
        print('::notice title=APK de distribuição::APK assinado do ZIP iniciou o Bedrock com autenticação desligada, respondeu em UDP e salvou mundo em Android ARM64 em contêiner. Não é teste em aparelho físico.')
        return

    # Also diagnose the optional authenticated mode without claiming a player login.
    configure(True)
    start()
    authenticated_start = wait_online(timeout=240)
    if authenticated_start:
        print('::notice title=Autenticação diagnóstica::Servidor iniciou com online-mode=true neste Android em contêiner; login de jogador não foi testado.')
    else:
        detail = logs()[-2500:].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print('::warning title=Autenticação diagnóstica::Servidor não iniciou com online-mode=true neste Android em contêiner. ' + detail)
    stop()
    print('PASS: diagnóstico Android em contêiner concluído (não equivale a um aparelho físico)', flush=True)

if __name__ == '__main__':
    try: main()
    except Exception as e:
        print('::error title=Teste Android em contêiner::' + str(e).replace('%', '%25').replace('\n', '%0A'), flush=True)
        try: print(cmd('logcat', '-d', '-s', 'AndroidRuntime:E', 'BlocoDiag:D', timeout=30)[-10000:], flush=True)
        except Exception: pass
        raise
