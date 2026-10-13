package afield.graphics.three

import afield.geometry.path.FLine
import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.then
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

import afield.geometry.path.start
import afield.ui.visualization.lines

import afield.document.graph.Box
import afield.app.server.SessionInformation
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.icosphereMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.graalvm.polyglot.Value
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a Three.js view sends, held to the promises `ThreeWire.kt` makes.
 *
 * The incident behind them (2026-10-07, a house host): `styleParts` on a view of 224 high-resolution
 * panels re-encoded the whole payload as JSON text on the runloop, and with the heap nearly full that
 * took twenty minutes and every document on the host with it. So: a style change sends styles and
 * encodes nothing; geometry is a small description plus binary buffers the viewer fetches; geometry
 * over the limit fails before anything is built; a native client still gets JSON it can decode.
 *
 * The viewer half runs here too, on GraalJS — the `wire-payload` region of `threejs_viewer.html`, fed the
 * messages and buffer bytes the server really produced — because styles are applied in the browser, and
 * a viewer that bound or drew them differently would be a regression no server-side assertion could see.
 */
class ThreeGeometryWireTest {
    private val json = Json { encodeDefaults = true }

    private fun document(): Box {
        val box = Box("wire")
        val info = SessionInformation(Files.createTempDirectory("three-wire-").toString(), documentName = "wire", root = box, ephemeral = true)
        box.inside = info; info.boxes[box.id] = box
        return box
    }

