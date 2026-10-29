"""Freeze changed teaching code since the previous landed catch-up; keep source spans."""
from pathlib import Path
import collections,hashlib,importlib.util,json,re,subprocess
import yaml
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/catchup-20261027';BASE='5b9e36542a'
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
DOCS=load('sections',HERE.parent/'box_code_adoption_batch_7/format_docs.py')
CORPUS=load('corpus',ROOT/'tools/inventory_box_code.py')
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def spans(path,text):
 rows=[]
 def add(code,lang,start,end,kind,**extra):
  if code.strip():rows.append(dict(path=path,code=code,language=lang,start=start,end=end,kind=kind,sha256=digest(code),**extra))
 if path.endswith('.md'):
  for lang in ['kotlin','glsl','javascript','python','kotlinjs']:
   for a,b in DOCS.sections(text,lang):add(text[a:b],lang,a,b,'markdown')
 elif path.endswith(('.yaml','.yml')):
  classified=CORPUS.Inventory(ROOT,[path]);classified.transcript(path,text)
  languages={(r['line'],r['property']):r['language'] for r in classified.rows}
  def visit(n):
   if isinstance(n,yaml.MappingNode):
    props={k.value:v.value for k,v in n.value if isinstance(v,yaml.ScalarNode)}
    for k,v in n.value:
     if k.value in CORPUS.LANGUAGES and isinstance(v,yaml.ScalarNode):
      lang='kotlin' if k.value=='code' else 'glsl'
      lang=languages.get((v.start_mark.line+1,k.value),lang)
      if k.value=='code' and props.get('isRemoteJSRoot')=='true':lang='kotlin'
      lang={'remotejs':'javascript','Javascript':'javascript'}.get(lang,lang)
      if re.match(r'\s*//\s*\{\{',v.value):lang='kotlinjs'
      add(v.value,lang,v.start_mark.index,v.end_mark.index,'yaml',indent=k.start_mark.column+2,property=k.value,style=v.style)
     else:visit(v)
   elif isinstance(n,yaml.SequenceNode):
    for child in n.value:visit(child)
  visit(yaml.compose(text))
 elif path.endswith(('.kt','.glsl','.frag','.vert','.comp')):
  lang='kotlin' if path.endswith('.kt') else 'glsl'
  if re.match(r'\s*//\s*\{\{',text):lang='kotlinjs'
  add(text,lang,0,len(text),'file')
 return rows
def inventory():
 head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 paths=subprocess.check_output(['git','diff','--name-only',BASE,head,'--','public/docs','dev_docs/agents/transcripts','templates'],cwd=ROOT,text=True).splitlines()
 paths=[p for p in paths if not p.startswith(('public/docs/reference/','public/docs/assets/')) and (ROOT/p).is_file()]
 todo=[];excluded=[]
 for p in paths:
  now=spans(p,(ROOT/p).read_text())
  old=subprocess.run(['git','show',f'{BASE}:{p}'],cwd=ROOT,capture_output=True,text=True)
  known={r['sha256'] for r in spans(p,old.stdout)} if old.returncode==0 else set()
  delta=[r for r in now if r['sha256'] not in known]
  todo.extend(delta)
  if not delta:excluded.append(p)
 scope=dict(baseline=BASE,head=head,candidatePaths=len(paths),paths=sorted({r['path'] for r in todo}),bodies=len(todo),languages=dict(collections.Counter(r['language'] for r in todo)),unchangedCodeOrNoSupportedCode=excluded)
 (HERE/'scope.json').write_text(json.dumps(scope,indent=2)+'\n');(OUT/'bodies.json').write_text(json.dumps(todo,indent=2)+'\n')
 (OUT/'review.txt').write_text('\n\n'.join(f"=== {i} {r['path']} ({r['language']}, {len(r['code'].splitlines())} lines) ===\n{r['code']}" for i,r in enumerate(todo)))
 (HERE/'checkpoint.json').write_text(json.dumps(dict(state='inventory frozen; review pending',scope='scope.json',reviewed=[],next='Review changed bodies, apply clarity/helper edits and actual formatting, verify affected behavior and run land precheck.'),indent=2)+'\n')
 print(json.dumps({k:v for k,v in scope.items() if k not in ['paths','unchangedCodeOrNoSupportedCode']},indent=2));print(len(scope['paths']),'paths with changed bodies');print('\n'.join(f"{p}: {sum(r['path']==p for r in todo)}" for p in scope['paths']))
if __name__=='__main__':inventory()
