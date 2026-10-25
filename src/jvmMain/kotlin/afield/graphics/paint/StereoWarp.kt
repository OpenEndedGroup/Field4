@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.paint

/**
 * A smooth map between the two canvases of a stereo pair, so canvas texture such as weave and
 * grain sits on the painted surface in both eyes: [StereoWarp].
 */

import afield.geometry.path.FLine
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Where each place on one eye's canvas of a stereo pair is on the other's (the reference): a
 * rubber sheet stretched between pins, as dense as a grid every [cell] canvas units.
 *
 * Two eyes see a painted surface at different places on their canvases. A stroke's own noise
 * already corresponds between them (`seed`, [PaintCanvas.gesture3d]). Texture that belongs to the
 * canvas's places does not: the ground's weave and priming, a watercolour sheet's fibres, a
 * pencil's grain, tacky paint's grab. It lands in the same place on both canvases, so it reads at
 * the depth of the screen. With a warp ([PaintCanvas.warp]), the canvas looks that texture up where
 * the place is on the reference canvas, so it sits on the surface in both eyes.
 *
 * [solve] makes one from [Pin]s (a place here and the same place on the reference) by relaxing the
 * displacement between them, as a membrane would (Laplace's equation). [pins] makes pins from
 * lines in 3D seen by both eyes. Between the lines, the sheet is pulled smoothly from one line's
 * disparity to the next.
 *
 * @property columns grid nodes across.
 * @property rows grid nodes down.
 * @property cell canvas units between nodes.
 */
class StereoWarp internal constructor(val columns: Int, val rows: Int, val cell: Double, internal val at: FloatArray) {

    /** A place on this canvas ([here]) and the same place on the reference ([there]), canvas units. */
    data class Pin(val here: Vec2, val there: Vec2)

    /** Where canvas position [p] is on the reference canvas. */
    fun map(p: Vec2): Vec2 {
        val (u, v) = p.x / cell to p.y / cell
        val (cu, cv) = u.coerceIn(0.0, columns - 1.0) to v.coerceIn(0.0, rows - 1.0)
        val i = cu.toInt().coerceAtMost(columns - 2); val j = cv.toInt().coerceAtMost(rows - 2)
        val (fu, fv) = cu - i to cv - j
        fun n(a: Int, b: Int, k: Int) = at[2 * (b * columns + a) + k].toDouble()
        fun l(k: Int) = (n(i, j, k) * (1 - fu) + n(i + 1, j, k) * fu) * (1 - fv) + (n(i, j + 1, k) * (1 - fu) + n(i + 1, j + 1, k) * fu) * fv
        return Vec2((l(0) + (u - cu) * cell).toFloat(), (l(1) + (v - cv) * cell).toFloat())
    }

    /** The largest displacement anywhere, canvas units. */
    val largest: Double get() = (0 until columns * rows).maxOf { k ->
        val (x, y) = (k % columns) * cell to (k / columns) * cell
        Math.hypot(at[2 * k] - x, at[2 * k + 1] - y)
    }

    companion object {
        /**
         * Pins along [line] (world units, z included), every [spacing] world units: where [here]
         * (this eye's projection) and [there] (the reference eye's) put each point.
         */
        fun pins(line: FLine, here: (Vec3) -> Vec2, there: (Vec3) -> Vec2, spacing: Double = 0.05): List<Pin> {
            require(spacing > 0.0) { "pins are some distance apart" }
            val out = mutableListOf<Pin>()
            for (pts in polylines3d(line, spacing)) for (p in pts) out += Pin(here(p), there(p))
            return out
        }

        /**
         * The sheet through [pins] (canvas units), over a canvas `1000 / aspect` units tall with a
         * node every [cell] units. With [frame], the canvas's edges are pinned at zero disparity too
         * (where nothing else pins them): what lies outside the pinned lines settles back to the
         * screen. Without it, the edges are free and the nearest lines' disparity carries out to them.
         */
        fun solve(pins: List<Pin>, aspect: Double, cell: Double = 10.0, frame: Boolean = true): StereoWarp {
            require(cell > 0.0 && aspect > 0.0) { "a grid's cells and the canvas are some size" }
            val cols = ceil(1000.0 / cell).toInt() + 1
            val rows = ceil(1000.0 / aspect / cell).toInt() + 1
            val n = cols * rows
            val dx = DoubleArray(n); val dy = DoubleArray(n)
            val sx = DoubleArray(n); val sy = DoubleArray(n); val count = IntArray(n)
            for (p in pins) {
                if (!(p.here.x.isFinite() && p.here.y.isFinite() && p.there.x.isFinite() && p.there.y.isFinite())) continue
                val i = (p.here.x / cell).roundToInt(); val j = (p.here.y / cell).roundToInt()
                if (i !in 0 until cols || j !in 0 until rows) continue
                val k = j * cols + i
                sx[k] += (p.there.x - p.here.x).toDouble(); sy[k] += (p.there.y - p.here.y).toDouble(); count[k]++
            }
            val fixed = BooleanArray(n) { k -> count[k] > 0 }
            for (k in 0 until n) if (fixed[k]) { dx[k] = sx[k] / count[k]; dy[k] = sy[k] / count[k] }
            if (frame) for (k in 0 until n) {
                val (i, j) = k % cols to k / cols
                if (!fixed[k] && (i == 0 || j == 0 || i == cols - 1 || j == rows - 1)) fixed[k] = true
            }
            if (fixed.any { it }) relax(dx, dy, fixed, cols, rows)
            val at = FloatArray(2 * n) { q ->
                val k = q / 2
                if (q % 2 == 0) ((k % cols) * cell + dx[k]).toFloat() else ((k / cols) * cell + dy[k]).toFloat()
            }
            return StereoWarp(cols, rows, cell, at)
        }

        /** Laplace's equation over the grid, [fixed] nodes held: coarse to fine, then over-relaxed sweeps. */
        private fun relax(dx: DoubleArray, dy: DoubleArray, fixed: BooleanArray, cols: Int, rows: Int) {
            // start the free nodes at the mean of the fixed, and sweep until nothing moves
            val free = (0 until cols * rows).filter { !fixed[it] }
            if (free.isEmpty()) return
            val fk = (0 until cols * rows).filter { fixed[it] }
            val (mx, my) = fk.sumOf { dx[it] } / fk.size to fk.sumOf { dy[it] } / fk.size
            for (k in free) { dx[k] = mx; dy[k] = my }
            val omega = 2.0 / (1.0 + Math.sin(Math.PI / maxOf(cols, rows)))
            for (sweep in 0 until 20000) {
                var change = 0.0
                for (k in free) {
                    val (i, j) = k % cols to k / cols
                    var ax = 0.0; var ay = 0.0; var m = 0
                    if (i > 0) { ax += dx[k - 1]; ay += dy[k - 1]; m++ }
                    if (i < cols - 1) { ax += dx[k + 1]; ay += dy[k + 1]; m++ }
                    if (j > 0) { ax += dx[k - cols]; ay += dy[k - cols]; m++ }
                    if (j < rows - 1) { ax += dx[k + cols]; ay += dy[k + cols]; m++ }
                    val nx = dx[k] + omega * (ax / m - dx[k]); val ny = dy[k] + omega * (ay / m - dy[k])
                    change = maxOf(change, abs(nx - dx[k]), abs(ny - dy[k]))
                    dx[k] = nx; dy[k] = ny
                }
                if (change < 1e-4) break
            }
        }
    }
}
