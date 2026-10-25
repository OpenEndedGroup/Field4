"""Compare camera layout and corrected specular lighting on real source-mode GPU frames."""
from pathlib import Path
import asyncio,importlib.util,json,re,yaml
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig63',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9721;m.OUT=R/'build/box-code-revision/adoption-63/runtime/settled-v4';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(label,code):return await m.macro(label,[{'execute':{'box':'cube-camera-proof-v4','type':'fragment','code':'run {\n'+code+'\n}'}}])
async def save(label,time):
 path=m.OUT/(label+'.png')
 if '--resume' in __import__('sys').argv and path.exists():return path
 path.unlink(missing_ok=True)
 await fragment(label+'-clock',f'cubeStudy.time={time}f;println("clock '+label+' set")')
 await m.batch(label+'-settle',['wait-native-renderer renderer --frames 12 --timeout 60'])
 await fragment(label+'-request','savedFrame=cubeStudy.saveFrame(java.nio.file.Path.of('+json.dumps(str(path))+'))')
 for attempt in range(30):
  await m.batch(label+'-frames-'+str(attempt),['wait-native-renderer renderer --frames 12 --timeout 60'])
  tag=f'READY:{label}:{attempt}='
  result=await fragment(label+'-ready-'+str(attempt),'println('+json.dumps(tag)+'+savedFrame.isDone)')
  if any(o.get('html')==tag+'true' for r in result['results'] for o in r.get('data',{}).get('output',[])):break
 else:raise AssertionError('actual readback timed out')
 assert path.is_file() and path.stat().st_size>0,'readback file missing after completion'

 return path
def equal(a,b):return ImageChops.difference(Image.open(a).convert('RGB'),Image.open(b).convert('RGB')).getbbox() is None
async def reload(label,vert,frag):
 a=m.OUT/(label+'.vert');b=m.OUT/(label+'.frag');a.write_text(vert);b.write_text(frag)
 if label=='named-camera-layout':
  await fragment(label+'-new-interface','cubeStudy.close();cubeStudy=afield.graphics.render.neural.OpenDlssNrStudy(renderer,size=256,name="camera-proof",geometry=true,vertex=java.nio.file.Files.readString(java.nio.file.Path.of('+json.dumps(str(a))+')),fragment=java.nio.file.Files.readString(java.nio.file.Path.of('+json.dumps(str(b))+')));cubeStudy.animate=false;println("full run installs matching new vertex/fragment interface")')
 else:
  await fragment(label+'-reload','cubeStudy.reload(vertex=java.nio.file.Files.readString(java.nio.file.Path.of('+json.dumps(str(a))+')),fragment=java.nio.file.Files.readString(java.nio.file.Path.of('+json.dumps(str(b))+'))).getOrThrow();println("compatible shader reload succeeded")')

