"""Real PSI inventory and family-map validation, including stale snapshots and collisions."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from tools.refactoring.core import KotlinEngine, RefactorError, affected_pattern, save_json, load_recipe, make_plan
from tools.refactoring.symbols import inventory, generate

ENGINE = Path(__file__).parent / 'refactor-engine/build/install/field-refactor-engine/bin/field-refactor-engine'


@unittest.skipUnless(ENGINE.exists(), 'Build standalone refactor engine first')
class SymbolMapTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.repo = self.root / 'repo'; self.repo.mkdir()
        subprocess.run(['git','init','-q',str(self.repo)],check=True)
        self.a = 'src/jvmMain/kotlin/mixed/A.kt'; self.b = 'src/commonMain/kotlin/mixed/B.kt'
        for path, source in [(self.a,'package mixed\nclass Color\nfun value(x: Int) = x\nprivate val hidden = 1'),
                             (self.b,'package mixed\nfun value(x: Double) = x\nclass Pending')]:
            p=self.repo/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
        subprocess.run(['git','-C',str(self.repo),'add','.'],check=True)
        subprocess.run(['git','-C',str(self.repo),'-c','user.name=Test','-c','user.email=t@example.test','commit','-qm','fixture'],check=True)
        with KotlinEngine(ENGINE.resolve()) as engine:
            self.index=inventory(self.repo,engine,self.root/'index')
        self.recipe=self.root/'recipe.json'
        save_json(self.recipe,{'schema':1,'id':'split','packages':{},'symbols':{},'splitPackages':['mixed'],'scriptImports':[]})

    def run_map(self, assignments):
        return generate(self.index,dict(schema=1,**assignments),self.recipe,self.repo,self.root/'out')

    def test_expand_file_overloads_and_keep_pending(self):
        result=self.run_map({'files':{self.a:'domain.color'},'keep':['mixed.Pending']})
        self.assertEqual(2,result['mappedFamilies']);self.assertEqual(0,result['unassignedFamilies'])
        recipe=json.loads((self.root/'out/recipe.json').read_text())
        self.assertEqual({'mixed.Color':'domain.color.Color','mixed.value':'domain.color.value'},recipe['symbols'])
        rows=json.loads((self.root/'out/symbol-map.json').read_text())['declarations']
        self.assertEqual(2,sum(d['fqn']=='mixed.value' for d in rows))
        self.assertNotIn('mixed.hidden',recipe['symbols'])
        self.assertEqual({'mixed':['domain.color','mixed']},recipe['splitStarImports'])
        with KotlinEngine(ENGINE.resolve()) as engine:
            result=engine.rewrite('import mixed.*\nval x = Color()', True, recipe)
        self.assertEqual('import domain.color.*\nimport mixed.*\nval x = Color()',result['code'])
        self.assertFalse(result['findings'])

    def test_explicit_declaration_split_records_owners_and_holds_only_ambiguous_uses(self):
        declared={d['id']: ('one' if d['path']==self.a else 'two')+'.'+d['name']
                  for d in self.index['declarations'] if d['fqn']=='mixed.value'}
        result=self.run_map({'declarations':declared,'symbols':{'mixed.Color':'one.Color','mixed.Pending':'two.Pending'}})
        self.assertEqual(0,result['unassignedFamilies']);self.assertEqual(1,result['splitFamilies'])
        recipe=load_recipe(self.root/'out/recipe.json')
        self.assertEqual({'mixed.value':['one.value','two.value']},recipe['splitSymbols'])
        self.assertEqual({'mixed':['one','two']},recipe['splitStarImports'])
        rows=json.loads((self.root/'out/symbol-map.json').read_text())['declarations']
        self.assertEqual({'one.value','two.value'},{d['destination'] for d in rows if d['fqn']=='mixed.value'})
        with KotlinEngine(ENGINE.resolve()) as engine:
            star=engine.rewrite('import mixed.*\nval x = value(1)', True, recipe)
            self.assertIn('import one.*\nimport two.*',star['code']);self.assertFalse(star['findings'])
            source='import mixed.value as v\nval x = mixed.value(1)'
            held=engine.rewrite(source,True,recipe)
            self.assertEqual(source,held['code'])
            self.assertTrue(all(f['kind']=='split-symbol' for f in held['findings']))
            self.assertTrue(all('one.value' in f['detail'] and 'two.value' in f['detail'] for f in held['findings']))

    def test_partial_and_unknown_declaration_assignments_are_rejected(self):
        d=next(d for d in self.index['declarations'] if d['fqn']=='mixed.value')
        with self.assertRaisesRegex(RefactorError,'every declaration'):
            self.run_map({'declarations':{d['id']:'one.value'}})
        with self.assertRaisesRegex(RefactorError,'Unknown declaration'):
            self.run_map({'declarations':{'stale-id':'one.value'}})

    def test_old_property_names_can_overlap_new_namespaces_without_rewriting_destinations(self):
        from tools.refactoring.core import rewrite_qualified_text
        recipe={'schema':1,'id':'namespace-overlap','packages':{},
                'symbols':{'afield.app':'afield.app.server.app','afield.Old':'afield.app.protocol.Old',
                           'afield.graphics.three':'afield.graphics.three.three','afield.value':'afield.renamed'},
                'splitPackages':['afield','afield.graphics'],
                'knownPackages':['afield.app.server','afield.app.protocol','afield.graphics.three']}
        save_json(self.recipe,recipe);load_recipe(self.recipe)
        source='import afield.app\nimport afield.graphics.three\nimport afield.graphics.three.*\nval a = afield.app\nval b = afield.graphics.three()\nval c = afield.app.protocol.Old()\nval d = afield.value'
        with KotlinEngine(ENGINE.resolve()) as engine:
            result=engine.rewrite(source,True,recipe)
            self.assertIn('import afield.app.server.app',result['code'])
            self.assertIn('import afield.graphics.three.three',result['code'])
            self.assertIn('import afield.graphics.three.*',result['code'])
            self.assertIn('val c = afield.app.protocol.Old()',result['code'])
            self.assertIn('val d = afield.renamed',result['code'])
            self.assertFalse(result['findings'])
            self.assertEqual(result['code'],engine.rewrite(result['code'],True,recipe)['code'])
        self.assertEqual('afield.app.protocol.Old',rewrite_qualified_text('afield.app.protocol.Old',recipe))
        self.assertEqual('afield.graphics.three',rewrite_qualified_text('afield.graphics.three',recipe))
        self.assertEqual('afield.app.server.app',rewrite_qualified_text('afield.app',recipe))

    def test_conflicting_overloads_refused(self):
        with self.assertRaisesRegex(RefactorError,'Conflicting'):
            self.run_map({'files':{self.a:'one',self.b:'two'}})
        self.assertFalse((self.root/'out').exists())

    def test_destination_collision_refused(self):
        with self.assertRaisesRegex(RefactorError,'collisions'):
            self.run_map({'symbols':{'mixed.Color':'target.Same','mixed.Pending':'target.Same'}})

    def test_declaration_ids_survive_line_shifts_but_source_hashes_still_require_refresh(self):
        before={d['signature']:d['id'] for d in self.index['declarations'] if d['path']==self.a}
        source=self.repo/self.a;source.write_text('\n\n'+source.read_text())
        with self.assertRaisesRegex(RefactorError,'stale'):
            self.run_map({})
        with KotlinEngine(ENGINE.resolve()) as engine:
            refreshed=inventory(self.repo,engine,self.root/'fresh-index')
        after={d['signature']:d['id'] for d in refreshed['declarations'] if d['path']==self.a}
        self.assertEqual(before,after)

    def test_source_change_refused(self):
        (self.repo/self.a).write_text('package mixed\nclass Changed')
        with self.assertRaisesRegex(RefactorError,'stale'):
            self.run_map({})

    def test_unknown_assignment_refused(self):
        with self.assertRaisesRegex(RefactorError,'Unknown symbol'):
            self.run_map({'symbols':{'mixed.Typo':'target.Typo'}})

    def test_pending_is_explicit(self):
        result=self.run_map({'files':{self.b:None},'symbols':{'mixed.Color':'target.Color'}})
        self.assertEqual(2,result['unassignedFamilies'])
        template=json.loads((self.root/'out/assignments.template.json').read_text())
        self.assertIsNone(template['files'][self.b])
        recipe=json.loads((self.root/'out/recipe.json').read_text())
        self.assertEqual({},recipe['splitStarImports'])

    def test_edn_split_stars_rewrite_inline_and_sidecar_together(self):
        from tools.test_refactor import metadata
        self.run_map({'files':{self.a:'domain.color'},'keep':['mixed.Pending']})
        recipe=load_recipe(self.root/'out/recipe.json')
        workspace=self.root/'workspace';workspace.mkdir()
        source='import mixed.*\nval x = Color()'
        (workspace/'metadata.edn').write_text(metadata(source,'box_code_.kt'))
        (workspace/'box_code_.kt').write_text(source)
        with KotlinEngine(ENGINE.resolve()) as engine:
            make_plan([(workspace,False)],recipe,self.root/'plan',engine)
        plan=json.loads((self.root/'plan/plan.json').read_text())
        changes=plan['roots'][0]['changes']
        self.assertEqual({'metadata.edn','box_code_.kt'},{c['path'] for c in changes})
        self.assertTrue(all(not c['held'] for c in changes))
        for change in changes:
            after=(self.root/'plan/blobs'/change['after']).read_text()
            self.assertIn('import domain.color.*',after)
            self.assertIn('import mixed.*',after)

    def test_inventory_errors_prevent_star_completeness(self):
        self.index['errors'].append({'path':self.b,'detail':'incomplete declaration'})
        self.run_map({'files':{self.a:'domain.color'},'keep':['mixed.Pending']})
        recipe=json.loads((self.root/'out/recipe.json').read_text())
        self.assertEqual({},recipe['splitStarImports'])

    def test_renamed_symbols_prevent_star_expansion(self):
        self.run_map({'symbols':{'mixed.Color':'domain.Hue','mixed.value':'domain.value','mixed.Pending':'domain.Pending'}})
        recipe=json.loads((self.root/'out/recipe.json').read_text())
        self.assertEqual({},recipe['splitStarImports'])

    def test_invalid_manual_star_map_is_rejected(self):
        recipe=json.loads(self.recipe.read_text())
        recipe['symbols']={'mixed.Color':'domain.Color'}
        for stars in [{'other':['domain']}, {'mixed':[]}, {'mixed':['wrong']}, {'mixed':['domain','domain']}]:
            recipe['splitStarImports']=stars
            save_json(self.recipe,recipe)
            with self.subTest(stars=stars),self.assertRaises(RefactorError):
                load_recipe(self.recipe)


class OwnershipTests(unittest.TestCase):
    def test_split_parent_does_not_flag_known_child_or_new_destination(self):
        recipe={'packages':{},'symbols':{'afield.Color4':'afield.graphics.color.Color4'},
                'splitPackages':['afield'], 'knownPackages':['afield','afield.math.gp','afield.graphics.color'],
                'retainedSymbols':['afield.Stays']}
        pattern=affected_pattern(recipe)
        for source in ['afield.math.gp.GaussianProcess','afield.graphics.color.Color4','afield.Stays.Nested']:
            self.assertIsNone(pattern.search(source),source)
        for source in ['afield.Color4','afield.Unknown','afield.*']:
            self.assertIsNotNone(pattern.search(source),source)
