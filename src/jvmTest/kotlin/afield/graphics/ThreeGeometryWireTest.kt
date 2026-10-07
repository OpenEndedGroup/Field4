package afield.graphics

import afield.Box
import afield.SessionInformation
import afield.stdlib.TriMesh
import afield.stdlib.icosphereMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.graalvm.polyglot.Value
import java.nio.file.Files
import java.util.Base64
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
 * What a Three.js view sends, held to the three promises `ThreeWire.kt` makes.
 *
 * The incident behind them (2026-10-07, a house host): `styleParts` on a view of 224 high-resolution
 * panels re-encoded the whole payload as JSON text on the runloop, and with the heap nearly full that
 * took twenty minutes and every document on the host with it. So: a style change sends styles and
 * encodes nothing; drawable buffers are packed; a message over the limit fails before it is built.
 *
 * The viewer half runs here too, on GraalJS — the `wire-payload` region of `threejs_viewer.html`, fed the
 * bytes the server really sent — because styles are now applied in the browser, and a viewer that drew
 * them differently from [applyPartAppearance] would be a regression no server-side assertion could see.
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

    @Test
    fun aStyleChangeSendsOnlyTheStylesAndKeepsTheGeometryEncoding() {
        val view = view(document())
        val geometry = view.replayMessages().single()
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
        assertTrue(geometry.size > 100 * sent.single().size, "geometry ${geometry.size} B, style ${sent.single().size} B")

        // The next viewer to attach is sent the very bytes encoded before, after the styles they predate.
        val replay = view.replayMessages()
        assertEquals(2, replay.size)
        assertSame(geometry, replay[1], "the cached geometry encoding was replaced")
        val ahead = parse(replay[0])
        assertTrue(ahead["beforeGeometry"]!!.jsonPrimitive.boolean)
        assertTrue(ahead["replace"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("sphere"), ahead["parts"]!!.jsonObject.keys)

        view.styleParts(revision, mapOf("tri" to ThreePartAppearance(listOf(0.1, 0.2, 0.3))))
        assertEquals(encodes, view.geometryEncodes)
        assertEquals(setOf("tri"), parse(sent.last())["parts"]!!.jsonObject.keys, "a merge sends what changed")

        // New geometry carries the stored styles itself, so a viewer attaching after it needs nothing more.
        view.update(view.sourcePayload!!)
        assertEquals(encodes + 1, view.geometryEncodes)
        val fresh = view.replayMessages().single()
        assertEquals(setOf("sphere", "tri"), parse(fresh)["partAppearance"]!!.jsonObject.keys)
    }

    @Test
    fun drawableBuffersArePackedAndFLineArraysAreNot() {
        val view = ThreeGeometryViewState("wire-format-${UUID.randomUUID()}")
        val payload = ThreeGeometryPayload(
            uid = view.uid,
            lines = ThreeGeometryData(mapOf("position" to ThreeAttribute(3, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)))),
            drawables = ThreeSceneBuilder(48).apply { mesh(triangle, id = "tri") }.payload(view.uid).drawables,
        )
        view.update(payload)
        val message = parse(view.replayMessages().single())

        // `FLineWire.swift` decodes these as `[Float]`.
        val line = message["lines"]!!.jsonObject["attributes"]!!.jsonObject["position"]!!.jsonObject
        assertEquals(6, line["array"]!!.jsonArray.size)
        assertNull(line["base64"])

        val geometry = message["drawables"]!!.jsonArray.single().jsonObject["geometry"]!!.jsonObject
        geometry["attributes"]!!.jsonObject.forEach { (name, attribute) ->
            assertEquals(0, attribute.jsonObject["array"]!!.jsonArray.size, "attribute $name was sent as text too")
            assertNotNull(attribute.jsonObject["base64"], "attribute $name was not packed")
        }
        assertEquals("Uint16Array", geometry["index"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNotNull(geometry["index"]!!.jsonObject["base64"])

        // Every other encoding, the review revision's included, never sees the wire-only fields.
        val source = json.encodeToString(ThreeGeometryPayload.serializer(), view.sourcePayload!!)
        assertFalse("base64" in source || "partAppearance" in source, source)
    }

    @Test
    fun aNonFiniteValueNamesItsDrawableAndAttribute() {
        val broken = ThreeGeometryPayload(uid = "u", drawables = listOf(ThreeDrawable(
            ThreeGeometryData(mapOf("position" to ThreeAttribute(3, floatArrayOf(0f, Float.NaN, 0f)))),
            ThreeDrawStyle.surface(), id = "panel-7",
        )))
        val failure = assertFailsWith<IllegalArgumentException> {
            ThreeGeometryViewState("wire-nan-${UUID.randomUUID()}").update(broken)
        }
        assertTrue("drawable 'panel-7'" in failure.message!! && "'position'" in failure.message!!, failure.message!!)
    }

    @Test
    fun theViewerDrawsTheStylesAsApplyPartAppearanceDefinesThem() {
        val view = view(document(), subdivisions = 2)
        val geometry = view.replayMessages().single().decodeToString()
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
            fun run(start: Value?, messages: List<String>): Pair<Value, Value?> {
                var state = start
                var draw: Value? = null
                for (message in messages) {
                    val received = js.call("receiveWireMessage", state, js.fromJson(message))
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
            // ...which is why the replay's styles go first, flagged: the cached geometry alone is stale.
            assertDrawn(view.sourcePayload!!, run(live, listOf(replay.last())).second!!)
            // A style for a revision the viewer is not showing changes nothing.
            val stale = sent.last().replace(revision, "an-older-revision")
            assertNull(run(live, listOf(stale)).second)

            view.styleParts(revision, emptyMap(), replace = true)
            assertDrawn(view.sourcePayload!!, run(live, listOf(sent.last())).second!!)
        }
    }

    @Test
    fun packedBuffersDecodeBitForBit() {
        val awkward = floatArrayOf(0f, -0f, 0.1f, -1.2345678e-12f, Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 1e30f, 123456.79f)
        // One, two and three floats: base64 of 4, 8 and 12 bytes, which is each of the three padding cases.
        val attributes = mapOf(
            "position" to ThreeAttribute(3, awkward),
            "one" to ThreeAttribute(1, awkward.copyOf(1)),
            "two" to ThreeAttribute(1, awkward.copyOf(2)),
            "three" to ThreeAttribute(1, awkward.copyOf(3)),
        )
        val small = IntArray(9) { listOf(0, 1, 2, 255, 256, 4096, 30000, 65534, 65535)[it] }
        val large = intArrayOf(0, 65536, 70000, 16_777_217, Int.MAX_VALUE)
        val topology = ThreePartTopology(
            positions = doubleArrayOf(0.0, -0.0, 0.1, 1.0 / 3, Double.MIN_VALUE, Double.MAX_VALUE, -1e-300, 123456.789, 2.0),
            indices = intArrayOf(0, 1, 2),
            edges = listOf(listOf(0, 1), listOf(1, 2), listOf(2, 0)),
            faces = intArrayOf(0),
        )
        val payload = ThreeGeometryPayload(uid = "u", drawables = listOf(
            ThreeDrawable(ThreeGeometryData(attributes, ThreeIndex("Uint16Array", small)), ThreeDrawStyle.surface(), id = "a"),
            ThreeDrawable(ThreeGeometryData(attributes, ThreeIndex("Uint32Array", large)), ThreeDrawStyle.surface(), id = "b"),
        ), inspector = ThreeInspector(parts = mapOf("a" to topology)))
        val wire = json.encodeToString(ThreeGeometryPayload.serializer(), payload.packedForWire())

        ViewerJavaScript.Session.open(regionName = "wire-payload", exports = EXPORTS).use { js ->
            val message = js.call("unpackWirePayload", js.fromJson(wire))
            val unpacked = message.getMember("drawables")

            // The exact topology comes back as plain arrays of the very same doubles.
            val part = message.getMember("inspector").getMember("parts").getMember("a")
            assertNull(part.getMember("base64"))
            listOf("positions", "indices", "faces", "edges").forEach {
                assertEquals("Array", part.getMember(it).metaObject.metaSimpleName, "topology $it is not a plain array")
            }
            val positions = part.getMember("positions")
            assertContentEquals(topology.positions.map { it.toRawBits() }, (0 until positions.arraySize).map { positions.getArrayElement(it).asDouble().toRawBits() })
            val edges = part.getMember("edges")
            assertEquals(topology.edges, (0 until edges.arraySize).map { e -> edges.getArrayElement(e).let { pair -> (0 until pair.arraySize).map { pair.getArrayElement(it).asInt() } } })
            assertEquals(listOf(0), (0 until part.getMember("faces").arraySize).map { part.getMember("faces").getArrayElement(it).asInt() })
            payload.drawables.forEachIndexed { i, want ->
                val got = unpacked.getArrayElement(i.toLong()).getMember("geometry")
                want.geometry.attributes.forEach { (name, attribute) ->
                    val array = got.getMember("attributes").getMember(name)
                    assertNull(array.getMember("base64"), "attribute $name kept its packed text")
                    assertContentEquals(
                        attribute.array.map { it.toRawBits() },
                        floats(array.getMember("array")).map { it.toRawBits() },
                        "attribute $name",
                    )
                }
                val index = got.getMember("index").getMember("array")
                assertContentEquals(want.geometry.index!!.array.toList(), (0 until index.arraySize).map { index.getArrayElement(it).asLong().toInt() })
            }

            // The fallback decoder, which a browser without `Uint8Array.fromBase64` runs, on every length mod 3.
            for (length in 0..10) {
                val bytes = ByteArray(length) { (it * 37 + 200).toByte() }
                val decoded = js.call("wireBase64BytesPortable", Base64.getEncoder().encodeToString(bytes))
                assertContentEquals(bytes.toList(), (0 until decoded.arraySize).map { decoded.getArrayElement(it).asInt().toByte() }, "length $length")
            }
        }
    }

    @Test
    fun aMessageOverTheLimitFailsBeforeEncodingAndSaysWhichDrawablesMakeIt() {
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

        // Under the limit the same payload goes, and the guard's estimate was the size it turned out to be.
        view.payloadLimitBytes = defaultThreePayloadLimitBytes
        view.update(big)
        val sent = view.replayMessages().single().size.toLong()
        val estimate = threeWireCosts(big).sumOf { it.bytes }
        assertTrue(sent in (estimate * 0.95).toLong()..(estimate * 1.05).toLong() + 8192, "estimated $estimate bytes, sent $sent")
    }

    @Test
    fun aMessageTheEstimateUnderratedIsRefusedAfterEncoding() {
        val view = ThreeGeometryViewState("wire-measured-${UUID.randomUUID()}")
        // Decimal text has no fixed width: each of these prints as `-1.2345678E-12`, fourteen characters
        // where the estimate allows ten.
        val lines = ThreeGeometryData(mapOf("position" to ThreeAttribute(3, FloatArray(30_000) { -1.2345678e-12f })))
        val payload = ThreeGeometryPayload(uid = view.uid, lines = lines)
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
