#!/usr/bin/env python3
"""Durable Drawing On receiver. /watch forwards newly committed events to Field."""
from __future__ import annotations
import argparse
import asyncio
import contextlib
import json
import os
from pathlib import Path
import uuid

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed


class Archive:
    def __init__(self, directory: Path):
        self.directory = directory
        directory.mkdir(parents=True, exist_ok=True)
        self.heads: dict[str, int] = {}
        for path in directory.glob('*.ndjson'):
            session = str(uuid.UUID(path.stem))
            head, valid = 0, 0
            with path.open('rb') as stream:
                for line in stream:
                    if not line.endswith(b'\n'):
                        break
                    event = json.loads(line)
                    if str(uuid.UUID(event['session'])) != session or event['seq'] != head + 1:
                        raise ValueError(f'Invalid journal sequence in {path}')
                    head += 1
                    valid += len(line)
            with path.open('r+b') as stream:
                stream.truncate(valid)
            self.heads[session] = head

    def commit(self, event: dict) -> tuple[dict, bool]:
        reply, fresh = self.commit_many([event])
        return reply, bool(fresh)

    def commit_many(self, events: list[dict]) -> tuple[dict, list[dict]]:
        if not isinstance(events, list) or not 1 <= len(events) <= 64:
            raise ValueError('A batch must contain 1 to 64 events')
        session = str(uuid.UUID(events[0]['session']))
        original_head = self.heads.get(session, 0)
        head, fresh = original_head, []
        for event in events:
            seq = event['seq']
            if (str(uuid.UUID(event['session'])) != session or event.get('v') != 1
                    or type(seq) is not int or seq < 1 or not isinstance(event.get('type'), str)
                    or not isinstance(event.get('data'), dict) or not isinstance(event.get('t'), (float, int))):
                raise ValueError('Invalid v1 event')
            if seq > head + 1:
                return {'replayFrom': original_head + 1}, []
            if seq <= head:
                continue
            fresh.append(event)
            head = seq
        if fresh:
            encoded = b''.join(json.dumps(event, separators=(',', ':'), allow_nan=False).encode() + b'\n' for event in fresh)
            with (self.directory / f'{session}.ndjson').open('ab') as stream:
                start = stream.tell()
                try:
                    stream.write(encoded)
                    stream.flush()
                    os.fsync(stream.fileno())
                except OSError:
                    stream.truncate(start)
                    raise
            self.heads[session] = head
        return {'ack': events[-1]['seq'], 'session': session}, fresh


class Receiver:
    def __init__(self, archive: Archive):
        self.archive = archive
        self.watchers: set[asyncio.Queue] = set()

    async def handle(self, socket):
        if socket.request.path == '/watch':
            queue: asyncio.Queue = asyncio.Queue(maxsize=512)
            self.watchers.add(queue)
            async def forward():
                while True:
                    message = await queue.get()
                    if message is None:
                        await socket.close(1013, 'Watcher fell behind; recover from archive')
                        return
                    await socket.send(message)
            sender = asyncio.create_task(forward())
            try:
                await socket.wait_closed()
            finally:
                sender.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await sender
                self.watchers.discard(queue)
            return
        try:
            async for message in socket:
                event = json.loads(message)
                if event.get('type') == 'capture.hello':
                    session = str(uuid.UUID(event['session']))
                    await socket.send(json.dumps({'ready': self.archive.heads.get(session, 0), 'session': session}))
                    continue
                incoming = event['events'] if event.get('type') == 'capture.batch' else [event]
                reply, fresh = self.archive.commit_many(incoming)
                for event in fresh:
                    encoded = json.dumps(event, separators=(',', ':'))
                    for queue in list(self.watchers):
                        if queue.full():
                            while not queue.empty():
                                queue.get_nowait()
                            queue.put_nowait(None)
                            self.watchers.discard(queue)
                        else:
                            queue.put_nowait(encoded)
                    kind = event['type']
                    if kind not in {'stroke.samples', 'stroke.prediction', 'stroke.geometry', 'pencil.hover', 'audio.buffer'} and not (kind == 'canvas.transform' and event['data'].get('phase') == 'changed'):
                        print(f"{event['session'][:8]} {event['seq']:>7} {event['t']:>10.3f}  {kind}", flush=True)
                await socket.send(json.dumps(reply))
        except ConnectionClosed:
            pass
        except (ValueError, KeyError, TypeError) as error:
            await socket.close(1008, str(error)[:100])


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--output', type=Path, default=Path('captures'))
    args = parser.parse_args()
    receiver = Receiver(Archive(args.output))
    async with serve(receiver.handle, args.host, args.port, max_size=2**20, compression=None):
        print(f'Drawing On listening on {args.host}:{args.port}; archive: {args.output.resolve()}', flush=True)
        print('Field can subscribe to ws://localhost:%s/watch (new events only)' % args.port, flush=True)
        await asyncio.Future()


if __name__ == '__main__':
    asyncio.run(main())
