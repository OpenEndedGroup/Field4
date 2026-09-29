package afield.manifold

import afield.stdlib.planning.Point3D
import kotlin.math.abs

/** A finite physical contact is extracted from boundary triangles on this plane, not from an infinite cut. */
data class ContactPlane(val origin: Point3D, val normal: Point3D) {
    init { require(normal.length.isFinite() && normal.length > 0) }
}
data class ContactPatch(val plane: ContactPlane, val u: Point3D, val v: Point3D,
    val rings: List<List<Pair<Double,Double>>>, val area: Double)
data class ApertureAudit(val contact: ContactPatch, val apertureA: ContactPatch, val apertureB: ContactPatch,
    val expected: ContactPatch, val mismatchArea: Double, val expectedMismatchArea: Double,
    val tolerances: GeometryTolerances, val outsideContactArea: Double = 0.0,
    val insufficientMarginArea: Double = 0.0, val requiredMargin: Double = 0.0) {
    val passed: Boolean get() = contact.area>tolerances.area && expected.area>tolerances.area &&
        maxOf(mismatchArea,expectedMismatchArea,outsideContactArea,insufficientMarginArea)<=tolerances.area
}

/** Recenter in doubles, and retain native precision throughout the dimensional audit. */
private class ContactFrame(val plane: ContactPlane, val tolerance: Double) {
    val n=plane.normal.normalized()
    val u=((if(abs(n.z)<.9)Point3D(0.0,0.0,1.0) else Point3D(1.0,0.0,0.0)) cross n).normalized()
    val v=n cross u
    fun boundary(mesh: SolidPlanarMesh): Section {
        fun point(i:Int)=Point3D(mesh.positions[3*i],mesh.positions[3*i+1],mesh.positions[3*i+2])
        val polygons=mesh.indices.toList().chunked(3).mapNotNull { indices ->
            val p=indices.map { point(it)-plane.origin }
            if(p.any { abs(it dot n)>tolerance }) return@mapNotNull null
            val q=p.map { (it dot u) to (it dot v) }
            val area=(q[1].first-q[0].first)*(q[2].second-q[0].second)-(q[1].second-q[0].second)*(q[2].first-q[0].first)
            if(area==0.0)return@mapNotNull null
            if(area>0)q else q.reversed()
        }
        return Section.ofDoublePolygons(polygons)
    }
    fun patch(section: Section)=ContactPatch(plane,u,v,section.toDoublePolygons(),section.area)
}

fun planarContact(a: SolidPlanarMesh, b: SolidPlanarMesh, plane: ContactPlane,
    tolerances: GeometryTolerances = GeometryTolerances()): ContactPatch = Solid.scope {
    val frame=ContactFrame(plane,tolerances.length)
    frame.patch(frame.boundary(a) and frame.boundary(b))
}

/** Compare actual removed boundaries against an explicitly supplied expected aperture boundary on the same plane. */
fun auditApertures(stockA: SolidPlanarMesh, stockB: SolidPlanarMesh, removedA: SolidPlanarMesh,
    removedB: SolidPlanarMesh, expectedBoundary: SolidPlanarMesh, plane: ContactPlane,
    tolerances: GeometryTolerances = GeometryTolerances(), minimumContactMargin: Double = 0.0): ApertureAudit = Solid.scope {
    require(minimumContactMargin.isFinite() && minimumContactMargin>=0.0)
    val frame=ContactFrame(plane,tolerances.length)
    val contact=frame.boundary(stockA) and frame.boundary(stockB)
    val rawA=frame.boundary(removedA); val rawB=frame.boundary(removedB)
    val rawExpected=frame.boundary(expectedBoundary)
    val a=rawA and contact; val b=rawB and contact; val expected=rawExpected and contact
    val outside=maxOf((rawA-contact).area,(rawB-contact).area,(rawExpected-contact).area)
    val margin=if(minimumContactMargin>0.0)(expected-contact.offset(-minimumContactMargin)).area else 0.0
    fun difference(x:Section,y:Section)=(x-y).area+(y-x).area
    ApertureAudit(frame.patch(contact),frame.patch(a),frame.patch(b),frame.patch(expected),difference(a,b),
        maxOf(difference(a,expected),difference(b,expected)),tolerances,outside,margin,minimumContactMargin)
}

/** Discover boundary planes shared by two parts. Coincident triangles in remote parts produce no patch. */
fun planarContacts(a: SolidPlanarMesh, b: SolidPlanarMesh,
    tolerances: GeometryTolerances = GeometryTolerances()): List<ContactPatch> {
    if (!contactBoundsOverlap(a, b)) return emptyList()
    fun point(i:Int)=Point3D(a.positions[3*i],a.positions[3*i+1],a.positions[3*i+2])
    val planes=mutableListOf<ContactPlane>()
    for(face in a.faces) {
        val triangles=face.triangles.map { t->(0..2).map { point(a.indices[t*3+it]) } }
        val tri=triangles.maxByOrNull { ((it[1]-it[0]) cross (it[2]-it[0])).length } ?: continue
        val cross=(tri[1]-tri[0]) cross (tri[2]-tri[0]); if(cross.length==0.0)continue
        val n=cross.normalized()
        if(planes.any { abs(abs(it.normal dot n)-1)<1e-10 && abs((tri[0]-it.origin) dot it.normal)<=tolerances.length })continue
        planes+=ContactPlane(tri[0],n)
    }
    return planes.map { planarContact(a,b,it,tolerances) }.filter { it.area>tolerances.area }
}

/** Union of polygonal cylinders and spheres along a saved polyline; not a toolpath or exact circular sweep. */
fun polylineTube(points: List<Point3D>, radius: Double, radialSegments: Int = 48): Solid = Solid.scope {
    require(points.size>=2 && radius.isFinite() && radius>0 && radialSegments>=16 && radialSegments%4==0)
    val pieces=points.zipWithNext().map { (a,b)->
        val z=(b-a).normalized(); val ref=if(abs(z.z)<.9)Point3D(0.0,0.0,1.0) else Point3D(1.0,0.0,0.0)
        val x=(ref cross z).normalized(); val y=z cross x
        Solid.cylinder(a.distanceTo(b),radius,segments=radialSegments).transformed(
            x.x,x.y,x.z,y.x,y.y,y.z,z.x,z.y,z.z,a.x,a.y,a.z)
    }+points.drop(1).dropLast(1).map { Solid.sphere(radius,radialSegments).translated(it.x,it.y,it.z) }
    Solid.union(pieces).checked("polyline tube")
}

/** Length, area and volume thresholds in caller-chosen units. */
data class GeometryTolerances(val length: Double = 1e-6, val area: Double = 1e-4, val volume: Double = 1e-3) {
    init { require(listOf(length, area, volume).all { it.isFinite() && it > 0 }) }
}

// A broad-phase rejection only; coincident bounds are never evidence of contact.
private fun contactBoundsOverlap(a: SolidPlanarMesh, b: SolidPlanarMesh): Boolean {
    if (a.triangleCount == 0 || b.triangleCount == 0) return false
    fun bounds(mesh: SolidPlanarMesh): DoubleArray {
        val result = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        mesh.positions.forEachIndexed { i, value ->
            val axis = i % 3
            result[axis] = minOf(result[axis], value)
            result[axis + 3] = maxOf(result[axis + 3], value)
        }
        return result
    }
    val aa = bounds(a); val bb = bounds(b)
    return (0..2).all { aa[it] <= bb[it + 3] && bb[it] <= aa[it + 3] }
}
