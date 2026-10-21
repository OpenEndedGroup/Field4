"""Run the actual cancellation callbacks and submission snapshot statements in a real box."""
from pathlib import Path
import asyncio,importlib.util,json,re,textwrap,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-66'
s=importlib.util.spec_from_file_location('rig66controls',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9721;m.OUT=O/'controls';m.OUT.mkdir(parents=True,exist_ok=True)
def code(name):return yaml.safe_load((R/'dev_docs/agents/transcripts'/(name+'.yaml')).read_text())['operations'][0]['create']['properties']['code']
study=code('kratos_parameter_study');callbacks=[]
for match in re.finditer(r'KratosStudyDemo\.study\(worker,\s*target\)\s*\{',study):
 a=match.end();b=a;depth=1
 while depth:
  depth+=(study[b]=='{')-(study[b]=='}');b+=1
 callbacks.append(study[a:b-1])
assert len(callbacks)==2
flow=code('kratos_flow_playground');a=flow.index('fun reynoldsOf');b=flow.index('status("Starting the optional worker',a);definitions=flow[a:b]
a=flow.index('val requested = values.toList()');b=flow.index('pending = worker.solveAsync',a);submission=textwrap.dedent(flow[a:b])
body='''import afield.simulation.kratos.*
val done=java.util.concurrent.atomic.AtomicInteger(0)
val cancelling=java.util.concurrent.atomic.AtomicBoolean(false)
val callbacks:List<()->Unit> = listOf(CALLBACKS)
for(callback in callbacks) {
    cancelling.set(false)
    callback()
    cancelling.set(true)
    val failure=runCatching {callback()}.exceptionOrNull()
    check(failure is java.util.concurrent.CancellationException && failure.message=="cancelled")
}
check(done.get()==4)
DEFINITIONS
val values=mutableListOf(0.30,1.0,0.05,3.0)
var submitted:List<Double>?=null
SUBMISSION
values[0]=0.45
values[1]=3.0
val solvedSettings=requireNotNull(submitted)
submitted=null
check(solvedSettings[0]==0.30 && solvedSettings[1]==1.0)
check(kotlin.math.abs(reynoldsOf(solvedSettings)-12.0)<1e-9)
check(kotlin.math.abs(reynoldsOf(solvedSettings)-reynoldsOf(values))>1.0)
val solvedBody=body(solvedSettings[0]) as SimulationGroup
check((solvedBody.children.first() as SimulationSphere).radius==0.30)
check(values[0]==0.45 && values[1]==3.0)
println("Both authored cancellation callbacks throw in Box context; submitted geometry and Reynolds retain A while preview settings become B")
'''.replace('CALLBACKS',',\n'.join('{'+c+'}' for c in callbacks)).replace('DEFINITIONS',definitions).replace('SUBMISSION',submission)
async def main():
 await m.macro('g1-source-controls-epsilon',[{'create':{'box':'g1-source-controls-epsilon','properties':{'code':body}}},{'execute':{'box':'g1-source-controls-epsilon'}}])
 (H/'source-controls-behavior.json').write_text(json.dumps({'authoredCancellationCallbacks':2,'throwInsideActualBoxReceiver':True,'normalCallbacksRemainUsable':True,'submittedGeometryAndReynoldsRetainSnapshotAfterPreviewEdit':True,'limits':'Executes extracted authored callbacks, snapshot statements, real FlowParameters and SimulationGeometry constructors. No Kratos solve, CFD result or capture is synthesized.'},indent=2)+'\n')
asyncio.run(main())
