@file:afield.CompletionCategory("Math")

package afield.stdlib.planning

import kotlin.math.sqrt

/** Immutable double-precision coordinates; units are chosen by the caller. */
data class Point3D(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) { "Coordinates must be finite" } }
    /** Coordinate-wise addition, returning a new value. */
    operator fun plus(b: Point3D) = Point3D(x+b.x, y+b.y, z+b.z)
    /** Coordinate-wise difference, also usable as a displacement. */
    operator fun minus(b: Point3D) = Point3D(x-b.x, y-b.y, z-b.z)
    /** Negates all coordinates without changing this value. */
    operator fun unaryMinus() = this * -1.0
    /** Multiplies every coordinate by [scale]; non-finite results are rejected. */
    operator fun times(scale: Double) = Point3D(x*scale, y*scale, z*scale)
    /** Euclidean dot product, treating the coordinates as vectors. */
    infix fun dot(b: Point3D) = x*b.x+y*b.y+z*b.z
    /** Right-handed cross product, treating the coordinates as vectors. */
    infix fun cross(b: Point3D) = Point3D(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x)
    /** Euclidean distance from the origin in the caller's units. */
    val length: Double get() = sqrt(this dot this)
    /** Unit direction; rejects zero length and lengths that overflow. */
    fun normalized(): Point3D { val n=length; require(n.isFinite() && n>0); return this*(1.0/n) }
    companion object { val ORIGIN = Point3D(0.0,0.0,0.0) }
    /** Euclidean distance to [b] in the caller's units. */
    fun distanceTo(b: Point3D): Double = (this-b).length
}
