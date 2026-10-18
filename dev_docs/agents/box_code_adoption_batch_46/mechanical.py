"""Use parsed YAML code properties and complete Markdown states for literal conversion."""
from pathlib import Path
import importlib.util,json,yaml,subprocess,re
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-46'
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit14',HERE.parent/'box_code_adoption_batch_6/literals.py');docs=load('docs14',HERE.parent/'box_code_adoption_batch_7/format_docs.py')
rows=json.loads((HERE/'changes.json').read_text());counts=[]
for row in rows:
 p=ROOT/row['path'];text=p.read_text();edits=[];count=0
 if p.suffix=='.yaml':
  def visit(node):
   global count
   if isinstance(node,yaml.MappingNode):
    for k,v in node.value:
     if k.value=='code' and isinstance(v,yaml.ScalarNode):
      code,n=lit.convert(v.value);count+=n
      if n:
       indent=k.start_mark.column+2
       value=('|\n' if code.endswith('\n') else '|-\n')+''.join(' '*indent+line+'\n' if line else '\n' for line in code.rstrip('\n').splitlines())
       edits.append((v.start_mark.index,v.end_mark.index,value.rstrip('\n')+('\n' if v.style in ['|','>'] else '')))
     else:visit(v)
   elif isinstance(node,yaml.SequenceNode):
    for v in node.value:visit(v)
  visit(yaml.compose(text))
 elif p.suffix=='.kt':
  code,n=lit.convert(text);count+=n;edits.append((0,len(text),code))
 else:
  for a,b in docs.sections(text):
   code,n=lit.convert(text[a:b]);count+=n
   if n:edits.append((a,b,code))
 for a,b,code in sorted(edits,reverse=True):text=text[:a]+code+text[b:]
 p.write_text(text);counts.append({'path':row['path'],'additionalConversions':count})
(OUT/'literal-conversions.json').write_text(json.dumps(counts,indent=2)+'\n')
sidecars=[]
for row in rows:
 if row['path'].endswith('.kt'):
  temp=OUT/(str(len(sidecars))+'_'+Path(row['path']).stem+'_code_.kt');temp.write_text((ROOT/row['path']).read_text());sidecars.append((row['path'],temp))
targets=[r['path'] for r in rows if r['path'].endswith('.yaml')]+[str(t) for _,t in sidecars]
if targets:subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(targets)],cwd=ROOT,check=True)
for path,temp in sidecars:(ROOT/path).write_text(temp.read_text())
docs.OUT=OUT/'format-docs';docs.HERE=docs.OUT/'input';docs.HERE.mkdir(parents=True,exist_ok=True)
(docs.HERE/'comment_changes.json').write_text(json.dumps([{'path':r['path']} for r in rows if r['path'].endswith('.md')]))
if any(r['path'].endswith('.md') for r in rows):docs.main()

# Preserve untrimmed raw-string contents after ktfmt, including interpolation layout.
for row in rows:
 p=ROOT/row['path']
 if p.suffix!='.md':continue
 actual=p.read_text();expected=row['after'];edits=[]
 for (a,b),(c,e) in zip(docs.sections(expected),docs.sections(actual)):
  x=expected[a:b];y=actual[c:e]
  aa=[v for v in lit.TOKEN.finditer(x) if v[0].startswith('"""')]
  bb=[v for v in lit.TOKEN.finditer(y) if v[0].startswith('"""')]
  assert len(aa)==len(bb)
  for u,v in zip(aa,bb):
   if not re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',x[u.end():]) and u[0]!=v[0]:edits.append((c+v.start(),c+v.end(),u[0]))
 for a,b,code in sorted(edits,reverse=True):actual=actual[:a]+code+actual[b:]
 p.write_text(actual)

shaders=[]
for row in rows:
 if row['path'].endswith('.glsl'):
  p=ROOT/row['path'];temp=OUT/f"shader-{len(shaders)}.yaml"
  temp.write_text('fragment: |\n'+''.join('    '+line+'\n' for line in p.read_text().splitlines()))
  shaders.append((p,temp))
if shaders:
 subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args='+' '.join(str(t) for _,t in shaders)],cwd=ROOT,check=True)
 for p,t in shaders:p.write_text(yaml.safe_load(t.read_text())['fragment'])
