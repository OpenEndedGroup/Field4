"""Migration integrity tests; no Field server or workspace execution."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
from unittest.mock import patch as mock_patch

from tools.refactoring.containers import ContainerError, EdnSpans, edn, extract, markdown, patch, qualified_name_properties, yaml_units
from tools.refactoring.core import (KotlinEngine, RefactorError, apply_plan, canonical, digest,
    load_plan, load_recipe, make_plan, save_json, undo, verify)

RECIPE = {'schema': 1, 'id': 'test-move', 'packages': {'old.pkg': 'new.pkg'},
          'symbols': {}, 'splitPackages': [], 'generated': []}
PROPS = {'code', 'preamble', 'sharedTypes'}
SOURCE = 'import old.pkg.Thing as T\nval x = T()\n'


class ImportFixture:
    """Container/transaction tests inject a deterministic edit; real PSI tests are below and JVM-side."""
    def rewrite(self, code, script, recipe):
        return {'code': code.replace('import old.pkg.', 'import new.pkg.'),
                'findings': [], 'imports': [], 'evidence': 'fixture'}


def metadata(code=SOURCE, sidecar=None):
    value = {'path': sidecar, 'inline': code} if sidecar else code
    if sidecar:
        value = '#afield/file-inline {' + ' '.join(json.dumps(k) + ' ' + json.dumps(v) for k, v in value.items()) + '}'
    else:
        value = json.dumps(value)
    return ('; untouched comment\n{"version" 2 "boxes" [{"id" "box1" "properties" '
            '{"code" ' + value + ' "parents" #{#afield/box "parent"} "frame" #afield/rect [1 2 3 4]}}]}\n')


class ContainerTests(unittest.TestCase):
    def test_qualified_name_configuration_preserves_layout_and_unrelated_values(self):
        source = '# Names\r\n linalg.package = old.pkg  \r\nurl=https://example.test\r\n'
        result = qualified_name_properties(source, {'linalg.package'})
        self.assertEqual('import old.pkg\n', result.units[0].code)
        self.assertFalse(result.units[0].script)
        after = patch(source, [(u.start, u.end, u.encode(u.code.replace('old.pkg', 'new.pkg'))) for u in result.units])
        self.assertEqual(source.replace('old.pkg', 'new.pkg'), after)

    def test_qualified_name_configuration_rejects_ambiguous_property_syntax(self):
        for source in ('linalg.package=old.pkg\nlinalg.package=other.pkg', 'linalg.package=old.\\\npkg',
                       'linalg\\.package=old.pkg', 'linalg.package=../escape', 'unknown=old.pkg'):
            with self.subTest(source=source), self.assertRaises(ContainerError):
                qualified_name_properties(source, {'linalg.package'})

    def transform(self, text, extraction):
        return patch(text, [(u.start, u.end, u.encode(u.code.replace('old.pkg', 'new.pkg'))) for u in extraction.units])

    def test_edn_inline_preserves_unrelated_bytes(self):
        text = metadata()
        result = edn(text, PROPS)
        self.assertEqual(result.units[0].identity, 'box1/code')
        self.assertEqual(result.units[0].code, SOURCE)
        self.assertEqual(self.transform(text, result), text.replace('old.pkg', 'new.pkg'))

    def test_edn_sidecars_and_unicode(self):
        text = metadata('// 🎹\n' + SOURCE, 'box_code_.kt')
        result = edn(text, PROPS)
        self.assertEqual(result.units[0].sidecar, 'box_code_.kt')
        self.assertEqual(edn(self.transform(text, result), PROPS).units[0].code, ('// 🎹\n' + SOURCE).replace('old.pkg', 'new.pkg'))

    def test_custom_kotlin_sidecar_is_found_by_type_not_property_name(self):
        text = metadata(sidecar='box_custom_.kt').replace('"code"', '"custom"')
        result = edn(text, PROPS)
        self.assertEqual('box1/custom', result.units[0].identity)
        self.assertEqual('box_custom_.kt', result.units[0].sidecar)

    def test_edn_rejects_malformed_and_reader_extensions(self):
        for text in ('{"a"}', '#_ {} {}', '"unfinished', '{} {}'):
            with self.subTest(text=text), self.assertRaises(ContainerError):
                EdnSpans(text).document()

    def test_markdown_nested_attributed_fences_and_non_kotlin(self):
        text = 'intro\n\n    ~~~~ {.kotlin}\n    import old.pkg.X\n    val x = 1\n    ~~~~\n```python\nimport old.pkg\n```\n'
        result = markdown(text)
        self.assertEqual(len(result.units), 1)
        self.assertEqual(self.transform(text, result), text.replace('old.pkg.X', 'new.pkg.X'))

    def test_yaml_block_and_quoted_edit_both_directions(self):
        text = 'operations:\n  - create:\n      properties:\n        code: |- # retain me\n          import old.pkg.X\n          val x = 1\n  - edit:\n      property: code\n      from: "import old.pkg.X\\n"\n      to: "import old.pkg.Y\\n"\n'
        result = yaml_units(text, PROPS)
        after = self.transform(text, result)
        self.assertIn('|- # retain me', after)
        self.assertEqual([u.code.replace('old.pkg', 'new.pkg') for u in result.units], [u.code for u in yaml_units(after, PROPS).units])

    def test_yaml_chomping_and_line_endings(self):
        for header in ('|', '|-', '|+'):
            text = f'code: {header}\r\n  import old.pkg.X\r\n  val x = 1\r\n\r\n'
            result = yaml_units(text, PROPS)
            after = self.transform(text, result)
            self.assertEqual(yaml_units(after, PROPS).units[0].code, result.units[0].code.replace('old.pkg', 'new.pkg'))
            self.assertIn('\r\n', after)

    def test_yaml_aliases_and_duplicate_keys_fail_closed(self):
        for text in ('code: &code "import old.pkg.X"\npreamble: *code\n', 'code: x\ncode: y\n', 'x: &loop [*loop]'):
            with self.subTest(text=text), self.assertRaises(ContainerError):
                yaml_units(text, PROPS)

    def test_malformed_yaml_is_a_container_finding(self):
        with self.assertRaisesRegex(ContainerError, 'invalid YAML'):
            yaml_units('code: hello\nnot a mapping\n', PROPS)

    def test_other_languages_and_overlaps(self):
        text = 'language: python\ncode: "import old.pkg"\n'
        self.assertEqual([], yaml_units(text, PROPS).units)
        with self.assertRaises(ContainerError):
            patch('abc', [(0, 2, 'x'), (1, 3, 'y')])

    def test_repo_box_fixtures_are_scripts_even_with_kt_suffix(self):
        self.assertTrue(extract('tools/ui_regressions/demo.kt', SOURCE, PROPS).units[0].script)
        self.assertFalse(extract('src/jvmMain/kotlin/old/pkg/Thing.kt', SOURCE, PROPS).units[0].script)


class PlanTests(unittest.TestCase):
    def test_workspace_markdown_scope_is_recursive_and_does_not_expand_repo(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            repo, workspace = base / 'repo', base / 'workspace'
            for root in (repo, workspace):
                (root / 'nested').mkdir(parents=True)
                for rel in ('root.md', 'nested/notes.md'):
                    (root / rel).write_text('See old.pkg.Thing.\n')
            subprocess.run(['git', 'init', '-q', str(repo)], check=True)
            subprocess.run(['git', '-C', str(repo), 'add', '.'], check=True)
            recipe_path = base / 'recipe.json'
            save_json(recipe_path, dict(copy.deepcopy(RECIPE), workspaceMarkdownProse=['*.md']))
            recipe = load_recipe(recipe_path)
            plan = make_plan([(repo, True), (workspace, False)], recipe, base / 'plan', ImportFixture())
            self.assertEqual([], plan['roots'][0]['changes'])
            self.assertEqual({'root.md', 'nested/notes.md'}, {c['path'] for c in plan['roots'][1]['changes']})
            self.assertTrue(all(not c['held'] for c in plan['roots'][1]['changes']))
            apply_plan(base / 'plan', '1', offline=True)
            for rel in ('root.md', 'nested/notes.md'):
                self.assertEqual('See new.pkg.Thing.\n', (workspace / rel).read_text())
                self.assertEqual('See old.pkg.Thing.\n', (repo / rel).read_text())
            undo(base / 'plan', '1')
            verify(base / 'plan', '1')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.root = self.base / 'workspace'
        self.root.mkdir()
        self.plan = self.base / 'plan'

    def tearDown(self):
        self.temp.cleanup()

    def write(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def make(self, recipe=None):
        return make_plan([(self.root, False)], recipe or copy.deepcopy(RECIPE), self.plan, ImportFixture())

    def test_document_pair_apply_verify_undo_exact_bytes(self):
        sidecar = self.write('doc/box_code_.kt', SOURCE)
        doc = self.write('doc/metadata.edn', metadata(sidecar='box_code_.kt'))
        sidecar.chmod(0o640)
        before = doc.read_bytes()
        plan = self.make()
        self.assertEqual(2, len(plan['roots'][0]['changes']))
        self.assertFalse(any(c['held'] for c in plan['roots'][0]['changes']))
        apply_plan(self.plan, offline=True)
        self.assertEqual(SOURCE.replace('old.pkg', 'new.pkg'), sidecar.read_text())
        self.assertEqual(0o640, sidecar.stat().st_mode & 0o777)
        self.assertFalse(verify(self.plan)['compiled'])
        undo(self.plan)
        self.assertEqual(before, doc.read_bytes())
        self.assertEqual(SOURCE, sidecar.read_text())
        self.assertEqual('undone', verify(self.plan)['state'])

    def test_inline_drift_holds_the_entire_document(self):
        self.write('doc/box_code_.kt', SOURCE)
        self.write('doc/metadata.edn', metadata(SOURCE + '// stale fallback\n', 'box_code_.kt'))
        plan = self.make()
        self.assertTrue(all(c['held'] for c in plan['roots'][0]['changes']))
        with self.assertRaisesRegex(RefactorError, 'review findings'):
            apply_plan(self.plan, offline=True)
        applied = apply_plan(self.plan, offline=True, allow_partial=True)
        self.assertTrue(applied['partial'])
        self.assertEqual([], applied['changes'])

    def test_copy_mode_leaves_original_untouched(self):
        source = self.write('a.kt', SOURCE)
        self.write('backups/old.kt', SOURCE)
        self.write('thumbnails/test.png', 'irrelevant')
        self.make()
        destination = self.base / 'migrated'
        apply_plan(self.plan, copy_to=destination)
        self.assertEqual(SOURCE, source.read_text())
        self.assertIn('new.pkg', (destination / 'a.kt').read_text())
        self.assertFalse((destination / 'backups').exists())
        self.assertFalse((destination / 'thumbnails').exists())
        self.assertEqual(str(destination), verify(self.plan)['root'])

    def test_stale_unedited_input_rejects_before_any_write(self):
        first = self.write('a.kt', SOURCE)
        other = self.write('z.kt', 'val x = 1\n')
        self.make()
        other.write_text('val x = 2\n')
        with self.assertRaisesRegex(RefactorError, 'stale plan'):
            apply_plan(self.plan, offline=True)
        self.assertEqual(SOURCE, first.read_text())

    def test_file_added_during_copy_prevents_publication(self):
        self.write('a.kt', SOURCE)
        self.make()
        import shutil
        original = shutil.copytree
        def changed_during_copy(source, destination, **kwargs):
            result = original(source, destination, **kwargs)
            (Path(destination) / 'late.kt').write_text(SOURCE)
            return result
        destination = self.base / 'migrated'
        with mock_patch('tools.refactoring.core.shutil.copytree', side_effect=changed_during_copy):
            with self.assertRaisesRegex(RefactorError, 'file set changed during copy'):
                apply_plan(self.plan, copy_to=destination)
        self.assertFalse(destination.exists())

    def test_new_file_invalidates_plan(self):
        self.write('a.kt', SOURCE)
        self.make()
        self.write('later.kt', SOURCE)
        with self.assertRaisesRegex(RefactorError, 'input set changed'):
            apply_plan(self.plan, offline=True)

    def test_undo_refuses_later_edits_before_restoring_anything(self):
        a = self.write('a.kt', SOURCE)
        b = self.write('b.kt', SOURCE)
        self.make()
        apply_plan(self.plan, offline=True)
        b.write_text('// later edit\n')
        with self.assertRaisesRegex(RefactorError, 'later edits'):
            undo(self.plan)
        self.assertIn('new.pkg', a.read_text())

    def test_interrupted_apply_recovers_from_content_not_progress_log(self):
        self.write('a.kt', SOURCE)
        self.write('b.kt', SOURCE)
        self.make()
        from tools.refactoring.core import atomic
        def crash(path, data, mode=0o600):
            atomic(path, data, mode)
            if path.name == 'a.kt':
                raise OSError('simulated interruption after replacement')
        with mock_patch('tools.refactoring.core.atomic', side_effect=crash):
            with self.assertRaises(OSError):
                apply_plan(self.plan, offline=True)
        self.assertEqual(1, undo(self.plan)['restoredFiles'])
        self.assertEqual(SOURCE, (self.root / 'a.kt').read_text())
        self.assertEqual(SOURCE, (self.root / 'b.kt').read_text())

    def test_symlink_swap_and_escaping_sidecars_are_refused(self):
        source = self.write('a.kt', SOURCE)
        self.make()
        source.unlink()
        outside = self.base / 'outside.kt'
        outside.write_text(SOURCE)
        source.symlink_to(outside)
        with self.assertRaisesRegex(RefactorError, 'symlink'):
            apply_plan(self.plan, offline=True)
        self.assertEqual(SOURCE, outside.read_text())

    def test_escaping_sidecar_holds_document(self):
        self.write('doc/metadata.edn', metadata(sidecar='../secret.kt'))
        plan = self.make()
        self.assertTrue(any(f['kind'] == 'unsafe-sidecar' for f in plan['roots'][0]['findings']))

    def test_tampered_plan_and_blob_are_detected(self):
        self.write('a.kt', SOURCE)
        plan = self.make()
        change = plan['roots'][0]['changes'][0]
        (self.plan / 'blobs' / change['after']).write_bytes(b'corrupt')
        with self.assertRaisesRegex(RefactorError, 'corrupted blob'):
            apply_plan(self.plan, offline=True)
        plan['roots'][0]['path'] = '/elsewhere'
        save_json(self.plan / 'plan.json', plan)
        with self.assertRaisesRegex(RefactorError, 'integrity'):
            load_plan(self.plan)

    def test_overlap_and_existing_destinations(self):
        with self.assertRaisesRegex(RefactorError, 'overlap'):
            make_plan([(self.root, False), (self.root, False)], copy.deepcopy(RECIPE), self.plan, ImportFixture())
        self.write('a.kt', SOURCE)
        self.make()
        with self.assertRaisesRegex(RefactorError, 'new and outside'):
            apply_plan(self.plan, copy_to=self.root / 'nested')

    def test_generated_outputs_are_findings_not_edits(self):
        self.write('generated/example.kt', SOURCE)
        recipe = copy.deepcopy(RECIPE)
        recipe['generated'] = [{'glob': 'generated/*', 'regenerate': 'run generator'}]
        plan = self.make(recipe)
        self.assertEqual([], plan['roots'][0]['changes'])
        self.assertTrue(any(f['kind'] == 'regenerate' for f in plan['roots'][0]['findings']))

    def test_recipe_validation_rejects_chains_and_collisions(self):
        path = self.base / 'recipe.json'
        for packages in ({'a': 'b', 'b': 'c'}, {'a': 'c', 'b': 'c'}, {'a': 'a'}, {'a': 'bad/path'}):
            recipe = copy.deepcopy(RECIPE)
            recipe['packages'] = packages
            path.write_text(json.dumps(recipe))
            with self.assertRaises(RefactorError):
                load_recipe(path)


ENGINE = Path(__file__).resolve().parent / 'refactor-engine/build/install/field-refactor-engine/bin/field-refactor-engine'


@unittest.skipUnless(ENGINE.exists(), 'standalone engine integration: run tools/refactor.py build-engine first')
class RealEngineTests(unittest.TestCase):
    def test_box_split_coverage_excludes_other_platforms_and_requires_host_imports(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            (root / 'doc').mkdir(parents=True)
            source = 'import old.mixed.* // host supplied\nval x = Thing()\n'
            recipe = dict(copy.deepcopy(RECIPE), packages={}, splitPackages=['old.mixed'],
                          symbols={'old.mixed.Thing': 'new.model.Thing', 'old.mixed.Browser': 'new.browser.Browser'},
                          splitStarImports={'old.mixed': ['new.model', 'new.browser']},
                          boxImports=['new.model.*'], boxSplitStarImports={'old.mixed': ['new.model']},
                          scriptImports=[])
            save_json(base / 'recipe.json', recipe)
            load_recipe(base / 'recipe.json')
            (root / 'doc/metadata.edn').write_text(metadata(source, 'code.kt'))
            (root / 'doc/code.kt').write_text(source)
            (root / 'standalone.kts').write_text(source)
            make_plan([(root, False)], recipe, base / 'plan', engine)
            apply_plan(base / 'plan', offline=True, allow_partial=True)
            self.assertEqual(' // host supplied\nval x = Thing()\n', (root / 'doc/code.kt').read_text())
            self.assertNotIn('new.browser', (root / 'doc/metadata.edn').read_text())
            self.assertIn('import new.browser.*', (root / 'standalone.kts').read_text())
            again = make_plan([(root, False)], recipe, base / 'again', engine)
            self.assertEqual([], again['roots'][0]['changes'])
            for targets in (['new.browser'], ['not.mapped'], [], ['new.model', 'new.model']):
                save_json(base / 'recipe.json', dict(recipe, boxSplitStarImports={'old.mixed': targets}))
                with self.subTest(targets=targets), self.assertRaises(RefactorError):
                    load_recipe(base / 'recipe.json')

    def test_box_intro_removes_redundant_split_stars_only_from_registered_box_code(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            root.mkdir()
            source = 'import old.mixed.* // supplied by intro\nval x = Thing()\n'
            recipe = dict(copy.deepcopy(RECIPE), packages={}, splitPackages=['old.mixed'],
                          symbols={'old.mixed.Thing': 'new.model.Thing'},
                          boxImports=['old.mixed.*', 'new.model.*'])
            save_json(base / 'recipe.json', recipe)
            load_recipe(base / 'recipe.json')
            (root / 'doc').mkdir()
            (root / 'doc/metadata.edn').write_text(metadata(source, 'code.kt'))
            (root / 'doc/code.kt').write_text(source)
            (root / 'inline').mkdir()
            (root / 'inline/metadata.edn').write_text(metadata(source))
            (root / 'standalone.kts').write_text(source)
            (root / 'unregistered.kt').write_text(source)
            (root / 'docs.md').write_text('```kotlin\n' + source + '```\n')
            (root / 'src').mkdir()
            (root / 'src/Example.kt').write_text(source)
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            changes = plan['roots'][0]['changes']
            self.assertEqual({'doc/code.kt', 'doc/metadata.edn', 'inline/metadata.edn'}, {c['path'] for c in changes})
            self.assertTrue(all(not c['held'] for c in changes))
            self.assertEqual({'standalone.kts', 'unregistered.kt', 'docs.md', 'src/Example.kt'},
                             {f['path'] for f in plan['roots'][0]['findings'] if f['kind'] == 'split-star'})
            apply_plan(base / 'plan', offline=True, allow_partial=True)
            self.assertEqual(' // supplied by intro\nval x = Thing()\n', (root / 'doc/code.kt').read_text())
            self.assertEqual(edn((root / 'doc/metadata.edn').read_text(), PROPS).units[0].code,
                             (root / 'doc/code.kt').read_text())
            verify(base / 'plan')
            again = make_plan([(root, False)], recipe, base / 'again', engine)
            self.assertEqual([], again['roots'][0]['changes'])

    def test_docs_wrappers_preserve_markers_and_parse_before_after_separately(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            root.mkdir()
            source = ('  ```diff_runner {data-lang=kotlin}\r\n'
                      '  :::before\r\n  import old.pkg.Thing\r\n  val x = Thing()\r\n'
                      '  :::after\r\n  import old.pkg.Thing\r\n  fun draw() {\r\n'
                      '  :::fold use old.pkg.Thing\r\n  val x = Thing()\r\n  :::endfold\r\n  }\r\n  ```\r\n'
                      '```diffstatic_runner {data-lang=kotlin}\n:::static\nval x = old.pkg.Thing()\n:::\n```\n')
            document = root / 'docs.md'
            document.write_bytes(source.encode())
            recipe = dict(copy.deepcopy(RECIPE), rewriteComments=True)
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            self.assertFalse(plan['roots'][0]['findings'])
            self.assertEqual(3, plan['roots'][0]['units'])
            apply_plan(base / 'plan', offline=True)
            self.assertEqual(source.replace('old.pkg', 'new.pkg'), document.read_bytes().decode())
            verify(base / 'plan')
            undo(base / 'plan')
            self.assertEqual(source, document.read_bytes().decode())

    def test_docs_wrappers_retain_unknown_markers_and_plain_kotlin_errors(self):
        with KotlinEngine(ENGINE) as engine:
            for source in ('```kotlin\n:::static\nval x = old.pkg.Thing()\n```\n',
                           '```diffstatic {data-lang=kotlin}\n:::unknown\nval x = old.pkg.Thing()\n```\n'):
                unit = markdown(source).units[0]
                self.assertTrue(engine.rewrite(unit.code, True, RECIPE)['findings'])

    def test_comment_and_selected_markdown_pass_preserves_other_languages_and_history(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            root.mkdir()
            document = root / 'current.md'
            document.write_text('See old.pkg.Thing.\n```kotlin\n// old.pkg.Thing\nval x = old.pkg.Thing()\n```\n')
            other = root / 'other.md'
            other.write_text('See old.pkg.Thing.\n```python\nname = "old.pkg.Thing"\n```\n')
            history = root / 'history.md'
            history.write_text('Previously old.pkg.Thing.\n')
            recipe = dict(copy.deepcopy(RECIPE), rewriteComments=True, markdownProse=['current.md', 'other.md'])
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            states = {c['path']: c['held'] for c in plan['roots'][0]['changes']}
            self.assertEqual({'current.md': False, 'other.md': True}, states)
            apply_plan(base / 'plan', offline=True, allow_partial=True)
            self.assertNotIn('old.pkg', document.read_text())
            self.assertIn('name = "old.pkg.Thing"', other.read_text())
            self.assertEqual('Previously old.pkg.Thing.\n', history.read_text())
            self.assertEqual(3, verify(base / 'plan')['filesVerified'])
            undo(base / 'plan')
            self.assertIn('See old.pkg.Thing.', document.read_text())

    def test_review_groups_exclude_unrelated_inventory_errors_but_include_dynamic_names(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            root.mkdir()
            (root / 'unrelated.kt').write_text('val x = (')
            (root / 'dynamic.kt').write_text('val name = "old.pkg.Thing"')
            plan = make_plan([(root, False)], copy.deepcopy(RECIPE), base / 'plan', engine)
            self.assertTrue(any(f['path'] == 'unrelated.kt' for f in plan['roots'][0]['findings']))
            review = json.loads((base / 'plan/review-groups.json').read_text())['0']
            self.assertEqual(['file:dynamic.kt'], [r['group'] for r in review])
            self.assertEqual([], review[0]['candidateFiles'])

    def test_registered_generator_config_is_mapped_and_outputs_remain_generator_owned(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            root.mkdir()
            config = root / 'packages.properties'
            config.write_text('# Generator names\nlinalg.package=old.pkg\n')
            output = root / 'Generated.kt'
            output.write_text('package old.pkg\nclass Thing\n')
            recipe = dict(copy.deepcopy(RECIPE), qualifiedNameProperties={'packages.properties': ['linalg.package']},
                          generated=[{'glob': 'Generated.kt', 'regenerate': 'run the generator'}])
            path = base / 'recipe.json'
            path.write_text(json.dumps(recipe))
            recipe = load_recipe(path)
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            self.assertEqual(['packages.properties'], [c['path'] for c in plan['roots'][0]['changes']])
            self.assertFalse(plan['roots'][0]['changes'][0]['held'])
            self.assertTrue(any(f['kind'] == 'regenerate' for f in plan['roots'][0]['findings']))
            apply_plan(base / 'plan', offline=True, allow_partial=True)
            self.assertEqual('# Generator names\nlinalg.package=new.pkg\n', config.read_text())
            self.assertEqual('package old.pkg\nclass Thing\n', output.read_text())
            self.assertEqual(2, verify(base / 'plan')['filesVerified'])
            undo(base / 'plan')
            self.assertEqual('# Generator names\nlinalg.package=old.pkg\n', config.read_text())

    def test_qualified_references_and_destination_intro_across_document_pair(self):
        source = 'import old.pkg.*\nimport old.pkg.Thing\nval x: old.pkg.Thing = old.pkg.Thing()\n'
        expected = '\n\nval x: new.pkg.Thing = new.pkg.Thing()\n'
        recipe = dict(copy.deepcopy(RECIPE), scriptImports=['new.pkg.*', 'new.pkg.Thing'])
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            root.mkdir()
            sidecar = root / 'box_code_.kt'
            sidecar.write_text(source)
            (root / 'metadata.edn').write_text(metadata(source, 'box_code_.kt'))
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            self.assertEqual(2, len(plan['roots'][0]['changes']))
            self.assertFalse(any(c['held'] for c in plan['roots'][0]['changes']))
            apply_plan(base / 'plan', offline=True)
            self.assertEqual(expected, sidecar.read_text())
            self.assertEqual(expected, edn((root / 'metadata.edn').read_text(), PROPS).units[0].code)
            self.assertEqual(2, verify(base / 'plan')['changedFiles'])
            undo(base / 'plan')
            self.assertEqual(source, sidecar.read_text())

    def test_intro_recipe_rejects_old_names_and_accepts_destination(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'recipe.json'
            for imports in ('new.pkg.*', ['import new.pkg.*'], ['old.pkg.*']):
                path.write_text(json.dumps(dict(RECIPE, scriptImports=imports)))
                with self.subTest(imports=imports), self.assertRaises(RefactorError):
                    load_recipe(path)
            path.write_text(json.dumps(dict(RECIPE, scriptImports=['new.pkg.*', 'new.pkg.Thing'])))
            self.assertEqual(['new.pkg.*', 'new.pkg.Thing'], load_recipe(path)['scriptImports'])

    def test_whole_package_source_move_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'repo'
            root.mkdir()
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            source = root / 'src/main/kotlin/old/pkg/Thing.kt'
            source.parent.mkdir(parents=True)
            source.write_text('package old.pkg\nclass Thing\n')
            subprocess.run(['git', '-C', str(root), 'add', '.'], check=True)
            out = base / 'plan'
            plan = make_plan([(root, True)], copy.deepcopy(RECIPE), out, engine)
            change = plan['roots'][0]['changes'][0]
            self.assertFalse(change['held'])
            self.assertEqual('src/main/kotlin/new/pkg/Thing.kt', change['destination'])
            apply_plan(out, offline=True)
            self.assertFalse(source.exists())
            self.assertEqual('package new.pkg\nclass Thing\n', (root / change['destination']).read_text())
            self.assertEqual(1, verify(out)['changedFiles'])
            undo(out)
            self.assertEqual('package old.pkg\nclass Thing\n', source.read_text())
            self.assertFalse((root / change['destination']).exists())

    def test_real_parser_through_all_containers(self):
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp)
            root = base / 'workspace'
            root.mkdir()
            (root / 'box_code_.kt').write_text(SOURCE)
            (root / 'metadata.edn').write_text(metadata(sidecar='box_code_.kt'))
            (root / 'doc.md').write_text('```kotlin\n' + SOURCE + '```\n')
            (root / 'macro.yaml').write_text('code: ' + json.dumps(SOURCE) + '\n')
            out = base / 'plan'
            plan = make_plan([(root, False)], copy.deepcopy(RECIPE), out, engine)
            self.assertEqual(4, len(plan['roots'][0]['changes']))
            self.assertFalse(any(c['held'] for c in plan['roots'][0]['changes']))
            apply_plan(out, offline=True)
            self.assertEqual(4, verify(out)['changedFiles'])
            again = make_plan([(root, False)], copy.deepcopy(RECIPE), base / 'again', engine)
            self.assertEqual([], again['roots'][0]['changes'])
            undo(out)
            self.assertEqual(SOURCE, (root / 'box_code_.kt').read_text())


if __name__ == '__main__':
    unittest.main()
