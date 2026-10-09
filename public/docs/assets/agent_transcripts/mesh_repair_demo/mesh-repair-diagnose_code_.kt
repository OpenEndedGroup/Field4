// Why is this mesh not a solid?  —  afield.stdlib/mesh_repair.kt
//
// Everything here is generated in this box: no files, no network. The imports are half the
// point — diagnose() and showMeshDiagnosis exist only where mesh_repair.kt does, so if this
// box compiles at all, the Field running it has that code.

import afield.manifold.ManifoldLibrary
import afield.manifold.isValidSolid
import afield.stdlib.TriMesh
import afield.stdlib.diagnose
import afield.stdlib.icosphereMesh
import afield.stdlib.showMeshDiagnosis

run {

// ---- the damage. change these two and re-run ----------------------------------------
val holeFaces = 4        // faces to delete: an open hole. 1 gives a triangular one; 8 gives
                         // a figure-eight loop, which the diagnosis names as such.
val crackAFace = true    // give one triangle its own three vertices, as an STL export does
// --------------------------------------------------------------------------------------

// A sphere with a patch missing. An open boundary is the commonest reason a mesh off disk
// is refused, and until mesh_repair.kt there was nothing in Field that could fix one —
// weld does not fill a hole, and neither does fixWinding.
val base = icosphereMesh(3, 1.0)
var broken = base.filterTriangles { it >= holeFaces }

if (crackAFace) {
    val pos = broken.positions.copyOf(broken.positions.size + 9)
    val idx = broken.indices.copyOf()
    for (k in 0..2) {
        val v = broken.indices[k]
        val d = (broken.vertexCount + k) * 3
        pos[d] = broken.x(v); pos[d + 1] = broken.y(v); pos[d + 2] = broken.z(v)
        idx[k] = broken.vertexCount + k
    }
    broken = TriMesh(pos, idx)
}

if (ManifoldLibrary.isAvailable) println("valid solid? ${broken.isValidSolid()}")
println()

// Not a boolean: the ordered list of what is wrong, worst first, each with the call that
// addresses it. A pinch vertex and disjoint components do not block — Manifold accepts both,
// so they are reported at a lower severity rather than as failures.
println(broken.diagnose().report())

// The same facts as a picture. Red is an open boundary, orange a non-manifold edge,
// yellow a pinch vertex — so the red patch is where to look.
// No `view` argument: the camera aims itself at the worst defect, so the title and the
// picture are always about the same place on the mesh.
showMeshDiagnosis(
    broken,
    title = "$holeFaces faces removed" + (if (crackAFace) ", plus one unwelded face" else ""),
)

}
