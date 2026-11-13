package afield.graphics.three

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a view keeps between updates so a redraw sends only what changed.
 *
 * The wire already sends a description plus the arrays a viewer does not hold, by content hash
 * (`ThreeWire.kt`), so an unchanged part costs its few hundred bytes of description and nothing more. This
 * adds the one change a hash cannot see: a part that **moved rigidly**. Its positions (and normals) are new
 * numbers, so every hash differs, yet the viewer already has the geometry. [ThreeRigidParts] compares each part
 * with the geometry the viewer holds for it and, when the new positions are that geometry turned and moved,
 * sends the held geometry again with a `transform`. Nothing new is fetched, and the viewer moves the part by
 * its matrix.
 *
 * A part is known by its [ThreeDrawable.id] and its ordinal among the drawables with that id (a mesh and its
 * outline share one). Only parts the box placed without a transform of its own are compared, and not solids
 * with an exact boundary ([ThreeInspector.parts]) or meshes with per-vertex explode offsets, whose other data
 * would not follow the move.
 */
internal class ThreeRigidParts {
    private class Held(val geometry: ThreeGeometryData, val transform: FloatArray?)
    private var held = HashMap<String, Held>()

    /** Rigid moves found by the last [apply], for tests and the bench. */
    var lastRigidMoves = 0
        private set

    /** [payload] with every rigidly moved part replaced by its held geometry and a transform. */
    fun apply(payload: ThreeGeometryPayload): ThreeGeometryPayload {
        val canonical = payload.inspector?.parts?.keys.orEmpty()
        val ordinals = HashMap<String, Int>()
        val next = HashMap<String, Held>()
        var moves = 0
        val drawables = payload.drawables.map { d ->
            if (d.id.isBlank() || d.id in canonical) return@map d
            val ordinal = ordinals.merge(d.id, 1, Int::plus)!! - 1
            val key = d.id + "#" + ordinal
            if (d.transform != null || "explode" in d.geometry.attributes) return@map d
            val before = held[key]
            val moved = before?.let { rigidMove(it.geometry, d.geometry) }
            if (before == null || moved == null) {
                next[key] = Held(d.geometry, null)
                return@map d
            }
            next[key] = Held(before.geometry, moved)
            if (moved !== IDENTITY) moves++
            d.copy(geometry = before.geometry, transform = moved.takeUnless { it === IDENTITY })
        }
        held = next
        lastRigidMoves = moves
        return if (moves == 0 && drawables.zip(payload.drawables).all { (a, b) -> a === b }) payload else payload.copy(drawables = drawables)
    }

