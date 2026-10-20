"""Compare actual GPU teacher captures before/after fullscreen mesh and reflected-push adoption."""
from pathlib import Path
import asyncio,importlib.util,json,re,yaml
import numpy as np
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-64'
s=importlib.util.spec_from_file_location('rig64',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9722;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 results=[]
 rows=json.loads((H/'changes.json').read_text())
 for name in ['learned_from_module','gpu_shader_learning']:
  row=next(r for r in rows if r['path']=='dev_docs/agents/transcripts/'+name+'.yaml')
  datasets={};digests={}
  for state in ['before','after']:
   doc=yaml.safe_load(row['before'] if state=='before' else (R/row['path']).read_text());box=next(o['create'] for o in doc['operations'] if o.get('create',{}).get('box')==name);body=box['properties']['code'];a=body.index('val source =');b=body.index('lab =',a);prefix=body[a:b]
   prefix=prefix.replace('width = 1920','width = 96').replace('height = 1080','height = 64')
   if name=='gpu_shader_learning':
    # The authored GPU view's callback is the same source-parameter contract; no training view is constructed.
    match=re.search(r't -> (mesh\.(?:push\["state"\]|state) = (?:\{\s*vec\(source.width, source.height, t, 0\)\s*\}|vec\(source.width, source.height, t, 0\)))',body);assert match
    prefix+='val setTime:(Float)->Unit={t -> '+match[1]+'}\n'
   directory=m.OUT/(name+'-'+state)
   program='''import afield.graphics.shader.learning.*
import java.io.File
import org.lwjgl.vulkan.VK13.*
PREFIX
val dataset=ShaderTraining.capture(renderer,source,File(DATASET),trainingFrames=3,validationFrames=2,pixelsPerFrame=64,seed=7,setParameter=setTime)
val manifest=org.json.JSONObject(File(dataset,"dataset.json").readText())
check(manifest.getString("sourceShaderDigest")==ShaderTraining.sourceDigest(source))
check(mesh.push.offsets["state"]==0)
println("actual GPU teacher capture and source digest verified")
'''.replace('PREFIX',prefix).replace('DATASET',json.dumps(str(directory)))
   box['box']='g5-'+name+'-'+state+('-callback' if name=='gpu_shader_learning' else '');box['properties']['code']=program
   saved=m.OUT/(box['box']+'.json')
   if (directory/'dataset.json').exists():
    assert saved.is_file() and json.loads(saved.read_text()).get('ok'),'partial capture cannot be reused'
   else:await m.macro(box['box'],[{'create':box},{'connect':{'parent':'renderer','child':box['box']}},{'execute':{'box':box['box']}}])
   manifest=json.loads((directory/'dataset.json').read_text());digests[state]=manifest['sourceShaderDigest'];datasets[state]=directory
   values=np.fromfile(directory/'train.f32',dtype='<f4').reshape(-1,6)
   assert np.isfinite(values).all() and np.ptp(values[:,3:])>.1
  for split in ['train','validation']:assert (datasets['before']/(split+'.f32')).read_bytes()==(datasets['after']/(split+'.f32')).read_bytes(),(name,split,'actual GPU RGB changed')
  results.append({'teacher':name,'actualTrainingAndValidationSamplesEqual':True,'manifestMatchesActualSourceDigest':True,'sourceDigestBefore':digests['before'],'sourceDigestAfter':digests['after']})
 (H/'behavior.json').write_text(json.dumps({'teachers':results,'limits':'Complete authored shader pairs and actual teacher setup/callbacks, with capture resolution reduced to 96×64 and five real parameter slices. No cooperative-vector training/view, neural inference or public capture is claimed. Helper adoption leaves lifecycle fixes for group 3.'},indent=2)+'\n')
asyncio.run(main())
