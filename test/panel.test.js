import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import request from 'supertest';
import yazl from 'yazl';
import { BedrockManager } from '../server/manager.js';
import { createApp } from '../server/app.js';
import { extractArchive, archiveBudget } from '../server/archive.js';

const TOKEN = 'uma-chave-de-teste-bem-comprida';
const ID_BEHAVIOR = 'aaaaaaaa-1111-4222-8333-444444444444';
const ID_RESOURCE = 'bbbbbbbb-1111-4222-8333-444444444444';

class FakeDocker {
  constructor() {
    this.available = true;
    this.options = null;
    this.running = false;
    this.modem = { followProgress: (_stream, callback) => callback(null, []) };
    this.handle = {
      inspect: async () => {
        if (!this.options) throw Object.assign(new Error('not found'), { statusCode: 404 });
        return { Config: { Labels: this.options.Labels, Env: this.options.Env }, State: { Running: this.running, Health: { Status: this.running ? 'starting' : 'unhealthy' } }, NetworkSettings: { Networks: {} } };
      },
      start: async () => { this.running = true; },
      stop: async () => { this.running = false; },
      restart: async () => { this.running = true; this.restarts = (this.restarts || 0) + 1; },
      remove: async () => { this.options = null; this.running = false; },
      logs: async () => Buffer.from('Bedrock log de teste'),
    };
  }
  async ping() { if (!this.available) throw Object.assign(new Error('offline'), { code: 'ECONNREFUSED' }); return 'OK'; }
  getContainer() { return this.handle; }
  async pull() { return {}; }
  async createContainer(options) { this.options = options; return this.handle; }
}

async function context(adminToken = TOKEN) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bloco-test-'));
  const docker = new FakeDocker();
  const manager = new BedrockManager({ docker, dataDir: path.join(root, 'bedrock'), stateDir: path.join(root, 'panel'), networkName: 'bloco-network', uid: process.getuid?.(), gid: process.getgid?.() });
  await manager.init();
  const app = createApp({ manager, adminToken, staticDir: path.join(root, 'not-built') });
  return { root, docker, manager, app, cleanup: () => fs.rm(root, { recursive: true, force: true }) };
}
function authorized(req) { return req.set('Authorization', `Bearer ${TOKEN}`); }
async function zip(entries) {
  const archive = new yazl.ZipFile();
  for (const [name, value] of Object.entries(entries)) {
    const data = Buffer.isBuffer(value) ? value : Buffer.from(value);
    archive.addBuffer(data, name);
  }
  archive.end();
  const chunks = [];
  for await (const chunk of archive.outputStream) chunks.push(chunk);
  return Buffer.concat(chunks);
}
function worldEntries(name = 'Minha ilha') {
  return { 'level.dat': 'dummy-bedrock-level-data', 'levelname.txt': name, 'db/000001.ldb': 'world-chunks' };
}
function manifest(id, name, type) {
  return JSON.stringify({ format_version: 2, header: { uuid: id, name, version: [1, 2, 3] }, modules: [{ uuid: 'cccccccc-1111-4222-8333-444444444444', type, version: [1, 2, 3] }] });
}
async function importWorld(app, name = 'Minha ilha') {
  return authorized(request(app).post('/api/world')).attach('file', await zip(worldEntries(name)), 'meu-mundo.mcworld');
}
function binaryParser(res, callback) {
  const chunks = [];
  res.on('data', (chunk) => chunks.push(chunk));
  res.on('end', () => callback(null, Buffer.concat(chunks)));
  res.on('error', callback);
}

test('modo de apresentação não permite alterações; painel protegido exige chave', async (t) => {
  const demo = await context(''); t.after(demo.cleanup);
  assert.equal((await request(demo.app).get('/api/health')).body.panelConfigured, false);
  assert.equal((await request(demo.app).get('/api/status')).status, 200);
  assert.equal((await request(demo.app).post('/api/server').send({})).status, 503);
  const secured = await context(); t.after(secured.cleanup);
  assert.equal((await request(secured.app).get('/api/health')).body.needsAuth, true);
  assert.equal((await request(secured.app).get('/api/status')).status, 401);
  assert.equal((await request(secured.app).get('/api/status').set('Authorization', 'Bearer errado')).status, 401);
  assert.equal((await authorized(request(secured.app).get('/api/status'))).status, 200);
});

