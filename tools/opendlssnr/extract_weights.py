"""Extract WEIGHTS_HT from the 310.8.Lecram DLL without loading or executing it.

Usage: python scripts/extract_nr_weights.py nvngx_dlssnr.dll models/nr
Only the observed 71-block, 153-tensor binary16-container serialization is accepted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct


def extract_resource(binary):
    def unpack(fmt, offset):
        return struct.unpack_from('<' + fmt, binary, offset)

    if binary[:2] != b'MZ':
        raise ValueError('Expected a PE DLL')
    pe, = unpack('I', 0x3c)
    if binary[pe:pe + 4] != b'PE\0\0':
        raise ValueError('Invalid PE signature')
    count, = unpack('H', pe + 6)
    optional_size, = unpack('H', pe + 20)
    optional = pe + 24
    if unpack('H', optional)[0] != 0x20b:
        raise ValueError('Expected PE32+')
    sections = []
    for index in range(count):
        virtual_size, address, size, raw = unpack('IIII', optional + optional_size + index * 40 + 8)
        sections.append((address, virtual_size, size, raw))

    def file_offset(address, length=1):
        for va, vs, size, raw in sections:
            relative = address - va
            if 0 <= relative and relative + length <= size and raw + relative + length <= len(binary):
                return raw + relative
        raise ValueError('Resource points outside file-backed PE sections')

    resource_rva, resource_size = unpack('II', optional + 112 + 16)
    base = file_offset(resource_rva, resource_size)
    found = []

    def walk(relative, path):
        if len(path) > 3 or relative + 16 > resource_size:
            raise ValueError('Invalid resource directory')
        named, ids = unpack('HH', base + relative + 12)
        for index in range(named + ids):
            entry = relative + 16 + index * 8
            if entry + 8 > resource_size:
                raise ValueError('Resource directory exceeds bounds')
            name, child = unpack('II', base + entry)
            if name & 0x80000000:
                name_offset = name & 0x7fffffff
                chars, = unpack('H', base + name_offset)
                if name_offset + 2 + chars * 2 > resource_size:
                    raise ValueError('Resource name exceeds bounds')
                name = binary[base + name_offset + 2:base + name_offset + 2 + chars * 2].decode('utf-16le')
            if child & 0x80000000:
                walk(child & 0x7fffffff, path + [name])
            elif path == [10, 'WEIGHTS_HT']:
                if child + 16 > resource_size:
                    raise ValueError('Resource data entry exceeds bounds')
                address, size, _, _ = unpack('IIII', base + child)
                start = file_offset(address, size)
                found.append(binary[start:start + size])

    walk(0, [])
    if len(found) != 1:
        raise ValueError('Expected exactly one WEIGHTS_HT resource')
    return found[0]


def parse_weights(blob):
    if struct.unpack_from('<Q', blob)[0] != len(blob):
        raise ValueError('Serialized weight map size mismatch')
    position = 8
    tensors = []
    seen = set()
    while position < len(blob):
        name_size, = struct.unpack_from('<Q', blob, position)
        position += 8
        if not 0 < name_size <= 128:
            raise ValueError('Invalid tensor name length')
        name = blob[position:position + name_size].decode('ascii')
        position += name_size
        match = re.fullmatch(r'block(\d+)\.layer(\d+)\.(layer|blend_scale)', name)
        if not match or name in seen:
            raise ValueError('Invalid or duplicate tensor: ' + name)
        seen.add(name)
        size, = struct.unpack_from('<Q', blob, position)
        position += 8
        total, data_size, device = struct.unpack_from('<QQI', blob, position)
        if size != total or size != data_size + 40 or device != 1 or position + size > len(blob):
            raise ValueError('Unsupported tensor serialization: ' + name)
        data = blob[position + 20:position + 20 + data_size]
        dtype, rank, elements = struct.unpack_from('<QQI', blob, position + 20 + data_size)
        if dtype != 0 or rank != 1 or elements * 2 != data_size:
            raise ValueError('Expected a flat binary16 storage container: ' + name)
        tensors.append((int(match[1]), int(match[2]), match[3], name, data))
        position += size
    if position != len(blob) or len(tensors) != 153 or {t[0] for t in tensors} != set(range(71)):
        raise ValueError('Expected the complete 71-block, 153-tensor model')
    return tensors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dll', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    binary = args.dll.read_bytes()
    expected = 'f95feb54137ea11979f9b4ec4f00afd84b5c98a5624d3388fbf6a87714a39fcc'
    if hashlib.sha256(binary).hexdigest() != expected:
        raise ValueError('Unsupported DLL: this extractor is pinned to RankFTW 310.8.Lecram')
    blob = extract_resource(binary)
    tensors = parse_weights(blob)
    if args.output.exists():
        raise ValueError('Output already exists; choose a new directory')
    stages = []
    entries = []
    packed = bytearray()
    # One stage holds the original packed bytes. No numeric decoding or repacking.
    for block, layer, parameter, name, data in sorted(tensors):
        entries.append(dict(name=name, block=block, layer=layer, parameter=parameter,
                            stage='nr', stageOffset=len(packed), byteLength=len(data)))
        packed.extend(data)
    stages.append(dict(id='nr', file='stages/nr.bin', packedByteLength=len(packed),
                       sha256=hashlib.sha256(packed).hexdigest()))
    manifest = dict(totals=dict(blockCount=71, tensorCount=len(entries), packedByteLength=len(packed)),
                    stages=stages, tensors=entries,
                    source=dict(file=args.dll.name, sha256=hashlib.sha256(binary).hexdigest(),
                                resource='WEIGHTS_HT', resourceSha256=hashlib.sha256(blob).hexdigest()))
    (args.output / 'model/stages').mkdir(parents=True)
    target = args.output / 'model/stages/nr.bin'
    target.write_bytes(packed)
    if hashlib.sha256(target.read_bytes()).hexdigest() != stages[0]['sha256']:
        raise ValueError('Disk readback hash mismatch')
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Extracted {len(entries)} tensors, 71 blocks, {len(packed):,} packed bytes to {args.output}')
    print('Stage SHA-256:', stages[0]['sha256'])


if __name__ == '__main__':
    main()
