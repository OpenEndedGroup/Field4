"""Exercise the authored reset wait and camera/object capture order without a renderer."""
from pathlib import Path
import importlib.util,asyncio,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig19',H/'native_checks.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.OUT=R/'build/box-code-revision/adoption-19/cpu';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 ops=yaml.safe_load((R/'dev_docs/agents/transcripts/flow_grain.yaml').read_text())['operations'];body=next(o['create']['properties']['code'] for o in ops if 'resetTimeoutNanos' in o.get('create',{}).get('properties',{}).get('code',''))
 a=body.index('val ticket = resetFlow.request()');b=body.index('println("Profile settings',a);wait=body[a:b]
 records=[]
 for mode in ['complete','failure','timeout']:
  injected=''
  if mode=='complete':injected='resetFlow.atFrame(1L) {}'
  if mode=='failure':injected='runCatching { resetFlow.atFrame(1L) { throw IllegalStateException("intentional reset failure") } }'
  snippet=wait.replace('val ticket = resetFlow.request()','val ticket = resetFlow.request()\n'+injected).replace('30_000_000_000L','50_000_000L')
  expected='Reset failed:' if mode=='failure' else 'Timed out waiting for reset'
  code='''val resetFlow=afield.runtime.lifecycle.SimulationReset()
frames {
 var outcome="complete"
 try {
'''+snippet+'''
 } catch (t:IllegalStateException) {
  check(t.message?.startsWith(EXPECTED)==true) { t.toString() }
  outcome="reported"
 } finally {
  check(outcome==OUTCOME) { outcome }
  display("proof","verified "+outcome)
 }
}
'''.replace('EXPECTED',json.dumps(expected)).replace('OUTCOME',json.dumps('complete' if mode=='complete' else 'reported'))
  await m.macro('reset_'+mode,[{'create':{'box':'subject','name':mode,'properties':{'code':code}}},{'launch':{'box':'subject'}},{'await':{}}],True)
  await m.batch('reset_'+mode+'_assert',['wait-output subject --channel proof --contains verified --timeout 10','stop subject','lifecycle-census subject --expect-stopped'])
  records.append({'resetWait':mode,'actualAuthoredWait':True,'deadlineSecondsForTest':0.05})
 text=(R/'public/docs/flow_grain.md').read_text();code=next(text[a:b] for a,b in n.d.sections(text) if 'val history = FrameHistory()' in text[a:b]);a=code.index('// Once per render');declarations=code[:a];step=code[a:].replace('renderer.frameCounter','serial')
 setup='''val viewProjection=Matrix4f().identity()
val width=960;val height=720
val sceneEpoch=Any();val geometryRevision=1
var serial=1L
'''
 code='import afield.math.linalg.Matrix4f\n'+declarations+setup+step+'\ncheck(!history.available && previous[4]==0f)\nserial=2\n'+step+'\ncheck(history.available && previous[4]==1f)\nserial=4\n'+step+'\ncheck(!history.available && previous[4]==0f)\nprintln("capture order: initial, consecutive and missed frames verified")'
 await m.program('capture_order',code);records.append({'captureOrder':'initial/consecutive/gap','cameraAndObjectValidityVerified':True})
 (m.OUT/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