async def main():
 rows=json.loads((H/'changes.json').read_text());oldv=next(x['before'] for x in rows if x['path']=='public/docs/assets/opendlssnr/cubes.vert');oldf=next(x['before'] for x in rows if x['path']=='public/docs/assets/opendlssnr/cubes.frag');newv=(R/'public/docs/assets/opendlssnr/cubes.vert').read_text();newf=(R/'public/docs/assets/opendlssnr/cubes.frag').read_text()
 if '--new-host' in __import__('sys').argv:
  owner=yaml.safe_load((R/'dev_docs/agents/transcripts/docs_capture/first_graphics_native.yaml').read_text())['operations'][0]['create'];owner['box']='renderer';owner['properties']['code']='import afield.graphics.vulkan.util.Options\nOptions.enableVR=false\nOptions.enableHDR=false\n'+owner['properties']['code']
  await m.macro('cube-camera-native-owner',[{'create':owner},{'launch':{'box':'renderer'}},{'await':{}}])
 # Freeze scene and light, while the actual camera continues to orbit with the study clock.
 def freezeV(v):return re.sub(r'(vec3 objectPoint\([^)]*\)\s*\{)',r'\1\n    time = 0;',v,count=1)
 def freezeF(f):return f.replace('sin(pc.times.x)*4','sin(0.0)*4').replace('sin(pc.times.x) * 4','sin(0.0) * 4')
 oldv=freezeV(oldv);newv=freezeV(newv);oldf=freezeF(oldf);newf=freezeF(newf)
 initialV=m.OUT/'initial.vert';initialF=m.OUT/'initial.frag';initialV.write_text(newv if '--reference-only' in __import__('sys').argv else oldv);initialF.write_text(newf if '--reference-only' in __import__('sys').argv else oldf)
 code='''import afield.graphics.render.neural.*
prop<OpenDlssNrStudy>(cubeStudy)
prop<java.util.concurrent.CompletableFuture<java.nio.file.Path>>(savedFrame)
cubeStudy=OpenDlssNrStudy(renderer,size=256,name="camera-proof",geometry=true,vertex=java.nio.file.Files.readString(java.nio.file.Path.of(VERT)),fragment=java.nio.file.Files.readString(java.nio.file.Path.of(FRAG)))
cubeStudy.animate=false
println("actual source-mode cube camera fixture")'''.replace('VERT',json.dumps(str(initialV))).replace('FRAG',json.dumps(str(initialF)))
 if '--resume' not in __import__('sys').argv or '--reference-only' in __import__('sys').argv:await m.macro('cube-camera-create-v4',[{'create':{'box':'cube-camera-proof-v4','properties':{'code':code}}},{'connect':{'parent':'renderer','child':'cube-camera-proof-v4'}},{'execute':{'box':'cube-camera-proof-v4'}}])
 before={t:(m.OUT/('original-'+str(t)+'.png') if '--resume' in __import__('sys').argv and (m.OUT/('original-'+str(t)+'.png')).exists() else await save('original-'+str(t),t)) for t in [0,3,6]}
 # Match the added stage interface but retain original lighting to isolate project() layout.
 layoutF=oldf.replace('layout(location=4) flat in int objectId;','layout(location=4) flat in int objectId;\nlayout(location=5) flat in vec3 cameraEye;')
 if '--resume' not in __import__('sys').argv:await reload('named-camera-layout',newv,layoutF)
 layoutDiffs={}
 for t in [0,3,6]:
  image=await save('layout-'+str(t),t)
  d=ImageChops.difference(Image.open(before[t]).convert('RGB'),Image.open(image).convert('RGB'))
  changed=sum(any(c) for c in d.getdata());layoutDiffs[t]=changed
  assert changed<=32,('projection layout changed more than isolated raster-edge pixels',t,changed)
 if not ('--resume' in __import__('sys').argv and all((m.OUT/('corrected-'+str(t)+'.png')).exists() for t in [0,3,6])):await reload('camera-correct-lighting',newv,newf)
 corrected={t:await save('corrected-'+str(t),t) for t in [0,3,6]}
 assert equal(m.OUT/'layout-0.png',corrected[0]),'time-zero eye must agree'
 assert not equal(before[3],corrected[3]) and not equal(before[6],corrected[6]),'orbit must change specular view direction'
 # Independently calculate the same projected eye in the fragment, instead of using a varying.
 marker='vec3 viewDirection = normalize(cameraEye - world);';assert marker in newf
 reference=newf.replace(marker,'''float cameraAngle = pc.settings.y == 1 ? pc.times.x * .18 : 0;
    vec3 referenceEye = vec3(5*sin(cameraAngle)+5*cos(cameraAngle),3.7,5*cos(cameraAngle)-5*sin(cameraAngle));
    vec3 viewDirection = normalize(referenceEye - world);''')
 if not ('--resume' in __import__('sys').argv and (m.OUT/'reference-0.png').exists()):await reload('independent-camera-reference',newv,reference)
 referenceDiffs={}
 for t in [0,3,6]:
  image=await save('reference-'+str(t),t)
  d=ImageChops.difference(Image.open(corrected[t]).convert('RGB'),Image.open(image).convert('RGB'))
  referenceDiffs[t]={'changedPixels':sum(any(c) for c in d.getdata()),'maxChannelDelta':max(v[1] for v in d.getextrema())}
  assert referenceDiffs[t]['maxChannelDelta']<=1,('independent camera differs beyond 8-bit rounding',t,referenceDiffs[t])
 await fragment('cube-camera-close','cubeStudy.close()')
 (H/'behavior.json').write_text(json.dumps({'actualNativeSourceMode':True,'fixedGeometryAndLightCameraOrbitTimes':[0,3,6],'namedProjectionLayoutChangedRasterEdgePixels':layoutDiffs,'lightingUnchangedAtTimeZero':True,'orbitLightingUsesActualEye':True,'flatEyeVersusIndependentFragmentCamera':referenceDiffs,'limits':'256-pixel source-mode study on the existing private Vulkan renderer. Fixture freezes object/light animation to isolate camera motion. No NVIDIA NR claim or public media replacement.'},indent=2)+'\n')
asyncio.run(main())
