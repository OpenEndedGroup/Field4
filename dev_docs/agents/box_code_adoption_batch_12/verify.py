"""Prove every source body matches the explicit edits, literals and mechanical formatting."""
from pathlib import Path
import json,subprocess,importlib.util,sys,re
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'tools'));sys.path.insert(0,str(HERE.parent/'box_code_adoption_batch_7'));from check_box_idioms import kotlin_blocks

def load(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
lit=load('lit',HERE.parent/'box_code_adoption_batch_6/literals.py');tok=load('tok',HERE.parent/'box_code_adoption_batch_7/verify_tokens.py')
rows=json.loads((HERE/'changes.json').read_text());records=[]
for path in sorted({r['path'] for r in rows}):
 old=subprocess.check_output(['git','show',f"{sys.argv[1]}:{path}"],cwd=ROOT,text=True)
 expected=old
 for r in [r for r in rows if r['path']==path]:
  assert expected.count(r['old'])==1;expected=expected.replace(r['old'],r['new'])
 lines=expected.splitlines(keepends=True);conversions=0
 for a,b in reversed(kotlin_blocks(path,[x.rstrip('\n') for x in lines])):
  body,n=lit.convert(''.join(lines[a:b]));lines[a:b]=body.splitlines(keepends=True);conversions+=n
 expected=''.join(lines);actual=(ROOT/path).read_text()
 # Kotlin normalizes imports/raw-string indentation. Non-Kotlin properties are checked
 # separately below, retaining their actual comment/literal content.
 def bodies(text):
  lines=text.splitlines(keepends=True);return [''.join(lines[a:b]) for a,b in kotlin_blocks(path,[x.rstrip('\n') for x in lines])]
 a=bodies(expected);b=bodies(actual);assert len(a)==len(b),(path,len(a),len(b))
 for x,y in zip(a,b):
  assert tok.tokens(x)==tok.tokens(y),(path,'Kotlin tokens')
  rawA=[m for m in lit.TOKEN.finditer(x) if m[0].startswith('"""')]
  rawB=[m for m in lit.TOKEN.finditer(y) if m[0].startswith('"""')]
  assert len(rawA)==len(rawB),(path,'raw strings')
  for u,v in zip(rawA,rawB):
   trimmed=re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',x[u.end():])
   if not trimmed:assert u[0]==v[0],(path,'untrimmed raw string content')
 assert [m.strip() for m in re.findall(r'(?m)^\s*//%-[^\n]*',old)]==[m.strip() for m in re.findall(r'(?m)^\s*//%-[^\n]*',actual)],(path,'execution boundaries')
 if path.endswith('.yaml'):
  import yaml
  def foreign(value):
   if isinstance(value,dict):
    return {k:foreign(v) for k,v in value.items() if k!='code'}
   if isinstance(value,list):return [foreign(v) for v in value]
   return value
  def normalize(value):
   if isinstance(value,str):return re.sub(r'\s+','',re.sub(r'//[^\n]*|/\*[\s\S]*?\*/','',value))
   if isinstance(value,dict):return {k:normalize(v) for k,v in value.items()}
   if isinstance(value,list):return [normalize(v) for v in value]
   return value
  assert normalize(foreign(yaml.safe_load(expected)))==normalize(foreign(yaml.safe_load(actual))),(path,'graph and non-Kotlin properties')
  def shader_comments(value):
   result=[]
   if isinstance(value,dict):
    for k,v in value.items():
     if k in ['vertex','fragment','compute','task','mesh'] and isinstance(v,str):
      result.append((k,re.findall(r'//[^\r\n]*|/\*[\s\S]*?\*/',v)))
     else:result.extend(shader_comments(v))
   elif isinstance(value,list):
    for v in value:result.extend(shader_comments(v))
   return result
  assert shader_comments(yaml.safe_load(expected))==shader_comments(yaml.safe_load(actual)),(path,'exact shader comments')
 records.append({'path':path,'kotlinBodies':len(a),'collectionLiterals':conversions,'bodyMatchesReviewedChanges':True,'executionBoundariesPreserved':True})
result={'baseline':sys.argv[1],'findingIds':sorted({i for r in rows for i in r['findingIds']}),'sources':records,'sourceFacts':{'midiBeats':2*8,'barsIn4_4':2*8/4,'gliderCells':5,'sixGlidersCells':30,'defaultCurlCalls':3*24*24*72,'alternativeCurlCalls':6*96*96*24,'sourceApertureLookMembers':['x','y']}}
(HERE/'verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