test('cria um Bedrock real com 10 vagas, versão estável recente e portas NetherNet', async (t) => {
  const ctx = await context(); t.after(ctx.cleanup);
  const payload = { name: 'Mundo dos amigos', address: '203.0.113.10', mode: 'survival', difficulty: 'normal', acceptEula: true };
  assert.equal((await authorized(request(ctx.app).post('/api/server')).send({ ...payload, acceptEula: false })).status, 400);
  assert.equal((await authorized(request(ctx.app).post('/api/server')).send({ ...payload, address: 'example.com' })).status, 400);
  assert.equal((await authorized(request(ctx.app).post('/api/server')).send(payload)).status, 201);
  const options = ctx.docker.options;
  assert.equal(options.Image, 'itzg/minecraft-bedrock-server:latest');
  assert.ok(options.Env.includes('VERSION=LATEST'));
  assert.ok(options.Env.includes('MAX_PLAYERS=10'));
  assert.ok(options.Env.includes('SERVER_UDP_PORTS=203.0.113.10:19140-19155:19140-19155'));
  assert.ok(options.Env.includes('TRANSPORT=nethernet'));
  assert.equal(options.HostConfig.RestartPolicy.Name, 'unless-stopped');
  assert.equal(Object.keys(options.HostConfig.PortBindings).length, 17);
  assert.ok(options.HostConfig.PortBindings['19132/tcp']);
  assert.ok(options.HostConfig.PortBindings['19155/udp']);
  const status = (await authorized(request(ctx.app).get('/api/status'))).body;
  assert.equal(status.server.name, payload.name);
  assert.equal(status.server.running, true);
  assert.equal(status.server.state, 'starting');
  assert.equal(status.server.players, null);
  assert.equal(status.server.version, null);
  assert.equal((await authorized(request(ctx.app).post('/api/server')).send(payload)).status, 409);
  assert.equal((await authorized(request(ctx.app).post('/api/server/action')).send({ action: 'stop' })).status, 200);
  assert.equal(ctx.docker.running, false);
  assert.equal((await authorized(request(ctx.app).post('/api/server/action')).send({ action: 'start' })).status, 200);
  assert.equal(ctx.docker.running, true);
  assert.equal((await authorized(request(ctx.app).post('/api/server/action')).send({ action: 'restart' })).status, 200);
  assert.equal(ctx.docker.restarts, 1);
  assert.equal((await authorized(request(ctx.app).get('/api/server/logs'))).body.logs, 'Bedrock log de teste');
});

test('importa e substitui mundos com servidor parado e guarda o anterior', async (t) => {
  const ctx = await context(); t.after(ctx.cleanup);
  await authorized(request(ctx.app).post('/api/server')).send({ name: 'Teste', address: '203.0.113.10', acceptEula: true });
  const rejected = await importWorld(ctx.app);
  assert.equal(rejected.status, 409);
  assert.match(rejected.body.error, /Pare o servidor/);
  await authorized(request(ctx.app).post('/api/server/action')).send({ action: 'stop' });
  const first = await importWorld(ctx.app, 'Mundo original');
  assert.equal(first.status, 200);
  assert.equal(first.body.world.name, 'Mundo original');
  assert.equal(first.body.backupCreated, false);
  const second = await importWorld(ctx.app, 'Mundo novo');
  assert.equal(second.status, 200);
  assert.equal(second.body.backupCreated, true);
  assert.equal((await ctx.manager.files.worldInfo()).name, 'Mundo novo');
  const backups = await fs.readdir(path.join(ctx.root, 'bedrock', 'bloco-backups'));
  assert.equal(backups.length, 1);
  assert.equal(await fs.readFile(path.join(ctx.root, 'bedrock', 'bloco-backups', backups[0], 'levelname.txt'), 'utf8'), 'Mundo original');
  const invalid = await authorized(request(ctx.app).post('/api/world')).attach('file', await zip({ 'level.dat': 'x' }), 'ruim.mcworld');
  assert.equal(invalid.status, 400);
  assert.equal((await ctx.manager.files.worldInfo()).name, 'Mundo novo');
});

