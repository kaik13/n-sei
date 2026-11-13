import fs from 'node:fs/promises';
import { constants as fsConstants, createReadStream } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const { version } = JSON.parse(await fs.readFile(path.join(root, 'package.json'), 'utf8'));
if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Versão inválida.');
const apk = path.join(root, '.android-build', `Bloco-${version}.apk`);
const stat = await fs.stat(apk);
if (stat.size < 5_000_000) throw new Error('APK não contém o runtime ARM64 completo.');
const output = path.resolve(process.env.BLOCO_ZIP_DIR || 'Zips');
const target = path.join(output, `bloco-${version}-android-celular.zip`);
await fs.mkdir(output, { recursive: true });
try { await fs.access(target); throw new Error(`O ZIP já existe e não será sobrescrito: ${target}`); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'bloco-mobile-release-'));
const stage = path.join(temporary, `Bloco-${version}-celular`);
let copied = false;
try {
  await fs.mkdir(stage, { recursive: true });
  await fs.copyFile(apk, path.join(stage, `Bloco-${version}.apk`));
  const template = await fs.readFile(path.join(root, 'distribution-mobile/LEIA-ME.txt'), 'utf8');
  if (!template.includes('@VERSAO@')) throw new Error('Guia sem marcador de versão.');
  const guide = template.replaceAll('@VERSAO@', version);
  await fs.writeFile(path.join(stage, 'LEIA-ME.txt'), guide);
  await fs.cp(path.join(root, 'mobile/licencas'), path.join(stage, 'LICENCAS'), { recursive: true });
  const sources = path.resolve(process.env.BLOCO_SOURCES_DIR || '.mobile-runtime/fontes');
  if (!(await fs.stat(path.join(sources, 'proot-termux-5.1.107.102.tar.gz'))).isFile() ||
      !(await fs.stat(path.join(sources, 'talloc-2.1.1.tar.gz'))).isFile() ||
      !(await fs.stat(path.join(sources, 'musl-e-compiler-rt-zig-0.13.0.tar.gz'))).isFile() ||
      !(await fs.stat(path.join(sources, 'native-proot', 'android-seccomp-log.patch'))).isFile() ||
      !(await fs.stat(path.join(sources, 'proot-objetos-religaveis-arm64-v8a.tar.gz'))).isFile() ||
      !(await fs.stat(path.join(sources, 'RELIGAR-TALLOC.txt'))).isFile() ||
      !(await fs.stat(path.join(sources, 'compilar-proot.py'))).isFile() ||
      !(await fs.stat(path.join(sources, 'native-box64', 'box64-tcp-reuse.patch'))).isFile() ||
      !(await fs.stat(path.join(sources, 'native-box64', 'bloco-tcp-reuse.h'))).isFile() ||
      !(await fs.stat(path.join(sources, 'certifi-2026.10.23-cacert.pem'))).isFile()) {
    throw new Error('Fontes correspondentes do runtime não encontradas.');
  }
  await fs.cp(sources, path.join(stage, 'FONTES'), { recursive: true });
  async function hash(file) {
    const h = createHash('sha256');
    for await (const chunk of createReadStream(file)) h.update(chunk);
    return h.digest('hex');
  }
  const lines = [];
  async function visit(dir, prefix = '') {
    for (const item of (await fs.readdir(dir, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
      const name = path.posix.join(prefix, item.name); const full = path.join(dir, item.name);
      if (item.isDirectory()) await visit(full, name);
      else if (item.isFile()) lines.push(`${await hash(full)}  ${name}`);
      else throw new Error(`Arquivo inesperado: ${name}`);
    }
  }
  await visit(stage); await fs.writeFile(path.join(stage, 'SHA256SUMS.txt'), lines.join('\n') + '\n');
  const zip = path.join(temporary, 'bloco.zip');
  let result = spawnSync('zip', ['-qr', '-n', '.apk', zip, path.basename(stage)], { cwd: temporary, stdio: 'inherit' });
  if (result.status !== 0) throw new Error('Não foi possível criar o ZIP.');
  result = spawnSync('unzip', ['-tq', zip], { stdio: 'inherit' });
  if (result.status !== 0) throw new Error('Integridade do ZIP inválida.');
  await fs.copyFile(zip, target, fsConstants.COPYFILE_EXCL); copied = true;
  console.log(`Pacote criado: ${target}`);
} catch (error) {
  if (copied) await fs.rm(target, { force: true });
  throw error;
} finally { await fs.rm(temporary, { recursive: true, force: true }); }
