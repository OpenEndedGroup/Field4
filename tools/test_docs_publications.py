from __future__ import annotations

import json
import subprocess
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import AsyncMock, patch
from types import SimpleNamespace

import docs_publications as publications


class DocsPublicationsTest(unittest.TestCase):
    def test_capture_uses_shared_managed_node_when_path_node_cannot_start(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            node = root/'home/.gradle/nodejs/node-v24.0.0/bin/node'
            node.parent.mkdir(parents=True)
            node.write_text('managed runtime fixture')
            with patch.multiple(publications, ROOT=root/'repo'), \
                 patch.dict(publications.os.environ, {}, clear=True), \
                 patch.object(Path, 'home', return_value=root/'home'), \
                 patch.object(publications.shutil, 'which', return_value='/broken/node'), \
                 patch.object(publications.subprocess, 'run', side_effect=[
                     SimpleNamespace(returncode=1, stdout=''), SimpleNamespace(returncode=0, stdout='v24.0.0\n')]):
                self.assertEqual(publications.publication_node(), str(node))

    def test_native_recipes_reuse_real_docs_captures_without_executing_compile_only(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root/'src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt'
            source.parent.mkdir(parents=True)
            source.write_text('internal val EXECUTE = setOf()\ninternal val COMPILE_ONLY = setOf("gpu")')
            docs = root/'public/docs'
            document = docs/'assets/agent_transcripts/gpu/metadata.edn'
            document.parent.mkdir(parents=True)
            document.write_text('authored document')
            transcript = root/'dev_docs/agents/transcripts/gpu.yaml'
            transcript.parent.mkdir(parents=True)
            transcript.write_text('operations: []')
            image = docs/'assets/gpu.png'
            image.write_bytes(b'real runner image fixture')
            (docs/'graphics.md').write_text('```diffstatic_runner {capture="native-window" '
                'capture-profile="linux-nvidia" screenshot="assets/gpu.png" '
                'macro-file="../../dev_docs/agents/transcripts/gpu.yaml" capture-box="renderer"}\n```')
            with patch.multiple(publications, ROOT=root, DOCS=docs):
                recipe, = publications.recipes('gallery')
                self.assertTrue(recipe['retainedNative'])
                self.assertEqual(recipe['nativeCaptures'][0]['uid'], 'renderer')
                before = publications.recipe_sources(recipe)
                self.assertIn('public/docs/assets/gpu.png', before)
                image.write_bytes(b'refreshed real capture fixture')
                self.assertNotEqual(publications.recipe_sources(recipe), before)

    def test_native_capture_depends_on_its_fence_not_the_rest_of_the_page(self):
        """A new starting point on the gallery page leaves every other native publication fresh;
        changing the capture's own fence (its alt text, its box) still invalidates it."""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root/'src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt'
            source.parent.mkdir(parents=True)
            source.write_text('internal val EXECUTE = setOf()\ninternal val COMPILE_ONLY = setOf("gpu")')
            docs = root/'public/docs'
            (docs/'assets/agent_transcripts/gpu').mkdir(parents=True)
            (docs/'assets/agent_transcripts/gpu/metadata.edn').write_text('authored document')
            (root/'dev_docs/agents/transcripts').mkdir(parents=True)
            (root/'dev_docs/agents/transcripts/gpu.yaml').write_text('operations: []')
            (docs/'assets/gpu.png').write_bytes(b'real runner image fixture')
            fence = ('```diffstatic_runner {capture="native-window" screenshot="assets/gpu.png" alt="ALT" '
                     'macro-file="../../dev_docs/agents/transcripts/gpu.yaml" capture-box="BOX"}\n'
                     '// body\n```\n')
            page = docs/'agent_transcripts.md'

            def write(prose='', alt='A glowing sphere', box='renderer'):
                page.write_text('### gpu\n\n' + fence.replace('ALT', alt).replace('BOX', box)
                                + '\n### other\n\nAnother starting point.\n' + prose)

            key = 'public/docs/agent_transcripts.md#capture-box=renderer'
            with patch.multiple(publications, ROOT=root, DOCS=docs):
                write()
                recipe, = publications.recipes('gallery')
                before = publications.recipe_sources(recipe)
                self.assertIn(key, before)
                self.assertNotIn('public/docs/agent_transcripts.md', before)

                write(prose='\n### new_entry\n\nA brand new starting point.\n')
                recipe, = publications.recipes('gallery')
                self.assertEqual(publications.recipe_sources(recipe), before)

                write(alt='A dim sphere')
                recipe, = publications.recipes('gallery')
                self.assertNotEqual(publications.recipe_sources(recipe)[key], before[key])

                write(box='other_renderer')
                self.assertIsNone(publications.source_digest(key))
                recipe, = publications.recipes('gallery')
                self.assertNotIn(key, publications.recipe_sources(recipe))

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

    def test_gitignored_runtime_state_is_not_a_source(self):
        # 2026-11-01: five gitignored backups/*.before-inline-* copies, written when Field opened the
        # document in the primary checkout, made canonical_graphics stale there and the docs build fail.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root/'.gitignore').write_text('**/backups/\n')
            folder = root/'public/docs/assets/example'
            (folder/'backups').mkdir(parents=True)
            (folder/'metadata.edn').write_text('document')
            (folder/'box_code_.kt').write_text('println(1)')
            (folder/'backups/box_code_.kt.before-inline-1').write_text('println(0)')
            with patch.multiple(publications, ROOT=root, DOCS=root/'public/docs'):
                sources = publications.source_files('assets/example/metadata.edn')
            self.assertIn('public/docs/assets/example/box_code_.kt', sources)  # untracked but not ignored
            self.assertNotIn('public/docs/assets/example/backups/box_code_.kt.before-inline-1', sources)


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

    def test_a_dropped_websocket_gets_one_fresh_session_retry(self):
        calls = []
        async def capture(recipes, **kwargs):
            calls.append(recipes)
            if len(calls) == 1:
                return [{'name': 'example', 'ok': False,
                         'error': 'ConnectionClosedError: no close frame received or sent [code=1006]'}]
            self.record()
            return [{'name': 'example', 'ok': True, 'channels': 0}]
        with patch.object(publications, 'capture', new=AsyncMock(side_effect=capture)):
            self.assertEqual(publications.sync_publications()['refreshed'], 1)
        self.assertEqual(len(calls), 2)

    def test_a_bare_backdoor_timeout_gets_one_fresh_session_retry(self):
        calls = []
        async def capture(recipes, **kwargs):
            calls.append(recipes)
            if len(calls) == 1:
                return [{'name': 'example', 'ok': False, 'error': 'TimeoutError'}]
            self.record()
            return [{'name': 'example', 'ok': True, 'channels': 0}]
        with patch.object(publications, 'capture', new=AsyncMock(side_effect=capture)):
            self.assertEqual(publications.sync_publications()['refreshed'], 1)
        self.assertEqual(len(calls), 2)

    def fake_backdoor(self, collisions: int):
        import field_backdoor as fb
        started = []
        class Backdoor:
            def __init__(self, *, port, **_kwargs):
                self.port, self.managed_process = port, object()
                started.append(self)
            async def ensure_server(self):
                if len(started) <= collisions:
                    raise fb.BackdoorError(f"Port {self.port} {publications.PORT_COLLISION}: the server belongs to elsewhere")
            async def connect(self): pass
            async def close(self): pass
        class Log:
            def __init__(self, *_args): pass
            def close(self): pass
        ports = iter(range(9479, 9490))
        return started, patch.multiple(fb, FieldBackdoor=Backdoor, SessionLogger=Log,
                                       resolve_cli_port=lambda *_a, **_k: (next(ports), 'automation-default'))

    def test_a_port_taken_by_another_checkout_starts_on_the_next_free_port(self):
        import asyncio
        started, fakes = self.fake_backdoor(collisions=1)
        with fakes, patch.object(publications, 'publication_node', return_value=None):
            self.assertEqual(asyncio.run(publications.capture([], port=None, force=True)), [])
        self.assertEqual([b.port for b in started], [9479, 9480])

    def test_an_explicit_port_or_a_port_that_stays_taken_still_fails(self):
        import asyncio
        import field_backdoor as fb
        for port, collisions, attempts in ((9600, 1, 1), (None, 99, publications.PORT_COLLISION_ATTEMPTS)):
            started, fakes = self.fake_backdoor(collisions=collisions)
            with fakes, patch.object(publications, 'publication_node', return_value=None):
                with self.assertRaisesRegex(fb.BackdoorError, 'did not start'):
                    asyncio.run(publications.capture([], port=port, force=True))
            self.assertEqual(len(started), attempts)

    def test_a_real_failure_is_not_retried(self):
        with patch.object(publications, 'capture', new=AsyncMock(return_value=[
                {'name': 'example', 'ok': False, 'error': 'Box interrupted: compile error'}])) as runner:
            with self.assertRaisesRegex(ValueError, 'compile error'):
                publications.sync_publications()
            self.assertEqual(runner.await_count, 1)

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



class LongComputeNamesRealRecipes(unittest.TestCase):
    """Against the real tree: a LONG_COMPUTE entry that names no recipe protects nothing."""

    def test_every_long_compute_name_is_a_gallery_recipe_with_the_long_budget(self):
        recipes = {r['name']: r for r in publications.recipes('gallery')}
        for name in sorted(publications.LONG_COMPUTE):
            with self.subTest(name=name):
                self.assertIn(name, recipes, f'{name} is no longer a publication recipe; drop it from LONG_COMPUTE')
                self.assertTrue(recipes[name]['longCompute'])


class SyncExitClassifiesMachineState(unittest.TestCase):
    """2026-11-14: unrelated branches bounced on refresh timeouts, a port a sibling worktree took,
    and a full disk; `sync` exits MACHINE_STATE_EXIT for those so the lander retries."""

    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(patch.multiple(publications, ROOT=self.root))
        self.enterContext(patch('traceback.print_exc'))

    def failing(self, error, report=None):
        if report is not None:
            (self.root / 'build').mkdir()
            (self.root / publications.REFRESH_REPORT).write_text(json.dumps(report))
        def run():
            raise error
        return publications.sync_exit(run)

    def test_capture_timeouts_and_watchdog_stops_are_machine_state(self):
        report = [{'name': 'flow_lenia', 'ok': False, 'error': 'TimeoutError'},
                  {'name': 'uncrossed_leapers', 'ok': False, 'error': 'Box interrupted: Runloop stalled 30073 ms (watchdog)'}]
        self.assertEqual(self.failing(ValueError('Publication capture failed: ...'), report),
                         publications.MACHINE_STATE_EXIT)

    def test_one_real_failure_among_timeouts_is_not(self):
        report = [{'name': 'flow_lenia', 'ok': False, 'error': 'TimeoutError'},
                  {'name': 'fit', 'ok': False, 'error': "Uncaptured channels: ['fit: No rendered output']"}]
        self.assertEqual(self.failing(ValueError('Publication capture failed: ...'), report), 1)

    def test_a_port_taken_mid_build_is_machine_state_and_reported(self):
        error = RuntimeError('Port 9479 is answered by a Field this session did not start')
        self.assertEqual(self.failing(error), publications.MACHINE_STATE_EXIT)
        written = json.loads((self.root / publications.REFRESH_REPORT).read_text())
        self.assertIn('9479', written[0]['machine'])

    def test_a_full_disk_is_machine_state(self):
        self.assertEqual(self.failing(OSError(28, 'No space left on device')), publications.MACHINE_STATE_EXIT)

    def test_publication_ports_avoid_the_shared_automation_ranges(self):
        span = range(publications.PUBLICATION_PORT_START,
                     publications.PUBLICATION_PORT_START + publications.PUBLICATION_PORT_SPAN)
        for shared in (range(9477, 9477 + 60), range(9490, 9490 + 60)):
            self.assertFalse(set(span) & set(shared))


class PersistentPropertyListIsNotACaptureInput(unittest.TestCase):
    """2026-11-14: a new persistent property (`ai4_garden`) rewrote the list at the end of every
    materialized transcript, and every landing that rematerialized them recaptured all ~200
    publications for an hour, then bounced on a slow page."""

    SOURCE = 'public/docs/assets/agent_transcripts/demo/metadata.edn'
    OLD = b'{"version" 2 "boxes" [{"id" "a"}] "persistentProperties" ["frame" "code"]}'

    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        git = lambda *a: subprocess.run(['git', *a], cwd=self.root, check=True, capture_output=True)
        git('init', '-q')
        (self.root / self.SOURCE).parent.mkdir(parents=True)
        (self.root / self.SOURCE).write_bytes(self.OLD)
        git('add', '-A')
        git('-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'commit.gpgsign=false', 'commit', '-q', '-m', 'base')
        self.index = {'documents': {'assets/agent_transcripts/demo/metadata.edn': {
            'sources': {self.SOURCE: hashlib.sha256(self.OLD).hexdigest()}}}}
        self.enterContext(patch.multiple(publications, ROOT=self.root))

    def rematerialize(self, text: bytes) -> list[str]:
        (self.root / self.SOURCE).write_bytes(text)
        return publications.adopt_persistent_list_changes(self.index)

    def recorded(self) -> str:
        return self.index['documents']['assets/agent_transcripts/demo/metadata.edn']['sources'][self.SOURCE]

    def test_a_new_persistent_property_is_recorded_without_a_recapture(self):
        new = self.OLD.replace(b'"code"]', b'"code" "ai4_garden"]')
        self.assertEqual(self.rematerialize(new), [self.SOURCE])
        self.assertEqual(self.recorded(), hashlib.sha256(new).hexdigest())

    def test_a_change_to_the_document_itself_still_makes_it_stale(self):
        new = self.OLD.replace(b'"a"', b'"b"').replace(b'"code"]', b'"code" "ai4_garden"]')
        self.assertEqual(self.rematerialize(new), [])
        self.assertEqual(self.recorded(), hashlib.sha256(self.OLD).hexdigest())

    def test_a_record_that_is_not_the_committed_file_is_left_alone(self):
        self.index['documents']['assets/agent_transcripts/demo/metadata.edn']['sources'][self.SOURCE] = 'f' * 64
        self.assertEqual(self.rematerialize(self.OLD.replace(b'"code"]', b'"code" "x"]')), [])
        self.assertEqual(self.recorded(), 'f' * 64)


if __name__ == '__main__':
    unittest.main()
