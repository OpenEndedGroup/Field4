"""Audit only owned Kotlin overlays: the CLI corpus excludes python/fieldcv/examples."""
from pathlib import Path
import importlib.util,json,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('idioms31',R/'tools/check_box_idioms.py');t=importlib.util.module_from_spec(s);sys.modules[s.name]=t;s.loader.exec_module(t)
records=[]
for row in json.loads((H/'changes.json').read_text()):
 def visit(n):
  if isinstance(n,dict):
   for k,v in n.items():
    if k=='code' and isinstance(v,str) and 'm.resize(0, 0)' in v:
     lines=v.splitlines(keepends=True);hits=t.check_block(lines,0,len(lines),literals=True)
     records.append({'path':row['path'],'findings':hits});assert not hits,hits
    else:visit(v)
  elif isinstance(n,list):
   for v in n:visit(v)
 visit(yaml.safe_load((R/row['path']).read_text()))
assert len(records)==3
(H/'idiom-verification.json').write_text(json.dumps(records,indent=2)+'\n');print('three Kotlin overlay occurrences pass actual idiom/collection checker')
