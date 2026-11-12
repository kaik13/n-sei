import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { AppError } from './errors.js';
import { archiveBudget, extractArchive, walk } from './archive.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const KIND_FOLDERS = { behavior: 'behavior_packs', resource: 'resource_packs' };
const KIND_FILES = { behavior: 'world_behavior_packs.json', resource: 'world_resource_packs.json' };

async function exists(file) {
  try { await fs.access(file); return true; } catch { return false; }
}

async function jsonFile(file, fallback) {
  try {
    const stat = await fs.stat(file);
    if (stat.size > 2 * 1024 * 1024) throw new AppError(400, `O arquivo ${path.basename(file)} é grande demais.`);
    return JSON.parse(await fs.readFile(file, 'utf8'));
  }
  catch (error) {
    if (error.code === 'ENOENT') return fallback;
    if (error instanceof AppError) throw error;
    throw new AppError(400, `Não foi possível ler ${path.basename(file)}. O JSON do mundo pode estar danificado.`);
  }
}

function packFromManifest(manifest, directory) {
  const header = manifest?.header;
  if (!header || !UUID.test(header.uuid || '') || !Array.isArray(header.version) || header.version.length !== 3 ||
      !header.version.every((n) => Number.isInteger(n) && n >= 0 && n <= 2147483647) || !Array.isArray(manifest.modules)) return [];
  const types = new Set(manifest.modules.map((module) => module.type));
  const kinds = [];
  if (types.has('data') || types.has('script')) kinds.push('behavior');
  if (types.has('resources')) kinds.push('resource');
  return kinds.map((kind) => ({
    id: header.uuid.toLowerCase(),
    version: header.version,
    name: typeof header.name === 'string' ? header.name.replace(/[\x00-\x1f\x7f]/g, '').slice(0, 100) : 'Conteúdo sem nome',
    kind,
    directory,
  }));
}

async function findPacks(root) {
  const results = [];
  const manifests = [];
  await walk(root, async (full, item) => {
    if (item.isFile() && item.name.toLowerCase() === 'manifest.json') manifests.push(full);
  });
  for (const file of manifests) {
    const stat = await fs.stat(file);
    if (stat.size > 1024 * 1024) throw new AppError(400, 'Um manifesto de conteúdo é grande demais.');
    let manifest;
    try { manifest = JSON.parse(await fs.readFile(file, 'utf8')); }
    catch { throw new AppError(400, 'Um manifesto de add-on ou textura contém JSON inválido.'); }
    results.push(...packFromManifest(manifest, path.dirname(file)));
  }
  return results;
}

function uniquePacks(packs) {
  const selected = new Map();
  for (const pack of packs) {
    const key = `${pack.kind}:${pack.id}`;
    if (selected.has(key)) throw new AppError(400, 'O arquivo contém dois conteúdos com o mesmo identificador.');
    selected.set(key, pack);
  }
  return [...selected.values()];
}

export class BedrockFiles {
  constructor(dataDir, { uid = process.getuid?.(), gid = process.getgid?.() } = {}) {
    this.root = path.resolve(dataDir);
    this.uid = uid;
    this.gid = gid;
    this.world = path.join(this.root, 'worlds', 'world');
    this.temp = path.join(this.root, '.bloco-tmp');
  }

  async own(file, recursive = false) {
    if (process.getuid?.() !== 0 || this.uid == null || this.gid == null) return;
    await fs.chown(file, this.uid, this.gid);
    if (recursive) {
      const stat = await fs.lstat(file);
      if (stat.isDirectory()) {
        for (const entry of await fs.readdir(file)) await this.own(path.join(file, entry), true);
      }
    }
  }

  async mkdir(directory) {
    await fs.mkdir(directory, { recursive: true });
    await this.own(directory);
  }

  async initialize() {
    await this.mkdir(this.root);
    await this.mkdir(this.temp);
    await this.mkdir(path.join(this.root, 'worlds'));
    await this.mkdir(path.join(this.root, 'behavior_packs'));
    await this.mkdir(path.join(this.root, 'resource_packs'));
  }

