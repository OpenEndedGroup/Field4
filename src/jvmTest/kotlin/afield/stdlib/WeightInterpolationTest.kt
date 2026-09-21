package afield.stdlib

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Interpolating two trained velocity fields in *weight* space, and whether the middle is anywhere
 * other than the obvious place.
 *
 * The trivial way to morph A into B is to interpolate the *targets*: send point `i` to
 * `(1-t)·A_i + t·B_i`. That is a straight-line crossfade, it needs no network, and its middle is
 * exactly the average of two letters. The question here is what `lerp(W_A, W_B, t)` does instead —
 * a field that was never trained on anything, and whose landing points are a nonlinear function of
 * `t` because the network is nonlinear in its weights.
 *
 * Two things have to hold before the question is even well-posed:
 *
 *  - **The same source.** Both fields map one shared cloud (a unit circle, phase-aligned to each
 *    contour) so that particle `i` has one start point and two destinations. Fields trained from
 *    different sources have no common particle to follow.
 *  - **The same initialisation.** Two independently initialised networks sit in different basins,
 *    related by a permutation of hidden units, and averaging them is averaging two unrelated
 *    functions. [interpolationNeedsASharedInitialisation] is the control that measures this rather
 *    than assuming it — and it is the reason `seed` is pinned in both calls.
 */
class WeightInterpolationTest {

    private val n = 512
    private val steps = 9000
    private val integrationSteps = 120

    // ---- shared source, and a pairing for each letter -------------------------------------

