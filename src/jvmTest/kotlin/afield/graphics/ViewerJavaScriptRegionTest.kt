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

    /**
     * Every region in [ViewerJavaScript.KNOWN_REGIONS], held to the same four rules — so a third region
     * inherits this file rather than needing its own copy of it.
     */
    @Test
    fun everyKnownRegionIsFoundAndDeclaresWhatItExports() {
        assertTrue(ViewerJavaScript.KNOWN_REGIONS.size >= 2, "the region inventory shrank")
        ViewerJavaScript.KNOWN_REGIONS.forEach { (name, exports) ->
            val source = ViewerJavaScript.regionSource(name)
            assertTrue(source.length > 200, "region `$name` shrank to ${source.length} characters")
            exports.forEach { export ->
                assertTrue(
                    Regex("""(function|const)\s+$export\b""").containsMatchIn(source),
                    "region `$name` no longer declares `$export`, which ViewerJavaScript exports",
                )
            }
            ViewerJavaScript.Session.open(regionName = name, exports = exports).use { js ->
                exports.forEach { export -> js.member(export) }
            }
        }
    }

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
     *
     * The scan runs over [ViewerJavaScript.withoutComments] rather than the raw text: the regions
     * explain themselves at length and one of them quotes the human's workspace *document*, which is a
     * sentence rather than a DOM access. Before 2026-08-11 that word alone failed this test, which is a
     * prose rule masquerading as a behaviour rule.
     */
    @Test
    fun noRegionUsesTheDomAndOnlyTheShimmedThreeMembersAppear() {
        val allowed = setOf("Vector2", "ShapeUtils.triangulateShape")
        ViewerJavaScript.KNOWN_REGIONS.keys.forEach { name ->
            val source = ViewerJavaScript.withoutComments(ViewerJavaScript.regionSource(name))
            listOf("document", "window", "navigator", "requestAnimationFrame", "localStorage").forEach {
                assertTrue(
                    !Regex("""\b$it\b""").containsMatchIn(source),
                    "region `$name` reaches for `$it`",
                )
            }
            val used = Regex("""THREE\.(\w+(?:\.\w+)?)""").findAll(source).map { it.groupValues[1] }.toSet()
            assertTrue(
                used.all { it in allowed },
                "region `$name` uses THREE members the harness does not shim: ${used - allowed}",
            )
        }
        // And the shimmed surface is exactly used, not merely permitted — if the tessellation region
        // stops needing `triangulateShape`, the shim has become dead weight and should be reconsidered.
        val tessellation = ViewerJavaScript.withoutComments(ViewerJavaScript.regionSource("planar-tessellation"))
        val inTessellation = Regex("""THREE\.(\w+(?:\.\w+)?)""").findAll(tessellation)
            .map { it.groupValues[1] }.toSet()
        assertEquals(allowed, inTessellation, "the tessellation region's THREE surface changed")
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

    /**
     * The comment stripper loosened the DOM guard, so here is the proof it did not blunt it.
     *
     * Three shapes of real DOM access — bare, behind a block comment on the same line, and behind a
     * trailing line comment — all still trip the same scan. And the third case is the one that says the
     * stripper is safe *in the right direction*: a `//` inside a string literal makes it delete more
     * than it should, never less, so it cannot conceal a reach for the DOM. It can only cause a
     * false green on a region whose only DOM access is inside a string, which is not a thing.
     */
    @Test
    fun aRealDomAccessIsStillCaughtAfterCommentsAreStripped() {
        val forbidden = Regex("""\bdocument\b""")
        listOf(
            "const el = document.getElementById('x');",
            "/* explaining a document */ const el = document.body;",
            "const el = document.body; // fetch the document",
            "const el =\n    document\n        .body;",
        ).forEach { snippet ->
            assertTrue(
                forbidden.containsMatchIn(ViewerJavaScript.withoutComments(snippet)),
                "the stripper hid a real DOM access in: $snippet",
            )
        }
        // …while prose alone no longer trips it, which is the whole point of the change.
        listOf(
            "// quoting the human's document, which is prose",
            "/*\n * a document, at length\n */",
        ).forEach { snippet ->
            assertTrue(
                !forbidden.containsMatchIn(ViewerJavaScript.withoutComments(snippet)),
                "prose still trips the DOM guard: $snippet",
            )
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
