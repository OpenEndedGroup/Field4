package afield.graphics

import afield.stdlib.Vec3
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
        "glyphish" to glyphish()
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

    @Test
    fun sampleByDistanceProducesEvenlySpacedPoints() {
        val offenders = ArcLengthTestCurves.all().mapNotNull { (name, line) ->
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
        val offenders = ArcLengthTestCurves.all().mapNotNull { (name, line) ->
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
