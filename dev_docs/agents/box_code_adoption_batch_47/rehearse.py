"""Exercise real shared-buffer consumers and native rendering with controlled publications."""
import asyncio,importlib.util,json,sys,yaml,os
from pathlib import Path
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig47',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-47/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
baseMacro=m.macro
async def trial(name,*args,**kwargs):return await baseMacro(name+'-'+os.environ.get('FIELD_TRIAL','initial'),*args,**kwargs)
m.macro=trial
from format_docs import sections
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
async def main():
 records=[]
 doc=yaml.safe_load((R/'dev_docs/agents/transcripts/fieldcv_webcam.yaml').read_text())
 creates={o['create']['box']:o['create'] for o in doc['operations'] if 'create' in o}
 renderer=creates['cv_renderer'];renderer['box']='renderer'
 publisher='''import afield.lang.python.NumpyShared
val names = listOf("tracks","skeleton","hands","face","contours","strokes")
fun clearLines() { names.forEach { NumpyShared.lines(it).publish(0,0) } }
fun publishTrack(y: Float) {
 val lines=NumpyShared.lines("tracks");lines.reserve(2,1)
 val points=lines.points().data
 points.put(0,0.2f);points.put(1,y);points.put(2,0.8f);points.put(3,y)
 lines.counts().data.put(0,2);lines.publish(2,1)
}
clearLines()
val picture = NumpyShared.image("camera",4,4)
val pixels=picture.array().data
for (i in 0 until 16) { pixels.put(i*4, (if(i<8)200 else 30).toByte());pixels.put(i*4+1,60.toByte());pixels.put(i*4+2,120.toByte());pixels.put(i*4+3,255.toByte()) }
picture.publish()
'''
 if '--resume-camera' not in sys.argv and '--resume-overlays' not in sys.argv:await m.macro('cv-install',[{'create':renderer},{'create':{'box':'publisher','properties':{'code':publisher}}},{'execute':{'box':'publisher'}},{'launch':{'box':'renderer'}},{'await':{}}],True)
 page=(R/'public/docs/python_cv.md').read_text();current=[page[a:b] for a,b in sections(page)]
 original=json.loads((H/'changes.json').read_text())[0]['before'];original=[original[a:b] for a,b in sections(original)]
 props=creates['cv_camera']['properties'].copy()
 for phase,code in [('before',original[0]),('after',current[0])]:
  if '--resume-overlays' in sys.argv:continue
  if phase=='before' and '--resume-camera' in sys.argv:continue
  if phase=='before':
   props['code']=code
   await m.macro('camera-baseline',[{'create':{'box':'camera-probe','name':'camera-probe','properties':props}},{'connect':{'parent':'renderer','child':'camera-probe'}},{'launch':{'box':'camera-probe'}},{'await':{}}])
  else:
   program=m.OUT/'camera-current.kt';program.write_text(code)
   await m.batch('camera-helper-fixed',[f'set camera-probe code --file {program}','launch camera-probe'])
  await m.batch('camera-'+phase,[f'native-screenshot {m.OUT}/camera-{phase}.png --uid renderer --wait-frames 10 --timeout 60'])
 a=Image.open(m.OUT/'camera-before.png').convert('RGB');b=Image.open(m.OUT/'camera-after.png').convert('RGB');assert a.size==b.size and ImageChops.difference(a,b).getbbox() is None
 records.append({'source':'camera quad','sameNativePixels':True,'size':a.size})
 if '--resume-overlays' not in sys.argv:await m.batch('camera-stop',['stop camera-probe'])
 # Remove its keyed drawing explicitly in this private test before testing empty overlays.
 if '--resume-overlays' not in sys.argv:await fragment('camera-hide','camera-probe','renderer.renderList.modules.clear();println("camera diagnostic drawing removed")')
 for name,props,width in [('transcript',creates['cv_overlay']['properties'],.003),('page',dict(creates['cv_overlay']['properties'],code=current[1]),.004)]:
  if '--only-page' in sys.argv and name=='transcript':continue
  alias='overlay-'+name
  if '--resume-overlays' in sys.argv:
   program=m.OUT/(name+'-current.kt');program.write_text(props['code'])
   await m.batch(name+'-update',[f'set {alias} code --file {program}',f'launch {alias}'])
  else:
   await m.macro(name+'-install',[{'create':{'box':alias,'name':alias,'properties':props}},{'connect':{'parent':'renderer','child':alias}},{'launch':{'box':alias}},{'await':{}}])
  for phase,action,expected in [('initial-empty','clearLines()',0),('line','publishTrack(0.5f)',4),('cleared','clearLines()',0),('resumed','publishTrack(0.25f)',4)]:
   await fragment(name+'-'+phase+'-publish','publisher',action)
   png=m.OUT/f'{name}-{phase}.png'
   await m.batch(name+'-'+phase+'-frames',[f'wait-native-renderer renderer --frames 20 --timeout 60',f'native-screenshot {png} --uid renderer --wait-frames 4 --timeout 60'])
   await fragment(name+'-'+phase+'-counts',alias,f'check(m.numVert=={expected} && m.numTriangle=={2 if expected else 0});println("{name} {phase} mesh counts verified")')
   im=Image.open(png).convert('RGB');x=im.width//2
   rows=[y for y in range(im.height) if (lambda c:c[1]>150 and c[1]>c[0]+40 and c[1]>c[2]+40)(im.getpixel((x,y)))]
   if expected:assert abs(len(rows)-width*im.height)<=2,(name,phase,len(rows),width*im.height)
   else:assert not rows,(name,phase)
   records.append({'source':name,'phase':phase,'vertices':expected,'size':im.size,'greenRows':rows})
  await m.batch(name+'-rerun',[f'launch {alias}','wait-native-renderer renderer --frames 20 --timeout 60'])
  await fragment(name+'-rerun-counts',alias,'check(m.numVert==4 && m.numTriangle==2);println("rerun reads current shared line")')
  await m.batch(name+'-stop',[f'stop {alias}'])
  await fragment(name+'-hide',alias,'m.resize(0,0);println("clear stopped diagnostic overlay")')
  await fragment(name+'-clear','publisher','clearLines()')
 await m.batch('stop-renderer',['stop renderer'])
 (m.OUT/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
asyncio.run(main())
