package afield.graphics

import afield.stdlib.Vec3
import field.graphics.FLinesAndJavaShapes
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An independent, high-resolution arc-length oracle for an [FLine].
 *
 * Deliberately shares no code with [PathFlattener]: it walks the node list itself, evaluates each
 * segment in double precision at a fixed, very fine parameter step, and accumulates chord lengths.
 * At the sample counts used here the polyline under-estimate of a cubic's true length is far below
 * the errors we are trying to measure.
 */
class ArcLengthOracle(line: FLine, private val samplesPerSegment: Int = 8192) {

    /** node-`t` of each sample (segment index + alpha). */
    private val ts = ArrayList<Double>()

    /** cumulative arc length at each sample. */
    private val ds = ArrayList<Double>()

    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val zs = ArrayList<Double>()

    init {
        var cum = 0.0
        var subpathStart: Vec3 = line.nodes.firstOrNull()?.to ?: Vec3()
        var previous: Vec3 = subpathStart

        fun push(t: Double, p: DoubleArray) {
            if (ts.isNotEmpty()) {
                val dx = p[0] - xs[xs.size - 1]
                val dy = p[1] - ys[ys.size - 1]
                val dz = p[2] - zs[zs.size - 1]
                cum += sqrt(dx * dx + dy * dy + dz * dz)
            }
            ts.add(t)
            ds.add(cum)
            xs.add(p[0]); ys.add(p[1]); zs.add(p[2])
        }

        push(0.0, doubleArrayOf(previous.x.toDouble(), previous.y.toDouble(), previous.z.toDouble()))

        for (i in 1 until line.nodes.size) {
            val node = line.nodes[i]
            val base = (i - 1).toDouble()
            when (node) {
                is FLine.MoveTo -> {
                    // a jump: contributes no arc length, but does advance node-t
                    subpathStart = node.to
                    previous = node.to
                    ts.add(base + 1.0)
                    ds.add(cum)
                    xs.add(previous.x.toDouble()); ys.add(previous.y.toDouble()); zs.add(previous.z.toDouble())
                }

                is FLine.LineTo -> {
                    lineSamples(base, previous, node.to, ::push)
                    previous = node.to
                }

                is FLine.Close -> {
                    lineSamples(base, previous, subpathStart, ::push)
                    previous = subpathStart
                }

                is FLine.CubicTo -> {
                    val a = previous
                    for (s in 1..samplesPerSegment) {
                        val alpha = s.toDouble() / samplesPerSegment
                        push(base + alpha, cubic(a, node.c1, node.c2, node.to, alpha))
                    }
                    previous = node.to
                }
            }
        }
    }

    private inline fun lineSamples(base: Double, a: Vec3, b: Vec3, push: (Double, DoubleArray) -> Unit) {
        for (s in 1..samplesPerSegment) {
            val alpha = s.toDouble() / samplesPerSegment
            push(
                base + alpha,
                doubleArrayOf(
                    a.x + (b.x - a.x) * alpha,
                    a.y + (b.y - a.y) * alpha,
                    a.z + (b.z - a.z) * alpha
                )
            )
        }
    }

    private fun cubic(a: Vec3, c1: Vec3, c2: Vec3, b: Vec3, t: Double): DoubleArray {
        val u = 1 - t
        val w0 = u * u * u
        val w1 = 3 * u * u * t
        val w2 = 3 * u * t * t
        val w3 = t * t * t
        return doubleArrayOf(
            w0 * a.x + w1 * c1.x + w2 * c2.x + w3 * b.x,
            w0 * a.y + w1 * c1.y + w2 * c2.y + w3 * b.y,
            w0 * a.z + w1 * c1.z + w2 * c2.z + w3 * b.z
        )
    }

    val length: Double get() = ds[ds.size - 1]

    /** true arc length at node-`t`. */
    fun distanceAt(t: Double): Double {
        if (t <= ts[0]) return ds[0]
        if (t >= ts[ts.size - 1]) return ds[ds.size - 1]
        var lo = 0
        var hi = ts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (ts[mid] <= t) lo = mid else hi = mid
        }
        val span = ts[hi] - ts[lo]
        if (span <= 0.0) return ds[lo]
        val f = (t - ts[lo]) / span
        return ds[lo] + f * (ds[hi] - ds[lo])
    }

    /** the point at a given true arc length. */
    fun positionAt(d: Double): DoubleArray {
        if (d <= 0.0) return doubleArrayOf(xs[0], ys[0], zs[0])
        if (d >= length) {
            val n = xs.size - 1
            return doubleArrayOf(xs[n], ys[n], zs[n])
        }
        var lo = 0
        var hi = ds.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (ds[mid] <= d) lo = mid else hi = mid
        }
        val span = ds[hi] - ds[lo]
        val f = if (span <= 0.0) 0.0 else (d - ds[lo]) / span
        return doubleArrayOf(
            xs[lo] + f * (xs[hi] - xs[lo]),
            ys[lo] + f * (ys[hi] - ys[lo]),
            zs[lo] + f * (zs[hi] - zs[lo])
        )
    }
}

