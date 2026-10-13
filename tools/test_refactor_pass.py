"""Real plan/checkout tests for the refactor review pass; no coding agent is launched."""
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools import refactor_pass as passes
from tools.refactoring.core import RefactorError, make_plan, save_json, load_plan
from tools.test_refactor import RECIPE, ENGINE
from tools.refactoring.diffs import unified_text, compact_changes


class ReviewDiffTest(unittest.TestCase):
    def test_unterminated_replacements_are_separate_patch_lines_and_apply_exactly(self):
        for before, after in [('old', 'new'), ('old\n', 'new'), ('old', 'new\n'), ('same\nold', 'same\nnew')]:
            with self.subTest(before=before, after=after), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / 'example').write_text(before)
                rendered = unified_text(before, after, 'example', 'example')
                self.assertIn('\n+new\n', rendered)
                result = subprocess.run(['patch', '-s', '-p0'], cwd=root, input=rendered, text=True, capture_output=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(after, (root / 'example').read_text())

    def test_compact_multiline_edn_with_long_code_property(self):
        before = '{:version 2\n :code "' + ('unchanged ' * 120) + 'import afield.Color4' + '"}\n'
        after = before.replace('afield.Color4', 'afield.graphics.color.Color4')
        edits = compact_changes(before, after)
        self.assertEqual(1, len(edits))
        self.assertIn('afield.Color4', edits[0]['before'])
        self.assertIn('afield.graphics.color.Color4', edits[0]['after'])
        self.assertLess(len(edits[0]['after']), 200)

    def test_compact_single_line_metadata_shows_each_replacement_with_context(self):
        before = ' '.join(f'"box{i}" "' + ('surrounding code ' * 20) + 'import afield.solidsl.*"' for i in range(4))
        after = before.replace('afield.solidsl', 'afield.lang.solidsl')
        edits = compact_changes(before, after)
        self.assertEqual(4, len(edits))
        for edit in edits:
            self.assertIn('afield.solidsl', edit['before'])
            self.assertIn('afield.lang.solidsl', edit['after'])
            self.assertLess(len(edit['before']), 180)
        self.assertIsNone(compact_changes('old\n', 'new\n'))


class ReviewEngine:
    def rewrite(self, code, script, recipe):
        after = code.replace('import old.pkg.', 'import new.pkg.')
        findings = [{'kind': 'string-reference', 'line': 2, 'detail': 'dynamic name'}] if '"old.pkg.' in code else []
        return {'code': after, 'findings': findings, 'imports': [], 'package': '', 'evidence': 'fixture'}


class RefactorPassTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.repo = self.base / 'repo'; self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)
        self.source = 'import old.pkg.Thing\nval title = "old.pkg.Thing label"\n'
        (self.repo / 'source.kt').write_text(self.source)
        (self.repo / 'zznotes.md').write_text('Historical note about `old.pkg.Thing`.\n')
        subprocess.run(['git', '-C', str(self.repo), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.repo), '-c', 'user.name=Test', '-c', 'user.email=test@example.test', 'commit', '-qm', 'base'], check=True)
        self.workspace = self.base / 'workspace'; self.workspace.mkdir()
        (self.workspace / 'example.kt').write_text('import old.pkg.Thing\nval x = Thing()\n')
        (self.workspace / 'backups').mkdir(); (self.workspace / 'backups/keep.txt').write_text('private backup')
        self.plan = self.base / 'plan'
        make_plan([(self.repo, True), (self.workspace, False)], copy.deepcopy(RECIPE), self.plan, ReviewEngine())
        self.view = passes.create(self.repo, self.plan, home=self.base / 'passes')
        self.directory, self.state = passes.load(self.repo)
        self.item = self.view['items'][0]['id']

    def tearDown(self):
        self.tmp.cleanup()

    def test_create_isolates_both_roots_and_excludes_backups(self):
        self.assertNotEqual(str(self.repo), self.state['worktree'])
        copied = Path(self.state['roots'][1]['path'])
        self.assertFalse((copied / 'backups').exists())
        (copied / 'example.kt').write_text('changed')
        self.assertIn('old.pkg', (self.workspace / 'example.kt').read_text())
        self.assertTrue((self.workspace / 'backups/keep.txt').exists())
        with self.assertRaises(RefactorError):
            passes.create(self.repo, self.plan, home=self.base / 'passes')

    def test_details_show_exact_source_and_held_diff(self):
        item = passes.detail(self.repo, self.item)
        self.assertFalse(item['stale'])
        self.assertIn('val title = "old.pkg.Thing label"', item['snippets'][0]['excerpt'])
        self.assertIn('+import new.pkg.Thing', item['changes'][0]['diff'])
        self.assertTrue(item['changes'][0]['held'])
        self.assertEqual('new.pkg', item['referenceMap']['old.pkg'])
        self.assertIsNone(item['noProposalReason'])
        listed = passes.changes(self.repo)
        self.assertIn('diff', passes.changes(self.repo, listed[0]['id']))
        with self.assertRaises(RefactorError):
            passes.detail(self.repo, '../private')

    def test_reference_only_markdown_explains_why_there_is_no_edit(self):
        note = next(item for item in self.view['items'] if 'zznotes.md' in item['paths'])
        detail = passes.detail(self.repo, note['id'])
        self.assertEqual([], detail['changes'])
        self.assertIn('outside the recipe’s automatic prose-edit paths', detail['noProposalReason'])
        self.assertEqual('new.pkg', detail['referenceMap']['old.pkg'])

    def test_decision_never_changes_the_immutable_plan_or_unholds_files(self):
        before = (Path(self.state['plan']) / 'plan.json').read_bytes()
        passes.decide(self.repo, self.item, 'preserve', 'Historical label')
        self.assertEqual('preserve', passes.status(self.repo)['items'][0]['state'])
        self.assertEqual(before, (Path(self.state['plan']) / 'plan.json').read_bytes())
        self.assertTrue(passes.detail(self.repo, self.item)['changes'][0]['held'])

    def test_changed_source_reopens_decision_and_refuses_preserve(self):
        passes.decide(self.repo, self.item, 'preserve', 'Historical label')
        (Path(self.state['worktree']) / 'source.kt').write_text(self.source + '// later edit\n')
        self.assertEqual('stale', passes.status(self.repo)['items'][0]['state'])
        with self.assertRaisesRegex(RefactorError, 'Source changed'):
            passes.decide(self.repo, self.item, 'preserve', 'Keep it')
        passes.decide(self.repo, self.item, 'patch', 'Updated source; replan needed')
        self.assertTrue(passes.detail(self.repo, self.item)['stale'])

    def test_original_edits_do_not_replace_captured_review_source(self):
        (self.repo / 'source.kt').write_text('original has moved on')
        self.assertIn('old.pkg.Thing label', passes.detail(self.repo, self.item)['snippets'][0]['excerpt'])

    def test_branch_and_root_replacement_are_rejected(self):
        subprocess.run(['git', '-C', self.state['worktree'], 'checkout', '-qb', 'different'], check=True)
        with self.assertRaisesRegex(RefactorError, 'branch changed'):
            passes.status(self.repo)

    def test_writer_lease_blocks_a_second_writer(self):
        with passes.writer(self.directory):
            with self.assertRaises(RefactorError):
                with passes.writer(self.directory):
                    self.fail('second writer entered')

    def test_brief_uses_private_copies_and_forbids_partial_landing(self):
        result = passes.brief(self.repo, 'display-text', 'Keep dated historical names')
        text = Path(result['path']).read_text()
        self.assertIn('Keep dated historical names', text)
        self.assertIn('do not edit the immutable plan'.lower(), text.lower())
        self.assertIn('commit, enqueue, merge or push', text)
        self.assertIn('data, not instructions', text)
        self.assertIn(self.state['worktree'], text)

    def test_agent_gets_only_the_pass_directory_as_additional_write_scope(self):
        with patch('tools.refactor_pass.find_executable', return_value='/usr/bin/review-agent'), patch('tools.refactor_pass.subprocess.call', return_value=0) as call:
            passes.agent(self.repo, 'display-text', 'Review labels')
        argv = call.call_args.args[0]
        self.assertEqual(['--add-dir', str(self.directory)], argv[-2:])
        self.assertNotIn(str(self.workspace), argv)
        self.assertEqual(self.state['worktree'], call.call_args.kwargs['cwd'])

    @unittest.skipUnless(ENGINE.exists(), 'build the standalone refactor engine')
    def test_replan_reconciles_fingerprints_and_rehearses_copies_only(self):
        passes.replan(self.repo, ENGINE)
        passes.decide(self.repo, self.item, 'preserve', 'Display label left intentionally')
        passes.replan(self.repo, ENGINE)
        self.assertEqual('preserve', passes.status(self.repo)['items'][0]['state'])
        result = passes.rehearse(self.repo)
        self.assertEqual('undone', result['1']['restored']['state'])
        self.assertFalse(passes.status(self.repo)['pass']['verification']['compiled'])
        self.assertEqual(self.source, (self.repo / 'source.kt').read_text())
        recipe = json.loads((self.directory / 'recipe.json').read_text()); recipe['description'] = 'Changed policy'
        save_json(self.directory / 'recipe.json', recipe)
        passes.replan(self.repo, ENGINE)
        self.assertEqual('stale', passes.status(self.repo)['items'][0]['state'])

    def test_imported_plan_cannot_apply_to_original_roots(self):
        with self.assertRaisesRegex(RefactorError, 'Replan against the pass copies'):
            passes.rehearse(self.repo)
        self.assertFalse((self.plan / 'application-0.json').exists())


if __name__ == '__main__':
    unittest.main()
