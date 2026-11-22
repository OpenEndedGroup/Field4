import afield.graphics.nile.*
import afield.graphics.vulkan.util.GpuProbe

val N = 900
val doc = GeziraSvg.parse(GeziraSvg.tiger())
// Every fill and every stroke outline, in paint order, uploaded once.
val paths = GeziraGpuScene.paths(doc.lines)
val fit = doc.fit(N, N)
val probe = GpuProbe(renderer, "gezira-gpu-tiger")
val scene = GeziraGpuScene(probe, paths, N, N, transform = fit)

prop<FrameBufferGroup>(tigerImage) = scene.image
prop<Int>(tigerSize) = N
println("${paths.size} paths, ${scene.curveCount} curves")

// Turn and breathe about the middle: only the six numbers of the transform change per frame.
var frames = 0
var last = System.nanoTime()
loop {
    val t = frames / 60.0
    val zoom = 1.0 + 0.35 * Math.sin(t * 0.7)
    val about =
        SvgTransform.translate(N / 2.0, N / 2.0) *
            SvgTransform.rotate(12.0 * Math.sin(t * 0.5)) *
            SvgTransform.scale(zoom) *
            SvgTransform.translate(-N / 2.0, -N / 2.0)
    scene.transform = about * fit
    frames++
    if (frames % 120 == 0) {
        val now = System.nanoTime()
        display("rate", "%.1f ms a frame".format((now - last) / 1e6 / 120))
        last = now
    }
    frame()
}
    .end { probe.close() }
