"""Run targeted behavioral checks using the authored bodies in a private Field JVM."""
import asyncio, importlib.util, json, sys
from pathlib import Path
import yaml
ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent/'box_code_adoption_batch_7'))
spec = importlib.util.spec_from_file_location('rehearse3', HERE.parent/'box_code_adoption_batch_3/rehearse.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.PORT = 9700
m.OUT = ROOT/'build/box-code-revision/adoption-26/runtime'
m.OUT.mkdir(parents=True, exist_ok=True)
def bodies(name):
    doc = yaml.safe_load((ROOT/f'dev_docs/agents/transcripts/{name}.yaml').read_text())
    return [op['create']['properties']['code'] for op in doc['operations'] if 'create' in op]
async def main():
    glyph = bodies('letter_interpolation_panels')[1]
    glyph = glyph[glyph.index('fun glyph('):glyph.index('/*',glyph.index('fun glyph('))].rstrip()
    await m.program('glyph-contours', 'System.setProperty("afield.paint.library", "/Users/marc/field4/build/field-paint/release/libfield_paint_jvm.dylib")\nval N = 512\n'+glyph, '''
for (letter in listOf("C", "S")) {
    val points = glyph(letter)
    check(points.size == N)
    check(points.all { it.x.isFinite() && it.y.isFinite() })
}
for (letter in listOf("O", " ")) {
    val rejected = runCatching { glyph(letter) }.exceptionOrNull()
    check(rejected is IllegalArgumentException) { "Expected contour rejection for '$letter': $rejected" }
    check(!rejected.message.isNullOrBlank())
    if (letter == "O") check(rejected.message.orEmpty().contains("closed contours"))
}
proof("two valid glyphs, two descriptive rejections")
''')
    logging = bodies('logging_layer')[0]
    logging = logging.replace('LogDispatcher.withSink(probe) {', '''LogDispatcher.withSink(probe) {
    val workers = (0 until 8).map { worker ->
        Thread { repeat(250) { index -> lprintln("info,mypage", "stress-$worker-$index") } }
    }
    workers.forEach { it.start() }
    workers.forEach { it.join() }
''')
    await m.program('concurrent-log-sink', logging, '''
val stress = seen.filter { it.message.startsWith("stress-") }
check(stress.size == 2000) { "Lost messages: ${stress.size}" }
check(stress.map { it.message }.toSet().size == 2000)
val detachedSize = seen.size
lprintln("info,mypage", "after sink scope")
check(seen.size == detachedSize) { "Sink leaked past its scope" }
proof("2000 concurrent messages, no loss or leaked sink")
''')
    ceres = bodies('ceres_free_variables')[0]
    imports = ceres[:ceres.index('/*')]
    search = ceres[ceres.index('val backendFailures'):ceres.index('val seed')]
    missing = search.replace('build/ken-ceres-redistributable/libken_ceres_jvm.dylib','build/box-code-revision/adoption-26/absent-a.dylib').replace('build/ken-ceres/libken_ceres_jvm.dylib','build/box-code-revision/adoption-26/absent-b.dylib')
    await m.program('ceres-absent', imports+missing, '''
check(backend == null && backendFailures.isEmpty())
check(backendStatus.startsWith("No Ceres wrapper found"))
proof(backendStatus)
''')
    badpath = m.OUT/'invalid-native-library.dylib'
    badpath.write_text('This is a real file, but not a native library.\n')
    bad = missing.replace('build/box-code-revision/adoption-26/absent-a.dylib',str(badpath))
    await m.program('ceres-rejected', imports+bad, '''
check(backend == null && backendFailures.size == 1)
check(backendFailures.single().contains("invalid-native-library.dylib"))
check(backendStatus.startsWith("Ceres wrappers were found but could not load"))
proof("existing invalid library retains path and loader exception")
''')
    rank = bodies('completion_rank_at_scale')[2]
    await m.program('ranking-success',rank, '''
check(report.startsWith("R0 is the popup as shipped"))
check(report.contains("c-recall"))
proof("actual ranking subprocess succeeded")
''')
    failed = rank.replace('"tools/rank_weight_lab.py")','"tools/rank_weight_lab.py", "--not-a-valid-option")')
    imports = '\n'.join(line for line in failed.splitlines() if line.startswith('import '))
    body = '\n'.join(line for line in failed.splitlines() if not line.startswith('import '))
    await m.program('ranking-rejected',imports+'\nval rejection = runCatching {\n'+body+'\n}.exceptionOrNull()', '''
check(rejection is IllegalStateException)
check(rejection.message.orEmpty().contains("status 2"))
check(rejection.message.orEmpty().contains("unrecognized arguments"))
proof("real child exit 2 rejects success report and retains diagnostics")
''')
    absent = rank.replace('build/completion-corpus/rank-spec.json','build/box-code-revision/adoption-26/missing-rank-spec.json')
    await m.program('ranking-no-spec',absent, '''
check(report.startsWith("no spec at"))
check(!report.startsWith("R0"))
proof("missing fixture gives generation instruction")
''')
async def healthy_ceres():
    code = bodies('ceres_free_variables')[1]
    marker = 'val (trueCentre, trueRadius) = closedForm()'
    assert marker in code
    # Assert against the independent closed-form solution at the actual solve site.
    at = code.index('println(', code.index('val centreError'))
    code = code[:at] + 'check(kotlin.math.abs(radius - trueRadius) < 1e-6)\ncheck(centreError < 1e-6)\n' + code[at:]
    await m.program('ceres-native-sphere',code,'check(backend != null);proof("native centre and radius agree with closed form within 1e-6")')
async def slideshow():
    doc = yaml.safe_load((ROOT/'dev_docs/agents/transcripts/slideshow_guide.yaml').read_text())
    await m.macro('slideshow-install', doc['operations'], True)
    await m.macro('slideshow-visits', [{'execute': {'box': 'sg_tour', 'property': 'code', 'type': 'fragment', 'code': 'runBlocking { show.goToStep(1); show.goToStep(2); show.goToStep(1); show.goToStep(3) };check(visits == 2) { "visits=$visits" };check(stars == 0)'}}])
    js = m.OUT/'click-star.js'
    js.write_text("const a=document.querySelector('a[href=\"#custom:star\"]');if(!a)throw Error('Missing star link');a.click();return 'clicked actual slideshow star link';")
    await m.batch('slideshow-click', [f'page-eval --body --file {js} --wait-ms 1000'])
    await m.macro('slideshow-star', [{'execute': {'box': 'sg_tour', 'property': 'code', 'type': 'fragment', 'code': 'check(stars == 1) { "stars=$stars" };check(visits == 2);println("actual star callback updated typed guide property")'}}])
    js = m.OUT/'assert-star.js'
    js.write_text("const deadline=Date.now()+5000;while(!document.body.innerText.includes('Stars so far: 1')){if(Date.now()>deadline)throw Error('Updated star count missing from visible note');await new Promise(r=>setTimeout(r,25));}return 'visible note shows one star';")
    await m.batch('slideshow-visible', [f'page-eval --body --file {js}'])
    await m.macro('slideshow-reset', [{'execute': {'box': 'sg_tour', 'property': 'code', 'type': 'fragment', 'code': 'visits = 0;stars = 0;runBlocking { show.goToStep(1) };check(visits == 1 && stars == 0);runBlocking { show.stop() };println("reset lines affect active callbacks")'}}])
if __name__ == '__main__':
    asyncio.run(slideshow() if '--slideshow' in sys.argv else healthy_ceres() if '--native-ceres' in sys.argv else main())
