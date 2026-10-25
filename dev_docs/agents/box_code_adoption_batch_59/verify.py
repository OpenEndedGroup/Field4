"""Compare each parsed code property/state with the reviewed helper edit and literals."""
from pathlib import Path
import importlib.util,json,re,sys,yaml
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE.parent/'box_code_adoption_batch_7'))
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit14',HERE.parent/'box_code_adoption_batch_6/literals.py');tok=load('tok14',HERE.parent/'box_code_adoption_batch_7/verify_tokens.py');docs=load('docs14',HERE.parent/'box_code_adoption_batch_7/format_docs.py')
records=[]
for row in json.loads((HERE/'changes.json').read_text()):
 p=ROOT/row['path'];count=[0,0]
 def code(a,b):
  a,n=lit.convert(a) if a!=b else (a,0);count[0]+=1;count[1]+=n
  assert tok.tokens(a)==tok.tokens(b),(p,count,'tokens')
  assert [x.strip() for x in re.findall(r'(?m)^\s*//%-[^\n]*',a)]==[x.strip() for x in re.findall(r'(?m)^\s*//%-[^\n]*',b)],(p,'execution boundaries')
  aa=[x for x in lit.TOKEN.finditer(a) if x[0].startswith('"""')];bb=[x for x in lit.TOKEN.finditer(b) if x[0].startswith('"""')]
  assert len(aa)==len(bb)
  for x,y in zip(aa,bb):
   if not re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',a[x.end():]):assert x[0]==y[0],(p,'raw string')
 def compare(a,b,key=''):
  if isinstance(a,dict):
   assert a.keys()==b.keys()
   for k in a:compare(a[k],b[k],k)
  elif isinstance(a,list):
   assert len(a)==len(b)
   for x,y in zip(a,b):compare(x,y)
  elif key=='code' and isinstance(a,str):code(a,b)
  elif key in ['vertex','fragment','compute','task','mesh'] and isinstance(a,str):
   def lex(s):return re.findall(r'\w+|\S',re.sub(r'//[^\n]*|/\*[\s\S]*?\*/','',s))
   def comments(s):return re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',s)
   assert lex(a)==lex(b) and comments(a)==comments(b),(p,key,'GLSL tokens and comments')
  else:assert a==b,(p,key,'metadata or foreign code')
 expected=row['after'];actual=p.read_text()
 if p.suffix=='.kt':code(expected,actual)
 elif p.suffix=='.glsl':compare(expected,actual,'fragment')
 elif p.suffix=='.yaml':compare(yaml.safe_load(expected),yaml.safe_load(actual))
 else:
  a=list(docs.sections(expected));b=list(docs.sections(actual));assert len(a)==len(b)
  ga=list(docs.sections(expected,'glsl'));gb=list(docs.sections(actual,'glsl'));assert len(ga)==len(gb)
  for (x,y),(u,v) in zip(ga,gb):compare(expected[x:y],actual[u:v],'fragment')
  def surrounding(s,sections):
   for x,y in reversed(sections):s=s[:x]+'<KOTLIN>'+s[y:]
   return s
  assert surrounding(expected,sorted(a+ga))==surrounding(actual,sorted(b+gb)),(p,'prose')
  for (x,y),(u,v) in zip(a,b):code(expected[x:y],actual[u:v])
 records.append({'path':row['path'],'kotlinBodies':count[0],'collectionConversions':count[1],'reviewedBodyAndBoundariesPreserved':True})
(HERE/'verification.json').write_text(json.dumps({'baseline':'72ce0a610','sources':records},indent=2)+'\n');print(json.dumps(records))