/** The result of scoring one curve's arc-length spacing against the oracle. */
data class SpacingReport(
    val name: String,
    val leaves: Int,
    val flattenerLength: Float,
    val oracleLength: Double,
    val maxOverMedianStep: Double,
    val minOverMedianStep: Double,
    val worstPositionError: Double,
    val meanPositionError: Double
) {
    override fun toString(): String = buildString {
        append(name.padEnd(24))
        append(" leaves=").append(leaves.toString().padStart(5))
        append(" len=").append(fmt(flattenerLength.toDouble())).append("/").append(fmt(oracleLength))
        append(" step max/med=").append(fmt(maxOverMedianStep)).append("x")
        append(" min/med=").append(fmt(minOverMedianStep)).append("x")
        append(" posErr worst=").append(fmt(worstPositionError))
        append(" mean=").append(fmt(meanPositionError))
    }

    private fun fmt(d: Double) = String.format("%.3f", d)
}

/**
 * Ask `Cursor.setD` for [steps] equally spaced arc-length positions and score how equally spaced
 * they actually turned out to be, using [ArcLengthOracle] as ground truth.
 */
fun scoreArcLengthSpacing(name: String, line: FLine, steps: Int = 64, tol: Float = 0.01f): SpacingReport {
    val oracle = ArcLengthOracle(line)
    val cursor = line.cursor(tol)
    val leaves = cursor.pathFlattener.mappings.size
    val total = cursor.lengthD()

    val trueDistances = DoubleArray(steps)
    var worstPositionError = 0.0
    var sumPositionError = 0.0
    for (i in 0 until steps) {
        val want = total * i / (steps - 1f)
        cursor.setD(want)
        trueDistances[i] = oracle.distanceAt(cursor.t.toDouble())

        val got = cursor.position()
        val expect = oracle.positionAt(want.toDouble())
        val e = sqrt(
            (got.x - expect[0]) * (got.x - expect[0]) +
                (got.y - expect[1]) * (got.y - expect[1]) +
                (got.z - expect[2]) * (got.z - expect[2])
        )
        if (e > worstPositionError) worstPositionError = e
        sumPositionError += e
    }

    val gaps = DoubleArray(steps - 1) { trueDistances[it + 1] - trueDistances[it] }
    val sorted = gaps.sortedArray()
    val median = sorted[sorted.size / 2]

    return SpacingReport(
        name = name,
        leaves = leaves,
        flattenerLength = total,
        oracleLength = oracle.length,
        maxOverMedianStep = if (median > 0) sorted[sorted.size - 1] / median else Double.NaN,
        minOverMedianStep = if (median > 0) sorted[0] / median else Double.NaN,
        worstPositionError = worstPositionError,
        meanPositionError = sumPositionError / steps
    )
}

/**
 * The curve family used by the spacing tests. Every one of these is a legitimate thing to draw;
 * the first three are the ones the flatness-only subdivision criterion is blind to.
 */
object ArcLengthTestCurves {

    /** control points exactly on the chord, but bunched -> pathological parameterisation. */
    fun collinearCubic(): FLine = FLine().moveTo(0f, 0f).cubicTo(
        Vec3(450f, 0f, 0f), Vec3(480f, 0f, 0f), Vec3(500f, 0f, 0f)
    )

    /** the same thing 0.001 units off the chord: still under any sane flatness tolerance. */
    fun nearCollinearCubic(): FLine = FLine().moveTo(0f, 0f).cubicTo(
        Vec3(450f, 0.001f, 0f), Vec3(480f, -0.001f, 0f), Vec3(500f, 0f, 0f)
    )

    /**
     * Collinear *and* symmetric about the chord midpoint, so `B(0.5)` lands exactly on the chord's
     * midpoint: both terms of the subdivision error are zero at the top level even though `t` runs
     * 16% fast through the middle. Only a forced minimum subdivision catches this one.
     */
    fun symmetricCollinearCubic(): FLine = FLine().moveTo(0f, 0f).cubicTo(
        Vec3(240f, 0f, 0f), Vec3(260f, 0f, 0f), Vec3(500f, 0f, 0f)
    )

