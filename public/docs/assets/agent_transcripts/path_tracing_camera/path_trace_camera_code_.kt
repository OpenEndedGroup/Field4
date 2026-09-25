import afield.four.fabricated.*
import afield.four.util.Camera
import afield.four.util.keyCamera
import afield.stdlib.linalg.Vector3f
val scene = PathTraceScene()
val grey = scene.material(PathTraceMaterial.diffuse(0.35f, 0.35f, 0.35f))
val light = scene.material(PathTraceMaterial.diffuse(0.7f, 0.7f, 0.7f))
for (z in -8..4) for (x in -7..7) {
    val m = if ((x+z) and 1 == 0) grey else light
    scene.quad(Vector3f(x.toFloat(),0f,z.toFloat()), Vector3f(x.toFloat(),0f,z+1f),
        Vector3f(x+1f,0f,z+1f), Vector3f(x+1f,0f,z.toFloat()), m)
}
val red = scene.material(PathTraceMaterial.plastic(0.65f, 0.035f, 0.015f, 0.25f))
val gold = scene.material(PathTraceMaterial.metal(0.95f, 0.7f, 0.3f, 0.18f))
val blue = scene.material(PathTraceMaterial.plastic(0.025f, 0.12f, 0.6f, 0.3f))
scene.sphere(Vector3f(-1.45f,0.65f,2.3f), 0.65f, red, 48, 24)
scene.sphere(Vector3f(0f,0.65f,0f), 0.65f, gold, 48, 24)
scene.sphere(Vector3f(1.8f,0.65f,-3f), 0.65f, blue, 48, 24)
val camera = Camera(renderer).also {
    it.at.set(0f,2.6f,8f)
    it.target.set(0f,0.65f,0f)
    it.p.fov = 0.62
}
val env = PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr")
prop<PathTracingRenderer>(tracer)
tracer = pathTracingHere(scene, env, width=1280, height=720, camera = camera)
tracer.apertureRadius = 0.25f // world units; zero switches depth of field off
tracer.focusDistance = null // keep the camera target in focus
tracer.pixelJitter = 1f // full-pixel antialiasing; zero samples pixel centres
tracer.temporalReuse = true // retain compatible surface history during camera motion
tracer.temporalMaxSamples = 16
tracer.samplesPerFrame = 4
tracer.indirectClamp = 10f
tracer.showEnvironment = false
tracer.exposure = 1.5f
tracer.blitToScreen()
var cameraKeys by keyCamera(renderer = renderer, camera = camera)
frames {
    while (tracer.samples < 1024) { frame() }
    println("camera study converged: ${tracer.samples} samples")
}
