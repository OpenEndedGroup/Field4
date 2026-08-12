package afield.graphics

import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.stroked
import afield.stdlib.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **wire** between the Kotlin inspector and the viewer that reads it.
 *
 * The three region tests execute the viewer's arithmetic. They say nothing about whether the payload
 * the server sends is the payload the viewer's JavaScript reaches for, and that is a real gap: it is a
 * hand-written JSON contract between a `@Serializable` Kotlin class and a set of dotted accessors in an
 * HTML file, with a compiler on exactly one side of it. Rename `ThreeExplode.mode` and every test in
 * this package stays green while the explode silently reverts to radial.
 *
 * So this file does two things. It asserts the JSON's *shape* — the keys, in the places the viewer
 * looks — and then it asserts that the viewer **actually contains** each of those accessors, by
 * grepping the shipped HTML. Neither half is sufficient: the first would pass after a rename on both
 * sides of a key the viewer never reads, and the second would pass if the server stopped emitting it.
 * Together they pin the contract from both ends.
 *
 * It also covers [ThreeInspectorView]'s mutators, which are the point of the class — the answer to
 * `explosionMm = 0.0` is that the number becomes something a box can *change*, and "it republishes"
 * should be a test rather than a claim.
 */
class ThreeInspectorPayloadTest {

    private val json = Json { encodeDefaults = true }

    private fun panel(name: String, x: Double, interactive: Boolean = false) = fline {
        moveTo(x, 0.0, 0.0)
        lineTo(x, 100.0, 0.0)
        lineTo(x, 100.0, 100.0)
        lineTo(x, 0.0, 100.0)
        close()
        label = name
        filled = true
        stroked = true
        if (interactive) hoverColor = vec(1.0, 0.5, 0.2, 1.0)
    }

    private fun encode(payload: ThreeGeometryPayload): JsonElement =
        Json.parseToJsonElement(json.encodeToString(payload))

    private fun payload(
        lines: List<FLine>,
        inspector: ThreeInspector,
    ) = buildThreeGeometryPayload(
        uid = "wire", lines = lines, samplesPerCubic = 12, autoFrame = true, editable = false,
        groupKeys = lines.map { it.label ?: "" }, inspector = inspector,
    )

    // ---------------------------------------------------------------- the shape

