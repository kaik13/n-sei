import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import yauzl from 'yauzl';
import { AppError } from './errors.js';

// Shared by all nested archives in a single upload, not just each individual ZIP.
export function archiveBudget() {
  return { files: 0, bytes: 0, archives: 0, maxFiles: 15000, maxBytes: 1024 * 1024 * 1024, maxArchives: 24 };
}

function openZip(file) {
  return new Promise((resolve, reject) => {
    yauzl.open(file, { lazyEntries: true, validateEntrySizes: true, strictFileNames: true }, (error, zip) => {
      if (error) reject(new AppError(400, 'Não foi possível abrir o arquivo. Confira se ele é um ZIP válido.'));
      else resolve(zip);
    });
  });
}

function safePath(name, destination) {
  if (!name || name.length > 1024 || name.includes('\\') || name.includes('\0') || name.startsWith('/') || /^[a-z]:/i.test(name)) {
    throw new AppError(400, 'O arquivo contém caminhos inválidos.');
  }
  const segments = name.split('/');
  if (segments.some((part) => part === '..' || part === '.')) throw new AppError(400, 'O arquivo contém caminhos inválidos.');
  const target = path.resolve(destination, ...segments);
  if (target !== destination && !target.startsWith(destination + path.sep)) throw new AppError(400, 'O arquivo contém caminhos inválidos.');
  return target;
}

export async function extractArchive(file, destination, budget = archiveBudget()) {
  destination = path.resolve(destination);
  if (++budget.archives > budget.maxArchives) throw new AppError(400, 'O arquivo contém arquivos compactados demais.');
  await fsp.mkdir(destination, { recursive: true });
  const zip = await openZip(file);

  await new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      zip.close();
      if (error) reject(error instanceof AppError ? error : new AppError(400, 'O arquivo está corrompido ou não pôde ser extraído.'));
      else resolve();
    };
    zip.on('error', finish);
    zip.on('end', () => finish());
    zip.on('entry', (entry) => {
      (async () => {
        if (++budget.files > budget.maxFiles) throw new AppError(400, 'O arquivo contém itens demais.');
        if (entry.generalPurposeBitFlag & 1) throw new AppError(400, 'Arquivos protegidos por senha não são aceitos.');
        const unixMode = entry.externalFileAttributes >>> 16;
        if ((unixMode & 0o170000) === 0o120000) throw new AppError(400, 'O arquivo contém links que não são permitidos.');
        const target = safePath(entry.fileName, destination);
        if (/\/$/.test(entry.fileName)) {
          await fsp.mkdir(target, { recursive: true });
        } else {
          if (entry.uncompressedSize > budget.maxBytes - budget.bytes) throw new AppError(400, 'O conteúdo descompactado ultrapassa o limite de 1 GB.');
          await fsp.mkdir(path.dirname(target), { recursive: true });
          const stream = await new Promise((res, rej) => zip.openReadStream(entry, (error, result) => error ? rej(error) : res(result)));
          const meter = new Transform({
            transform(chunk, _encoding, callback) {
              budget.bytes += chunk.length;
              if (budget.bytes > budget.maxBytes) callback(new AppError(400, 'O conteúdo descompactado ultrapassa o limite de 1 GB.'));
              else callback(null, chunk);
            },
          });
          // Exclusive creation also rejects duplicate paths and file/directory collisions.
          await pipeline(stream, meter, fs.createWriteStream(target, { flags: 'wx', mode: 0o644 }));
        }
        if (!settled) zip.readEntry();
      })().catch(finish);
    });
    zip.readEntry();
  });
}

export async function walk(directory, visitor) {
  const items = await fsp.readdir(directory, { withFileTypes: true });
  for (const item of items) {
    if (item.name === '__MACOSX' || item.name === '.DS_Store') continue;
    const full = path.join(directory, item.name);
    if (item.isSymbolicLink()) throw new AppError(400, 'Links não são permitidos no arquivo.');
    await visitor(full, item);
    if (item.isDirectory()) await walk(full, visitor);
  }
}
