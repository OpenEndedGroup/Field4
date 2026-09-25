import afield.four.fabricated.PathTraceDemo
import afield.four.fabricated.PathTracingRenderer
import java.io.File
prop<PathTracingRenderer>(tracer)
tracer = PathTraceDemo.install(renderer, width=1280, height=720,
    environmentPath=File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f // Optional biased suppression of indirect fireflies.
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
tracer.showEnvironment = false
tracer.blitToScreen()
// This coroutine only announces capture readiness; the named scene owns the tracer.
frames {
    while (tracer.samples < 1024) { frame() }
    println("hidden environment study converged: ${tracer.samples} samples")
}
