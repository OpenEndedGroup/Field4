from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import docs_publications as publications


class DocsPublicationsTest(unittest.TestCase):
    def test_shared_runtime_and_source_invalidation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            docs = root / 'public/docs'
            storage = docs / 'assets/web_publications'
            source = docs / 'assets/example/metadata.edn'
            source.parent.mkdir(parents=True)
            source.write_text('source document')
            loose = source.with_name('box_code_.kt')
            loose.write_text('println(1)')
            archive = root / 'capture.zip'
            manifest = {'version': 1, 'capturedAt': '2026-10-28T00:00:00Z', 'outputs': [
                {'uid': 'a', 'channel': 'lines', 'path': 'outputs/0.html', 'kinds': ['svg'], 'warnings': []}]}
            with zipfile.ZipFile(archive, 'w') as out:
                out.writestr('document/metadata.edn', 'captured document')
                out.writestr('document/outputs.json', json.dumps(manifest))
                out.writestr('outputs/0.html', '<script src="../publication-font.js"></script><svg/>')
                out.writestr('publication-font.js', 'shared font runtime')
            with patch.multiple(publications, ROOT=root, DOCS=docs, PUBLICATIONS=storage, CATALOG=storage/'catalog.json'):
                sources = publications.source_files('assets/example/metadata.edn')
                self.assertIn('public/docs/assets/example/box_code_.kt', sources)
                for name in ('first', 'second'):
                    publications.install(archive, name=name, source=f'assets/{name}/metadata.edn', sources=sources)
                self.assertEqual(len(list((storage/'runtime').rglob('*.js'))), 1)
                html = (storage/'first/outputs/0.html').read_text()
                self.assertIn('../../runtime/', html)
                self.assertNotIn('../publication-font.js', html)
                self.assertEqual(len(publications.registry()['documents']), 2)
                loose.write_text('println(2)')
                with self.assertRaisesRegex(ValueError, 'Stale publication.*box_code_'):
                    publications.registry()

    def test_capture_failure_keeps_previous_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = root/'sample'
            target.mkdir()
            (target/'keep').write_text('previous capture')
            archive = root/'bad.zip'
            with zipfile.ZipFile(archive, 'w') as out:
                out.writestr('document/outputs.json', json.dumps({'outputs': [
                    {'channel': 'missing', 'warnings': ['No rendered output']}]}))
            with patch.multiple(publications, PUBLICATIONS=root, CATALOG=root/'catalog.json'):
                with self.assertRaisesRegex(ValueError, 'no captured'):
                    publications.install(archive, name='sample', source='assets/sample/metadata.edn', sources={})
            self.assertEqual((target/'keep').read_text(), 'previous capture')


if __name__ == '__main__':
    unittest.main()
