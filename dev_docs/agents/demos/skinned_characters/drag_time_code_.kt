import afield.controlGroup
import afield.displayOpen
import afield.four.modules.RiggedFixture
import afield.four.modules.posedGltfToTriMesh
import afield.stdlib.MeshView
import afield.stdlib.showMesh

// Drag `time`. The cylinder bends.
//
// `time` runs 0 -> 2 seconds through a clip that rotates the upper bone 0 -> 60 -> 0 degrees about
// +z. At 0 the tube is straight and the skinned vertices are bit-identical to the unskinned ones;
// at 1 the tip has swung -sqrt(3) in x, which is -2 sin 60 exactly. Drag `bend to curl` as well and
// the LOWER bone joins in, which is the parent chain composing.

val rig = RiggedFixture.locate()
val view = MeshView(yawDegrees = 0.0, pitchDegrees = 8.0, distance = 2.6)

var seconds = 0.6
var mix = 0.0

fun draw() {
    // blendedPalette mixes the two clips' joint matrices componentwise -- the same linear blend that
    // linear blend skinning itself commits to, so a limb shortens at 90 degrees here exactly as it
    // does there. Not a quaternion blend, deliberately, so the two agree.
    val mesh = posedGltfToTriMesh(rig) { s ->
        s.blendedPalette(s.clip("bend"), seconds.toFloat(), s.clip("curl"), seconds.toFloat(), mix.toFloat())
    }
    val b = mesh.bounds()
    // Printed as well as drawn, so the pose is a NUMBER and not only a picture -- at time 1, mix 0,
    // tip x is -1.982, which is -2 sin 60 minus the tube's own radius projected onto the bend.
    println("time %.2f s, bend->curl %.2f -> tip x %.4f, top y %.4f"
        .format(seconds, mix, b.min.x, b.max.y))
    showMesh(
        mesh, channel = "pose", view = view,
        caption = "time %.2f s, bend->curl %.2f — tip reaches x = %.3f".format(seconds, mix, b.min.x),
    )
}

controlGroup("controls") {
    slider("time", min = 0.0, max = 2.0, step = 0.01, initialValue = seconds) { seconds = it; draw() }
    slider("bend to curl", min = 0.0, max = 1.0, step = 0.01, initialValue = mix) { mix = it; draw() }
}
displayOpen("controls")
draw()
println("Drag `time` (0 to 2 s) in the controls above, or on /controllers.")
println("At time 0 and 2 the tube is straight; the extreme is at time 1.")
