@file:afield.CompletionCategory("ALife")

package afield.stdlib

import afield.Colormaps
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Inline `ToHTML` views for the `alife*.kt` family — what a box draws when you *evaluate* an [IntGrid]
 * or a [GrayScottState], with nothing to set up.
 *
 * This file exists for the same reason `wfc_field.kt` does. The family's own files stay free of any
 * dependency on Field's visualizer: `alife.kt` and `alife_continuous.kt` each name exactly one symbol
 * from here ([alifeGridHtml], [alifeGrayScottHtml]) and inherit every pixel of their view from it. So a
 * reader of `alife.kt` sees integer lattices and nothing about HTML, and the two files can be understood
 * separately.
 *
 * **Range-policy discipline, which is the whole difference between a heatmap and a lie.** Copied
 * separately from `wfc_field.kt`, because these are the rules that stop an inline view being
 * decoration:
 *
 *  - **Every scale is named in the caption, with both endpoints spelled out in the units of the thing
 *    being shown.** A picture whose scale is a mystery says nothing.
 *  - **Domains are fixed by meaning, not by the frame.** An [IntGrid]'s states run `0 .. maxState`; a
 *    Gray–Scott `v` runs `0 .. 1`, the range the reaction is defined on, *not* `min .. max` of this
 *    particular field. Auto-ranging per frame is how a saturated Lenia field — constant at 1 — rendered
 *    as solid black and looked exactly like death; that trap cost this family a figure, and it is a trap
 *    an inline view walks into on every evaluation rather than once.
 *  - **One pixel per cell, never resampled.** The `<img>` is scaled by an integer factor with
 *    `image-rendering:pixelated`, so cell coordinates and pixel coordinates stay the same thing and a
 *    reader can count. A filtered upscale destroys exactly the local structure a CA is about.
 *  - **A flat field says so.** If every cell holds the same value the caption reports it, because a
 *    uniform panel is otherwise indistinguishable from a broken one.
 *
 * These views are checked, not eyeballed: `AlifeGallery`'s `inline-view` figure decodes the base64 PNG
 * back out of the emitted HTML and asserts the colour of individual pixels against the value the caption
 * claims for them — the same thing `WfcSmokeTest` does for the solver's view.
 */

/** The family's caption grey, matching `wfc_field.kt` so the two inline views look like one system. */
private const val ALIFE_LABEL = "#c8cee0"
private const val ALIFE_NOTE_STYLE = "color:#8a90a0;font-size:10px;margin-top:2px;line-height:1.35"

/** Widest a panel is drawn. Small grids are integer-upscaled toward it; large ones are left alone. */
private const val ALIFE_PANEL_MAX_SIDE = 240

/**
 * One panel: the pixels, an optional colour-ramp legend, a title and a caption.
 *
 * Scaling is **integer only**. A grid wider than [ALIFE_PANEL_MAX_SIDE] is emitted at its natural size
 * and left to the browser's `image-rendering:pixelated`, rather than being downsampled here — throwing
 * cells away to make a thumbnail is the one thing a CA view must not do, because the discarded cells are
 * the structure.
 */
private fun alifePanelHtml(img: Img, title: String, legend: String?, caption: String): String {
    val longest = max(img.width, img.height)
    val zoom = max(1, ALIFE_PANEL_MAX_SIDE / max(1, longest))
    val dw = img.width * zoom
    val dh = img.height * zoom
    val b64 = Base64.getEncoder().encodeToString(img.toPngBytes())
    return """
        <div style="width:${max(dw, 170)}px">
          <div style="color:$ALIFE_LABEL;margin-bottom:2px">$title</div>
          <img src="data:image/png;base64,$b64" width="$dw" height="$dh"
               style="image-rendering:pixelated;border:1px solid #3a3f4b;border-radius:3px;display:block"/>
          ${legend ?: ""}
          <div style="$ALIFE_NOTE_STYLE">$caption</div>
        </div>
    """.trimIndent()
}

/** A gradient swatch of colormap [name] with both endpoints labelled, so the scale is readable. */
private fun alifeRampLegendHtml(name: String, loLabel: String, hiLabel: String): String {
    val stops = (0..8).joinToString(", ") { i ->
        val c = Colormaps.sample(name, i / 8.0)
        fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt().coerceIn(0, 255)
        "rgb(${q(c.r)},${q(c.g)},${q(c.b)}) ${i * 100 / 8}%"
    }
    return """
        <div style="margin-top:3px">
          <div style="height:6px;border-radius:2px;background:linear-gradient(to right, $stops)"></div>
          <div style="display:flex;justify-content:space-between;color:#8a90a0;font-size:10px">
            <span>$loLabel</span><span>$hiLabel</span>
          </div>
        </div>
    """.trimIndent()
}

