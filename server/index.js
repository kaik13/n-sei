import path from 'node:path';
import Docker from 'dockerode';
import { BedrockManager } from './manager.js';
import { createApp } from './app.js';

const port = Number(process.env.PORT || 3000);
const docker = new Docker({ socketPath: process.env.DOCKER_SOCKET || '/var/run/docker.sock' });
const dataDir = process.env.BEDROCK_DATA_DIR || path.resolve('data/bedrock');
const manager = new BedrockManager({
  docker,
  dataDir,
  stateDir: process.env.PANEL_STATE_DIR || path.resolve('data/panel'),
  // On a host-based development install, bind the same directory the panel edits.
  // Docker Compose supplies the shared named volume instead.
  volumeName: process.env.BEDROCK_VOLUME || dataDir,
  networkName: process.env.BEDROCK_NETWORK || '',
  hostHint: process.env.PUBLIC_IPV4 || '',
});
await manager.init();
const app = createApp({ manager, adminToken: process.env.ADMIN_TOKEN || '', staticDir: path.resolve('dist'), trustProxy: process.env.TRUST_PROXY === '1' });
if (!process.env.ADMIN_TOKEN || process.env.ADMIN_TOKEN.length < 16) {
  console.warn('Modo de apresentação: configure ADMIN_TOKEN (16+ caracteres) para habilitar alterações.');
}
app.listen(port, '0.0.0.0', () => console.log(`Bloco disponível na porta ${port}`));
