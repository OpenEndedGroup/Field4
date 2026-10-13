"""Real PSI inventory and family-map validation, including stale snapshots and collisions."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from tools.refactoring.core import KotlinEngine, RefactorError, affected_pattern, save_json
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
        save_json(self.recipe,{'schema':1,'id':'split','packages':{},'symbols':{},'splitPackages':['mixed']})

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

    def test_conflicting_overloads_refused(self):
        with self.assertRaisesRegex(RefactorError,'Conflicting'):
            self.run_map({'files':{self.a:'one',self.b:'two'}})
        self.assertFalse((self.root/'out').exists())

    def test_destination_collision_refused(self):
        with self.assertRaisesRegex(RefactorError,'collisions'):
            self.run_map({'symbols':{'mixed.Color':'target.Same','mixed.Pending':'target.Same'}})

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
