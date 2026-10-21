"""Format changed inline Kotlin and authored GLSL with container-aware replacements."""
from pathlib import Path
import importlib.util,json,re,subprocess,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-49'
def load(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit49',H.parent/'box_code_adoption_batch_6/literals.py');jobs=[];inline=[];shaders=[]
STR=re.compile(r':code\s+("(?:\\.|[^"\\])*")')
for row in json.loads((H/'changes.json').read_text()):
 p=R/row['path']
 if p.suffix=='.edn':
  before=list(STR.finditer(row['before']));after=list(STR.finditer(p.read_text()));assert len(before)==len(after)
  for i,(a,b) in enumerate(zip(before,after)):
   if a[1]==b[1]:continue
   t=OUT/f'inline-{len(inline)}_code_.kt';code,n=lit.convert(json.loads(b[1]));t.write_text(code);inline.append((p,i,t,n));jobs.append(str(t))
 elif p.suffix=='.yaml':jobs.append(str(p))
 elif p.suffix=='.glsl':
  t=OUT/f'shader-{len(shaders)}.yaml';t.write_text('vertex: |\n'+''.join('    '+l+'\n' for l in p.read_text().splitlines()));shaders.append((p,t));jobs.append(str(t))
assert jobs
subprocess.run([str(R/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(jobs)],cwd=R,check=True)
for p in dict.fromkeys(x[0] for x in inline):
 text=p.read_text();matches=list(STR.finditer(text));edits=[]
 for source,i,t,n in inline:
  if source==p:edits.append((matches[i].start(1),matches[i].end(1),json.dumps(t.read_text(),ensure_ascii=False)))
 for a,b,c in sorted(edits,reverse=True):text=text[:a]+c+text[b:]
 p.write_text(text)
for p,t in shaders:p.write_text(yaml.safe_load(t.read_text())['vertex'])
(OUT/'format-counts.json').write_text(json.dumps({'inlineKotlinBodies':len(inline),'collectionConversions':sum(x[3] for x in inline),'shaderSidecars':len(shaders),'yamlContainers':2},indent=2)+'\n')
