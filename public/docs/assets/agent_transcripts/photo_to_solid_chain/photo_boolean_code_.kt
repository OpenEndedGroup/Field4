import afield.examples.chain.PhotoToSolidChain
import afield.manifold.Solid
import afield.manifold.toSolid

val mount = PhotoToSolidChain.mount()
println(mount.summary())

// Booleans, with the identities any two solids owe each other checked rather than trusted.
Solid.scope {
    val plaque = mount.relief.toSolid() + mount.frame.toSolid()
    val frame = PhotoToSolidChain.frameAround(2.0, mount.footprintZ)
    val union = plaque + frame
    val meet = plaque and frame
    println("frame genus ${frame.genus} — a slab with a window is a torus")
    println("mounted genus ${union.genus} in ${union.decompose().size} part — the plaque plugs it")
    println("union %.9f + meet %.9f = %.9f".format(union.volume, meet.volume, union.volume + meet.volume))
    println("plaque %.9f + frame %.9f = %.9f".format(plaque.volume, frame.volume, plaque.volume + frame.volume))
    println("inclusion-exclusion holds to ${"%.2e".format(kotlin.math.abs((union.volume + meet.volume) - (plaque.volume + frame.volume)))}")
}

// mount()'s meshes carry NO normals on purpose — splitting a vertex at a crease is what a
// renderer wants and is exactly what stops toSolid() accepting the mesh back. renderPng
// adds them itself, so nothing here has to care.
fun draw(m: TriMesh, yaw: Double = 30.0, pitch: Double = 34.0) =
    imgFromBytes(m.renderPng(MeshView(yawDegrees = yaw, pitchDegrees = pitch, width = 760, height = 560)))

showImgGrid(
    "relief = plaque - frame" to draw(mount.relief),
    "the frame, genus 1" to draw(mount.frame),
    "assembled, genus 0" to draw(triMesh { add(mount.relief); add(mount.frame) }),
    columns = 3,
    cellSide = 460,
)
