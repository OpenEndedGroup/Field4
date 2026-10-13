@file:afield.CompletionCategory("Graphics")
package afield.paint

import afield.GestureHandle
import afield.KeyframeEvaluator
import afield.PenSample
import afield.Rect
import afield.lanesToTake
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/*
 * Gesture pad -> brush. A take recorded on a `gesture(...)` pad (or its x/y keyframe lanes, edited)
 * becomes a [TimedGesture] on a canvas: the pad's square mapped onto a rectangle of the canvas, the
 * take's own timing kept, so how fast the hand moved is how fast the brush moves. What the pad did
 * not record is filled in the way `PaintCanvas.gesture(line, ...)` fills it from a path. See
 * dev_docs/paint/GESTURE_PAINT_REPLAY.md for which dimensions that is.
 */

/**
 * The take [samples], recorded on a pad spanning [xRange] by [yRange] (y up, as the pad shows it),
 * as a [TimedGesture] on this canvas: the pad's square laid over [into] (in this canvas's FLine
 * coordinates, y down; by default the largest square in the middle 80% of the canvas), its timing kept and stretched by
 * [timeScale].
 *
 * Where the take carries the pen's pressure it is multiplied by [pressureScale] (a pen pressed
 * fully, 1, is 1.2 by default; a mouse, which reports 0.5 while down, is 0.6, the canvas's usual
 * press); a take without pressure presses [pressure] throughout. Where it carries the pen's tilt,
 * the handle leans as the pen did; a take with no tilt (a mouse, or a pad without `pen`) leans
 * [tilt] degrees, toward where it is going if [lean]. A recorded twist turns the handle about its
 * own axis; without one a flat is held across its path, as [PaintCanvas.gesture] holds it.
 */
fun PaintCanvas.gesture(
    samples: List<PenSample>,
    xRange: Pair<Double, Double> = -1.0 to 1.0,
    yRange: Pair<Double, Double> = -1.0 to 1.0,
    into: Rect? = null,
    pressure: Double = 0.6,
    pressureScale: Double = 1.2,
    tilt: Double = 30.0,
    lean: Boolean = true,
    timeScale: Double = 1.0,
): TimedGesture {
    require(samples.isNotEmpty()) { "an empty take paints nothing: record one on the pad first" }
    require(timeScale > 0.0) { "timeScale stretches time: above 0" }
    val r = into ?: defaultReplayRect()
    val (x0, x1) = xRange
    val (y0, y1) = yRange
    fun cx(x: Double) = r.x + (x - x0) / ((x1 - x0).takeIf { it != 0.0 } ?: 1.0) * r.w
    fun cy(y: Double) = r.y + (1.0 - (y - y0) / ((y1 - y0).takeIf { it != 0.0 } ?: 1.0)) * r.h
    // a dimension the pointer never moved off its resting value was not recorded
    val hasTilt = samples.any { it.tiltX != 0.0 || it.tiltY != 0.0 }
    val hasTwist = samples.any { it.twist != 0.0 }
    val t0 = samples.first().t
    var hand: Double? = null
    val out = samples.mapIndexed { i, s ->
        val x = cx(s.x); val y = cy(s.y)
        // heading on the canvas, unwrapped and held through sharp reversals as PaintCanvas.gesture does
        val (a, b) = if (i + 1 < samples.size) s to samples[i + 1] else samples[(i - 1).coerceAtLeast(0)] to s
        val dx = cx(b.x) - cx(a.x); val dy = cy(b.y) - cy(a.y)
        val heading = if (hypot(dx, dy) < 1e-9) hand ?: 0.0 else {
            val raw = Math.toDegrees(atan2(dy, dx))
            hand?.let { prev -> val turn = (raw - prev + 540.0) % 360.0 - 180.0; if (abs(turn) > 120.0) prev else prev + turn } ?: raw
        }
        hand = heading
        val (penTilt, penAzimuth) = penPose(s.tiltX, s.tiltY)
        TimedGesture.Sample(
            x, y, (s.t - t0) * timeScale,
            if (s.pressure.isFinite()) s.pressure * pressureScale else pressure,
            tilt = if (hasTilt) penTilt else tilt,
            azimuth = if (hasTilt) penAzimuth else if (lean) heading else 0.0,
            roll = if (hasTwist) s.twist else heading + 90.0,
        )
    }
    return TimedGesture(out)
}

