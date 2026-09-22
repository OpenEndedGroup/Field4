import afield.four.modules.CpuSkinner
import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// The candy wrapper: one rig, one pose, two ways of averaging the joints.
//
// `useDualQuaternions = false` is linear blend skinning -- average the joint MATRICES. `true` is
// dual quaternion skinning -- average the rigid MOTIONS. Everything else is identical: same file,
// same palette, same clip, same frame. The only difference is `method`. Flip it and re-run.
//
// Why it happens, exactly: at the joint ring the weights are 0.5/0.5 (the rig has only two distinct
// weight tuples, [1,0,0,0] and [0.5,0.5,0,0]). Linear blending averages the joint MATRICES, so at
// 180 degrees about y it forms 0.5*I + 0.5*Ry(180) = diag(0, 1, 0), and every x and z on that ring
// is multiplied by zero. Dual quaternions average the joint MOTIONS, and the average of two
// rotations is a rotation.
val useDualQuaternions = false
val twistTime = 1.0f

// ONE mesh, deliberately. Drawing LBS and DQS side by side
// as two Mesh objects DOES NOT WORK: both read `inst`'s vertex buffer at draw time rather than at
// applyToMesh time, so both tubes show whichever pose was written LAST. Measured 2026-09-19 -- with
// lbs-then-dqs both rendered round, and with lbs-then-lbs both rendered pinched, while the printed
// numbers stayed correct. A two-mesh comparison here would be a figure that lies.
val renderer: Renderer = fromContext()

var (module, models) = gltfModelHere(RiggedFixture.locate())
var m = Mesh()
module += "tube" to m
renderer += "candy wrapper" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(2, 3, -8)
camera.p()

val inst = models[0].meshes[0]
val method =
    if (useDualQuaternions) GltfSkinning.SkinningMethod.DUAL_QUATERNION
    else GltfSkinning.SkinningMethod.LINEAR_BLEND
val skinner = CpuSkinner(inst, GltfSkinning.SkinningOptions(method = method))

val jointY = RiggedFixture.UPPER_JOINT_Y
val rest = DualQuaternionSkinning.candyWrapperRadius(skinner.pose("twist", 0f).position, jointY)
val out = skinner.pose("twist", twistTime)
inst.applyToMesh(m, camera, Matrix4f(), reuseBuffers = true)

// These four lines were left over from the two-mesh version this box replaced, and still named
// its `rL` / `rR` / `outR` / `rRest` -- so the box failed to compile with "Unresolved reference
// 'rRest'" from the moment the rewrite landed. Nothing caught it: box code is compiled by the K2
// REPL, not by Gradle, so `check` is green on a demo that cannot run. Found 2026-09-20 by running
// every box of this document one at a time, which is the only thing that does catch it.
val radius = DualQuaternionSkinning.candyWrapperRadius(out.position, jointY)
val label = if (useDualQuaternions) "dual quaternion" else "linear blend"
println("twist t = %.2f, min radius at the y = %.1f ring (rest %.4f)".format(twistTime, jointY, rest))
println("  %-16s %.6f   (%5.1f%% of rest)".format(label, radius, 100f * radius / rest))
println("  non-rigid joints ${out.nonRigidJoints}, antipodal flips ${out.antipodalFlips}")

loop { frame() }