    /** a perfectly ordinary straight cubic: control points at the thirds. This is the cost case. */
    fun uniformStraightCubic(): FLine = FLine().moveTo(0f, 0f).cubicTo(
        Vec3(500f / 3f, 0f, 0f), Vec3(1000f / 3f, 0f, 0f), Vec3(500f, 0f, 0f)
    )

    /** collinear *and* with a cusp-ish reversal in speed. */
    fun collinearBackAndForth(): FLine = FLine().moveTo(0f, 0f).cubicTo(
        Vec3(900f, 0f, 0f), Vec3(-400f, 0f, 0f), Vec3(500f, 0f, 0f)
    )

    fun sCurve(): FLine = FLine().moveTo(0f, 0f)
        .cubicTo(Vec3(100f, 200f, 0f), Vec3(200f, -200f, 0f), Vec3(300f, 0f, 0f))

    fun sharplyCurved(): FLine = FLine().moveTo(0f, 0f)
        .cubicTo(Vec3(0f, 300f, 0f), Vec3(300f, 300f, 0f), Vec3(300f, 0f, 0f))

    /**
     * A closed outline whose subpath-closing segment is long, so spacing has to stay even *across*
     * the `Close` boundary and not merely within each cubic. A different question from the `Close`
     * crash: the crash was about reading a node, this is about the table spanning one.
     */
    fun closedWithLongCloseSegment(): FLine = FLine().moveTo(0f, 0f)
        .cubicTo(Vec3(120f, 0f, 0f), Vec3(280f, 0f, 0f), Vec3(400f, 0f, 0f))
        .cubicTo(Vec3(400f, 120f, 0f), Vec3(400f, 280f, 0f), Vec3(400f, 400f, 0f))
        .close() // ~566 units back to the origin, longer than either cubic

    /** two subpaths, so the table must span a MoveTo jump that contributes no length. */
    fun twoSubpaths(): FLine = FLine().moveTo(0f, 0f)
        .cubicTo(Vec3(150f, 0f, 0f), Vec3(280f, 0f, 0f), Vec3(300f, 0f, 0f))
        .moveTo(0f, 200f)
        .cubicTo(Vec3(20f, 200f, 0f), Vec3(150f, 200f, 0f), Vec3(300f, 200f, 0f))

    /**
     * Real z variation with the pathology living *in z*. `flatnessFor` is 3-D
     * (`FLinesAndJavaShapes.ptSegDistSq3`), so the skew term this branch added had better be 3-D too
     * or a curve bunched along z slips straight through it.
     */
    fun collinearInZ(): FLine = FLine().moveTo(0f, 0f, 0f).cubicTo(
        Vec3(0f, 0f, 450f), Vec3(0f, 0f, 480f), Vec3(0f, 0f, 500f)
    )

    /** a genuinely three-dimensional badly-parameterised curve, as the 3-D control arm. */
    fun skewedInThreeD(): FLine = FLine().moveTo(0f, 0f, 0f).cubicTo(
        Vec3(300f, 40f, 260f), Vec3(320f, 60f, 280f), Vec3(100f, 200f, 300f)
    )

    /** a glyph-ish closed outline: a couple of near-straight stems plus round joins. */
    fun glyphish(): FLine = FLine().moveTo(0f, 0f)
        .cubicTo(Vec3(0f, 260f, 0f), Vec3(0f, 480f, 0f), Vec3(0f, 700f, 0f)) // straight stem as a cubic
        .cubicTo(Vec3(120f, 700f, 0f), Vec3(220f, 620f, 0f), Vec3(220f, 500f, 0f))
        .cubicTo(Vec3(220f, 380f, 0f), Vec3(120f, 300f, 0f), Vec3(0f, 300f, 0f))
        .lineTo(60f, 300f)
        .cubicTo(Vec3(190f, 240f, 0f), Vec3(60f, 120f, 0f), Vec3(190f, 0f, 0f))
        .close()

    fun all(): List<Pair<String, FLine>> = listOf(
        "collinearCubic" to collinearCubic(),
        "nearCollinearCubic" to nearCollinearCubic(),
        "symmetricCollinear" to symmetricCollinearCubic(),
        "uniformStraightCubic" to uniformStraightCubic(),
        "collinearBackAndForth" to collinearBackAndForth(),
        "sCurve" to sCurve(),
        "sharplyCurved" to sharplyCurved(),
        "glyphish" to glyphish(),
        "closedLongClose" to closedWithLongCloseSegment(),
        "twoSubpaths" to twoSubpaths(),
        "collinearInZ" to collinearInZ(),
        "skewedInThreeD" to skewedInThreeD()
    )

