"""Compile the actual graphs and published full examples; test inherited shader source without GPU work."""
from pathlib import Path
import importlib.util,json,yaml,asyncio,shlex,sys,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(H.parent/'box_code_adoption_batch_3'))
s=importlib.util.spec_from_file_location('runtime18',H.parent/'box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.ROOT=R;m.PORT=9700;m.OUT=R/'build/box-code-revision/adoption-18/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('states18',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def inherited_reads():
 read='val v=requireNotNull(vertex) { "missing vertex" }; val f=requireNotNull(fragment) { "missing fragment" }; check(v=="parent-v" && f=="parent-f")'
 ops=[{'create':{'box':'shader_source','name':'shader parent','properties':{'vertex':'parent-v','fragment':'parent-f'}}},{'create':{'box':'reader','name':'typed reader','properties':{'code':read}}},{'connect':{'parent':'shader_source','child':'reader'}},{'execute':{'box':'reader','property':'code'}}]
 ops += [{'edit':{'box':'shader_source','property':'vertex','from':'parent-v','to':'updated-v'}},{'execute':{'box':'reader','property':'code','type':'fragment','code':'check(requireNotNull(vertex)=="updated-v"); println("reload sees updated inherited source")'}}]
 await m.macro('inherited_sources',ops,True)

async def main():
 paths=['dev_docs/agents/transcripts/neural_reconstruction.yaml','public/docs/assets/opendlssnr/open_dlss_nr.yaml','public/docs/assets/opendlssnr/open_dlss_nr_cubes.yaml']
 scaffold=yaml.safe_load((R/paths[1]).read_text())['operations'][0]['create'];scaffold=dict(scaffold);scaffold['box']='shader_parent'
 text=(R/'public/docs/open_dlss_nr.md').read_text();bodies=[text[a:b] for a,b in d.sections(text) if 'OpenDlssNrStudy(' in text[a:b]]
 bodies=[re.sub(r'(?m)^\s*:::(?:fold[^\n]*|endfold)\s*$','',c) for c in bodies]
 bodies.append((R/'dev_docs/agents/demos/neural_reconstruction/example.kt').read_text())
 for i,code in enumerate(bodies):
  p=m.OUT/f'published-{i}.yaml';p.write_text(yaml.safe_dump({'operations':[{'create':scaffold},{'create':{'box':'source','name':'published example','properties':{'code':code}}},{'connect':{'parent':'shader_parent','child':'source'}}]},sort_keys=False));paths.append(str(p))
 full=[str(R/p) if not Path(p).is_absolute() else p for p in paths]
 code='''
val owner=thisBox
val compileJob=java.util.concurrent.CompletableFuture.runAsync {
 val smoke=afield.examples.DemoTranscriptExecSmokeTest
 val method=smoke.javaClass.declaredMethods.single { it.name.startsWith("runCompileOnlyDemo") }
 method.isAccessible=true
 val failures=mutableListOf<String>()
 try {
  for ((i,path) in PATHS.withIndex()) {
   method.invoke(smoke,java.io.File(path),"shader_source_$i",failures)
   afield.app.server.thisSessionInfo.get()?.cleanup()
   afield.app.server.thisSessionInfo.remove()
  }
  check(failures.isEmpty()) { failures.joinToString("\\n") }
 } finally { afield.app.server.thisSessionInfo.remove() }
}.whenComplete { _,failure -> owner.display("compiled",failure?.toString()?:"all shader graphs compiled") }
'''.replace('PATHS','listOf('+','.join(json.dumps(p) for p in full)+')')
 await m.program('compile_sources',code)
 await m.batch('compile_wait',['wait-output subject --channel compiled --timeout 180'])
 await m.macro('compile_verdict',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'compileJob.get(); println("shader graphs compiled")'}}])
 # Ordinary typed properties must inherit and be read again when the parent source changes.
 await inherited_reads()
 (m.OUT/'coverage.json').write_text(json.dumps({'compiledGraphs':paths,'publishedFullBodies':len(bodies),'inheritedAndReloadReads':True},indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
