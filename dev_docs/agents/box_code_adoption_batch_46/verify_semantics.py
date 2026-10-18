"""Prove this batch changes teaching text, imports and layout, not executable tokens."""
from pathlib import Path
import json,re,importlib.util,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'))
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
lit=load('literals46',H.parent/'box_code_adoption_batch_6/literals.py')
from verify_tokens import tokens
from format_docs import sections
proof=[]
for row in json.loads((H/'changes.json').read_text()):
 p=R/row['path'];before=row['before'];after=p.read_text();bodies=0;conversions=0
 def compare_code(a,b):
  global bodies,conversions
  a,n=lit.convert(a);conversions+=n;assert tokens(a)==tokens(b),(p,'body tokens');bodies+=1
  assert re.findall(r'(?m)^\s*//%-.*$',a)==re.findall(r'(?m)^\s*//%-.*$',b),(p,'fragment markers')
 if p.suffix=='.md':
  a=list(sections(before));b=list(sections(after));assert len(a)==len(b)
  for (x,y),(u,v) in zip(a,b):compare_code(before[x:y],after[u:v])
 elif p.suffix=='.yaml':
  a=yaml.safe_load(before);b=yaml.safe_load(after);a.pop('description',None);b.pop('description',None)
  def walk(a,b,key=''):
   if isinstance(a,dict):
    assert a.keys()==b.keys()
    for k in a:walk(a[k],b[k],k)
   elif isinstance(a,list):
    assert len(a)==len(b)
    for x,y in zip(a,b):walk(x,y,key)
   elif key=='code' and isinstance(a,str):compare_code(a,b)
   else:assert a==b,(p,key)
  walk(a,b)
 else:assert tokens(before)==tokens(after),(p,'shader tokens')
 proof.append({'path':row['path'],'kotlinBodies':bodies,'collectionConversions':conversions,'baselineExecutableTokensPreserved':True})
# Check the concrete source contracts behind corrected instructions.
klecks=(R/'src/jvmMain/kotlin/afield/graphics/paint/Klecks.kt').read_text();assert 'fun pngBytes(): ByteArray? = bytes(PNG)' in klecks
planner=(R/'src/jvmMain/kotlin/afield/graphics/scene/fabricated/FabricatePassLibrary.kt').read_text();assert 'generateSequence(resolution / 2)' in planner and 'return listOf(seed) + floods + distance' in planner
remote=(R/'templates/advanced_remote/e3fe297a-36b5-450a-8343-116e105de4ee_code_.kt').read_text();assert 'newShaderForBox({"foo":vec(0,0,0)})' in remote
(H/'semantics.json').write_text(json.dumps({'baseline':'d54d91aa6','sources':proof,'sourceContractsChecked':['Klecks snapshot accessors','jump-flood stride/seed/plus-one/distance stages','remote foo binding'],'runtimeReplay':'Not repeated: executable tokens and shader interfaces are unchanged.'},indent=2)+'\n')
print(sum(x['kotlinBodies'] for x in proof),'Kotlin states; all baseline executable tokens preserved;',sum(x['collectionConversions'] for x in proof),'collection conversions')
