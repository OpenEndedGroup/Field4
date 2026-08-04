package afield.graphics

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A viewer name with an apostrophe in it made the three.js pane go permanently blank, silently.
 *
 * `Box.threeLines(name = …)` builds a view uid as `"${box.id}_$name"` and puts it into a
 * **single-quoted** `src` attribute as well as into a query string. `name = "the client's own
 * triangulation"` closed the attribute at the apostrophe, so the iframe loaded with no `uid`, the
 * viewer subscribed as an anonymous client, and it sat on "waiting for data…" for as long as you cared
 * to look — with nothing in the browser console, the server log, or the box's output to say why.
 *
 * Found 2026-08-04 while capturing pixels for the JavaScript differential
 * ([ThreeJsViewerTessellationParityTest]): two of three panes were blank, the third was fine, and the
 * only difference between them was an apostrophe in a display name.
 */
class ThreeJsViewerIframeSourceTest {

    /** The characters that end a single-quoted HTML attribute, or a query parameter, early. */
    private val hostile = listOf(
        "the client's own triangulation",
        "a \"quoted\" name",
        "a & b",
        "a #fragment",
        "spaces everywhere",
        "a?b=c",
        "100% done",
        "a+b",
        "naïve — em dash",
        "back\\slash",
        "<script>",
    )

    @Test
    fun anEncodedSourceIsSafeInsideASingleQuotedSrcAttribute() {
        hostile.forEach { name ->
            val source = threeViewerIframeSource("box7_$name")
            // The attribute is written as src='…', so an apostrophe is the fatal one. The rest would
            // corrupt the query or the surrounding HTML instead, which is no better.
            listOf('\'', '"', '<', '>', ' ', '#').forEach { bad ->
                assertTrue(bad !in source, "`$name` encoded to `$source`, which still contains `$bad`")
            }
            // Exactly one `?` and one `=`, both structural.
            assertEquals(1, source.count { it == '?' }, source)
            assertEquals(1, source.count { it == '=' }, source)
        }
    }

    /**
     * And the uid survives the trip, which is the part that matters: it is the `__threejs__` routing
     * key, so an encoding that mangled it would trade a blank pane for a pane that never matches.
     *
     * Decoded here with [URLDecoder], which implements the same `application/x-www-form-urlencoded`
     * rules as the browser's `URLSearchParams` — including `+` for a space, which is why a plain
     * percent-encoder would *not* be interchangeable here.
     */
    @Test
    fun theUidRoundTripsThroughTheEncodingUnchanged() {
        hostile.forEach { name ->
            val uid = "box7_$name"
            val query = threeViewerIframeSource(uid).substringAfter("?uid=")
            assertEquals(uid, URLDecoder.decode(query, "UTF-8"), "`$uid` did not survive encoding")
        }
    }

    /** The ordinary name, unchanged, so the fix did not quietly rename every existing view. */
    @Test
    fun anOrdinaryNameIsUntouched() {
        assertEquals("/threejs?uid=box7_threejs", threeViewerIframeSource("box7_threejs"))
        assertEquals("/threejs?uid=box7_debug.view-2", threeViewerIframeSource("box7_debug.view-2"))
    }
}
