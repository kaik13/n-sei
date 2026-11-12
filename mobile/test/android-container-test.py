#!/usr/bin/env python3
"""Diagnostic app-level smoke test on ARM64 Android in a CI container.

This is NOT a physical-device or Android SELinux proof. It does exercise the
APK's own UI, service, process launch, a real BDS, UDP and saved world.
"""
import os
import re
import socket
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get('ADB', 'adb')
PACKAGE = 'br.com.bloco.servidor'

def cmd(*args, timeout=50, check=True):
    p = subprocess.run([ADB, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       timeout=timeout, text=True)
    if check and p.returncode:
        raise RuntimeError('adb %s: %s' % (' '.join(args), p.stdout[-4000:]))
    return p.stdout

def hierarchy():
    cmd('shell', 'uiautomator', 'dump', '/sdcard/bloco-test.xml')
    xml = cmd('shell', 'cat', '/sdcard/bloco-test.xml')
    return ET.fromstring(xml[xml.index('<?xml'):])

def labels(tree):
    return [x.get('text', '') for x in tree.iter('node') if x.get('text')]

def tap_text(label, tree=None):
    tree = hierarchy() if tree is None else tree
    for node in tree.iter('node'):
        if node.get('text') != label:
            continue
        bounds = re.findall(r'\d+', node.get('bounds', ''))
        if len(bounds) != 4:
            continue
        x1, y1, x2, y2 = map(int, bounds)
        if x2 <= x1 or y2 <= y1:
            continue
        cmd('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.8)
        return True
    return False

def seek(label, direction='down'):
    for _ in range(9):
        tree = hierarchy()
        if tap_text(label, tree):
            return True
        if direction == 'down': cmd('shell', 'input', 'swipe', '500', '1500', '500', '400', '400')
        else: cmd('shell', 'input', 'swipe', '500', '400', '500', '1500', '400')
        time.sleep(.6)
    return False

def wait_labels(allowed, seconds):
    deadline = time.monotonic() + seconds
    last = ''
    while time.monotonic() < deadline:
        tree = hierarchy(); text = labels(tree)
        display = ' | '.join(x for x in text if any(k in x for k in ('Online', 'Preparando', 'atenção', 'servidor', 'Desligado')))
        if display != last:
            print('TELA:', display[:1000], flush=True); last = display
        for expected in allowed:
            if any(expected in x for x in text):
                return expected, tree
        time.sleep(4)
    raise RuntimeError('Tempo de espera da tela expirado: ' + last)

def start_server():
    if not seek('Ligar servidor  →', 'up'):
        raise RuntimeError('Botão Ligar servidor não encontrado')
    tree = hierarchy()
    if any('Servidor oficial Minecraft' in label for label in labels(tree)):
        if not tap_text('Concordo e iniciar', tree): raise RuntimeError('Aceite não encontrado')
    return wait_labels(('●  Online', '●  Precisa de atenção'), 480)

def stop_server():
    if not seek('Desligar servidor', 'up'): raise RuntimeError('Botão de parada não encontrado')
    tree = hierarchy()
    if not tap_text('Desligar', tree): raise RuntimeError('Confirmação de parada não encontrada')
    return wait_labels(('●  Desligado',), 180)

def current_logs():
    if seek('Ver detalhes e console', 'up'):
        tree = hierarchy()
        text = '\n'.join(labels(tree))
        print('CONSOLE:', text[-8000:], flush=True)
        tap_text('Fechar', tree)
        return text
    return ''

def set_offline_mode():
    if not seek('Configurações do servidor', 'down'): raise RuntimeError('Configurações não encontradas')
    tree = hierarchy()
    if not tap_text('Exigir conta Microsoft dos jogadores', tree): raise RuntimeError('Autenticação não encontrada')
    tree = hierarchy()
    if not tap_text('Salvar', tree): raise RuntimeError('Salvar configuração não encontrado')
    tree = hierarchy()
    if not tap_text('Usar só em rede confiável', tree): raise RuntimeError('Confirmação de segurança não encontrada')

def ping():
    magic = bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
    stamp = int(time.time()*1000)
    payload = b'\x01' + struct.pack('>Q', stamp) + magic + struct.pack('>Q', 42)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(5); s.sendto(payload, ('127.0.0.1', 19132))
        reply, _ = s.recvfrom(4096)
    assert len(reply) >= 35 and reply[0] == 0x1c and reply[1:9] == struct.pack('>Q', stamp) and reply[17:33] == magic
    print('PASS: resposta RakNet real pela porta UDP mapeada', flush=True)

def main():
    print('ABI:', cmd('shell', 'getprop', 'ro.product.cpu.abilist').strip(), flush=True)
    print('SELinux:', cmd('shell', 'getenforce', check=False).strip(), flush=True)
    cmd('install', '-r', sys.argv[1], timeout=120)
    cmd('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity', timeout=120)
    time.sleep(8)
    tree = hierarchy()
    tap_text('OK', tree)
    tree = hierarchy()
    if not any('Seu celular é o servidor' in x for x in labels(tree)):
        raise RuntimeError('Interface inicial inesperada: ' + ' | '.join(labels(tree)))
    state, _ = start_server()
    if 'atenção' in state:
        current_logs()
        print('::warning title=Autenticação diagnóstica::A inicialização com autenticação ativada não passou neste ambiente Android em contêiner.')
        set_offline_mode()
        state, _ = start_server()
    if 'Online' not in state:
        current_logs()
        raise RuntimeError('Servidor não entrou Online no Android ARM64 em contêiner')
    ping()
    stop_server()
    if not seek('Configurações do servidor', 'down'):
        raise RuntimeError('Não conseguiu acessar a parte inferior após a parada')
    tree = hierarchy()
    # Settings dialog is now open; close it and inspect the world on the main page.
    tap_text('Cancelar', tree)
    if not seek('Salvar cópia do mundo', 'up'):
        raise RuntimeError('Controle de exportação ausente')
    tree = hierarchy()
    if not any('Mundo salvo neste celular' in x for x in labels(tree)):
        raise RuntimeError('Mundo salvo não apareceu após parada')
    print('PASS: APK, interface, serviço, servidor Bedrock, UDP e mundo no Android ARM64 em contêiner')

if __name__ == '__main__':
    try: main()
    except Exception as e:
        print('::error title=Teste Android em contêiner::' + str(e).replace('%', '%25').replace('\n', '%0A'), flush=True)
        try: print(cmd('logcat', '-d', '-s', 'AndroidRuntime:E', 'ActivityManager:E', timeout=30)[-12000:], flush=True)
        except Exception: pass
        raise
