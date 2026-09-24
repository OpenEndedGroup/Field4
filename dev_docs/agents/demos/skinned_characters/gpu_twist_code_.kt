import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning.SkinningMethod
import afield.four.modules.GltfSkinning.SkinningOptions
import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.GpuSkinner
import afield.four.modules.GpuSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// Box 5 is the candy wrapper on the CPU. This is the same twist on the GPU, both methods in ONE
// draw call: the left cylinder is linear blend skinning and pinches to a point at 180 degrees, the
// right one is dual quaternion skinning and keeps its radius. Same mesh, same palette, same shader;
// the only difference between them is one word per instance saying which blend to run.
//
// RUN BOX 0 FIRST, and stop box 6 if it is running -- both draw into box 0's window.
//
// EDIT `methods` AND RE-RUN: make both LINEAR_BLEND and both pinch; make both DUAL_QUATERNION and
// neither does.
val methods = listOf(SkinningMethod.LINEAR_BLEND, SkinningMethod.DUAL_QUATERNION)

val model = GltfSupport.loadGltfBasic(
    RiggedFixture.locate(), GltfSupport.GltfLoadConfig(loadTextures = false)
)
val inst = model.meshes.first { it.skin != null }

val module = Module(
    Parameters(
        renderer = renderer,
        vertex = GpuSkinning.skinnedVertexShader,
        fragment = GpuSkinning.skinnedFragmentShader,
        name = "gpu twist, lbs vs dqs"
    )
).pointer()

val m = Mesh(renderer, module)
module += "twist" to m
renderer += "gpu twist, lbs vs dqs" to module

val camera = Camera(renderer)
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(0, 2.4, -5.2)
camera.p()

val skinner = GpuSkinner(inst, instances = 2, renderer = renderer, name = "twist")
skinner.bindTo(m, camera, instanceCount = 2, tint = true)

// Seen from this camera +x is on the LEFT, so instance 0 goes to +1.3.
val places = listOf(Matrix4f().translate(vec(1.3, 0, 0)), Matrix4f().translate(vec(-1.3, 0, 0)))
val sk = skinner.skin.skeleton
val twist = sk.clip("twist")!!

// The waist as a number, from the CPU oracles on the same palette: the smallest distance from the
// bone axis of any vertex on the y = 2 ring. Printed so the picture is not the only evidence.
val restRadius = RiggedFixture.RADIUS
fun waist(method: SkinningMethod, palette: List<Matrix4f>): Float {
    val bind = FloatArray(skinner.vertexCount * 3) { skinner.bindPositions[it] }
    val out = GltfSkinning.skin(bind, null, inst.skin!!.influences, palette, SkinningOptions(method = method))
    return DualQuaternionSkinning.candyWrapperRadius(out.position, RiggedFixture.UPPER_JOINT_Y)
}

var n = 0
loop {
    // 0 -> 180 -> 0 degrees of twist, three seconds a cycle at 60 fps, on a loop.
    val seconds = (n / 90f) % twist.durationSeconds
    skinner.uploadPalettes(places.map { skinner.placedPalette(it, "twist", seconds) }, methods)
    skinner.updateCamera(m, camera)
    if (n % 45 == 0) {
        val rest = sk.palette(twist, seconds)
        println("t = %.2f s   waist, %% of rest radius:   %s %.1f%%   |   %s %.1f%%".format(
            seconds,
            methods[0], 100 * waist(methods[0], rest) / restRadius,
            methods[1], 100 * waist(methods[1], rest) / restRadius,
        ))
    }
    n++
    frame()
}
