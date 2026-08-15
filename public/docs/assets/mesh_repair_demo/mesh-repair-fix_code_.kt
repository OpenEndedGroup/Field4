// Repair it, cut it, and be told what could not be done.

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.manifold.toSolid
import afield.stdlib.TriMesh
import afield.stdlib.defectFLines
import afield.stdlib.icosphereMesh
import afield.stdlib.repairToSolid
import afield.stdlib.signedVolume

run {

val holeFaces = 4
val base = icosphereMesh(3, 1.0)
var broken = base.filterTriangles { it >= holeFaces }
val pos = broken.positions.copyOf(broken.positions.size + 9)
val idx = broken.indices.copyOf()
for (k in 0..2) {
    val v = broken.indices[k]
    val d = (broken.vertexCount + k) * 3
    pos[d] = broken.x(v); pos[d + 1] = broken.y(v); pos[d + 2] = broken.z(v)
    idx[k] = broken.vertexCount + k
}
broken = TriMesh(pos, idx)

// repairToSolid returns a RESULT, not a mesh. The mesh is one field of it. The rest is the
// story: what each step changed, one line per hole with how it was filled, and `unfixed` —
// the blocking defects still present, each with the reason nothing was done about it.
// A repair that hands back only a mesh cannot be audited, which is why this one does not.
val result = broken.repairToSolid()
println(result.report())

// The defects as drawable lines, on their own channel.
display("defects", broken.defectFLines())

if (!ManifoldLibrary.isAvailable) { println(ManifoldLibrary.describe()); return@run }
if (!result.isSolid) { println("still not a solid — see the unfixed list above"); return@run }

// It is a solid now, so it can be cut. That was the whole point.
val solid = result.mesh.toSolid()
val knife = Solid.cube(4.0, 4.0, 4.0, center = true).translated(0.0, 0.0, 2.0)
val lower = solid - knife
println()
println("whole sphere     ${"%.5f".format(base.signedVolume())}")
println("repaired volume  ${"%.5f".format(solid.volume)}   (the flat cap loses a sliver)")
println("after the cut    ${"%.5f".format(lower.volume)}   (half, cut at the equator)")

showMeshGrid(
    "broken — an open hole" to broken,
    "repaired — capped" to result.mesh,
    // The PLAIN mesh, not toTriMesh(normals = true): asking for normals splits a vertex per
    // face at every hard edge, which is right for shading and makes the caption's
    // isWatertight() read false on a solid. See Solid.toTriMesh's KDoc.
    "and cut" to lower.toTriMesh(),
    columns = 3,
    cellWidth = 300,
)
lower.close(); knife.close(); solid.close()

}
