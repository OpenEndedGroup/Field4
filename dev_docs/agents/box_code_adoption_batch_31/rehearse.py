"""Publish real SharedLines buffers and exercise the authored native overlays."""
import asyncio,importlib.util,json,sys,yaml
from pathlib import Path
from PIL import Image
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig31',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
m.OUT=R/'build/box-code-revision/adoption-31/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):
 return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
def green_rows(path):
 im=Image.open(path).convert('RGB');x=im.width//2
 rows=[y for y in range(im.height) if (lambda c:c[1]>150 and c[1]>c[0]+40 and c[1]>c[2]+40)(im.getpixel((x,y)))]
 return im.width,im.height,rows
async def main():
 records=[]
 for name,alias,width in [('shared_memory','cv_overlay_lines',0.004),('webcam_demo','cv_overlay',0.003)]:
  doc=yaml.safe_load((R/f'python/fieldcv/examples/{name}.yaml').read_text())
  overlay=next(o['create'] for o in doc['operations'] if o.get('create',{}).get('box')==alias)
  renderer=yaml.safe_load((R/'python/fieldcv/examples/webcam_demo.yaml').read_text())['operations'][0]['create'];renderer['box']='renderer'
  publisher='''
import afield.lang.python.NumpyShared
val names = listOf("tracks","skeleton","hands","face","contours","strokes")
fun clearLines() { names.forEach { NumpyShared.lines(it).publish(0,0) } }
fun publishTrack(y: Float) {
    val lines=NumpyShared.lines("tracks")
    lines.reserve(2,1)
    val points=lines.points().data
    points.put(0,0.2f);points.put(1,y);points.put(2,0.8f);points.put(3,y)
    lines.counts().data.put(0,2)
    lines.publish(2,1)
}
clearLines()
'''
  await m.macro(name+'-install',[{'create':renderer},{'create':{'box':'publisher','properties':{'code':publisher}}},{'create':overlay},{'connect':{'parent':'renderer','child':alias}},{'execute':{'box':'publisher'}},{'launch':{'box':'renderer'}},{'await':{}},{'launch':{'box':alias}},{'await':{}}],True)
  for phase,action,expected in [('initial-empty','clearLines()',0),('line','publishTrack(0.5f)',4),('cleared','clearLines()',0),('resumed','publishTrack(0.25f)',4)]:
   await fragment(name+'-'+phase+'-publish','publisher',action)
   png=m.OUT/f'{name}-{phase}.png'
   await m.batch(name+'-'+phase+'-frames',[f'wait-native-renderer renderer --frames 20 --timeout 60',f'native-screenshot {png} --uid renderer --wait-frames 4 --timeout 60'])
   await fragment(name+'-'+phase+'-counts',alias,f'check(m.numVert == {expected} && m.numTriangle == {2 if expected else 0}) {{ m.toString() }};println("overlay {phase} counts verified")')
   w,h,rows=green_rows(png)
   if expected:assert abs(len(rows)-width*h)<=2,(name,phase,len(rows),width*h)
   else:assert not rows,(name,phase,rows)
   records.append({'source':name,'phase':phase,'vertices':expected,'image':[w,h],'greenRows':rows})
  await m.batch(name+'-rerun',[f'launch {alias}','wait-native-renderer renderer --frames 20 --timeout 60'])
  await fragment(name+'-rerun-counts',alias,'check(m.numVert == 4 && m.numTriangle == 2);println("overlay rerun consumes published line")')
  await m.batch(name+'-stop',[f'stop {alias}','stop renderer'])
 (m.OUT/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
asyncio.run(main())
