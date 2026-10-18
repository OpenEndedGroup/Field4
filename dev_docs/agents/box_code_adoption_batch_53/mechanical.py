"""Format selected Kotlin controllers and pages; leave the actual Csound language untouched."""
from pathlib import Path
import importlib.util,json,subprocess,yaml,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-53'
def load(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit53',H.parent/'box_code_adoption_batch_6/literals.py');docs=load('docs53',H.parent/'box_code_adoption_batch_7/format_docs.py');jobs=[]
p=R/'dev_docs/agents/transcripts/csound_demo.yaml';text=p.read_text()
def visit(n):
 if isinstance(n,yaml.MappingNode):
  d={k.value:v for k,v in n.value}
  if d.get('box') and d['box'].value in ['cs_talk','cs_keys'] and 'properties' in d:
   k,v=next((k,v) for k,v in d['properties'].value if k.value=='code');code,count=lit.convert(v.value);t=OUT/f'control-{len(jobs)}_code_.kt';t.write_text(code);jobs.append((k,v,t,count))
  else:
   for k,v in n.value:visit(v)
 elif isinstance(n,yaml.SequenceNode):
  for v in n.value:visit(v)
visit(yaml.compose(text));assert len(jobs)==2
subprocess.run([str(R/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(t) for _,_,t,_ in jobs)],check=True)
for k,v,t,count in reversed(jobs):
 ind=k.start_mark.column+2;value='|\n'+''.join(' '*ind+l+'\n' if l else '\n' for l in t.read_text().rstrip().splitlines());text=text[:v.start_mark.index]+value+text[v.end_mark.index:]
p.write_text(text)
docs.OUT=OUT/'format-docs';docs.HERE=docs.OUT/'input';docs.HERE.mkdir(parents=True,exist_ok=True);rows=json.loads((H/'changes.json').read_text());(docs.HERE/'comment_changes.json').write_text(json.dumps([{'path':r['path']} for r in rows if r['path'].endswith('.md')]));docs.main()
for row in rows:
 p=R/row['path']
 if p.suffix!='.md':continue
 actual=p.read_text();expected=row['after'];edits=[]
 for (a,b),(c,e) in zip(docs.sections(expected),docs.sections(actual)):
  x=expected[a:b];y=actual[c:e];aa=[u for u in lit.TOKEN.finditer(x) if u[0].startswith('"""')];bb=[u for u in lit.TOKEN.finditer(y) if u[0].startswith('"""')];assert len(aa)==len(bb)
  for u,v in zip(aa,bb):
   if not re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',x[u.end():]) and u[0]!=v[0]:edits.append((c+v.start(),c+v.end(),u[0]))
 for a,b,value in sorted(edits,reverse=True):actual=actual[:a]+value+actual[b:]
 p.write_text(actual)
