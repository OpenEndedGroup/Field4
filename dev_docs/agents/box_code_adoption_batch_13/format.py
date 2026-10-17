"""Use Field's actual import, ktfmt and GLSL formatters on the reviewed sources."""
from pathlib import Path
import json,subprocess,yaml,re
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-13/format';OUT.mkdir(parents=True,exist_ok=True)
rows=json.loads((HERE/'drafts.json').read_text());targets=[]
for i,row in enumerate(rows):
 p=ROOT/row['path']
 if p.suffix in ['.glsl','.vert','.frag']:
  q=OUT/f'shader{i}.yaml';q.write_text('fragment: |\n'+''.join('    '+x+'\n' for x in p.read_text().splitlines()));targets.append((p,q,p.read_text()))
 else:targets.append((p,p,p.read_text()))
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(q) for _,q,_ in targets)],cwd=ROOT,check=True)
for p,q,before in targets:
 if p!=q:
  after=yaml.safe_load(q.read_text())['fragment']
  assert re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',before)==re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',after),p
  p.write_text(after)
print(len(rows),'explicit source/container paths formatted')
