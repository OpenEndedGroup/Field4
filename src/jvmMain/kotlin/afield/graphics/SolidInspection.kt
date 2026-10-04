@file:afield.CompletionCategory("Graphics")

package afield.graphics

import afield.manifold.SolidPlanarMesh
import afield.stdlib.TriMesh
import afield.stdlib.planning.Point3D
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

/**
 * Draw canonical double boundaries relative to one render origin. Boolean geometry is never read
 * back from these Float buffers. Lines follow geometric creases and open boundaries within each part;
 * coplanar construction boundaries and triangulation diagonals are omitted. Separate parts retain their edges.
 * Z-up is an optional rigid display rotation (x,z,-y); canonical data and artifact identity stay unchanged.
 * An overlay uses the same part ID/group as its solid, so visibility and supplied motions agree.
 */
fun ThreeSceneBuilder.solidParts(parts: Map<String,SolidPlanarMesh>,
    origin: Point3D = Point3D.ORIGIN, assembly: List<ThreeAssemblyMotion> = emptyList(),
    review: ThreeReviewState? = null, explode: ThreeExplode = ThreeExplode(), units: String = "mm",
    upAxis: ThreeUpAxis = ThreeUpAxis.Y, partPaths: Map<String,List<String>> = emptyMap()) {
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
        mesh(TriMesh(positions,boundary.indices.copyOf()),id=id,label=id,wireframe=false,flatShading=true,pickable=true,group=group)
        val edges = solidOutlineEdges(boundary)
        topology[id] = solidPartTopology(boundary, edges)
        val lines=edges.flatMap { (a,b)->listOf(a,b).flatMap { i->listOf(positions[3*i],positions[3*i+1],positions[3*i+2]) } }.toFloatArray()
        drawables+=ThreeDrawable(ThreeGeometryData(mapOf("position" to ThreeAttribute(itemSize=3,array=lines),
            "color" to ThreeAttribute(itemSize=3,array=FloatArray(lines.size){.15f}))),ThreeDrawStyle(kind="lines"),id=id,group=group)
    }
    background("#080b0c");helpers(false)
    inspect(units=units,explode=explode,groups=ids,artifactDigest=solidArtifactDigest(parts),renderOrigin=doubleArrayOf(origin.x,origin.y,origin.z),renderUpAxis=upAxis,assembly=displayedAssembly,review=review)
    inspector = inspector!!.copy(parts = topology, partPaths = partPaths)
}
