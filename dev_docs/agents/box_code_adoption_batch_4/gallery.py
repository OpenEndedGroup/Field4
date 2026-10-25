"""Compile every real gallery wrapper, then render and rerun normal/escape boxes."""
import asyncio
import importlib.util
import json
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('batch4_rehearse',HERE/'rehearse.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
b=r.b; OUT=r.OUT; ROOT=r.ROOT

async def main():
    copy=OUT/'gallery'
    shutil.copytree(ROOT/'public/docs/assets/rectsl_gallery',copy,dirs_exist_ok=True)
    await b.program('gallery_compile',f'''
val saving = afield.document.persistence.SavingContext()
val previousSession = afield.app.server.thisSessionInfo.get()
val galleryBoxes = try {{
    afield.app.server.thisSessionInfo.set(thisBox.inside)
    saving.load({json.dumps(str(copy))}, upgradeOnLoad = false)
}} finally {{ afield.app.server.thisSessionInfo.set(previousSession) }}
val smoke = afield.examples.DemoTranscriptExecSmokeTest
val compile = smoke.javaClass.getDeclaredMethod("compileBoxOnly", afield.document.graph.Box::class.java)
compile.isAccessible = true
val targets = galleryBoxes.filter {{ it.id != "root" }}.sortedBy {{ if (it.id == "renderer") "" else it.id }}
val compiled = java.util.concurrent.CompletableFuture.runAsync {{
for (target in targets) {{
    val errors = compile.invoke(smoke, target) as List<*>
    check(errors.isEmpty()) {{ "${{target.id}}: $errors" }}
    println("wrapper compiled: ${{target.id}}")
}}
println("gallery wrapper compile proof: ${{targets.size}} boxes")
}}.whenComplete {{ _, failure -> display("compiled", failure?.toString() ?: "compiled ${{targets.size}} wrappers") }}
''')
    await b.batch('gallery_compile_complete',['wait-output subject --channel compiled --timeout 180'])
    await r.fragment('gallery_compile_verdict','subject','compiled.get(); println("all wrapper compilations passed")')
    await b.batch('gallery_renderer',['launch renderer','wait-running renderer --state running --settle 0.5'])
    for box in ['all-time','inline-functions']:
        await b.batch('gallery_'+box,[f'launch {box}',f'wait-native-renderer {box} --frames 20 --timeout 60',f'native-screenshot {OUT}/gallery_{box}.png --uid {box} --wait-frames 8 --timeout 60'])
        await b.batch('gallery_'+box+'_rerun',[f'launch {box}',f'wait-native-renderer {box} --frames 20 --timeout 60',f'stop {box}',f'lifecycle-census {box} --expect-stopped'])
        if box == 'inline-functions':
            await r.fragment('gallery_escape_engine_closed',box,'check(engine.isDisposed); println("dedicated escape compiler disposed on stop")')
    await b.batch('gallery_renderer_stop',['stop renderer','lifecycle-census renderer --expect-stopped'])
    await b.program('gallery_language_smoke','''
afield.examples.rectsl.RectSLExamples.main(emptyArray())
val bad = afield.lang.rectsl.RectAnimationOracle.compile("#rectsl\\n->wall 20 10 meters\\n->definitely-not-a-word")
check(!bad.ok)
check(runCatching { bad.program }.isFailure)
println("RectSL language/gallery checks passed; invalid word still reports diagnostics")
''')
    print('all gallery wrappers compiled; native normal/escape rerun and stop passed',flush=True)

if __name__=='__main__':asyncio.run(main())
