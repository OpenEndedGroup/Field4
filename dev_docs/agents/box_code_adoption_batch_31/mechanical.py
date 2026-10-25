"""Format only the Kotlin overlay bodies; preserve neighboring Python byte-for-byte."""
from pathlib import Path
import importlib.util,json,subprocess,yaml
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-31'
s=importlib.util.spec_from_file_location('lit31',HERE.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
rows=json.loads((HERE/'changes.json').read_text());jobs=[];shaders=[]
for row in rows:
 p=ROOT/row['path'];text=p.read_text()
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   vals={k.value:v for k,v in n.value}
   if 'code' in vals and isinstance(vals['code'],yaml.ScalarNode) and 'm.resize(0, 0)' in vals['code'].value:
    selected={k.value:v.value for k,v in n.value if k.value in ['vertex','fragment']}
    if selected:
     temp=OUT/f'shaders-{len(shaders)}.yaml';temp.write_text(yaml.safe_dump(selected,sort_keys=False))
     for k,v in n.value:
      if k.value in selected:shaders.append((p,k,v,temp))
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'm.resize(0, 0)' in v.value:
     code,count=lit.convert(v.value);temp=OUT/f'overlay-{len(jobs)}_code_.kt';temp.write_text(code);jobs.append((p,text,k,v,temp,count))
    else:visit(v)
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:visit(v)
 visit(yaml.compose(text))
assert len(jobs)==3
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join([str(j[4]) for j in jobs]+list(dict.fromkeys(str(j[3]) for j in shaders)))],cwd=ROOT,check=True)
for row in rows:
 p=ROOT/row['path'];text=p.read_text();edits=[]
 for source,_,k,v,temp,count in jobs:
  if source!=p:continue
  ind=k.start_mark.column+2;code=temp.read_text();value='|\n'+''.join(' '*ind+line+'\n' if line else '\n' for line in code.rstrip('\n').splitlines())
  edits.append((v.start_mark.index,v.end_mark.index,value))
 for source,k,v,temp in shaders:
  if source!=p:continue
  code=yaml.safe_load(temp.read_text())[k.value];ind=k.start_mark.column+2
  value='|\n'+''.join(' '*ind+line+'\n' if line else '\n' for line in code.rstrip('\n').splitlines())
  edits.append((v.start_mark.index,v.end_mark.index,value))
 for a,b,value in sorted(edits,reverse=True):text=text[:a]+value+text[b:]
 p.write_text(text)
(OUT/'format-counts.json').write_text(json.dumps({'overlayOccurrences':len(jobs),'shaderProperties':len(shaders),'collectionConversions':sum(j[5] for j in jobs)},indent=2)+'\n')
