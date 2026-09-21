package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * What a failing `@{...}` right-hand side actually costs: **one** hydration attempt, not one per
 * matched node.
 *
 * It is tempting to read a failed hydration as an amplifier: a spec matching *n* nodes
 * would then attempt *n* compiles, each one making the next worse. It is not, and this test is the
 * fence. `hydrate()` throws, and nothing between it and the caller catches — not
 * [applyNumberAction], not [Functions.Fn.previewRewrite], not `deepCopy`'s recursion — so the first
 * failure ends the whole rewrite with the other matched nodes unvisited.
 *
 * The consequence worth remembering is the narrower one: an evaluator **reused across rewrites** (the
 * form [SourceBackedFnRewriteNumberEvaluator]'s KDoc recommends for a loop) used to re-attempt a rejected
 * compile once per rewrite. The compiler memo now remembers that rejection, until explicitly cleared.
 */
class FnRewriteHydrateFailureTest {

    /** `1.0` keeps the arithmetic irrelevant; the selector is what has to match many nodes. */
    private val spec = "**/[tag=number] => *= @{1.0}"

    private fun source() = with(Functions) { (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0) }

    /**
     * A `SourceBackedFnRewriteNumberExpression` with an empty box id fails inside `hydrate()` before
     * it reaches a compiler, which is the cheapest stand-in for any hydration failure.
     */
    private fun failingExpression() = fnRewriteNumberExpression("1.0", box = null)

    @Test
    fun theSelectorReachesManyNumericNodes() {
        var visited = 0
        source().previewRewrite(
            spec,
            FnRewriteOptions(kotlinNumberContext = fnRewriteNumber { visited++; null })
        )

        // Positive control: without several matches, "once per node" and "once in total" would be
        // the same number and the test below would prove nothing.
        assertTrue(visited > 5, "spec should match many numeric nodes, matched $visited")
    }

    @Test
    fun aHydrationFailureIsAttemptedOnceAndAbortsTheRewrite() {
        val expression = failingExpression()
        assertEquals("", expression.boxId, "the expression under test must have no box to hydrate in")

        var attempts = 0
        val counted: (FnRewriteNumberContext) -> Double? = { context ->
            attempts++
            expression(context)
        }

        var thrown: Throwable? = null
        try {
            source().previewRewrite(spec, FnRewriteOptions(kotlinNumberContext = counted))
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalStateException, "hydration failure should leave the rewrite as a throw, got $thrown")
        assertTrue(
            thrown.message?.contains("is not loaded") == true,
            "the throw should name the missing box: ${thrown.message}"
        )
        assertEquals(1, attempts, "a hydration failure must abort the rewrite, not be retried per matched node")
    }

    @Test
    fun aMissingBoxRemainsRetryableRatherThanBeingPermanentlyCached() {
        val expression = failingExpression()
        var attempts = 0
        val counted: (FnRewriteNumberContext) -> Double? = { context ->
            attempts++
            expression(context)
        }
        val options = FnRewriteOptions(kotlinNumberContext = counted)

        repeat(3) {
            runCatching { source().previewRewrite(spec, options) }
        }

        assertEquals(3, attempts, "missing boxes are transient, not compiler rejections")
    }

    @Test
    fun reusedCompilerMemoAttemptsRejectedCompilationOnceAcrossRewrites() {
        val memo = FnRewriteCompilationMemo<(FnRewriteNumberContext) -> Double?>()
        var compiles = 0
        val rejection = FnRewriteCompilationFailure("original source diagnostic")
        val options = FnRewriteOptions(kotlinNumberContext = { context ->
            memo.get { compiles++; throw rejection }(context)
        })
        repeat(3) {
            assertSame(rejection, assertFailsWith<FnRewriteCompilationFailure> {
                source().previewRewrite(spec, options)
            })
        }
        assertEquals(1, compiles, "count the compiler callback, not evaluator invocations")
        memo.clear()
        val recovered = memo.get { compiles++; { _: FnRewriteNumberContext -> null } }
        source().previewRewrite(spec, FnRewriteOptions(kotlinNumberContext = recovered))
        assertEquals(2, compiles, "explicit invalidation permits repair")
    }

    @Test
    fun transientFailuresAndRuntimeErrorsDoNotPoisonTheMemo() {
        val memo = FnRewriteCompilationMemo<() -> Unit>()
        var compiles = 0
        repeat(2) {
            assertFailsWith<java.util.concurrent.CancellationException> {
                memo.get { compiles++; throw java.util.concurrent.CancellationException() }
            }
        }
        var calls = 0
        repeat(3) {
            val block = memo.get { compiles++; { calls++; error("runtime") } }
            assertFailsWith<IllegalStateException> { block() }
        }
        assertEquals(3, compiles)
        assertEquals(3, calls)
        memo.clear()
        memo.get { compiles++; {} }()
        assertEquals(4, compiles)
    }

    @Test
    fun concurrentRejectionsCompileOnlyOnce() {
        val memo = FnRewriteCompilationMemo<String>()
        val compiles = java.util.concurrent.atomic.AtomicInteger()
        val rejection = FnRewriteCompilationFailure("bad source")
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results = (1..12).map {
                workers.submit<Throwable> {
                    assertFailsWith<FnRewriteCompilationFailure> {
                        memo.get { compiles.incrementAndGet(); throw rejection }
                    }
                }
            }
            results.forEach { assertSame(rejection, it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(1, compiles.get())
        } finally {
            workers.shutdownNow()
        }
    }
}
