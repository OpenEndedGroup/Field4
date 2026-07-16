package afield.logger

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Colors the numeric tokens of a rolling-stats line (`"fps=61.2 upload=3.4MB net:102%"`) on a
 * blue -> green -> yellow -> orange -> red heatmap keyed by whether each value rose or fell
 * since the last line the same pinned channel emitted. A value identical to last time renders
 * grey. History is kept per channel+key so unrelated metrics sharing a channel don't interfere.
 */
object StatsHeatmap {
    data class Fragment(val text: String, val style: TextStyle?)

    private val TOKEN = Regex("""([A-Za-z_][\w]*)([=:]|\s)(-?\d+(?:\.\d+)?)([A-Za-z%/]*)""")
    private val previous = ConcurrentHashMap<String, Double>()

    /** Split [line] into plain/heatmap-colored fragments, updating [channel]'s per-key value history. */
    fun fragments(channel: String, line: String): List<Fragment> {
        val out = mutableListOf<Fragment>()
        var last = 0
        for (m in TOKEN.findAll(line)) {
            if (m.range.first > last) out += Fragment(line.substring(last, m.range.first), null)
            val (key, sep, numStr, unit) = m.destructured
            val value = numStr.toDouble()
            val prev = previous.put("$channel|$key", value)
            out += Fragment(key + sep, null)
            out += Fragment(numStr + unit, colorFor(prev, value))
            last = m.range.last + 1
        }
        if (last < line.length) out += Fragment(line.substring(last), null)
        return out
    }

    /** Drop [channel]'s value history (unpin/expire) so a later reuse doesn't diff against stale values. */
    fun reset(channel: String) {
        val prefix = "$channel|"
        previous.keys.removeAll { it.startsWith(prefix) }
    }

    private const val SATURATION_PCT = 0.5 // a +/-50% change saturates the scale
    private const val MIN_DENOM = 1e-9

    private val STOPS = listOf(
        -1.00 to Triple(70, 130, 240),  // blue   - decreasing sharply
        -0.50 to Triple(80, 200, 140),  // green  - decreasing
        0.00 to Triple(230, 200, 60),   // yellow - flat-ish
        0.50 to Triple(240, 140, 40),   // orange - increasing
        1.00 to Triple(220, 60, 60),    // red    - increasing sharply
    )

    private fun colorFor(prev: Double?, current: Double): TextStyle {
        if (prev == null || !prev.isFinite() || !current.isFinite() || current == prev) return TextColors.gray
        val pct = (current - prev) / maxOf(abs(prev), MIN_DENOM)
        return rgbAt((pct / SATURATION_PCT).coerceIn(-1.0, 1.0))
    }

    private fun rgbAt(t: Double): TextStyle {
        var i = 0
        while (i < STOPS.size - 2 && t > STOPS[i + 1].first) i++
        val (t0, c0) = STOPS[i]
        val (t1, c1) = STOPS[i + 1]
        val frac = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        val r = lerp(c0.first, c1.first, frac)
        val g = lerp(c0.second, c1.second, frac)
        val b = lerp(c0.third, c1.third, frac)
        return TextColors.rgb("#%02x%02x%02x".format(Locale.US, r, g, b))
    }

    private fun lerp(a: Int, b: Int, f: Double): Int = (a + (b - a) * f).roundToInt().coerceIn(0, 255)
}