    companion object {
        /** A move too small to tell from float noise: send the held geometry as it is. */
        val IDENTITY = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

        /**
         * The column-major matrix taking [from]'s positions to [to]'s, when [to] is [from] turned and moved
         * (normals turned with it, every other array the same), [IDENTITY] when the two are the same within float
         * noise, or `null`.
         */
        fun rigidMove(from: ThreeGeometryData, to: ThreeGeometryData): FloatArray? {
            if (from.attributes.keys != to.attributes.keys) return null
            val p0 = from.attributes["position"]?.takeIf { it.itemSize == 3 }?.array ?: return null
            val p1 = to.attributes["position"]?.takeIf { it.itemSize == 3 }?.array ?: return null
            if (p0.size != p1.size || p0.size < 3) return null
            if (!sameIndex(from.index, to.index)) return null
            for ((name, a) in from.attributes) {
                if (name == "position" || name == "normal") continue
                val b = to.attributes.getValue(name)
                if (a.itemSize != b.itemSize || a.type != b.type || !(a.array === b.array || a.array.contentEquals(b.array))) return null
            }
            val n = p0.size / 3
            var scale = 1e-30
            for (v in p1) scale = max(scale, abs(v.toDouble()))
            for (v in p0) scale = max(scale, abs(v.toDouble()))
            // Float positions carry about 6e-8 relative error each, and the box's own transform adds a few more.
            val tolerance = scale * 4e-6
            // Three well-spread anchors: the first vertex, the one farthest from it, and the one farthest from
            // the line through both.
            fun d0(i: Int, j: Int) = sq(p0[3 * i] - p0[3 * j].toDouble(), p0[3 * i + 1] - p0[3 * j + 1].toDouble(), p0[3 * i + 2] - p0[3 * j + 2].toDouble())
            val a = 0
            var b = 0
            for (i in 1 until n) if (d0(i, a) > d0(b, a)) b = i
            val r = if (b == a) null else {
                val ux = p0[3 * b] - p0[3 * a].toDouble(); val uy = p0[3 * b + 1] - p0[3 * a + 1].toDouble(); val uz = p0[3 * b + 2] - p0[3 * a + 2].toDouble()
                var c = -1; var best = (tolerance * 8) * (tolerance * 8)
                val ul = sq(ux, uy, uz)
                for (i in 0 until n) {
                    val wx = p0[3 * i] - p0[3 * a].toDouble(); val wy = p0[3 * i + 1] - p0[3 * a + 1].toDouble(); val wz = p0[3 * i + 2] - p0[3 * a + 2].toDouble()
                    val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
                    val dist = sq(cx, cy, cz) / ul
                    if (dist > best) { best = dist; c = i }
                }
                if (c < 0) null else rotation(p0, p1, a, b, c)
            }
            // Degenerate (a point or collinear points): only a translation can be told.
            val rot = r ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            val tx = p1[0] - (rot[0] * p0[0] + rot[3] * p0[1] + rot[6] * p0[2])
            val ty = p1[1] - (rot[1] * p0[0] + rot[4] * p0[1] + rot[7] * p0[2])
            val tz = p1[2] - (rot[2] * p0[0] + rot[5] * p0[1] + rot[8] * p0[2])
            val t2 = tolerance * tolerance
            for (i in 0 until n) {
                val x = p0[3 * i].toDouble(); val y = p0[3 * i + 1].toDouble(); val z = p0[3 * i + 2].toDouble()
                val ex = rot[0] * x + rot[3] * y + rot[6] * z + tx - p1[3 * i]
                val ey = rot[1] * x + rot[4] * y + rot[7] * z + ty - p1[3 * i + 1]
                val ez = rot[2] * x + rot[5] * y + rot[8] * z + tz - p1[3 * i + 2]
                if (sq(ex, ey, ez) > t2) return null
            }
            val n0 = from.attributes["normal"]?.array
            val n1 = to.attributes["normal"]?.array
            if (n0 != null && n1 != null) {
                if (n0.size != n1.size) return null
                for (i in 0 until n0.size / 3) {
                    val x = n0[3 * i].toDouble(); val y = n0[3 * i + 1].toDouble(); val z = n0[3 * i + 2].toDouble()
                    val ex = rot[0] * x + rot[3] * y + rot[6] * z - n1[3 * i]
                    val ey = rot[1] * x + rot[4] * y + rot[7] * z - n1[3 * i + 1]
                    val ez = rot[2] * x + rot[5] * y + rot[8] * z - n1[3 * i + 2]
                    if (sq(ex, ey, ez) > 1e-6) return null
                }
            }
            val still = abs(rot[0] - 1) + abs(rot[4] - 1) + abs(rot[8] - 1) + abs(rot[1]) + abs(rot[2]) + abs(rot[3]) +
                abs(rot[5]) + abs(rot[6]) + abs(rot[7]) < 1e-7 && sq(tx, ty, tz) <= t2
            if (still) return IDENTITY
            return floatArrayOf(
                rot[0].toFloat(), rot[1].toFloat(), rot[2].toFloat(), 0f,
                rot[3].toFloat(), rot[4].toFloat(), rot[5].toFloat(), 0f,
                rot[6].toFloat(), rot[7].toFloat(), rot[8].toFloat(), 0f,
                tx.toFloat(), ty.toFloat(), tz.toFloat(), 1f,
            )
        }

        private fun sq(x: Double, y: Double, z: Double) = x * x + y * y + z * z

        private fun sameIndex(a: ThreeIndex?, b: ThreeIndex?): Boolean =
            if (a == null || b == null) a == null && b == null
            else a.type == b.type && (a.array === b.array || a.array.contentEquals(b.array))

        /** The rotation (column-major 3×3) taking the frame of anchors a, b, c in [p0] to theirs in [p1]. */
        private fun rotation(p0: FloatArray, p1: FloatArray, a: Int, b: Int, c: Int): DoubleArray? {
            fun frame(p: FloatArray): DoubleArray? {
                val ux = p[3 * b] - p[3 * a].toDouble(); val uy = p[3 * b + 1] - p[3 * a + 1].toDouble(); val uz = p[3 * b + 2] - p[3 * a + 2].toDouble()
                val ul = sqrt(sq(ux, uy, uz)); if (ul == 0.0) return null
                val e1x = ux / ul; val e1y = uy / ul; val e1z = uz / ul
                var wx = p[3 * c] - p[3 * a].toDouble(); var wy = p[3 * c + 1] - p[3 * a + 1].toDouble(); var wz = p[3 * c + 2] - p[3 * a + 2].toDouble()
                val dot = wx * e1x + wy * e1y + wz * e1z
                wx -= dot * e1x; wy -= dot * e1y; wz -= dot * e1z
                val wl = sqrt(sq(wx, wy, wz)); if (wl == 0.0) return null
                val e2x = wx / wl; val e2y = wy / wl; val e2z = wz / wl
                return doubleArrayOf(e1x, e1y, e1z, e2x, e2y, e2z, e1y * e2z - e1z * e2y, e1z * e2x - e1x * e2z, e1x * e2y - e1y * e2x)
            }
            val f0 = frame(p0) ?: return null
            val f1 = frame(p1) ?: return null
            // R = F1 · F0ᵀ, with each frame's columns its three unit vectors.
            val r = DoubleArray(9)
            for (col in 0..2) for (row in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += f1[3 * k + row] * f0[3 * k + col]
                r[3 * col + row] = sum
            }
            return r
        }
    }
}
