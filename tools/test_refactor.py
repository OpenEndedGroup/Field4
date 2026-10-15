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
    load_plan, load_recipe, make_plan, save_json, undo, verify, affected_pattern, rewrite_qualified_text, discover)

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
    def test_recorded_box_yaml_uses_host_imports_only_for_box_operations(self):
        source = ('operations:\n'
                  '  - launch: {box: first, code: "import old.pkg.*"}\n'
                  '  - create: {box: second, properties: {preamble: "import old.pkg.*"}}\n'
                  '  - edit: {box: first, property: code, from: "old", to: "new"}\n'
                  'unrelated: {code: "import old.pkg.*"}\n')
        units = yaml_units(source, PROPS).units
        self.assertEqual([True, True, True, True, False], [u.box_context for u in units])
        self.assertEqual('import old.pkg.*', units[0].code)

    def test_workspace_discovery_preserves_history_and_appledouble(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'versions.edn').write_text('{"history" []}')
            (root / '._code.kt').write_bytes(b'\x00\x05\x16\x07binary resource fork')
            (root / '._ordinary.kt').write_text('val valid = 1')
            (root / 'code.kt').write_text('val code = 1')
            files, retained = discover(root)
            self.assertEqual(['._ordinary.kt', 'code.kt'], files)
            self.assertEqual({'historical-or-excluded', 'appledouble-metadata'}, {f['kind'] for f in retained})

    def test_dependency_records_are_distinct_from_box_metadata(self):
        source = ('; keep layout\n{"version" 1 "edges" [\n'
                  '{"name" "old.pkg.Thing" "mechanism" "FROM_CONTEXT" "count" 7 "matchedName" "renderer"}\n'
                  '{"name" "old.pkg.Thing" "mechanism" "INHERITANCE" "count" 9}]}\n')
        result = extract('doc/dependencies.edn', source, PROPS)
        self.assertEqual(1, len(result.units))
        unit = result.units[0]
        self.assertEqual('import old.pkg.Thing\n', unit.code)
        edited = patch(source, [(unit.start, unit.end, unit.encode('import new.pkg.Thing\n'))])
        self.assertEqual(source.replace('"old.pkg.Thing"', '"new.pkg.Thing"', 1), edited)
        with self.assertRaises(ContainerError):
            extract('doc/dependencies.edn', source.replace('"version" 1', '"version" 2'), PROPS)
        with self.assertRaises(ContainerError):
            unit.encode('import new.pkg.*\n')
        unsupported = extract('dependencies.edn', source.replace('old.pkg.Thing', '[Lold.pkg.Thing;'), PROPS)
        self.assertEqual('unsupported-dependency-type', unsupported.findings[0]['kind'])

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

    def test_exported_text_preamble_is_box_kotlin_not_arbitrary_text(self):
        source = ('```text [field-property box="box1" name="preamble"]\nimport old.pkg.X\n```\n'
                  '```text [field-property box="box1" name="notes"]\nimport old.pkg.X\n```\n'
                  '```text\nimport old.pkg.X\n```\n'
                  '```python [field-property box="box1" name="preamble"]\nimport old.pkg.X\n```\n')
        units = markdown(source).units
        self.assertEqual(1, len(units))
        self.assertTrue(units[0].box_context)
        self.assertEqual(source.replace('old.pkg.X', 'new.pkg.X', 1), self.transform(source, markdown(source)))

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
    def test_field_urls_are_opaque_but_adjacent_names_still_migrate(self):
        recipe = dict(copy.deepcopy(RECIPE), packages={'afield.old': 'afield.newplace'},
                      symbols={}, splitPackages=['afield'])
        pattern = affected_pattern(recipe)
        for url in ('afield:/document/box/code', 'afield://document/box/code',
                    'afield://document/afield.old.Thing?type=afield.old.Other',
                    'AFIELD:/document/afield.old.Thing'):
            text = f'fileKey={url} next'
            with self.subTest(url=url):
                self.assertIsNone(pattern.search(text))
                self.assertEqual(text, rewrite_qualified_text(text, recipe))
        text = '`afield:/document/afield.old.Thing` then afield.old.Thing'
        self.assertEqual(['afield.old.Thing'], [m.group() for m in pattern.finditer(text)])
        self.assertEqual('`afield:/document/afield.old.Thing` then afield.newplace.Thing',
                         rewrite_qualified_text(text, recipe))
        self.assertEqual('afield', pattern.search('afield: Value').group())
        self.assertEqual('afield', pattern.search('package afield').group())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'workspace'; root.mkdir()
            (root / 'conversation.json').write_text(json.dumps({
                'log': ['fileKey=afield://function_conversation/function_source/code',
                        'primary=afield:/document/afield.old.Thing']}))
            plan = make_plan([(root, False)], recipe, Path(tmp) / 'plan', ImportFixture())
            self.assertFalse(plan['roots'][0]['findings'])
            self.assertFalse(plan['roots'][0]['changes'])

    def test_affected_references_respect_namespace_and_path_boundaries(self):
        recipe = dict(copy.deepcopy(RECIPE), splitPackages=['old'],
                      knownPackages=['old', 'old.stays'], retainedSymbols=['old.Keep'])
        pattern = affected_pattern(recipe)
        for text in ('old.pkg.Type/path', 'old/pkg', 'some.old.pkg.Type', 'oldExtra.pkg.Type',
                     'old.Keep.Nested', 'old.stays.Type', 'new.pkg.Type'):
            with self.subTest(text=text):
                self.assertIsNone(pattern.search(text))
        for text, expected in (('old.pkg.Type.', 'old.pkg.Type'), ('old.Unknown', 'old.Unknown'),
                               ('old.pkg.Type/path then old.Other', 'old.Other')):
            with self.subTest(text=text):
                self.assertEqual(expected, pattern.search(text).group())

    def test_hyphenated_namespace_labels_are_not_packages(self):
        recipe = dict(copy.deepcopy(RECIPE), packages={}, symbols={'afield.Thing': 'moved.Thing'},
                      splitPackages=['afield'])
        pattern = affected_pattern(recipe)
        for text in ('afield-stacksl-token-comment', 'afield-stacksl-token-*', 'afield-',
                     'class="afield-panel"', 'data-afield-token'):
            with self.subTest(text=text):
                self.assertIsNone(pattern.search(text))
                self.assertEqual(text, rewrite_qualified_text(text, recipe))
        # Do not hide qualified references next to arithmetic or punctuation.
        for text, expected in (('afield.Thing-other', 'afield.Thing'),
                               ('afield.Unknown', 'afield.Unknown'), ('package afield', 'afield')):
            self.assertEqual(expected, pattern.search(text).group())
        self.assertEqual('moved.Thing-other', rewrite_qualified_text('afield.Thing-other', recipe))
        self.assertEqual(['afield.Thing'], [m.group() for m in pattern.finditer(
            'afield-token followed by afield.Thing')])

    def test_javascript_bundle_filenames_are_not_package_references(self):
        recipe = dict(copy.deepcopy(RECIPE), packages={'afield': 'moved'}, symbols={}, splitPackages=[])
        pattern = affected_pattern(recipe)
        for text in ('afield.mjs', '`afield.mjs`', './afield.mjs', '/afield.mjs?v=2',
                     'afield.mjs.map', 'import { send } from "./afield.mjs";'):
            with self.subTest(text=text):
                self.assertIsNone(pattern.search(text))
                self.assertEqual(text, rewrite_qualified_text(text, recipe))
        for text in ('afield.mjsHelper', 'afield.mjs.Factory', 'afield.Server'):
            self.assertEqual(text, pattern.search(text).group())
            self.assertEqual(text.replace('afield', 'moved'), rewrite_qualified_text(text, recipe))

    def test_source_roots_and_paths_are_not_package_references(self):
        recipe = dict(copy.deepcopy(RECIPE), splitPackages=['old'])
        pattern = affected_pattern(recipe)
        paths = ['src/jvmMain/kotlin/old', 'src/commonMain/kotlin/old',
                 'src/jsMain/kotlin/old', 'src/jvmTest/kotlin/old',
                 'src/jvmMain/kotlin/old/pkg/Thing.kt', '/tmp/old.pkg',
                 r'src\jvmMain\kotlin\old', r'C:\src\old.pkg']
        for path in paths:
            with self.subTest(path=path):
                self.assertIsNone(pattern.search(f'`{path}`'))
                self.assertEqual(path, rewrite_qualified_text(path, recipe))
        text = 'See src/jvmMain/kotlin/old; run old.pkg.Thing then old.Unknown'
        self.assertEqual(['old.pkg.Thing', 'old.Unknown'], [m.group() for m in pattern.finditer(text)])
        self.assertEqual('old', pattern.search('package old').group())

    def test_prose_branding_is_exact_and_repeatable(self):
        recipe = dict(copy.deepcopy(RECIPE), proseReplacements={'afield.four': 'Field 4'})
        source = 'Use `afield.four`. Keep afield.four.* and afield.four.Renderer and src/afield.four/file.'
        after = rewrite_qualified_text(source, recipe)
        self.assertEqual('Use `Field 4`. Keep afield.four.* and afield.four.Renderer and src/afield.four/file.', after)
        self.assertEqual(after, rewrite_qualified_text(after, recipe))
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'recipe.json'; save_json(p, recipe)
            self.assertEqual(recipe['proseReplacements'], load_recipe(p)['proseReplacements'])
            recipe['proseReplacements'] = {'afield.four': 'afield.four again'}; save_json(p, recipe)
            with self.assertRaises(RefactorError): load_recipe(p)

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

    def test_unrelated_incomplete_snippet_does_not_hold_an_import_edit(self):
        class ParsingFixture(ImportFixture):
            def rewrite(self, code, script, recipe):
                if '...' in code:
                    return {'code': code, 'findings': [{'kind': 'parse-error', 'detail': 'Incomplete example'}]}
                return super().rewrite(code, script, recipe)

        for relevant in (False, True):
            with self.subTest(relevant=relevant):
                incomplete = ('import old.pkg.Other\n' if relevant else '') + 'val example = ...\n'
                self.write('notes.md', '```kotlin\n' + SOURCE + '```\n```kotlin\n' + incomplete + '```\n')
                out = self.base / ('relevant' if relevant else 'unrelated')
                plan = make_plan([(self.root, False)], copy.deepcopy(RECIPE), out, ParsingFixture())
                root = plan['roots'][0]
                self.assertEqual(1, len(root['changes']))
                self.assertEqual(relevant, root['changes'][0]['held'])
                diagnostic = next(f for f in root['findings'] if f['kind'] == 'parse-error')
                self.assertEqual(not relevant, bool(diagnostic.get('retained')))

    def test_parse_error_policy_never_releases_other_errors_or_changed_invalid_units(self):
        for kind, changed in [('rewrite-parse-error', False), ('parse-error', True), ('container-error', False)]:
            with self.subTest(kind=kind, changed=changed):
                class ErrorFixture(ImportFixture):
                    def rewrite(self, code, script, recipe):
                        return {'code': code + ('\n' if changed else ''),
                                'findings': [{'kind': kind, 'detail': 'Must still block'}]}
                self.write('doc/metadata.edn', metadata(code='val x = 1'))
                self.write('doc/broken_code_.kt', SOURCE)
                self.write('doc/AGENTS.md', 'Use `old.pkg.Thing`.\n')
                recipe = dict(copy.deepcopy(RECIPE), parseErrorPolicy='retain', workspaceMarkdownProse=['**/*.md'])
                out = self.base / (kind + str(changed))
                plan = make_plan([(self.root, False)], recipe, out, ErrorFixture())
                root = plan['roots'][0]
                self.assertTrue(root['changes'])
                self.assertTrue(all(c['held'] for c in root['changes']))
                self.assertTrue(any(f['kind'] == kind and not f.get('retained') for f in root['findings']))

    def test_parse_error_policy_validation(self):
        path = self.base / 'recipe.json'
        for policy in ['hold', 'retain', 'ignore-everything', True, None]:
            save_json(path, dict(copy.deepcopy(RECIPE), parseErrorPolicy=policy))
            if policy in ('hold', 'retain'):
                self.assertEqual(policy, load_recipe(path)['parseErrorPolicy'])
            else:
                with self.assertRaises(RefactorError):
                    load_recipe(path)

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
        scratch = self.write('.agent-scratch/session/metadata.edn', metadata())
        plan = self.make()
        self.assertTrue(any(f['kind'] == 'excluded-directory' and f['detail'] == '.agent-scratch'
                            and f['retained'] for f in plan['roots'][0]['findings']))
        destination = self.base / 'migrated'
        apply_plan(self.plan, copy_to=destination)
        self.assertEqual(SOURCE, source.read_text())
        self.assertIn('new.pkg', (destination / 'a.kt').read_text())
        self.assertFalse((destination / 'backups').exists())
        self.assertFalse((destination / 'thumbnails').exists())
        self.assertFalse((destination / '.agent-scratch').exists())
        self.assertEqual(metadata(), scratch.read_text())
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
    def test_reviewed_import_exception_releases_only_unchanged_exact_unit(self):
        from tools import refactor_pass as passes
        code = 'import old.split.mistake\n'
        yaml = '- execute:\n    property: code\n    code: |\n      import old.split.mistake\n'
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp); root = base / 'workspace'; doc = root / 'doc'; doc.mkdir(parents=True)
            (doc / 'metadata.edn').write_text(metadata(code='val n = 1'))
            (doc / 'operations.yaml').write_text(yaml)
            (doc / 'AGENTS.md').write_text('Use `old.pkg.Thing`.\n')
            recipe = dict(copy.deepcopy(RECIPE), splitPackages=['old.split'], workspaceMarkdownProse=['**/*.md'])
            strict = make_plan([(root, False)], recipe, base / 'strict', engine)
            self.assertTrue(strict['roots'][0]['changes'][0]['held'])
            finding = next(f for f in strict['roots'][0]['findings'] if f['kind'] == 'unmapped-import')
            rule = {k: finding[k] for k in ('path', 'unit', 'kind', 'detail')}
            rule.update(codeSha256=digest(code.encode()), reason='Recorded failed experiment; retain history')
            recipe['retainedFindings'] = [rule]
            save_json(base / 'recipe.json', recipe)
            recipe = load_recipe(base / 'recipe.json')
            plan = make_plan([(root, False)], recipe, base / 'accepted', engine)
            self.assertFalse(plan['roots'][0]['changes'][0]['held'])
            self.assertEqual([], passes.build_index(base, base / 'accepted'))
            apply_plan(base / 'accepted', offline=True); verify(base / 'accepted', '0')
            self.assertEqual(yaml, (doc / 'operations.yaml').read_text())
            self.assertEqual('Use `new.pkg.Thing`.\n', (doc / 'AGENTS.md').read_text())
            again = make_plan([(root, False)], recipe, base / 'again', engine)
            self.assertEqual([], passes.build_index(base, base / 'again'))
            undo(base / 'accepted', '0')
            for label, content in [('changed', yaml + '      val n = 2\n'), ('additional', yaml + yaml)]:
                (doc / 'operations.yaml').write_text(content)
                changed = make_plan([(root, False)], recipe, base / label, engine)
                self.assertTrue(changed['roots'][0]['changes'][0]['held'])
            for key, value in [('kind', 'roundtrip-error'), ('path', '**/operations.yaml'), ('codeSha256', '')]:
                bad = copy.deepcopy(recipe); bad['retainedFindings'][0][key] = value
                save_json(base / 'recipe.json', bad)
                with self.assertRaises(RefactorError):
                    load_recipe(base / 'recipe.json')

    def test_dependency_edn_and_custom_transcript_properties_migrate_and_roundtrip(self):
        from tools import refactor_pass as passes
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp); root = base / 'workspace'; root.mkdir()
            deps = '{"version" 1 "edges" [{"name" "old.pkg.Thing" "mechanism" "FROM_CONTEXT" "count" 3 "reader" "r" "supplier" "s"}]}'
            yaml = '- edit:\n    property: cameraCheck\n    from: ""\n    to: |\n      import old.pkg.Thing\n      val x = Thing()\n'
            (root / 'dependencies.edn').write_text(deps)
            (root / 'operations.yaml').write_text(yaml)
            recipe = dict(copy.deepcopy(RECIPE), kotlinProperties=['cameraCheck'])
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            self.assertEqual(2, len(plan['roots'][0]['changes']))
            self.assertFalse(any(c['held'] for c in plan['roots'][0]['changes']))
            self.assertEqual([], passes.build_index(base, base / 'plan'))
            apply_plan(base / 'plan', offline=True); verify(base / 'plan', '0')
            self.assertEqual(deps.replace('old.pkg.Thing', 'new.pkg.Thing'), (root / 'dependencies.edn').read_text())
            self.assertEqual(yaml.replace('old.pkg.Thing', 'new.pkg.Thing'), (root / 'operations.yaml').read_text())
            again = make_plan([(root, False)], recipe, base / 'again', engine)
            self.assertEqual([], again['roots'][0]['changes'])
            undo(base / 'plan', '0')
            self.assertEqual(deps, (root / 'dependencies.edn').read_text())
            self.assertEqual(yaml, (root / 'operations.yaml').read_text())

    def test_retained_parse_error_releases_independent_document_edit(self):
        from tools import refactor_pass as passes
        broken = 'import old.pkg.Thing\nfun incomplete() {\n'
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp); root = base / 'workspace'; doc = root / 'doc'; doc.mkdir(parents=True)
            (doc / 'metadata.edn').write_text(metadata(code=broken, sidecar='broken_code_.kt'))
            (doc / 'broken_code_.kt').write_text(broken)
            (doc / 'AGENTS.md').write_text('Use `old.pkg.Thing`.\n')
            recipe = dict(copy.deepcopy(RECIPE), workspaceMarkdownProse=['**/*.md'])
            strict = make_plan([(root, False)], recipe, base / 'strict', engine)
            self.assertTrue(strict['roots'][0]['changes'][0]['held'])
            recipe['parseErrorPolicy'] = 'retain'
            plan = make_plan([(root, False)], recipe, base / 'retain', engine)
            spec = plan['roots'][0]
            self.assertEqual(['doc/AGENTS.md'], [c['path'] for c in spec['changes']])
            self.assertFalse(spec['changes'][0]['held'])
            errors = [f for f in spec['findings'] if f['kind'] == 'parse-error']
            self.assertTrue(errors)
            self.assertTrue(all(f.get('retained') and f.get('retentionReason') for f in errors))
            self.assertEqual([], passes.build_index(base, base / 'retain'))
            self.assertIn('Retained parse errors (non-blocking', (base / 'retain/report.md').read_text())
            apply_plan(base / 'retain', offline=True)
            verify(base / 'retain', '0')
            self.assertEqual('Use `new.pkg.Thing`.\n', (doc / 'AGENTS.md').read_text())
            self.assertEqual(broken, (doc / 'broken_code_.kt').read_text())

    def test_exported_preamble_uses_box_split_import_rules_and_roundtrips(self):
        source = ('```text [field-property box="b" name="preamble"]\n'
                  'import old.split.*\nval n = 1\n```\n')
        recipe = dict(copy.deepcopy(RECIPE), splitPackages=['old.split'],
                      splitStarImports={'old.split': ['new.first', 'new.second']},
                      boxImports=['new.first.*', 'new.second.*'],
                      boxSplitStarImports={'old.split': ['new.first', 'new.second']})
        with tempfile.TemporaryDirectory() as tmp, KotlinEngine(ENGINE) as engine:
            base = Path(tmp); root = base / 'workspace'; root.mkdir()
            path = root / 'document.md'; path.write_text(source)
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            self.assertFalse(plan['roots'][0]['changes'][0]['held'])
            apply_plan(base / 'plan', offline=True)
            self.assertNotIn('import old.split', path.read_text())
            self.assertNotIn('import new.', path.read_text())
            self.assertIn('```text [field-property box="b" name="preamble"]', path.read_text())
            verify(base / 'plan'); undo(base / 'plan')
            self.assertEqual(source, path.read_text())

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

    def test_exported_edn_documents_receive_the_box_intro_and_keep_sidecars_in_sync(self):
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
            (root / 'doc/export.edn').write_text(metadata(source, 'code.kt'))
            (root / 'doc/code.kt').write_text(source)
            (root / 'inline').mkdir()
            (root / 'inline/starter.edn').write_text(metadata(source))
            (root / 'standalone.kts').write_text(source)
            (root / 'unregistered.kt').write_text(source)
            (root / 'docs.md').write_text('```kotlin\n' + source + '```\n')
            (root / 'src').mkdir()
            (root / 'src/Example.kt').write_text(source)
            plan = make_plan([(root, False)], recipe, base / 'plan', engine)
            changes = plan['roots'][0]['changes']
            self.assertEqual({'doc/code.kt', 'doc/export.edn', 'inline/starter.edn'}, {c['path'] for c in changes})
            self.assertTrue(all(not c['held'] for c in changes))
            self.assertEqual({'standalone.kts', 'unregistered.kt', 'docs.md', 'src/Example.kt'},
                             {f['path'] for f in plan['roots'][0]['findings'] if f['kind'] == 'split-star'})
            apply_plan(base / 'plan', offline=True, allow_partial=True)
            self.assertEqual(' // supplied by intro\nval x = Thing()\n', (root / 'doc/code.kt').read_text())
            self.assertEqual(edn((root / 'doc/export.edn').read_text(), PROPS).units[0].code,
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
