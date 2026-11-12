import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import multer from 'multer';
import { ZipArchive } from 'archiver';
import fs from 'node:fs/promises';
import path from 'node:path';
import { timingSafeEqual } from 'node:crypto';
import { AppError } from './errors.js';

const asyncRoute = (handler) => (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);

function tokenMatches(actual, expected) {
  const a = Buffer.from(actual || '');
  const b = Buffer.from(expected || '');
  return a.length === b.length && a.length > 0 && timingSafeEqual(a, b);
}

export function createApp({ manager, adminToken = '', staticDir = path.resolve('dist'), trustProxy = false }) {
  const app = express();
  app.disable('x-powered-by');
  if (trustProxy) app.set('trust proxy', 1);
  app.use(helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        scriptSrc: ["'self'"],
        styleSrc: ["'self'", "'unsafe-inline'"],
        imgSrc: ["'self'", 'data:', 'blob:'],
        fontSrc: ["'self'"],
        connectSrc: ["'self'"],
        objectSrc: ["'none'"],
        frameAncestors: ["'self'", 'https://arena.ai', 'https://*.arena.ai', 'https://*.e2b.app'],
        upgradeInsecureRequests: null,
      },
    },
    // The hosted preview embeds the application; deployments should place the panel behind HTTPS.
    crossOriginEmbedderPolicy: false,
    xFrameOptions: false,
  }));
  app.use('/api', (req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });
  app.use('/api', rateLimit({ windowMs: 60_000, limit: 120, standardHeaders: 'draft-7', legacyHeaders: false, message: { error: 'Muitas solicitações. Aguarde um pouco.' } }));
  app.use(express.json({ limit: '32kb' }));

  const configured = adminToken.length >= 16;
  app.get('/api/health', (_req, res) => res.json({ needsAuth: Boolean(adminToken), panelConfigured: configured }));

  const authLimiter = rateLimit({
    windowMs: 15 * 60_000, limit: 20, standardHeaders: 'draft-7', legacyHeaders: false,
    skipSuccessfulRequests: true, message: { error: 'Muitas tentativas. Aguarde antes de tentar novamente.' },
  });
  app.use('/api', authLimiter, (req, res, next) => {
    if (!adminToken) return next(); // Presentation mode: status is readable, all mutations stay disabled.
    const header = req.get('authorization') || '';
    const candidate = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!tokenMatches(candidate, adminToken)) return res.status(401).json({ error: 'Chave de acesso incorreta.' });
    next();
  });

  const requireConfigured = (_req, _res, next) => {
    if (!configured) return next(new AppError(503, 'Configure uma chave ADMIN_TOKEN com pelo menos 16 caracteres na hospedagem antes de fazer alterações.'));
    next();
  };
  const upload = multer({ dest: path.join(manager.stateDir, 'uploads'), limits: { fileSize: 512 * 1024 * 1024, files: 1, fields: 4 } });
  const validateUpload = (allowed) => (req, _res, next) => {
    if (!req.file) return next(new AppError(400, 'Escolha um arquivo para importar.'));
    if (!allowed.includes(path.extname(req.file.originalname).toLowerCase())) return next(new AppError(400, `Use um arquivo ${allowed.join(', ')}.`));
    next();
  };
  // Ensure discarded uploads are cleaned even when validation fails.
  const cleanupUpload = (req, res, next) => {
    res.once('finish', () => { if (req.file?.path) fs.rm(req.file.path, { force: true }).catch(() => {}); });
    next();
  };

  app.get('/api/status', asyncRoute(async (_req, res) => {
    res.json({ ...await manager.status(), panelConfigured: configured });
  }));

  app.post('/api/server', requireConfigured, asyncRoute(async (req, res) => {
    res.status(201).json(await manager.create(req.body || {}));
  }));
  app.post('/api/server/action', requireConfigured, asyncRoute(async (req, res) => {
    res.json(await manager.action(req.body?.action));
  }));
  app.get('/api/server/logs', requireConfigured, asyncRoute(async (_req, res) => {
    await manager.ensureDocker();
    const found = await manager.container();
    if (!found) throw new AppError(404, 'Crie um servidor primeiro.');
    const logs = await found.container.logs({ stdout: true, stderr: true, tail: 120, timestamps: false });
    res.json({ logs: Buffer.isBuffer(logs) ? logs.toString('utf8').slice(-40_000) : String(logs).slice(-40_000) });
  }));

  app.post('/api/world', requireConfigured, upload.single('file'), cleanupUpload, validateUpload(['.mcworld', '.zip']), asyncRoute(async (req, res) => {
    const result = await manager.exclusive('importando mundo', async () => {
      await manager.ensureStopped();
      return manager.files.importWorld(req.file.path);
    });
    res.json(result);
  }));

  app.post('/api/packs', requireConfigured, upload.single('file'), cleanupUpload, validateUpload(['.mcpack', '.mcaddon', '.zip']), asyncRoute(async (req, res) => {
    const result = await manager.exclusive('importando conteúdo', async () => {
      await manager.ensureStopped();
      return manager.files.importPacks(req.file.path);
    });
    res.json(result);
  }));

  app.delete('/api/packs/:kind/:id', requireConfigured, asyncRoute(async (req, res) => {
    const result = await manager.exclusive('desativando conteúdo', async () => {
      await manager.ensureStopped();
      return manager.files.removePack(req.params.kind, req.params.id);
    });
    res.json(result);
  }));

  app.get('/api/world/download', requireConfigured, asyncRoute(async (_req, res) => {
    await manager.exclusive('preparando download', async () => {
      await manager.ensureStopped();
      if (!(await manager.files.worldInfo()).exists) throw new AppError(404, 'Ainda não há um mundo para baixar.');
      const packs = await manager.files.listPacks();
      res.setHeader('Content-Type', 'application/octet-stream');
      res.setHeader('Content-Disposition', 'attachment; filename="mundo-bloco.mcworld"');
      const archive = new ZipArchive({ zlib: { level: 6 } });
      await new Promise((resolve, reject) => {
        archive.on('error', reject);
        res.on('error', reject);
        res.on('finish', resolve);
        res.on('close', () => { if (!res.writableFinished) { archive.abort(); resolve(); } });
        archive.pipe(res);
        // Repackage the active globally installed packs inside the exported world.
        archive.glob('**/*', { cwd: manager.files.world, dot: true, ignore: ['behavior_packs/**', 'resource_packs/**'] });
        for (const pack of packs) {
          const folder = pack.kind === 'behavior' ? 'behavior_packs' : 'resource_packs';
          const local = path.join(manager.files.root, folder, pack.id);
          archive.directory(local, `${folder}/${pack.id}`);
        }
        archive.finalize().catch(reject);
      });
    });
  }));

  app.use('/api', (_req, res) => res.status(404).json({ error: 'Rota não encontrada.' }));
  app.use(express.static(staticDir, { index: 'index.html', maxAge: '1h' }));
  app.get('*', (_req, res, next) => res.sendFile(path.join(staticDir, 'index.html'), (error) => { if (error) next(); }));
  app.use((error, _req, res, _next) => {
    if (res.headersSent) { res.destroy(); return; }
    if (error instanceof multer.MulterError) {
      return res.status(400).json({ error: error.code === 'LIMIT_FILE_SIZE' ? 'O arquivo deve ter no máximo 512 MB.' : 'Não foi possível receber o arquivo.' });
    }
    if (error instanceof AppError) return res.status(error.status).json({ error: error.message });
    if (error?.type === 'entity.parse.failed') return res.status(400).json({ error: 'Dados inválidos.' });
    console.error('Erro do painel:', error);
    res.status(500).json({ error: 'Algo deu errado. Tente novamente ou verifique os logs do painel.' });
  });
  return app;
}
