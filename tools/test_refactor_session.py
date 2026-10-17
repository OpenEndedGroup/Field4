"""Session delivery exercises real files, commands and journals without landing the real repo."""
import copy
import json
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

import unittest
from tools import test_refactor_pass as fixtures
from tools.test_refactor_pass import ReviewEngine
from tools.refactoring import session
from tools.refactoring.core import RefactorError, make_plan, save_json
from tools import refactor_pass as passes


class SessionDeliveryTest(unittest.TestCase):
    setUp = fixtures.RefactorPassTest.setUp
    tearDown = fixtures.RefactorPassTest.tearDown
    def ready(self):
        tree = Path(self.state['worktree'])
        (tree / 'source.kt').write_text('import old.pkg.Thing\nval x = Thing()\n')
        recipe = copy.deepcopy(json.loads((self.directory / 'recipe.json').read_text()))
        recipe['markdownProse'] = ['*.md']
        save_json(self.directory / 'recipe.json', recipe)
        target = self.directory / 'plans/ready'
        make_plan([(Path(r['path']), r['kind'] == 'repo') for r in self.state['roots']], recipe, target, ReviewEngine())
        passes.build_index(self.directory, target)
        self.state.update(plan=str(target), revision=1)
        save_json(self.directory / 'state.json', self.state)
        checks = [{'name': kind, 'kind': kind, 'argv': [sys.executable, '-c', 'from pathlib import Path; assert "new.pkg" in Path("source.kt").read_text()']}
                  for kind in ('build', 'test', 'runtime')]
        session.configure(self.repo, checks)
        return tree

    def test_complete_prepare_check_publish_and_queue(self):
        tree = self.ready()
        self.assertEqual('prepared', session.prepare(self.repo)['phase'])
        self.assertIn('new.pkg', (tree / 'source.kt').read_text())
        self.assertIn('old.pkg', (self.repo / 'source.kt').read_text())
        self.assertEqual('verified', session.validate(self.repo)['phase'])
        destination = self.base / 'published'
        result = session.publish(self.repo, '1', destination)
        self.assertEqual(str(destination), result['path'])
        self.assertIn('new.pkg', (destination / 'example.kt').read_text())
        self.assertIn('old.pkg', (self.workspace / 'example.kt').read_text())
        self.assertFalse((destination / 'backups').exists())
        # Only the external queue is mocked; commit, hashes and workspace publication are real.
        original = subprocess.check_output
        def output(argv, **kwargs):
            if str(passes.REPO / 'tools/land.py') in argv:
                return 'queued test-item'
            return original(argv, **kwargs)
        subprocess.run(['git', '-C', str(tree), 'config', 'user.name', 'Test'], check=True)
        subprocess.run(['git', '-C', str(tree), 'config', 'user.email', 'test@example.test'], check=True)
        with patch('tools.refactoring.session.subprocess.check_output', side_effect=output):
            self.assertEqual('queued', session.land(self.repo)['phase'])
            self.assertEqual('queued test-item', session.land(self.repo)['queueReceipt'])

    def test_holds_and_unreviewed_references_block_preparation(self):
        with self.assertRaisesRegex(RefactorError, 'blockers'):
            session.prepare(self.repo)
        passes.decide(self.repo, self.item, 'preserve', 'Keep label')
        with self.assertRaisesRegex(RefactorError, 'blockers'):
            session.prepare(self.repo)

    def test_preparation_cannot_replan_or_start_batch_and_can_restore(self):
        tree = self.ready(); session.prepare(self.repo)
        with self.assertRaisesRegex(RefactorError, 'Return to review'):
            passes.replan(self.repo, Path('/unused'))
        with self.assertRaisesRegex(RefactorError, 'Return to review'):
            passes.agent(self.repo, 'prose', '')
        session.restore(self.repo)
        self.assertIn('old.pkg', (tree / 'source.kt').read_text())
        # Repeated preparation uses a fresh journal.
        session.prepare(self.repo)

    def test_added_binary_or_changed_configuration_invalidates_verification(self):
        tree = self.ready(); session.prepare(self.repo); session.validate(self.repo)
        (tree / 'new.bin').write_bytes(b'\0new input')
        with self.assertRaisesRegex(RefactorError, 'Files or configuration changed'):
            session.publish(self.repo, '1', self.base / 'published')
        (tree / 'new.bin').unlink()
        session.configure(self.repo, [])
        with self.assertRaisesRegex(RefactorError, 'Configure build, test and runtime'):
            session.validate(self.repo)

    def test_failing_check_records_log_and_blocks_publication(self):
        self.ready(); session.prepare(self.repo)
        checks = [{'name': kind, 'kind': kind, 'argv': [sys.executable, '-c', 'raise SystemExit(7)']}
                  for kind in ('build', 'test', 'runtime')]
        session.configure(self.repo, checks)
        with self.assertRaisesRegex(RefactorError, 'build failed'):
            session.validate(self.repo)
        _, state = passes.load(self.repo)
        self.assertEqual('failed', session.phase(state))
        self.assertTrue(Path(state['delivery']['checks'][0]['log']).is_file())
        with self.assertRaises(RefactorError):
            session.land(self.repo)

    def test_check_that_changes_source_cannot_certify_its_own_edits(self):
        self.ready(); session.prepare(self.repo)
        checks = [{'name': kind, 'kind': kind, 'argv': [sys.executable, '-c', 'from pathlib import Path; Path("changed.txt").write_text("x")']}
                  for kind in ('build', 'test', 'runtime')]
        session.configure(self.repo, checks)
        with self.assertRaisesRegex(RefactorError, 'changed source'):
            session.validate(self.repo)

    def test_publication_refuses_existing_and_overlapping_destinations(self):
        self.ready(); session.prepare(self.repo); session.validate(self.repo)
        for target in (self.workspace, self.workspace / 'new', self.directory / 'new'):
            with self.assertRaises(RefactorError):
                session.publish(self.repo, '1', target)

    def test_close_resume_preserves_work_and_wrong_owner_is_rejected(self):
        self.ready()
        session.close(self.repo)
        self.assertFalse(passes.pointer(self.repo).exists())
        self.assertTrue(Path(self.state['worktree']).is_dir())
        self.assertTrue(session.sessions(self.repo, self.base / 'passes'))
        session.resume(self.repo, self.directory)
        self.assertEqual(self.state['worktree'], passes.load(self.repo)[1]['worktree'])

    def test_recipe_change_requires_replan_and_partial_preparation_can_be_undone(self):
        tree = self.ready()
        original_recipe = json.loads((self.directory / 'recipe.json').read_text())
        save_json(self.directory / 'recipe.json', dict(original_recipe, description='New intent'))
        with self.assertRaisesRegex(RefactorError, 'Recipe changed'):
            session.prepare(self.repo)
        save_json(self.directory / 'recipe.json', original_recipe)
        workspace = Path(self.state['roots'][1]['path'])
        (workspace / 'example.kt').write_text('later workspace edit')
        with self.assertRaisesRegex(RefactorError, 'stale plan input'):
            session.prepare(self.repo)
        self.assertIn('new.pkg', (tree / 'source.kt').read_text())
        session.restore(self.repo)
        self.assertIn('old.pkg', (tree / 'source.kt').read_text())
        self.assertEqual('later workspace edit', (workspace / 'example.kt').read_text())

    def test_continue_review_keeps_applied_edits_and_invalidates_receipt(self):
        tree = self.ready(); session.prepare(self.repo); session.validate(self.repo)
        (tree / 'notes.txt').write_text('authored fix')
        with patch('tools.refactor_pass.replan', return_value={'replanned': True}) as replan:
            session.continue_review(self.repo, Path('/engine'))
        self.assertEqual('review', session.phase(passes.load(self.repo)[1]))
        self.assertIn('new.pkg', (tree / 'source.kt').read_text())
        self.assertEqual('authored fix', (tree / 'notes.txt').read_text())
        replan.assert_called_once()
        with self.assertRaises(RefactorError):
            session.publish(self.repo, '1', self.base / 'published')

    def test_start_from_recipe_and_workspaces(self):
        session.close(self.repo)
        recipe = copy.deepcopy(json.loads((self.directory / 'recipe.json').read_text()))
        class Engine:
            def __init__(self, path): pass
            def __enter__(self): return ReviewEngine()
            def __exit__(self, *args): pass
        with patch('tools.refactor_pass.KotlinEngine', Engine):
            result = session.start(self.repo, json.dumps(recipe), [str(self.workspace)], Path('/unused'), self.base / 'new-sessions')
        self.assertEqual(1, result['pass']['revision'])
        self.assertFalse((Path(result['roots'][1]['path']) / 'backups').exists())
