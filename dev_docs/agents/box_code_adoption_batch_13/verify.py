"""Compare parsed containers and shader bodies; compile and validate every authored stage."""
from pathlib import Path
import json,re,yaml,sys,importlib.util,subprocess,shutil
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-13';sys.path.insert(0,str(HERE.parent/'box_code_adoption_batch_7'))
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit',HERE.parent/'box_code_adoption_batch_6/literals.py');tok=load('tok',HERE.parent/'box_code_adoption_batch_7/verify_tokens.py')
def comments(s):return re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',s)
def lex(s):return re.findall(r'\w+|\S',re.sub(r'//[^\n]*|/\*[\s\S]*?\*/','',s))
shaders=[];records=[]
for row in json.loads((HERE/'drafts.json').read_text()):
 p=ROOT/row['path'];actual=p.read_text();counts={'kotlinBodies':0,'collectionLiterals':0,'glslBodies':0}
 def compare(a,b,key='',location=''):
  if isinstance(a,dict):
   assert isinstance(b,dict) and a.keys()==b.keys(),(p,location)
   for k in a:compare(a[k],b[k],k,location+'/'+str(k))
  elif isinstance(a,list):
   assert isinstance(b,list) and len(a)==len(b),(p,location)
   for i,(x,y) in enumerate(zip(a,b)):compare(x,y,'',location+'/'+str(i))
  elif key=='code' and isinstance(a,str):
   expected,n=lit.convert(a);assert tok.tokens(expected)==tok.tokens(b),(p,location,'Kotlin body')
   assert re.findall(r'(?m)^\s*//%-[^\n]*',expected)==re.findall(r'(?m)^\s*//%-[^\n]*',b),(p,'markers')
   counts['kotlinBodies']+=1;counts['collectionLiterals']+=n
  elif key in ['vertex','fragment','compute','task','mesh'] and isinstance(a,str):
   assert lex(a)==lex(b) and comments(a)==comments(b),(p,location,'shader body/comments')
   counts['glslBodies']+=1;shaders.append((row['path']+location,key,b))
  else:assert a==b,(p,location,'container metadata')
 if p.suffix=='.yaml':compare(yaml.safe_load(row['after']),yaml.safe_load(actual))
 else:
  assert lex(row['after'])==lex(actual) and comments(row['after'])==comments(actual),p
  stage='vertex' if p.suffix=='.vert' or p.name=='vertex.glsl' else 'fragment'
  shaders.append((row['path'],stage,actual));counts['glslBodies']+=1
 records.append({'path':row['path'],**counts,'matchesReviewedDraft':True})
compiler=shutil.which('glslc');validator=shutil.which('spirv-val');assert compiler and validator
results=[]
for i,(label,stage,code) in enumerate(shaders):
 source=OUT/f'compiled-{i}.glsl';binary=source.with_suffix('.spv');source.write_text(code)
 a=subprocess.run([compiler,'-O','--target-env=vulkan1.2','-fshader-stage='+stage,str(source),'-o',str(binary)],capture_output=True,text=True)
 assert a.returncode==0,(label,a.stderr)
 b=subprocess.run([validator,'--target-env','vulkan1.2',str(binary)],capture_output=True,text=True);assert b.returncode==0,(label,b.stderr)
 results.append({'source':label,'stage':stage,'compiled':True,'spirvValidated':True})
result={'baseline':'9abce375c','sources':records,'shaders':results,'gpuRenderAcceptance':'not exercised; mathematical operations and ABI preserved by source proof','rrGuideConversion':'Copied the exact reviewed batch-10 guide body after matching the original shader tokens'}
(HERE/'verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
