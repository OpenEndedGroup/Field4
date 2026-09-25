import afield.four.fabricated.PathTraceGlassDemo
import afield.four.fabricated.PathTracingRenderer
import java.io.File
prop<PathTracingRenderer>(tracer)
tracer = PathTraceGlassDemo.install(renderer, width=1280, height=720,
    environmentPath=File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
tracer.samplesPerFrame = 1
tracer.maxBounces = 10
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
tracer.blitToScreen()
// This coroutine only announces capture readiness; the named scene owns the tracer.
frames {
    while (tracer.samples < 2048) { frame() }
    println("glass study converged: ${tracer.samples} samples")
}
