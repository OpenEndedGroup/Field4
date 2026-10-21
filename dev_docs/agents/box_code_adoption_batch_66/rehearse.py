"""Compile authored Kratos boxes and exercise actual Frame cancellation at a pending open."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-66'
s=importlib.util.spec_from_file_location('rig66workers',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9721;m.OUT=O/'workers';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(label,box,code):return await m.macro(label,[{'execute':{'box':box,'type':'fragment','code':'run {\n'+code+'\n}'}}])
def instrument(code,held):
 header='''prop<java.util.concurrent.ConcurrentLinkedQueue<String>>(proofEvents)
proofEvents=java.util.concurrent.ConcurrentLinkedQueue<String>()
'''
 # Imports must stay at the beginning; insert fixture state immediately before frames.
 a=code.index('frames {');code=code[:a]+header+code[a:]
 marker='} catch (cancelled: java.util.concurrent.CancellationException) {'
 assert marker in code;code=code.replace(marker,marker+'\n        proofEvents.add("cancelled")')
 a=code.rfind('} finally {');code=code[:a]+code[a:].replace('} finally {','} finally {\n        proofEvents.add("finally")',1)
 if held:
  a=code.index('val opening = KratosSession.openAsync(');b=code.index('(',a);depth=1;c=b+1
  while depth:
   depth+=(code[c]=='(')-(code[c]==')');c+=1
  code=code[:a]+'val opening = java.util.concurrent.CompletableFuture<afield.simulation.kratos.KratosSession>()'+code[c:]
 return code
async def main():
 results=[]
 for name in ['kratos_parameter_study','kratos_flow_playground','kratos_thermal_playground']:
  box=yaml.safe_load((R/'dev_docs/agents/transcripts'/(name+'.yaml')).read_text())['operations'][0]['create'];source=box['properties']['code']
  for held in [False,True]:
   if name=='kratos_parameter_study' and not held:
    results.append({'box':name,'heldOpening':False,'compiledInRealBox':True,'actualUnavailableWorkerCleanup':True,'retained':'workers/g1-kratos_parameter_study-unavailable-ready-0.json'});continue
   label='g1b-'+name+('-held' if held else '-unavailable');box['box']=label;box['properties']['code']=instrument(source,held)
   await m.macro(label+'-launch',[{'create':box},{'launch':{'box':label}},{'wait':{'frames':30}}])
   if held:
    await m.batch(label+'-stop',['stop '+label])
    await fragment(label+'-assert',label,'check(proofEvents.toList()==listOf("cancelled","finally")){proofEvents.toString()};println("Stop propagated cancellation and executed cleanup without worker-unavailable fallback")')
   else:
    # The actual optional worker is absent on this machine; inspect eventual, real failure cleanup.
    for attempt in range(20):
     tag='CLOSED:'+label+':'+str(attempt)+'='
     r=await fragment(label+'-ready-'+str(attempt),label,'println('+json.dumps(tag)+'+proofEvents.contains("finally"))')
     if any(o.get('html')==tag+'true' for x in r['results'] for o in x.get('data',{}).get('output',[])):break
     await m.macro(label+'-wait-'+str(attempt),[{'wait':{'frames':30}}])
    else:raise AssertionError('worker-unavailable path failed to clean up')
   results.append({'box':name,'heldOpening':held,'compiledInRealBox':True,'actualCancellationAndFinally':held,'actualUnavailableWorkerCleanup':not held})
 (H/'worker-behavior.json').write_text(json.dumps({'cases':results,'limits':'No local Kratos environment is installed and the Podman VM is stopped. Full solves/grid/search are not claimed. The complete authored bodies compile and execute the real unavailable-worker path; held-opening fixtures exercise real Frame cancellation, catch ordering and finally. No solver result, picture or simulation output is substituted.'},indent=2)+'\n')
asyncio.run(main())
