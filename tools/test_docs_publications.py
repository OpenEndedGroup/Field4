from __future__ import annotations

import json
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import AsyncMock, patch

import docs_publications as publications


class DocsPublicationsTest(unittest.TestCase):
    def test_classification_accepts_multiple_names_on_a_line_and_ignores_comments(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root/'src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt'
            source.parent.mkdir(parents=True)
            source.write_text('internal val EXECUTE = setOf("one", "two", // "not_a_recipe"\n "three", "morpho_circuit_search")\n'
                              'internal val COMPILE_ONLY = setOf("native")')
            docs = root/'public/docs'
            for name in ('one', 'two', 'three', 'morpho_circuit_search', 'not_a_recipe', 'native'):
                path = docs/f'assets/agent_transcripts/{name}/metadata.edn'
                path.parent.mkdir(parents=True)
                path.write_text('document')
            with patch.multiple(publications, ROOT=root, DOCS=docs):
                recipes = publications.recipes('gallery')
                self.assertEqual([r['name'] for r in recipes], ['one', 'two', 'three', 'morpho_circuit_search'])
                self.assertFalse(recipes[0]['longCompute'])
                self.assertTrue(recipes[-1]['longCompute'])

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
                    folder = docs/'assets'/name
                    folder.mkdir()
                    (folder/'metadata.edn').write_text(source.read_text())
                    (folder/loose.name).write_text(loose.read_text())
                    source_path = f'assets/{name}/metadata.edn'
                    publications.install(archive, name=name, source=source_path,
                                         sources=publications.source_files(source_path))
                self.assertEqual(len(list((storage/'runtime').rglob('*.js'))), 1)
                html = (storage/'first/outputs/0.html').read_text()
                self.assertIn('../../runtime/', html)
                self.assertNotIn('../publication-font.js', html)
                self.assertEqual(len(publications.registry()['documents']), 2)
                (docs/'assets/first'/loose.name).write_text('println(2)')
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


class PublicationRefreshTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.docs = self.root/'public/docs'
        self.storage = self.docs/'assets/web_publications'
        self.source = 'assets/example/metadata.edn'
        self.file = self.docs/self.source
        self.file.parent.mkdir(parents=True)
        self.file.write_text('example')
        self.recipe = {'name': 'example', 'source': self.source}
        self.enterContext(patch.multiple(publications, ROOT=self.root, DOCS=self.docs,
                                        PUBLICATIONS=self.storage, CATALOG=self.storage/'catalog.json'))
        self.enterContext(patch.object(publications, 'capture_digest', return_value='publisher-v1'))
        self.enterContext(patch.object(publications, 'recipes', side_effect=lambda group: [self.recipe] if group == 'gallery' else []))

    def record(self):
        publications.record_empty(self.recipe, publications.recipe_sources(self.recipe))

    def test_new_document_runs_once_and_empty_result_is_cached(self):
        async def capture(recipes, **kwargs):
            self.assertEqual(recipes, [self.recipe])
            self.record()
            return [{'name': 'example', 'ok': True, 'channels': 0}]
        with patch.object(publications, 'capture', new=AsyncMock(side_effect=capture)) as runner:
            self.assertEqual(publications.sync_publications()['refreshed'], 1)
            self.assertEqual(publications.sync_publications()['refreshed'], 0)
            self.assertEqual(runner.await_count, 1)

    def test_freshness_tracks_new_sidecars_and_publisher_changes(self):
        self.record()
        self.assertEqual(publications.pending_recipes([self.recipe], mode='strict'), [])
        self.file.with_name('new_code_.kt').write_text('display("new", 1)')
        self.assertEqual(publications.pending_recipes([self.recipe], mode='incremental'), [self.recipe])
        self.record()
        with patch.object(publications, 'capture_digest', return_value='publisher-v2'):
            self.assertEqual(publications.pending_recipes([self.recipe], mode='strict'), [self.recipe])

    def test_capture_free_check_is_read_only_and_missing_recipe_fails(self):
        with patch.object(publications, 'capture', new=AsyncMock()) as runner:
            with self.assertRaisesRegex(ValueError, 'need refresh'):
                publications.sync_publications(mode='never')
            self.record()
            before = publications.CATALOG.stat().st_mtime_ns
            publications.sync_publications(mode='never')
            self.assertEqual(publications.CATALOG.stat().st_mtime_ns, before)
            runner.assert_not_awaited()

    def test_failure_preserves_previous_record_and_fails_build(self):
        self.record()
        before = publications.CATALOG.read_bytes()
        self.file.write_text('changed')
        with patch.object(publications, 'capture', new=AsyncMock(return_value=[
                {'name': 'example', 'ok': False, 'error': 'real run failed'}])):
            with self.assertRaisesRegex(ValueError, 'real run failed'):
                publications.sync_publications()
        self.assertEqual(publications.CATALOG.read_bytes(), before)

    def test_capture_timeout_gets_one_fresh_session_retry(self):
        calls = []
        async def capture(recipes, **kwargs):
            calls.append(recipes)
            if len(calls) == 1:
                return [{'name': 'example', 'ok': False, 'error': 'Output capture exceeded 30 seconds'}]
            self.record()
            return [{'name': 'example', 'ok': True, 'channels': 0}]
        with patch.object(publications, 'capture', new=AsyncMock(side_effect=capture)):
            self.assertEqual(publications.sync_publications()['refreshed'], 1)
        self.assertEqual(len(calls), 2)

    def test_persistent_capture_timeout_fails_after_one_retry(self):
        with patch.object(publications, 'capture', new=AsyncMock(return_value=[
                {'name': 'example', 'ok': False, 'error': 'Output capture exceeded 30 seconds'}])) as runner:
            with self.assertRaisesRegex(ValueError, 'Output capture exceeded'):
                publications.sync_publications()
            self.assertEqual(runner.await_count, 2)

    def test_removing_recipe_retires_its_record(self):
        self.record()
        with patch.object(publications, 'recipes', return_value=[]):
            with self.assertRaisesRegex(ValueError, 'removed recipes'):
                publications.sync_publications(mode='never')
            publications.sync_publications()
        self.assertEqual(publications.catalog()['emptyDocuments'], {})

    def test_always_mode_refreshes_unchanged_documents(self):
        self.record()
        self.assertEqual(publications.pending_recipes([self.recipe], mode='always'), [self.recipe])

    def test_missing_channel_or_runtime_needs_repair(self):
        folder = self.storage/'example'
        (folder/'document').mkdir(parents=True)
        (folder/'outputs').mkdir()
        (folder/'document/metadata.edn').write_text('captured document')
        (folder/'document/outputs.json').write_text(json.dumps({'version': 1, 'outputs': [{'path': 'outputs/0.html'}]}))
        entry = {'file': 'assets/web_publications/example/document/metadata.edn',
                 'outputs': 'assets/web_publications/example/document/outputs.json', 'channels': 1,
                 'sources': publications.recipe_sources(self.recipe), 'captureDigest': 'publisher-v1'}
        publications.write_catalog({'version': 1, 'documents': {self.source: entry}})
        self.assertIn('missing captured channel', publications.publication_problem(entry))
        self.assertEqual(publications.pending_recipes([self.recipe], mode='incremental'), [self.recipe])
        runtime_hash = hashlib.sha256(b'font runtime').hexdigest()[:20]
        (folder/'outputs/0.html').write_text(f'<script src="../../runtime/{runtime_hash}/font.js"></script>')
        self.assertIn('missing runtime', publications.publication_problem(entry))
        runtime = self.storage/f'runtime/{runtime_hash}/font.js'
        runtime.parent.mkdir(parents=True)
        runtime.write_text('font runtime')
        self.assertIsNone(publications.publication_problem(entry))
        self.assertEqual(publications.pending_recipes([self.recipe], mode='incremental'), [])
        entry['files'] = publications.snapshot_hashes(entry)
        publications.write_catalog({'version': 1, 'documents': {self.source: entry}})
        (folder/'outputs/0.html').write_text('changed during merge')
        self.assertIn('captured file changed', publications.publication_problem(entry))
        self.assertEqual(publications.pending_recipes([self.recipe], mode='incremental'), [self.recipe])
        self.file.with_name('added_code_.kt').write_text('println(1)')
        with self.assertRaisesRegex(ValueError, 'source file set changed'):
            publications.registry()


if __name__ == '__main__':
    unittest.main()
