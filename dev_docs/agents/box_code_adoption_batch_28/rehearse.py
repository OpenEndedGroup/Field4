"""Exercise authored paint tools locally, including their native PNG side effect."""
import asyncio,importlib.util,json,sys,yaml
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig28',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
m.OUT=R/'build/box-code-revision/adoption-28/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 row=json.loads((H/'changes.json').read_text())[0]
 before=yaml.safe_load(row['before'])['operations'][0]['create']['properties']['code']
 old=before[before.index('fun through('):before.index('fun region(')].replace('fun through(', 'fun throughBefore(')
 ops=yaml.safe_load((R/row['path']).read_text())['operations']
 code = ops[0]['create']['properties']['code']
 marker = 'import java.util.Base64'
 assert marker in code
 ops[0]['create']['properties']['code'] = code.replace(marker, marker + '\nSystem.setProperty("afield.paint.library", "/Users/marc/field4/build/field-paint/release/libfield_paint_jvm.dylib")')
 if '--continue-tools' not in sys.argv: await m.macro('paint-install',ops,True)
 probe=old+'''
var maxDifference = 0.0
for (xy in listOf(listOf(0.0,0.0,100.0,30.0), listOf(0.0,0.0,100.0,30.0,50.0,120.0),listOf(10.0,20.0,180.0,80.0,50.0,220.0,250.0,300.0))) {
    for (closed in listOf(false,true)) {
        val a = throughBefore(xy,closed).sampleByNumber(256)
        val b = through(xy,closed).sampleByNumber(256)
        check(a.size == b.size)
        for (i in a.indices) maxDifference = maxOf(maxDifference,a[i].distance(b[i]).toDouble())
    }
}
check(maxDifference < 0.001) { "spline difference $maxDifference" }
for (bad in listOf(emptyList<Double>(),listOf(0.0,0.0),listOf(0.0,0.0,10.0,10.0,20.0))) {
    val rejected = runCatching { through(bad) }.exceptionOrNull()
    check(rejected is IllegalArgumentException && rejected.message.orEmpty().contains("complete coordinate pairs"))
}
println("six open/closed paths agree within 0.001; malformed pairs rejected; maximum $maxDifference")
'''
 if '--continue-tools' not in sys.argv: await m.macro('paint-splines',[{'execute':{'box':'ai4_paint_studio','property':'code','type':'fragment','code':probe}}])
 code='''
val scribble = requireNotNull(DocumentToolRegistry.get(inside.filename, "scribble"))
runBlocking {
    for (bad in listOf(-1.0,0.0,0.5,9.0)) {
        val rejected = runCatching { scribble.call("outline" to listOf(10.0,10.0,70.0,10.0,70.0,70.0,10.0,70.0),"color" to "#224466","density" to bad) }.exceptionOrNull()
        check(rejected is IllegalArgumentException) { "density $bad: $rejected" }
        check(rejected.message.orEmpty().contains("density must be"))
    }
    for (valid in listOf(1.0,8.0)) {
        val result = scribble.call("outline" to listOf(10.0,10.0,70.0,10.0,70.0,70.0,10.0,70.0),"color" to "#224466","density" to valid)
        check(result.toString().contains("scribbled"))
    }
}
println("four invalid densities rejected; both valid endpoint densities painted")
'''
 code = 'go {\n' + code.replace('runBlocking {', '').replace('\n}\nprintln', '\nprintln') + '\ndisplay(\"density-proof\", \"valid and invalid densities verified\")\n}'
 await m.macro('paint-density',[{'execute':{'box':'ai4_paint_scribbler','property':'code','type':'fragment','code':code}}])
 await m.batch('density-wait',['wait-output ai4_paint_scribbler --channel density-proof --contains verified --timeout 60'])
 code='''
val look = requireNotNull(DocumentToolRegistry.get(inside.filename, "paint.look"))
check(ToolEffect.CAPTURE_IMAGE in look.meta.effects && ToolEffect.FILESYSTEM_WRITE in look.meta.effects)
val countBefore = galleryDir().parentFile.resolve("progress").listFiles()?.size ?: 0
runBlocking { look.call() }
val progressFiles = galleryDir().parentFile.resolve("progress").listFiles().orEmpty()
check(progressFiles.size == countBefore + 1)
val saved = progressFiles.maxBy { it.lastModified() }
val image = requireNotNull(javax.imageio.ImageIO.read(saved))
check(image.width == 900 && image.height > 0)
println("declared file-write effect saved a real ${image.width} x ${image.height} PNG")
'''
 code = 'go {\n' + code.replace('runBlocking { look.call() }', 'look.call()') + '\ndisplay(\"look-proof\", \"real native PNG verified\")\n}'
 await m.macro('paint-look',[{'execute':{'box':'ai4_paint_studio','property':'code','type':'fragment','code':code}}])
 await m.batch('look-wait',['wait-output ai4_paint_studio --channel look-proof --contains verified --timeout 60'])
 await m.batch('paint-visible',['screenshot-box ai4_paint_studio '+str(m.OUT/'paint.png')])
asyncio.run(main())
