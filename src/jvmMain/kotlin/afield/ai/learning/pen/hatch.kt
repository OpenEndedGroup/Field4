/**
 * Hatching around a drawing with a sensing pen: every stroke of the drawing gets passes of the
 * pen's own trembling line laid beside it, which keep clear of the other strokes and of the
 * hatching already done.
 *
 * A pen cannot remember which strokes it has not hatched yet, so [Hatcher] chooses: it hands the
 * pen one stroke at a time as a finished primer, with everything else on the page as
 * surroundings, and moves on when the pen has drawn enough or wandered off. Tests: `PenHatchTest`.
 */

package afield.ai.learning.pen

import afield.geometry.path.FLine
import afield.math.linalg.Vec3

/**
 * Hatches [lines] stroke by stroke with [model], [grow] points at a time, so a box can watch it.
 *
 * ```kotlin
 * val hatcher = Hatcher(listOf(HersheyFont.load("futural").text("FIELD", size = 90.0)))
 * hatcher.finish()
 * lines(hatcher.hatching)
 * ```
 *
 * @param lines the drawing to hatch around; every subpath is a stroke.
 * @param model a sensing pen; `"echoes"` (the default) was trained for this.
 * @param passes roughly how many passes to lay beside each stroke: a stroke gets `passes` times its
 *   own length in points.
 * @param wander how far, in drawing units, the pen may get from the stroke it is hatching before it
 *   is moved on to the next.
 * @param gap when not null, steer the pen with a [FollowGuide] to keep this many drawing units from
 *   the ink beside it; `null` leaves the pen to find its own way, which only a pen trained for
 *   hatching can do.
 */
class Hatcher(
    lines: List<FLine>,
    val model: StrokeModel = PenModel.bundled("echoes"),
    val passes: Int = 6,
    val scale: Double = 8.0,
    // the learned spread: a guide needs proposals that differ to have something to choose from
    var controls: PenControls = PenControls(temperature = 1.0, noise = 1.0),
    val seed: Long = 0,
    val step: Double = 0.35,
    val wander: Double = 40.0,
    val gap: Double? = 4.0,
) {
    // the strokes, longest first: a long stroke's hatching gives the short ones something to meet
    private val strokes: List<FLine> = lines.flatMap { it.pieces() }
        .filter { PenCorpus.strokes(it, scale, step).sumOf { s -> s.size } >= 6 }
        .sortedByDescending { PenCorpus.strokes(it, scale, step).sumOf { s -> s.size } }

    /** The hatching so far, one FLine per stroke started. */
    val hatching: MutableList<FLine> = ArrayList()

    private var index = -1
    private var writer: PenLine? = null
    private var budget = 0
    private var points: List<Vec3> = emptyList()

    /** True once every stroke has been hatched. */
    val done: Boolean get() = index >= strokes.size

    /** The stroke being hatched now, or null when [done]. */
    val current: FLine? get() = strokes.getOrNull(index)

    /** Draw up to [steps] more points; returns false once everything is hatched. */
    fun grow(steps: Int = 8): Boolean {
        var left = steps
        while (left > 0 && !done) {
            val w = writer ?: next() ?: return false
            val n = minOf(left, budget)
            w.grow(n)
            budget -= n
            left -= n
            if (budget <= 0 || nearest(w.tip) > wander) writer = null
        }
        return !done
    }

    /** Hatch everything; returns [hatching]. */
    fun finish(): List<FLine> {
        while (grow(256)) {}
        return hatching
    }

    private fun next(): PenLine? {
        index++
        val stroke = strokes.getOrNull(index) ?: return null
        val others = strokes.filterIndexed { i, _ -> i != index } + hatching
        points = PenCorpus.strokes(stroke, scale, step).flatten()
        budget = passes * points.size
        // pen down at the stroke's end: guided, it carries on round the end and back beside the
        // stroke; unguided, a finished stroke (a lift) is what precedes passes in the echoes corpus
        val w = PenLine(stroke, model, scale, controls, seed + index, step, surroundings = others, liftAtEnd = gap == null)
        // hatching around one stroke is one unbroken stroke: lifting would let the pen jump anywhere
        if (gap != null) w.guide = FollowGuide(gap, candidates = 32, liftCost = 1000.0).also { it.see(strokes + hatching) }
        hatching += w.drawn
        writer = w
        return w
    }

    private fun nearest(at: Vec3): Double = points.minOf { it.distance(at).toDouble() }
}

/**
 * Hatch around [lines] with a sensing pen and return the hatching, one FLine per stroke: [Hatcher]
 * run to the end.
 */
fun hatch(
    lines: List<FLine>,
    model: StrokeModel = PenModel.bundled("echoes"),
    passes: Int = 6,
    seed: Long = 0,
    controls: PenControls = PenControls(temperature = 1.0, noise = 1.0),
    gap: Double? = 4.0,
): List<FLine> = Hatcher(lines, model, passes, controls = controls, seed = seed, gap = gap).finish()
