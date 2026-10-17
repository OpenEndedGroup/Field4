"""Use the editor GLSL formatter for explicit Markdown shader states."""
from pathlib import Path
import importlib.util,json,re,yaml,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-25/glsl';O.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('states19',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
entries=[]
for row in json.loads((H/'changes.json').read_text()):
 p=R/row['path']
 text=p.read_text()
 states=list(d.sections(text,'glsl')) if p.suffix=='.md' else [(0,len(text))] if p.suffix=='.glsl' else []
 for a,b in states:
  code=text[a:b];q=O/f'shader-{len(entries)}.yaml';q.write_text('fragment: |\n'+''.join('    '+line+'\n' for line in code.splitlines()));entries.append((p,a,b,q,code))
if entries:subprocess.run([str(R/'gradlew'),'formatBoxCode','--args='+' '.join(str(x[3]) for x in entries)],cwd=R,check=True)
def lex(s):return re.findall(r'\w+|\S',re.sub(r'//[^\n]*|/\*[\s\S]*?\*/','',s))
def comments(s):return re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',s)
for p in {x[0] for x in entries}:
 text=p.read_text()
 for _,a,b,q,code in reversed([x for x in entries if x[0]==p]):
  after=yaml.safe_load(q.read_text())['fragment'];assert lex(code)==lex(after) and comments(code)==comments(after);text=text[:a]+after.rstrip()+'\n'+text[b:]
 p.write_text(text)
(O/'verification.json').write_text(json.dumps({'states':len(entries),'tokensAndCommentsPreserved':True})+'\n')
