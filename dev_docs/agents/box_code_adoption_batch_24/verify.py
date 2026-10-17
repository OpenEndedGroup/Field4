"""Prove comment edits preserve executable tokens and check the real formatter output."""
from pathlib import Path
import importlib.util,json,re,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'))
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit24',H.parent/'box_code_adoption_batch_6/literals.py');tok=load('tokens24',H.parent/'box_code_adoption_batch_7/verify_tokens.py')
def glsl(s):return re.findall(r'\w+|\S',re.sub(r'//[^\n]*|/\*[\s\S]*?\*/','',s))
records=[]
for r in json.loads((H/'changes.json').read_text()):
 p=R/r['path'];actual=p.read_text();before=r['before'];reviewed=r['after']
 if p.suffix=='.glsl':
  assert glsl(before)==glsl(reviewed)==glsl(actual),p
  assert re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',reviewed)==re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',actual),p
  count=0
 else:
  assert tok.tokens(before)==tok.tokens(reviewed),(p,'comment-only edit changed code')
  converted,count=lit.convert(reviewed);assert tok.tokens(converted)==tok.tokens(actual),(p,'formatter tokens')
  assert [x.strip() for x in re.findall(r'(?m)^\s*//%-[^\n]*',before)]==[x.strip() for x in re.findall(r'(?m)^\s*//%-[^\n]*',actual)],(p,'fragment markers')
 records.append({'path':r['path'],'commentOnlyExecutableTokensPreserved':True,'formatterVerified':True,'collectionConversions':count})
(H/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
print(len(records),'bodies proved;',sum(x['collectionConversions'] for x in records),'collection conversions')
