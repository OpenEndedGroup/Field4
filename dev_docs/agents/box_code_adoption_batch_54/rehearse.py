"""Compare genuine native paint pixels and real schema packing; no renderer required."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig54',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-54/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
from format_docs import sections
rows={x['path']:x for x in json.loads((H/'changes.json').read_text())}
def flow(text):
 return next(o['create']['properties']['code'] for o in yaml.safe_load(text)['operations'] if o.get('create',{}).get('box')=='paint_flow_field')
async def main():
 p='dev_docs/agents/transcripts/paint_canvas.yaml'
 for label in ['before','after','edited','restored']:
  code=flow(rows[p]['before'] if label=='before' else (R/p).read_text())
  if label=='edited':code=code.replace('strokeCount = 260','strokeCount = 160').replace('stepLength = 6.0','stepLength = 9.0')
  image=json.dumps(str(m.OUT/(label+'.png')))
  code=code.replace('display("main", canvas.png(relief = 0.8))','val png = canvas.png(relief = 0.8)\njava.nio.file.Files.write(java.nio.file.Path.of('+image+'), png)\ndisplay("main", png)\ncanvas.close()')
  await m.program('flow-'+label,code,'proof("real native flow rendered")')
 from PIL import Image
 images={k:Image.open(m.OUT/(k+'.png')).convert('RGBA') for k in ['before','after','edited','restored']}
 assert images['before'].tobytes()==images['after'].tobytes()==images['restored'].tobytes()
 assert images['after'].tobytes()!=images['edited'].tobytes()
 p='public/docs/gpu_schema.md';proofs={}
 for label in ['before','after']:
  page=rows[p]['before'] if label=='before' else (R/p).read_text();ss=[page[a:b] for a,b in sections(page)]
  declarations=next(x for x in ss if 'data class Particle(' in x)
  values=next(x for x in ss if 'val values =' in x);values=values[:values.index('val particleBuffer')]
  packing='''val packed=java.nio.ByteBuffer.allocate(values.size*particle.strideBytes).order(java.nio.ByteOrder.nativeOrder())
particle.writeArray(values,packed)
check(values.size==3)
proof(particle.abiHash.toString()+packed.array().joinToString(","))'''
  result=await m.program('schema-'+label,declarations+'\n'+values,packing);proofs[label]=result
 assert proofs['before']==proofs['after']
 (H/'behavior.json').write_text(json.dumps({'nativePaint':{'defaultPixelsIdentical':True,'editedControlsChangePixels':True,'seededRestorationIdentical':True,'size':images['after'].size},'schema':{'realCpuPackingAndAbiIdentical':True,'proof':proofs['after']},'limits':'Actual native paint and CPU schema bytes; no Vulkan allocation, GPU drawing or public capture.'},indent=2)+'\n')
asyncio.run(main())