test('instala mcaddon com pacotes aninhados, ativa no mundo e exporta um mcworld', async (t) => {
  const ctx = await context(); t.after(ctx.cleanup);
  assert.equal((await importWorld(ctx.app)).status, 200);
  const behavior = await zip({ 'manifest.json': manifest(ID_BEHAVIOR, 'Super poderes', 'data'), 'functions/hello.mcfunction': 'say oi' });
  const resource = await zip({ 'manifest.json': manifest(ID_RESOURCE, 'Texturas verdes', 'resources'), 'textures/block.png': 'png' });
  const addon = await zip({ 'comportamento.mcpack': behavior, 'texturas.mcpack': resource });
  const result = await authorized(request(ctx.app).post('/api/packs')).attach('file', addon, 'novo.mcaddon');
  assert.equal(result.status, 200, JSON.stringify(result.body));
  assert.equal(result.body.added.length, 2);
  const behaviorList = JSON.parse(await fs.readFile(path.join(ctx.manager.files.world, 'world_behavior_packs.json'), 'utf8'));
  const resourceList = JSON.parse(await fs.readFile(path.join(ctx.manager.files.world, 'world_resource_packs.json'), 'utf8'));
  assert.deepEqual(behaviorList, [{ pack_id: ID_BEHAVIOR, version: [1, 2, 3] }]);
  assert.deepEqual(resourceList, [{ pack_id: ID_RESOURCE, version: [1, 2, 3] }]);
  const status = (await authorized(request(ctx.app).get('/api/status'))).body;
  assert.equal(status.packs.length, 2);
  assert.ok(status.packs.some((pack) => pack.name === 'Super poderes'));
  const download = await authorized(request(ctx.app).get('/api/world/download')).buffer(true).parse(binaryParser);
  assert.equal(download.status, 200);
  assert.match(download.headers['content-disposition'], /mcworld/);
  const downloadedPath = path.join(ctx.root, 'download.mcworld');
  const unpacked = path.join(ctx.root, 'downloaded');
  await fs.writeFile(downloadedPath, download.body);
  await extractArchive(downloadedPath, unpacked);
  assert.equal(await fs.readFile(path.join(unpacked, 'db', '000001.ldb'), 'utf8'), 'world-chunks');
  assert.ok(await fs.stat(path.join(unpacked, 'behavior_packs', ID_BEHAVIOR, 'manifest.json')));
  assert.ok(await fs.stat(path.join(unpacked, 'resource_packs', ID_RESOURCE, 'manifest.json')));
  const restored = await authorized(request(ctx.app).post('/api/world')).attach('file', download.body, 'backup.mcworld');
  assert.equal(restored.status, 200, JSON.stringify(restored.body));
  assert.equal(restored.body.embeddedPacks, 2);
  assert.equal((await ctx.manager.files.listPacks()).length, 2);
  assert.equal((await authorized(request(ctx.app).delete(`/api/packs/behavior/${ID_BEHAVIOR}`))).status, 200);
  assert.equal((await ctx.manager.files.listPacks()).length, 1);
  assert.ok(await fs.stat(path.join(ctx.root, 'bedrock', 'behavior_packs', ID_BEHAVIOR, 'manifest.json')));
});

