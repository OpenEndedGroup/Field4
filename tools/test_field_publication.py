from __future__ import annotations

import asyncio
import base64
import io
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

from field_publication import publish_document
from test_offline_viewer_js import runnable_node

ROOT = Path(__file__).resolve().parents[1]


class FakeBrowser:
    playwright_state = {"ok": True}

    def __init__(self, content: bytes, *, fail_chunk: bool = False):
        self.content = content
        self.fail_chunk = fail_chunk
        self.cleaned = False

    async def playwright_input(self, *, script: str, **_kwargs):
        if "FieldPublication.build" in script:
            return {"ok": True, "result": {"bytes": len(self.content), "manifest": {"outputs": [{"path": "outputs/0.html"}]}, "warnings": []}}
        if script.startswith("delete"):
            self.cleaned = True
            return {"ok": True, "result": True}
        if self.fail_chunk:
            return {"ok": False, "message": "browser disconnected"}
        import re
        start, end = map(int, re.search(r"\.subarray\((\d+),(\d+)\)", script).groups())
        return {"ok": True, "result": base64.b64encode(self.content[start:end]).decode()}


class FieldPublicationTest(unittest.TestCase):
    def test_transfer_crosses_chunk_boundaries_and_cleans_browser(self):
        content = bytes(range(256)) * 1900
        client = FakeBrowser(content)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "publication.zip"
            result = asyncio.run(publish_document(client, str(target), timeout=10))
            self.assertEqual(target.read_bytes(), content)
            self.assertEqual(result["capturedOutputs"], 1)
            self.assertEqual(list(Path(directory).iterdir()), [target])
        self.assertTrue(client.cleaned)

    def test_transfer_failure_leaves_no_partial_publication(self):
        client = FakeBrowser(b"archive", fail_chunk=True)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "browser disconnected"):
                asyncio.run(publish_document(client, str(Path(directory) / "out.zip"), timeout=10))
            self.assertEqual(list(Path(directory).iterdir()), [])
        self.assertTrue(client.cleaned)

    def test_existing_export_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "out.zip"
            target.write_bytes(b"keep")
            with self.assertRaisesRegex(ValueError, "already exists"):
                asyncio.run(publish_document(FakeBrowser(b"replacement"), str(target), timeout=10))
            self.assertEqual(target.read_bytes(), b"keep")

    def test_browser_zip_roundtrips_unicode_binary_and_empty_files(self):
        node, why = runnable_node()
        if node is None:
            self.skipTest(why)
        module = (ROOT / "src/commonMain/resources/publication-zip.js").read_bytes()
        source_url = "data:text/javascript;base64," + base64.b64encode(module).decode()
        script = """
            const {zipFiles,crc32} = await import(MODULE);
            if(crc32(new TextEncoder().encode('123456789'))!==0xcbf43926) throw new Error('CRC mismatch');
            for(const path of ['../escape','/absolute','a/../b']) {
                let failed=false;try{await zipFiles(new Map([[path,'x']]))}catch{failed=true}
                if(!failed) throw new Error('unsafe ZIP path accepted');
            }
            const zip=await zipFiles(new Map([['document/metadata.edn','Field · 4'],['outputs/été.html','<svg/>'],['large.html','repeated content '.repeat(10000)],['bytes.bin',new Uint8Array([0,255,31])],['empty','']]));
            process.stdout.write(Buffer.from(await zip.arrayBuffer()));
        """.replace("MODULE", json.dumps(source_url))
        proc = subprocess.run([node, "--input-type=module", "-e", script], capture_output=True, timeout=30)
        self.assertEqual(proc.returncode, 0, proc.stderr.decode(errors="replace"))
        with zipfile.ZipFile(io.BytesIO(proc.stdout)) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("document/metadata.edn").decode(), "Field · 4")
            self.assertEqual(archive.read("outputs/été.html"), b"<svg/>")
            self.assertEqual(archive.read("bytes.bin"), bytes([0,255,31]))
            self.assertEqual(archive.read("empty"), b"")
            self.assertEqual(archive.read("large.html"), b"repeated content " * 10000)


if __name__ == "__main__":
    unittest.main()
