"""Check literal edits in the real box compiler and synchronize persistence mirrors."""
import base64,hashlib
import asyncio,importlib.util,json,shutil,yaml
from pathlib import Path
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('adoption4',HERE.parent/'box_code_adoption_batch_4/rehearse.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
b=r.b;ROOT=r.ROOT;OUT=ROOT/'build/box-code-revision/adoption-5';b.OUT=OUT;b.BASE='7c28456fd'
async def refresh():
    changes=json.loads((HERE/'comment_changes.json').read_text())
    dirs=sorted({str(Path(c['path']).parent) for c in changes if '/demos/' in c['path']})
    for i,d in enumerate(dirs):
        folder=ROOT/d
        # Formatting properties uses exactly the editor's Kotlin/GLSL formatter.
        digest=hashlib.sha256(b''.join(p.read_bytes() for p in sorted(folder.glob('*')) if p.is_file() and p.suffix in {'.kt','.comp','.glsl'})).hexdigest()
        code=f'// source digest {digest}\n'
        shaders=[p for p in folder.iterdir() if p.name in ['pressure.comp','polytope_source_vertex_.glsl']]
        for p in shaders:
            prop='compute' if p.suffix=='.comp' else 'vertex'
            code+=f'java.io.File({json.dumps(str(p))}).let {{ it.writeText(requireNotNull(afield.runtime.kotlin.formatCodeProperty("{prop}", it.readText()))) }}\n'
        if not (folder/'metadata.edn').exists():
            if code: await b.program(f'format_shader_{i}',code)
            continue
        code+=f'''
val directory = {json.dumps(str(folder))}
val saving = afield.document.persistence.SavingContext()
val before = saving.readEdnTransfersFromString(java.io.File(directory, "metadata.edn").readText())
val inlineNames = saving.readInlineFileSnapshots(directory).map {{ it.property }}.toSet()
val paths = saving.refreshInlineFilesFromDisk(directory)
val after = saving.readEdnTransfersFromString(java.io.File(directory, "metadata.edn").readText())
check(before.map {{ it.id }} == after.map {{ it.id }})
check(before.map {{ it.properties - inlineNames }} == after.map {{ it.properties - inlineNames }})
check(saving.readInlineFileSnapshots(directory).all {{ it.inline == java.io.File(directory, it.path).readText() }})
println("persistence proof: ${{after.size}} boxes; ${{paths.size}} sidecars; non-sidecar properties unchanged")
'''
        await b.program(f'refresh_{i}',code)
        if (folder/'document.md').exists():
            private=OUT/'mirrors'/folder.name;shutil.copytree(folder,private,dirs_exist_ok=True)
            await b.program(f'mirror_{i}',f'''// source digest {digest}

val saving = afield.document.persistence.SavingContext()
val previous = afield.app.server.thisSessionInfo.get()
val loaded = try {{
    afield.app.server.thisSessionInfo.set(thisBox.inside)
    saving.load({json.dumps(str(private))}, upgradeOnLoad = false)
}} finally {{ afield.app.server.thisSessionInfo.set(previous) }}
java.io.File({json.dumps(str(folder/'document.md'))}).writeText(saving.exportDocumentMarkdown(loaded, {json.dumps(str(folder))}))
println("mirror regenerated with Field codec")
''')
async def run():
    results={}
    for label in ['original','revised']:
        values={}
        source=yaml.safe_load(b.source('dev_docs/agents/artifacts/datalog_aggregate_proofs/aggregate_proofs.yaml',label))
        for i,op in enumerate(source['operations']):
            if 'create' not in op:continue
            c=op['create'];code=c['properties']['code'];probe='proof([d.why("payroll(sales, 300)")?.render(),d.why("headcount(eng, 2)")?.render(),d.why("topEarner(sales, bob)")?.render()])' if c['box'].endswith('_why') else 'proof(g.whyNot("path(a, e)").render())'
            values[c['box']]=await b.program(label+'_'+c['box'],code,probe)
        code=b.source('dev_docs/agents/demos/midi_clip/midi_write_code_.kt',label)
        values['midi']=await b.program(label+'_midi',code,'''val read = readMidiClip(out)
check(clip.tracks[0].notes.size == 16 && clip.tracks[1].notes.size == 8)
check(read.timeSignature == (4 to 4) && kotlin.math.abs(read.bpmAt(0.0) - 104.0) < 0.001)
check(read.notes.maxOf { it.startBeats } == 15.0)
proof([clip.notes,read.notes,read.durationBeats,read.timeSignature])''')
        code=b.source('dev_docs/agents/demos/chart_primitive/chart_pick_code_.kt',label)
        values['chart']=await b.program(label+'_chart',code,'proof(c.plotRect())')
        results[label]=values
    assert results['original']==results['revised'],results
    (OUT/'equivalence.json').write_text(json.dumps(results,indent=2)+'\n')
    code=(ROOT/'dev_docs/agents/demos/midi_clip/midi_audition_code_.kt').read_text()
    await b.program('audition_compile_driver','''
val target = afield.document.graph.Box("audition").also { it.inside = thisBox.inside; it.code = AUDITION_CODE }
val smoke = afield.examples.DemoTranscriptExecSmokeTest
val method = smoke.javaClass.getDeclaredMethod("compileBoxOnly", afield.document.graph.Box::class.java)
method.isAccessible = true
val done = java.util.concurrent.CompletableFuture.runAsync {
    val errors = method.invoke(smoke, target) as List<*>
    check(errors.isEmpty()) { "$errors" }
}.whenComplete { _, failure -> display("compiled", failure?.toString() ?: "compiled") }
'''.replace('AUDITION_CODE', 'String(java.util.Base64.getDecoder().decode("' + base64.b64encode(code.encode()).decode() + '"))'))
    await b.batch('audition_compile_wait',['wait-output subject --channel compiled --timeout 120'])
    await r.fragment('audition_compile_verdict','subject','done.get(); println("audition compiled without opening an audio device")')

async def main():
    OUT.mkdir(parents=True,exist_ok=True)
    await refresh();await run()
    print('batch 5 persistence and executable changes passed',flush=True)
if __name__=='__main__':asyncio.run(main())
