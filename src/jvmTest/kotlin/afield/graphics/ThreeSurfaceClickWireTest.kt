package afield.graphics

import afield.stdlib.boxMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.descriptors.elementNames
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin end of the click loop, against **JSON a real browser really posted**.
 *
 * ### Why a fixture rather than a hand-written string
 *
 * The three files under `src/jvmTest/resources/threeSurfaceClick/` are not written by hand and are not
 * a model of what the viewer sends. They are the exact bytes the shipped `threejs_viewer.html` put on
 * the websocket, captured by patching `WebSocket.prototype.send` inside the iframe of a real Field on
 * port 9491 while real `click` events went through `renderer.domElement`'s own listener — one for each
 * of the three element kinds. Nothing about the message was constructed here.
 *
 * That matters because of what it can catch, which nothing else in this repo can:
 *
 *  * **the viewer sends a field Kotlin does not know.** Production decodes with
 *    `ignoreUnknownKeys = true`, deliberately, so the envelope's own keys can be dropped — which means
 *    a genuinely new field is also dropped, silently. [everyFieldTheViewerSendsIsDeclared] strips the
 *    three known envelope keys and then decodes **strictly**, so a fourth unknown key is a failure.
 *  * **Kotlin declares a field the viewer never sends.** Every such field silently takes its default
 *    and looks like data. [everyFieldKotlinDeclaresIsActuallySent] walks
 *    `ThreeSurfaceClick.serializer().descriptor` and requires each name to be present in all three
 *    fixtures.
 *
 * Those are the two halves of the drift that bit this viewer on 2026-08-11, when three copies of the
 * interactive entry existed and the one production called was missing three fields. It failed silently
 * for a day.
 *
 * ### What is still not covered, said once
 *
 * The ktor plumbing between the socket and `installThreeJsHandler` — `rawHandlers`, `thisSession`, the
 * `GlobalScope.launch` — is not exercised here; a session is not constructible in a unit test. What is
 * exercised is every line of the handler's body that does anything: the same
 * `Json.parseToJsonElement(...).jsonObject` and the same `threeJson.decodeFromJsonElement`, then
 * `toHit()`, then the real callback registry that `Box.three { mesh(onClick = …) }` writes to.
 */
class ThreeSurfaceClickWireTest {

    /** The production encoder's configuration, from `ThreeJsViewer.kt`. */
    private val threeJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The envelope keys `installThreeJsHandler` reads and `ThreeSurfaceClick` deliberately ignores. */
    private val envelopeKeys = setOf("channel", "debug_uid", "type")

    private val kinds = listOf("vertex", "edge", "triangle")

    private fun fixtureText(kind: String): String {
        val stream = javaClass.getResourceAsStream("/threeSurfaceClick/$kind.json")
        return assertNotNull(stream, "the captured $kind wire message is missing").reader().readText()
    }

    private fun fixture(kind: String): JsonObject = Json.parseToJsonElement(fixtureText(kind)).jsonObject

    /** Exactly the two calls `installThreeJsHandler` makes for a `three-surface-click`. */
    private fun decode(kind: String): ThreeSurfaceClick =
        threeJson.decodeFromJsonElement(fixture(kind))

    /**
     * The slab the fixtures were captured against, lowered exactly as the demo lowers it.
     *
     * `boxMesh` has no normals, so `toThreeDrawable` runs `shadedNormals(35°)`, and every edge of a box
     * is above the 35° crease angle — so all eight corners split, once per face they touch. **8 vertices
     * become 24**, six faces of four. That is why the captured `vertexIndex` is 18 and not 6, and it is
     * the single most likely place for an off-by-one to hide: a hit's vertex index is only useful if it
     * indexes the buffer the viewer was actually given, which is this one and not the source mesh.
     */
    private val slab = boxMesh(1200.0, 800.0, 60.0).toThreeDrawable()

    private fun slabVertex(index: Int): List<Double> {
        val p = assertNotNull(slab.geometry.attributes["position"]).array
        return listOf(p[index * 3].toDouble(), p[index * 3 + 1].toDouble(), p[index * 3 + 2].toDouble())
    }

    // ------------------------------------------------------------------ the fixtures are what they claim