/**
 * The take recorded on [pad] as a [TimedGesture] on this canvas; see the `List<PenSample>`
 * overload. A pad made with `pen = true` brings its pressure, tilt and twist along.
 */
fun PaintCanvas.gesture(
    pad: GestureHandle,
    into: Rect? = null,
    pressure: Double = 0.6,
    pressureScale: Double = 1.2,
    tilt: Double = 30.0,
    lean: Boolean = true,
    timeScale: Double = 1.0,
): TimedGesture {
    require(pad.hasTake) { "the pad has no take: Shift-drag (or press rec) on it to record one" }
    return gesture(pad.penSamples, pad.xRange, pad.yRange, into, pressure, pressureScale, tilt, lean, timeScale)
}

/**
 * Keyframe lanes [x] and [y] — a take baked with `bakeGesture` and edited in the keyframe editor —
 * as a [TimedGesture] on this canvas, sampled [samplesPerSecond] times a second and at every key.
 * [pressureLane] (pen pressure, 0 to 1, as `GestureHandle.penLane("pressure")` bakes it) presses
 * the brush where it is given; otherwise [pressure] does, throughout. [tiltXLane], [tiltYLane] and
 * [twistLane] (degrees, `penLane("tiltX")` and so on) hold the handle as the pen was held. The rest
 * is as in the `List<PenSample>` overload.
 */
fun PaintCanvas.gesture(
    x: KeyframeEvaluator,
    y: KeyframeEvaluator,
    xRange: Pair<Double, Double> = -1.0 to 1.0,
    yRange: Pair<Double, Double> = -1.0 to 1.0,
    into: Rect? = null,
    pressureLane: KeyframeEvaluator? = null,
    tiltXLane: KeyframeEvaluator? = null,
    tiltYLane: KeyframeEvaluator? = null,
    twistLane: KeyframeEvaluator? = null,
    pressure: Double = 0.6,
    pressureScale: Double = 1.2,
    tilt: Double = 30.0,
    lean: Boolean = true,
    timeScale: Double = 1.0,
    samplesPerSecond: Double = 60.0,
): TimedGesture {
    val start = (x.getKeyframeTimes() + y.getKeyframeTimes()).minOrNull()
        ?: throw IllegalArgumentException("the lanes have no keys: bake a take into them first")
    val take = lanesToTake(x, y, samplesPerSecond)
    fun KeyframeEvaluator?.at(t: Double, otherwise: Double) = this?.takeIf { it.getKeyframeCount() > 0 }?.evaluateValueAt(t + start) ?: otherwise
    val samples = take.map { (t, v) ->
        PenSample(t, v.x.toDouble(), v.y.toDouble(), pressureLane.at(t, Double.NaN), tiltXLane.at(t, 0.0), tiltYLane.at(t, 0.0), twistLane.at(t, 0.0))
    }
    return gesture(samples, xRange, yRange, into, pressure, pressureScale, tilt, lean, timeScale)
}

/**
 * A pen's `PointerEvent` tiltX/tiltY (degrees toward +x and toward +y) as a [TimedGesture.Sample]'s
 * tilt from upright and the azimuth it leans toward, both degrees. Upright leans toward 0.
 */
fun penPose(tiltX: Double, tiltY: Double): Pair<Double, Double> {
    val tx = tan(Math.toRadians(tiltX.coerceIn(-89.9, 89.9)))
    val ty = tan(Math.toRadians(tiltY.coerceIn(-89.9, 89.9)))
    val r = hypot(tx, ty)
    return Math.toDegrees(atan(r)) to if (r < 1e-12) 0.0 else Math.toDegrees(atan2(ty, tx))
}

// The pad is square: the largest square in the middle 80% of the canvas (or its view).
private fun PaintCanvas.defaultReplayRect(): Rect {
    val v = view ?: Rect(0.0, 0.0, 1000.0, heightUnits)
    val side = 0.8 * minOf(abs(v.w), abs(v.h))
    return Rect(v.x + (v.w - side) / 2, v.y + (v.h - side) / 2, side, side)
}
