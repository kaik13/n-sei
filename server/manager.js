import fs from 'node:fs/promises';
import path from 'node:path';
import { isIP } from 'node:net';
import { randomUUID } from 'node:crypto';
import { AppError } from './errors.js';
import { BedrockFiles } from './files.js';

const IMAGE = 'itzg/minecraft-bedrock-server:latest';
const NAME = 'bloco-bedrock';
const MAX_PLAYERS = 10;
const LABEL = 'app.bloco.managed';

function publicError(error) {
  if (error?.statusCode === 409) return 'Já existe um servidor com esse nome ou uma porta está em uso.';
  if (error?.code === 'ENOENT' || error?.code === 'ECONNREFUSED' || error?.code === 'EACCES') return 'Não foi possível conectar ao Docker da hospedagem.';
  return 'Não foi possível falar com a hospedagem. Verifique o Docker e tente novamente.';
}

export class BedrockManager {
  constructor({ docker, dataDir, stateDir, volumeName = 'bloco-bedrock-data', networkName = '', hostHint = '', uid, gid, image = IMAGE, containerName = NAME, telemetryPort = 19132 }) {
    this.docker = docker;
    this.stateDir = stateDir;
    this.configFile = path.join(stateDir, 'server.json');
    this.volumeName = volumeName;
    this.networkName = networkName;
    this.hostHint = isIP(hostHint) === 4 ? hostHint : '';
    this.image = image;
    this.containerName = containerName;
    this.telemetryPort = telemetryPort;
    const ownerUid = uid ?? (process.getuid?.() === 0 ? 1000 : process.getuid?.());
    const ownerGid = gid ?? (process.getuid?.() === 0 ? 1000 : process.getgid?.());
    this.uid = ownerUid;
    this.gid = ownerGid;
    this.files = new BedrockFiles(dataDir, { uid: ownerUid, gid: ownerGid });
    this.busy = null;
  }

  async init() {
    await fs.mkdir(this.stateDir, { recursive: true });
    await this.files.initialize();
  }

  async readConfig() {
    try { return JSON.parse(await fs.readFile(this.configFile, 'utf8')); }
    catch (error) { if (error.code === 'ENOENT') return null; throw error; }
  }

  async writeConfig(config) {
    const temp = `${this.configFile}.${randomUUID()}.tmp`;
    try {
      await fs.writeFile(temp, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
      await fs.rename(temp, this.configFile);
    } finally { await fs.rm(temp, { force: true }); }
  }

  async exclusive(label, action) {
    if (this.busy) throw new AppError(409, 'Outra operação está em andamento. Aguarde um instante.');
    this.busy = label;
    try { return await action(); } finally { this.busy = null; }
  }

  async ping() {
    try { await this.docker.ping(); return true; } catch { return false; }
  }

  async ensureDocker() {
    if (!(await this.ping())) throw new AppError(503, 'Hospedagem não conectada. Configure este painel em uma máquina com Docker.');
  }

  async container() {
    const container = this.docker.getContainer(this.containerName);
    try {
      const details = await container.inspect();
      if (details?.Config?.Labels?.[LABEL] !== 'true') throw new AppError(409, 'Já existe outro contêiner com o nome do servidor.');
      return { container, details };
    } catch (error) {
      if (error?.statusCode === 404) return null;
      if (error instanceof AppError) throw error;
      throw new AppError(503, publicError(error));
    }
  }

  async ensureStopped() {
    await this.ensureDocker();
    const found = await this.container();
    if (found?.details?.State?.Running) throw new AppError(409, 'Pare o servidor antes de alterar ou baixar o mundo.');
    return found;
  }

  async queryServer(details) {
    const networks = Object.values(details?.NetworkSettings?.Networks || {});
    const preferred = details?.NetworkSettings?.Networks?.[this.networkName];
    const address = preferred?.IPAddress || networks.find((item) => item.IPAddress)?.IPAddress;
    if (!address || isIP(address) !== 4) return null;
    try {
      const response = await fetch(`http://${address}:${this.telemetryPort}/v1/join`, { signal: AbortSignal.timeout(1500) });
      if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) return null;
      const text = await response.text();
      if (text.length > 10000) return null;
      const body = JSON.parse(text);
      return {
        players: Number.isInteger(body.players) && body.players >= 0 ? body.players : null,
        version: typeof body.version === 'string' && /^[\d.]+$/.test(body.version) ? body.version : null,
      };
    } catch { return null; }
  }

  async status() {
    const config = await this.readConfig();
    const dockerAvailable = await this.ping();
    let found = null;
    let hostError = null;
    if (dockerAvailable) {
      try { found = await this.container(); } catch (error) { hostError = error.message; }
    }
    let state = !dockerAvailable ? 'unknown' : 'not_created';
    let telemetry = null;
    if (found) {
      if (found.details.State?.Running) {
        telemetry = await this.queryServer(found.details);
        const health = found.details.State?.Health?.Status;
        state = telemetry || health === 'healthy' ? 'online' : health === 'unhealthy' ? 'error' : 'starting';
      } else state = 'offline';
    }
    const env = Object.fromEntries((found?.details?.Config?.Env || []).map((line) => {
      const idx = line.indexOf('=');
      return [line.slice(0, idx), line.slice(idx + 1)];
    }));
    const envAddress = env.SERVER_UDP_PORTS?.split(':')[0];
    const serverConfig = config || (found ? { name: env.SERVER_NAME, address: isIP(envAddress) === 4 ? envAddress : '', mode: env.GAMEMODE, difficulty: env.DIFFICULTY, maxPlayers: MAX_PLAYERS } : null);
    let world = { exists: false, name: null };
    let packs = [];
    try { world = await this.files.worldInfo(); packs = await this.files.listPacks(); } catch { /* Status remains usable if world metadata is corrupt. */ }
    return {
      dockerAvailable,
      hostError,
      hostHint: this.hostHint,
      busy: this.busy,
      server: {
        exists: Boolean(found) || (!dockerAvailable && Boolean(serverConfig)),
        state,
        running: Boolean(found?.details?.State?.Running),
        name: serverConfig?.name || 'Meu servidor',
        address: serverConfig?.address || null,
        port: 19132,
        mode: serverConfig?.mode || 'survival',
        difficulty: serverConfig?.difficulty || 'normal',
        maxPlayers: MAX_PLAYERS,
        players: telemetry?.players ?? null,
        version: telemetry?.version ?? null,
      },
      world,
      packs,
    };
  }

