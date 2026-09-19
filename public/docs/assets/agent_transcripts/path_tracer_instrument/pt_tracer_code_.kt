// The shipped materials scene at 640x360, plus the one new line: a convergence log.
//
// `recordConvergence()` attaches a log the tracer appends to from inside its own `render`, so
// it costs no frames. This replaces the shape four scratch boxes were written in last night --
// `frames { repeat(180) { frame() } }` with a `println` inside -- which spends 180 real frames
// to learn what the log already knows.
import afield.four.fabricated.*
import afield.four.modules.fromContext
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)
prop<PathTraceConvergence>(log)

tracer = PathTraceDemo.install(renderer,
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f
tracer.exposure = 1.5f
tracer.blitToScreen()

log = tracer.recordConvergence()
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