test('rejeita arquivos inseguros, tipos inválidos e conteúdo sem manifesto', async (t) => {
  const ctx = await context(); t.after(ctx.cleanup);
  assert.equal((await authorized(request(ctx.app).post('/api/world')).attach('file', Buffer.from('x'), 'mundo.exe')).status, 400);
  assert.equal((await authorized(request(ctx.app).post('/api/world')).attach('file', Buffer.from('não é zip'), 'mundo.mcworld')).status, 400);
  const safe = await zip({ 'safe/evil.txt': 'surpresa', ...worldEntries() });
  const malicious = Buffer.from(safe.toString('binary').replaceAll('safe/evil.txt', '../x/evil.txt'), 'binary');
  const traversal = await authorized(request(ctx.app).post('/api/world')).attach('file', malicious, 'mundo.zip');
  assert.equal(traversal.status, 400);
  assert.equal((await ctx.manager.files.worldInfo()).exists, false);
  assert.equal((await importWorld(ctx.app)).status, 200);
  const invalidPack = await authorized(request(ctx.app).post('/api/packs')).attach('file', await zip({ 'hello.txt': 'nada' }), 'conteudo.mcpack');
  assert.equal(invalidPack.status, 400);
  assert.equal((await ctx.manager.files.listPacks()).length, 0);
  const tooLarge = path.join(ctx.root, 'expanded.zip');
  await fs.writeFile(tooLarge, await zip({ 'grande.txt': 'x'.repeat(1024) }));
  const budget = archiveBudget(); budget.maxBytes = 100;
  await assert.rejects(extractArchive(tooLarge, path.join(ctx.root, 'limit'), budget), /limite/);
});

test('impede operações simultâneas e não cria servidor sem Docker', async (t) => {
  const ctx = await context(); t.after(ctx.cleanup);
  ctx.docker.available = false;
  const data = { name: 'Teste', address: '203.0.113.10', acceptEula: true };
  const result = await authorized(request(ctx.app).post('/api/server')).send(data);
  assert.equal(result.status, 503);
  assert.equal(ctx.docker.options, null);
  let release;
  const blocker = ctx.manager.exclusive('teste', () => new Promise((resolve) => { release = resolve; }));
  const concurrent = await authorized(request(ctx.app).post('/api/server/action')).send({ action: 'stop' });
  assert.equal(concurrent.status, 409);
  release(); await blocker;
});

test('rejeita links simbólicos em arquivos importados', async (t) => {
  const ctx = await context(); t.after(ctx.cleanup);
  const archive = new yazl.ZipFile();
  archive.addBuffer(Buffer.from('alvo'), 'link', { mode: 0o120777 });
  archive.addBuffer(Buffer.from('dummy'), 'level.dat');
  archive.addBuffer(Buffer.from('dummy'), 'db/000001.ldb');
  archive.end();
  const chunks = []; for await (const chunk of archive.outputStream) chunks.push(chunk);
  const response = await authorized(request(ctx.app).post('/api/world')).attach('file', Buffer.concat(chunks), 'mundo.mcworld');
  assert.equal(response.status, 400);
  assert.equal((await ctx.manager.files.worldInfo()).exists, false);
});

test('mostra versão e jogadores apenas quando a consulta NetherNet responde', async (t) => {
  const { createServer } = await import('node:http');
  const ctx = await context(); t.after(ctx.cleanup);
  let valid = true;
  const telemetry = createServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(valid ? { version: '1.26.51', players: 4, maxPlayers: 10 } : { version: 'inválida', players: -1 }));
  });
  await new Promise((resolve) => telemetry.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => telemetry.close(resolve)));
  ctx.manager.telemetryPort = telemetry.address().port;
  const originalInspect = ctx.docker.handle.inspect;
  ctx.docker.handle.inspect = async () => {
    const details = await originalInspect();
    details.NetworkSettings.Networks = { 'bloco-network': { IPAddress: '127.0.0.1' } };
    return details;
  };
  await ctx.manager.create({ name: 'Mundo', address: '203.0.113.10', acceptEula: true });
  const status = await ctx.manager.status();
  assert.equal(status.server.state, 'online');
  assert.equal(status.server.version, '1.26.51');
  assert.equal(status.server.players, 4);
  valid = false;
  const invalid = await ctx.manager.status();
  assert.equal(invalid.server.version, null);
  assert.equal(invalid.server.players, null);
});
