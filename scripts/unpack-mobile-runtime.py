#!/usr/bin/env python3
"""Unpack the same runtime format as the Android installer (CI verification)."""
import json
import os
from pathlib import Path
import stat
import sys
import zipfile

archive, destination = map(Path, sys.argv[1:3])
destination.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as z:
    if z.testzip() is not None:
        raise RuntimeError('Runtime ZIP corrupt')
    manifest = json.loads(z.read('runtime-manifest.json'))
    if manifest['format'] != 1:
        raise RuntimeError('Unknown runtime format')
    for entry in z.infolist():
        name = entry.filename
        if name.startswith('/') or '..' in Path(name).parts:
            raise RuntimeError('Unsafe runtime entry')
        target = destination / name
        if entry.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(entry) as src, target.open('wb') as out:
                while chunk := src.read(128 * 1024):
                    out.write(chunk)
    for name, target in manifest['links']:
        link = destination / name
        if name.startswith('/') or '..' in Path(name).parts or link.exists() or link.is_symlink():
            raise RuntimeError('Unsafe runtime link')
        link.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(target, link)
    for name in manifest['executable']:
        file = destination / name
        if name.startswith('/') or '..' in Path(name).parts or file.is_symlink() or not file.is_file():
            raise RuntimeError('Unsafe executable path')
        file.chmod(file.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
print('Runtime extraído e verificado')
