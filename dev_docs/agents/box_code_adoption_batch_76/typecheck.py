"""Typecheck complete authored view bodies without entering unavailable hardware paths."""
from pathlib import Path
import asyncio,importlib.util,json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-76'
s=importlib.util.spec_from_file_location('r76t',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9724;m.OUT=O/'typecheck';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 cases=[];byname={}
 for row in json.loads((H/'changes.json').read_text()):
  p=R/row['path']
  if p.suffix!='.yaml':continue
  doc=yaml.safe_load(p.read_text())
  for op in doc['operations']:
   props=op.get('create',{}).get('properties',{});code=props.get('code','')
   if 'val runScope =' not in code:continue
   cases.append((str(p.relative_to(R)),code,props));byname[p.stem]=props
 for row in json.loads((H/'changes.json').read_text()):
  p=R/row['path']
  if p.suffix!='.kt' or row['path'].startswith('src/'):continue
  code=p.read_text()
  if 'val runScope =' not in code:continue
  props=byname.get(p.parent.name,{})
  cases.append((str(p.relative_to(R)),code,props))
 unique={}
 for path,code,props in cases:unique.setdefault(code,{'paths':[],'properties':props})['paths'].append(path)
 records=[]
 for index,(code,record) in enumerate(unique.items()):
  imports='\n'.join(x for x in code.splitlines() if x.startswith('import '));body='\n'.join(x for x in code.splitlines() if not x.startswith('import '))
  body=imports+'\nif(false) {\n'+body+'\n}\nprintln("VERIFIED76 complete authored body typechecked")'
  uid='g3-typecheck-'+str(index);props={**record['properties'],'code':body}
  result=await m.macro(uid,[{'create':{'box':uid,'properties':props}},{'connect':{'parent':'renderer','child':uid}},{'launch':{'box':uid}},{'await':{}}])
  assert any('VERIFIED76' in out.get('html','') for r in result['results'] for out in r.get('data',{}).get('output',[])),record['paths']
  records.append({'paths':record['paths'],'completeBodyTypechecked':True})
 (H/'typecheck.json').write_text(json.dumps({'bodies':records,'limits':'Complete authored code is compiled in an unentered branch with its actual shader properties. Actual positive Source/portable backend behavior is recorded separately. No unavailable NVIDIA/cluster/NR/training execution is claimed.'},indent=2)+'\n')
asyncio.run(main())
