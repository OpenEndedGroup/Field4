"""Exercise the authored background block with a blocking dependency, without a solver stand-in capture."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-66'
s=importlib.util.spec_from_file_location('rig66order',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9721;m.OUT=O/'order';m.OUT.mkdir(parents=True,exist_ok=True)
source=yaml.safe_load((R/'dev_docs/agents/transcripts/kratos_parameter_study.yaml').read_text())['operations'][0]['create']['properties']['code'];a=source.index('CompletableFuture.supplyAsync<StudyOutcome>');b=source.index('{',a);depth=1;c=b+1
while depth:
 depth+=(source[c]=='{')-(source[c]=='}');c+=1
block=source[a:c]
code='''import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
prop<java.util.concurrent.ConcurrentLinkedQueue<String>>(proofEvents)
proofEvents=java.util.concurrent.ConcurrentLinkedQueue<String>()
class TestWinner(val coordinates:DoubleArray=doubleArrayOf(0.0))
class TestLandscape(val best:TestWinner=TestWinner())
class TestOptimisation(val best:TestWinner=TestWinner())
class StudyOutcome(val landscape:TestLandscape?,val optimisation:TestOptimisation?,val winningField:Int?)
class BlockingDependency(val caller:Thread,val entered:CountDownLatch,val release:CountDownLatch) {
    fun grid(nx:Int,ny:Int)=TestLandscape()
    fun optimise(steps:Int,seed:Long)=TestOptimisation()
    fun resultAt(coordinates:DoubleArray):Int {
        check(Thread.currentThread()!==caller) {"blocking field retrieval ran on the box thread"}
        entered.countDown()
        check(release.await(10,TimeUnit.SECONDS))
        return 7
    }
}
val entered=CountDownLatch(1)
val release=CountDownLatch(1)
val active=BlockingDependency(Thread.currentThread(),entered,release)
val cancelling=AtomicBoolean(false)
val command="grid"
val n=3
val running=BLOCK
frames {
    try {
        while(entered.count>0) frame()
        repeat(3) {frame()}
        check(!running.isDone) {"dependency did not remain blocked while frames advanced"}
        release.countDown()
        while(!running.isDone) frame()
        check(running.get().winningField==7)
        proofEvents.add("background field retrieval; main frames continued")
    } finally {release.countDown()}
}
'''.replace('BLOCK',block)
async def main():
 await m.macro('g1-background-order',[{'create':{'box':'g1-background-order','properties':{'code':code}}},{'launch':{'box':'g1-background-order'}},{'wait':{'frames':30}}])
 await m.macro('g1-background-order-assert',[{'execute':{'box':'g1-background-order','type':'fragment','code':'check(proofEvents.toList()==listOf("background field retrieval; main frames continued"));println("actual authored async block keeps Runloop frames moving while its dependency blocks")'}}])
 (H/'async-order-behavior.json').write_text(json.dumps({'authoredSupplyAsyncBlockExecutedVerbatim':True,'blockingFieldRetrievalOffBoxThread':True,'threeFramesAdvanceWhileDependencyBlocks':True,'resultOnlyDeliveredAfterRelease':True,'limits':'Controlled latch-based dependency verifies scheduling of the actual authored future block. It does not emulate Kratos numerics or generate a capture.'},indent=2)+'\n')
asyncio.run(main())
