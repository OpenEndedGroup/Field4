import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.CappedTwistFixture
import afield.four.modules.GltfSkinning.SkinningMethod
import afield.four.modules.GltfSkinning.SkinningOptions
import afield.four.modules.fromContext
import afield.four.modules.skinnedGltfToTriMesh
import afield.four.util.Camera
import afield.manifold.ManifoldLibrary
import afield.manifold.toSolid
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

require(ManifoldLibrary.isAvailable) {
    ManifoldLibrary.describe() + "\nOn macOS: brew install manifold, then run this box again."
}

val twist = 180f   // degrees about the bone's own axis; 180 is the worst case for LBS

// ---- stage one: skin the same file two ways -----------------------------------
val rig = CappedTwistFixture.locate()
fun posed(method: SkinningMethod) = skinnedGltfToTriMesh(
    rig, clip = "twist", seconds = CappedTwistFixture.secondsFor(twist),
    options = SkinningOptions(method = method),
)

// ---- stage two: each pose becomes a solid, is weighed, and comes back shaded ----
// 60 degrees splits the caps from the wall and leaves the 30-degree facets of the
// twelve-sided wall smooth.
fun weighed(method: SkinningMethod) = posed(method).toSolid().use { solid ->
    println("%-16s genus %d, volume %.4f (rest %.1f)".format(
        method.name, solid.genus, solid.volume, CappedTwistFixture.REST_VOLUME))
    solid.toTriMesh(normals = true, minSharpAngle = 60.0)
}
val lbs = weighed(SkinningMethod.LINEAR_BLEND)
val dqs = weighed(SkinningMethod.DUAL_QUATERNION)

// ---- stage three: lay both limbs on a floor and trace them --------------------
val scene = PathTraceScene()
val cyan = scene.material(PathTraceMaterial.plastic(0.30f, 0.72f, 0.90f, 0.35f))
val orange = scene.material(PathTraceMaterial.plastic(0.95f, 0.50f, 0.20f, 0.35f))
val floor = scene.material(PathTraceMaterial.plastic(0.5f, 0.51f, 0.53f, 0.5f))

// The rig stands along +y from 0 to 4. Turn it onto its side (+y to +x), centre it, and
// lift it by its radius so it rests on the floor at y = 0.
fun lying(z: Float) = Matrix4f().translation(-2f, CappedTwistFixture.RADIUS, z)
    .rotateZ(-0.5f * Math.PI.toFloat())
val a = scene.importTriMesh(lbs, cyan, lying(0.8f))
val b = scene.importTriMesh(dqs, orange, lying(-0.8f))
println("scene: ${a.triangleCount + b.triangleCount} triangles appended, " +
    "${a.degenerateCount + b.degenerateCount} dropped")

val reach = 8f
scene.quad(
    Vector3f(-reach, 0f, -reach), Vector3f(reach, 0f, -reach),
    Vector3f(reach, 0f, reach), Vector3f(-reach, 0f, reach),
    floor,
)

val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(1.2f, 3.4f, 5.2f)
    it.target.set(0f, 0.35f, 0f)
    it.p.fov = 0.62
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 6
tracer.exposure = 1.3f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 500) { frame() }
    println("skinned volume study converged: ${tracer.samples} samples")
}
