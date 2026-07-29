@file:afield.CompletionCategory("ALife")

package afield.stdlib

import afield.Box
import afield.Frames
import afield.graphics.FLine
import afield.loop
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One shared, honest way to *animate* the `alife*.kt` family — because every system in it is a time
 * evolution, and a montage of frozen generations is the one view that hides the thing they are about.
 *
 * The whole file is [Box.animateEvolution] plus four small "show a prefix of something" helpers. It sits
 * apart from the five simulation files for the same reason `alife_field.kt` does: those files name nothing
 * from Field's box runtime, and this one is entirely about it ([Box], [Frames], `display`).
 *
 * **Pacing is the craft, and it is the reason this is a shared helper rather than a copied loop.** The
 * naive animation — one simulation step per displayed frame — is wrong for almost every system here, and
 * wrong in two opposite directions:
 *
 *  - Gray–Scott needs about **6000** explicit-Euler steps to reach its named regime. One step per frame at
 *    20 fps is five minutes of a clip in which nothing happens.
 *  - Conway's glider moves one cell per **four** generations. Forty steps per frame is a strobe.
 *
 * So [Box.animateEvolution] takes the *whole* evolution — `totalSteps` — and the wall-clock `seconds` it
 * should occupy, and derives the steps-per-frame from them. The clip is then a statement about the system
 * ("these 6000 steps, in 7 seconds") rather than about the machine that recorded it.
 *
 * **Two clocks, deliberately.** A fixed steps-per-frame alone makes the clip's *content* depend on how
 * fast the host is; a pure wall-clock schedule alone degrades catastrophically, because a pass that falls
 * behind is then asked to do a bigger batch, which makes it fall further behind. So the loop uses both: it
 * advances at most `ceil(totalSteps / framesTarget)` steps per pass (smoothness, and a bound on how far one
 * pass can lag), and never runs *ahead* of the wall clock (so a fast host does not finish in two seconds).
 * A slow host therefore lags at a bounded rate and keeps catching up after the schedule ends, which is why
 * a recording should ask for a second or two more than `seconds` — the tail is both a hold on the settled
 * result and the slack that absorbs the lag.
 *
 * **The step counter belongs in the frame.** Every call site here labels its panels with the step number,
 * which is not decoration: it is how "did this clip actually reach the regime?" is answered by *looking* at
 * the last frame of the recorded mp4 rather than by trusting the schedule. A clip whose final label reads
 * `step 2100 / 6000` is a failed clip, and it says so on its face.
 *
 * **The range policy from `alife_field.kt` applies with more force here, not less.** A colour map whose
 * range is taken from the frame makes an animation of a growing structure appear to stand still, and makes
 * a saturating field indistinguishable from a dying one. So every call site passes explicit `lo`/`hi` to
 * [Img.colormap]. Auto-ranging is a per-figure mistake and a per-*frame* lie.
 */