    /**
     * The fixtures came off the wire of the shipped viewer, driving the committed demo.
     *
     * Ties three artifacts together: if the demo's `id`/`label` change, or the viewer stops posting the
     * `__threejs__` envelope, these stop being a capture of anything and say so.
     */
    @Test
    fun theFixturesAreRealMessagesFromTheCommittedDemo() {
        kinds.forEach { kind ->
            val o = fixture(kind)
            assertEquals("__threejs__", o.getValue("channel").jsonPrimitive.content)
            assertEquals("three-surface-click", o.getValue("type").jsonPrimitive.content)
            assertEquals(kind, o.getValue("kind").jsonPrimitive.content)
            assertEquals("slab", o.getValue("id").jsonPrimitive.content, "the demo names its solid `slab`")
            assertEquals("slab", o.getValue("label").jsonPrimitive.content)
            assertTrue(
                o.getValue("debug_uid").jsonPrimitive.content.startsWith("mesh-pick-clearance"),
                "the capture should come from the committed demo document's box",
            )
            assertTrue(
                o.getValue("callbackId").jsonPrimitive.content.startsWith("three-surface-"),
                "a surface callback id must be visibly distinct from an FLine's",
            )
        }
        // …and the demo really does declare that id, so this is not two copies of my own typo.
        val demo = java.io.File("public/docs/assets/three_mesh_pick_demo/metadata.edn")
        assertTrue(demo.isFile, "the demo artifact is missing at ${demo.path}")
        assertTrue(demo.readText().contains("""id = \"slab\""""), "the demo no longer names its solid `slab`")
    }

    // ------------------------------------------------------------------ the two halves of wire drift

    /**
     * The viewer sends nothing `ThreeSurfaceClick` cannot name.
     *
     * Production sets `ignoreUnknownKeys = true` on purpose, so this cannot be checked on the
     * production path — an unknown key is dropped without a word. Strip the three envelope keys the
     * handler is entitled to ignore, then decode strictly: anything left over is a field the viewer
     * gained and Kotlin did not.
     */
    @Test
    fun everyFieldTheViewerSendsIsDeclared() {
        val strict = Json { ignoreUnknownKeys = false }
        kinds.forEach { kind ->
            val trimmed = JsonObject(fixture(kind).filterKeys { it !in envelopeKeys })
            // If this throws, read the message: it names the key.
            val decoded = strict.decodeFromJsonElement<ThreeSurfaceClick>(trimmed)
            assertEquals(kind, decoded.kind)
        }
    }

    /** …and the guard is not vacuous: an extra key really does fail a strict decode. */
    @Test
    fun theStrictDecodeGuardWouldCatchANewField() {
        val strict = Json { ignoreUnknownKeys = false }
        val withExtra = JsonObject(
            fixture("edge").filterKeys { it !in envelopeKeys } +
                ("materialIndex" to kotlinx.serialization.json.JsonPrimitive(3))
        )
        assertFailsWith<Exception> { strict.decodeFromJsonElement<ThreeSurfaceClick>(withExtra) }
    }

    /**
     * And `ThreeSurfaceClick` declares nothing the viewer never sends.
     *
     * The quieter half. Every field is defaulted — deliberately, so an older viewer still decodes — and
     * a field that is *never* sent therefore looks like real data forever. Checked against all three
     * fixtures, because a field could plausibly be sent for one kind and not another.
     */
    @Test
    fun everyFieldKotlinDeclaresIsActuallySent() {
        val declared = ThreeSurfaceClick.serializer().descriptor.elementNames.toList()
        assertTrue(declared.size >= 18, "ThreeSurfaceClick shrank to ${declared.size} fields")
        kinds.forEach { kind ->
            val keys = fixture(kind).keys
            val missing = declared.filterNot { it in keys }
            assertTrue(
                missing.isEmpty(),
                "the viewer never sends $missing on a `$kind` hit, so ${missing.size} field(s) of " +
                    "ThreeSurfaceClick silently carry their defaults and look like data",
            )
        }
    }

    // ------------------------------------------------------------------ the record checks itself