    /**
     * [all], but with every multi-subpath curve broken into its subpaths.
     *
     * For metrics that read a *Euclidean* distance between consecutive samples — which is the
     * arc-length distance only within one subpath — this is the domain on which the metric is
     * meaningful. `FLine.pieces()` returns the line itself for a single-subpath curve, so the
     * control arms are unaffected.
     */
    fun allSubpaths(): List<Pair<String, FLine>> = all().flatMap { (name, line) ->
        val parts = line.pieces()
        if (parts.size <= 1) listOf(name to line)
        else parts.mapIndexed { i, part -> "$name#${i + 1}" to part }
    }

    /** Degenerate shapes, kept out of [all] because "evenly spaced" is meaningless for them. */
    fun degenerate(): List<Pair<String, FLine>> = listOf(
        "zeroLengthCubic" to FLine().moveTo(50f, 50f)
            .cubicTo(Vec3(50f, 50f, 0f), Vec3(50f, 50f, 0f), Vec3(50f, 50f, 0f)),
        "coincidentControls" to FLine().moveTo(0f, 0f)
            .cubicTo(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f), Vec3(300f, 0f, 0f)),
        "bothControlsAtEnd" to FLine().moveTo(0f, 0f)
            .cubicTo(Vec3(300f, 0f, 0f), Vec3(300f, 0f, 0f), Vec3(300f, 0f, 0f)),
        "moveToOnly" to FLine().moveTo(7f, 9f),
        "emptyLine" to FLine(),
        "closeWithNothingToClose" to FLine().moveTo(1f, 1f).close(),
        "repeatedMoveTos" to FLine().moveTo(0f, 0f).moveTo(10f, 0f).moveTo(20f, 0f)
    )
}

class ArcLengthSpacingTest {

    @Test
    fun everyCurveIsEvenlySpacedByArcLength() {
        val reports = ArcLengthTestCurves.all().map { (name, line) -> scoreArcLengthSpacing(name, line) }
        reports.forEach { println(it) }

        val bad = reports.filter { it.maxOverMedianStep > 2.0 }
        assertTrue(
            bad.isEmpty(),
            "setD is not spacing by arc length on: " +
                bad.joinToString("; ") { "${it.name} max/median step = ${"%.2f".format(it.maxOverMedianStep)}x" }
        )
    }

    @Test
    fun flattenerLengthAgreesWithAnIndependentOracle() {
        val wrong = ArcLengthTestCurves.all().mapNotNull { (name, line) ->
            val oracle = ArcLengthOracle(line)
            val got = line.cursor().lengthD().toDouble()
            val relative = abs(got - oracle.length) / oracle.length
            println("$name flattener length $got oracle ${oracle.length} relative error $relative")
            if (relative > 0.01) "$name off by ${"%.2f".format(relative * 100)}%" else null
        }
        assertTrue(wrong.isEmpty(), "PathFlattener under-measures length on: ${wrong.joinToString("; ")}")
    }

    /**
     * NOTE ON THE METRIC. This measures the *Euclidean* gap between consecutive returned points,
     * which is only the arc-length gap within a single subpath: across a `moveTo` jump two points
     * that are adjacent in arc length are far apart in space. So the metric runs per subpath. The
     * whole-line behaviour of a multi-subpath curve is covered by
     * [spacingIsEvenAcrossSubpathBoundaries], which measures gaps in arc length via the oracle and
     * therefore reads them correctly.
     */
    @Test
    fun sampleByDistanceProducesEvenlySpacedPoints() {
        val offenders = ArcLengthTestCurves.allSubpaths().mapNotNull { (name, line) ->
            val oracle = ArcLengthOracle(line)
            val spacing = (oracle.length / 40).toFloat()
            val pts = line.sampleByDistance(spacing)
            if (pts.size < 4) return@mapNotNull null
            val gaps = (1 until pts.size).map { pts[it].distance(pts[it - 1]).toDouble() }
            val sorted = gaps.sorted()
            val median = sorted[sorted.size / 2]
            val ratio = sorted.last() / median
            println("$name sampleByDistance n=${pts.size} spacing=$spacing max/median=${"%.3f".format(ratio)}")
            if (ratio > 2.0) "$name ${"%.2f".format(ratio)}x" else null
        }
        assertTrue(offenders.isEmpty(), "sampleByDistance is uneven on: ${offenders.joinToString("; ")}")
    }

