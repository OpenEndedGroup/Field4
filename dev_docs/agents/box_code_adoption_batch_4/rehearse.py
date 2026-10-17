"""Real Faust, slice, OSC, Syphon and RectSL checks in a private Field on 9700."""
import asyncio
import base64
import hashlib
import importlib.util
import json
import re
import sys
import shutil
from pathlib import Path
import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'public')]
sys.path.insert(0, str(HERE.parent / 'box_code_adoption_batch_3'))
from docs_hooks import extract_static_code
spec = importlib.util.spec_from_file_location('batch3', HERE.parent / 'box_code_adoption_batch_3/rehearse.py')
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
b.BASE = '773649d52'; b.PORT = 9700; b.OUT = ROOT / 'build/box-code-revision/adoption-4'
OUT = b.OUT

async def fragment(name, box, code):
    return await b.macro(name, [{'execute': {'box': box, 'property': 'code', 'type': 'fragment', 'code': code}}])

async def wavs(name, channels):
    result = await b.batch(name, [f'output subject --channel {json.dumps(c)} --kind display' for c in channels])
    blobs = re.findall(r'data:audio/wav;base64,([A-Za-z0-9+/=]+)', json.dumps(result))
    assert blobs, name
    hashes = []
    for blob in sorted(set(blobs)):
        data = base64.b64decode(blob)
        digest = hashlib.sha256(data).hexdigest(); hashes.append(digest)
        (OUT / (digest + '.wav')).write_bytes(data)
    return sorted(hashes)

async def faust():
    results = {}
    for label in ['original','revised']:
        source = yaml.safe_load(b.source('dev_docs/agents/transcripts/faust_polyphony.yaml',label))
        values = {}
        for op in source['operations']:
            c = op.get('create')
            if not c or c['box'].endswith('_play'): continue
            name=c['box']; code=c['properties']['code']
            probes = {
                'faust_polyphony_offline': 'check(render.notes.size == 16); proof([render.left.toList(), render.right.toList()])',
                'faust_polyphony_chord': 'check(poly.peakConcurrency == 3 && one.peakConcurrency == 1); check(one.steals == 2L); proof([poly.left.toList(), poly.right.toList(), one.left.toList(), one.right.toList()])',
                'faust_polyphony_timing': 'check(notated.size == 14); proof([mono.toList(), notated])',
                'faust_polyphony_refusal': 'check(runCatching { FaustPolyControls.of(program) }.isFailure); proof(program.ui.writable.map { it.displayLabel })',
                'faust_polyphony_steal': '',
            }
            capture_channel = {'faust_polyphony_offline':'main-audio','faust_polyphony_chord':'chord','faust_polyphony_timing':'waveform','faust_polyphony_refusal':None,'faust_polyphony_steal':'steal=1'}[name]
            values[name] = await b.program(label+'_'+name,code,probes[name],capture_channel or 'main',False)
            if name.endswith('_chord'):
                values['chord_wavs'] = await wavs(label+'_chord_wavs',['chord','one voice'])
            if name.endswith('_steal'):
                values['steal_wavs'] = await wavs(label+'_steal_wavs',['steal=1','steal=1440'])
            if label=='revised' and capture_channel:
                await b.batch(label+'_'+name+'_capture',[f'wait-output subject --channel {json.dumps(capture_channel)} --timeout 60',f'screenshot-box subject {OUT}/{name}.png'])
            if label=='revised' and name.endswith('_timing'):
                await fragment('onset_measurement_checks','subject','''
check(timingErrors.isNotEmpty() && timingErrors.all { it == 0 }) { "$timingErrors" }
val advanced = FloatArray(mono.size) { mono.getOrElse(it + 64) { 0f } }
val delayed = FloatArray(mono.size) { mono.getOrElse(it - 64) { 0f } }
check(checkable.all { onsetError(advanced, it.toInt()) == -64 })
check(checkable.all { onsetError(delayed, it.toInt()) == 64 })
check(checkable.all { onsetError(FloatArray(mono.size) { 0.25f }, it.toInt()) == null })
println("onset proof: default exact; advanced=-64; delayed=+64; no-gap signal inconclusive")
''')
        results[label]=values
    assert results['original']==results['revised'],results
    (OUT/'faust-equivalence.json').write_text(json.dumps(results,indent=2))

async def slices():
    results={}
    for label in ['original','revised']:
        text=b.source('public/docs/chain_ceres_manifold_contours.md',label)
        results[label]=[]
        for i,(start,end) in enumerate(b.sections(text)):
            code=extract_static_code(lines=text[start:end].splitlines())
            if label == "original" and i == 2:
                # Baseline predates the inward-winding guard; retain its diagnostic intent.
                code = code.replace(".mesh.weld(1e-6).toSolid()", ".mesh.weld(1e-6).toSolid(check = false)")
            results[label].append(await b.program(f'{label}_slices_{i}',code,'proof(rows)','cuts' if i==0 else 'forms' if i==1 else 'kkc',False))
            if label=='revised':
                await b.batch(f'slices_{i}_capture',[f'screenshot-box subject {OUT}/slices_{i}.png'])
    assert results['original']==results['revised'],results
    (OUT/'slice-equivalence.json').write_text(json.dumps(results,indent=2))

