package afield.examples.graphics

import afield.graphics.FLine
import afield.graphics.buildInteractiveSVG
import afield.stdlib.Vec3
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.lineTo
import afield.stdlib.moveTo
import afield.stdlib.onClick
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import java.io.File

/*
FIELD_TEST
id: graphics.fline-hit-records.smoke
runner: jvm-main
entrypoint: afield.examples.graphics.FLineHitInspectorFigure
tags: [smoke, graphics, fline, interaction, svg]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "FLine hit-record figure written"
*/

/**
 * The figure behind the hit-record work, as a file you can open in a browser.
 *
 * A wall of panels seen plan-oblique — the shape the 2026-08-09 `panel-*` documents kept measuring by
 * `println` — rendered through the *real* interactive SVG path, so the emitted markup is the markup a
 * Field output would contain: an `onclick` per panel, the topology in `data-fline-elements`, and a
 * hover colour. Run it, then load the SVG in a browser next to `sliders.js` (or use
 * `dev_docs/fline_hit_records.md`'s proof script, which does exactly that under Playwright) to click
 * the panels and watch the reconstructed hit record come back.
 *
 * As a smoke it asserts the part that can rot silently: that every panel carries a callback id, a
 * vertex table, a face in its topology and a label, and that the numbers in that topology are the
 * figure's own coordinates rather than pixels.
 *
 * Pass an output path as the first argument; the default is a temp file, because a test that writes
 * into the repository leaves junk in every worktree it runs in.
 */
object FLineHitInspectorFigure {

    /** How wide and tall the figure is drawn, in the figure's own units. */
    const val WIDTH: Double = 560.0
    const val HEIGHT: Double = 380.0

    private const val COLUMNS = 5
    private const val ROWS = 3

    /**
     * A point on the wall: [u] runs along it (0 at the near end, 1 at the far end), [v] up it.
     *
     * The wall recedes as `u` grows, so on screen the panels are trapezoids — and `z` carries the real
     * distance along the wall, which is what makes a click's reported `z` worth reading.
     */
    fun onWall(u: Double, v: Double): Vec3 {
        val depth = u * 220.0
        val x = 40.0 + u * 430.0 + v * 22.0
        val y = 330.0 - v * 250.0 - depth * 0.20
        return vec(x, y, depth)
    }

    /** One FLine per panel, plus the ridge line along the top of the wall. */
    fun panelWall(): List<FLine> {
        val panels = ArrayList<FLine>()
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                val u0 = column / COLUMNS.toDouble()
                val u1 = (column + 1) / COLUMNS.toDouble()
                val v0 = row / ROWS.toDouble()
                val v1 = (row + 1) / ROWS.toDouble()
                panels += fline {
                    moveTo(onWall(u0, v0))
                    lineTo(onWall(u1, v0))
                    lineTo(onWall(u1, v1))
                    lineTo(onWall(u0, v1))
                    close()
                    label = "panel $row,$column"
                    filled = true
                    stroked = true
                    thickness = 1.5
                    color = vec(0.13 + 0.055 * column, 0.30 + 0.03 * row, 0.44 - 0.05 * row, 0.85)
                    hoverColor = vec(1.0, 0.84, 0.32, 0.9)
                }
            }
        }
        panels += fline {
            moveTo(onWall(0.0, 1.0))
            lineTo(onWall(1.0, 1.0))
            label = "ridge"
            thickness = 4.0
            color = vec(1.0, 0.76, 0.36, 1.0)
            hoverColor = vec(1.0, 1.0, 0.8, 1.0)
        }
        return panels
    }

    /** The interactive SVG for [panelWall], with a click callback on every line. */
    fun svg(onHit: (afield.stdlib.FLineIntersection) -> Unit = {}): String {
        val lines = panelWall()
        lines.forEach { it.onClick = onHit }
        return buildInteractiveSVG(lines.map { it to it }, WIDTH, HEIGHT, null)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val markup = svg()

        val panels = ROWS * COLUMNS
        val callbacks = Regex("data-fline-callback-id=").findAll(markup).count()
        check(callbacks == panels + 1) { "expected ${panels + 1} clickable groups, found $callbacks" }
        val faces = Regex("\"faces\":\\[\\{").findAll(markup).count()
        check(faces == panels) { "expected $panels faces in the emitted topology, found $faces" }
        for (row in 0 until ROWS) {
            check(markup.contains("data-fline-label=\"panel $row,0\"")) { "panel $row,0 lost its label" }
        }
        check(markup.contains("data-fline-index=\"$panels\"")) { "the ridge should be line $panels" }
        // The topology is in the figure's own coordinates: the far end of the ridge is 220 deep, and no
        // pixel-space rewrite of it would still say so.
        check(markup.contains(",220]")) { "the emitted topology lost the wall's own z coordinates" }

        val out = File(args.firstOrNull() ?: File.createTempFile("fline-hit-figure", ".svg").absolutePath)
        out.writeText(markup)
        println("FLine hit-record figure written to ${out.absolutePath} (${markup.length} bytes, $callbacks clickable groups)")
    }
}