    /**
     * The hand-picked curves are only as good as my imagination. `minSubDiv = 2` rests on the claim
     * that two forced levels of subdivision break every symmetry that can zero *both* terms of the
     * error metric, and the honest way to test a claim like that is to try to break it: 400 random
     * collinear cubics (control points anywhere along the chord, in any order, including reversals
     * that make the curve double back) and 400 random general cubics.
     */
    @Test
    fun randomCurvesAreAlsoEvenlySpaced() {
        val rng = java.util.Random(20260731L)
        var worstCollinear = 0.0
        var worstCollinearName = ""
        var worstGeneral = 0.0
        var worstGeneralName = ""

        fun ratioOf(line: FLine): Double {
            val oracle = ArcLengthOracle(line, samplesPerSegment = 4096)
            if (oracle.length < 1.0) return 1.0
            val cursor = line.cursor()
            val total = cursor.lengthD()
            val trueD = DoubleArray(48) {
                cursor.setD(total * it / 47f)
                oracle.distanceAt(cursor.t.toDouble())
            }
            val gaps = DoubleArray(47) { trueD[it + 1] - trueD[it] }.sortedArray()
            val median = gaps[gaps.size / 2]
            return if (median <= 0.0) 1.0 else gaps.last() / median
        }

        repeat(400) {
            // collinear: every control point on the x axis, so flatness is 0 wherever the control
            // points also stay between the ends, and the whole curve is geometrically a segment.
            val c1x = rng.nextFloat() * 500f
            val c2x = rng.nextFloat() * 500f
            val line = FLine().moveTo(0f, 0f).cubicTo(Vec3(c1x, 0f, 0f), Vec3(c2x, 0f, 0f), Vec3(500f, 0f, 0f))
            val r = ratioOf(line)
            if (r > worstCollinear) {
                worstCollinear = r
                worstCollinearName = "cubicTo(($c1x,0),($c2x,0),(500,0))"
            }
        }

        repeat(400) {
            fun p() = Vec3(rng.nextFloat() * 500f - 100f, rng.nextFloat() * 500f - 100f, rng.nextFloat() * 60f)
            val a = p()
            val line = FLine().moveTo(a).cubicTo(p(), p(), p())
            val r = ratioOf(line)
            if (r > worstGeneral) {
                worstGeneral = r
                worstGeneralName = line.nodes.joinToString(" ")
            }
        }

        println("random collinear worst max/median = ${"%.4f".format(worstCollinear)}  $worstCollinearName")
        println("random general   worst max/median = ${"%.4f".format(worstGeneral)}  $worstGeneralName")

        assertTrue(worstCollinear < 1.10, "collinear cubic spaced ${"%.3f".format(worstCollinear)}x on $worstCollinearName")
        assertTrue(worstGeneral < 1.10, "general cubic spaced ${"%.3f".format(worstGeneral)}x on $worstGeneralName")
    }

    /**
     * Hitting `maxSubDiv` used to drop that piece of the curve out of the table altogether, which
     * under-reports the total length and shifts every distance past it. Forced here by turning the
     * ceiling down to nothing.
     */
    @Test
    fun hittingMaxSubDivStillAccountsForTheWholeCurve() {
        val line = ArcLengthTestCurves.sharplyCurved()
        val oracle = ArcLengthOracle(line)
        val was = PathFlattener.maxSubDiv
        try {
            PathFlattener.maxSubDiv = 0
            val flattener = PathFlattener(line, 0.01f)
            println("maxSubDiv=0 leaves=${flattener.mappings.size} length=${flattener.length()} oracle=${oracle.length}")
            assertTrue(flattener.mappings.isNotEmpty(), "the whole cubic vanished from the table")
            // one forced level, so two chords across a quarter-turn-ish curve: coarse, but it must
            // account for the curve rather than for none of it.
            assertTrue(
                flattener.length() > oracle.length * 0.8,
                "length ${flattener.length()} is nowhere near the oracle's ${oracle.length}"
            )
        } finally {
            PathFlattener.maxSubDiv = was
        }
    }

