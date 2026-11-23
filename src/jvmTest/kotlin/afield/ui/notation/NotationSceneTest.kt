package afield.ui.notation

import afield.math.linalg.vec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The promises [NotationScene]'s KDoc and the element helpers beside it make, pinned without a
 * session: parse/serialize, [NotationScene.withElement], [NotationScene.merge], [NotationChange],
 * [MarkType], and the world-space geometry helpers (`worldPoints`, `withWorldPoints`,
 * `worldBounds`), including empty, degenerate and non-finite input.
 */
class NotationSceneTest {
    private fun el(id: String, type: String = "rectangle", x: Double = 0.0, y: Double = 0.0, w: Double = 10.0, h: Double = 10.0,
                   version: Long = 1, nonce: Long = 1, deleted: Boolean = false,
                   points: List<List<JsonElement>>? = null): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id)); put("type", JsonPrimitive(type))
        put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
        put("width", JsonPrimitive(w)); put("height", JsonPrimitive(h))
        put("version", JsonPrimitive(version)); put("versionNonce", JsonPrimitive(nonce))
        put("isDeleted", JsonPrimitive(deleted))
        if (points != null) put("points", JsonArray(points.map { JsonArray(it) }))
    }

    private fun pts(vararg p: Pair<Double, Double>) = p.map { listOf<JsonElement>(JsonPrimitive(it.first), JsonPrimitive(it.second)) }

    private fun scene(vararg e: JsonObject) = NotationScene.EMPTY.withElements(e.toList())

    // ── parse / serialize ────────────────────────────────────────────────────────────────────

    @Test
    fun nullBlankAndMissingTextParseToEmpty() {
        assertSame(NotationScene.EMPTY, NotationScene.parse(null))
        assertSame(NotationScene.EMPTY, NotationScene.parse("   "))
        assertSame(NotationScene.EMPTY, NotationScene.parseOrNull(""))
    }

    @Test
    fun textThatIsNotASceneIsNullFromParseOrNullButEmptyFromParse() {
        for (bad in listOf("not json", "[1,2]", "42", "{\"elements\":")) {
            assertNull(NotationScene.parseOrNull(bad), bad)
            assertSame(NotationScene.EMPTY, NotationScene.parse(bad), bad)
        }
    }

    @Test
    fun theEmptySceneSerializesToAnExcalidrawDocumentWithNoElements() {
        val back = NotationScene.parseOrNull(NotationScene.EMPTY.serialize())
        assertNotNull(back)
        assertTrue(back.elements.isEmpty())
        assertTrue(NotationScene.EMPTY.serialize().contains("\"type\":\"excalidraw\""))
    }

    @Test
    fun nonObjectEntriesInElementsAreSkippedAndMissingElementsIsEmpty() {
        val s = NotationScene.parse("""{"type":"excalidraw","elements":[1,"x",null,{"id":"a","type":"text"}]}""")
        assertEquals(listOf("a"), s.elements.map { it.string("id") })
        assertTrue(NotationScene.parse("""{"type":"excalidraw"}""").elements.isEmpty())
    }

    @Test
    fun liveDropsTombstonesButElementsAndLookupKeepThem() {
        val s = scene(el("a"), el("b", deleted = true), el("c"))
        assertEquals(listOf("a", "b", "c"), s.elements.map { it.string("id") })
        assertEquals(listOf("a", "c"), s.live.map { it.string("id") })
        assertNotNull(s["b"])
        assertNull(s["nope"])
    }

    @Test
    fun aTextElementRoundTripsWithItsUnicodeText() {
        val t = JsonObject(el("t", "text") + mapOf("text" to JsonPrimitive("π ≈ 3.14 — \"quoted\"\nline 2 🎵")))
        val back = NotationScene.parse(scene(t).serialize())
        assertEquals("π ≈ 3.14 — \"quoted\"\nline 2 🎵", back["t"]!!.string("text"))
    }

    // ── withElement ──────────────────────────────────────────────────────────────────────────

    @Test
    fun withElementAppendsANewIdAndReplacesAnExistingOneInPlace() {
        val s = scene(el("a"), el("b"))
        val moved = s.withElement(el("a", x = 99.0))
        assertEquals(listOf("a", "b"), moved.elements.map { it.string("id") })
        assertEquals(99.0, moved["a"]!!.double("x"))
        assertEquals(listOf("a", "b", "c"), s.withElement(el("c")).elements.map { it.string("id") })
        assertEquals(listOf("a", "b"), s.elements.map { it.string("id") }, "the original is untouched")
    }

    @Test
    fun withElementBumpsVersionPastBothTheHeldAndTheIncomingCopy() {
        val s = scene(el("a", version = 1)) // version 2 after the first bump
        assertEquals(3L, s.withElement(el("a", version = 1)).get("a")!!.long("version"))
        assertEquals(11L, s.withElement(el("a", version = 10)).get("a")!!.long("version"))
    }

    @Test
    fun withElementsOfNothingIsTheSameScene() {
        val s = scene(el("a"))
        assertSame(s, s.withElements(emptyList()))
    }

    @Test
    fun withElementRefusesAnElementWithoutAnId() {
        val r = runCatching { NotationScene.EMPTY.withElement(buildJsonObject { put("type", JsonPrimitive("text")) }) }
        assertTrue(r.isFailure)
    }

    // ── merge / change ───────────────────────────────────────────────────────────────────────

    @Test
    fun mergingWithEmptyKeepsTheOtherSide() {
        val s = NotationScene.parse("""{"type":"excalidraw","elements":[${el("a")}]}""")
        assertEquals(listOf("a"), NotationScene.merge(s, NotationScene.EMPTY).elements.map { it.string("id") })
        assertEquals(listOf("a"), NotationScene.merge(NotationScene.EMPTY, s).elements.map { it.string("id") })
    }

    @Test
    fun aNewerTombstoneWinsTheMergeAndIsReportedAsRemoved() {
        val before = NotationScene.parse("""{"elements":[${el("a", version = 3)}]}""")
        val incoming = NotationScene.parse("""{"elements":[${el("a", version = 4, deleted = true)}]}""")
        val merged = NotationScene.merge(before, incoming)
        assertTrue(merged.live.isEmpty())
        val change = NotationChange.between(before, merged)
        assertEquals(listOf("a"), change.removed.map { it.string("id") })
        assertTrue(change.added.isEmpty() && change.updated.isEmpty())
    }

    @Test
    fun changeBetweenASceneAndItselfIsEmptyAndAVersionBumpIsAnUpdate() {
        val s = scene(el("a"))
        assertTrue(NotationChange.between(s, s).isEmpty)
        val c = NotationChange.between(s, s.withElement(el("a", x = 5.0)))
        assertEquals(listOf("a"), c.updated.map { it.string("id") })
        assertEquals(listOf("b"), NotationChange.between(s, s.withElement(el("b"))).added.map { it.string("id") })
    }

    // ── MarkType ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun markTypesMapExcalidrawNamesAndOnlyLinesArrowsAndStrokesAreLinear() {
        assertEquals(MarkType.STROKE, MarkType.of("freedraw"))
        assertEquals(MarkType.FRAME, MarkType.of("magicframe"))
        assertEquals(MarkType.OTHER, MarkType.of(null))
        assertEquals(MarkType.OTHER, MarkType.of("embeddable"))
        assertEquals(setOf(MarkType.LINE, MarkType.ARROW, MarkType.STROKE), MarkType.entries.filter { it.isLinear }.toSet())
    }

    // ── geometry ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun worldPointsAreRelativeToXYAndMalformedPointsAreSkipped() {
        val raw = pts(0.0 to 0.0, 5.0 to -3.0) + listOf(listOf<JsonElement>(JsonPrimitive(1.0)), listOf<JsonElement>(JsonPrimitive("x"), JsonPrimitive(2.0)))
        val p = el("l", "line", x = 10.0, y = 20.0, points = raw).worldPoints()
        assertEquals(listOf(10.0 to 20.0, 15.0 to 17.0), p.map { it.x.toDouble() to it.y.toDouble() })
        assertTrue(el("r").worldPoints().isEmpty(), "a shape has no points")
    }

    @Test
    fun withWorldPointsRoundTripsAndSetsExtent() {
        val world = listOf(vec(10, 20), vec(40, 0), vec(-5, 30))
        val e = el("l", "line").withWorldPoints(world)
        assertEquals(10.0, e.double("x")); assertEquals(20.0, e.double("y"))
        assertEquals(45.0, e.double("width")); assertEquals(30.0, e.double("height"))
        assertEquals(world.map { it.x.toDouble() to it.y.toDouble() }, e.worldPoints().map { it.x.toDouble() to it.y.toDouble() })
        val empty = el("l", "line")
        assertSame(empty, empty.withWorldPoints(emptyList()))
    }

    @Test
    fun aShapesBoundsAreItsFrameWithNegativeSizesNormalised() {
        val b = el("r", x = 10.0, y = 20.0, w = -4.0, h = -6.0).worldBounds()
        assertEquals(listOf(6.0, 14.0, 4.0, 6.0), listOf(b.x, b.y, b.w, b.h))
    }

    @Test
    fun aShapeMissingGeometryHasZeroBoundsAtTheOrigin() {
        val b = buildJsonObject { put("id", JsonPrimitive("t")); put("type", JsonPrimitive("text")) }.worldBounds()
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), listOf(b.x, b.y, b.w, b.h))
    }

    @Test
    fun aLinesBoundsCoverItsPoints() {
        val b = el("l", "arrow", x = 100.0, y = 100.0, points = pts(0.0 to 0.0, -20.0 to 50.0, 30.0 to 10.0)).worldBounds()
        assertEquals(listOf(80.0, 100.0, 50.0, 50.0), listOf(b.x, b.y, b.w, b.h))
    }

    @Test
    fun aLineWithNoPointsOrOnePointIsAZeroSizeRectAtItsOrigin() {
        val none = el("l", "line", x = 7.0, y = 8.0).worldBounds()
        assertEquals(listOf(7.0, 8.0, 0.0, 0.0), listOf(none.x, none.y, none.w, none.h))
        val one = el("l", "freedraw", x = 7.0, y = 8.0, points = pts(0.0 to 0.0)).worldBounds()
        assertEquals(listOf(7.0, 8.0, 0.0, 0.0), listOf(one.x, one.y, one.w, one.h))
    }

    @Test
    fun aNonFinitePointDoesNotPoisonALinesBounds() {
        val nan = listOf<JsonElement>(JsonPrimitive("NaN"), JsonPrimitive(1.0))
        val inf = listOf<JsonElement>(JsonPrimitive("Infinity"), JsonPrimitive("-Infinity"))
        val e = el("l", "line", points = pts(0.0 to 0.0) + listOf(nan, inf) + pts(10.0 to 5.0))
        val b = e.worldBounds()
        assertEquals(listOf(0.0, 0.0, 10.0, 5.0), listOf(b.x, b.y, b.w, b.h))
        assertEquals(2, e.worldPoints().size)
    }

    @Test
    fun aNonFiniteShapeFrameDoesNotPoisonBounds() {
        val b = JsonObject(el("r", x = 3.0, y = 4.0) + mapOf("x" to JsonPrimitive("NaN"), "width" to JsonPrimitive("Infinity"))).worldBounds()
        assertTrue(listOf(b.x, b.y, b.w, b.h).all { it.isFinite() }, "got $b")
    }

    @Test
    fun withWorldPointsDropsNonFinitePointsSoTheSceneStillSerializes() {
        val e = el("l", "line").withWorldPoints(listOf(vec(1.0, 2.0), vec(Double.NaN, 0.0), vec(4.0, Double.POSITIVE_INFINITY), vec(5.0, 6.0)))
        assertEquals(listOf(1.0 to 2.0, 5.0 to 6.0), e.worldPoints().map { it.x.toDouble() to it.y.toDouble() })
        NotationScene.parse(scene(e).serialize())
    }

    @Test
    fun toFLineOfAZeroSizeShapeAndAnEmptyLineDoNotThrow() {
        el("r", w = 0.0, h = 0.0).toFLine()
        el("e", "ellipse", w = 0.0, h = 0.0).toFLine()
        assertTrue(el("l", "line").toFLine().nodes.isEmpty())
    }
}
