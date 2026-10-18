"""Keep literals where type inference works; disambiguate overload-sensitive drawing calls."""
import importlib.util
from pathlib import Path
s=importlib.util.spec_from_file_location('litbase52',Path(__file__).resolve().parent.parent/'box_code_adoption_batch_6/literals.py');base=importlib.util.module_from_spec(s);s.loader.exec_module(base)
def lex(code):return [m for m in base.TOKEN.finditer(code) if not m[0].startswith(('//','/*'))]
def normalize(code):
 code,collections=base.convert(code);tokens=lex(code);stack=[];pairs=[]
 for i,t in enumerate(tokens):
  if t[0]=='[':stack.append(i)
  elif t[0]==']':pairs.append((stack.pop(),i))
 edits=[];retained=0
 for a,b in pairs:
  previous=tokens[a-1][0] if a else ''
  following=tokens[b+1][0] if b+1<len(tokens) else ''
  if previous in ['(','=',',','+','return','{','->'] and (previous=='+' or following=='+'):
   edits += [(tokens[a].start(),tokens[a].end(),'listOf('),(tokens[b].start(),tokens[b].end(),')')];retained+=1
 for a,b,value in sorted(edits,reverse=True):code=code[:a]+value+code[b:]
 tokens=lex(code);edits=[];calls=0
 for i,t in enumerate(tokens[:-2]):
  if t[0]!='lines' or tokens[i+1][0]!='(' or tokens[i+2][0]!='[':continue
  depth=0
  for j in range(i+2,len(tokens)):
   q=tokens[j]
   if q[0]=='[':depth+=1
   elif q[0]==']':
    depth-=1
    if depth==0:
     if j+1<len(tokens) and tokens[j+1][0] in [',',')']:
      edits.extend([(tokens[i+2].start(),tokens[i+2].end(),''),(q.start(),q.end(),'')]);calls+=1
     break
 for a,b,value in sorted(edits,reverse=True):code=code[:a]+value+code[b:]
 return code,max(0,collections-retained),calls
