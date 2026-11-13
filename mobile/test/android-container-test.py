#!/usr/bin/env python3
"""Test the release APK and a debuggable diagnostic on ARM64 Android in CI.

The container's Android SELinux is disabled and root is used only to control
preferences/commands. The service and Bedrock process run as the app UID.
This is NOT a physical-device, enforced-SELinux, or 10-player test.
"""
import ctypes
import http.client
import io
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ADB = os.environ.get('ADB', 'adb')
# The new package coexists with all earlier installations; internal Java
# classes and explicit service actions retain their established names.
PACKAGE = 'br.com.bloco.local'
JAVA_PACKAGE = 'br.com.bloco.servidor'
ROOT = '/data/data/' + PACKAGE
ACTIVITY = PACKAGE + '/' + JAVA_PACKAGE + '.MainActivity'
SERVICE = PACKAGE + '/' + JAVA_PACKAGE + '.HostService'
START = JAVA_PACKAGE + '.START'
STOP = JAVA_PACKAGE + '.STOP'
COMMAND = JAVA_PACKAGE + '.COMMAND'

def cmd(*args, timeout=90, check=True, input_text=None):
    p = subprocess.run([ADB, *args], input=input_text, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=timeout, text=True)
    if check and p.returncode:
        raise RuntimeError('adb %s: %s' % (' '.join(args), p.stdout[-4000:]))
    return p.stdout

def inherited_filter():
    # Installed only on a native Linux test child, never on the test runner.
    # Model selected ARM64 traps without asserting that every OEM has these
    # exact rules or that the privileged Android container reproduces them.
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    instructions = [Filter(0x20, 0, 0, 0)]
    for number in (99, 293, 435):  # set_robust_list, rseq, clone3
        instructions += [Filter(0x15, 0, 1, number), Filter(0x06, 0, 0, 0x00030000)]
    instructions.append(Filter(0x06, 0, 0, 0x7fff0000))
    filters = (Filter * len(instructions))(*instructions)
    program = Program(len(filters), filters)
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) or libc.prctl(22, 2, ctypes.byref(program), 0, 0):
        os._exit(120)

