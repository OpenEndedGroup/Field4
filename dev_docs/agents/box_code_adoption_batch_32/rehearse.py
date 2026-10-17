"""Exercise the actual polytope shaders and compile/execute the actual KotlinJS sum."""
import asyncio,importlib.util,json,sys,yaml,re,subprocess,os
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig32',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(os.environ.get('FIELD_TEST_PORT','9701'))
m.OUT=R/'build/box-code-revision/adoption-32/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,code):
 return await m.macro(name,[{'execute':{'box':'polytope','property':'code','type':'fragment','code':code}}])
async def main():
 rows=json.loads((H/'changes.json').read_text());poly=rows[0]
 folder=R/'dev_docs/agents/demos/stackauto_conversation'
 if '--js-only' not in sys.argv:
  for label in ['before','after']:
   props={'code':poly['before'] if label=='before' else (R/poly['path']).read_text(),'vertex':(folder/'polytope_source_vertex_.glsl').read_text(),'fragment':(folder/'polytope_source_fragment_.glsl').read_text()}
   if label=='before':
    await m.macro('polytope-'+label,[{'create':{'box':'renderer','properties':{'code':(folder/'polytope_renderer_code_.kt').read_text()}}},{'create':{'box':'polytope','properties':props}},{'connect':{'parent':'renderer','child':'polytope'}},{'launch':{'box':'renderer'}},{'await':{}},{'launch':{'box':'polytope'}},{'await':{}}],True)
   else:
    await m.macro('polytope-hot-edit',[{'edit':{'box':'polytope','property':'code','from':poly['before'],'to':props['code']}},{'launch':{'box':'polytope'}},{'await':{}}])
   from PIL import Image
   visible=False
   for attempt in range(6):
    png=m.OUT/f'polytope-{label}-{attempt}.png'
    await m.batch(label+f'-frames-{attempt}',['wait-native-renderer renderer --frames 30 --timeout 60',f'native-screenshot {png} --uid renderer --wait-frames 4 --timeout 60'])
    image=Image.open(png).convert('RGB')
    if sum(max(pixel)>30 for pixel in image.getdata())>1000:
     (m.OUT/f'polytope-{label}.png').write_bytes(png.read_bytes());visible=True;break
   assert visible,('native polytope stayed empty',label)
 
   await fragment(label+'-clock-start','val time0 = m.params().x;println("'+label+' initial time $time0")')
   await m.batch(label+'-advance',['wait-native-renderer renderer --frames 20 --timeout 60'])
   await fragment(label+'-clock-advances','check(m.params().x > time0);println("'+label+' push time advances")')
 
 if '--native-only' in sys.argv:
  await cleanup()
  return
 # The source is a CPU math box: compile it through Field's real KotlinJS compiler,
 # then execute the emitted program in Node with its real console.
 jsrow=rows[1];files=[]
 for label,text in [('before',jsrow['before']),('after',(R/jsrow['path']).read_text())]:
  code=next(o['create']['properties']['code'] for o in yaml.safe_load(text)['operations'] if o.get('create',{}).get('box')=='kjs_math')
  p=m.OUT/f'sum-{label}.kt';p.write_text(code);files.append(str(p))
 compiler='''val owner=thisBox
val compileJob=java.util.concurrent.CompletableFuture.runAsync {
    for ((index,path) in PATHS.withIndex()) {
        val result=afield.lang.remotejs.KotlinToJsCompiler.compile(java.io.File(path).readText(),moduleName="readable_sum_$index")
        check(result.success && result.js != null) { result.diagnostics.toString() }
        java.io.File(path+".js").writeText(requireNotNull(result.js))
    }
}.whenComplete { _,failure -> owner.display("compiled",failure?.toString()?:"compiled both actual math boxes") }
'''.replace('PATHS','listOf('+','.join(json.dumps(f) for f in files)+')')
 await m.macro('kotlinjs-compile',[{'create':{'box':'subject','properties':{'code':compiler}}},{'execute':{'box':'subject'}}])
 await m.batch('kotlinjs-compiled',['wait-output subject --channel compiled --timeout 180'])
 await m.macro('kotlinjs-verdict',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'compileJob.get();println("both actual boxes compiled to JavaScript")'}}])
 await finish()
async def finish():
 node=os.environ.get('FIELD_TEST_NODE','node')
 outputs=[]
 files=[m.OUT/'sum-before.kt',m.OUT/'sum-after.kt']
 for file in files:
  p=subprocess.run([node,str(file)+'.js'],cwd=R,capture_output=True,text=True)
  Path(str(file)+'.stderr').write_text(p.stderr)
  Path(str(file)+'.stdout').write_text(p.stdout)
  assert p.returncode==0,(p.returncode,p.stderr)
  outputs.append(p.stdout)
 assert outputs[0].split(' perimeter=')[0] == outputs[1].split(' perimeter=')[0],outputs
 import math
 measured=float(outputs[1].split(' perimeter=')[1])
 expected=2*32*10*math.sin(math.pi/32)
 assert abs(measured-expected)<1e-10,(measured,expected)
 (m.OUT/'js-verification.json').write_text(json.dumps({'before':outputs[0],'after':outputs[1],'vectorSumEqual':True,'closedPerimeter':measured,'referencePerimeter':expected,'runtime':node,'connectedHead':False},indent=2)+'\n')
 if '--js-only' not in sys.argv: await cleanup()
async def cleanup():
 await fragment('native-cleanup','renderer.renderList.modules.entries.removeIf { it.value === module };module.current.cleanup();println("private module released before renderer")')
 await m.batch('native-stop',['stop polytope','stop renderer'])
if __name__ == '__main__':asyncio.run(finish() if '--finish' in sys.argv else main())

