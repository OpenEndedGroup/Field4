import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera

prop<PathTracingRenderer>(tracer)
val scene = PathTraceScene()
val dark = scene.material(PathTraceMaterial.diffuse(0.055f, 0.055f, 0.055f))
val light = scene.material(PathTraceMaterial.diffuse(0.55f, 0.55f, 0.55f))
for (x in -10 until 10) for (z in -6 until 8) {
    val a = x * 0.55f
    val b = z * 0.55f
    val m = if ((x + z) and 1 == 0) light else dark
    scene.quad(
        Vector3f(a, 0f, b),
        Vector3f(a, 0f, b + 0.55f),
        Vector3f(a + 0.55f, 0f, b + 0.55f),
        Vector3f(a + 0.55f, 0f, b),
        m,
    )
}
// Narrow stripes reveal refraction and frosting against a common background.
for (i in 0 until 18) {
    val y = i * 0.18f
    scene.quad(
        Vector3f(-6f, y, -2.5f),
        Vector3f(6f, y, -2.5f),
        Vector3f(6f, y + 0.18f, -2.5f),
        Vector3f(-6f, y + 0.18f, -2.5f),
        if (i and 1 == 0) light else dark,
    )
}
val clear = scene.material(PathTraceMaterial.glass())
val green = scene.material(PathTraceMaterial.glass(attenuationColor = Vector3f(0.45f, 0.9f, 0.65f)))
val frosted = scene.material(PathTraceMaterial.glass(roughness = 0.32f))
val sheet = scene.material(PathTraceMaterial.thinGlass(tint = Vector3f(0.65f, 0.85f, 1f)))
scene.sphere(Vector3f(-2.7f, 0.8f, 0f), 0.8f, clear, 48, 24)
scene.sphere(Vector3f(-0.9f, 0.8f, 0f), 0.8f, green, 48, 24)
scene.sphere(Vector3f(0.9f, 0.8f, 0f), 0.8f, frosted, 48, 24)
scene.quad(
    Vector3f(2f, 0.05f, 0.1f),
    Vector3f(3.4f, 0.05f, 0.1f),
    Vector3f(3.4f, 1.7f, 0.1f),
    Vector3f(2f, 1.7f, 0.1f),
    sheet,
)
val camera = Camera(renderer).also {
    it.at.set(0f, 2.7f, 9.5f)
    it.target.set(0f, 0.8f, 0f)
    it.p.fov = 0.52
}

val environment = PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr")
tracer = PathTracingRenderer(
    renderer,
    scene,
    environment,
    width = 1280,
    height = 720,
    camera = camera,
).install()
tracer.samplesPerFrame = 1
tracer.maxBounces = 10
tracer.exposure = 1.5f
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
tracer.blitToScreen()
// This coroutine only announces capture readiness; the named scene owns the tracer.
frames {
    while (tracer.samples < 2048) {
        frame()
    }
    println("glass study converged: ${tracer.samples} samples")
}
