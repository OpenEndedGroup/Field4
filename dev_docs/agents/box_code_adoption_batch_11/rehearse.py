"""Compile edited wrappers in their real saved graph, then test actual compute boundaries."""
from pathlib import Path
import sys,importlib.util,asyncio,json,shutil
ROOT=Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'dev_docs/agents/box_code_adoption_batch_3'))
spec=importlib.util.spec_from_file_location('prior',ROOT/'dev_docs/agents/box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.ROOT=ROOT;m.OUT=ROOT/'build/box-code-revision/adoption-11/runtime';m.OUT.mkdir(parents=True,exist_ok=True);m.PORT=9700
ASSET=ROOT/'public/docs/assets/canonical_graphics'
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
async def saved_graph():
 await m.program('refresh_canonical',f'''
val directory={json.dumps(str(ASSET))}
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
val snapshots=saving.readInlineFileSnapshots(directory)
val excluded=snapshots.groupBy {{ it.boxId }}.mapValues {{ (_,v) -> v.map {{ it.property }}.toSet() }}
println("previously different fallbacks: "+snapshots.filter {{ it.inline!=java.io.File(directory,it.path).readText() }}.map {{ it.path }})
saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
check(before.map {{ it.id }}==after.map {{ it.id }})
check(before.map {{ it.properties-(excluded[it.id]?:emptySet<String>()) }}==after.map {{ it.properties-(excluded[it.id]?:emptySet<String>()) }})
check(saving.readInlineFileSnapshots(directory).all {{ it.inline==java.io.File(directory,it.path).readText() }})
println("canonical graph and non-sidecar properties preserved")
''')
 copy=m.OUT/'canonical-copy';shutil.copytree(ASSET,copy,dirs_exist_ok=True)
 await m.program('saved_wrapper_compilation',f'''
val saving=afield.document.persistence.SavingContext()
val prior=afield.app.server.thisSessionInfo.get()
val boxes=try {{
 afield.app.server.thisSessionInfo.set(thisBox.inside)
 saving.load({json.dumps(str(copy))},upgradeOnLoad=false,initIfCreated=false)
}} finally {{ afield.app.server.thisSessionInfo.set(prior) }}
val selected=listOf("611072da-1af6-4d2d-85bf-07a9ba191aca","ad857eaa-362e-45ea-bdc8-472af40f1ed2","262e80f5-d991-453d-9af8-8d181ff0eb0b","8323570b-87db-4623-95a2-bd61c7d0f6f7")
val smoke=afield.examples.DemoTranscriptExecSmokeTest
val compile=smoke.javaClass.getDeclaredMethod("compileBoxOnly",afield.document.graph.Box::class.java)
compile.isAccessible=true
val compiled=java.util.concurrent.CompletableFuture.runAsync {{
 for(id in selected) {{
  val target=boxes.single {{ it.id==id }}
  val errors=compile.invoke(smoke,target) as List<*>
  check(errors.isEmpty()) {{ "$id: $errors" }}
  println("compiled wrapper: $id")
 }}
}}.whenComplete {{ _,failure -> display("compiled",failure?.toString()?:"all four wrappers compiled") }}
''')
 await m.batch('saved_wrapper_wait',['wait-output subject --channel compiled --timeout 180'])
 await fragment('saved_wrapper_verdict','subject','compiled.get(); println("four real saved wrappers compiled")')
async def main():
 await saved_graph()
if __name__=='__main__':asyncio.run(main())
