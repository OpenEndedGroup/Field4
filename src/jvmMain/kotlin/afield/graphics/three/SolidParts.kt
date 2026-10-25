@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.three

import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

import afield.ui.visualization.inspect
import afield.ui.visualization.lines

import afield.geometry.solid.manifold.SolidPlanarMesh
import afield.geometry.mesh.TriMesh
import afield.geometry.path.planning.Point3D

/**
 * A set of named solid parts, shown in a Three.js scene as something to inspect rather than
 * something to look at. One call inside a `three { … }` block lays out every part as its own
 * pickable mesh, draws each one's creases and open boundaries over it as dark lines, and installs
 * the inspector: units, the explode slider, per-part visibility, assembly motions and the paths a
 * part came from.
 *
 * A part arrives as a planar mesh whose triangles carry the diagonals the triangulator needed and
 * the coplanar edges Boolean history left behind, so drawing every edge would bury the shape under
 * both. What is drawn instead is the geometric outline: creases between faces that meet at a real
 * angle, and boundaries where a part ends. Edges between separate parts stay visible, since those
 * are where the assembly is.
 *
 * Coordinates stay canonical throughout. Positions reach the GPU as `Float`s relative to one shared
 * render origin, and `upAxis = ThreeUpAxis.Z` is a rigid display rotation applied on the way out, so
 * neither large coordinates nor a Z-up convention changes the double-precision data the inspector
 * reports or the digest that identifies the artifact.
 */

// solidParts lives apart from SolidInspection.kt on purpose. The house kit pins SolidInspectionKt's class bytes as
// part of its geometry identity, and a call to ThreeSceneBuilder.mesh() compiles against mesh()'s whole parameter
// list: while this viewer function sat there, every parameter mesh() gained changed the pinned class and made every
// house's panel, recipe and fabrication builds stale, though no geometry or audit code had changed.

/**
 * Draw canonical double boundaries relative to one render origin. Boolean geometry is never read
 * back from these Float buffers. Lines follow geometric creases and open boundaries within each part;
 * coplanar construction boundaries and triangulation diagonals are omitted. Separate parts retain their edges.
 * Z-up is an optional rigid display rotation (x,z,-y); canonical data and artifact identity stay unchanged.
 * An overlay uses the same part ID/group as its solid, so visibility and supplied motions agree.
 * [panel] is the viewport that draws the parts in a multi-panel scene; `-1` draws them in every panel, which is how a
 * sheet of close-ups shows one assembly framed differently in each panel (`panel(bounds = …)`).
 */
fun ThreeSceneBuilder.solidParts(parts: Map<String,SolidPlanarMesh>,
    origin: Point3D = Point3D.ORIGIN, assembly: List<ThreeAssemblyMotion> = emptyList(),
    review: ThreeReviewState? = null, explode: ThreeExplode = ThreeExplode(), units: String = "mm",
    upAxis: ThreeUpAxis = ThreeUpAxis.Y, partPaths: Map<String,List<String>> = emptyMap(), panel: Int = 0) {
    require(parts.isNotEmpty() && parts.keys.none { it.isBlank() })
    require(assembly.map { it.part }.distinct().size==assembly.size && assembly.all { it.part in parts && it.displacement.size==3 && it.displacement.all(Double::isFinite) })
    fun displayVector(x: Double, y: Double, z: Double) =
        if (upAxis == ThreeUpAxis.Z) doubleArrayOf(x, z, -y) else doubleArrayOf(x, y, z)
    val displayedAssembly = assembly.map { motion -> motion.copy(displacement =
        displayVector(motion.displacement[0], motion.displacement[1], motion.displacement[2])) }
    val ids=parts.keys.sorted()
    val topology = linkedMapOf<String, ThreePartTopology>()
    for((group,id)in ids.withIndex()) {
        val boundary=parts.getValue(id)
        val positions=FloatArray(boundary.positions.size)
        for (i in boundary.positions.indices step 3) {
            val p=displayVector(boundary.positions[i]-origin.x, boundary.positions[i+1]-origin.y, boundary.positions[i+2]-origin.z)
            for (axis in 0..2) positions[i+axis]=p[axis].toFloat()
        }
        require(positions.all(Float::isFinite))
        mesh(TriMesh(positions,boundary.indices.copyOf()),id=id,label=id,wireframe=false,flatShading=true,pickable=true,group=group,panel=panel)
        val edges = solidOutlineEdges(boundary)
        topology[id] = solidPartTopology(boundary, edges)
        val lines=edges.flatMap { (a,b)->listOf(a,b).flatMap { i->listOf(positions[3*i],positions[3*i+1],positions[3*i+2]) } }.toFloatArray()
        drawables+=ThreeDrawable(ThreeGeometryData(mapOf("position" to ThreeAttribute(itemSize=3,array=lines),
            "color" to ThreeAttribute(itemSize=3,array=FloatArray(lines.size){.15f}))),ThreeDrawStyle(kind="lines"),id=id,group=group,panel=panel)
    }
    background("#080b0c");helpers(false)
    inspect(units=units,explode=explode,groups=ids,artifactDigest=solidArtifactDigest(parts),renderOrigin=doubleArrayOf(origin.x,origin.y,origin.z),renderUpAxis=upAxis,assembly=displayedAssembly,review=review)
    inspector = inspector!!.copy(parts = topology, partPaths = partPaths)
}
