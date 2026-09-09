package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a failing `@{...}` right-hand side actually costs: **one** hydration attempt, not one per
 * matched node.
 *
 * `SourceBackedFnRewriteNumberExpression.hydratedBlock` is assigned only when `hydrate()` returns
 * normally, so the memo caches successes and not failures — true since the file was written
 * (`f91613648`, 2026-07-09). It is tempting to read that as an amplifier: a spec matching *n* nodes
 * would then attempt *n* compiles, each one making the next worse. It is not, and this test is the
 * fence. `hydrate()` throws, and nothing between it and the caller catches — not
 * [applyNumberAction], not [Functions.Fn.previewRewrite], not `deepCopy`'s recursion — so the first
 * failure ends the whole rewrite with the other matched nodes unvisited.
 *
 * The consequence worth remembering is the narrower one: an evaluator **reused across rewrites** (the
 * form [SourceBackedFnRewriteNumberEvaluator]'s KDoc recommends for a loop) re-attempts the compile
 * once per rewrite, because the failure was never remembered. Once per call, never once per node.
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
    fun aReusedEvaluatorRetriesOncePerRewriteBecauseFailuresAreNotMemoized() {
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

        // This is the whole of the missing negative cache: three rewrites, three attempts. Were
        // failures memoized this would be 1, and the later rewrites would fail without compiling.
        assertEquals(3, attempts, "each rewrite re-attempts the known-bad hydration exactly once")
    }
}
