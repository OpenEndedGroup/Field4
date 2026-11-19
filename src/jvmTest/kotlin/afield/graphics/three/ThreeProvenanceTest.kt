package afield.graphics.three

import afield.app.protocol.GeometryReferenceAsk
import afield.app.protocol.GeometrySourceLine
import afield.geometry.mesh.TriMesh
import kotlinx.serialization.json.*
import kotlin.test.*

/** A part's source line rides with the reference and the chat header, and never moves the review revision. */
class ThreeProvenanceTest {
    private val triangle = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))

    @Test fun provenanceIsLeftOutOfTheRevision() {
        val drawable = triangle.toThreeDrawable(id = "a", pickable = true)
        val plain = ThreeGeometryPayload(uid = "v", drawables = listOf(drawable))
        val traced = plain.copy(drawables = listOf(drawable.copy(provenance = GeometrySourceLine("box1", "code", 12, "wall"))))
        assertEquals(threeReviewRevision(plain), threeReviewRevision(traced))
        assertEquals(threeLegacyReviewRevision(plain), threeLegacyReviewRevision(traced))
    }

    @Test fun referenceHeaderRoundTripsTheCompactFields() {
        val record = buildJsonObject {
            put("artifactDigest", "r2-abc"); put("text", "Too thin here"); put("partLabel", "Wall")
            putJsonObject("sourceLine") { put("box", "box1"); put("property", "code"); put("line", 12); put("variable", "wall") }
            putJsonObject("selection") { put("kind", "edge"); put("partId", "wall"); putJsonArray("edge") { add(3); add(7) } }
        }
        val ask = threeGeometryReferenceAsk("view1", record, owner = "box1")
        assertEquals("edge v3–v7", ask.element)
        assertEquals(GeometrySourceLine("box1", "code", 12, "wall"), ask.sourceLine)
        val header = ask.header.lines()
        assertEquals(ask, GeometryReferenceAsk.parseHeader(header[0]))
        assertEquals(GeometryReferenceAsk.HEADER_HINT, header[1])
        assertNull(GeometryReferenceAsk.parseHeader("GeometryReference: not json"))
    }
}