private fun alifeRowHtml(header: String, panels: List<String>): String = """
    <div style="font-family:monospace;font-size:11px;line-height:1.4">
      $header
      <div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:5px">
        ${panels.joinToString("\n")}
      </div>
    </div>
""".trimIndent()

private fun alifeNum(v: Double): String = when {
    v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e9 -> v.toLong().toString()
    kotlin.math.abs(v) >= 0.01 -> "%.3f".format(v)
    else -> "%.2e".format(v)
}

// ==============================================================================================
// IntGrid
// ==============================================================================================

/**
 * The body of [IntGrid.toHTML]: the lattice itself, one pixel per cell, plus the two readings that say
 * whether it is doing anything — population and state entropy.
 *
 * A two-state grid is drawn grayscale (dead black, alive white) because that is what every reader of a
 * Life board expects and a colour map would only obscure. A grid with more states is drawn with
 * [cyclicPalette], the same repeating hue ring the cyclic-CA figures use, so a *cyclic* soup evaluated in
 * a box looks like the same object the docs page shows rather than a different one.
 *
 * The **state count is read from the data** (`maxState() + 1`) and named in the caption. This is the one
 * place where "fixed by meaning, not by the frame" has to give a little: an [IntGrid] does not carry its
 * rule's state count, so a 14-state cyclic soup that happens to have died back to 9 distinct states will
 * report 9. The caption therefore says "states observed", not "states", and that is the accurate wording
 * rather than a number that looks more authoritative than it is.
 */
fun alifeGridHtml(grid: IntGrid): String {
    val states = grid.maxState() + 1
    val population = grid.population()
    val entropy = grid.stateEntropyBits(states)
    val flat = states <= 1
    val border = if (grid.wrap) "toroidal" else "bounded (outside is dead)"

    val header = "<div style=\"color:$ALIFE_LABEL\">" + listOf(
        "IntGrid ${grid.width}&times;${grid.height}",
        "$states state${if (states == 1) "" else "s"} observed",
        "$border",
        "population $population/${grid.size} (${"%.1f".format(100.0 * population / grid.size)}% non-zero)",
        "state entropy ${alifeNum(entropy)} bits of ${alifeNum(kotlin.math.ln(states.toDouble().coerceAtLeast(1.0)) / kotlin.math.ln(2.0))}"
    ).joinToString(" &middot; ") + "</div>"

    val panel = if (states <= 2) {
        alifePanelHtml(
            grid.toImg(2),
            "cells",
            null,
            "one pixel per cell &middot; black = state 0 (dead), white = state 1 (alive)" +
                if (flat) " &middot; <b>every cell is 0</b>: this grid is empty" else ""
        )
    } else {
        alifePanelHtml(
            grid.toImg(cyclicPalette(states)),
            "cells",
            null,
            "one pixel per cell &middot; hue = state, a repeating ring over the $states states observed " +
                "(cyclicPalette), so state 0 and state $states would share a colour"
        )
    }
    return alifeRowHtml(header, listOf(panel))
}

// ==============================================================================================
// GrayScottState
// ==============================================================================================

/**
 * The body of [GrayScottState.toHTML]: both reagent fields side by side on **fixed `0 .. 1` scales**, plus
 * the morphology reading that the docs page's regime claims are actually made through.
 *
 * Fixed scales, not `min .. max`, and this is the whole reason this function is worth writing rather than
 * letting `Img`'s own thumbnail handle it. `Img.toHTML` auto-normalises, so an unseeded field — `v = 0`
 * everywhere, a fixed point, the *control arm* — normalises to whatever numerical dust it contains and
 * renders as a rich texture. On a `0 .. 1` scale it renders black, which is what it is. The captions name
 * both endpoints so the reader can tell the two situations apart without trusting the picture.
 */
fun alifeGrayScottHtml(state: GrayScottState): String {
    val report = try {
        state.v.morphology()
    } catch (t: Throwable) {
        null
    }
    val vLo = state.v.min()
    val vHi = state.v.max()
    val header = "<div style=\"color:$ALIFE_LABEL\">" + listOfNotNull(
        "GrayScottState ${state.width}&times;${state.height}",
        "v in ${alifeNum(vLo.toDouble())}&hellip;${alifeNum(vHi.toDouble())}",
        report?.let { "coverage ${"%.3f".format(it.coverage)} &middot; ${it.blobCount} blob${if (it.blobCount == 1) "" else "s"}" },
        report?.takeIf { it.blobCount > 0 }?.let { "median P&sup2;/4&pi;A ${"%.2f".format(it.medianIsoperimetric)}" }
    ).joinToString(" &middot; ") + "</div>"

    val degenerate = vHi - vLo < 1e-6f
    val note = if (degenerate) {
        " &middot; <b>the field is flat at ${alifeNum(vHi.toDouble())}</b> — v = 0 is a fixed point, so an " +
            "unseeded run stays here forever. Note this panel is on a FIXED 0&hellip;1 scale: auto-ranging " +
            "would turn this into a texture"
    } else ""

    val panels = listOf(
        alifePanelHtml(
            state.v.colormap("magma", 0f, 1f),
            "v (autocatalyst)",
            alifeRampLegendHtml("magma", "0", "1"),
            "colour = v, magma on a FIXED 0&hellip;1 scale (the range the reaction is defined on, not this " +
                "field's own min&hellip;max)$note"
        ),
        alifePanelHtml(
            state.u.colormap("viridis", 0f, 1f),
            "u (substrate)",
            alifeRampLegendHtml("viridis", "0", "1"),
            "colour = u, viridis on the same FIXED 0&hellip;1 scale &middot; u and v are complementary: " +
                "the autocatalyst consumes the substrate, so this is close to the negative of the panel " +
                "beside it"
        )
    )
    return alifeRowHtml(header, panels)
}