    /**
     * Three arc-length consumers pass 1 missed, found by grepping for every `Cursor` construction in
     * `src/jvmMain` rather than from memory:
     *
     *   `FLinesAndJavaShapes.Cursor.split()`          FLinesAndJavaShapes.kt:583
     *   `FLinesAndJavaShapes.Cursor.splitD(list)`     FLinesAndJavaShapes.kt:635
     *   `FLinesAndJavaShapes.samplePoints(f, d)`      FLinesAndJavaShapes.kt:2349
     *
     * `splitD` is the most exposed of the three: "give me this line cut at 3.2, 4 and 8 units along"
     * is a promise about distance and nothing else.
     */
    @Test
    fun splittingByDistanceCutsWhereItSaysItWill() {
        val offenders = mutableListOf<String>()

        for ((name, line) in ArcLengthTestCurves.allSubpaths()) {
            val oracle = ArcLengthOracle(line)
            val total = oracle.length
            if (total < 1.0) continue

            // split(): the first piece should be exactly as long as asked for
            val at = (total * 0.4).toFloat()
            val (left, _) = line.cursor().setD(at).split()
            if (left != null) {
                val leftLength = ArcLengthOracle(left).length
                val error = kotlin.math.abs(leftLength - at) / total
                println("${name.padEnd(22)} split at $at gave a first piece ${"%.3f".format(leftLength)} long")
                if (error > 0.01) offenders += "$name split ${"%.2f".format(error * 100)}%"
            }

            // splitD(): every requested cut, in one call
            val cuts = listOf(0.2, 0.45, 0.7).map { (total * it).toFloat() }
            val pieces = line.cursor().splitD(cuts)
            if (pieces.size >= 2) {
                var cumulative = 0.0
                val boundaries = pieces.dropLast(1).map { cumulative += ArcLengthOracle(it).length; cumulative }
                boundaries.forEachIndexed { i, got ->
                    if (i < cuts.size) {
                        val error = kotlin.math.abs(got - cuts[i]) / total
                        if (error > 0.01) offenders += "$name splitD cut ${cuts[i]} landed at ${"%.1f".format(got)}"
                    }
                }
                println("${name.padEnd(22)} splitD $cuts -> boundaries ${boundaries.map { "%.1f".format(it) }}")
            }

            // samplePoints: the older twin of sampleByDistance
            val spacing = (total / 30).toFloat()
            val pts = FLinesAndJavaShapes.samplePoints(line, spacing)
            if (pts.size >= 4) {
                val gaps = (1 until pts.size).map { pts[it].distance(pts[it - 1]).toDouble() }.sorted()
                val ratio = gaps.last() / gaps[gaps.size / 2]
                println("${name.padEnd(22)} samplePoints n=${pts.size} max/median=${"%.3f".format(ratio)}")
                if (ratio > 2.0) offenders += "$name samplePoints ${"%.2f".format(ratio)}x"
            }
        }

        assertTrue(offenders.isEmpty(), "distance-based splitting/sampling is off on: ${offenders.joinToString("; ")}")
    }

    /**
     * Spacing has to stay even *across* a subpath boundary, not merely within each cubic. This is a
     * different question from the `Close`-node crash: that was about reading a node, this is about
     * the arc-length table spanning one. `twoSubpaths` also checks the `MoveTo` jump, which
     * contributes no length but does advance `node.t`.
     */
    @Test
    fun spacingIsEvenAcrossSubpathBoundaries() {
        val offenders = mutableListOf<String>()
        for (name in listOf("closedLongClose", "twoSubpaths", "glyphish")) {
            val line = ArcLengthTestCurves.all().first { it.first == name }.second
            val report = scoreArcLengthSpacing(name, line, steps = 96)
            println(report)
            if (report.maxOverMedianStep > 1.5) offenders += "$name ${"%.2f".format(report.maxOverMedianStep)}x"
            // and the flattener's own length must include the closing/jumping segments
            val relative = kotlin.math.abs(report.flattenerLength - report.oracleLength) / report.oracleLength
            if (relative > 0.01) offenders += "$name length off by ${"%.2f".format(relative * 100)}%"
        }
        assertTrue(offenders.isEmpty(), "spacing breaks across a subpath boundary on: ${offenders.joinToString("; ")}")
    }

    /**
     * `flatnessFor` is 3-D, so the parameter-skew term added by this branch has to be 3-D too. A
     * curve whose control points are collinear and bunched *along z* is the test that catches a
     * 2-D-only skew term.
     */
    @Test
    fun theSkewTermIsThreeDimensional() {
        for (name in listOf("collinearInZ", "skewedInThreeD")) {
            val line = ArcLengthTestCurves.all().first { it.first == name }.second
            val report = scoreArcLengthSpacing(name, line, steps = 64)
            println(report)
            assertTrue(
                report.maxOverMedianStep < 1.10,
                "$name is spaced ${"%.3f".format(report.maxOverMedianStep)}x — the skew term is " +
                    "ignoring a dimension"
            )
            assertTrue(
                report.worstPositionError < report.oracleLength * 0.005,
                "$name positional error ${report.worstPositionError} on a curve ${report.oracleLength} long"
            )
        }
    }

