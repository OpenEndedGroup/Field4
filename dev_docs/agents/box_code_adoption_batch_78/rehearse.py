"""Real ORCA page, Chromium console and cached ONNX worker lifetime acceptance."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-78'
s=importlib.util.spec_from_file_location('rig78',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9726;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def macro(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def box(name,body,parent=None):
 ops=[{'create':{'box':name,'properties':{'code':body}}}]
 if parent:ops.append({'connect':{'parent':parent,'child':name}})
 return await macro(name,ops+[{'launch':{'box':name}},{'await':{}}])
async def fragment(name,uid,code):return await macro(name,[{'execute':{'box':uid,'type':'fragment','code':code}}])
async def wait(name,uid,marker):return await m.batch(name,[f'wait-output {uid} --contains "{marker}" --timeout 30'])
async def main():
 await box('g3-owner78b','''import kotlinx.coroutines.future.await
import afield.audio.synth.Orca
import afield.media.browser.*
import java.util.concurrent.atomic.AtomicInteger
prop<Orca>(ownedOrca)
prop<HeadlessPuppetBrowser>(ownedBrowser)
prop<AtomicInteger>(oldNotes)
prop<AtomicInteger>(newNotes)
prop<AtomicInteger>(oldConsole)
prop<AtomicInteger>(newConsole)
prop<AtomicInteger>(oldCells)
prop<AtomicInteger>(newCells)
prop<MutableList<Thread>>(depthThreads)
oldNotes=AtomicInteger();newNotes=AtomicInteger();oldConsole=AtomicInteger();newConsole=AtomicInteger();oldCells=AtomicInteger();newCells=AtomicInteger();depthThreads=java.util.concurrent.CopyOnWriteArrayList<Thread>()
ownedOrca=orca(rows=12,grid="""
#.ORCA.IN.FIELD.#
.
.2C8
D2.8TCDEGAGED
.:04
.
D8
.:12C
""")
ownedBrowser=HeadlessPuppetBrowser("data:text/html,<title>Listener acceptance</title>")
requireNotNull(RunScope.current()).own(ownedBrowser)
go { ownedBrowser.loaded.await();check(ownedOrca.sync());println("OWNER_READY78") }
''')
 await wait('g3-owner-ready78','g3-owner78b','OWNER_READY78')
 handlers='''import afield.media.browser.addEventListener
val registeringScope=requireNotNull(RunScope.current())
ownedOrca.onNote("sound") { oldNotes.incrementAndGet() }
ownedOrca.onNote("sound") { check(RunScope.current()===registeringScope);newNotes.incrementAndGet() }
ownedOrca.onCell(1,1,"cell") { oldCells.incrementAndGet() }
ownedOrca.onCell(1,1,"cell") { newCells.incrementAndGet() }
ownedBrowser.addEventListener("console") { if(it.name=="console")oldConsole.incrementAndGet() }
ownedBrowser.addEventListener("console") { if(it.name=="console"){check(RunScope.current()===registeringScope);newConsole.incrementAndGet()} }
println("HANDLERS_READY78")'''
 await box('g3-handlers78',handlers,'g3-owner78b')
 await fragment('g3-fire-real-events78','g3-owner78b','''go {
 ownedBrowser.evaluate("console.log('real console acceptance 78')").await()
 ownedOrca[1,1]='A';check(ownedOrca.sync())
 repeat(100) { if(newNotes.get()>0 && newConsole.get()>0 && newCells.get()>0)return@repeat;delay(50) }
 check(oldNotes.get()==0 && oldConsole.get()==0 && oldCells.get()==0)
 check(newNotes.get()>0 && newConsole.get()>0 && newCells.get()>0)
 println("REAL_CALLBACKS_REPLACED78")
}''')
 await wait('g3-replaced78','g3-owner78b','REAL_CALLBACKS_REPLACED78')
 await macro('g3-stop-handlers78',[{'stop':{'box':'g3-handlers78'}}])
 await fragment('g3-after-handler-stop78','g3-owner78b','''go {
 val before=listOf(newNotes.get(),newConsole.get(),newCells.get())
 ownedBrowser.evaluate("console.log('after handler Stop 78')").await()
 ownedOrca[1,1]='B';check(ownedOrca.sync());delay(600)
 check(before==listOf(newNotes.get(),newConsole.get(),newCells.get()))
 println("STOP_DETACHED_REAL_CALLBACKS78")
}''')
 await wait('g3-detached78','g3-owner78b','STOP_DETACHED_REAL_CALLBACKS78')
 await box('g3-depth78','''import afield.ai.inference.onnx.*
check(DepthAnything.FP32.isPresent)
val depth=DepthAnythingWorker(width=28,height=28)
val workerThread=DepthAnythingWorker::class.java.getDeclaredField("thread").also {it.isAccessible=true}.get(depth) as Thread
depthThreads.add(workerThread);check(workerThread.isAlive);println("DEPTH_RUNNING78")''','g3-owner78b')
 await macro('g3-depth-stop78',[{'stop':{'box':'g3-depth78'}}])
 await fragment('g3-depth-stopped-proof78','g3-owner78b','check(depthThreads.size==1 && !depthThreads[0].isAlive);println("DEPTH_STOPPED78")')
 await macro('g3-depth-rerun78',[{'launch':{'box':'g3-depth78'}},{'await':{}}])
 await macro('g3-depth-rerun-stop78',[{'stop':{'box':'g3-depth78'}}])
 await fragment('g3-depth-rerun-proof78','g3-owner78b','check(depthThreads.size==2 && depthThreads.none {it.isAlive});println("DEPTH_RERUN_STOPPED78")')
 (H/'behavior.json').write_text(json.dumps({'realOrcaNotesAndCells':True,'realChromiumConsole':True,'sameNameReplacedCallbacks':True,'callbacksRetainedRegisteringRunContext':True,'stopDetachedCallbacksWhileSourcesKeptRunning':True,'cachedModelWorkerThreadStoppedAcrossTwoRuns':True,'inferenceClaimed':False},indent=2)+'\n')
asyncio.run(main())
