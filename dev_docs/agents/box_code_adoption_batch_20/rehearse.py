"""Compile full edited bodies in their real graph/import context; verify the phase schedule."""
from pathlib import Path
import sys,importlib.util,asyncio,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig20',H.parent/'box_code_adoption_batch_18/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.OUT=R/'build/box-code-revision/adoption-20/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 rows=json.loads((H/'changes.json').read_text());paths=[]
 scaffold=yaml.safe_load((R/'public/docs/assets/opendlssnr/open_dlss_nr.yaml').read_text())['operations'][0]['create'];scaffold=dict(scaffold);scaffold['box']='shader_parent'
 for i,r in enumerate(rows):
  if r['path'].endswith('.yaml'):continue
  if 'rp_bundle' in r['path']:continue # Compiled with its actual build graph below.
  p=m.OUT/f'body-{i}.yaml';p.write_text(yaml.safe_dump({'operations':[{'create':scaffold},{'create':{'box':'source','properties':{'code':(R/r['path']).read_text()}}},{'connect':{'parent':'shader_parent','child':'source'}}]},sort_keys=False));paths.append(str(p))
 paths.append(str(R/'dev_docs/agents/demos/frame_history_reprojection/build.yaml'))
 code='''val owner=thisBox
val compileJob=java.util.concurrent.CompletableFuture.runAsync {
 val smoke=afield.examples.DemoTranscriptExecSmokeTest
 val method=smoke.javaClass.declaredMethods.single { it.name.startsWith("runCompileOnlyDemo") }
 method.isAccessible=true
 val failures=mutableListOf<String>()
 try {
  for ((i,path) in PATHS.withIndex()) {
   method.invoke(smoke,java.io.File(path),"readability20_$i",failures)
   afield.app.server.thisSessionInfo.get()?.cleanup()
   afield.app.server.thisSessionInfo.remove()
  }
  check(failures.isEmpty()) { failures.joinToString("\\n") }
 } finally { afield.app.server.thisSessionInfo.remove() }
}.whenComplete { _,failure -> owner.display("compiled",failure?.toString()?:"all graphs compiled") }
'''.replace('PATHS','listOf('+','.join(json.dumps(p) for p in paths)+')')
 await m.program('compile_sources',code)
 await m.batch('compile_wait',['wait-output subject --channel compiled --timeout 180'])
 await m.macro('compile_verdict',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'compileJob.get(); println("full sources compiled")'}}])
 await m.program('training_schedule','''val trainingSampleCount = 13
val previewAngularStep = 0.018f
for (phase in 0..100000) {
 check((phase % trainingSampleCount) / ((trainingSampleCount - 1) / 2f) - 1 == (phase % 13) / 6f - 1)
 check(kotlin.math.sin(phase * previewAngularStep) == kotlin.math.sin(phase * .018f))
}
println("100001 training and preview phases exactly preserved")''')
 # Refresh only the changed saved sidecar, preserving every other property and box identity.
 directory=str(R/'dev_docs/agents/demos/frame_history_reprojection')
 await m.program('refresh_metadata','''val directory=DIRECTORY
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
val names=saving.readInlineFileSnapshots(directory).groupBy { it.boxId }.mapValues { it.value.map { s->s.property }.toSet() }
saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
check(before.map { it.id }==after.map { it.id })
check(before.map { it.properties-names[it.id].orEmpty() }==after.map { it.properties-names[it.id].orEmpty() })
check(saving.readInlineFileSnapshots(directory).all { it.inline==java.io.File(directory,it.path).readText() })
println("saved sources refreshed; graph and non-source properties preserved")'''.replace('DIRECTORY',json.dumps(directory)))
 (m.OUT/'coverage.json').write_text(json.dumps({'compiledGraphs':paths,'schedulePhases':100001},indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
