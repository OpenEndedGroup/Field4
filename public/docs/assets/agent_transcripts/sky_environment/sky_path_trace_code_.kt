import afield.graphics.scene.fabricated.PathTraceDemo
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTracingRenderer

// Launch this instead of the fabricate box: both draw to the renderer's window.
prop<PathTracingRenderer>(tracer)
tracer = PathTraceDemo.install(
    renderer,
    PathTraceEnvironment.fromAtmosphere(sunElevation = 4.0, sunAzimuth = -150.0),
    width = 1280,
    height = 720,
)
tracer.maxBounces = 5
tracer.indirectClamp = 10f // the sun seen through a mirror (a caustic) cannot be light-sampled
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles")
tracer.blitToScreen()
frames {
    while (tracer.samples < 1024) frame()
    println("atmosphere path trace converged: ${tracer.samples} samples")
}
