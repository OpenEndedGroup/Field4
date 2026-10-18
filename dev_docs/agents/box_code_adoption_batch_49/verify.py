"""Compare baseline executable code, interfaces and document structure after formatting."""
from pathlib import Path
import importlib.util,json,re,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'))
from verify_tokens import tokens
s=importlib.util.spec_from_file_location('lit49',H.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
STR=re.compile(r':code\s+("(?:\\.|[^"\\])*")');records=[]
for row in json.loads((H/'changes.json').read_text()):
 p=R/row['path'];a=row['before'];b=p.read_text();counts={'changedKotlinBodies':0,'shaderBodies':0,'collectionConversions':0,'foreignBodiesPreserved':0}
 def kotlin(x,y):
  if x==y:counts['foreignBodiesPreserved']+=1;return
  x,n=lit.convert(x);assert tokens(x)==tokens(y),(p,'Kotlin body');counts['changedKotlinBodies']+=1;counts['collectionConversions']+=n
  assert re.findall(r'(?m)^\s*//%-[^\n]*',x)==re.findall(r'(?m)^\s*//%-[^\n]*',y),(p,'fragment markers')
 def shader(x,y):
  # A local name changes to match its actual coordinate space; interfaces and expressions stay.
  assert tokens(x)==tokens(re.sub(r'\bclipPosition\b','viewPos',y)),(p,'GLSL tokens');counts['shaderBodies']+=1
 def compare(x,y,key=''):
  if isinstance(x,dict):
   assert x.keys()==y.keys()
   for k in x:compare(x[k],y[k],k)
  elif isinstance(x,list):
   assert len(x)==len(y)
   for u,v in zip(x,y):compare(u,v,key)
  elif key=='code' and isinstance(x,str):kotlin(x,y)
  elif key in ['compute','vertex','fragment'] and isinstance(x,str):shader(x,y)
  else:assert x==y,(p,key,'container')
 if p.suffix=='.edn':
  old=list(STR.finditer(a));new=list(STR.finditer(b));assert len(old)==len(new)
  assert STR.sub(':code "<BODY>"',a)==STR.sub(':code "<BODY>"',b),(p,'EDN IDs, graph and non-code values')
  for u,v in zip(old,new):kotlin(json.loads(u[1]),json.loads(v[1]))
 elif p.suffix=='.yaml':compare(yaml.safe_load(a),yaml.safe_load(b))
 else:shader(a,b)
 records.append({'path':row['path'],**counts,'baselineCodeAndContainerPreserved':True})
# Confirm packed fields from their actual writers and host colour contract.
cluster=(R/'src/jvmMain/kotlin/afield/graphics/render/ClusterGeometry.kt').read_text();assert 'out.putInt(clusterId).putInt(geometryId).putInt(0).putInt(0)' in cluster
study=(R/'public/docs/assets/opendlssnr/OpenDlssNrStudy.kt').read_text();assert 'Vector4f(time,size.toFloat(),size.toFloat(),0f)' in study and 'OpenDlssNrColorEncoding.LINEAR_LDR' in study
for name in ['c82c404d-a15d-4f51-a06f-66dfc2a00364_fragment_.glsl','gpu_skin_fragment_.glsl']:
 assert 'vViewPos' not in (R/'dev_docs/agents/demos/skinned_characters'/name).read_text()
(H/'verification.json').write_text(json.dumps({'baseline':'df883e149','sources':records,'contractsChecked':['ClusterGeometry record writer','OpenDlssNrStudy frame and LINEAR_LDR inputs','paired fragments do not consume vViewPos'],'runtimeReplay':'Not repeated: only comments/layout/imports and an expression-preserving local rename.'},indent=2)+'\n');print(json.dumps(records))