    /**
     * Degenerate geometry: zero-length cubics, coincident control points, lines with no drawable
     * segment at all. "Evenly spaced" is meaningless for these, so the bar is simply that nothing
     * throws, hangs or returns NaN — the things that turn a live document into a dead one.
     */
    @Test
    fun degenerateGeometryIsSurvivable() {
        for ((name, line) in ArcLengthTestCurves.degenerate()) {
            val cursor = line.cursor()
            val length = cursor.lengthD()
            assertTrue(length.isFinite() && length >= 0f, "$name lengthD = $length")

            for (f in listOf(0f, 0.25f, 0.5f, 1f)) {
                cursor.setD(length * f)
                if (line.nodes.isNotEmpty()) {
                    val p = cursor.position()
                    assertTrue(
                        p.x.isFinite() && p.y.isFinite() && p.z.isFinite(),
                        "$name position at f=$f was $p"
                    )
                }
            }

            val samples = line.sampleByDistance(5f)
            assertTrue(samples.all { it.x.isFinite() }, "$name sampleByDistance produced a non-finite point")
            val dashes = line.byDashing(5f, 3f)
            assertTrue(dashes.size < 10_000, "$name byDashing produced ${dashes.size} dashes — runaway loop")
            val leaves = PathFlattener(line, 0.01f).mappings.size
            assertTrue(leaves < 5000, "$name flattened to $leaves leaves")

            println("${name.padEnd(24)} lengthD=$length samples=${samples.size} dashes=${dashes.size} leaves=$leaves")
        }
    }

    @Test
    fun degenerateInputsDoNotHangOrProduceNaN() {
        val line = ArcLengthTestCurves.sCurve()

        // one sample: steps - 1 == 0, so the loop's divisor is zero
        val one = line.sampleByNumber(1)
        assertTrue(one.size == 1, "expected one sample, got ${one.size}")
        assertTrue(one[0].x.isFinite() && one[0].y.isFinite(), "sampleByNumber(1) produced ${one[0]}")

        val two = line.sampleByNumber(2)
        assertTrue(two.size == 2 && two.all { it.x.isFinite() }, "sampleByNumber(2) produced $two")

        // a line with nothing to walk along
        assertTrue(FLine().moveTo(3f, 4f).cursor().lengthD() == 0f)
        assertTrue(FLine().moveTo(3f, 4f).byDashing(5f, 5f).isEmpty())

        // a zero gap used to spin forever
        assertTrue(line.byDashing(5f, 0f).isEmpty(), "a zero gap should be rejected, not looped on")
        assertTrue(line.byDashing(0f, 5f).isEmpty(), "a zero dash should be rejected, not looped on")
    }

    /**
     * The audit: every public entry point that promises a *distance along the curve*, scored
     * against the oracle. Each one funnels into `Cursor.setD` and so into `PathFlattener`, and each
     * one was wrong by the same mechanism.
     */
    @Test
    fun everyArcLengthConsumerAgreesWithTheOracle() {
        val offenders = mutableListOf<String>()

        // Per subpath: `oracle.positionAt` is ambiguous exactly at a `moveTo` jump, where the
        // cumulative-length table has a plateau and both sides of it are legitimately "that far
        // along". Scoring subpath by subpath removes the ambiguity instead of papering over it;
        // [spacingIsEvenAcrossSubpathBoundaries] covers the whole-line case.
        for ((name, line) in ArcLengthTestCurves.allSubpaths()) {
            val oracle = ArcLengthOracle(line)
            val total = oracle.length
            if (total < 1.0) continue

            // FLine.midPoint(): the point at half the arc length
            val mid = line.midPoint()
            val wantMid = oracle.positionAt(total / 2)
            val midError = dist(mid.x, mid.y, mid.z, wantMid)

            // fractionAlong: the point a given fraction of the arc length in. The anchor resolver
            // is private, so probe it the way a user would — scale the line to nothing about the
            // anchor and read where every point collapsed to.
            var worstFraction = 0.0
            for (f in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
                val got = line.scale(0f, fractionAlong(f)).nodes[0].to
                val want = oracle.positionAt(total * f)
                worstFraction = maxOf(worstFraction, dist(got.x, got.y, got.z, want))
            }

            // FLine.sampleByNumber: n points evenly along the arc length
            val byNumber = line.sampleByNumber(33)
            var worstByNumber = 0.0
            byNumber.forEachIndexed { i, p ->
                val want = oracle.positionAt(total * i / 32.0)
                worstByNumber = maxOf(worstByNumber, dist(p.x, p.y, p.z, want))
            }

            // byArcLengthParameterizing: resample the whole line at a fixed spacing
            val spacing = (total / 40).toFloat()
            val reparam = line.byArcLengthParameterizing(spacing)
            val gaps = (2 until reparam.nodes.size).map {
                reparam.nodes[it].to.distance(reparam.nodes[it - 1].to).toDouble()
            }
            val reparamRatio = if (gaps.size < 3) 1.0 else gaps.sorted().last() / gaps.sorted()[gaps.size / 2]

            // FLineSamplers.byDistance: the composable sampler wrapper
            val sampled = FLineSamplers.byDistance(spacing).sample(line, FLineSampleContext())
            val sampledGaps = (1 until sampled.size).map {
                sampled[it].point.distance(sampled[it - 1].point).toDouble()
            }
            val sampledRatio =
                if (sampledGaps.size < 3) 1.0 else sampledGaps.sorted().last() / sampledGaps.sorted()[sampledGaps.size / 2]

            println(
                name.padEnd(24) +
                    " midPoint err=" + "%.3f".format(midError) +
                    " fractionAlong err=" + "%.3f".format(worstFraction) +
                    " sampleByNumber err=" + "%.3f".format(worstByNumber) +
                    " byArcLengthParam max/med=" + "%.3f".format(reparamRatio) +
                    " samplers.byDistance max/med=" + "%.3f".format(sampledRatio)
            )

            // 0.5% of the line's length is a generous ceiling for a *positional* error on
            // something claiming to be an exact distance along it.
            val ceiling = total * 0.005
            if (midError > ceiling) offenders += "$name midPoint ${"%.1f".format(midError)}"
            if (worstFraction > ceiling) offenders += "$name fractionAlong ${"%.1f".format(worstFraction)}"
            if (worstByNumber > ceiling) offenders += "$name sampleByNumber ${"%.1f".format(worstByNumber)}"
            if (reparamRatio > 2.0) offenders += "$name byArcLengthParameterizing ${"%.2f".format(reparamRatio)}x"
            if (sampledRatio > 2.0) offenders += "$name samplers.byDistance ${"%.2f".format(sampledRatio)}x"
        }

        assertTrue(offenders.isEmpty(), "arc-length consumers disagree with the oracle: ${offenders.joinToString("; ")}")
    }

