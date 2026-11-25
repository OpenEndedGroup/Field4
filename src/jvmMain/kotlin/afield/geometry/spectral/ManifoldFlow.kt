@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.bvh
import afield.geometry.mesh.topology
import afield.math.linalg.Vec3
import afield.math.linalg.Vector3d

/** A triangle and the three barycentric coordinates of a particle on it. */
class ManifoldParticle internal constructor(
    val face: Int,
    val a: Double,
    val b: Double,
    val c: Double,
)

/**
 * A piecewise tangent flow through adjacent faces. A particle stops at a boundary or where the
 * flow on both sides of an edge points into that edge. Steps never jump to a nearby sheet.
 */
class ManifoldFlow internal constructor(private val mesh: TriMesh, gradients: Array<Vec3>, perpendicular: Boolean) {
    private val topology = mesh.topology()
    private val bvh by lazy { mesh.bvh() }
    private val velocity = gradients.mapIndexed { face, gradient ->
        if (perpendicular) mesh.faceNormal(face).cross(gradient) else gradient
    }

    fun start(point: Vec3): ManifoldParticle {
        val hit = checkNotNull(bvh.closestPoint(point))
        return particle(hit.triangle, barycentric(hit.triangle, hit.point))
    }

    fun position(particle: ManifoldParticle): Vec3 {
        val (a, b, c) = mesh.triangle(particle.face)
        return a.mul(particle.a.toFloat()).fma(particle.b.toFloat(), b).fma(particle.c.toFloat(), c)
    }

    fun step(particle: ManifoldParticle, elapsed: Double): ManifoldParticle {
        require(elapsed.isFinite() && elapsed in 0.0..Float.MAX_VALUE.toDouble())
        var current = particle
        var remaining = elapsed
        var previous = -1
        repeat(32) {
            if (remaining == 0.0) return current
            val start = position(current)
            val end = Vec3(start).fma(remaining.toFloat(), velocity[current.face])
            val from = doubleArrayOf(current.a, current.b, current.c)
            val to = barycentric(current.face, end)
            var fraction = 1.0
            var crossed = -1
            for (i in 0..2) {
                if (to[i] >= -1e-8) continue
                val time = from[i] / (from[i] - to[i])
                if (time < fraction) {
                    fraction = time
                    crossed = i
                }
            }
            if (crossed < 0) return particle(current.face, to)
            val hit = particle(current.face, DoubleArray(3) { from[it] + fraction * (to[it] - from[it]) })
            val corners = intArrayOf(mesh.ia(current.face), mesh.ib(current.face), mesh.ic(current.face))
            val edge = topology.edgeOf(corners[(crossed + 1) % 3], corners[(crossed + 2) % 3])
            val next = topology.edgeTriangles[edge].firstOrNull { it != current.face } ?: return hit
            if (next == previous && fraction < 1e-6) return hit
            remaining *= 1 - fraction
            previous = current.face
            current = particle(next, barycentric(next, position(hit)))
        }
        return current
    }

    private fun barycentric(face: Int, point: Vec3): DoubleArray {
        val (a, b, c) = mesh.triangle(face)
        fun delta(p: Vec3) = Vector3d(p.x.toDouble() - a.x, p.y.toDouble() - a.y, p.z.toDouble() - a.z)
        val u = delta(b)
        val v = delta(c)
        val w = delta(point)
        val normal = u.cross(v, Vector3d())
        val det = normal.lengthSquared()
        val y = w.cross(v, Vector3d()).dot(normal) / det
        val z = u.cross(w, Vector3d()).dot(normal) / det
        return doubleArrayOf(1 - y - z, y, z)
    }

    private fun particle(face: Int, weights: DoubleArray): ManifoldParticle {
        val clamped = weights.map { it.coerceAtLeast(0.0) }
        val sum = clamped.sum()
        return ManifoldParticle(face, clamped[0] / sum, clamped[1] / sum, clamped[2] / sum)
    }
}