// ==============================================================================================
// LeniaRun
// ==============================================================================================

/**
 * The body of [LeniaRun.toHTML]: the final field on a **fixed `0 .. 1` scale**, the measured phase, and
 * the mass history as a sparkline.
 *
 * The fixed scale is not a style preference here, it is the fix for a specific lie this family already
 * told once. A `SATURATION` field is constant at 1. Auto-ranged, it maps to the *dark* end of the colour
 * map and renders as solid black — indistinguishable from `DEATH`, which is the opposite of what anyone
 * looking at it wants to know. The phase word in the header and the fixed scale in the panel are two
 * independent ways to tell them apart, which is one more than a picture alone gives.
 */
fun alifeLeniaRunHtml(run: LeniaRun): String {
    val phaseColor = when (run.phase) {
        LeniaPhase.DEATH -> "#c86f6f"
        LeniaPhase.SATURATION -> "#c8b76f"
        LeniaPhase.BOUNDED -> "#7ec96f"
    }
    val header = "<div style=\"color:$ALIFE_LABEL\">" + listOf(
        "LeniaRun ${run.field.width}&times;${run.field.height}",
        "<b style=\"color:$phaseColor\">${run.phase}</b>",
        "mean field ${"%.4f".format(run.finalMass)}",
        "${run.blobCount} localised feature${if (run.blobCount == 1) "" else "s"}",
        "drift ${"%.2f".format(run.centroidDrift)} cells"
    ).joinToString(" &middot; ") + "</div>"

    val panels = listOf(
        alifePanelHtml(
            run.field.colormap("inferno", 0f, 1f),
            "final field",
            alifeRampLegendHtml("inferno", "0 (empty)", "1 (saturated)"),
            "colour = A, inferno on a FIXED 0&hellip;1 scale. A saturated field is constant at 1; an " +
                "auto-ranged map draws it BLACK and it becomes indistinguishable from death, which is why " +
                "this scale is not derived from the frame"
        ),
        alifePanelHtml(
            leniaMassSparkline(run.massHistory, 120, 40),
            "mean field over time",
            null,
            "${run.massHistory.size} samples, y scaled to 0&hellip;1 (the same fixed range as the panel " +
                "beside it), so a flat line at the top is saturation and a flat line at the bottom is death"
        )
    )
    return alifeRowHtml(header, panels)
}

/**
 * A [values] sparkline as a 1-channel [Img], `y` fixed to `0 .. 1` — *not* auto-scaled, so a
 * death curve and a saturation curve are drawn in different places on the panel rather than both filling
 * it.
 */
private fun leniaMassSparkline(values: DoubleArray, w: Int, h: Int): Img {
    val out = Img.zeros(w, h, 1)
    if (values.isEmpty()) return out
    // A dim baseline, so an all-zero (death) history is still legible as a line at the bottom rather
    // than as an empty panel that might equally be a rendering failure.
    for (x in 0 until w) out.data[(h - 1) * w + x] = 0.18f
    var prevX = 0
    var prevY = h - 1 - (values[0].coerceIn(0.0, 1.0) * (h - 1)).roundToInt()
    for (i in values.indices) {
        val x = if (values.size == 1) 0 else (i.toDouble() / (values.size - 1) * (w - 1)).roundToInt()
        val y = h - 1 - (values[i].coerceIn(0.0, 1.0) * (h - 1)).roundToInt()
        // Bresenham, so a steep segment is continuous rather than a dotted line.
        var x0 = prevX
        var y0 = prevY
        val dx = kotlin.math.abs(x - x0)
        val sx = if (x0 < x) 1 else -1
        val dy = -kotlin.math.abs(y - y0)
        val sy = if (y0 < y) 1 else -1
        var err = dx + dy
        while (true) {
            if (x0 in 0 until w && y0 in 0 until h) out.data[y0 * w + x0] = 1f
            if (x0 == x && y0 == y) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
        prevX = x
        prevY = y
    }
    return out
}