async def osc():
    text=b.source('public/docs/osc.md','revised')
    for i,(start,end) in enumerate(b.sections(text)):
        code=extract_static_code(lines=text[start:end].splitlines())
        await b.macro(f'osc_{i}_create',[{'create':{'box':'subject','name':f'OSC {i}','frame':{'x':0.,'y':0.,'w':1200.,'h':100.},'properties':{'code':code}}}],True)
        await b.batch(f'osc_{i}_launch',['launch subject','wait-output subject --channel main --timeout 30',f'screenshot-box subject {OUT}/osc_{i}.png'])
        if 'runOwned = true' in code:
            await fragment('osc_owned_registered','subject','check(osc(57308).hasHandler("/1/fader1"))')
            await b.batch('osc_owned_rerun',['launch subject','wait-running subject --state running --settle 0.2','stop subject','lifecycle-census subject --expect-stopped'])
            await fragment('osc_owned_removed','subject','check(!osc(57308).hasHandler("/1/fader1")); check(osc(57308).isListening); println("owned OSC handler removed; shared server remains listening")')
        else: await b.batch(f'osc_{i}_stop',['stop subject','lifecycle-census subject --expect-stopped'])

async def syphon():
    source=yaml.safe_load(b.source('dev_docs/agents/transcripts/syphon_bridge.yaml','revised'))
    creates=[op for op in source['operations'] if 'create' in op]
    for i,op in enumerate(creates):
        c=op['create'];c['frame']={'x':i*4000.,'y':0.,'w':1200.,'h':100.}
        c['properties']['code']=c['properties']['code'].replace('"Field pattern"','"Field pattern (code pass 4)"')
    await b.macro('syphon_create',creates,True)
    await b.batch('syphon_launch',['launch syphon_publish','wait-running syphon_publish --state running --settle 0.5','launch syphon_receive','wait-output syphon_receive --channel main --timeout 30',f'screenshot-box syphon_receive {OUT}/syphon_receive.png'])
    await fragment('syphon_client_before','syphon_receive','val firstClient = inp; check(inp === syphonClient("Field pattern (code pass 4)")); check(inp.latest() != null)')
    await b.batch('syphon_receive_stop',['stop syphon_receive','lifecycle-census syphon_receive --expect-stopped'])
    await fragment('syphon_client_retained','syphon_receive','check(!inp.isClosed); check(inp === firstClient)')
    await b.batch('syphon_receive_rerun',['launch syphon_receive','wait-running syphon_receive --state running --settle 0.3'])
    await fragment('syphon_client_reused','syphon_receive','check(inp === firstClient); inp.close(); val replacement = syphonClient("Field pattern (code pass 4)"); check(replacement !== inp && !replacement.isClosed); check(replacement === syphonClient("Field pattern (code pass 4)")); replacement.close()')
    await b.batch('syphon_stop',['stop syphon_receive','stop syphon_publish','lifecycle-census syphon_receive --expect-stopped','lifecycle-census syphon_publish --expect-stopped'])
    await fragment('syphon_close_server','syphon_publish','out.close()')

async def refresh_gallery():
    gallery=ROOT/'public/docs/assets/rectsl_gallery'
    snapshot=OUT/'gallery-refresh'
    shutil.copytree(gallery,snapshot,dirs_exist_ok=True)
    code=f'''
val directory = {json.dumps(str(gallery))}
val saving = afield.document.persistence.SavingContext()
val before = saving.readEdnTransfersFromString(java.io.File(directory, "metadata.edn").readText())
val refreshed = saving.refreshInlineFilesFromDisk(directory)
val after = saving.readEdnTransfersFromString(java.io.File(directory, "metadata.edn").readText())
check(before.map {{ it.id }} == after.map {{ it.id }})
check(before.map {{ it.properties - "code" }} == after.map {{ it.properties - "code" }})
check(saving.readInlineFileSnapshots(directory).all {{ it.inline == java.io.File(directory, it.path).readText() }})
val previousSession = afield.app.server.thisSessionInfo.get()
val loaded = try {{
    afield.app.server.thisSessionInfo.set(thisBox.inside)
    saving.load({json.dumps(str(snapshot))}, upgradeOnLoad = false)
}} finally {{ afield.app.server.thisSessionInfo.set(previousSession) }}
java.io.File(directory, "document.md").writeText(saving.exportDocumentMarkdown(loaded, directory))
println("gallery persistence proof: ${{after.size}} boxes; ${{refreshed.size}} sidecars; non-code properties unchanged")
'''
    await b.program('gallery_refresh',code)
    # Preserve authored tour text; update only the actual sidecar fences and their digests.
    doc=gallery/'document.md';text=doc.read_text()
    pattern=r'(^(`{3,})kotlin \[field-property[^\n]*path="([^"]+)"[^\n]*\]\n)(.*?)(^\2[ \t]*$)'
    count=0
    def mirror(m):
        nonlocal count
        count+=1
        return m[1]+(gallery/m[3]).read_text().rstrip()+'\n'+m[5]
    text=re.sub(pattern,mirror,text,flags=re.M|re.S)
    assert count==len(list(gallery.glob('*_code_.kt'))),(count,'gallery mirror')
    doc.write_text(text)
    from check_box_idioms import refresh_mirror_digests
    refresh_mirror_digests(doc)

async def main():
    OUT.mkdir(parents=True,exist_ok=True)
    phase = sys.argv[1] if len(sys.argv) > 1 else 'all'
    for name, operation in [('faust',faust),('slices',slices),('osc',osc),('syphon',syphon),('refresh',refresh_gallery)]:
        if phase in ['all', name]: await operation()
    print(f'batch 4 phase {phase} passed',flush=True)

if __name__=='__main__': asyncio.run(main())
