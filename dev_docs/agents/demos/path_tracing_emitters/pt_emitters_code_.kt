import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

// Two lamps in the dark and nothing else: the case BSDF sampling alone cannot find.
// The wide one radiates from both faces; the small, bright one is single-sided and
// faces down, so its back is dark to the direct sampler exactly as it is to a hit.
val scene = PathTraceScene().apply {
    val floor = material(PathTraceMaterial.diffuse(0.72f, 0.70f, 0.66f))
    val wall = material(PathTraceMaterial.diffuse(0.58f, 0.22f, 0.18f))
    val ball = material(PathTraceMaterial.diffuse(0.75f, 0.75f, 0.78f))
    val warm = material(PathTraceMaterial.emissive(24f, 21f, 15f))
    val cool = material(PathTraceMaterial.emissive(60f, 74f, 110f).copy(doubleSided = false))
    quad(Vector3f(-5f, 0f, -5f), Vector3f(-5f, 0f, 5f), Vector3f(5f, 0f, 5f), Vector3f(5f, 0f, -5f), floor)
    quad(Vector3f(-5f, 0f, -3.2f), Vector3f(-5f, 5f, -3.2f), Vector3f(5f, 5f, -3.2f), Vector3f(5f, 0f, -3.2f), wall)
    sphere(Vector3f(0.55f, 0.6f, 0.7f), 0.6f, ball, 40, 20)
    quad(Vector3f(-1.3f, 2.6f, -0.9f), Vector3f(-0.5f, 2.6f, -0.9f),
        Vector3f(-0.5f, 2.6f, -0.1f), Vector3f(-1.3f, 2.6f, -0.1f), warm)
    quad(Vector3f(1.0f, 2.2f, 0.2f), Vector3f(1.35f, 2.2f, 0.2f),
        Vector3f(1.35f, 2.2f, 0.55f), Vector3f(1.0f, 2.2f, 0.55f), cool)
}
val camera = Camera(renderer).also {
    it.at.set(0.2f, 1.9f, 6.4f); it.target.set(0f, 0.9f, 0f); it.p.fov = 0.78
}
tracer = PathTracingRenderer(renderer, scene,
    PathTraceEnvironment.constant(0f), 640, 480, camera, name = "emitter-demo")
tracer.maxBounces = 3
tracer.samplesPerFrame = 2
tracer.showEnvironment = false
tracer.background = Vector4f(0f, 0f, 0f, 1f)
tracer.exposure = 1.6f
tracer.install()
tracer.blitToScreen()
println("${tracer.emitterCount} emissive triangles of ${tracer.triangleCount}, " +
    "in a black environment: they are the only light in this scene")

// Flip the one flag every 256 samples. Changing it resets accumulation, so the two
// estimators are always compared at the same budget, over and over. Off is a
// blizzard; on is the same image, clean. Comment out the loop to hold one of them.
frames {
    while (true) {
        while (tracer.samples < 256) frame()
        println("emitterSampling = ${tracer.emitterSampling} at ${tracer.samples} samples")
        tracer.emitterSampling = !tracer.emitterSampling
        while (tracer.samples >= 256) frame()
    }
}
