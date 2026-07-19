package afield.stdlib

import afield.stdlib.Functions.Fn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression guard for oscillators whose default domain is unbounded.
 *
 * That change flipped every periodic constructor (sin/cos/saw/triangle/square and the
 * pulseTrain/impulseTrain/pulseBurst family) to default `domain = Domain.ALL`, and made the
 * public enumeration surface safe by routing each implicit-domain sampler/iterator through
 * `Domain.finiteSweep()` (which previews an unbounded domain over a finite window). The
 * hazard it defends against: any enumerator that walks `Domain.ALL` directly would hang
 * (infinite `while`) or OOM (a `count` computed as `Inf/step` → Int.MAX_VALUE allocation)
 * when handed a *fresh* oscillator.
 *
 * This test hands every public builder to every public enumerator with DEFAULT arguments
 * and asserts each returns a finite, size-bounded result quickly. If a future edit drops a
 * `finiteSweep()` guard:
 *   - step / `while`-based enumerators (toDoubleArray(step), sampleZeroCrossings,
 *     samplePeaks) hang or OOM → the per-oscillator worker-thread timeout fires → FAIL
 *     (rather than hanging the whole suite);
 *   - start-anchored streams/cursor emit NaN from `x = -Infinity` → the finiteness
 *     assertions FAIL.
 * On the current (guarded) code every case is green.
 */
class OscillatorDomainGuardTest {

    // A fresh oscillator previews over plotSafe()'s default window (Domain.DEFAULT = 0..1),
    // so every default-arg enumeration is tiny. This cap is orders of magnitude above any
    // legitimate default result and far below the ~2e9-element allocation an unguarded
    // Inf/step count would attempt.
    private val sizeCap = 200_000

    // A generous anti-hang backstop. Each oscillator's full battery runs on [0,1] and
    // completes in milliseconds; only an unbounded regression would blow past this.
    private val timeoutMs = 15_000L

    @Test
    fun everyPublicEnumeratorStaysFiniteAndBoundedOnInfiniteDomainOscillators() = with(Functions) {
        val oscillators: List<Pair<String, Fn>> = listOf(
            "sin" to sin(),
            "cos" to cos(),
            "saw" to saw(),
            "triangle" to triangle(),
            "square" to square(),
            "pulseTrain" to pulseTrain(),
            "impulseTrain" to impulseTrain(),
            "pulseBurst" to pulseBurst(4)
        )

        // Premise check: the test only proves something if these really default to an
        // infinite domain. If a builder ever reverts to a finite default, this fails loudly
        // so the guard test cannot silently become a no-op.
        for ((name, fn) in oscillators) {
            assertFalse(
                fn.domain.isFinite,
                "$name must default to an infinite domain for this guard test to be meaningful"
            )
        }

        for ((name, fn) in oscillators) {
            runBounded("$name enumerator battery") {
                exerciseEnumerators(name, fn)
            }
        }
    }

    /** Runs [block] on a daemon worker; fails (rather than hangs) if it does not finish. */
    private fun runBounded(label: String, block: () -> Unit) {
        var failure: Throwable? = null
        val worker = Thread {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            worker.interrupt()
            fail("$label did not finish within ${timeoutMs}ms — likely an unbounded-domain enumeration regression")
        }
        failure?.let { throw AssertionError("$label failed: ${it.message}", it) }
    }

    private fun exerciseEnumerators(name: String, fn: Fn) = with(Functions) {
        fun boundedSize(label: String, size: Int, expected: Int? = null) {
            assertTrue(size in 0..sizeCap, "$name.$label produced $size elements (cap $sizeCap)")
            if (expected != null) {
                assertTrue(size == expected, "$name.$label produced $size elements, expected $expected")
            }
        }
        fun finite(label: String, v: Double) =
            assertTrue(v.isFinite(), "$name.$label produced a non-finite value ($v)")

        // --- fixed-count materialisers: must honour the requested count, not the domain span
        val da = fn.toDoubleArray(8192)
        boundedSize("toDoubleArray(count)", da.size, expected = 8192)
        finite("toDoubleArray(count)[0]", da[0])

        // step-based: on an unguarded Domain.ALL this computes count = Inf/step and OOMs
        val daStep = fn.toDoubleArray(1e-3)
        boundedSize("toDoubleArray(step)", daStep.size)
        assertTrue(daStep.isNotEmpty(), "$name.toDoubleArray(step) should preview a non-empty window")

        boundedSize("toVec2List(count)", fn.toVec2List(8192).size, expected = 8192)
        boundedSize("toFLine", fn.toFLine(samples = 2048).nodes.size)

        boundedSize("sampleUniform", fn.sampleUniform(8192).size, expected = 8192)
        boundedSize("sampleJittered", fn.sampleJittered(8192).size, expected = 8192)
        boundedSize("sampleRandom", fn.sampleRandom(8192).size, expected = 8192)
        boundedSize("sampleByDensity", fn.sampleByDensity(2048, density = one()).size)

        // step-based `while` scans: on unguarded Domain.ALL these never terminate
        boundedSize("sampleZeroCrossings", fn.sampleZeroCrossings().size)
        boundedSize("samplePeaks", fn.samplePeaks(32).size)

        // start-anchored: default start = domain.finiteSweep().min; a regression to
        // domain.min = -Infinity makes every emitted x, and thus value, NaN.
        finite("cursor().x", fn.cursor().x)
        val stream = fn.stream().take(8192).toList()
        boundedSize("stream().take", stream.size, expected = 8192)
        assertTrue(stream.all { it.isFinite() }, "$name.stream() emitted a non-finite value")

        val streamX = fn.streamWithX().take(8192).toList()
        boundedSize("streamWithX().take", streamX.size, expected = 8192)
        assertTrue(
            streamX.all { it.first.isFinite() && it.second.isFinite() },
            "$name.streamWithX() emitted a non-finite (x,y)"
        )

        assertTrue(
            fn.lowpassStream(0.2).take(8192).all { it.isFinite() },
            "$name.lowpassStream() emitted a non-finite value"
        )
        assertTrue(
            fn.highpassStream(0.2).take(8192).all { it.isFinite() },
            "$name.highpassStream() emitted a non-finite value"
        )

        // graph transforms whose default origin/period come from the (infinite) domain
        finite("integral().eval", fn.integral().eval(0.5))
        finite("repeat().eval", fn.repeat().eval(0.5))
        finite("mirrorRepeat().eval", fn.mirrorRepeat().eval(0.5))
        finite("tile(4).eval", fn.tile(4).eval(0.5))

        // stats-driven rescalers: enumerate an implicit domain to gather statistics
        finite("autoScale().eval", fn.autoScale().eval(0.5))
        finite("zNormalize().eval", fn.zNormalize().eval(0.5))
        finite("whitenCdf().eval", fn.whitenCdf().eval(0.5))

        // plot HTML embeds a sampled series; must sweep plotSafe(), not the infinite domain
        val html = fn.plotHtml()
        assertTrue(html.isNotEmpty(), "$name.plotHtml() returned empty")
        assertTrue(html.length < 5_000_000, "$name.plotHtml() produced an implausibly large document")
    }
}