def test_app_seccomp(apk):
    # Prove the intended filter actually fires in a fresh native process.
    with tempfile.TemporaryDirectory(prefix='bloco-filter-control-') as control:
        killed = subprocess.run(['/bin/sh', '-c', 'exec /bin/true'], preexec_fn=inherited_filter,
                                env=dict(os.environ, GLIBC_TUNABLES='glibc.pthread.rseq=0'),
                                cwd=control, capture_output=True, timeout=20)
        if killed.returncode != -signal.SIGSYS:
            raise RuntimeError('Filtro de controle não bloqueou glibc: %d' % killed.returncode)
    # docker exec starts processes through a pre-existing daemon; installing a
    # filter around the docker client would NOT affect the app. Test the exact
    # Android command on the native host below and test the Android binary in
    # the app UID separately, under its actual inherited Android policy.
    with tempfile.TemporaryDirectory(prefix='bloco-app-filter-') as tmp:
        proot = Path(tmp) / 'proot'
        with zipfile.ZipFile(apk) as package:
            with zipfile.ZipFile(io.BytesIO(package.read('assets/runtime.zip'))) as runtime:
                proot.write_bytes(runtime.read('proot'))
        # Exact runtime binary + real Ubuntu/glibc guest, under an inherited
        # ARM64 trap filter on the CI host. Never disable the inherited filter.
        proot.chmod(0o755)
        with zipfile.ZipFile(apk) as package:
            with zipfile.ZipFile(io.BytesIO(package.read('assets/runtime.zip'))) as runtime:
                shell = Path(tmp) / 'guest' / 'bin' / 'sh'
                shell.parent.mkdir(parents=True)
                shell.write_bytes(runtime.read('rootfs/usr/bin/dash')); shell.chmod(0o755)
                libdir = Path(tmp) / 'guest' / 'lib' / 'aarch64-linux-gnu'
                libdir.mkdir(parents=True)
                for name in ('ld-linux-aarch64.so.1', 'libc.so.6'):
                    library = libdir / name
                    library.write_bytes(runtime.read('rootfs/usr/lib/aarch64-linux-gnu/' + name))
                    library.chmod(0o755)  # PRoot checks execute permission on the ELF interpreter
                (Path(tmp) / 'guest' / 'lib' / 'ld-linux-aarch64.so.1').symlink_to('aarch64-linux-gnu/ld-linux-aarch64.so.1')
                (Path(tmp) / 'guest' / 'tmp').mkdir()
        environment = dict(os.environ, HOME=tmp, TMPDIR='/tmp', PROOT_TMP_DIR=tmp,
                           PROOT_NO_SECCOMP='1', GLIBC_TUNABLES='glibc.pthread.rseq=0')
        result = subprocess.run([str(proot), '--kill-on-exit', '-r', str(Path(tmp) / 'guest'),
                                 '-w', '/', '/bin/sh', '-c', 'echo proot-app-ok'],
                                preexec_fn=inherited_filter, env=environment,
                                capture_output=True, text=True, timeout=60)
        if result.returncode or 'proot-app-ok' not in result.stdout:
            raise RuntimeError('PRoot/guest reais com filtro herdado falharam: código %d\n%s\n%s' %
                               (result.returncode, result.stdout[-3000:], result.stderr[-3000:]))
        print('PASS: PRoot e shell do APK sob filtro ARM64 simulado no host', flush=True)
        # Copy the same tracer to app-private storage, then use run-as to keep
        # the actual app UID and exercise the container's real Android policy.
        cmd('push-proot-test', str(proot), PACKAGE, timeout=120)
        script = ('set -e; p="%s/files/bloco/seccomp-teste"; '
                  'export HOME="$p" TMPDIR="$p" PROOT_TMP_DIR="$p" '
                  'PROOT_NO_SECCOMP=1 GLIBC_TUNABLES=glibc.pthread.rseq=0; '
                  'exec "$p/proot" --kill-on-exit -r / -w /system/bin /system/bin/sh -c '
                  "'echo proot-android-ok'") % ROOT
        result = subprocess.run([ADB, 'shell', 'run-as', PACKAGE, 'sh', '-c', script],
                                capture_output=True, text=True, timeout=60)
        if result.returncode or 'proot-android-ok' not in result.stdout:
            raise RuntimeError('PRoot no UID do app falhou: código %d\n%s\n%s' %
                               (result.returncode, result.stdout[-3000:], result.stderr[-3000:]))
        print('PASS: PRoot incluído executou shell no Android sob UID do app', flush=True)

def configure(auth, port=None, discovery=None):
    # The diagnostic accepts the EULA itself and configures local test mode;
    # release installations still require explicit consent in the UI.
    cmd('shell', 'am', 'force-stop', PACKAGE)
    auth_setting = '' if auth is None else '<boolean name="autenticacao" value="%s" />' % str(auth).lower()
    port_setting = '' if port is None else '<int name="porta" value="%d" />' % port
    discovery_setting = '' if discovery is None else '<boolean name="visibilidade" value="%s" />' % str(discovery).lower()
    xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map>' \
          '<boolean name="eula" value="true" />' + auth_setting + port_setting + discovery_setting + '</map>'
    script = ('set -e; p="%s"; own=$(stat -c "%%u:%%g" "$p"); '
              'mkdir -p "$p/shared_prefs"; chown "$own" "$p/shared_prefs"; '
              'echo \'%s\' > "$p/shared_prefs/bloco_config.xml"; '
              'chown "$own" "$p/shared_prefs/bloco_config.xml"; chmod 660 "$p/shared_prefs/bloco_config.xml"') % (ROOT, xml)
    cmd('shell', 'sh', '-c', script)

def logs():
    return cmd('logcat', '-d', '-s', 'BlocoDiag:D', '*:S', timeout=40, check=False)

def private_history():
    # CI-only disposable instance; the release app never sends logs to logcat.
    return cmd('shell', 'cat', ROOT + '/files/bloco/logs/servidor.log', timeout=30, check=False)

