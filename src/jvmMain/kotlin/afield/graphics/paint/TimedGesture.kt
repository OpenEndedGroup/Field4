@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.paint

/**
 * A movement of the hand in time: where the tool is, when, how hard it presses, and how the handle
 * is held. An [afield.geometry.path.FLine] gives only a path; flicking, dragging slowly, pausing, pressing
 * and lifting need the time and the orientation too. [PaintCanvas.gesture] makes one from an FLine at a
 * speed; a pen or an agent can give the samples themselves.
 *
 * Coordinates are those of the canvas's FLines (canvas units, or its `view`), [Sample.t] seconds
 * from the gesture's start. Pressure 1 is a firm press, and more is harder still. For a
 * [WaterBrush], 0 is lifted. For a [DynamicBrush], 0 is the hairs' tips just touching the surface,
 * and below 0 lifts them clear of it, so a flick ends below 0.
 */
data class TimedGesture(val samples: List<Sample>) {
    init {
        require(samples.isNotEmpty()) { "a gesture needs at least one sample" }
        require(samples.zipWithNext().all { (a, b) -> b.t >= a.t }) { "a gesture's times must not go backwards" }
    }

    /**
     * One moment of a gesture.
     *
     * @param tilt the handle's lean from upright, degrees (0 straight down onto the paper).
     * @param azimuth which way the handle leans, degrees in the canvas (0 toward +x, 90 toward +y).
     * @param roll the handle's turn about its own axis, degrees.
     * @param s how far along its movement the tool is here, mm, measured in the world the gesture
     *   was made in ([PaintCanvas.gesture3d]): what is laid out along a stroke (the hand's drift,
     *   the bristles' wander, the pressure profile, how fast the load runs down, a knife's tears, a
     *   rag's creases, a pencil's tremor) follows it, so two eyes of a stereo pair that see one
     *   movement at different lengths still paint it alike. NaN: the length on the canvas.
     * @param piece which movement of the hand this sample belongs to: a new value lifts the tool.
     */
    data class Sample(
        val x: Double, val y: Double, val t: Double, val pressure: Double,
        val tilt: Double = 0.0, val azimuth: Double = 0.0, val roll: Double = 0.0,
        val s: Double = Double.NaN, val piece: Int = 0,
    )

    /** How long it lasts, seconds. */
    val duration: Double get() = samples.last().t - samples.first().t

    /** The same gesture [factor] times as fast. */
    fun faster(factor: Double): TimedGesture = TimedGesture(samples.map { it.copy(t = it.t / factor) })

    /** The same gesture held still for [seconds] at its end, the tool still down. */
    fun pause(seconds: Double): TimedGesture = TimedGesture(samples + samples.last().copy(t = samples.last().t + seconds))

    /**
     * This gesture followed by [next], [gap] seconds later: as further movements of the hand (an
     * ordinary brush lifts between them; a brush of moving hairs is carried on through).
     */
    fun then(next: TimedGesture, gap: Double = 0.0): TimedGesture {
        val t0 = samples.last().t + gap - next.samples.first().t
        val p0 = samples.last().piece + 1 - next.samples.first().piece
        val s0 = samples.last().s.takeIf { it.isFinite() }?.let { it - (next.samples.first().s.takeIf { v -> v.isFinite() } ?: 0.0) } ?: 0.0
        return TimedGesture(samples + next.samples.map { it.copy(t = it.t + t0, piece = it.piece + p0, s = it.s + s0) })
    }

    /** The same path backwards, at the same pace. */
    fun reversed(): TimedGesture {
        val end = samples.last().t
        val sEnd = samples.last().s
        val pEnd = samples.last().piece + samples.first().piece
        return TimedGesture(samples.reversed().map { it.copy(t = end - it.t, s = sEnd - it.s, piece = pEnd - it.piece) })
    }

    /** The movements of the hand, each its run of samples ([Sample.piece]). */
    val pieces: List<List<Sample>> get() {
        val out = mutableListOf<MutableList<Sample>>()
        for (s in samples) if (out.isEmpty() || out.last().last().piece != s.piece) out += mutableListOf(s) else out.last() += s
        return out
    }

    internal fun xytp(): FloatArray = FloatArray(samples.size * 4).also { a ->
        samples.forEachIndexed { i, s ->
            a[4 * i] = s.x.toFloat(); a[4 * i + 1] = s.y.toFloat(); a[4 * i + 2] = s.t.toFloat(); a[4 * i + 3] = s.pressure.toFloat()
        }
    }
}
