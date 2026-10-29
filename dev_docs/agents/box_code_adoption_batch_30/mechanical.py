"""Format owned sidecars and copy only the owned inverse body into its builder."""
from pathlib import Path
import importlib.util,json,subprocess,yaml
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-30'
s=importlib.util.spec_from_file_location('lit30',HERE.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
rows=json.loads((HERE/'changes.json').read_text());temps=[]
for i,r in enumerate(rows):
 if not r['path'].endswith('.kt'):continue
 p=ROOT/r['path'];code,n=lit.convert(p.read_text());temp=OUT/f'body-{i}_code_.kt';temp.write_text(code);temps.append((p,temp,n))
assert temps
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(t) for _,t,_ in temps)],cwd=ROOT,check=True)
for p,t,_ in temps:p.write_text(t.read_text())
p=ROOT/'dev_docs/agents/demos/solidsl_inverse/build.yaml';text=p.read_text();edits=[]
def visit(node):
 if isinstance(node,yaml.MappingNode):
  for k,v in node.value:
   if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'target reference; fitted parameter' in v.value:
    code=(p.parent/'inverse_code_.kt').read_text();indent=k.start_mark.column+2
    value='|\n'+''.join(' '*indent+line+'\n' if line else '\n' for line in code.rstrip('\n').splitlines())
    edits.append((v.start_mark.index,v.end_mark.index,value))
   else:visit(v)
 elif isinstance(node,yaml.SequenceNode):
  for v in node.value:visit(v)
visit(yaml.compose(text));assert len(edits)==1
for a,b,code in reversed(edits):text=text[:a]+code+text[b:]
p.write_text(text)
(OUT/'literal-conversions.json').write_text(json.dumps([{'path':str(p.relative_to(ROOT)),'conversions':n} for p,_,n in temps],indent=2)+'\n')