    /**
     * Every key the viewer reads is in the JSON, at the path it reads it from.
     *
     * The list is not decorative — each entry below corresponds to a line of `threejs_viewer.html`, and
     * the companion test greps for them.
     */
    @Test
    fun `the inspector block serialises to the keys the viewer reads`() {
        val lines = listOf(panel("a", 0.0), panel("b", 40.0))
        val encoded = encode(
            payload(
                lines,
                ThreeInspector(
                    enabled = true,
                    explode = ThreeExplode(amount = 12.5, mode = "axis", axis = floatArrayOf(0f, 0f, 1f), sliderMax = 99.0),
                    section = ThreeSection(enabled = true, normal = floatArrayOf(1f, 0f, 0f), fraction = 0.25, outline = true, flip = true),
                    measurements = listOf(
                        ThreeMeasurement(
                            ThreeMeasureElement.point(1.0, 2.0, 3.0),
                            ThreeMeasureElement.face(lines[1]),
                            "clear ",
                        )
                    ),
                    units = "mm",
                    decimals = 1,
                )
            )
        ).jsonObject

        val inspector = encoded.getValue("inspector").jsonObject
        assertEquals(true, inspector.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertEquals(listOf("a", "b"), inspector.getValue("groups").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("mm", inspector.getValue("units").jsonPrimitive.content)
        assertEquals(1, inspector.getValue("decimals").jsonPrimitive.int())

        val explode = inspector.getValue("explode").jsonObject
        assertEquals(12.5, explode.getValue("amount").jsonPrimitive.content.toDouble())
        assertEquals("axis", explode.getValue("mode").jsonPrimitive.content)
        assertEquals(listOf(0.0, 0.0, 1.0), explode.getValue("axis").jsonArray.map { it.jsonPrimitive.content.toDouble() })
        assertEquals(99.0, explode.getValue("sliderMax").jsonPrimitive.content.toDouble())

        val section = inspector.getValue("section").jsonObject
        assertEquals("true", section.getValue("enabled").jsonPrimitive.content)
        assertEquals(0.25, section.getValue("fraction").jsonPrimitive.content.toDouble())
        assertEquals("true", section.getValue("flip").jsonPrimitive.content)
        assertEquals("true", section.getValue("outline").jsonPrimitive.content)
        assertEquals(listOf(1.0, 0.0, 0.0), section.getValue("normal").jsonArray.map { it.jsonPrimitive.content.toDouble() })

        val measurement = inspector.getValue("measurements").jsonArray.single().jsonObject
        assertEquals("clear ", measurement.getValue("label").jsonPrimitive.content)
        assertEquals("point", measurement.getValue("a").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(1, measurement.getValue("a").jsonObject.getValue("points").jsonArray.size)
        // `face` uses the anchors and drops the `close`, so a four-corner panel is four points
        assertEquals("polygon", measurement.getValue("b").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(4, measurement.getValue("b").jsonObject.getValue("points").jsonArray.size)

        // and the per-vertex group attribute, which is what `explodeApply` indexes with
        val group = encoded.getValue("fills").jsonObject.getValue("attributes").jsonObject
            .getValue("group").jsonObject
        assertEquals(1, group.getValue("itemSize").jsonPrimitive.int())
        assertEquals(8, group.getValue("array").jsonArray.size, "two 4-corner panels is 8 fill vertices")
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int() = content.toDouble().toInt()

    /** An interactive object carries a scalar group, because the viewer moves it whole. */
    @Test
    fun `an interactive object carries its assembly group`() {
        val lines = listOf(panel("a", 0.0), panel("b", 40.0, interactive = true))
        val encoded = encode(payload(lines, ThreeInspector()))
        val interactive = encoded.jsonObject.getValue("interactive").jsonArray.single().jsonObject
        assertEquals(1, interactive.getValue("group").jsonPrimitive.int())
        assertEquals(1, interactive.getValue("lineIndex").jsonPrimitive.int())
    }

    /**
     * A plain `threeLines` payload is byte-identical to what it was before the inspector existed.
     *
     * The most valuable property in the change and the easiest to lose: `inspector` is `null`, no
     * geometry grows a `group` attribute, and `interactive[].group` is the `-1` the viewer treats as
     * "not in an assembly". A browser holding a cached viewer sees exactly the payload it expects.
     */
    @Test
    fun `an ordinary payload is unchanged by the inspector existing`() {
        val lines = listOf(panel("a", 0.0), panel("b", 40.0, interactive = true))
        val plain = buildThreeGeometryPayload("plain", lines, 12, true, false)
        assertNull(plain.inspector)
        assertNull(plain.fills!!.attributes["group"])
        assertNull(plain.lines!!.attributes["group"])
        assertEquals(-1, plain.interactive.single().group)
        val encoded = encode(plain).jsonObject
        assertTrue(encoded.getValue("inspector").toString() == "null", "the inspector key should serialise to null")
    }

    // ---------------------------------------------------------------- the other end of the wire

    /**
     * The viewer really reads these keys — grepped out of the shipped HTML.
     *
     * Without this, the test above pins the JSON against nothing but itself. With it, renaming
     * `ThreeExplode.mode` breaks a test on the day it happens rather than on the day someone notices
     * every explode is radial.
     */
    @Test
    fun `the viewer reaches for every key the payload sends`() {
        val viewer = ViewerJavaScript.VIEWER.readText()
        val accessors = listOf(
            "payload.inspector",
            "spec.groups",
            "spec.explode",
            "spec.section",
            "spec.measurements",
            "spec.units",
            "spec.decimals",
            "explode.mode",
            "explode.axis",
            "explode.sliderMax",
            "section.normal",
            "section.fraction",
            "section.flip",
            "section.outline",
            "section.enabled",
            "attributes.group",
            "obj.group",
            "m.a.kind",
            "m.b.kind",
        )
        val missing = accessors.filterNot { viewer.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "the payload sends keys the viewer never reads (or the viewer renamed them): $missing",
        )
    }

    // ---------------------------------------------------------------- the view's mutators

    private fun view(lines: List<FLine>, inspector: ThreeInspector = ThreeInspector()) = ThreeInspectorView(
        state = ThreeGeometryViewState("mutators"),
        lines = lines,
        groupKeys = lines.map { it.label ?: "" },
        samplesPerCubic = 12,
        autoFrame = true,
        inspector = inspector,
    )

    /**
     * `view.explode(n)` is the whole answer to `explosionMm = 0.0`: the number becomes something a box
     * can change without rebuilding the geometry or re-running itself.
     */
    @Test
    fun `the view republishes when it is asked a new question`() {
        val lines = listOf(panel("a", 0.0), panel("b", 40.0))
        val v = view(lines)
        assertEquals(0.0, v.inspector().explode.amount)

        v.explode(180.0)
        assertEquals(180.0, v.inspector().explode.amount)
        assertEquals("radial", v.inspector().explode.mode, "explode(n) must not change the mode")

        v.explodeAlong(0.0, 0.0, 1.0, 240.0)
        assertEquals(240.0, v.inspector().explode.amount)
        assertEquals("axis", v.inspector().explode.mode)
        assertEquals(listOf(0f, 0f, 1f), v.inspector().explode.axis.toList())

        v.section(1.0, 0.0, 0.0, fraction = 0.3)
        assertTrue(v.inspector().section.enabled)
        assertEquals(0.3, v.inspector().section.fraction)
        v.clearSection()
        assertTrue(!v.inspector().section.enabled)
        assertEquals(0.3, v.inspector().section.fraction, "clearing a section should not forget where it was")

        v.measureFaces(lines[0], lines[1], "gap ")
        assertEquals(1, v.inspector().measurements.size)
        assertEquals("gap ", v.inspector().measurements.single().label)
        assertEquals(4, v.inspector().measurements.single().a.points.size)
        v.clearMeasurements()
        assertEquals(0, v.inspector().measurements.size)
    }

    /** Unlabelled lines still get one group each, rather than all collapsing into one. */
    @Test
    fun `unlabelled lines each get their own group`() {
        // de-duplicated in FIRST-APPEARANCE order, not sorted: the order is the order the `group`
        // vertex attribute indexes into and the order the parts are listed in, and a wall's layers come
        // in a build-up order rather than an alphabetical one.
        assertEquals(listOf("b", "a"), distinctGroupNames(listOf("b", "a", "b")))
        assertEquals(listOf("#0", "#1"), distinctGroupNames(listOf("#0", "#1")))
        // the fallback `threeInspect` applies for a blank label, spelled out here so the convention is
        // pinned somewhere other than in a lambda default
        val keys = listOf("", "wall", "").mapIndexed { index, key -> key.ifBlank { "#$index" } }
        assertEquals(listOf("#0", "wall", "#2"), keys)
        assertEquals(3, distinctGroupNames(keys).size)
    }
}
