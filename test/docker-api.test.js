import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import Docker from 'dockerode';
import { BedrockManager } from '../server/manager.js';

test('Dockerode troca as requisições esperadas com a API Docker', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bloco-docker-api-'));
  const socket = path.join(root, 'docker.sock');
  let options = null;
  let running = false;
  let pulled = false;
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://docker');
    const pathname = url.pathname.replace(/^\/v[0-9.]+/, '');
    const send = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
    if (pathname === '/_ping') { res.writeHead(200); res.end('OK'); return; }
    if (pathname.endsWith('/json') && pathname.includes('/containers/')) {
      if (!options) { send(404, { message: 'No such container' }); return; }
      send(200, { Id: 'container-id', Config: { Env: options.Env, Labels: options.Labels }, State: { Running: running, Health: { Status: 'starting' } }, NetworkSettings: { Networks: {} } });
      return;
    }
    if (pathname === '/images/create') { pulled = true; res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"status":"Download complete"}\n'); return; }
    if (pathname === '/containers/create') {
      let body = ''; for await (const chunk of req) body += chunk;
      options = JSON.parse(body);
      send(201, { Id: 'container-id', Warnings: [] }); return;
    }
    if (pathname.endsWith('/start')) { running = true; res.writeHead(204); res.end(); return; }
    if (pathname.endsWith('/stop')) { running = false; res.writeHead(204); res.end(); return; }
    send(404, { message: `Unexpected ${req.method} ${req.url}` });
  });
  await new Promise((resolve) => server.listen(socket, resolve));
  t.after(async () => { await new Promise((resolve) => server.close(resolve)); await fs.rm(root, { recursive: true, force: true }); });
  const docker = new Docker({ socketPath: socket });
  const manager = new BedrockManager({ docker, dataDir: path.join(root, 'bedrock'), stateDir: path.join(root, 'panel'), volumeName: 'bloco-bedrock-data', networkName: 'bloco-network', uid: process.getuid?.(), gid: process.getgid?.() });
  await manager.init();
  const result = await manager.create({ name: 'Mundo de teste', address: '203.0.113.10', acceptEula: true });
  assert.equal(result.created, true);
  assert.equal(pulled, true);
  assert.equal(running, true);
  assert.equal(options.HostConfig.NetworkMode, 'bloco-network');
  assert.deepEqual(options.HostConfig.Binds, ['bloco-bedrock-data:/data']);
  assert.equal((await manager.status()).server.state, 'starting');
  await manager.action('stop');
  assert.equal(running, false);
});
