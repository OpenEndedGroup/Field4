package afield.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The health of the seam itself, rather than of the code behind it.
 *
 * A test harness that lifts source text out of a file has one characteristic failure: the text moves,
 * the extraction quietly finds nothing or finds something that no longer runs, and the differential
 * goes green for the wrong reason. That is the exact shape of the bug this branch exists to fix, so it
 * would be poor to reintroduce it one level up.
 *
 * So: the sentinels must be present and unique, the region must actually contain the functions the
 * harness thinks it does, and the two rules that keep it liftable — no DOM, and no `THREE` beyond
 * `Vector2` and `ShapeUtils.triangulateShape` — are asserted rather than requested.
 */
class ViewerJavaScriptRegionTest {

    private val region: String by lazy { ViewerJavaScript.regionSource("planar-tessellation") }

    @Test
    fun theRegionIsFoundAndHoldsTheFunctionsTheHarnessExports() {
        assertTrue(region.length > 2000, "the region shrank to ${region.length} characters")
        ViewerJavaScript.DEFAULT_EXPORTS.forEach { name ->
            assertTrue(
                Regex("""(function|const)\s+$name\b""").containsMatchIn(region),
                "the region no longer declares `$name`, which ViewerJavaScript exports",
            )
        }
        // `fillCoplanarGroup` is not exported — it is reached through `tessellateCoplanarGroups` — but
        // it has to be *inside* the region or the region will not evaluate.
        assertTrue(region.contains("function fillCoplanarGroup("), "fillCoplanarGroup left the region")
    }

    @Test
    fun aMissingSentinelIsALoudFailureRatherThanAnEmptyRegion() {
        val failure = assertFailsWith<IllegalStateException> {
            ViewerJavaScript.regionSource("a-region-that-was-never-marked")
        }
        assertTrue(failure.message!!.contains("is missing from"), failure.message!!)
    }

    /**
     * No DOM, and no `THREE` beyond the two members the harness shims.
     *
     * If the viewer ever needs more of three.js in here, this test is the place that says so — and the
     * answer is to extend the shim deliberately, not to discover months later that the region stopped
     * being executable and the differential stopped meaning anything.
     */
    @Test
    fun theRegionUsesNoDomAndOnlyTheTwoThreeMembersTheHarnessShims() {
        listOf("document", "window", "navigator", "requestAnimationFrame", "localStorage").forEach {
            assertTrue(!Regex("""\b$it\b""").containsMatchIn(region), "the region reaches for `$it`")
        }
        val used = Regex("""THREE\.(\w+(?:\.\w+)?)""").findAll(region).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("Vector2", "ShapeUtils.triangulateShape"), used, "the region's THREE surface changed")
    }

    /**
     * And the region evaluates and answers, which is the only claim that matters. Deliberately the
     * simplest possible case, so that a failure here means "the harness is broken" rather than "the
     * grouping rule is wrong".
     */
    @Test
    fun theRegionEvaluatesAndTriangulatesASquare() {
        ViewerJavaScript.open().use { js ->
            val polygons = js.call(
                "tessellateCoplanarGroups",
                js.fromJson("[[[0,0,0],[100,0,0],[100,100,0],[0,100,0]]]"),
            )
            assertEquals(1L, polygons.arraySize)
            assertEquals(0, polygons.getArrayElement(0).getMember("outer").asInt())
            assertEquals(6L, polygons.getArrayElement(0).getMember("triangles").arraySize)
        }
    }

    /** A perturbation that matched nothing is a mistake, not a passing test. */
    @Test
    fun aPerturbationThatRewritesNothingIsRefused() {
        val failure = assertFailsWith<IllegalStateException> {
            ViewerJavaScript.perturbed { it.replace("a string the viewer does not contain", "x") }.close()
        }
        assertTrue(failure.message!!.contains("left the `planar-tessellation` region unchanged"), failure.message!!)
    }
}
