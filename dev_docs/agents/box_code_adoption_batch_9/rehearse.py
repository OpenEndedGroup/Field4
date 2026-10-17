"""Exercise actual reduction/landing code, renderer-free Frames and queued-request freshness."""
from pathlib import Path
import sys,importlib.util,asyncio,json,yaml
ROOT=Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'dev_docs/agents/box_code_adoption_batch_3'))
spec=importlib.util.spec_from_file_location('prior',ROOT/'dev_docs/agents/box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.ROOT=ROOT;m.OUT=ROOT/'build/box-code-revision/adoption-9/runtime';m.OUT.mkdir(parents=True,exist_ok=True);m.PORT=9700
async def main():
 path=ROOT/'dev_docs/agents/transcripts/confluence_grid.yaml';code=yaml.safe_load(path.read_text())['operations'][0]['create']['properties']['code']
 probe='''
check(cells.size == 20)
check(cells.all { c -> c.done && c.landing == landingFrames && c.pose.all { (id,p) -> c.pos[id]==p && c.vel[id]==vec(0,0,0) } })
val settled = cells.map { it.positionedGraph() }
repeat(10) { cells.forEach { it.physics(); it.land() } }
check(settled == cells.map { it.positionedGraph() })
terms.forEachIndexed { row, term ->
 val oracle = LambdaTerms.deBruijnKey(LambdaTerms.referenceNormalOrder(LambdaTerms.parse(term.second)))
 check(cells.filter { it.row==row }.all { LambdaTerms.deBruijnKey(LambdaTerms.readback(it.compiled))==oracle })
}
proof(settled)
'''
 await m.program('confluence_landing',code,probe,channel='confluence3d',capture=True)
 await m.macro('frames_clock_setup',[{'create':{'box':'subject','name':'Renderer-free Frames','frame':{'x':0.,'y':0.,'w':400.,'h':100.},'properties':{'code':'frames { var ticks=0; while(frame()) { ticks++; if(ticks==5) display("clock", "five renderer-free ticks") } }'}}}],True)
 await m.batch('frames_clock_live',['launch subject','wait-output subject --channel clock --contains five --timeout 15','stop subject','lifecycle-census subject --expect-stopped'])
 source=yaml.safe_load((ROOT/'dev_docs/agents/transcripts/kratos_structural_playground.yaml').read_text())['operations'][0]['create']['properties']['code']
 cls=source[source.index('data class SolveRequest('):source.index('data class PendingSolve(')]
 functions=source[source.index('fun request(values:'):source.index('var resultText =')]
 code='import java.util.concurrent.atomic.AtomicReference\n'+cls+'\nval settings=AtomicReference(listOf(0.24,0.025,2.0,3.0,300.0))\nval action=AtomicReference("")\nvar requestEpoch=0L\n'+functions
 probe='''
val first=request(settings.get(),1)
val second=first.copy(order=2)
check(first.comparisonKey==second.comparisonKey && first.meshKey!=second.meshKey)
check(isCurrent(first) && isCurrent(second))
for (index in listOf(0,1,3)) {
 val prior=settings.get()
 settings.set(prior.mapIndexed { i,v -> if(i==index) v+1.0 else v })
 check(!isCurrent(first) && !isCurrent(second))
 settings.set(prior)
}
settings.set(settings.get().mapIndexed { i,v -> if(i==4) v+100 else v })
check(isCurrent(first))
action.set("cancel")
check(!isCurrent(first))
action.set("")
requestEpoch++
check(!isCurrent(first))
proof(listOf(first,second))
'''
 await m.program('request_freshness',code,probe)
if __name__=='__main__':asyncio.run(main())