/**
 * Animate a stateful system in a box: step it toward a schedule, draw it, repeat, forever.
 *
 * This is the family's single animation idiom. [start] is the initial state, [step] advances it by one
 * simulation step, and [panels] renders it — as a list of captioned images, exactly the shape [montage]
 * takes, so a clip can show a system *and its control arm* side by side and the difference is watchable
 * rather than asserted. The result is a [Frames], so a box whose last expression is a call to this
 * function animates when it is **launched** (`animate`/`loop` semantics; a `Frames` that is merely built is
 * never run).
 *
 * The returned animation **never ends**. Once `done == totalSteps` it stops stepping and stops
 * re-encoding, holding the final frame — which is the right behaviour for a recording of fixed duration
 * (the tail is a beat on the result) and for a live box (the value stays on screen). Stop it the way you
 * stop any Field animation: re-run or stop the box.
 *
 * **On `framesTarget`.** It is the number of *displayed* frames the schedule is divided into, so
 * `framesTarget / seconds` is the animation's own frame rate and should be at least the fps of any
 * recording made of it. The default 140 with the default 7 seconds is 20 fps, which is the docs runner's
 * usual video rate.
 *
 * ```
 * // 6000 Gray-Scott steps in 7 seconds: 43 steps per displayed frame.
 * animateEvolution(
 *     grayScottSeed(128, 128, patches = 6, patchSize = 14, seed = 11L),
 *     totalSteps = 6000,
 *     step = { it.step(GRAY_SCOTT_MAZE) },
 *     panels = { s, n -> listOf("step $n / 6000" to s.v.colormap("magma", 0f, 0.5f).upscaleNearest(3)) }
 * )
 * ```
 *
 * @param start the initial state; [step] is never applied to it before the first frame is drawn, so frame
 *   one is always the seed
 * @param totalSteps how many [step] applications the whole clip covers
 * @param seconds the wall-clock time those steps should take
 * @param columns passed to [montage]
 * @param cellSide passed to [montage] — note it only ever scales a panel **down**, so integer-upscale a
 *   small lattice with [Img.upscaleNearest] before returning it rather than expecting this to enlarge it
 * @param channel the box output channel to push PNG frames to
 * @param framesTarget how many displayed frames the schedule is divided into; see above
 * @param step advance the state by exactly one simulation step. For the family's immutable systems
 *   (`GrayScottState`, `IntGrid`, `Img`) return the new value; for the mutable ones (`BoidFlock`) mutate
 *   and return the same object.
 * @param panels render the current state, given the state and the number of steps taken so far. Label
 *   every panel with that number.
 */
fun <S> Box.animateEvolution(
    start: S,
    totalSteps: Int,
    seconds: Double = 7.0,
    columns: Int = 1,
    cellSide: Int = 420,
    channel: String = "main",
    framesTarget: Int = 140,
    step: (S) -> S,
    panels: (S, Int) -> List<Pair<String, Img>>
): Frames {
    require(totalSteps >= 1) { "totalSteps must be >= 1, got $totalSteps" }
    require(seconds > 0.0) { "seconds must be > 0, got $seconds" }
    require(framesTarget >= 1) { "framesTarget must be >= 1, got $framesTarget" }

    val perPass = max(1, ceil(totalSteps.toDouble() / framesTarget).toInt())
    var state = start
    var done = 0
    var drewFinal = false
    val startedAt = System.nanoTime()

    return loop {
        val elapsed = (System.nanoTime() - startedAt) / 1e9
        // The wall-clock ceiling: how far the schedule says we are allowed to have got by now. Without
        // it a fast host runs the whole evolution in the first second; without `perPass` a slow host
        // answers one late pass with an ever-bigger batch and diverges.
        val allowedByClock = min(totalSteps.toDouble(), elapsed / seconds * totalSteps).toInt()
        val advance = min(perPass, max(0, allowedByClock - done))
        repeat(advance) {
            state = step(state)
            done++
        }
        val finished = done >= totalSteps
        // Draw when something changed, and once more when the run finishes; then hold. Re-encoding an
        // unchanged montage a few hundred times would spend the whole hold in the PNG encoder.
        if (advance > 0 || done == 0 && !drewFinal || finished && !drewFinal) {
            display(channel, montage(panels(state, done), columns = columns, cellSide = cellSide).toPngBytes())
            if (finished) drewFinal = true
        }
        frame()
    }
}

/**
 * A copy of this image with every row after [rows] set to zero — the space-time diagram of a 1-D
 * automaton as it would look at generation [rows].
 *
 * This is how the elementary-CA clip works, and it is worth being exact about what it is: the diagram is
 * computed **once**, in full, by [elementaryCa], and the clip reveals it a row at a time. That is
 * legitimate because a space-time diagram's row `g` depends only on rows `< g` — revealing rows and
 * re-running the automaton produce the same pixels — but it is a reveal and not a re-simulation, and the
 * prose beside the clip says so.
 *
 * The image keeps its full height, which is the point: rasterising only the rows so far would make every
 * frame a different size, and [montage] would rescale each one differently.
 */
fun Img.rowsRevealed(rows: Int): Img {
    val out = Img.zeros(width, height, channels)
    val keep = rows.coerceIn(0, height)
    System.arraycopy(data, 0, out.data, 0, keep * width * channels)
    return out
}

