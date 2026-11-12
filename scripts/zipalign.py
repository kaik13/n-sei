#!/usr/bin/env python3
"""Align stored entries to 4-byte offsets before APK signing (no native libraries)."""
import argparse
import struct
import zipfile


def align(source, destination):
    with zipfile.ZipFile(source, 'r') as src, zipfile.ZipFile(destination, 'w', allowZip64=False) as dst:
        for old in src.infolist():
            item = zipfile.ZipInfo(old.filename, old.date_time)
            item.compress_type = old.compress_type
            item.comment = old.comment
            item.create_system = old.create_system
            item.external_attr = old.external_attr
            item.internal_attr = old.internal_attr
            if old.compress_type == zipfile.ZIP_STORED:
                # A valid extra field keeps the local file's payload aligned without changing data.
                name = old.filename.encode('utf-8')
                offset = dst.fp.tell() + 30 + len(name)
                padding = (-(offset + 4)) % 4
                item.extra = struct.pack('<HH', 0xD935, padding) + b'\0' * padding
            dst.writestr(item, src.read(old.filename))


def verify(archive):
    with open(archive, 'rb') as raw, zipfile.ZipFile(archive, 'r') as zf:
        for item in zf.infolist():
            if item.compress_type != zipfile.ZIP_STORED:
                continue
            raw.seek(item.header_offset)
            header = raw.read(30)
            if len(header) != 30 or header[:4] != b'PK\x03\x04':
                raise ValueError(f'Invalid local header: {item.filename}')
            filename_length, extra_length = struct.unpack_from('<HH', header, 26)
            if (item.header_offset + 30 + filename_length + extra_length) % 4:
                raise ValueError(f'Unaligned entry: {item.filename}')
        if zf.testzip() is not None:
            raise ValueError('Invalid ZIP CRC')


parser = argparse.ArgumentParser()
parser.add_argument('--verify', action='store_true')
parser.add_argument('source')
parser.add_argument('destination', nargs='?')
args = parser.parse_args()
if args.verify:
    verify(args.source)
else:
    if not args.destination:
        parser.error('destination is required')
    align(args.source, args.destination)
    verify(args.destination)
