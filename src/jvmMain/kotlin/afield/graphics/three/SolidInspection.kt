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