    private fun dist(x: Float, y: Float, z: Float, to: DoubleArray): Double =
        sqrt((x - to[0]) * (x - to[0]) + (y - to[1]) * (y - to[1]) + (z - to[2]) * (z - to[2]))

    /**
     * Not an assertion about wall time — machines differ — but a printed record of what the
     * subdivision criterion costs, plus a hard ceiling on the leaf count so a future change cannot
     * quietly make the flattener quadratic.
     */
    @Test
    fun subdivisionCostStaysBounded() {
        // a thousand-segment polyline expressed as cubics: the case where a minimum-subdivision
        // floor costs the most, because every one of these would otherwise be a single leaf.
        val polyline = FLine().moveTo(0f, 0f)
        for (i in 1..1000) {
            val x0 = (i - 1) * 10f
            val x1 = i * 10f
            polyline.cubicTo(
                Vec3(x0 + (x1 - x0) / 3f, 0f, 0f),
                Vec3(x0 + 2 * (x1 - x0) / 3f, 0f, 0f),
                Vec3(x1, 0f, 0f)
            )
        }

        val cases = ArcLengthTestCurves.all() + ("polyline1000" to polyline)
        for ((name, line) in cases) {
            repeat(3) { PathFlattener(line, 0.01f) } // warm
            val start = System.nanoTime()
            var leaves = 0
            repeat(10) { leaves = PathFlattener(line, 0.01f).mappings.size }
            val micros = (System.nanoTime() - start) / 10_000.0
            println("$name leaves=$leaves build=${"%.1f".format(micros)}us")
        }

        val leaves = PathFlattener(polyline, 0.01f).mappings.size
        assertTrue(leaves <= 8000, "1000 straight cubics flattened to $leaves leaves")
    }

    @Test
    fun dashesHaveTheLengthTheyWereAskedFor() {
        val offenders = ArcLengthTestCurves.allSubpaths().mapNotNull { (name, line) ->
            val oracle = ArcLengthOracle(line)
            val dash = (oracle.length / 24).toFloat()
            val gap = dash / 2
            val dashes = FLine().let { _ -> line.byDashing(dash, gap) }
            if (dashes.size < 3) return@mapNotNull null
            // measure each dash's own drawn length; interior dashes should all be `dash` long
            val lengths = dashes.dropLast(1).map { d ->
                (1 until d.nodes.size).sumOf { d.nodes[it].to.distance(d.nodes[it - 1].to).toDouble() }
            }.filter { it > 0 }
            val worst = lengths.maxOf { abs(it - dash) / dash }
            println("$name byDashing n=${dashes.size} asked=$dash worst relative error=${"%.3f".format(worst)}")
            if (worst > 0.25) "$name ${"%.1f".format(worst * 100)}%" else null
        }
        assertTrue(offenders.isEmpty(), "byDashing produces wrong-length dashes on: ${offenders.joinToString("; ")}")
    }
}
