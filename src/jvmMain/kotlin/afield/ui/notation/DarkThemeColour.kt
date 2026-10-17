package afield.ui.notation

import kotlin.math.roundToInt

/**
 * Colours as the drawing layer shows them. Excalidraw's dark theme draws the scene through the CSS filter
 * `invert(93%) hue-rotate(180deg)` (`theme="dark"` in `tools/excalidraw-bundle/entry.jsx`), so a colour stored
 * on an element is not the colour on screen. Per channel the inversion is `0.93 - 0.86 c`; the hue rotation is
 * the CSS filter matrix [H] for 180°.
 *
 * [compensate] gives the colour to store so that what is *shown* is as close as the filter allows to the colour
 * code asked for: `H (0.93 - 0.86 s) = t`, solved and then kept inside the displayable range by projected least
 * squares. Greys and many mid colours come out exact; warm and pale ones (`#ffec99`, `#e03131`) cannot be reached
 * and come out duller or shifted. The default ink [DEFAULT_INK] is left as the theme draws it.
 */
object DarkThemeColour {
    const val DEFAULT_INK = "#1e1e1e"

    private val H = arrayOf(
        doubleArrayOf(-0.574, 1.430, 0.144),
        doubleArrayOf(0.426, 0.430, 0.144),
        doubleArrayOf(0.426, 1.430, -0.856),
    )

    private fun shownOf(stored: DoubleArray): DoubleArray {
        val u = DoubleArray(3) { 0.93 - 0.86 * stored[it] }
        return DoubleArray(3) { r -> (H[r][0] * u[0] + H[r][1] * u[1] + H[r][2] * u[2]).coerceIn(0.0, 1.0) }
    }

    /** What the dark theme shows for a stored `#rrggbb`. */
    fun shown(storedHex: String): String = parse(storedHex)?.let { format(shownOf(it)) } ?: storedHex

    /** The `#rrggbb` to store so the dark theme shows [requestedHex]; anything that is not `#rrggbb` passes through. */
    fun compensate(requestedHex: String): String {
        // Excalidraw's default ink is theme-relative: it should stay light on dark, as hand-drawn marks are.
        if (requestedHex.trim().equals(DEFAULT_INK, ignoreCase = true)) return requestedHex
        val t = parse(requestedHex) ?: return requestedHex
        // Unconstrained solution, then projected gradient descent on ||H(0.93 - 0.86 s) - t||² over s in [0,1]³.
        val inv = invert(H)
        val u = DoubleArray(3) { r -> inv[r][0] * t[0] + inv[r][1] * t[1] + inv[r][2] * t[2] }
        val s = DoubleArray(3) { ((0.93 - u[it]) / 0.86).coerceIn(0.0, 1.0) }
        repeat(400) {
            val uNow = DoubleArray(3) { 0.93 - 0.86 * s[it] }
            val residual = DoubleArray(3) { r -> H[r][0] * uNow[0] + H[r][1] * uNow[1] + H[r][2] * uNow[2] - t[r] }
            for (c in 0..2) {
                val gradient = -0.86 * (H[0][c] * residual[0] + H[1][c] * residual[1] + H[2][c] * residual[2])
                s[c] = (s[c] - 0.15 * gradient).coerceIn(0.0, 1.0)
            }
        }
        return format(s)
    }

    private fun parse(hex: String): DoubleArray? {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6 || h.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return DoubleArray(3) { h.substring(2 * it, 2 * it + 2).toInt(16) / 255.0 }
    }

    private fun format(c: DoubleArray): String =
        "#" + c.joinToString("") { (it.coerceIn(0.0, 1.0) * 255).roundToInt().toString(16).padStart(2, '0') }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
        val (a, b, c) = m[0].let { Triple(it[0], it[1], it[2]) }
        val (d, e, f) = m[1].let { Triple(it[0], it[1], it[2]) }
        val (g, h, i) = m[2].let { Triple(it[0], it[1], it[2]) }
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return arrayOf(
            doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
        )
    }
}
