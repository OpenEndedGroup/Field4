"""Check current bodies against reviewed replacements and allowed literal/local-name edits."""
from pathlib import Path
import importlib.util,json,re,subprocess,yaml,sys
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-10'
sys.path.insert(0,str(HERE.parent/'box_code_adoption_batch_7'))
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
m=module('tokens',HERE.parent/'box_code_adoption_batch_7/verify_tokens.py');lit=module('lit',HERE.parent/'box_code_adoption_batch_6/literals.py')
protected=re.compile(r'//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
cs=json.loads((HERE/'comment_changes.json').read_text());rows=[]
for name in sorted({c['path'] for c in cs}):
 before=subprocess.check_output(['git','show','94245dbf4:'+name],cwd=ROOT,text=True)
 for c in cs:
  if c['path']==name and c['old']:
   assert c['old'] in before,(name,c['finding']);before=before.replace(c['old'],c['new'])
 after=(ROOT/name).read_text()
 if name.endswith('.yaml'):
  a=yaml.safe_load(before);b=yaml.safe_load(after)
  aa=[o.get('create',{}).get('properties',{}).get('code','') for o in a.get('operations',[])];bb=[o.get('create',{}).get('properties',{}).get('code','') for o in b.get('operations',[])];assert len(aa)==len(bb)
 else:aa=[before];bb=[after]
 for a,b in zip(aa,bb):
  a=lit.convert(a)[0] if not name.endswith('.glsl') else a
  ta=m.tokens(a);tb=m.tokens(b)
  if name=='src/jvmMain/resources/nano_compute.glsl':ta=['byteOffset' if t=='flat' else t for t in ta]
  assert ta==tb,(name,'unexpected executable tokens')
  assert a.count('//%-')==b.count('//%-'),name
  if name.endswith('.glsl'):assert protected.findall(a)==protected.findall(b),(name,'comment/literal preservation')
 rows.append({'path':name,'bodyMatchesReviewedChanges':True,'glslCommentsAndLiteralsPreserved':True if name.endswith('.glsl') else None})
(OUT/'token-preservation.json').write_text(json.dumps(rows,indent=2)+'\n')
print(len(rows),'sources retain executable tokens and shader comments after reviewed edits')