    /**
     * A vertex hit's `vertexIndex` indexes the **real drawable's** buffer, and lands on the point it
     * reported.
     *
     * The strongest assertion in this file, and the one an off-by-one cannot survive. The captured
     * message says `vertexIndex: 18` and `ex, ey, ez = (−600, 400, 30)`; the drawable is rebuilt here
     * from `boxMesh` through the same `toThreeDrawable`, and vertex 18 of it has to *be* that point.
     * Note that 18 only makes sense because `shadedNormals` splits the box's 8 corners into 36 vertices
     * — a naive index into the source mesh would be 6.
     */
    @Test
    fun aVertexHitIndexesTheDrawableTheViewerWasGiven() {
        val hit = decode("vertex").toHit()
        assertEquals(ThreeSurfaceHitKind.VERTEX, hit.kind)
        assertEquals(18, hit.vertexIndex)
        assertEquals(-1, hit.edgeIndex)

        val onElement = assertNotNull(hit.onElement, "a vertex hit must report the corner")
        val fromBuffer = slabVertex(hit.vertexIndex)
        listOf(onElement.x.toDouble(), onElement.y.toDouble(), onElement.z.toDouble()).zip(fromBuffer).forEachIndexed { axis, (a, b) ->
            assertEquals(b, a, 1e-9, "axis $axis of vertex ${hit.vertexIndex} does not match the record")
        }
        // …and it is a real corner of a 1200 × 800 × 60 box centred on the origin.
        assertEquals(600.0, abs(onElement.x.toDouble()), 1e-9)
        assertEquals(400.0, abs(onElement.y.toDouble()), 1e-9)
        assertEquals(30.0, abs(onElement.z.toDouble()), 1e-9)

        // The buffer really is the split one, or `18` proves nothing about indexing: index 18 does not
        // even exist in the 8-vertex source mesh, which is the point.
        val vertexCount = assertNotNull(slab.geometry.attributes["position"]).array.size / 3
        assertEquals(24, vertexCount, "a hard-edged box splits to 24 vertices — six faces of four")
        assertTrue(
            hit.vertexIndex >= boxMesh(1200.0, 800.0, 60.0).vertexCount,
            "vertex ${hit.vertexIndex} would also be a valid index into the *source* mesh, so this " +
                "test could not tell the two buffers apart",
        )
    }

    /**
     * A triangle hit's `onElement` is its barycentric reconstruction — recomputed here, in Kotlin.
     *
     * The weights and the point travel as separate fields, so nothing on the wire forces them to agree.
     * This is the check that they do, on the same message, from the triangle the message also carries.
     * It is also the only place the weights are load-bearing at all — see
     * `ThreeSurfaceHitDifferentialTest`'s audit of what a wrong weight can and cannot break.
     */
    @Test
    fun aTriangleHitsPointIsItsBarycentricReconstruction() {
        val hit = decode("triangle").toHit()
        assertEquals(ThreeSurfaceHitKind.TRIANGLE, hit.kind)
        assertEquals(-1, hit.vertexIndex)
        assertEquals(-1, hit.edgeIndex)
        assertEquals(1.0, hit.u + hit.v + hit.w, 1e-12, "barycentric weights must sum to 1")
        assertTrue(
            listOf(hit.u, hit.v, hit.w).all { it > 0.0 },
            "an interior hit's weights should all be positive: ${hit.u}, ${hit.v}, ${hit.w}",
        )

        val tri = hit.triangle
        assertEquals(3, tri.size, "the record must carry the triangle it was classified against")
        // Checked at full precision, on the wire record's own doubles, rather than on the narrowed
        // `Vec3` — see `theHitRecordNarrowsToFloat32` for why that distinction is not pedantry.
        val wire = decode("triangle")
        val corners = (0 until 3).map { c -> (0 until 3).map { axis -> wire.triangle[c * 3 + axis] } }
        val expected = (0 until 3).map { axis ->
            wire.u!! * corners[0][axis] + wire.v!! * corners[1][axis] + wire.w!! * corners[2][axis]
        }
        listOf(wire.ex!!, wire.ey!!, wire.ez!!).zip(expected).forEachIndexed { axis, (a, b) ->
            assertEquals(b, a, 1e-9, "axis $axis: onElement is not u·a + v·b + w·c")
        }

        // The fixture's weights are distinct, so this could have caught a permutation. At the centroid
        // it could not have — the same trap `ThreeSurfaceHitDifferentialTest` documents.
        val gaps = listOf(abs(hit.u - hit.v), abs(hit.v - hit.w), abs(hit.u - hit.w))
        assertTrue(gaps.min() > 0.05, "the captured weights ${listOf(hit.u, hit.v, hit.w)} are too close")
    }

