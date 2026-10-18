"""Format saved inline Kotlin without materializing absent sidecars or changing graph data."""
from pathlib import Path
import importlib.util,json,re,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-52'
s=importlib.util.spec_from_file_location('edit52',H/'edit.py')
# Patterns only: importing edit.py would mutate sources again.
PLAIN=re.compile(r':code\s+("(?:\\.|[^"\\])*")');INLINE=re.compile(r'"code"\s+#afield/file-inline\s*\{"path"\s+("(?:\\.|[^"\\])*")\s+"inline"\s+("(?:\\.|[^"\\])*")')
s=importlib.util.spec_from_file_location('lit52',H.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
jobs=[]
for row in json.loads((H/'changes.json').read_text()):
 p=R/row['path'];pattern,group=(INLINE,2) if 'fline_examples' in row['path'] else (PLAIN,1)
 for m,approved in zip(pattern.finditer(p.read_text()),pattern.finditer(row["after"])):
  from literals import normalize
  code,n,calls=normalize(json.loads(approved[group]));t=OUT/f'inline-{len(jobs)}_code_.kt';t.write_text(code);jobs.append((p,m.start(group),m.end(group),t,code,n))
assert len(jobs)==40,len(jobs)
subprocess.run([str(R/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(j[3]) for j in jobs)],check=True)
for p in dict.fromkeys(j[0] for j in jobs):
 text=p.read_text()
 for source,a,b,t,expected,n in reversed([j for j in jobs if j[0]==p]):
  code=t.read_text();aa=[x for x in lit.TOKEN.finditer(expected) if x[0].startswith('"""')];bb=[x for x in lit.TOKEN.finditer(code) if x[0].startswith('"""')];assert len(aa)==len(bb)
  for x,y in reversed(list(zip(aa,bb))):
   if not re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',expected[x.end():]):code=code[:y.start()]+x[0]+code[y.end():]
  text=text[:a]+json.dumps(code,ensure_ascii=False)+text[b:]
 p.write_text(text)
(OUT/'format-counts.json').write_text(json.dumps({'inlineKotlinBodies':len(jobs),'collectionConversions':sum(j[5] for j in jobs),'missingSidecarsMaterialized':0},indent=2)+'\n')
