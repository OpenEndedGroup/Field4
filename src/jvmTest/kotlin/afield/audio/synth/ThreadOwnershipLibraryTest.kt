package afield.audio.synth

import afield.runtime.bytecode.ThreadOwner
import afield.runtime.bytecode.ThreadOwnership
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library half of the thread-ownership guard: `Signal.render`'s cache-miss check, which catches a
 * synth graph rendered off its audio thread by *library* code, which no box-code rewrite sees.
 *
 * The case it was built for is `Param.modulate(SineOsc(...))` sampled by the graph-binding runtime on
 * the Runloop: `Param.evaluateControl` renders the modulating oscillator with the Runloop's own
 * context while the audio thread renders the same oscillator with the engine's, so the oscillator's
 * phase advances twice per block (`AGENTS.md`, "Runloop vs audio loop").
 */
class ThreadOwnershipLibraryTest {

    private val release = CountDownLatch(1)
    private var audio: Thread? = null

    @BeforeTest
    fun setUp() = ThreadOwnership.resetForTests()

    @AfterTest
    fun tearDown() {
        release.countDown()
        audio?.join(2000)
        ThreadOwnership.resetForTests()
    }

    private fun reportsFor(name: String) = ThreadOwnership.recentReports().filter { it.target.contains(name) }

    /**
     * A stand-in audio thread: owns [ctx] the way `FieldAudioEngine`'s thread does, renders [graph]
     * for [blocks] blocks, then stays alive (still owning it) until the test ends, or, with
     * [releaseAfter], gives it up and ends.
     */
    private fun playOnAudioThread(graph: Signal, ctx: SynthContext, blocks: Int = 4, releaseAfter: Boolean = false) {
        val rendered = CountDownLatch(1)
        audio = thread(name = "test-audio", isDaemon = true) {
            ctx.claimOwnership(Thread.currentThread())
            val buf = FloatArray(ctx.bufferSize)
            repeat(blocks) {
                ctx.advanceCycle()
                graph.render(buf, ctx.bufferSize, ctx)
            }
            if (releaseAfter) ctx.claimOwnership(null)
            rendered.countDown()
            if (!releaseAfter) {
                release.await()
                ctx.claimOwnership(null)
            }
        }
        assertTrue(rendered.await(10, TimeUnit.SECONDS))
        if (releaseAfter) audio!!.join(2000)
    }

    @Test
    fun aRunloopSamplerRenderingAPlayingModulatorIsReported() {
        val sine = SineOsc(Param(5f))
        val cutoff = Param(800f).modulate(sine)
        val engine = SynthContext(48_000f, 64)
        playOnAudioThread(cutoff, engine)
        assertTrue(reportsFor("SineOsc").isEmpty(), "the audio thread rendering its own graph is fine")

        // What the graph-binding sampler used to do on the Runloop: evaluate the Param with a context
        // of its own, which renders the oscillator the audio thread owns.
        val runloopSide = GraphBindingContext("thread-ownership-test", 60f, 1)
        runloopSide.advance(1f / 60f)
        val before = ThreadOwnership.libraryViolations
        cutoff.evaluateControl(runloopSide.synth)

        assertTrue(ThreadOwnership.libraryViolations > before)
        val r = reportsFor("SineOsc").single()
        assertEquals(ThreadOwner.AUDIO, r.owner)
        assertEquals("'test-audio'", r.ownerThread)
        assertNull(r.box, "no box code on this stack: the culprit is library code")
        // An internal function's JVM name carries the module suffix (`evaluateControl$<module>`).
        assertTrue(r.caller?.methodName?.startsWith("evaluateControl") == true, r.message)
        assertTrue(r.message.contains("Param.evaluateControl"), r.message)

        // Warn once: the same caller sampling again every frame is counted, not re-reported.
        ThreadOwnership.lastLibraryWalk = 0L
        runloopSide.advance(1f / 60f)
        cutoff.evaluateControl(runloopSide.synth)
        assertEquals(1, reportsFor("SineOsc").size)
    }

    @Test
    fun theSamplerThroughGraphBindingContextIsReportedToo() {
        val sine = SineOsc(Param(3f))
        val engine = SynthContext(48_000f, 64)
        playOnAudioThread(sine, engine)
        val runloopSide = GraphBindingContext("thread-ownership-test-2", 60f, 1)
        runloopSide.advance(1f / 60f)
        runloopSide.sample(sine)
        val r = ThreadOwnership.recentReports().single { it.target.startsWith("SineOsc") }
        assertEquals("sample", r.caller?.methodName, r.message)
    }

    @Test
    fun offlineRendersAndAReleasedOrStaleOwnerAreNotReported() {
        // Two offline contexts, nothing owned: the docs galleries and smoke renders.
        val offline = SineOsc(Param(220f))
        val a = SynthContext(48_000f, 32)
        val b = SynthContext(48_000f, 32)
        val buf = FloatArray(32)
        a.advanceCycle(); offline.render(buf, 32, a)
        b.advanceCycle(); offline.render(buf, 32, b)

        // An engine that stopped and gave its context up owns nothing.
        val released = SineOsc(Param(330f))
        val gone = SynthContext(48_000f, 32)
        playOnAudioThread(released, gone, releaseAfter = true)
        b.advanceCycle(); released.render(buf, 32, b)

        // A graph the engine stopped rendering (unregistered) blocks ago is no longer its graph.
        val stale = SineOsc(Param(440f))
        val engine = SynthContext(48_000f, 32)
        playOnAudioThread(stale, engine)
        repeat(3) { engine.advanceCycle() }
        b.advanceCycle(); stale.render(buf, 32, b)

        assertTrue(reportsFor("SineOsc").isEmpty(), ThreadOwnership.recentReports().toString())
    }

    @Test
    fun withTheGuardsOffNothingIsReported() {
        val sine = SineOsc(Param(5f))
        val engine = SynthContext(48_000f, 64)
        playOnAudioThread(sine, engine)
        val was = ThreadOwnership.enabled
        ThreadOwnership.enabled = false
        try {
            val other = SynthContext(48_000f, 64)
            other.advanceCycle()
            sine.render(FloatArray(64), 64, other)
        } finally {
            ThreadOwnership.enabled = was
        }
        assertTrue(reportsFor("SineOsc").isEmpty())
    }
}
