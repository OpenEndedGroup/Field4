import afield.graphics.scene.fabricated.*
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.vulkan.util.Camera

// Launch this instead of the fabricate box: both draw to the renderer's window.
prop<PathTracingRenderer>(tracer)
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

val environment = PathTraceEnvironment.fromAtmosphere(sunElevation = 4.0, sunAzimuth = -150.0)
tracer = PathTracingRenderer(
    renderer,
    scene,
    environment,
    width = 1280,
    height = 720,
    camera = camera,
).install()
tracer.maxBounces = 5
tracer.indirectClamp = 10f // the sun seen through a mirror (a caustic) cannot be light-sampled
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles")
tracer.blitToScreen()
frames {
    while (tracer.samples < 1024) frame()
    println("atmosphere path trace converged: ${tracer.samples} samples")
}
