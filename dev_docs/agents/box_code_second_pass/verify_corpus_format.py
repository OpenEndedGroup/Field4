"""Reparse all edited containers and compare every old/new teaching program."""
from pathlib import Path
from collections import defaultdict,Counter
import importlib.util,json,re,subprocess,sys
R=Path(__file__).resolve().parents[3];OUT=R/'build/box-code-revision/mechanical-final';sys.path.insert(0,str(R))
from tools.inventory_box_code import Inventory
spec=importlib.util.spec_from_file_location('strict_format_tokens',R/'dev_docs/agents/box_code_first_pass/mechanical.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
changes=json.loads((OUT/'changes.json').read_text())+json.loads((OUT/'metadata-changes.json').read_text())+json.loads((OUT/'mirror-changes.json').read_text());old={}
for change in changes:old.setdefault(str((R/change['path']).resolve()),change['before'])
paths={x['path'] for x in changes}
tracked=list(filter(None,subprocess.check_output(['git','ls-files','-z'],cwd=R).decode().split('\0')))
class Capture(Inventory):
 def __init__(self):super().__init__(R,tracked);self.codes={}
 def add(self,path,locator,code,*args,**kwargs):
  n=len(self.rows);super().add(path,locator,code,*args,**kwargs)
  if len(self.rows)>n:self.codes[self.rows[-1]['id']]=code
read_text=Path.read_text
def previous(self,*args,**kwargs):
 name=str(self.resolve())
 return old[name] if name in old else read_text(self,*args,**kwargs)
try:
 Path.read_text=previous;before=Capture();before.run()
finally:Path.read_text=read_text
after=Capture();after.run()
def grouped(inv):
 groups=defaultdict(list)
 for row in inv.rows:
  if row['path'] in paths:groups[row['path']].append(row)
 return groups
left=grouped(before);right=grouped(after);records=[];counts=Counter()
for path in sorted(paths):
 order=lambda x:(x['box'],x['property'],x['language'],x['role'],x['line'])
 a=sorted(left[path],key=order);b=sorted(right[path],key=order);assert len(a)==len(b),(path,'occurrence count',len(a),len(b))
 for x,y in zip(a,b):
  for key in ['box','property','language','role']:assert x[key]==y[key],(path,key,x[key],y[key])
  oldCode=before.codes[x['id']];newCode=after.codes[y['id']];lang=x['language']
  if oldCode==newCode:counts[lang]+=1;continue
  oldCode=re.sub(r'(?m)^[ \t]*:::.*$','',oldCode);newCode=re.sub(r'(?m)^[ \t]*:::.*$','',newCode)
  if lang=='kotlin':
   aa=[t for t in m.tokens(oldCode,lang) if t not in {',',';'}];bb=[t for t in m.tokens(newCode,lang) if t not in {',',';'}]
   assert aa==bb,(path,x['locator'],'Kotlin executable tokens')
   assert m.markers(oldCode)==m.markers(newCode),(path,x['locator'],'execution boundaries')
  elif lang=='glsl':assert m.tokens(oldCode,lang)==m.tokens(newCode,lang),(path,x['locator'],'GLSL tokens')
  else:assert oldCode==newCode,(path,x['locator'],'foreign language changed')
  counts[lang]+=1
 records.append({'path':path,'parsedPrograms':len(a),'allProgramsPreserved':True})
summary={'files':len(paths),'parsedPrograms':sum(counts.values()),'languages':dict(counts),'rawStringsAndFragmentMarkersPreserved':True,'sources':records}
(OUT/'verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary|{'sources':len(records)}))
