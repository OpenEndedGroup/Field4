"""Prepare token-aware literals in frozen, current authored Kotlin; never edit recorded provenance."""
from pathlib import Path
import importlib.util,json,sys,re,subprocess,hashlib
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-79';sys.path.insert(0,str(R))
from tools.inventory_box_code import Inventory,digest

def load(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit79',H.parent/'box_code_adoption_batch_6/literals.py')
groups={x['group'] for x in json.loads((H.parent/'box_code_first_pass/scope.json').read_text())['groups']}
texts={}
class Capture(Inventory):
 def add(self,path,locator,code,*args,**kwargs):
  old=len(self.rows);super().add(path,locator,code,*args,**kwargs)
  if len(self.rows)>old:texts[self.rows[-1]['id']]=code
inv=Capture(R,filter(None,subprocess.check_output(['git','ls-files','-z'],cwd=R).decode().split('\0')));inv.run()
bodies={};retained=[]
for row in inv.rows:
 if row['group'] not in groups or row['role'] not in {'source','docs-fence'} or row['language']!='kotlin':continue
 code=texts[row['id']]
 if 'listOf' not in code:continue
 if 'function_conversation' in row['path'] or '/stackauto_conversation/' in row['path'] or row['property']=='preamble':
  retained.append({'id':row['id'],'reason':'recorded conversation provenance or compiler preamble'});continue
 markers=list(re.finditer(r'^:::(?:static|before|after)[ \t]*\n',code,re.M))
 ranges=[(m.end(),markers[i+1].start() if i+1<len(markers) else len(code)) for i,m in enumerate(markers)] if markers else [(0,len(code))]
 for index,(a,b) in enumerate(ranges):
  original=code[a:b];folds=[]
  def carry(m):folds.append(m[0]);return f'/* BOX_CODE_FOLD_{len(folds)-1} */'
  prepared=re.sub(r'^[ \t]*:::(?:fold[^\n]*|endfold|)[ \t]*$',carry,original,flags=re.M)
  try:converted,count=lit.convert(prepared)
  except ValueError:retained.append({'id':row['id'],'reason':'schematic/unbalanced code'});continue
  if not count:continue
  sha=digest(prepared);key='kotlin-'+sha
  body=bodies.setdefault(key,{'language':'kotlin','sha256':sha,'code':prepared,'candidate':converted,'conversions':count,'occurrences':[]})
  occurrence={k:row[k] for k in ['id','path','property','group','line','locator','source_sha256']};occurrence.update(state=index,start=a,end=b,folds=folds,original_sha256=digest(original));body['occurrences'].append(occurrence)
(O/'mechanical/inputs').mkdir(parents=True,exist_ok=True)
for key,body in bodies.items():(O/'mechanical/inputs'/(key+'_code_.kt')).write_text(body['candidate'])
(O/'bodies.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in bodies.values()))
(O/'scope.json').write_text(json.dumps({'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),'uniqueBodies':len(bodies),'occurrences':sum(len(x['occurrences']) for x in bodies.values()),'retained':retained},indent=2)+'\n')
print(len(bodies),'distinct bodies;',sum(x['conversions'] for x in bodies.values()),'list calls;',sum(len(x['occurrences']) for x in bodies.values()),'occurrences')
