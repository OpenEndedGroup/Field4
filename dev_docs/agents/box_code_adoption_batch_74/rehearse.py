"""Run actual native Loris recipes and cancel a real pending analysis."""
from pathlib import Path
import asyncio,importlib.util,json,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-74'
s=importlib.util.spec_from_file_location('rig74',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9723;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('docs74',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def main():
 page=(R/'public/docs/loris.md').read_text();states=[page[a:b] for a,b in d.sections(page)]
 # Keep all dependent fragments in this one real box; named analyzed is published by the first recipe.
 await m.macro('g2-loris-subject',[{'create':{'box':'g2-loris','frame':{'x':40.,'y':40.,'w':1000.,'h':100.},'properties':{'code':''}}}])
 for index,channel in [(0,'partials and sound'),(6,'low partials'),(2,'edited tracks and sound'),(4,'changed sound'),(7,'morph')]:
  body=states[index]
  # Record completion only after the actual result's visualizer is constructed.
  body=body.replace('    } catch (cancelled:', '        display("recipe done", "VERIFIED74 native recipe '+str(index)+' complete")\n    } catch (cancelled:',1)
  await m.macro('g2-loris-recipe-'+str(index),[{'execute':{'box':'g2-loris','type':'fragment','code':body}}])
  await m.batch('g2-loris-complete-'+str(index),[f'wait-output g2-loris --channel "recipe done" --contains "recipe {index} complete" --timeout 120'])
  await m.batch('g2-loris-visible-'+str(index),[f'wait-output g2-loris --channel "{channel}" --timeout 15'])
 code='''import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture
prop<CompletableFuture<LorisResult>>(pendingAnalysis)
val client = cached { LorisClient() }
val input = FloatArray(48000*60) { i -> (.15*kotlin.math.sin(i*.013)+.04*kotlin.math.sin(i*.029)).toFloat() }
go {
    pendingAnalysis=client.analyzeAsync(input,48000,options=LorisOptions(resolutionHz=25.0,windowWidthHz=50.0))
    val result=pendingAnalysis.await()
    display("late result","analysis finished before Stop")
}
'''
 await m.macro('g2-loris-stop-install',[{'create':{'box':'g2-loris-stop','properties':{'code':code}}},{'launch':{'box':'g2-loris-stop'}},{'await':{}}])
 pid=int(subprocess.check_output(['lsof','-nP','-iTCP:9723','-sTCP:LISTEN','-t'],text=True).strip().splitlines()[0])
 def children():
  ps=subprocess.check_output(['ps','-axo','pid=,ppid=,comm='],text=True)
  return [line.strip() for line in ps.splitlines() if len(line.split())>=3 and line.split()[1]==str(pid) and 'field_loris' in line]
 observed=[]
 for _ in range(30):
  observed=children()
  if observed:break
  await asyncio.sleep(.1)
 assert observed,'Real native Loris process did not start'
 await m.macro('g2-loris-stop',[{'stop':{'box':'g2-loris-stop'}},{'wait':{'frames':40}},{'execute':{'box':'g2-loris-stop','type':'fragment','code':'check(pendingAnalysis.isCancelled);println("VERIFIED74 Stop cancels native analysis future")'}}])
 assert not children(),'Native worker survived Stop'
 (H/'behavior.json').write_text(json.dumps({'actualNativeRecipes':[0,6,2,4,7],'typedAnalyzedPropertyWorksInFollowOn':True,'realNativeProcessObservedBeforeStop':observed,'nativeFutureCancelledAndProcessExited':True,'limits':'Actual committed dialogue input and actual native Loris executable. Stop uses a generated sixty-second tonal input to keep native analysis pending; no model output or public capture is substituted.'},indent=2)+'\n')
asyncio.run(main())
