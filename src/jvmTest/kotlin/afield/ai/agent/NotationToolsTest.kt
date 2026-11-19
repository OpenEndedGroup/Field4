package afield.ai.agent

import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import afield.ai.tools.registerBuiltInAgentTools
import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.ui.notation.NotationStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI4's drawing-layer tools: [ReadNotationRequest] summarises and filters the scene the server holds, and
 * [ViewNotationRequest] turns a mark, a box or a region into the world rectangle the capture page frames.
 * The picture itself needs a browser, so the host here records the rectangle and returns a blank image.
 */
class NotationToolsTest {
    private lateinit var info: SessionInformation
    private lateinit var synth: Box
    private lateinit var plot: Box
    private val captured = mutableListOf<Triple<Rect, Boolean, Int>>()

    private val host = object : FieldGraphHost by LiveFieldGraphHost {
        override fun <T> onOwner(info: SessionInformation, action: () -> T): T = action()
        override fun captureRegion(info: SessionInformation, world: Rect, showBoxes: Boolean, liveMarks: Int): RegionShot {
            captured += Triple(world, showBoxes, liveMarks)
            return RegionShot(true, "captured by the test host", BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), zoom = 1.5)
        }
    }

    private lateinit var tools: FieldGraphTools

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        info = SessionInformation(filename = "<notation-tools>", documentName = "notation-tools", root = root, ephemeral = true)
        root.inside = info
        info.loading = false
        thisSessionInfo.set(info)
        synth = box("synth", "Synth", Rect(100.0, 100.0, 100.0, 60.0))
        plot = box("plot", "Plot", Rect(400.0, 100.0, 100.0, 60.0))
        tools = FieldGraphTools(info, host)
        NotationStore.update(info) {
            it.withElement(element("circle", "ellipse", 120.0, 110.0, 40.0, 30.0))
                .withElement(element("label", "text", 600.0, 300.0, 80.0, 20.0, extra = mapOf("text" to JsonPrimitive("score"))))
                .withElement(element("link", "arrow", 205.0, 130.0, 0.0, 0.0, points = listOf(0.0 to 0.0, 190.0 to 0.0)))
                .withElement(element("scribble", "freedraw", 0.0, 400.0, 0.0, 0.0,
                    points = (0..99).map { i -> i.toDouble() to (i % 7).toDouble() }))
        }
    }

    @AfterTest
    fun tearDown() {
        thisSessionInfo.remove()
    }

    private fun box(id: String, name: String, frame: Rect): Box = Box(id).also {
        it.name = name
        it.inside = info
        it.frame = frame
        info.boxes[id] = it
    }

    private fun element(id: String, type: String, x: Double, y: Double, w: Double, h: Double,
                        points: List<Pair<Double, Double>>? = null, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(id)); put("type", JsonPrimitive(type))
            put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
            put("width", JsonPrimitive(w)); put("height", JsonPrimitive(h))
            put("version", JsonPrimitive(1)); put("versionNonce", JsonPrimitive(1))
            put("isDeleted", JsonPrimitive(false))
            put("strokeColor", JsonPrimitive("#e03131"))
            if (points != null) put("points", JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
            extra.forEach { (k, v) -> put(k, v) }
        }

    @Test
    fun readsEveryMarkWithBoundsAndTheBoxesItTouches() {
        val r = tools.readNotation(ReadNotationRequest())
        assertTrue(r.success)
        assertEquals(4, r.total)
        assertEquals(listOf("circle", "label", "link", "scribble"), r.marks.map { it.id })
        val circle = r.marks.first { it.id == "circle" }
        assertEquals("ellipse", circle.kind)
        assertEquals(listOf(120.0, 110.0, 40.0, 30.0), circle.bounds)
        assertEquals("Synth (synth)", circle.over)
        assertTrue(circle.handDrawn)
        val link = r.marks.first { it.id == "link" }
        assertEquals("Synth (synth)", link.from)
        assertEquals("Plot (plot)", link.to)
        assertEquals(2, link.pointCount)
        assertNull(link.points, "points only on request")
        assertEquals("score", r.marks.first { it.id == "label" }.label)
        assertEquals(listOf(0.0, 110.0, 680.0, 296.0), r.drawingBounds)
    }

    @Test
    fun filtersCombine() {
        assertEquals(listOf("label"), tools.readNotation(ReadNotationRequest(name = "SCORE")).marks.map { it.id })
        assertEquals(listOf("circle"), tools.readNotation(ReadNotationRequest(over = "Synth")).marks.map { it.id })
        assertEquals(listOf("link", "scribble"), tools.readNotation(ReadNotationRequest(kinds = listOf("arrows", "stroke"))).marks.map { it.id })
        assertEquals(listOf("scribble"), tools.readNotation(ReadNotationRequest(region = listOf(-10.0, 390.0, 50.0, 50.0))).marks.map { it.id })
        val none = tools.readNotation(ReadNotationRequest(name = "nothing"))
        assertTrue(none.success)
        assertEquals(0, none.matched)
        assertTrue("4 mark" in none.message)
        assertFailsWith<IllegalArgumentException> { tools.readNotation(ReadNotationRequest(kinds = listOf("blob"))) }
        assertFailsWith<IllegalArgumentException> { tools.readNotation(ReadNotationRequest(over = "Nobody")) }
    }

    @Test
    fun pointsAreThinnedToMaxPoints() {
        val scribble = tools.readNotation(ReadNotationRequest(name = "", kinds = listOf("stroke"), includePoints = true, maxPoints = 10)).marks.single()
        assertEquals(100, scribble.pointCount)
        assertEquals(10, scribble.points!!.size)
        assertEquals(listOf(0.0, 400.0), scribble.points.first())
        assertEquals(listOf(99.0, 401.0), scribble.points.last())
    }

    @Test
    fun viewFramesAMarkByLabelWithPadding() {
        val v = tools.viewNotation(ViewNotationRequest(mark = "score", padding = 10.0, note = "is it legible?"))
        assertTrue(v.success, v.message)
        val (rect, showBoxes, live) = captured.single()
        assertEquals(Rect(590.0, 290.0, 100.0, 40.0), rect)
        assertTrue(showBoxes)
        assertEquals(4, live)
        assertEquals(listOf("label"), v.marks)
        assertEquals(1.5, v.zoom)
        assertEquals(1, v.toolImages.size)
        assertTrue("is it legible?" in v.toolImages.single().caption)
    }

    @Test
    fun viewFramesABoxWithTheMarksOverItAndDefaultsToTheWholeDrawing() {
        tools.viewNotation(ViewNotationRequest(box = "Synth", padding = 0.0, showBoxes = false))
        assertEquals(Rect(100.0, 100.0, 100.0, 60.0) to false, captured.last().let { it.first to it.second })
        tools.viewNotation(ViewNotationRequest(padding = 0.0))
        assertEquals(Rect(0.0, 110.0, 680.0, 296.0), captured.last().first)
    }

    @Test
    fun viewRefusesUnknownMarksAndConflictingFrames() {
        assertFailsWith<IllegalArgumentException> { tools.viewNotation(ViewNotationRequest(mark = "missing")) }
        assertFailsWith<IllegalArgumentException> { tools.viewNotation(ViewNotationRequest(mark = "score", box = "Synth")) }
        assertFailsWith<IllegalArgumentException> { tools.viewNotation(ViewNotationRequest(region = listOf(0.0, 0.0, -1.0, 5.0))) }
        assertTrue(captured.isEmpty())
    }

    @Test
    fun anEmptyDrawingReadsAsEmptyAndCannotBeViewed() {
        NotationStore.update(info) { s ->
            s.withElements(s.live.map { e -> JsonObject(e + mapOf("isDeleted" to JsonPrimitive(true), "version" to JsonPrimitive(2))) })
        }
        val r = tools.readNotation(ReadNotationRequest())
        assertTrue(r.success)
        assertEquals(0, r.total)
        assertTrue("empty" in r.message)
        assertFailsWith<IllegalArgumentException> { tools.viewNotation(ViewNotationRequest()) }
    }

    @Test
    fun bothToolsAreRegisteredAndTheViewNeedsSight() {
        val registry = ToolRegistry().also(::registerBuiltInAgentTools)
        val sees = ToolExecutionContext(fieldGraph = tools)
        val blind = ToolExecutionContext(fieldGraph = FieldGraphTools(info, host, seesOutput = false))
        assertTrue(registry.isAvailable(sees, "ReadNotationRequest"))
        assertTrue(registry.isAvailable(sees, "ViewNotationRequest"))
        assertTrue(registry.isAvailable(blind, "ReadNotationRequest"))
        assertFalse(registry.isAvailable(blind, "ViewNotationRequest"))
        assertTrue(notationToolNames.all { it in fieldGraphToolNames })
    }
}