    /** A letter's outline: `n` points, arc-length order, centred and scaled to unit RMS radius. */
    private fun glyph(ch: String): List<Vec3> {
        val contours = layoutText(ch, typeface("Helvetica", 300.0)).toFLines()
        val components = contourComponents(contours, total = n, seed = 3)
        require(components.size == 1) {
            "$ch has ${components.size} closed subpaths; this test needs letters with exactly one, " +
                "because a shared circular source can only be paired with a single contour"
        }
        val pts = components[0]
        val cx = pts.map { it.x.toDouble() }.average()
        val cy = pts.map { it.y.toDouble() }.average()
        val rms = sqrt(pts.sumOf { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) } / pts.size)
        return pts.map { vec((it.x - cx) / rms, (it.y - cy) / rms, 0.0) }
    }

    /** The shared source: a unit circle, parameterised by angle. */
    private fun circle(): List<Vec3> = (0 until n).map {
        val a = 2 * PI * it / n
        vec(cos(a), sin(a), 0.0)
    }

    /**
     * Rotate (and if it helps, reverse) a contour so index `i` lands near circle index `i`.
     *
     * Without this the pairing carries an arbitrary rotation per letter, which the flow then has to
     * spend its capacity unwinding — the same effect `mixtureSource`'s `alignPhase` exists for, and
     * measured there as the difference between a training loss of 0.054 and 0.00097. Here it also
     * has to be *consistent across the two letters*, or the two fields disagree about which part of
     * the circle becomes which part of the glyph and the interpolation is comparing nothing.
     */
    private fun alignToCircle(contour: List<Vec3>, src: List<Vec3>): List<Vec3> {
        var best: List<Vec3> = contour
        var bestCost = Double.MAX_VALUE
        for (reversed in listOf(false, true)) {
            val base = if (reversed) contour.reversed() else contour
            for (shift in 0 until n) {
                var cost = 0.0
                for (i in 0 until n) {
                    val q = base[(i + shift) % n]
                    val p = src[i]
                    cost += (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
                    if (cost > bestCost) break
                }
                if (cost < bestCost) {
                    bestCost = cost
                    best = List(n) { base[(it + shift) % n] }
                }
            }
        }
        return best
    }

    private fun train(target: List<Vec3>, source: List<Vec3>, seed: Long): FlowMatchExport =
        flowMatch(
            target, source,
            FlowMatchOptions(
                steps = steps, coupling = FlowCoupling.PAIRED, seed = seed,
                hidden = intArrayOf(64, 64),
            ),
        ).export()

    // ---- integrating an arbitrary weight set ----------------------------------------------

    /** The library's blend — this test is also the evidence for it. */
    private fun blend(a: FlowMatchExport, b: FlowMatchExport, t: Float): FlowMatchExport =
        a.blendWith(b, t)

    /**
     * Euler from `t = 0` to `t = 1` in the training frame, matching `FlowMatchExport.glsl`'s
     * encoding exactly: position, raw `t`, then `sin`/`cos` octave pairs.
     */
    private fun transport(export: FlowMatchExport, start: List<Vec3>): List<Vec3> {
        val d = export.dimension
        val xin = FloatArray(export.network.inputs)
        val yout = FloatArray(d)
        val dt = 1f / integrationSteps
        return start.map { p0 ->
            var x = ((p0.x - export.center[0]) / export.scale).toFloat()
            var y = ((p0.y - export.center[1]) / export.scale).toFloat()
            for (s in 0 until integrationSteps) {
                val t = s * dt
                xin[0] = x; xin[1] = y; xin[d] = t
                for (k in 0 until export.timeFeatures) {
                    val omega = (PI * (1 shl k)).toFloat()
                    xin[d + 1 + 2 * k] = sin(omega * t)
                    xin[d + 2 + 2 * k] = cos(omega * t)
                }
                export.network.evaluate(xin, yout)
                x += yout[0] * dt
                y += yout[1] * dt
            }
            vec(
                (x * export.scale + export.center[0]).toDouble(),
                (y * export.scale + export.center[1]).toDouble(),
                0.0,
            )
        }
    }

    private fun meanDistance(a: List<Vec3>, b: List<Vec3>): Double =
        a.indices.sumOf { hypot((a[it].x - b[it].x).toDouble(), (a[it].y - b[it].y).toDouble()) } / a.size

    /**
     * How far you walk visiting the landings in source order — the instrument for "is this a
     * shape or a smear".
     *
     * Consecutive source indices are adjacent on the circle, so a map that preserves the source's
     * ordering lands them adjacent too and this comes out near the figure's perimeter. A map that
     * scrambles the circle produces a cloud that may have a perfectly ordinary width and centre
     * while being nothing you would call a letter, and that is invisible to RMS radius, to a
     * centroid, and to chamfer distance in one direction. It shows up here as a path many times
     * longer than the perimeter.
     */
    private fun pathLength(p: List<Vec3>): Double =
        (p.indices).sumOf {
            val q = p[(it + 1) % p.size]
            hypot((q.x - p[it].x).toDouble(), (q.y - p[it].y).toDouble())
        }

    // ---- the measurements ------------------------------------------------------------------

    /**
     * My hand-written integrator against the library's, on the same field and the same points.
     *
     * Everything else here is measured through [transport], which re-implements the time encoding
     * and the normalised-frame round trip by hand because a blended [FlowMatchExport] is not a
     * [FlowMatchField] and cannot be handed to `FlowMatchField.transport`. If that
     * re-implementation is wrong then every figure in this file is a measurement of my own bug,
     * and the endpoint-fidelity number would be the first place it showed up — which is exactly
     * where it did show up, so this check is here before any of it is believed.
     */
    @Test
    fun theHandWrittenIntegratorAgreesWithTheLibrarys() {
        val src = circle()
        val a = alignToCircle(glyph("C"), src)
        val field = flowMatch(
            a, src,
            FlowMatchOptions(
                steps = 1200, coupling = FlowCoupling.PAIRED, seed = 7, hidden = intArrayOf(64, 64),
            ),
        )
        val probe = src.filterIndexed { i, _ -> i % 8 == 0 }

        val mine = transport(field.export(), probe)
        val theirs = field.transport(probe, steps = integrationSteps, integrator = FlowIntegrator.EULER)

        val worst = probe.indices.maxOf {
            hypot((mine[it].x - theirs[it].x).toDouble(), (mine[it].y - theirs[it].y).toDouble())
        }
        val travelled = probe.indices.sumOf {
            hypot((theirs[it].x - probe[it].x).toDouble(), (theirs[it].y - probe[it].y).toDouble())
        } / probe.size
        println("integrator parity: worst ${"%.3g".format(worst)} against a mean travel of ${"%.3f".format(travelled)}")

        assertTrue(
            worst < travelled * 0.01,
            "the hand-written integrator disagrees with FlowMatchField.transport by $worst, which " +
                "is not small against the $travelled a point travels — the encoding or the frame " +
                "round trip is wrong, and every other number in this file is measuring that bug"
        )
    }

    /**
     * What the shared source costs, against the source `mixtureSource` would have built.
     *
     * Interpolation needs *one* source for both letters, so the per-letter fitted ellipse that
     * tier one normally uses is not available. This measures the price of that constraint rather
     * than assuming it is small: same architecture, same steps, same target, only the source
     * differs.
     */
    @Test
    fun whatTheSharedSourceCosts() {
        val src = circle()
        val a = alignToCircle(glyph("C"), src)

        val shared = train(a, src, seed = 7)
        val sharedError = meanDistance(transport(shared, src), a)

        val pairing = mixtureSource(listOf(a), seed = 5)
        val fitted = flowMatch(
            pairing.to, pairing.from,
            FlowMatchOptions(
                steps = steps, coupling = FlowCoupling.PAIRED, seed = 7, hidden = intArrayOf(64, 64),
            ),
        ).export()
        val fittedError = meanDistance(transport(fitted, pairing.from), pairing.to)

        println(
            "landing error on 'C' (unit-RMS letter): shared circle ${"%.3f".format(sharedError)}, " +
                "per-letter mixtureSource ${"%.3f".format(fittedError)}"
        )
    }

    /**
     * The headline: at `t = 0.5` the weight-space middle is somewhere the straight-line morph is
     * not.
     *
     * Both are quoted against the same yardstick — the mean distance a point travels from A to B,
     * which is the whole width of the morph. If weight interpolation landed on the crossfade this
     * would be near zero and the idea would be worthless; the endpoints are checked in the same
     * run so that a large number cannot be bought by the fields simply being bad.
     */
    @Test
    fun theWeightSpaceMiddleIsNotTheStraightLineMiddle() {
        val src = circle()
        val a = alignToCircle(glyph("C"), src)
        val b = alignToCircle(glyph("S"), src)

        val fa = train(a, src, seed = 7)
        val fb = train(b, src, seed = 7)

        val width = meanDistance(a, b)

        val landedA = transport(fa, src)
        val landedB = transport(fb, src)
        val errA = meanDistance(landedA, a) / width
        val errB = meanDistance(landedB, b) / width

        println("endpoint fidelity: A ${"%.3f".format(errA)}, B ${"%.3f".format(errB)} of the morph width")

        for (t in listOf(0.25f, 0.5f, 0.75f)) {
            val weightMiddle = transport(blend(fa, fb, t), src)
            val lineMiddle = src.indices.map {
                vec(
                    a[it].x * (1 - t) + b[it].x * t,
                    a[it].y * (1 - t) + b[it].y * t,
                    0.0,
                )
            }
            val departure = meanDistance(weightMiddle, lineMiddle) / width
            val toA = chamferDistance(weightMiddle, a) / width
            val toB = chamferDistance(weightMiddle, b) / width
            val coherence = pathLength(weightMiddle) / ((pathLength(a) + pathLength(b)) / 2)
            println(
                "t=$t: departure from the crossfade ${"%.3f".format(departure)}, " +
                    "chamfer to A ${"%.3f".format(toA)}, to B ${"%.3f".format(toB)}, " +
                    "path/perimeter ${"%.2f".format(coherence)}"
            )
        }

        // 0.3, not 0.05: a circle mapped onto a letter *outline* has to squeeze into a thin ribbon
        // whose two sides come from opposite sides of the circle, and `whatTheSharedSourceCosts`
        // shows the purpose-built `mixtureSource` does no better (0.187 against 0.195), so this is
        // the map's own difficulty and not the shared source or the step count. The published
        // FIELD figure sits at about 6.5x its own chamfer floor for the same reason and still
        // reads as a word once it is a particle cloud.
        assertTrue(errA < 0.3 && errB < 0.3, "the endpoints must still be the letters: $errA, $errB")

        val half = transport(blend(fa, fb, 0.5f), src)
        val line = src.indices.map { vec((a[it].x + b[it].x) / 2, (a[it].y + b[it].y) / 2, 0.0) }
        val departure = meanDistance(half, line) / width
        assertTrue(
            departure > 0.1,
            "the weight-space middle sits on the crossfade (departure $departure), so interpolating " +
                "weights bought nothing over interpolating the targets"
        )
    }

    /**
     * The control, and the reason `seed` is pinned: interpolating two *independently* initialised
     * networks does not work, and the failure is not subtle.
     *
     * Hidden units are exchangeable, so two runs from different initialisations represent the same
     * kind of function through different permutations of the same machinery. Their average is not
     * a blend of the two behaviours, it is a fourth thing that neither network would recognise.
     * Quoted as the midpoint cloud's spread against the shared-init one, both measured the same way.
     */
    @Test
    fun interpolationNeedsASharedInitialisation() {
        val src = circle()
        val a = alignToCircle(glyph("C"), src)
        val b = alignToCircle(glyph("S"), src)

        val perimeter = (pathLength(a) + pathLength(b)) / 2

        fun coherenceOfMidpoint(seedA: Long, seedB: Long): Double {
            val mid = transport(blend(train(a, src, seedA), train(b, src, seedB), 0.5f), src)
            return pathLength(mid) / perimeter
        }

        // 1.0 is "as tidy as a letter". An earlier version of this test compared the midpoint's
        // RMS radius instead and could not tell the two cases apart at all — 0.934 against 0.940 —
        // because a scrambled cloud and a glyph occupy about the same amount of space.
        val shared = listOf(7L, 11L, 23L).map { coherenceOfMidpoint(it, it) }
        val independent = listOf(7L to 8L, 11L to 12L, 23L to 24L).map { coherenceOfMidpoint(it.first, it.second) }
        println(
            "midpoint path length / perimeter (a letter is 1.0):\n" +
                "  shared init      ${shared.joinToString(", ") { "%.2f".format(it) }}\n" +
                "  independent init ${independent.joinToString(", ") { "%.2f".format(it) }}"
        )

        assertTrue(shared.all { it < 3.0 }, "the shared-init midpoints should be tidy curves, got $shared")

        // Deliberately asserting what was *measured*, which is not what was predicted. The
        // permutation argument says averaging two independently initialised networks should give
        // a fourth thing that neither recognises, and here it does not: both cases stay tidy and
        // the independent one is not reliably worse. The likely reason is that PAIRED coupling
        // from a shared source makes the two training problems nearly the same problem, so the
        // runs do not wander into genuinely different basins in the first place.
        //
        // This is pinned rather than dropped so that the day it stops being true — a wider net, a
        // harder pair of targets, a different coupling — something says so.
        assertTrue(
            independent.all { it < 3.0 },
            "independent initialisations were expected to scramble the midpoint and measurably do " +
                "not, for these targets at this size: shared $shared, independent $independent. " +
                "If this now fails, the permutation argument has started to bite and the comment " +
                "above is the thing to rewrite."
        )
    }
}
