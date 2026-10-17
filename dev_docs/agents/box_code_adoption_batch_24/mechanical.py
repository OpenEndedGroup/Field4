"""Format each explicitly owned Kotlin/GLSL body through the real editor formatters."""
from pathlib import Path
import json,subprocess,yaml,importlib.util,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-24/format';O.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('lit24',H.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
entries=[]
for i,row in enumerate(json.loads((H/'changes.json').read_text())):
 p=R/row['path'];text=p.read_text()
 if p.suffix=='.kt':
  code,count=lit.convert(text);q=O/f'{i}_code_.kt';q.write_text(code)
 elif p.suffix=='.glsl':
  count=0;q=O/f'{i}.yaml';q.write_text('fragment: |\n'+''.join('    '+line+'\n' for line in text.splitlines()))
 else:raise ValueError(p)
 entries.append((p,q,text,count))
assert entries and len({q for _,q,_,_ in entries})==len(entries)
subprocess.run([str(R/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(q) for _,q,_,_ in entries)],cwd=R,check=True)
records=[]
for p,q,before,count in entries:
 after=yaml.safe_load(q.read_text())['fragment'] if p.suffix=='.glsl' else q.read_text()
 if p.suffix=='.kt':
  aa=[x for x in lit.TOKEN.finditer(before) if x[0].startswith('"""')];bb=[x for x in lit.TOKEN.finditer(after) if x[0].startswith('"""')];edits=[];assert len(aa)==len(bb)
  for a,b in zip(aa,bb):
   if not re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',before[a.end():]):edits.append((b.start(),b.end(),a[0]))
  for a,b,value in reversed(edits):after=after[:a]+value+after[b:]
 p.write_text(after);records.append({'path':str(p.relative_to(R)),'collectionConversions':count})
(O/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
