import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera
import afield.geometry.solid.manifold.*
import afield.math.linalg.Vector3f
import afield.examples.chain.PhotoToSolidChain
import java.io.File
prop<PathTracingRenderer>(tracer)
require(ManifoldLibrary.isAvailable) { ManifoldLibrary.describe() }
val scene = PathTraceScene()
val mounted = PhotoToSolidChain.mount()
val relief = scene.material(PathTraceMaterial.plastic(0.67f, 0.42f, 0.19f, 0.4f))
val frameMaterial = scene.material(PathTraceMaterial.plastic(0.18f, 0.22f, 0.27f, 0.28f))
scene.importTriMesh(mounted.relief, relief)
scene.importTriMesh(mounted.frame, frameMaterial)
println(mounted.summary())
val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))
scene.quad(Vector3f(-6f, -0.16f, -6f), Vector3f(6f, -0.16f, -6f), Vector3f(6f, -0.16f, 6f), Vector3f(-6f, -0.16f, 6f), floor)
val environment = PathTraceEnvironment.fromFile(File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
val camera = Camera(renderer).also {
    it.at.set(2.2f, 2.25f, 2.6f)
    it.target.set(0f, 0.1f, 0f)
    it.p.fov = 0.52
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 4
tracer.maxBounces = 8
tracer.exposure = 1.35f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 900) { frame() }
    println("photo_mount_trace study converged: ${tracer.samples} samples")
}
