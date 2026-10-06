#!/usr/bin/env python3
"""Strict PVTRACK1 reader, CSV exporter, and disk-sorted particle movie renderer.
Python standard library only; rendering additionally requires FFmpeg.
"""
import argparse
import collections
import csv
import json
import math
import pathlib
import sqlite3
import struct
import subprocess
import tempfile

HEADER = struct.Struct('<8s6Id3Q')
RECORD = struct.Struct('<IIQQQ12f')
FIELDS = ('type flags id frame related x y vx vy r g b confidence fb residual texture color_delta').split()


def read_header(f):
    data = f.read(HEADER.size)
    if len(data) != HEADER.size:
        raise ValueError('Truncated header')
    magic, version, hs, rs, w, h, seed, fps, frames, records, complete = HEADER.unpack(data)
    if (magic, version, hs, rs) != (b'PVTRACK1', 1, 64, 80):
        raise ValueError('Unsupported format')
    if complete != 1:
        raise ValueError('File was not finalized')
    if not (w > 0 and h > 0 and math.isfinite(fps) and fps > 0 and frames >= 2):
        raise ValueError('Invalid video metadata')
    return dict(width=w, height=h, seed=seed, fps=fps, frames=frames, records=records)


def read_records(f, header):
    for _ in range(header['records']):
        data = f.read(RECORD.size)
        if len(data) != RECORD.size:
            raise ValueError('Truncated record stream')
        r = RECORD.unpack(data)
        if r[0] not in (1, 2) or r[2] == 0 or r[3] >= header['frames']:
            raise ValueError('Invalid record identity or time')
        if not all(math.isfinite(v) for v in r[5:]):
            raise ValueError('Non-finite sample')
        yield r
    if f.read(1):
        raise ValueError('Unexpected trailing data')


def inspect(path, csv_path=None):
    with open(path, 'rb') as f:
        h = read_header(f)
        active, reasons, lengths = {}, collections.Counter(), collections.Counter()
        samples = 0
        dest = open(csv_path, 'w', newline='') if csv_path else None
        try:
            writer = csv.writer(dest) if dest else None
            if writer:
                writer.writerow(FIELDS)
            for r in read_records(f, h):
                kind, flags, ident, frame, related = r[:5]
                if writer:
                    writer.writerow(r)
                if kind == 1:
                    if ident not in active:
                        if not flags & 1:
                            raise ValueError('Track does not start with BORN')
                        active[ident] = [frame - 1, 0]
                    if active[ident][0] + 1 != frame:
                        raise ValueError('Non-consecutive samples within track')
                    active[ident][0] = frame
                    active[ident][1] += 1
                    samples += 1
                else:
                    if ident not in active:
                        raise ValueError('End without active track')
                    if related and (not flags & 64 or related not in active):
                        raise ValueError('Invalid merge ancestry')
                    last, length = active.pop(ident)
                    if frame < last:
                        raise ValueError('End precedes last sample')
                    lengths[length] += 1
                    reasons[str(flags)] += 1
            if active:
                raise ValueError('Unterminated tracks')
        finally:
            if dest:
                dest.close()
    return dict(**h, samples=samples, tracks=sum(lengths.values()),
                shortest_track=min(lengths, default=0), longest_track=max(lengths, default=0),
                end_flags=dict(reasons))


def render(path, output, radius=2):
    # Probation flushes records out of frame order. Sort on disk, not in RAM.
    with tempfile.TemporaryDirectory(prefix='pvt-render-') as tmp, open(path, 'rb') as f:
        h = read_header(f)
        db = sqlite3.connect(str(pathlib.Path(tmp) / 'frames.sqlite'))
        db.execute('CREATE TABLE samples(frame INTEGER, id INTEGER, data BLOB)')
        rows = ((r[3], r[2], RECORD.pack(*r)) for r in read_records(f, h) if r[0] == 1)
        db.executemany('INSERT INTO samples VALUES(?,?,?)', rows)
        db.execute('CREATE INDEX frame_index ON samples(frame,id)')
        db.commit()
        cursor = iter(db.execute('SELECT frame,data FROM samples ORDER BY frame,id'))
        row = next(cursor, None)
        w, ht = h['width'], h['height']
        cmd = ['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
               '-s', f'{w}x{ht}', '-r', str(h['fps']), '-i', 'pipe:0', '-an',
               '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-c:v', 'libx264',
               '-crf', '18', '-pix_fmt', 'yuv420p', str(pathlib.Path(output).absolute())]
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        try:
            for frame in range(h['frames']):
                pixels = bytearray(w * ht * 3)
                while row is not None and row[0] == frame:
                    r = RECORD.unpack(row[1])
                    x, y = round(r[5]), round(r[6])
                    rgb = bytes(max(0, min(255, round(v * 255))) for v in r[9:12])
                    for dy in range(-radius, radius + 1):
                        for dx in range(-radius, radius + 1):
                            if dx*dx + dy*dy <= radius*radius and 0 <= x+dx < w and 0 <= y+dy < ht:
                                k = ((y+dy)*w+x+dx)*3
                                pixels[k:k+3] = rgb
                    row = next(cursor, None)
                proc.stdin.write(pixels)
            proc.stdin.close()
            if proc.wait() != 0:
                raise RuntimeError('FFmpeg render failed')
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.wait()
            db.close()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=pathlib.Path)
    p.add_argument('--csv', type=pathlib.Path)
    p.add_argument('--render', type=pathlib.Path)
    p.add_argument('--radius', type=int, default=2)
    args = p.parse_args()
    if not 0 <= args.radius <= 100:
        p.error('radius must be in [0,100]')
    for output in (args.csv, args.render):
        if output and output.resolve() == args.input.resolve():
            p.error('output must differ from input')
    print(json.dumps(inspect(args.input, args.csv), indent=2))
    if args.render:
        render(args.input, args.render, args.radius)


if __name__ == '__main__':
    main()
