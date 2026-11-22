import afield.geometry.rhino.*
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.vulkan.util.Camera
import java.io.File

prop<PathTracingRenderer>(tracer)

// Start here when changing render cost, lighting or convergence.
val samplesPerFrame = 2
val maxBounces = 6
val exposure = 1.3f
val environmentRotation = 2.1f
val targetSamples = 600

// The reader is an optional native library; this says how to build it when it is absent.
check(Rhino3dm.isAvailable) { Rhino3dm.describe() }

// ---- the file ------------------------------------------------------------------
// Swap this for Rhino3dm.load("~/Downloads/model.3dm") to trace your own.
val dir = java.nio.file.Files.createTempDirectory("rhino-trace").toFile()
val model = Rhino3dm.load(Rhino3dm.writeNurbsSample(File(dir, "save_small.3dm")))
dir.deleteRecursively()
println(model)

// ---- the scene -----------------------------------------------------------------
// The fixture is in model units of about 50 across; a twentieth puts it near 2.5.
val scene = PathTraceScene()
val added = scene.rhino(model, scale = 0.05f)
println("scene: ${added.objects} objects, ${added.triangleCount} triangles, ${added.degenerateCount} dropped")
println("       ${added.materials.values.toSet().size} materials, one per display colour")
println("model.mesh(): ${model.mesh().triangleCount} triangles")

val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))
val low = added.boundsMin.y - 0.01f
val reach = 8f
scene.quad(
    Vector3f(-reach, low, -reach),
    Vector3f(reach, low, -reach),
    Vector3f(reach, low, reach),
    Vector3f(-reach, low, reach),
    floor,
)

// ---- the render ----------------------------------------------------------------
val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val c = added.center
val span = maxOf(added.size.x, added.size.z)
val camera = Camera(renderer).also {
    it.at.set(c.x + span * 0.55f, c.y + span * 0.55f, c.z + span * 1.25f)
    it.target.set(c.x, c.y - added.size.y * 0.15f, c.z)
    it.p.fov = 0.6
}
// 1000 x 614 is the window's own aspect, so the blit fills it rather than letterboxing.
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = samplesPerFrame
tracer.maxBounces = maxBounces
tracer.exposure = exposure
tracer.environmentRotation = environmentRotation
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < targetSamples) {
        frame()
    }
    println("rhino trace converged: ${tracer.samples} samples")
}
