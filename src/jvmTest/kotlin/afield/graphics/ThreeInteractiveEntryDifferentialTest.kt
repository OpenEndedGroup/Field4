package afield.graphics

import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.onClick
import afield.stdlib.stroked
import afield.stdlib.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A clickable object remembers what it is — on **both** viewer paths, which it did not.
 *
 * This test exists because of a bug found on 2026-08-11 while wiring the inspector's measure tool.
 * `06352df08` taught the three.js viewer to name the element under the pointer, which needs three
 * fields on the interactive entry: `topology`, `lineIndex` and `label`. There were two places that
 * built an entry:
 *
 *  * `makeInteractiveEntry`, for editable scenes — updated, correct;
 *  * an object literal inside `upsertInteractiveObject`, for ordinary ones — **not** updated;
 *
 * and a third, `addInteractiveObject`, which had all three fields and *no callers at all*. The one
 * `renderPayloadGeometry` actually calls for a non-editable payload is the second. So on the live path
 * — the path every one of the human's `panel-*` documents takes — `entry.topology` was `undefined`,
 * `flinePickClassify` was handed `null`, and every click came back with a kind but with `nodeIndex: -1`
 * and no label. The feature was dead on exactly the figures it was written for, and nothing failed:
 * `ThreeJsHitRecordDifferentialTest` tests the *classifier*, and the classifier was fine.
 *
 * The fix is one constructor, marked as the `interactive-entry` region, with the dead third copy
 * deleted. This test is what makes "it cannot drift again" a claim rather than a hope: it feeds the
 * region a **real `ThreeInteractiveObject`**, serialised by the real server-side `Json` and parsed back
 * the way the browser parses it, and asserts the three fields survive.
 */
class ThreeInteractiveEntryDifferentialTest {

    private fun session(perturbation: (String) -> String = ViewerJavaScript.UNPERTURBED) =
        ViewerJavaScript.Session.open(
            regionName = "interactive-entry",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("interactive-entry"),
            perturbation = perturbation,
        )

    private fun panel() = fline {
        moveTo(0.0, 0.0, 0.0)
        lineTo(100.0, 0.0, 0.0)
        lineTo(100.0, 80.0, 20.0)
        lineTo(0.0, 80.0, 20.0)
        close()
        label = "panel 2,3"
        filled = true
        stroked = true
        hoverColor = vec(1.0, 0.4, 0.2, 1.0)
        onClick = { }
    }

    /** The payload's first interactive object, as JSON — exactly the text the browser receives. */
    private fun interactiveObjectJson(): String {
        val payload = buildThreeGeometryPayload(
            uid = "t", lines = listOf(panel()), samplesPerCubic = 12, autoFrame = true, editable = false,
        )
        assertEquals(1, payload.interactive.size, "the figure should have produced one interactive object")
        val encoded = Json { encodeDefaults = true }.encodeToString(payload)
        val obj = Json.parseToJsonElement(encoded).jsonObject.getValue("interactive")
        return obj.toString().removePrefix("[").removeSuffix("]")
    }

    /**
     * The whole point: a real interactive object round-trips into an entry that still knows itself.
     *
     * `topology` parsed rather than a string, `lineIndex` the real index, `label` the FLine's own — and
     * the topology usable, i.e. it really is the object `flinePickClassify` expects and not an empty
     * one that happens to be non-null.
     */
    @Test
    fun `a real interactive object becomes an entry that knows its topology, index and label`() {
        val json = interactiveObjectJson()
        session().use { js ->
            val source = js.fromJson(json)
            val entry = js.call("interactiveEntry", source, js.fromJson("[]"), null)
            assertEquals(0, entry.getMember("lineIndex").asInt())
            assertEquals("panel 2,3", entry.getMember("label").asString())
            val topology = entry.getMember("topology")
            assertTrue(!topology.isNull, "the entry lost its topology — this is the 2026-08-11 bug")
            assertTrue(
                topology.getMember("segments").arraySize >= 4,
                "the topology parsed to something without segments",
            )
            assertTrue(topology.getMember("faces").arraySize >= 1, "the topology has no faces")
            assertTrue(entry.getMember("callbackId").asString().startsWith("fline-"))
            assertTrue(!entry.getMember("hoverColor").isNull)
        }
    }