def ui_screen():
    # UiAutomation runs via a real adbd shell. Docker exec alone does not
    # reproduce that environment. A dump may also return zero without writing
    # a file when the window is busy. Never read a stale tree.
    path = '/data/local/tmp/bloco-ci-window.xml'
    errors = []
    for attempt in range(5):
        cmd('shell', 'rm', '-f', path, timeout=15, check=False)
        result = subprocess.run([ADB, 'shell-as-shell', 'uiautomator', 'dump', path],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
        status = 'código %d: %s' % (result.returncode, result.stdout)
        xml = cmd('shell', 'cat', path, timeout=20, check=False)
        try:
            screen = ET.fromstring(xml)
            if screen.tag == 'hierarchy': return screen
            errors.append('raiz inesperada: ' + screen.tag)
        except ET.ParseError:
            errors.append('tentativa ' + str(attempt + 1) + ': ' + status[-400:] + '; XML: ' + xml[-400:])
        time.sleep(2)
    details = cmd('logcat', '-d', '-t', '150', '-s', 'uiautomator:V', 'UiAutomation:V',
                  'AndroidRuntime:E', '*:S', timeout=30, check=False)
    raise RuntimeError('uiautomator não produziu uma hierarquia atual: ' + ' | '.join(errors) +
                       '; logcat: ' + details[-1500:])

def tap_text(screen, label):
    for node in screen.iter():
        if node.get('text', '') == label:
            bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                cmd('shell-as-shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2), timeout=20)
                time.sleep(1)
                return
    raise RuntimeError('Controle não encontrado na tela: ' + label)

def test_dashboard():
    screen = ui_screen()
    for tab, heading in [('Mundo', 'Mundo e conteúdos.'), ('Console', 'Console ao vivo.'),
                         ('Ajustes', 'Ajustes do servidor.'), ('Painel', 'Tudo em um só lugar.')]:
        tap_text(screen, tab)
        screen = ui_screen()
        texts = [node.get('text', '') for node in screen.iter()]
        if not any(heading in text for text in texts):
            raise RuntimeError('Aba %s não exibiu %s: %s' % (tab, heading, ' | '.join(texts)[-3000:]))
    print('PASS: navegação do painel, mundo, console e ajustes no APK de distribuição', flush=True)

def assert_java_and_console():
    for name in ('java/bin/java', 'java/lib/modules', 'java/.bloco-java-verificado'):
        cmd('shell', 'test', '-f', ROOT + '/files/bloco/' + name)
    history = private_history()
    if 'Java ARM64 verificado no Ubuntu' not in history:
        raise RuntimeError('Java não foi verificado pelo aplicativo.\n' + history[-5000:])
    cmd('shell', 'am', 'startservice', '-n', SERVICE, '-a', COMMAND, '--es', 'comando', 'list')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        history = private_history()
        if '> list' in history and 'players online' in history.rsplit('> list', 1)[1]:
            print('PASS: Java executou no Ubuntu e o console registrou o comando list', flush=True)
            return
        time.sleep(1)
    raise RuntimeError('O console não registrou/respondeu ao comando list.\n' + history[-5000:])

def release_diagnostics():
    # Release builds intentionally do not log the console to Android logcat.
    # These diagnostics inspect the same on-screen console a person can open,
    # plus system state; the CI container contains only disposable test data.
    details = []
    try:
        cmd('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=45, check=False)
        time.sleep(2)
        screen = ui_screen()
        texts = [node.get('text', '') for node in screen.iter() if node.get('text')]
        details.append('Tela: ' + ' | '.join(texts)[-2000:])
        buttons = [node for node in screen.iter() if node.get('text', '').lower() == 'console']
        if buttons:
            bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', buttons[0].get('bounds', ''))
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                cmd('shell-as-shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2), timeout=20, check=False)
                time.sleep(1)
                screen = ui_screen()
                texts = [node.get('text', '') for node in screen.iter() if node.get('text')]
                details.append('Console visível: ' + ' | '.join(texts)[-9000:])
        else:
            details.append('Botão de console não encontrado na tela de teste.')
    except Exception as error:
        details.append('UI de diagnóstico indisponível: ' + str(error))
    try: details.append('Histórico privado do teste: ' + private_history()[-9000:])
    except Exception as error: details.append('Histórico indisponível: ' + str(error))
    for label, args, limit in (
        ('Serviço', ('shell', 'dumpsys', 'activity', 'services', PACKAGE), 3000),
        ('Notificações', ('shell', 'dumpsys', 'notification', '--noredact'), 4000),
        ('Erros Android', ('logcat', '-d', '-t', '150', '-s', 'AndroidRuntime:E', 'libc:E', '*:S'), 2500),
    ):
        try: details.append(label + ': ' + cmd(*args, timeout=40, check=False)[-limit:])
        except Exception as error: details.append(label + ': indisponível (' + str(error) + ')')
    result = '\n'.join(details)
    escaped = result.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
    print('::error title=Diagnóstico do APK de distribuição::' + escaped, flush=True)
    return result

def wait_online(timeout=300, release=False, port=19132, allow_empty=False):
    end = time.monotonic() + timeout
    last = ''
    next_service_check = time.monotonic() + 30
    while time.monotonic() < end:
        if release:
            # The signed release APK does not expose its console to logcat.
            # Check the real TCP endpoint AND the app's ready notification.
            try:
                ping(timeout=2, quiet=True, port=port, allow_empty=allow_empty)
                notifications = cmd('shell', 'dumpsys', 'notification', '--noredact', timeout=30, check=False)
                if 'Servidor ativo neste celular.' in notifications:
                    return True
                last = 'HTTP respondeu, mas a notificação de pronto não foi encontrada.'
            except (OSError, http.client.HTTPException, RuntimeError, ValueError) as error:
                last = 'Teste HTTP: ' + type(error).__name__ + ': ' + str(error)
            if time.monotonic() >= next_service_check:
                state = cmd('shell', 'dumpsys', 'activity', 'services', PACKAGE, timeout=30, check=False)
                if 'HostService' not in state:
                    print('Serviço não está ativo; ' + last, flush=True)
                    return False
                next_service_check = time.monotonic() + 30
        else:
            result = logs()
            if result != last:
                print(result[len(last):] if result.startswith(last) else result[-1000:], flush=True)
                last = result
            if ('Porta local %d respondeu ao teste NetherNet' % port) in result:
                return True
            if 'ERRO:' in result:
                return False
        time.sleep(4)
    if release:
        print('Tempo de espera esgotado; ' + last, flush=True)
        print('::notice title=Último teste de prontidão::' + last.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'), flush=True)
    return False

def start():
    cmd('shell', 'logcat', '-c')
    output = cmd('shell', 'am', 'start-foreground-service', '-n', SERVICE, '-a', START)
    print('START:', output.strip(), flush=True)

def stop():
    output = cmd('shell', 'am', 'startservice', '-n', SERVICE, '-a', STOP)
    print('STOP:', output.strip(), flush=True)
    end = time.monotonic() + 150
    while time.monotonic() < end:
        state = cmd('shell', 'dumpsys', 'activity', 'services', PACKAGE, timeout=45, check=False)
        if 'HostService' not in state:
            return
        time.sleep(3)
    raise RuntimeError('Serviço não parou no prazo.\n' + logs()[-4000:])

def ping(timeout=8, quiet=False, port=19132, allow_empty=False):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
    empty = False
    try:
        connection.request('GET', '/v1/join', headers={'Accept': 'application/json', 'Connection': 'close'})
        reply = connection.getresponse()
        transfer = reply.getheader('Transfer-Encoding')
        if transfer and (transfer.lower().strip() != 'chunked' or reply.getheader('Content-Length') is not None):
            raise RuntimeError('Enquadramento HTTP de sinalização inválido')
        expected = reply.length
        if expected is not None and expected > 65536:
            raise RuntimeError('Resposta HTTP de sinalização grande demais')
        body = reply.read(65537)
        if reply.status != 200 or len(body) > 65536 or (expected is not None and len(body) != expected):
            raise RuntimeError('Resposta HTTP de sinalização inválida ou truncada: %d' % reply.status)
        empty = not body
        if empty:
            if not allow_empty:
                raise RuntimeError('Resposta de sinalização sem metadados com descoberta LAN ativa')
        else:
            if reply.getheader('Content-Type', '').lower().split(';', 1)[0].strip() != 'application/json':
                raise RuntimeError('Tipo HTTP de sinalização inválido')
            status = json.loads(body)
            if not isinstance(status, dict) or status.get('protocol', 0) <= 0 or not isinstance(status.get('name'), str) or not status.get('version'):
                raise RuntimeError('JSON de sinalização não descreve um servidor Bedrock')
    finally:
        connection.close()
    if not quiet:
        print('PASS: sinalização NetherNet TCP %d%s; não é um login de jogador' %
              (port, ' (resposta vazia, sem identificar o serviço)' if empty else ''), flush=True)

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
    sdk = int(cmd('shell', 'getprop', 'ro.build.version.sdk').strip())
    if sdk >= 33:
        # This test starts the service through adb rather than answering the
        # Android permission dialog in the UI. Grant the notification permission
        # explicitly so the release readiness check can see its notification.
        cmd('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
        print('CI: permissão de notificações concedida no Android %d' % sdk, flush=True)
    cmd('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=120)
    time.sleep(5)
    activities = cmd('shell', 'dumpsys', 'activity', 'activities', timeout=60)
    if PACKAGE not in activities: raise RuntimeError('A tela principal não foi iniciada')
    print('PASS: Activity iniciou no Android ARM64 em contêiner', flush=True)
    if release: test_dashboard()
    else: test_app_seccomp(apk)

    # Never expose offline-mode to a public network. This is an isolated CI
    # network, explicitly opting out of Microsoft auth for the protocol test.
    # For the release APK, leave the setting unset to exercise its actual default.
    configure(None if release else False)
    start()
    if not wait_online(timeout=420 if release else 300, release=release):
        if release: release_diagnostics()
        raise RuntimeError('Servidor não ficou online.\n' + logs()[-8000:])
    ping()
    assert_java_and_console()
    props = ROOT + '/files/bloco/servidor/server.properties'
    for expected in ('online-mode=false', 'transport=nethernet', 'server-port=19132', 'server-udp-ports=19132'):
        cmd('shell', 'grep', '-q', '^' + expected + '$', props)
    stop()
    world = ROOT + '/files/bloco/servidor/worlds/Bloco'
    cmd('shell', 'test', '-f', world + '/level.dat')
    cmd('shell', 'test', '-d', world + '/db')
    # Preserve a marker while changing both port and discovery, so the exact
    # APK proves it reuses its prior world rather than silently replacing it.
    script = 'p="%s"; own=$(stat -c "%%u:%%g" "$p"); echo salvo > "$p/bloco-ci-marker"; chown "$own" "$p/bloco-ci-marker"' % world
    cmd('shell', 'sh', '-c', script)
    # A successful HTTP probe can leave the previous listener in TCP TIME_WAIT.
    # Restart immediately on the SAME port and require the signed APK to serve
    # the endpoint again; changing port alone would hide this regression.
    start()
    if not wait_online(timeout=300, release=release):
        if release: release_diagnostics()
        raise RuntimeError('Servidor não reabriu a sinalização na mesma porta.\n' + logs()[-8000:])
    ping()
    stop()
    cmd('shell', 'test', '-f', world + '/bloco-ci-marker')
    print('PASS: reinício na mesma porta e mundo preservado no Android ARM64 em contêiner', flush=True)
    configure(False, port=19142, discovery=False)
    start()
    if not wait_online(timeout=300, release=release, port=19142, allow_empty=True):
        if release: release_diagnostics()
        raise RuntimeError('Servidor não ficou online na porta alternativa.\n' + logs()[-8000:])
    ping(port=19142, allow_empty=True)
    for expected in ('server-port=19142', 'server-udp-ports=19142', 'enable-lan-visibility=false', 'transport=nethernet'):
        cmd('shell', 'grep', '-q', '^' + expected + '$', props)
    stop()
    cmd('shell', 'test', '-f', world + '/bloco-ci-marker')
    print('PASS: mundo persistiu com porta alternativa no Android ARM64 em contêiner', flush=True)
    if release:
        print('::notice title=APK de distribuição::APK assinado do ZIP iniciou com autenticação desligada, respondeu na sinalização TCP em duas portas e preservou o mundo em Android ARM64 em contêiner. Não é teste de login ou aparelho físico.')
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
