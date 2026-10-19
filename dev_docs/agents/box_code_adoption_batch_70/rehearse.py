"""Exercise complete authored reduction, renderer inheritance and convergence controls."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-70'
s=importlib.util.spec_from_file_location('rig70',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9722;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
def code(name,box):return next(x['create']['properties']['code'] for x in yaml.safe_load((R/f'dev_docs/agents/transcripts/{name}.yaml').read_text())['operations'] if x.get('create',{}).get('box')==box)
async def cached(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def run(name,body,parent=False):
 ops=[{'create':{'box':name,'frame':{'x':40.,'y':40.,'w':1200.,'h':100.},'properties':{'code':body}}}]
 if parent:ops.append({'connect':{'parent':'renderer','child':name}})
 ops.extend([{'launch':{'box':name}},{'await':{}}])
 result=await cached(name,ops)
 # Macro application alone is insufficient: require the actual assertion marker.
 output=[v.get('html','') for r in result['results'] for v in r.get('data',{}).get('output',[])]
 assert any('VERIFIED70' in x for x in output),(name,output)
async def main():
 body=code('interaction_nets_lambda','lambda_reduction')
 for label,source in [('identity',None),('shared',r'(\\f. \\y. f (f y)) ((\\x. x) (\\x. x))')]:
  current=body if source is None else body.replace(r'(\\x. x x) (\\y. y)',source,1)
  await run('g5-stages-bounded-'+label,current+'\ncheck(LambdaTerms.alphaEquivalent(readback,oracle))\ncheck(stages.isNotEmpty())\n'+'check(reducer.stepDetailed(compiled.net)==null)\n'+'println("VERIFIED70 real reduction and oracle agreement")')
 if not (m.OUT/'stages.png').exists():await m.batch('g5-stages-capture',['wait-output g5-stages-bounded-identity --channel reduction --timeout 30',f'screenshot-box g5-stages-bounded-identity {m.OUT}/stages.png'])
 body=(R/'public/docs/assets/opendlssnr/example.kt').read_text()
 # Mac does not expose NVIDIA NR; run the actual availability branch against the inherited renderer.
 await run('g5-inherited-renderer',body+'\ncheck(!available.ready)\nprintln("VERIFIED70 inherited renderer and actual unsupported-NR branch")',True)
 body=code('polytope_path_trace_lineage','polytope_lineage_trace').replace('1000, 614','96, 64').replace('val targetSamples = 900','val targetSamples = 4').replace('tracer.blitToScreen()','// Private offscreen convergence rehearsal.').replace('println("lineage study converged: ${tracer.samples} samples")','''check(tracer.samples >= targetSamples)
        check(tracer.samplesPerFrame == samplesPerFrame && tracer.maxBounces == maxBounces)
        check(tracer.exposure == exposure && tracer.environmentRotation == environmentRotation)
        println("VERIFIED70 actual path tracer convergence ${tracer.samples} samples")
        tracer.close()''')
 # Completion is asynchronous: require the live result rather than only launch application.
 ops=[{'create':{'box':'g5-lineage-controls','properties':{'code':body}}},{'connect':{'parent':'renderer','child':'g5-lineage-controls'}},{'launch':{'box':'g5-lineage-controls'}},{'await':{}},{'wait':{'frames':80}}]
 result=await cached('g5-lineage-controls',ops)
 assert any('VERIFIED70 actual path tracer convergence' in out.get('html','') for r in result['results'] for out in r.get('data',{}).get('output',[]))
 (H/'behavior.json').write_text(json.dumps({'twoActualTermsAgreeWithOracle':True,'bothTermsReducedToCompletion':True,'realStageOutputCaptured':True,'inheritedRendererActualNrAvailability':True,'actualPathTracerOffscreenConvergence':True,'limits':'Real authored path-tracer scene at 96×64 with four target samples. NVIDIA NR image processing is unavailable on this macOS host; only its real availability branch was exercised. No public captures changed.'},indent=2)+'\n')
asyncio.run(main())