  async pullImage() {
    try {
      const stream = await this.docker.pull(this.image);
      await new Promise((resolve, reject) => this.docker.modem.followProgress(stream, (error, output) => {
        if (error) reject(error);
        else {
          const failed = output?.find((item) => item.error || item.errorDetail);
          if (failed) reject(new Error('Image pull failed'));
          else resolve();
        }
      }));
    } catch {
      throw new AppError(503, 'Não foi possível baixar o servidor Bedrock. Confira a internet da hospedagem e tente novamente.');
    }
  }

  async create(input) {
    return this.exclusive('criando', async () => {
      const name = typeof input.name === 'string' ? input.name.trim() : '';
      const address = typeof input.address === 'string' ? input.address.trim() : '';
      const mode = input.mode || 'survival';
      const difficulty = input.difficulty || 'normal';
      if (name.length < 2 || name.length > 40 || /[\x00-\x1f\x7f]/.test(name)) throw new AppError(400, 'Escolha um nome entre 2 e 40 caracteres, sem quebras de linha.');
      if (isIP(address) !== 4) throw new AppError(400, 'Informe o endereço IPv4 da hospedagem.');
      if (!['survival', 'creative', 'adventure'].includes(mode)) throw new AppError(400, 'Modo de jogo inválido.');
      if (!['peaceful', 'easy', 'normal', 'hard'].includes(difficulty)) throw new AppError(400, 'Dificuldade inválida.');
      if (input.acceptEula !== true) throw new AppError(400, 'É necessário aceitar os termos do Minecraft.');
      await this.ensureDocker();
      if (await this.container()) throw new AppError(409, 'Este painel já possui um servidor.');
      await this.files.initialize();
      await this.pullImage();
      const ports = { '19132/tcp': {} };
      const portBindings = { '19132/tcp': [{ HostPort: '19132' }] };
      for (let port = 19140; port <= 19155; port++) {
        ports[`${port}/udp`] = {};
        portBindings[`${port}/udp`] = [{ HostPort: String(port) }];
      }
      const config = { name, address, mode, difficulty, maxPlayers: MAX_PLAYERS, eulaAcceptedAt: new Date().toISOString() };
      let container;
      try {
        container = await this.docker.createContainer({
          name: this.containerName,
          Image: this.image,
          Env: [
            'EULA=TRUE', 'VERSION=LATEST', `SERVER_NAME=${name}`, 'LEVEL_NAME=world',
            `GAMEMODE=${mode}`, `DIFFICULTY=${difficulty}`, `MAX_PLAYERS=${MAX_PLAYERS}`,
            'ONLINE_MODE=true', 'ALLOW_LIST=false', 'TRANSPORT=nethernet',
            `SERVER_UDP_PORTS=${address}:19140-19155:19140-19155`,
            ...(this.uid != null ? [`UID=${this.uid}`] : []),
            ...(this.gid != null ? [`GID=${this.gid}`] : []),
          ],
          Labels: { [LABEL]: 'true' },
          ExposedPorts: ports,
          OpenStdin: true,
          Tty: true,
          HostConfig: {
            Binds: [`${this.volumeName}:/data`],
            PortBindings: portBindings,
            RestartPolicy: { Name: 'unless-stopped' },
            ...(this.networkName ? { NetworkMode: this.networkName } : {}),
          },
        });
        try { await this.writeConfig(config); }
        catch (error) { await container.remove({ force: true }).catch(() => {}); throw error; }
        await container.start();
      } catch (error) {
        if (error instanceof AppError) throw error;
        throw new AppError(503, container ? 'O servidor foi criado, mas não iniciou. Verifique as portas da hospedagem e tente iniciá-lo novamente.' : publicError(error));
      }
      return { created: true };
    });
  }

  async action(action) {
    return this.exclusive(action, async () => {
      if (!['start', 'stop', 'restart'].includes(action)) throw new AppError(400, 'Ação inválida.');
      await this.ensureDocker();
      const found = await this.container();
      if (!found) throw new AppError(404, 'Crie um servidor primeiro.');
      const running = Boolean(found.details.State?.Running);
      try {
        if (action === 'start' && !running) await found.container.start();
        else if (action === 'stop' && running) await found.container.stop({ t: 45 });
        else if (action === 'restart' && running) await found.container.restart({ t: 45 });
        else if (action === 'restart' && !running) await found.container.start();
      } catch (error) { throw new AppError(503, publicError(error)); }
      return { ok: true };
    });
  }
}
