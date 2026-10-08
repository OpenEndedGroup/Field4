@file:afield.CompletionCategory("Graphics")
package afield.paint

/**
 * A movement of the hand in time: where the tool is, when, how hard it presses, and how the handle
 * is held. An [afield.graphics.FLine] gives only a path; flicking, dragging slowly, pausing, pressing
 * and lifting need the time and the orientation too. [PaintCanvas.gesture] makes one from an FLine at a
 * speed; a pen or an agent can give the samples themselves.
 *
 * Coordinates are those of the canvas's FLines (canvas units, or its `view`), [Sample.t] seconds
 * from the gesture's start, pressure 0 (lifted) upward — 1 is a firm press, and more is harder
 * still.
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
     */
    data class Sample(
        val x: Double, val y: Double, val t: Double, val pressure: Double,
        val tilt: Double = 0.0, val azimuth: Double = 0.0, val roll: Double = 0.0,
    )

    /** How long it lasts, seconds. */
    val duration: Double get() = samples.last().t - samples.first().t

    /** The same gesture [factor] times as fast. */
    fun faster(factor: Double): TimedGesture = TimedGesture(samples.map { it.copy(t = it.t / factor) })

    /** The same gesture held still for [seconds] at its end, the tool still down. */
    fun pause(seconds: Double): TimedGesture = TimedGesture(samples + samples.last().copy(t = samples.last().t + seconds))

    /** This gesture followed by [next], [gap] seconds later. */
    fun then(next: TimedGesture, gap: Double = 0.0): TimedGesture {
        val t0 = samples.last().t + gap - next.samples.first().t
        return TimedGesture(samples + next.samples.map { it.copy(t = it.t + t0) })
    }

    /** The same path backwards, at the same pace. */
    fun reversed(): TimedGesture {
        val end = samples.last().t
        return TimedGesture(samples.reversed().map { it.copy(t = end - it.t) })
    }

    internal fun xytp(): FloatArray = FloatArray(samples.size * 4).also { a ->
        samples.forEachIndexed { i, s ->
            a[4 * i] = s.x.toFloat(); a[4 * i + 1] = s.y.toFloat(); a[4 * i + 2] = s.t.toFloat(); a[4 * i + 3] = s.pressure.toFloat()
        }
    }
}
