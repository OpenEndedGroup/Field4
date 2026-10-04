"""Pins the line protocol of `python/fieldvoice/whisper_worker.py`, the process
`afield.ai.core.VoiceTranscriber` talks to for AI4 push-to-talk.

No model is loaded: a stub `mlx_whisper` package placed first on PYTHONPATH stands in for the
backend, echoing what it was given, so the test checks the protocol (ready line, one reply per
request carrying its id, prompt and audio reaching the backend, resampling, errors that keep the
worker alive) rather than recognition quality.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / "python" / "fieldvoice" / "whisper_worker.py"

STUB = '''
def transcribe(audio, path_or_hf_repo=None, initial_prompt=None, **kwargs):
    return {"text": " samples=%d prompt=%s repo=%s " % (len(audio), initial_prompt, path_or_hf_repo)}
'''

BROKEN_STUB = "raise ImportError('stub backend is broken')\n"


class WhisperWorkerProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def start(self, stub: str, extra_env=None):
        pkg = self.dir / "stubs" / "mlx_whisper"
        pkg.mkdir(parents=True, exist_ok=True)
        (pkg / "__init__.py").write_text(stub)
        env = dict(os.environ)
        env["PYTHONPATH"] = str(self.dir / "stubs")
        env.pop("FIELD_VOICE_MODEL", None)
        env.update(extra_env or {})
        return subprocess.Popen(
            [sys.executable, "-u", str(WORKER)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, env=env,
        )

    def clip(self, n: int) -> str:
        path = self.dir / f"clip{n}.f32"
        np.zeros(n, dtype="<f4").tofile(path)
        return str(path)

    def ask(self, proc, request) -> dict:
        proc.stdin.write(json.dumps(request) + "\n")
        proc.stdin.flush()
        return json.loads(proc.stdout.readline())

    def test_ready_then_one_reply_per_request(self):
        proc = self.start(STUB, {"FIELD_VOICE_MODEL": "custom/repo"})
        try:
            ready = json.loads(proc.stdout.readline())
            self.assertEqual(ready["ready"], True)
            self.assertEqual(ready["backend"], "mlx-whisper")
            self.assertEqual(ready["model"], "custom/repo")

            reply = self.ask(proc, {"id": 7, "pcm": self.clip(16000), "sampleRate": 16000,
                                    "prompt": "fromContext, Renderer"})
            self.assertEqual(reply["id"], 7)
            self.assertTrue(reply["ok"])
            # Text is stripped; the prompt and the model name reach the backend.
            self.assertEqual(reply["text"], "samples=16000 prompt=fromContext, Renderer repo=custom/repo")
            self.assertIsInstance(reply["millis"], int)

            # An empty prompt is passed as None, not "".
            reply = self.ask(proc, {"id": 8, "pcm": self.clip(8000), "prompt": ""})
            self.assertIn("prompt=None", reply["text"])

            # A 48 kHz clip is resampled to 16 kHz before it reaches the backend.
            reply = self.ask(proc, {"id": 9, "pcm": self.clip(48000), "sampleRate": 48000})
            self.assertIn("samples=16000 ", reply["text"])

            # A bad request answers with an error and leaves the worker serving.
            reply = self.ask(proc, {"id": 10, "pcm": str(self.dir / "missing.f32")})
            self.assertEqual(reply["id"], 10)
            self.assertFalse(reply["ok"])
            reply = self.ask(proc, {"id": 11, "pcm": self.clip(16000)})
            self.assertTrue(reply["ok"])
        finally:
            proc.stdin.close()
            self.assertEqual(proc.wait(timeout=30), 0)
            proc.stdout.close()
            proc.stderr.close()

    def test_no_backend_reports_not_ready(self):
        # With mlx_whisper broken, the worker tries faster_whisper; block that too so the
        # result does not depend on what this venv has installed.
        fw = self.dir / "stubs" / "faster_whisper"
        fw.mkdir(parents=True)
        (fw / "__init__.py").write_text(BROKEN_STUB)
        proc = self.start(BROKEN_STUB)
        try:
            ready = json.loads(proc.stdout.readline())
            self.assertEqual(ready["ready"], False)
            self.assertIn("uv sync --inexact --group voice", ready["error"])
        finally:
            proc.stdin.close()
            self.assertEqual(proc.wait(timeout=30), 1)
            proc.stdout.close()
            proc.stderr.close()


if __name__ == "__main__":
    unittest.main()