    /** An edge hit's point lies on the named edge of the triangle it carries. */
    @Test
    fun anEdgeHitsPointLiesOnTheEdgeItNames() {
        val hit = decode("edge").toHit()
        assertEquals(ThreeSurfaceHitKind.EDGE, hit.kind)
        assertEquals(-1, hit.vertexIndex)
        assertTrue(hit.edgeIndex in 0..2, "edge index out of range: ${hit.edgeIndex}")

        val a = hit.triangle[hit.edgeIndex]
        val b = hit.triangle[(hit.edgeIndex + 1) % 3]
        val on = assertNotNull(hit.onElement)
        // Collinear and between: |a→p| + |p→b| == |a→b| for a point on the segment.
        fun d(p: afield.stdlib.Vec3, q: afield.stdlib.Vec3): Double {
            val dx = (p.x - q.x).toDouble()
            val dy = (p.y - q.y).toDouble()
            val dz = (p.z - q.z).toDouble()
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        // 1e-3 on a 1200-long edge: `Vec3` is float32, so a coordinate near 1e3 resolves to about
        // 1e-4. Tightening this would be testing the storage type, not the geometry.
        assertEquals(
            d(a, b), d(a, on) + d(on, b), 1e-3,
            "the reported point is not on the edge it names",
        )
        assertTrue(d(a, on) > 1.0 && d(on, b) > 1.0, "the edge hit degenerated to a corner")
    }

    /** Every fixture's triangle is a real face of the slab, and the record's own `position` is on it. */
    @Test
    fun everyFixturesTriangleIsARealFaceOfTheSlab() {
        val positions = assertNotNull(slab.geometry.attributes["position"]).array
        val corners = (0 until positions.size / 3).map {
            Triple(positions[it * 3], positions[it * 3 + 1], positions[it * 3 + 2])
        }.toSet()
        kinds.forEach { kind ->
            val hit = decode(kind).toHit()
            assertEquals(3, hit.triangle.size, "$kind: no triangle on the record")
            hit.triangle.forEach { c ->
                assertTrue(
                    Triple(c.x.toFloat(), c.y.toFloat(), c.z.toFloat()) in corners,
                    "$kind: the corner (${c.x}, ${c.y}, ${c.z}) is not a vertex of the drawable",
                )
            }
            assertTrue(hit.frontFacing, "$kind: a click from outside a back-face-culled solid is front-facing")
            assertEquals(1, hit.faceIndex, "$kind: all three captures landed on face 1 of the slab")
        }
    }

    // ------------------------------------------------------------------ the callback actually fires

    /**
     * **The loop closes**: the API mints a callback, the wire record reaches it, and it is the record.
     *
     * `ThreeSceneBuilder.mesh(onClick = …)` registers through the same `registerThreeSurfaceClickCallback`
     * the production path uses and puts the id in the payload; `getThreeSurfaceClickCallback` is what
     * `installThreeJsHandler` looks it up with. The only thing between them in production is the ktor
     * session, which is not constructible here.
     *
     * The fixture's own `callbackId` is deliberately *not* used to look anything up — it is a UUID from
     * a browser session that ended hours ago, and a test that depended on it resolving would be testing
     * nothing. What is asserted is that the two namespaces agree on their prefix.
     */
    @Test
    fun theRegisteredCallbackReceivesTheRecordTheBrowserPosted() {
        val seen = AtomicReference<ThreeSurfaceHit?>(null)
        val payload = ThreeSceneBuilder(48).apply {
            mesh(boxMesh(1200.0, 800.0, 60.0), id = "slab", label = "slab", onClick = { seen.set(it) })
        }.payload("wire-test")

        val callbackId = payload.drawables.single().callbackId
        assertTrue(callbackId.startsWith("three-surface-"), "mesh(onClick = …) minted `$callbackId`")
        assertTrue(
            fixture("vertex").getValue("callbackId").jsonPrimitive.content.startsWith("three-surface-"),
            "the browser and the registry disagree about the callback-id namespace",
        )

        val callback = assertNotNull(
            getThreeSurfaceClickCallback(callbackId),
            "the id `$callbackId` in the payload does not resolve to a callback",
        )
        callback(decode("vertex").toHit())

        val received = assertNotNull(seen.get(), "the callback was never invoked")
        assertEquals(ThreeSurfaceHitKind.VERTEX, received.kind)
        assertEquals("slab", received.id)
        assertEquals("slab", received.label)
        assertEquals(18, received.vertexIndex)
        assertEquals(0, received.drawableIndex)
        val on = assertNotNull(received.onElement)
        assertEquals(-600.0, on.x.toDouble(), 1e-9)
        assertEquals(400.0, on.y.toDouble(), 1e-9)
        assertEquals(30.0, on.z.toDouble(), 1e-9)
    }

    /**
     * The hit record is **float32**, and a caller should know it.
     *
     * `ThreeSurfaceHit.position` and `.onElement` are `Vec3`, which is three floats, while the wire
     * carries doubles. So a coordinate near 1e3 arrives with about 1e-4 of resolution. That is ample for
     * a millimetre model and it is *not* ample for asserting equality at 1e-9, which is how this was
     * found: three assertions in this file were written at double tolerances and failed by exactly the
     * float32 gap.
     *
     * Recorded as a property rather than papered over with looser tolerances, because a caller
     * differencing two nearby hits — which is what measuring is — needs to know where the floor is.
     */
    @Test
    fun theHitRecordNarrowsToFloat32() {
        val wire = decode("triangle")
        val hit = wire.toHit()
        val on = assertNotNull(hit.onElement)
        val exact = wire.ex!!
        assertEquals(exact, on.x.toDouble(), 1e-3, "the narrowing should cost far less than a millimetre")
        assertTrue(
            on.x.toDouble() != exact,
            "onElement round-tripped exactly; if Vec3 became double-precision, delete this test rather " +
                "than loosening the ones it explains",
        )
        assertEquals(exact.toFloat(), on.x, 0.0f, "the value should be exactly the float32 of the wire's double")
    }

    /** An unknown id resolves to nothing rather than to something plausible. */
    @Test
    fun anUnknownCallbackIdResolvesToNothing() {
        assertNull(getThreeSurfaceClickCallback("three-surface-not-a-real-id"))
        assertNull(getThreeSurfaceClickCallback(""))
    }

    /**
     * A viewer that predates a field still decodes — the reason every field is defaulted.
     *
     * Cheap to assert and easy to break: switching any field to non-optional would make a cached
     * browser's older payload undecodable, and the symptom is a click that does nothing at all.
     */
    @Test
    fun aSparseMessageFromAnOlderViewerStillDecodes() {
        val sparse = """{"callbackId":"three-surface-x","kind":"triangle","x":1.0,"y":2.0,"z":3.0}"""
        val hit = threeJson.decodeFromJsonElement<ThreeSurfaceClick>(
            Json.parseToJsonElement(sparse).jsonObject
        ).toHit()
        assertEquals(ThreeSurfaceHitKind.TRIANGLE, hit.kind)
        assertEquals(1.0, hit.position.x.toDouble(), 0.0)
        assertEquals(-1, hit.faceIndex)
        assertNull(hit.onElement, "no `ex`/`ey` means no point on the element, not the origin")
        assertTrue(hit.u.isNaN(), "an absent weight is NaN, not zero")
        assertTrue(hit.triangle.isEmpty())
    }

    /** An unrecognised kind degrades to UNKNOWN rather than to a plausible one. */
    @Test
    fun anUnrecognisedKindIsUnknown() {
        listOf("", "quad", "FACE_2") .forEach { kind ->
            val click = ThreeSurfaceClick(callbackId = "three-surface-x", kind = kind)
            assertEquals(ThreeSurfaceHitKind.UNKNOWN, click.toHit().kind, "`$kind` should be UNKNOWN")
        }
        // …but `face` is accepted, because that is what an FLine-shaped caller would say.
        assertEquals(
            ThreeSurfaceHitKind.TRIANGLE,
            ThreeSurfaceClick(callbackId = "x", kind = "face").toHit().kind,
        )
    }
}
