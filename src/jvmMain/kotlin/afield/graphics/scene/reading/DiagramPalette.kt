package afield.graphics.scene.reading

import afield.math.linalg.Vector4f

/**
 * A ramp of colours that reading light is mapped onto, keeping the renderer's channel layout.
 *
 * Reading light is monochrome on the GPU: red and blue carry light, green carries the coverage that
 * selects blur, so no channel is left for hue. With the palette, alpha carries `light × u`, where
 * `u` is a position on this ramp: 0 at the first stop, 1 at the middle stop and 2 at the last.
 * Blending is additive, so overlapping light sums and blur averages `u` weighted by light; the
 * compositor divides alpha by light and looks `u` up here. Before alpha carried hue it carried
 * light, so anything drawn without a palette sits at `u = 1`: make the middle stop the neutral one.
 *
 * Item colours stay RGBA in the API. [coordinate] projects each to the nearest point of the ramp,
 * so colours off the palette land on its closest hue, and [DiagramScoreBuilder.tint] and
 * [DiagramScoreBuilder.saturate] move items along it.
 */
class DiagramPalette(stops: List<Vector4f>) {
    val stops: List<Vector4f> = stops.map { Vector4f(it.x, it.y, it.z, 1f) }

    init {
        require(this.stops.size in 3..MAX_STOPS && this.stops.size % 2 == 1) {
            "A palette needs an odd number of stops, 3 to $MAX_STOPS, so one stop is the middle (neutral) one; got ${stops.size}"
        }
        require(this.stops.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    }

    /** The colour at ramp position [u] (0..2), interpolated linearly between stops. */
    fun at(u: Float): Vector4f {
        val x = (u.coerceIn(0f, 2f) / 2f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        val f = x - i
        val a = stops[i]
        val b = stops[i + 1]
        return Vector4f(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f, 1f)
    }

    /** The ramp position (0..2) nearest to [color]'s RGB; its alpha is ignored. */
    fun coordinate(color: Vector4f): Float {
        var best = Float.MAX_VALUE
        var bestU = 1f
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
            val len = dx * dx + dy * dy + dz * dz
            val t = if (len < 1e-12f) 0f
            else (((color.x - a.x) * dx + (color.y - a.y) * dy + (color.z - a.z) * dz) / len).coerceIn(0f, 1f)
            val ex = a.x + dx * t - color.x; val ey = a.y + dy * t - color.y; val ez = a.z + dz * t - color.z
            val d = ex * ex + ey * ey + ez * ez
            if (d < best - 1e-9f) { best = d; bestU = 2f * (i + t) / (stops.size - 1) }
        }
        return bestU
    }

    companion object {
        /** The compositor has this many palette slots. */
        const val MAX_STOPS = 17

        /** A palette from `RRGGBB` hex strings, with or without a leading `#`. */
        fun hex(vararg colors: String) = DiagramPalette(colors.map { h ->
            val s = h.removePrefix("#")
            require(s.length == 6) { "Expected RRGGBB, got '$h'" }
            Vector4f(s.substring(0, 2).toInt(16) / 255f, s.substring(2, 4).toInt(16) / 255f, s.substring(4, 6).toInt(16) / 255f, 1f)
        })
    }
}