  async writeJson(file, value) {
    await this.mkdir(path.dirname(file));
    const tmp = `${file}.${randomUUID()}.tmp`;
    try {
      await fs.writeFile(tmp, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o644, flag: 'wx' });
      await this.own(tmp);
      await fs.rename(tmp, file);
    } finally { await fs.rm(tmp, { force: true }); }
  }

  async worldInfo() {
    const hasWorld = await exists(path.join(this.world, 'level.dat'));
    let name = null;
    if (hasWorld) {
      try {
        const handle = await fs.open(path.join(this.world, 'levelname.txt'), 'r');
        try { const buffer = Buffer.alloc(512); const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0); name = buffer.subarray(0, bytesRead).toString('utf8').trim().slice(0, 100) || null; }
        finally { await handle.close(); }
      } catch { /* default name */ }
    }
    return { exists: hasWorld, name };
  }

  async activePackList(kind) {
    const value = await jsonFile(path.join(this.world, KIND_FILES[kind]), []);
    if (!Array.isArray(value) || value.length > 1000) throw new AppError(400, 'A lista de conteúdos do mundo é inválida ou grande demais.');
    return value;
  }

  async listPacks() {
    if (!(await this.worldInfo()).exists) return [];
    const list = [];
    for (const kind of ['behavior', 'resource']) {
      const packs = await this.activePackList(kind);
      for (const pack of packs) {
        if (!UUID.test(pack?.pack_id || '')) continue;
        const id = pack.pack_id.toLowerCase();
        const manifest = await jsonFile(path.join(this.root, KIND_FOLDERS[kind], id, 'manifest.json'), null).catch(() => null);
        const name = manifest?.header?.name;
        list.push({ id, kind, version: pack.version, name: typeof name === 'string' ? name.replace(/[\x00-\x1f\x7f]/g, '').slice(0, 100) : `Pacote ${id.slice(0, 8)}` });
      }
    }
    return list;
  }

  async operation() {
    await this.initialize();
    const directory = await fs.mkdtemp(path.join(this.temp, 'import-'));
    await this.own(directory);
    return directory;
  }

  // All directory replacements happen on the same volume. Roll back if a later step fails.
  async replaceDirectories(replacements, operation, finish) {
    const records = [];
    try {
      for (let index = 0; index < replacements.length; index++) {
        const { source, target } = replacements[index];
        await this.mkdir(path.dirname(target));
        const old = path.join(operation, `previous-${index}`);
        const hadOld = await exists(target);
        if (hadOld) await fs.rename(target, old);
        const record = { target, old, hadOld, installed: false };
        records.push(record);
        await fs.rename(source, target);
        record.installed = true;
      }
      await finish(records);
      return records;
    } catch (error) {
      for (const record of records.reverse()) {
        if (record.installed) await fs.rm(record.target, { recursive: true, force: true }).catch(() => {});
        if (record.hadOld) await fs.rename(record.old, record.target).catch(() => {});
      }
      throw error;
    }
  }

  async stagedPackReplacements(packs, operation) {
    const replacements = [];
    for (let i = 0; i < packs.length; i++) {
      const pack = packs[i];
      const stage = path.join(operation, `pack-${i}`);
      await fs.cp(pack.directory, stage, { recursive: true, errorOnExist: true, force: false });
      await this.own(stage, true);
      replacements.push({ source: stage, target: path.join(this.root, KIND_FOLDERS[pack.kind], pack.id) });
    }
    return replacements;
  }

  async importWorld(file) {
    const operation = await this.operation();
    try {
      const extraction = path.join(operation, 'extracted');
      await extractArchive(file, extraction);
      const roots = [];
      await walk(extraction, async (full, item) => {
        if (item.isFile() && item.name.toLowerCase() === 'level.dat') roots.push(path.dirname(full));
      });
      if (roots.length !== 1) throw new AppError(400, 'Envie um único mundo Bedrock contendo o arquivo level.dat.');
      const source = roots[0];
      const dbStat = await fs.stat(path.join(source, 'db')).catch(() => null);
      if (!dbStat?.isDirectory()) throw new AppError(400, 'O mundo não contém a pasta de dados db.');
      const embedded = [];
      for (const folder of Object.values(KIND_FOLDERS)) {
        const contentDir = path.join(source, folder);
        if (await exists(contentDir)) embedded.push(...await findPacks(contentDir));
      }
      const packs = uniquePacks(embedded);
      const replacements = await this.stagedPackReplacements(packs, operation);
      await this.own(source, true);
      replacements.push({ source, target: this.world });
      let backup = null;
      await this.replaceDirectories(replacements, operation, async (records) => {
        const previousWorld = records[records.length - 1];
        if (previousWorld.hadOld) {
          const backupRoot = path.join(this.root, 'bloco-backups');
          await this.mkdir(backupRoot);
          backup = path.join(backupRoot, `world-${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`);
          await fs.rename(previousWorld.old, backup);
          // It has already been safely preserved; do not try to restore a moved path.
          previousWorld.old = backup;
        }
      });
      return { world: await this.worldInfo(), embeddedPacks: packs.length, backupCreated: Boolean(backup) };
    } finally { await fs.rm(operation, { recursive: true, force: true }); }
  }

  async importPacks(file) {
    if (!(await this.worldInfo()).exists) throw new AppError(409, 'Crie ou importe um mundo antes de adicionar conteúdos.');
    const operation = await this.operation();
    try {
      const budget = archiveBudget();
      const root = path.join(operation, 'extracted');
      await extractArchive(file, root, budget);
      const roots = [{ directory: root, depth: 0 }];
      const packs = [];
      for (let i = 0; i < roots.length; i++) {
        const { directory, depth } = roots[i];
        packs.push(...await findPacks(directory));
        const nested = [];
        await walk(directory, async (full, item) => {
          if (item.isFile() && /\.(mcpack|mcaddon|zip)$/i.test(item.name)) nested.push(full);
        });
        if (nested.length && depth >= 2) throw new AppError(400, 'O conteúdo possui arquivos compactados aninhados demais.');
        for (const nestedFile of nested) {
          const nestedRoot = path.join(operation, `nested-${roots.length}`);
          await extractArchive(nestedFile, nestedRoot, budget);
          roots.push({ directory: nestedRoot, depth: depth + 1 });
        }
      }
      const selected = uniquePacks(packs);
      if (!selected.length) throw new AppError(400, 'Nenhum add-on ou textura Bedrock válido foi encontrado. Confira o manifest.json.');
      const lists = {};
      for (const kind of ['behavior', 'resource']) {
        lists[kind] = await this.activePackList(kind);
      }
      const original = structuredClone(lists);
      for (const pack of selected) {
        lists[pack.kind] = lists[pack.kind].filter((item) => String(item?.pack_id).toLowerCase() !== pack.id);
        lists[pack.kind].push({ pack_id: pack.id, version: pack.version });
      }
      const replacements = await this.stagedPackReplacements(selected, operation);
      await this.replaceDirectories(replacements, operation, async () => {
        try {
          await this.writeJson(path.join(this.world, KIND_FILES.behavior), lists.behavior);
          await this.writeJson(path.join(this.world, KIND_FILES.resource), lists.resource);
        } catch (error) {
          await this.writeJson(path.join(this.world, KIND_FILES.behavior), original.behavior).catch(() => {});
          await this.writeJson(path.join(this.world, KIND_FILES.resource), original.resource).catch(() => {});
          throw error;
        }
      });
      return { added: selected.map(({ id, kind, name, version }) => ({ id, kind, name, version })) };
    } finally { await fs.rm(operation, { recursive: true, force: true }); }
  }

  async removePack(kind, id) {
    if (!KIND_FILES[kind] || !UUID.test(id)) throw new AppError(400, 'Conteúdo inválido.');
    const current = await this.activePackList(kind);
    const updated = current.filter((pack) => String(pack?.pack_id).toLowerCase() !== id.toLowerCase());
    if (updated.length === current.length) throw new AppError(404, 'Conteúdo não encontrado neste mundo.');
    await this.writeJson(path.join(this.world, KIND_FILES[kind]), updated);
    return { removed: true };
  }
}
