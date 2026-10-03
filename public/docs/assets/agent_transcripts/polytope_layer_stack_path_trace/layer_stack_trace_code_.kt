import afield.four.fabricated.*
import afield.four.util.Camera
import afield.manifold.*
import afield.stdlib.linalg.Vector3f
import afield.stdlib.polytope.*
import afield.examples.chain.PhotoToSolidChain
import java.io.File
prop<PathTracingRenderer>(tracer)
require(ManifoldLibrary.isAvailable) { ManifoldLibrary.describe() }
val scene = PathTraceScene()
Solid.scope {
    val mesh = conway("gC").polyhedron.canonicalize().polyhedron.triangulate(normals = NormalPolicy.Flat).mesh.weld(1e-6)
    val original = mesh.toSolid()
    val scaled = original.scaled(1.0 / Math.cbrt(original.volume))
    val bounds = scaled.bounds
    val solid = scaled.translated(-bounds.center.x.toDouble(), -bounds.center.y.toDouble(), -bounds.min.z.toDouble())
    val height = solid.bounds.max.z.toDouble()
    val count = 48
    val dz = height / count
    repeat(count) { i ->
        val t = i.toFloat() / (count - 1)
        val material = scene.material(PathTraceMaterial.plastic(0.08f + 0.8f*t, 0.65f - 0.4f*t, 0.8f - 0.72f*t, 0.32f))
        val slab = solid.slice(dz * (i + 0.5)).use { it.extruded(dz * 0.92) }
            .translated(0.0, 0.0, dz * i).rotated(-90.0, 0.0, 0.0)
        scene.importTriMesh(slab.toTriMesh(normals = true, minSharpAngle = 20.0), material)
    }
    println("48 canonical gC layers, source volume ${solid.volume}")
}
val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))
scene.quad(Vector3f(-6f, -0.03f, -6f), Vector3f(6f, -0.03f, -6f), Vector3f(6f, -0.03f, 6f), Vector3f(-6f, -0.03f, 6f), floor)
val environment = PathTraceEnvironment.fromFile(File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
val camera = Camera(renderer).also {
    it.at.set(2.45f, 2.8f, 3.6f)
    it.target.set(0f, 0.45f, 0f)
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
    println("layer_stack_trace study converged: ${tracer.samples} samples")
}
