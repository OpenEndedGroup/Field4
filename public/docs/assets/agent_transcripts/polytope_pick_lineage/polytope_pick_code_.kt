import afield.geometry.mesh.MeshView
import afield.geometry.polytope.ElementId
import afield.geometry.polytope.NormalPolicy
import afield.geometry.polytope.canonicalConway
import afield.geometry.polytope.triangulate
import afield.graphics.interaction.PickParts
import afield.graphics.interaction.pickScene
import afield.graphics.interaction.rayCamera
import afield.ui.visualization.showPointer

val chain = canonicalConway("bD")
fun sceneWith(normals: NormalPolicy) =
    chain.polyhedron.triangulate(normals = normals).let { geometry ->
        pickScene { add("Beveled dodecahedron", geometry.mesh, parts = PickParts.fromTriangulated(geometry)) }
    }
val flat = sceneWith(NormalPolicy.Flat)
val smooth = sceneWith(NormalPolicy.Crease(180.0))
val view = MeshView(width = 530, height = 340)
val camera = view.rayCamera(flat.mergedMesh())

var x = view.width / 2.0
var y = view.height / 2.0
var smoothNormals = false

fun drawPick() {
    val scene = if (smoothNormals) smooth else flat
    val hit = showPointer(scene, camera, vec(x, y), view)
    val face = hit?.externalId?.let(::ElementId)
    val source = face?.let { chain.sourceElements(it).single() }
    val report = if (face == null || source == null) {
        "No face at this pixel."
    } else {
            val seedPart = when (source.rank) {
                0 -> "vertex"
                1 -> "edge"
                else -> "pentagon"
            }
            val sides = chain.polyhedron.topology.faceCycle(face).size
            "$sides-sided face ${face.value} came from a seed $seedPart."
        }
    markdown("lineage", report)
    println(report)
}

controlGroup("Pointer") {
    slider("Horizontal pixel", min = 0.0, max = view.width - 1.0, step = 1.0, initialValue = x) {
        x = it
        drawPick()
    }
    slider("Vertical pixel", min = 0.0, max = view.height - 1.0, step = 1.0, initialValue = y) {
        y = it
        drawPick()
    }
    checkbox("Smooth normals", smoothNormals) {
        smoothNormals = it
        drawPick()
    }
}
drawPick()
