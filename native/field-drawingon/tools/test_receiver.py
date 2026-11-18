import asyncio
import json
from pathlib import Path
import tempfile
import unittest
import uuid
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from receiver import Archive, Receiver


class ReceiverTests(unittest.IsolatedAsyncioTestCase):
    async def test_native_uppercase_session_survives_receiver_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            session = str(uuid.uuid4()).upper()
            archive = Archive(Path(directory))
            archive.commit(dict(v=1, session=session, seq=1, t=0, type='capture.finish', data={}))
            restored = Archive(Path(directory))
            self.assertEqual(restored.heads[session.lower()], 1)

    async def test_durable_ack_replay_dedup_and_watch(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Archive(Path(directory))
            receiver = Receiver(archive)
            async with serve(receiver.handle, '127.0.0.1', 0) as server:
                port = server.sockets[0].getsockname()[1]
                address = f'ws://127.0.0.1:{port}'
                session = str(uuid.uuid4())
                event = dict(v=1, session=session, seq=1, t=0.1, type='stroke.begin', data={'stroke': 'a'})
                async with connect(address + '/watch') as watcher, connect(address) as sender:
                    await sender.send(json.dumps({'type': 'capture.hello', 'session': session, 'through': 3}))
                    self.assertEqual(json.loads(await sender.recv()), {'ready': 0, 'session': session})
                    await sender.send(json.dumps(event))
                    self.assertEqual(json.loads(await sender.recv())['ack'], 1)
                    self.assertEqual(json.loads(await watcher.recv()), event)
                    await sender.send(json.dumps(event))
                    self.assertEqual(json.loads(await sender.recv())['ack'], 1)
                    event['seq'] = 3
                    await sender.send(json.dumps(event))
                    self.assertEqual(json.loads(await sender.recv()), {'replayFrom': 2})
                path = Path(directory) / f'{session}.ndjson'
                self.assertEqual(len(path.read_text().splitlines()), 1)
                with path.open('ab') as stream:
                    stream.write(b'{"partial":')
                restored = Archive(Path(directory))
                self.assertEqual(restored.heads[session], 1)
                self.assertTrue(path.read_bytes().endswith(b'\n'))
                event['seq'] = 2
                reply, fresh = restored.commit(event)
                self.assertTrue(fresh)
                self.assertEqual(reply['ack'], 2)

    async def test_upload_batch_is_validated_before_append_and_acked_after_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Archive(Path(directory))
            receiver = Receiver(archive)
            session = str(uuid.uuid4())
            def event(seq):
                return dict(v=1, session=session, seq=seq, t=seq * .01, type='stroke.samples', data={'samples': []})
            reply, fresh = archive.commit_many([event(1), event(3)])
            self.assertEqual(reply, {'replayFrom': 1})
            self.assertFalse(fresh)
            self.assertFalse((Path(directory) / f'{session}.ndjson').exists())
            async with serve(receiver.handle, '127.0.0.1', 0) as server:
                address = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}"
                async with connect(address) as sender:
                    batch = {'type': 'capture.batch', 'events': [event(i) for i in range(1, 65)]}
                    await sender.send(json.dumps(batch))
                    self.assertEqual(json.loads(await sender.recv())['ack'], 64)
                    await sender.send(json.dumps(batch))
                    self.assertEqual(json.loads(await sender.recv())['ack'], 64)
                self.assertEqual(archive.heads[session], 64)
                self.assertEqual(len((Path(directory) / f'{session}.ndjson').read_text().splitlines()), 64)
                async with connect(address) as resumed:
                    await resumed.send(json.dumps({'type': 'capture.hello', 'session': session, 'through': 64}))
                    self.assertEqual(json.loads(await resumed.recv())['ready'], 64)


if __name__ == '__main__':
    unittest.main()
