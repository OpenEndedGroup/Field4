"""Format selected Kotlin/GLSL, preserving the mixed transcript's Python verbatim."""
from pathlib import Path
import importlib.util,json,subprocess,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-47';edits=[]
spec=importlib.util.spec_from_file_location('lit47',H.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(spec);spec.loader.exec_module(lit)
p=R/'dev_docs/agents/transcripts/fieldcv_webcam.yaml';text=p.read_text()
def visit(n):
 if isinstance(n,yaml.MappingNode):
  for k,v in n.value:
   if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'm.resize(0, 0)' in v.value:
    t=OUT/f'overlay-{len(edits)}_code_.kt';t.write_text(lit.convert(v.value)[0]);edits.append((k,v,t))
   else:visit(v)
 elif isinstance(n,yaml.SequenceNode):
  for v in n.value:visit(v)
visit(yaml.compose(text));assert len(edits)==1
subprocess.run([str(R/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(t) for _,_,t in edits)],check=True)
for k,v,t in reversed(edits):
 ind=k.start_mark.column+2;value='|\n'+''.join(' '*ind+line+'\n' if line else '\n' for line in t.read_text().rstrip().splitlines());text=text[:v.start_mark.index]+value+text[v.end_mark.index:]
p.write_text(text)
s=importlib.util.spec_from_file_location('docs47',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d);d.OUT=OUT/'format-docs';d.HERE=d.OUT/'input';d.HERE.mkdir(parents=True,exist_ok=True);(d.HERE/'comment_changes.json').write_text(json.dumps([{'path':'public/docs/python_cv.md'}]));d.main()
