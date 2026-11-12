import fs from 'node:fs/promises';
import { constants as fsConstants } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const manifest = JSON.parse(await fs.readFile(path.join(root, 'package.json'), 'utf8'));
const version = manifest.version;
if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Versão de distribuição inválida.');
const apk = path.join(root, '.android-build', `Bloco-${version}.apk`);
const outputDir = path.resolve(process.env.BLOCO_ZIP_DIR || 'Zips');
const target = path.join(outputDir, `bloco-${version}-android-servidor.zip`);
const sourceStatic = path.join(root, 'dist');
for (const required of [apk, path.join(sourceStatic, 'index.html'), path.join(root, 'distribution/Dockerfile')]) await fs.access(required);
await fs.mkdir(outputDir, { recursive: true });
try { await fs.access(target); throw new Error(`O ZIP já existe e não será sobrescrito: ${target}`); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'bloco-release-'));
const stage = path.join(tmp, `Bloco-${version}`);
const server = path.join(stage, 'servidor');
const temporaryZip = path.join(tmp, 'pacote-temporario.zip');
let copied = false;
await fs.mkdir(server, { recursive: true });
try {
  await fs.copyFile(apk, path.join(stage, `Bloco-${version}.apk`));
  await fs.copyFile(path.join(root, 'distribution/LEIA-ME.txt'), path.join(stage, 'LEIA-ME.txt'));
  await fs.copyFile(path.join(root, 'distribution/Dockerfile'), path.join(server, 'Dockerfile'));
  await fs.copyFile(path.join(root, 'docker-compose.yml'), path.join(server, 'docker-compose.yml'));
  await fs.copyFile(path.join(root, '.env.example'), path.join(server, 'env.example'));
  for (const file of ['package.json', 'package-lock.json']) await fs.copyFile(path.join(root, file), path.join(server, file));
  await fs.cp(path.join(root, 'server'), path.join(server, 'server'), { recursive: true });
  await fs.cp(sourceStatic, path.join(server, 'dist'), { recursive: true });
  const dependencies = ['react', 'react-dom', 'scheduler', 'lucide-react', '@fontsource/dm-sans', '@fontsource/outfit'];
  let notices = 'Licenças das bibliotecas e fontes incluídas no painel compilado.\n\n';
  for (const dependency of dependencies) {
    const directory = path.join(root, 'node_modules', ...dependency.split('/'));
    const info = JSON.parse(await fs.readFile(path.join(directory, 'package.json'), 'utf8'));
    const license = await fs.readFile(path.join(directory, 'LICENSE'), 'utf8');
    notices += `\n================ ${info.name} ${info.version} (${info.license}) ================\n${license.trim()}\n`;
  }
  notices += '\nO servidor Bedrock é baixado separadamente pela imagem itzg/minecraft-bedrock-server; seu uso exige aceitar os termos da Microsoft/Mojang.\n';
  await fs.writeFile(path.join(stage, 'LICENCAS-terceiros.txt'), notices);
  const lines = [];
  async function visit(dir, prefix = '') {
    for (const item of (await fs.readdir(dir, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name))) {
      const name = path.posix.join(prefix, item.name);
      const file = path.join(dir, item.name);
      if (item.isDirectory()) await visit(file, name);
      else if (item.isFile()) lines.push(`${createHash('sha256').update(await fs.readFile(file)).digest('hex')}  ${name}`);
      else throw new Error(`Arquivo inesperado no pacote: ${name}`);
    }
  }
  await visit(stage);
  await fs.writeFile(path.join(stage, 'SHA256SUMS.txt'), lines.join('\n') + '\n');
  const zipped = spawnSync('zip', ['-qr', temporaryZip, path.basename(stage)], { cwd: tmp, stdio: 'inherit' });
  if (zipped.status !== 0) throw new Error('Falha ao criar o ZIP.');
  const checked = spawnSync('unzip', ['-tq', temporaryZip], { stdio: 'inherit' });
  if (checked.status !== 0) throw new Error('O ZIP não passou na verificação.');
  // COPYFILE_EXCL fails safely if another build published this version concurrently.
  await fs.copyFile(temporaryZip, target, fsConstants.COPYFILE_EXCL);
  copied = true;
  console.log(`Pacote criado: ${target}`);
} catch (error) {
  if (copied) await fs.rm(target, { force: true });
  throw error;
} finally { await fs.rm(tmp, { recursive: true, force: true }); }