    private val triangle get() = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))

    private fun view(box: Box, subdivisions: Int = 4): ThreeGeometryViewState = box.three(name = "wire") {
        mesh(icosphereMesh(subdivisions), id = "sphere", pickable = true)
        mesh(triangle, id = "tri", label = "Triangle")
    }

    private fun parse(message: ByteArray): JsonObject = Json.parseToJsonElement(message.decodeToString()).jsonObject
    private fun generation(description: JsonObject) = description["buffers"]!!.jsonObject["generation"]!!.jsonPrimitive.content

    @Test
    fun aStyleChangeSendsOnlyTheStylesAndKeepsTheGeometryEncoding() {
        val view = view(document())
        val geometry = view.replayMessages().single()
        val buffers = view.buffers(generation(parse(geometry)))!!
        val revision = threeReviewRevision(view.sourcePayload!!)
        val encodes = view.geometryEncodes
        val sent = mutableListOf<ByteArray>()
        view.onBroadcast = { sent += it }

        val amber = ThreePartAppearance(listOf(1.0, 0.72, 0.18), unlit = true, xrayOpacity = 1.0)
        view.styleParts(revision, mapOf("sphere" to amber))

        assertEquals(encodes, view.geometryEncodes, "styling a part encoded the geometry again")
        val delta = parse(sent.single())
        assertEquals("three/appearance", delta["type"]!!.jsonPrimitive.content)
        assertEquals(revision, delta["revision"]!!.jsonPrimitive.content)
        assertFalse(delta["replace"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("sphere"), delta["parts"]!!.jsonObject.keys)
        assertNull(delta["drawables"], "the style message carried geometry")
        assertTrue(sent.single().size < 400, "a one-part style message is ${sent.single().size} bytes")
        assertTrue(buffers.bytes > 100 * sent.single().size, "geometry ${buffers.bytes} B, style ${sent.single().size} B")

        // The next viewer to attach is sent the very description encoded before, after the styles it
        // predates, and its buffers are the same ones.
        val replay = view.replayMessages()
        assertEquals(2, replay.size)
        assertSame(geometry, replay[1], "the cached description was replaced")
        assertSame(buffers, view.buffers(generation(parse(replay[1]))))
        val ahead = parse(replay[0])
        assertTrue(ahead["beforeGeometry"]!!.jsonPrimitive.boolean)
        assertTrue(ahead["replace"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("sphere"), ahead["parts"]!!.jsonObject.keys)

        view.styleParts(revision, mapOf("tri" to ThreePartAppearance(listOf(0.1, 0.2, 0.3))))
        assertEquals(encodes, view.geometryEncodes)
        assertEquals(setOf("tri"), parse(sent.last())["parts"]!!.jsonObject.keys, "a merge sends what changed")

        // New geometry carries the stored styles itself, so a viewer attaching after it needs nothing more,
        // and the old generation's buffers are gone.
        view.update(view.sourcePayload!!)
        assertEquals(encodes + 1, view.geometryEncodes)
        val fresh = parse(view.replayMessages().single())
        assertEquals(setOf("sphere", "tri"), fresh["partAppearance"]!!.jsonObject.keys)
        assertNull(view.buffers(generation(parse(geometry))), "a superseded generation is still served")
        assertNotNull(view.buffers(generation(fresh)))
    }

    @Test
    fun geometryIsADescriptionPlusBuffersAndNativeClientsGetJson() {
        val view = ThreeGeometryViewState("wire-format-${UUID.randomUUID()}")
        val payload = ThreeGeometryPayload(
            uid = view.uid,
            lines = ThreeGeometryData(mapOf("position" to ThreeAttribute(3, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)))),
            drawables = ThreeSceneBuilder(48).apply { mesh(icosphereMesh(3), id = "sphere") }.payload(view.uid).drawables,
        )
        view.update(payload)
        val bytes = view.replayMessages().single()
        val message = parse(bytes)

        val table = message["buffers"]!!.jsonObject
        val entries = table["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(view.buffers(generation(message))!!.bytes, table["bytes"]!!.jsonPrimitive.long)
        entries.forEach { assertEquals(0L, it["offset"]!!.jsonPrimitive.long % 8, "entry $it is not 8-byte aligned") }
        assertTrue(bytes.size < 4096, "the description is ${bytes.size} bytes for ${table["bytes"]} bytes of geometry")

        fun assertReferenced(attribute: JsonObject, what: String) {
            assertEquals(0, attribute["array"]!!.jsonArray.size, "$what was sent as JSON too")
            assertNotNull(attribute["buffer"], "$what has no buffer")
        }
        assertReferenced(message["lines"]!!.jsonObject["attributes"]!!.jsonObject["position"]!!.jsonObject, "FLine lines")
        val geometry = message["drawables"]!!.jsonArray.single().jsonObject["geometry"]!!.jsonObject
        geometry["attributes"]!!.jsonObject.forEach { (name, attribute) -> assertReferenced(attribute.jsonObject, "attribute $name") }
        assertNotNull(geometry["index"]!!.jsonObject["buffer"])

        // `FLineWire.swift` decodes the FLine arrays as `[Float]`, and never reads drawables.
        val native = parse(view.nativeMessage()!!)
        assertEquals(6, native["lines"]!!.jsonObject["attributes"]!!.jsonObject["position"]!!.jsonObject["array"]!!.jsonArray.size)
        assertNull(native["drawables"])
        assertNull(native["buffers"])
        assertFalse("\"buffer\"" in view.nativeMessage()!!.decodeToString())

        // Every other encoding, the review revision's included, never sees the wire-only fields.
        val source = json.encodeToString(ThreeGeometryPayload.serializer(), view.sourcePayload!!)
        assertFalse("\"buffer" in source || "partAppearance" in source, source)
    }

    @Test
    fun aNativeClientIsSentNothingRatherThanJsonOverTheLimit() {
        val view = ThreeGeometryViewState("wire-native-limit-${UUID.randomUUID()}")
        // 120 kB as buffers, about 330 kB as decimal text.
        val payload = ThreeGeometryPayload(uid = view.uid,
            lines = ThreeGeometryData(mapOf("position" to ThreeAttribute(3, FloatArray(30_000) { it * 0.001f }))))
        view.payloadLimitBytes = 200_000
        view.update(payload)
        assertNotNull(view.buffers(generation(parse(view.replayMessages().single()))))
        assertNull(view.nativeMessage())
    }

    @Test
    fun aNonFiniteValueNamesItsPartAndAttribute() {
        val broken = ThreeGeometryPayload(uid = "u", drawables = listOf(ThreeDrawable(
            ThreeGeometryData(mapOf("position" to ThreeAttribute(3, floatArrayOf(0f, Float.NaN, 0f)))),
            ThreeDrawStyle.surface(), id = "panel-7",
        )))
        val failure = assertFailsWith<IllegalArgumentException> {
            ThreeGeometryViewState("wire-nan-${UUID.randomUUID()}").update(broken)
        }
        assertTrue("drawable 'panel-7'" in failure.message!! && "'position'" in failure.message!!, failure.message!!)

        val line = ThreeGeometryPayload(uid = "u", lines = ThreeGeometryData(mapOf("position" to
            ThreeAttribute(3, floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY, 1f, 1f, 1f)))))
        val lineFailure = assertFailsWith<IllegalArgumentException> {
            ThreeGeometryViewState("wire-inf-${UUID.randomUUID()}").update(line)
        }
        assertTrue("FLine lines" in lineFailure.message!!, lineFailure.message!!)
    }

    @Test
    fun theViewerDrawsTheStylesAsApplyPartAppearanceDefinesThem() {
        val view = view(document(), subdivisions = 2)
        val geometry = view.replayMessages().single().decodeToString()
        val bytes = view.buffers(generation(Json.parseToJsonElement(geometry).jsonObject))!!.toByteArray()
        val revision = threeReviewRevision(view.sourcePayload!!)
        val sent = mutableListOf<String>()
        view.onBroadcast = { sent += it.decodeToString() }
        val sphere = ThreePartAppearance(listOf(1.0, 0.72, 0.18), unlit = true, xrayOpacity = 1.0)
        val tri = ThreePartAppearance(listOf(0.1, 0.2, 0.3))
        view.styleParts(revision, mapOf("sphere" to sphere))
        view.styleParts(revision, mapOf("tri" to tri))
        val styled = applyPartAppearance(view.sourcePayload!!, mapOf("sphere" to sphere, "tri" to tri))
        val replay = view.replayMessages().map { it.decodeToString() }

        ViewerJavaScript.Session.open(regionName = "wire-payload", exports = EXPORTS).use { js ->
            // What `wirePump` does with each message, less the fetch.
            fun run(start: Value?, messages: List<String>): Pair<Value, Value?> {
                var state = start
                var draw: Value? = null
                for (text in messages) {
                    val message = js.fromJson(text)
                    if (message.getMember("buffers")?.isNull == false) js.call("bindWireBuffers", message, js.arrayBuffer(bytes))
                    val received = js.call("receiveWireMessage", state, message)
                    state = received.getMember("state")
                    received.getMember("draw").takeUnless { it.isNull }?.let { draw = it }
                }
                return state!! to draw
            }

            // A viewer that was watching: the geometry, then each change as it happened.
            val (live, drawn) = run(null, listOf(geometry) + sent)
            assertDrawn(styled, drawn!!)
            // One that attaches now.
            assertDrawn(styled, run(null, replay).second!!)
            // One that reconnects, already holding the geometry and the styles.
            assertDrawn(styled, run(live, replay).second!!)
            // ...which is why the replay's styles go first, flagged: the cached description alone is stale.
            assertDrawn(view.sourcePayload!!, run(live, listOf(replay.last())).second!!)
            // A style for a revision the viewer is not showing changes nothing.
            val stale = sent.last().replace(revision, "an-older-revision")
            assertNull(run(live, listOf(stale)).second)

            view.styleParts(revision, emptyMap(), replace = true)
            assertDrawn(view.sourcePayload!!, run(live, listOf(sent.last())).second!!)
        }
    }

    @Test
    fun buffersBindBitForBit() {
        val awkward = floatArrayOf(0f, -0f, 0.1f, -1.2345678e-12f, Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 1e30f, 123456.79f)
        val attributes = mapOf(
            "position" to ThreeAttribute(3, awkward),
            "one" to ThreeAttribute(1, awkward.copyOf(1)),
            "two" to ThreeAttribute(1, awkward.copyOf(2)),
        )
        val small = intArrayOf(0, 1, 2, 255, 256, 4096, 30000, 65534, 65535)
        val large = intArrayOf(0, 65536, 70000, 16_777_217, Int.MAX_VALUE)
        // A Float64 entry after an odd number of 2- and 1-byte elements: it must still land on a multiple of 8.
        val topology = ThreePartTopology(
            positions = doubleArrayOf(0.0, -0.0, 0.1, 1.0 / 3, Double.MIN_VALUE, Double.MAX_VALUE, -1e-300, 123456.789, 2.0),
            indices = intArrayOf(0, 1, 2),
            edges = listOf(listOf(0, 1), listOf(1, 2), listOf(2, 0)),
            faces = intArrayOf(0),
        )
        val payload = ThreeGeometryPayload(
            uid = "u",
            lines = ThreeGeometryData(attributes),
            interactive = listOf(ThreeInteractiveObject(callbackId = "", fills = ThreeGeometryData(attributes, ThreeIndex("Uint16Array", small)), lineIndex = 3)),
            drawables = listOf(
                ThreeDrawable(ThreeGeometryData(attributes, ThreeIndex("Uint8Array", intArrayOf(0, 1, 2, 255, 7))), ThreeDrawStyle.surface(), id = "a"),
                ThreeDrawable(ThreeGeometryData(attributes, ThreeIndex("Uint32Array", large)), ThreeDrawStyle.surface(), id = "b"),
            ),
            inspector = ThreeInspector(parts = mapOf("a" to topology)),
        )
        val split = payload.splitForWire("g")
        val bytes = split.buffers!!.toByteArray()
        assertEquals(split.buffers.bytes, bytes.size.toLong())
        val wire = json.encodeToString(ThreeGeometryPayload.serializer(), split.description)

        ViewerJavaScript.Session.open(regionName = "wire-payload", exports = EXPORTS).use { js ->
            val message = js.call("bindWireBuffers", js.fromJson(wire), js.arrayBuffer(bytes))
            assertNull(message.getMember("buffers"), "the buffer table outlived binding")

            fun assertBound(want: ThreeGeometryData, got: Value, what: String) {
                want.attributes.forEach { (name, attribute) ->
                    val spec = got.getMember("attributes").getMember(name)
                    assertNull(spec.getMember("buffer"), "$what attribute $name kept its reference")
                    assertContentEquals(attribute.array.map { it.toRawBits() }, floats(spec.getMember("array")).map { it.toRawBits() }, "$what attribute $name")
                }
                want.index?.let { index ->
                    val array = got.getMember("index").getMember("array")
                    assertEquals(index.type, array.metaObject.metaSimpleName, "$what index type")
                    assertContentEquals(index.array.toList(), (0 until array.arraySize).map { array.getArrayElement(it).asLong().toInt() }, "$what index")
                }
            }
            assertBound(payload.lines!!, message.getMember("lines"), "FLine lines")
            assertBound(payload.interactive.single().fills!!, message.getMember("interactive").getArrayElement(0).getMember("fills"), "interactive fills")
            payload.drawables.forEachIndexed { i, want ->
                assertBound(want.geometry, message.getMember("drawables").getArrayElement(i.toLong()).getMember("geometry"), "drawable ${want.id}")
            }

            // The exact topology comes back as plain arrays of the very same doubles.
            val part = message.getMember("inspector").getMember("parts").getMember("a")
            assertNull(part.getMember("buffers"))
            listOf("positions", "indices", "faces", "edges").forEach {
                assertEquals("Array", part.getMember(it).metaObject.metaSimpleName, "topology $it is not a plain array")
            }
            val positions = part.getMember("positions")
            assertContentEquals(topology.positions.map { it.toRawBits() }, (0 until positions.arraySize).map { positions.getArrayElement(it).asDouble().toRawBits() })
            val edges = part.getMember("edges")
            assertEquals(topology.edges, (0 until edges.arraySize).map { e -> edges.getArrayElement(e).let { pair -> (0 until pair.arraySize).map { pair.getArrayElement(it).asInt() } } })

            // A response that is not the one the description describes is refused, not misread.
            assertFailsWith<org.graalvm.polyglot.PolyglotException> {
                js.call("bindWireBuffers", js.fromJson(wire), js.arrayBuffer(bytes.copyOf(bytes.size - 8)))
            }
        }
    }

    @Test
    fun aLaterGeometryMessageMakesEverythingQueuedBeforeItMoot() {
        ViewerJavaScript.Session.open(regionName = "wire-payload", exports = EXPORTS).use { js ->
            val geometry = { n: Int -> """{"type":"three/geometry","revision":"r$n"}""" }
            val style = { n: Int -> """{"type":"three/appearance","revision":"r$n","replace":false,"parts":{}}""" }
            val replayStyle = """{"type":"three/appearance","revision":"r2","replace":true,"parts":{},"beforeGeometry":true}"""
            fun compact(state: String, vararg queue: String): Pair<Value, List<String>> {
                val result = js.call("wireCompactQueue", js.fromJson(state), js.fromJson(queue.joinToString(",", "[", "]")))
                val kept = result.getMember("queue")
                return result.getMember("state") to (0 until kept.arraySize).map { i ->
                    val m = kept.getArrayElement(i)
                    m.getMember("type").asString() + ":" + m.getMember("revision").asString() + (if (m.getMember("beforeGeometry")?.isNull == false) "*" else "")
                }
            }
            val holding = """{"base":null,"parts":{},"nextParts":{"revision":"r1","parts":{}}}"""

            // The newest geometry, the replay styles that belong to it, and what follows it.
            val (state, queue) = compact(holding, geometry(1), style(1), replayStyle, geometry(2), style(2))
            assertEquals(listOf("three/appearance:r2*", "three/geometry:r2", "three/appearance:r2"), queue)
            assertTrue(state.getMember("nextParts").isNull, "a dropped geometry message kept its replay styles")

            // A style for the base the geometry replaces is moot too, but no geometry was dropped, so the
            // held replay styles stay.
            val (kept, rest) = compact(holding, style(0), geometry(1))
            assertEquals(listOf("three/geometry:r1"), rest)
            assertFalse(kept.getMember("nextParts").isNull)
            // Nothing before the geometry, or no geometry at all: nothing is dropped.
            assertEquals(listOf("three/geometry:r1", "three/appearance:r1"), compact(holding, geometry(1), style(1)).second)
            assertEquals(listOf("three/appearance:r1", "three/appearance:r2"), compact(holding, style(1), style(2)).second)
        }
    }

    @Test
    fun geometryOverTheLimitFailsBeforeEncodingAndSaysWhichDrawablesMakeIt() {
        val view = view(document(), subdivisions = 1)
        val before = view.sourcePayload
        val geometry = view.replayMessages().single()
        val encodes = view.geometryEncodes
        val big = ThreeSceneBuilder(48).apply {
            mesh(icosphereMesh(5), id = "panel-big", label = "Big panel")
            mesh(icosphereMesh(1), id = "panel-small")
        }.payload(view.uid)

        view.payloadLimitBytes = 256L * 1024
        val failure = assertFailsWith<ThreePayloadTooLargeException> { view.update(big) }
        assertEquals("drawable 'panel-big' (Big panel)", failure.largest.first().first)
        assertTrue(failure.bytes > failure.limitBytes)
        listOf("drawable 'panel-big' (Big panel)", " MB", "estimated before encoding", "AFIELD_THREE_PAYLOAD_LIMIT_MB").forEach {
            assertTrue(it in failure.message!!, "the failure does not say `$it`: ${failure.message}")
        }
        assertEquals(encodes, view.geometryEncodes, "the guard encoded the payload it refused")
        assertSame(before, view.sourcePayload, "a refused payload replaced the view's geometry")
        assertSame(geometry, view.replayMessages().single())

        // Under the limit the same payload goes, and the estimate was what it cost.
        view.payloadLimitBytes = defaultThreePayloadLimitBytes
        view.update(big)
        val description = view.replayMessages().single()
        val sent = description.size + view.buffers(generation(parse(description)))!!.bytes
        val estimate = threeWireCosts(big).sumOf { it.bytes }
        assertTrue(sent in estimate..estimate + 16_384, "estimated $estimate bytes, sent $sent")
    }

    @Test
    fun aDescriptionTheEstimateUnderratedIsRefusedAfterEncoding() {
        val view = ThreeGeometryViewState("wire-measured-${UUID.randomUUID()}")
        // An editable scene's nodes stay JSON, and decimal text has no fixed width: each of these prints
        // as `-1.2345678E-12`, so a cubic node is about twice what the estimate allows it.
        val x = -1.2345678e-12f
        val nodes = List(3000) { ThreeEditableNode(it, "cubic", to = floatArrayOf(x, x, x), c1 = floatArrayOf(x, x, x), c2 = floatArrayOf(x, x, x)) }
        val scene = ThreeEditableScene("", listOf(ThreeEditableLine(0, floatArrayOf(1f, 1f, 1f, 1f), 1f, true, false, false, 1f, nodes = nodes)))
        val payload = ThreeGeometryPayload(uid = view.uid, scene = scene, editableEnabled = true)
        view.payloadLimitBytes = threeWireCosts(payload).sumOf { it.bytes } + 1024
        val failure = assertFailsWith<ThreePayloadTooLargeException> { view.update(payload) }
        assertTrue("measured after encoding" in failure.message!!, failure.message!!)
        assertEquals(1, view.geometryEncodes)
        assertNull(view.sourcePayload)
        assertTrue(view.replayMessages().isEmpty())
    }

    private fun floats(array: Value): FloatArray = FloatArray(array.arraySize.toInt()) { array.getArrayElement(it.toLong()).asDouble().toFloat() }

    /** The viewer drew [expected]'s drawables: every attribute, bit for bit, and the two styled fields. */
    private fun assertDrawn(expected: ThreeGeometryPayload, draw: Value) {
        val drawables = draw.getMember("drawables")
        assertEquals(expected.drawables.size.toLong(), drawables.arraySize)
        expected.drawables.forEachIndexed { i, want ->
            val got = drawables.getArrayElement(i.toLong())
            val attributes = got.getMember("geometry").getMember("attributes")
            assertEquals(want.geometry.attributes.keys, attributes.memberKeys, "drawable ${want.id}")
            want.geometry.attributes.forEach { (name, attribute) ->
                assertContentEquals(attribute.array, floats(attributes.getMember(name).getMember("array")), "drawable ${want.id} attribute $name")
            }
            val style = got.getMember("style")
            assertEquals(want.style.unlit, style.getMember("unlit").asBoolean(), "drawable ${want.id} unlit")
            val xray = style.getMember("xrayOpacity")?.takeUnless { it.isNull }?.asDouble()?.toFloat()
            assertEquals(want.style.xrayOpacity, xray, "drawable ${want.id} x-ray opacity")
        }
    }

    private companion object {
        val EXPORTS = ViewerJavaScript.KNOWN_REGIONS.getValue("wire-payload")
    }
}
