"""Format explicit Kotlin/GLSL paths with Field's real editor formatter."""
from pathlib import Path
import json,subprocess,yaml,re
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-10/format';OUT.mkdir(parents=True,exist_ok=True)
paths=sorted({c['path'] for c in json.loads((HERE/'comment_changes.json').read_text())});targets=[]
for i,path in enumerate(paths):
 p=ROOT/path
 if p.suffix=='.glsl':
  q=OUT/f'shader{i}.yaml';q.write_text('fragment: |\n'+''.join('    '+x+'\n' for x in p.read_text().splitlines()));targets.append((p,q,p.read_text()))
 else:targets.append((p,p,p.read_text()))
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(q) for _,q,_ in targets)],cwd=ROOT,check=True)
for p,q,before in targets:
 if p!=q:
  after=yaml.safe_load(q.read_text())['fragment']
  protected=re.compile(r'//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
  assert protected.findall(before)==protected.findall(after),(p,'comment/literal preservation')
  p.write_text(after)
print(len(paths),'sources formatted')
