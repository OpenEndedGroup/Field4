package afield.stdlib

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Chebyshev waveshaper — the harmonic-generating shaper `Tₙ(cos θ) = cos(nθ)`.
 *
 * Proves it is genuinely first-class: (i) the Tₙ harmonic identity holds numerically,
 * (ii) the op survives a serialize→rebuild graph round-trip (deepCopy), (iii) it is a
 * real `OpRegistry` transmutation target. Also renders a visual proof SVG into the
 * worktree root so a human can eyeball the harmonics.
 */
class ChebyshevWaveshaperTest {

    @Test
    fun chebyshevReproducesTheNthHarmonicOfACosine() = with(Functions) {
        // Feed a raw cosine drive (values already in [-1,1]) through Tₙ and check it
        // equals the n-th harmonic: Tₙ(cos θ) = cos(nθ). Convention-independent — the
        // source literally evaluates to cos(x).
        val drive = fn(Functions.Domain.ALL) { x -> kotlin.math.cos(x) }
        for (n in 0..6) {
            val shaped = drive.chebyshev(n)
            for (i in 0..40) {
                val theta = -3.0 + i * 0.15
                assertEquals(kotlin.math.cos(n * theta), shaped.eval(theta), 1e-9, "T$n at θ=$theta")
            }
        }
    }

    @Test
    fun graphRoundTripPreservesTheChebyshevOp() = with(Functions) {
        val built = sin(2.0).chebyshev(3)
        assertEquals("chebyshev", built.graphNode().op)

        // deepCopy rebuilds every node through its `rebuild` lambda — the in-memory
        // serialize→rebuild round-trip. The op and its `order` param child must survive.
        val roundTripped = built.deepCopy()
        assertEquals("chebyshev", roundTripped.graphNode().op)
        assertNotNull(roundTripped.graphNode().child("source"), "source child preserved")
        assertNotNull(roundTripped.graphNode().child("order"), "order child preserved")

        for (i in 0..30) {
            val x = i / 30.0
            assertEquals(built.eval(x), roundTripped.eval(x), 1e-12, "eval after round-trip at x=$x")
        }
    }

    @Test
    fun chebyshevIsATransmutationTargetInTheRegistry() = with(Functions) {
        assertTrue(OpRegistry.isTransmutable("chebyshev"), "chebyshev must be transmutable")
        assertEquals("transform", OpRegistry.family("chebyshev"))

        // A value transform carries a `source` child, so transform => chebyshev succeeds.
        val source = sin(1.0).fold(1.0)
        val rebuilt = OpRegistry.rebuild(source, "chebyshev")
        assertNotNull(rebuilt, "rebuild(fold-node, chebyshev) must be non-null")
        assertEquals("chebyshev", rebuilt.graphNode().op)
        for (i in 0..20) assertTrue(rebuilt.eval(i / 20.0).isFinite())

        // Reachable through the rewrite DSL too.
        val viaDsl = sin(1.0).clip(0.9).rewrite("clip => op=chebyshev")
        assertEquals("chebyshev", viaDsl.graphNode().op)
    }

    @Test
    fun orderIsContinuousControl() = with(Functions) {
        // A fractional order evaluates the real-n Chebyshev, Tₙ(v) = cos(n·arccos v).
        val drive = sin(1.0) over (0.0..1.0)
        for (i in 0..20) {
            val x = i / 20.0
            val v = drive.eval(x).coerceIn(-1.0, 1.0)
            assertEquals(
                kotlin.math.cos(2.5 * kotlin.math.acos(v)),
                drive.chebyshev(2.5).eval(x),
                1e-9,
                "chebyshev(2.5) at x=$x"
            )
        }

        // The whole point of the change: a swept order glides between harmonics rather
        // than stepping through integer bands. On a fine grid the sweep must have NO
        // jump discontinuities — adjacent samples stay close (the old roundToInt version
        // jumped by ~1–2 at each integer boundary x≈0.1,0.3,0.5,0.7,0.9).
        val sweep = drive.chebyshev(ramp(1.0, 6.0) over (0.0..1.0))
        val n = 2000
        var maxJump = 0.0
        var prev = sweep.eval(0.0)
        for (i in 1..n) {
            val cur = sweep.eval(i.toDouble() / n)
            maxJump = maxOf(maxJump, kotlin.math.abs(cur - prev))
            prev = cur
        }
        // The ~6-cycle chirp alone moves <~0.03 per step at this resolution; a retained
        // integer step would show up as a ~1+ jump. 0.1 cleanly separates the two.
        assertTrue(maxJump < 0.1, "continuous sweep should not jump; max adjacent Δ was $maxJump")
    }

    @Test
    fun rendersVisualProofSvg() = with(Functions) {
        // Sine drive → chebyshev(2..5). Each shaped curve should be a clean n-th
        // harmonic of the drive (n zero crossings-ish per period), NOT flat/garbled.
        val drive = sin(1.0, domain = Functions.Domain.of(0.0, 1.0))
        val orders = listOf(2, 3, 4, 5)
        val samples = 400
        val w = 720.0
        val h = 140.0
        val pad = 8.0

        fun polyline(pts: List<Vec2>, color: String): String {
            val coords = pts.joinToString(" ") { v ->
                val px = pad + v.x * (w - 2 * pad)
                val py = h / 2 - v.y * (h / 2 - pad)
                "%.2f,%.2f".format(px, py)
            }
            return """<polyline fill="none" stroke="$color" stroke-width="1.6" points="$coords"/>"""
        }

        val colors = listOf("#e6194b", "#3cb44b", "#4363d8", "#f58231")
        val rows = StringBuilder()
        // sanity: collect a nonflat check per order
        val ranges = mutableListOf<Double>()
        orders.forEachIndexed { idx, n ->
            val shaped = drive.chebyshev(n)
            val pts = shaped.toVec2List(samples, Functions.Domain.of(0.0, 1.0))
            val ys = pts.map { it.y.toDouble() }
            ranges += (ys.max() - ys.min())
            val y0 = idx * h
            rows.append("""<g transform="translate(0,$y0)">""")
            rows.append("""<line x1="$pad" y1="${h/2}" x2="${w-pad}" y2="${h/2}" stroke="#ccc" stroke-width="0.5"/>""")
            rows.append(polyline(drive.toVec2List(samples, Functions.Domain.of(0.0, 1.0)), "#bbb"))
            rows.append(polyline(pts, colors[idx]))
            rows.append("""<text x="${pad+4}" y="16" font-family="monospace" font-size="12" fill="${colors[idx]}">chebyshev($n) — harmonic $n</text>""")
            rows.append("</g>")
        }
        val totalH = orders.size * h
        val svg = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $totalH" width="$w" height="$totalH">
<rect width="$w" height="$totalH" fill="white"/>
$rows
</svg>"""

        // A temp dir by default — a relative path here littered the repository (and every nightly
        // agent worktree) with a `chebyshev-proof.svg`. Set AFIELD_PROOF_DIR to collect it.
        val out = File(afield.proofDir("chebyshev"), "chebyshev-proof.svg")
        out.writeText(svg)
        println("[chebyshev] wrote visual proof to ${out.absolutePath} (${out.length()} bytes)")

        // Guard the render is real: every harmonic must actually swing (not flat/black).
        ranges.forEachIndexed { i, r ->
            assertTrue(r > 0.5, "chebyshev(${orders[i]}) output range $r too small — flat/garbled render")
        }
    }
}
