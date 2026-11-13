/*
The shipped materials scene at 640x360, plus the one new line: a convergence log.

`recordConvergence()` attaches a log the tracer appends to from inside its own `render`, so
it costs no frames. This replaces the shape four scratch boxes were written in last night --
`frames { repeat(180) { frame() } }` with a `println` inside -- which spends 180 real frames
to learn what the log already knows.
*/
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera

prop<PathTracingRenderer>(tracer)
prop<PathTraceConvergence>(log)

val scene = PathTraceScene()
// Colours are linear RGB; the sphere materials end with roughness.
val floor = scene.material(PathTraceMaterial.diffuse(0.48f, 0.48f, 0.48f))
val red = scene.material(PathTraceMaterial.plastic(0.65f, 0.055f, 0.025f, 0.22f))
val gold = scene.material(PathTraceMaterial.metal(0.95f, 0.7f, 0.3f, 0.12f))
val rough = scene.material(PathTraceMaterial.metal(0.55f, 0.65f, 0.8f, 0.55f))
// A floor and three independently editable spheres.
scene.quad(Vector3f(-15f, 0f, -15f), Vector3f(-15f, 0f, 15f), Vector3f(15f, 0f, 15f), Vector3f(15f, 0f, -15f), floor)
scene.sphere(Vector3f(-1.7f, 0.8f, 0f), 0.8f, red, 48, 24)
scene.sphere(Vector3f(0f, 0.8f, 0f), 0.8f, gold, 48, 24)
scene.sphere(Vector3f(1.7f, 0.8f, 0f), 0.8f, rough, 48, 24)
val camera = Camera(renderer).also {
    it.at.set(3.5f, 2.4f, 7.5f)
    it.target.set(0f, 0.65f, 0f)
    it.p.fov = 0.62
}

val environment = PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr")
tracer = PathTracingRenderer(
    renderer,
    scene,
    environment,
    width = 640,
    height = 360,
    camera = camera,
).install()
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f
tracer.exposure = 1.5f
tracer.blitToScreen()

log = tracer.recordConvergence()
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