    /**
     * An editable scene's line goes through the same constructor and keeps the same three fields.
     *
     * Which is the invariant the two-copy arrangement could not state: the server names these fields
     * identically on `ThreeEditableLine` and on `ThreeInteractiveObject`, and that is what makes one
     * constructor possible. If either shape drifts, this fails.
     */
    @Test
    fun `an editable scene line goes through the same constructor`() {
        val payload = buildThreeGeometryPayload(
            uid = "t", lines = listOf(panel()), samplesPerCubic = 12, autoFrame = true, editable = true,
        )
        val encoded = Json { encodeDefaults = true }.encodeToString(payload)
        val line = Json.parseToJsonElement(encoded).jsonObject
            .getValue("scene").jsonObject.getValue("lines").toString()
            .removePrefix("[").removeSuffix("]")
        session().use { js ->
            val source = js.fromJson(line)
            val vertices = js.call("verticesForLine", source)
            val entry = js.call("interactiveEntry", source, js.fromJson("[]"), vertices)
            assertEquals(0, entry.getMember("lineIndex").asInt())
            assertEquals("panel 2,3", entry.getMember("label").asString())
            assertTrue(!entry.getMember("topology").isNull, "the editable path lost its topology")
            // four anchors, no `close`
            assertEquals(4L, entry.getMember("vertices").arraySize)
            assertEquals(0, entry.getMember("vertices").getArrayElement(0).getMember("index").asInt())
        }
    }

    /** A hover-only object carries no topology by design, and the entry says so without throwing. */
    @Test
    fun `an object with no topology yields a usable entry`() {
        session().use { js ->
            val entry = js.call(
                "interactiveEntry",
                js.fromJson("""{"callbackId": "", "lineIndex": 3, "label": null, "topology": ""}"""),
                js.fromJson("[]"),
                null,
            )
            assertEquals(3, entry.getMember("lineIndex").asInt())
            assertTrue(entry.getMember("label").isNull)
            assertTrue(entry.getMember("topology").isNull)
            assertEquals(0L, entry.getMember("vertices").arraySize)
        }
    }

    /** Unparsable topology degrades to null rather than throwing inside a render pass. */
    @Test
    fun `unparsable topology degrades to null`() {
        session().use { js ->
            assertTrue(js.call("parseFLineTopology", "{not json").isNull)
            assertTrue(js.call("parseFLineTopology", "").isNull)
        }
    }

    /**
     * And the source itself no longer contains a second entry constructor.
     *
     * The differential above proves the *surviving* constructor is right. It cannot prove that nobody
     * added a second one next to it, which is precisely how the original bug happened — so this checks
     * the shape of the file: exactly one `topology: parseFLineTopology(` in the whole viewer, and no
     * `addInteractiveObject` at all.
     */
    @Test
    fun `there is exactly one entry constructor in the viewer`() {
        val text = ViewerJavaScript.VIEWER.readText()
        val constructors = Regex("""topology:\s*parseFLineTopology\(""").findAll(text).count()
        assertEquals(
            1, constructors,
            "there are $constructors places that build an interactive entry's topology; " +
                "on 2026-08-11 there were three and only two of them were right",
        )
        assertTrue(
            !Regex("""function\s+addInteractiveObject\b""").containsMatchIn(text),
            "the dead third copy is back",
        )
    }

    /**
     * Counterfactual: put the drift back and the differential catches it.
     *
     * Dropping `topology` from the constructor is exactly the pre-fix state of
     * `upsertInteractiveObject`, and the assertion that fails is the one that would have caught it in
     * the first place.
     */
    @Test
    fun `counterfactual — a constructor that forgets the topology is caught`() {
        val json = interactiveObjectJson()
        session { it.replace("topology: parseFLineTopology(source.topology)", "topology: undefined") }.use { js ->
            val entry = js.call("interactiveEntry", js.fromJson(json), js.fromJson("[]"), null)
            assertTrue(
                entry.getMember("topology") == null || entry.getMember("topology").isNull,
                "the perturbation should have removed the topology",
            )
        }
    }

    /** A perturbation that rewrites nothing is refused. */
    @Test
    fun `a perturbation that rewrites nothing is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            session { it.replace("a string this region does not contain", "x") }.close()
        }
        assertTrue(failure.message!!.contains("left the `interactive-entry` region unchanged"), failure.message!!)
    }
}