/**
 * [rasterizeFLines], but with the framing taken from [reference] rather than from [lines].
 *
 * The reason this exists is a specific animation failure. [rasterizeFLines] fits its raster to the bounds
 * of what it is given, so drawing a *growing* curve one prefix at a time rescales the figure on every
 * frame: the curve appears to stay the same size while the world shrinks around it, and no motion is
 * legible. Pass the complete curve as [reference] and every frame is drawn in the same coordinate frame.
 *
 * Implemented by handing [rasterizeFLines] two extra single-node [FLine]s at the corners of [reference]'s
 * bounding box. A one-node line contributes no segment — the rasteriser's inner loop starts at node 1 —
 * so they pin the framing and draw nothing.
 */
fun rasterizeFLinesFramed(
    lines: List<FLine>,
    reference: List<FLine>,
    width: Int,
    height: Int,
    margin: Int = 4
): Img {
    val b = flineBounds(reference) ?: return rasterizeFLines(lines, width, height, margin)
    val corners = listOf(
        FLine().moveTo(b[0], b[1]),
        FLine().moveTo(b[2], b[3])
    )
    return rasterizeFLines(lines + corners, width, height, margin)
}

/**
 * The first [fraction] of a turtle's path, in the order the turtle walked it — so an L-system can be
 * animated by *drawing*, which is what a turtle does, rather than by cutting between derivation depths.
 *
 * Segments are counted across [lines] in list order, and [drawLSystem] emits them in walk order, so this
 * really is the pen's own sequence: a plant's trunk appears before its branches, and a Koch snowflake is
 * traced edge by edge. The line that the budget runs out inside is truncated, so the growth is smooth
 * rather than jumping a whole branch at a time.
 *
 * **Polylines only.** Every node is rebuilt as a `moveTo`/`lineTo`, which is exactly what a turtle emits;
 * hand this a curve with cubic segments and you get its control polygon. Pair it with
 * [rasterizeFLinesFramed] so the figure does not rescale as it grows.
 *
 * @param fraction clamped to `0 .. 1`
 */
fun flinePrefix(lines: List<FLine>, fraction: Double): List<FLine> {
    val total = lines.sumOf { max(0, it.nodes.size - 1) }
    if (total == 0) return emptyList()
    var budget = (fraction.coerceIn(0.0, 1.0) * total).roundToInt()
    if (budget <= 0) return emptyList()
    val out = ArrayList<FLine>()
    for (l in lines) {
        val segments = max(0, l.nodes.size - 1)
        if (segments == 0) continue
        val take = min(segments, budget)
        if (take == segments) {
            out.add(l)
        } else {
            val partial = FLine()
            partial.moveTo(l.nodes[0].to.x, l.nodes[0].to.y, l.nodes[0].to.z)
            for (i in 1..take) partial.lineTo(l.nodes[i].to.x, l.nodes[i].to.y, l.nodes[i].to.z)
            out.add(partial)
        }
        budget -= take
        if (budget <= 0) break
    }
    return out
}

/**
 * The first [upTo] particles of this aggregate, coloured by arrival order **on the full cluster's scale**.
 *
 * [Aggregate.arrivalOrderImg] normalises by its own particle count, which is right for a finished figure
 * and wrong for an animation: every frame would re-map the newest particle to 1.0 and the colours would
 * churn while the cluster grew. Here the ramp is fixed by `points.size` for every frame, so a branch keeps
 * the colour it arrived with and the growth reads as growth.
 *
 * Colour-map it with an explicit range too — `colormap("turbo", 0f, 1f)` — for the same reason.
 */
fun Aggregate.arrivalPrefixImg(upTo: Int): Img {
    val out = Img.zeros(mask.width, mask.height, 1)
    val n = max(1, points.size - 1)
    val end = upTo.coerceIn(0, points.size)
    for (i in 0 until end) {
        val p = points[i]
        val ix = p.x.roundToInt().coerceIn(0, mask.width - 1)
        val iy = p.y.roundToInt().coerceIn(0, mask.height - 1)
        out.data[iy * mask.width + ix] = i.toFloat() / n
    }
    return out
}
