@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.three

import afield.geometry.path.cross
import afield.geometry.path.div
import afield.geometry.path.dot
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

import afield.geometry.path.length
import afield.geometry.path.scale
import afield.geometry.solid.manifold.SolidPlanarMesh
import afield.geometry.path.planning.Point3D
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The three things the solid inspector measures off a part's boundary: which edges are worth
 * drawing, which triangles belong to the same face, and one string that identifies the whole set of
 * parts.
 *
 * A boundary arrives as a [SolidPlanarMesh] whose triangles carry the diagonals its triangulator
 * needed and the coplanar edges its Boolean history left behind, so drawing every edge buries the
 * shape under both. `solidOutlineEdges` keeps an edge unless exactly two triangles meet along it and
 * those two are one flat face: normals parallel and facing the same way, and each triangle's
 * vertices within tolerance of the other's plane. An edge where some other number of triangles meet,
 * or where a triangle is too degenerate to have a normal, stays visible, so an open surface keeps
 * its perimeter rather than reading as a closed solid. The tolerance is the mesh's own
 * `nativeTolerance` or sixty-four ulps of the part's largest coordinate, whichever is larger, so a
 * part modelled in metres and the same part in millimetres get the same answer.
 *
 * `solidPartTopology` groups the triangles into faces by merging across every edge that is not one
 * of those creases. It keeps the canonical double-precision positions and the original triangle
 * order, because a pick is resolved against this copy rather than against the render mesh, whose
 * vertices shading has already split.
 *
 * `solidArtifactDigest` hashes part names, positions, indices and faces in sorted name order, so the
 * same boundaries hash the same however the parts were collected. That digest is the geometry
 * identity a review note is pinned to, which is what lets a note outlive a relaunch.
 */

/** Exact boundary identity, including part names and topology; independent of input map order. */
fun solidArtifactDigest(parts: Map<String,SolidPlanarMesh>): String {
    val hash=MessageDigest.getInstance("SHA-256")
    fun number(value:Long) { hash.update(ByteBuffer.allocate(8).putLong(value).array()) }
    for((id,mesh)in parts.toSortedMap()) {
        val name=id.toByteArray(Charsets.UTF_8);number(name.size.toLong());hash.update(name)
        number(mesh.positions.size.toLong());mesh.positions.forEach { number(it.toBits()) }
        number(mesh.indices.size.toLong());mesh.indices.forEach { number(it.toLong()) }
        number(mesh.triangleFaces.size.toLong());mesh.triangleFaces.forEach { number(it.toLong()) }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

/** Per-part geometric edges; coplanar Boolean provenance boundaries are not panel seams. */
internal fun solidOutlineEdges(boundary:SolidPlanarMesh):List<Pair<Int,Int>> {
    val points=boundary.positions.toList().chunked(3).map{Point3D(it[0],it[1],it[2])}
    val normals=(0 until boundary.triangleCount).map{t->
        val a=points[boundary.indices[3*t]]
        val n=(points[boundary.indices[3*t+1]]-a) cross (points[boundary.indices[3*t+2]]-a)
        n.length.let{if(it>0 && it.isFinite())n*(1.0/it) else null}
    }
    val edges=linkedMapOf<Pair<Int,Int>,MutableList<Int>>()
    for(t in 0 until boundary.triangleCount)for(e in 0..2) {
        val a=boundary.indices[3*t+e];val b=boundary.indices[3*t+(e+1)%3]
        edges.getOrPut(minOf(a,b) to maxOf(a,b)){mutableListOf()}+=t
    }
    val scale=boundary.positions.maxOfOrNull{abs(it)}?:1.0
    val distanceTolerance=maxOf(boundary.nativeTolerance,64*Math.ulp(maxOf(1.0,scale)))
    return edges.filter{(_,incident)->
        // Preserve open/non-manifold or degenerate boundaries rather than concealing uncertainty.
        if(incident.size!=2)return@filter true
        val a=incident[0];val b=incident[1]
        val na=normals[a]?:return@filter true;val nb=normals[b]?:return@filter true
        if((na dot nb)<=0 || (na cross nb).length>1e-10)return@filter true
        val pa=points[boundary.indices[3*a]];val pb=points[boundary.indices[3*b]]
        (0..2).any{abs((points[boundary.indices[3*b+it]]-pa) dot na)>distanceTolerance ||
            abs((points[boundary.indices[3*a+it]]-pb) dot nb)>distanceTolerance}
    }.keys.toList()
}

/** Keep the pick topology in canonical doubles, before shading expands the render vertices. */
internal fun solidPartTopology(boundary: SolidPlanarMesh, edges: List<Pair<Int, Int>>): ThreePartTopology {
    val parent = IntArray(boundary.triangleCount) { it }
    fun root(t: Int): Int {
        var r = t
        while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
        return r
    }
    val creases = edges.toSet()
    val incident = mutableMapOf<Pair<Int, Int>, Int>()
    for (t in parent.indices) for (e in 0..2) {
        val a = boundary.indices[3*t+e]; val b = boundary.indices[3*t+(e+1)%3]
        val key = minOf(a,b) to maxOf(a,b)
        if (key !in creases) incident.putIfAbsent(key,t)?.let { parent[root(t)] = root(it) }
    }
    return ThreePartTopology(boundary.positions.copyOf(), boundary.indices.copyOf(),
        edges.map { listOf(it.first, it.second) }, IntArray(parent.size) { root(it) })
}
