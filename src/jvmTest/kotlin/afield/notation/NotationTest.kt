package afield

import afield.stdlib.vec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notation layer's server side: the scene model, the merge that lets code and hand edits
 * coexist, ownership of code-written marks, anchoring, and reactions. See `dev_docs/NOTATION.md`.
 */
class NotationTest {
    private lateinit var session: SessionInformation
    private lateinit var a: Box
    private lateinit var b: Box

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<notation>", documentName = "notation", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        a = box("a", Rect(100.0, 100.0, 100.0, 60.0))
        b = box("b", Rect(400.0, 100.0, 100.0, 60.0))
    }

    private fun box(id: String, frame: Rect): Box {
        val box = Box(id)
        box.inside = session
        session.boxes[id] = box
        box.frame = frame
        return box
    }

    private fun scene() = NotationStore.scene(session)

    private fun element(id: String, type: String, x: Double, y: Double, version: Long = 1, nonce: Long = 1,
                        points: List<Pair<Double, Double>>? = null, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
        buildJsonObject {
            put("id", JsonPrimitive(id)); put("type", JsonPrimitive(type))
            put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
            put("width", JsonPrimitive(10)); put("height", JsonPrimitive(10))
            put("version", JsonPrimitive(version)); put("versionNonce", JsonPrimitive(nonce))
            put("isDeleted", JsonPrimitive(false))
            if (points != null) put("points", JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
            extra.forEach { (k, v) -> put(k, v) }
        }

    private fun sceneOf(vararg elements: JsonObject): NotationScene =
        NotationScene.parse("""{"type":"excalidraw","version":2,"elements":[${elements.joinToString(",")}]}""")

    // ── the scene model ───────────────────────────────────────────────────────────────────────

    @Test
    fun aRoundTripKeepsFieldsThisCodeDoesNotKnow() {
        val text = """{"type":"excalidraw","version":2,"appState":{"x":1},"elements":[{"id":"e","type":"rectangle","x":1,"y":2,"version":3,"futureField":{"deep":[1,2]}}]}"""
        val back = NotationScene.parse(NotationScene.parse(text).serialize())
        assertEquals("""{"deep":[1,2]}""", back["e"]!!["futureField"].toString())
        assertTrue(back.serialize().contains(""""appState":{"x":1}"""), "top-level fields survive too")
    }

    @Test
    fun mergeTakesTheHigherVersionAndOnATieTheLowerNonce() {
        val current = sceneOf(element("x", "rectangle", 0.0, 0.0, version = 5), element("y", "rectangle", 0.0, 0.0, version = 2, nonce = 9))
        val incoming = sceneOf(element("x", "rectangle", 50.0, 0.0, version = 4), element("y", "rectangle", 70.0, 0.0, version = 2, nonce = 3))
        val merged = NotationScene.merge(current, incoming)
        assertEquals(0.0, merged["x"]!!.double("x"), "an older client copy must not overwrite a newer one")
        assertEquals(70.0, merged["y"]!!.double("x"), "a tie goes to the lower versionNonce, as in Excalidraw")
    }

    @Test
    fun aCodeWriteSurvivesAClientSceneSentBeforeTheClientSawIt() {
        val current = sceneOf(element("hand", "rectangle", 0.0, 0.0), element("code", "ellipse", 5.0, 5.0))
        val staleClient = sceneOf(element("hand", "rectangle", 1.0, 0.0, version = 2))
        val merged = NotationScene.merge(current, staleClient)
        assertNotNull(merged["code"], "the element only the server had must be kept")
        assertEquals(1.0, merged["hand"]!!.double("x"))
    }

    @Test
    fun changesAreAddedUpdatedAndRemovedLiveElements() {
        val before = sceneOf(element("keep", "rectangle", 0.0, 0.0), element("edit", "rectangle", 0.0, 0.0), element("gone", "rectangle", 0.0, 0.0))
        val after = sceneOf(
            element("keep", "rectangle", 0.0, 0.0),
            element("edit", "rectangle", 9.0, 0.0, version = 2),
            element("gone", "rectangle", 0.0, 0.0, version = 2, extra = mapOf("isDeleted" to JsonPrimitive(true))),
            element("new", "freedraw", 0.0, 0.0, points = listOf(0.0 to 0.0, 5.0 to 5.0)),
        )
        val change = NotationChange.between(before, after)
        assertEquals(listOf("new"), change.added.map { it.string("id") })
        assertEquals(listOf("edit"), change.updated.map { it.string("id") })
        assertEquals(listOf("gone"), change.removed.map { it.string("id") })
    }

    @Test
    fun pointsAreWorldSpaceAndBoundsFollowThem() {
        val e = element("s", "freedraw", 10.0, 20.0, points = listOf(0.0 to 0.0, 30.0 to -5.0, 10.0 to 40.0))
        assertEquals(listOf(10f to 20f, 40f to 15f, 20f to 60f), e.worldPoints().map { it.x to it.y })
        assertEquals(Rect(10.0, 15.0, 30.0, 45.0), e.worldBounds())
        val moved = e.withWorldPoints(listOf(vec(0, 0), vec(10, 10)))
        assertEquals(0.0, moved.double("x")); assertEquals(10.0, moved.double("width"))
    }

    // ── writing from code ─────────────────────────────────────────────────────────────────────

    @Test
    fun aBoxOwnsWhatItDrawsAndRerunningReplacesIt() {
        a.notation { rect(0, 0, 10, 10); rect(20, 0, 10, 10); text("hi", 0, 30) }
        assertEquals(3, a.notation.mine.size)
        assertTrue(a.notation.mine.all { it.ownerId == "a" && !it.handDrawn })

        a.notation { rect(0, 0, 10, 10) }
        assertEquals(1, a.notation.mine.size, "marks the re-run no longer makes are deleted")
        assertEquals(3, scene().elements.size, "…as tombstones, so other clients hear about the deletion")
    }

    @Test
    fun anUnchangedRerunDoesNotBumpVersions() {
        a.notation { rect(0, 0, 10, 10, name = "box") }
        val v1 = a.notation.named("box")!!.json.long("version")
        a.notation { rect(0, 0, 10, 10, name = "box") }
        assertEquals(v1, a.notation.named("box")!!.json.long("version"))
        a.notation { rect(5, 0, 10, 10, name = "box") }
        assertTrue(a.notation.named("box")!!.json.long("version")!! > v1!!)
    }

    @Test
    fun keysAreIndependentSetsAndHandDrawnMarksAreNeverTouched() {
        NotationStore.update(session) { it.withElement(element("hand", "rectangle", 0.0, 0.0)) }
        a.notation("one") { rect(0, 0, 1, 1) }
        a.notation("two") { rect(0, 0, 1, 1) }
        a.notation("one") { }
        assertEquals(1, a.notation.mine.size)
        a.notation.clear()
        assertEquals(0, a.notation.mine.size)
        assertNotNull(a.notation["hand"])
    }

    @Test
    fun editChangesAnyMarkIncludingOneDrawnByHand() {
        NotationStore.update(session) { it.withElement(element("hand", "text", 0.0, 0.0, extra = mapOf("text" to JsonPrimitive("old")))) }
        a.notation.edit(a.notation["hand"]!!) { text = "new"; stroke = "#e03131"; name = "tempo" }
        val m = a.notation["hand"]!!
        assertEquals("new", m.text); assertEquals("#e03131", m.stroke); assertEquals(m, a.notation.named("tempo"))
        a.notation.delete(m)
        assertNull(a.notation["hand"])
    }

    // ── relating marks to boxes ───────────────────────────────────────────────────────────────

    @Test
    fun marksKnowTheBoxesTheyAreOnAndArrowsTheBoxesTheyJoin() {
        a.notation {
            ellipse(120, 110, 20, 20, name = "dot")
            arrow(vec(150, 130), vec(450, 130), name = "link")
        }
        val n = a.notation
        assertEquals(a, n.named("dot")!!.box)
        assertEquals(listOf("dot"), n.over(a).mapNotNull { it.name }.filter { it == "dot" })
        val link = n.named("link")!!
        assertEquals(a, link.fromBox); assertEquals(b, link.toBox)
        assertEquals(listOf(link), n.arrowsFrom(a)); assertEquals(listOf(link), n.arrowsTo(b))
    }

    @Test
    fun anArrowBetweenBoxesFollowsThemWhenTheyMove() {
        a.notation { arrow(from = a, to = b, name = "flow") }
        val before = a.notation.named("flow")!!
        assertTrue(before.end!!.x < 400f && before.end!!.x > 390f, "the end sits just outside b's left edge")

        b.frame = Rect(400.0, 400.0, 100.0, 60.0)
        NotationAnchors.recompute(session)
        val after = a.notation.named("flow")!!
        assertEquals(b, after.toBox, "the end is on b again")
        assertTrue(after.end!!.y > 380f, "and it moved down with b: ${after.end}")
        assertEquals(a, after.fromBox)
    }

    @Test
    fun aMarkAnchoredToABoxMovesWithIt() {
        val ref = a.notation { rect(110, 110, 10, 10, name = "note") }.first()
        a.notation.anchor(ref, a)
        a.frame = Rect(300.0, 300.0, 100.0, 60.0)
        NotationAnchors.recompute(session)
        val m = a.notation.named("note")!!
        assertEquals(310.0, m.bounds.x, 0.01); assertEquals(310.0, m.bounds.y, 0.01)
    }

    @Test
    fun aHandDrawnArrowThatLandsOnBoxesIsAnchoredToThem() {
        val arrow = element("drawn", "arrow", 150.0, 130.0, points = listOf(0.0 to 0.0, 300.0 to 0.0))
        val (before, after) = NotationStore.update(session) { it.withElement(arrow) }
        NotationAnchors.autoAnchor(session, NotationChange.between(before, after))
        val e = scene()["drawn"]!!
        assertEquals("a", e.custom("fieldStart")?.asObject()?.string("box"))
        assertEquals("b", e.custom("fieldEnd")?.asObject()?.string("box"))

        b.frame = Rect(400.0, 300.0, 100.0, 60.0)
        NotationAnchors.recompute(session)
        val end = scene()["drawn"]!!.worldPoints().last()
        assertEquals(330f, end.y, 0.5f)
        assertEquals(450f, end.x, 0.5f)
    }

    // ── reactions ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun reactionsSeeWhatAPersonDrew() {
        val strokes = mutableListOf<String>()
        val texts = mutableListOf<String?>()
        a.notation.onStroke { strokes += it.id }
        a.notation.onText { texts += it.text }
        val before = scene()
        val after = before.withElements(listOf(
            element("s1", "freedraw", 0.0, 0.0, points = listOf(0.0 to 0.0, 5.0 to 5.0)),
            element("t1", "text", 0.0, 0.0, extra = mapOf("text" to JsonPrimitive("tempo 120"))),
        ))
        dispatchNotationChange(session, NotationChange.between(before, after))
        assertEquals(listOf("s1"), strokes)
        assertEquals(listOf<String?>("tempo 120"), texts)
    }

    @Test
    fun anUnreadableDrawingIsNeverWrittenOver() {
        session.root.setPropertyRaw("drawing", "{not json")
        a.notation { rect(0, 0, 10, 10) }
        assertEquals("{not json", session.root.getPropertyRaw("drawing"))
        assertTrue(a.notation.marks.isEmpty())
    }

    @Test
    fun anchoringTheSelectionUsesTheBoxUnderEachMarkAndEachArrowEnd() {
        a.notation { ellipse(120, 110, 20, 20, name = "dot"); arrow(vec(150, 130), vec(450, 130), name = "link") }
        val dot = NotationAnchors.anchorToBoxUnder(session, a.notation.named("dot")!!.json)!!
        assertEquals("a", dot.custom("fieldAnchor")?.asObject()?.string("box"))
        val link = NotationAnchors.anchorToBoxUnder(session, a.notation.named("link")!!.json)!!
        assertEquals("a", link.custom("fieldStart")?.asObject()?.string("box"))
        assertEquals("b", link.custom("fieldEnd")?.asObject()?.string("box"))
    }

    @Test
    fun geometryBecomesAnFLine() {
        a.notation { rect(0, 0, 10, 20, name = "r"); stroke(listOf(vec(0, 0), vec(3, 4), vec(6, 0)), name = "s") }
        val r = a.notation.named("r")!!.toFLine()
        assertEquals(5, r.nodes.size)
        val s = a.notation.named("s")!!
        assertEquals(MarkType.STROKE, s.kind)
        assertEquals(3, s.toFLine().nodes.size)
        assertTrue(abs(s.points[1].y - 4f) < 1e-4)
        assertFalse(s.handDrawn)
    }
}
