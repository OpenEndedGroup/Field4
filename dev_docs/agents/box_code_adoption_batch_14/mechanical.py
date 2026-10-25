"""Use parsed YAML code properties and complete Markdown states for literal conversion."""
from pathlib import Path
import importlib.util,json,yaml,subprocess
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-14'
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
 else:
  for a,b in docs.sections(text):
   code,n=lit.convert(text[a:b]);count+=n
   if n:edits.append((a,b,code))
 for a,b,code in sorted(edits,reverse=True):text=text[:a]+code+text[b:]
 p.write_text(text);counts.append({'path':row['path'],'additionalConversions':count})
(OUT/'literal-conversions.json').write_text(json.dumps(counts,indent=2)+'\n')
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(r['path'] for r in rows if r['path'].endswith('.yaml'))],cwd=ROOT,check=True)
docs.OUT=OUT/'format-docs';docs.HERE=docs.OUT/'input';docs.HERE.mkdir(parents=True,exist_ok=True)
(docs.HERE/'comment_changes.json').write_text(json.dumps([{'path':r['path']} for r in rows if r['path'].endswith('.md')]))
docs.main()
