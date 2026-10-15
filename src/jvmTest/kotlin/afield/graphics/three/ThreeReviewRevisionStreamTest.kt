package afield.graphics.three

import afield.geometry.path.FLine
import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.lang.remotejs.JsFun
import afield.ui.visualization.l

import afield.ui.visualization.lines

import afield.geometry.mesh.TriMesh
import afield.graphics.render.filled
import afield.graphics.render.fline
import afield.graphics.render.hoverColor
import afield.geometry.mesh.icosphereMesh
import afield.graphics.render.label
import afield.graphics.render.onClick
import afield.graphics.render.pointed
import afield.math.linalg.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

/**
 * [threeReviewRevision] streams its text into the digest; it must produce the very revisions the old
 * tree-and-string implementation did, or every stored note on an ordinary mesh or FLine view would read
 * as made against older geometry.
 *
 * [treeRevision] is that implementation, verbatim as of `4822f05bf`, kept here as the oracle. The corpus
 * reaches every shape the stream's state machine distinguishes: ignored keys at the top, deep inside
 * drawables and interactive objects, and as *map* keys; empty and null containers; strings full of
 * escapes (topology is JSON inside a string); non-ASCII; the canonical-solid prefix.
 */
class ThreeReviewRevisionStreamTest {

    private fun treeRevision(payload: ThreeGeometryPayload): String {
        val canonicalDigest = payload.inspector?.artifactDigest
        val geometryPayload = if (canonicalDigest == null) payload else {
            val canonicalParts = payload.inspector.parts.keys
            payload.copy(drawables = payload.drawables.filterNot { it.id in canonicalParts })
        }
        if (canonicalDigest != null && geometryPayload.lines == null && geometryPayload.fills == null &&
            geometryPayload.points == null && geometryPayload.drawables.isEmpty() &&
            geometryPayload.interactive.isEmpty() && geometryPayload.scene == null) return canonicalDigest
        val json = Json { encodeDefaults = true }
        val geometry = buildJsonObject {
            if (canonicalDigest != null) put("canonicalDigest", canonicalDigest)
            for ((key, value) in json.encodeToJsonElement(geometryPayload).jsonObject) {
                if (key in setOf("lines", "fills", "points", "drawables", "interactive", "scene")) put(key, value)
            }
        }
        fun stable(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it !in setOf("callbackId", "sessionId", "hoverColor", "style", "label") }.mapValues { stable(it.value) })
            is JsonArray -> JsonArray(value.map(::stable))
            else -> value
        }
        return MessageDigest.getInstance("SHA-256").digest(stable(geometry).toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun assertSameRevision(payload: ThreeGeometryPayload, what: String) =
        assertEquals(treeRevision(payload), threeReviewRevision(payload), what)

    private val square = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2, 0, 2, 3))

    private fun sampleLines() = listOf(
        fline {
            moveTo(vec(0.0, 0.0, 0.0)); lineTo(vec(1.0, 0.5, -0.25)); cubicTo(vec(2.0, 1.0, 0.0), vec(3.0, -1.0, 0.0), vec(4.0, 0.0, 1e-7))
            label = "a \"quoted\" label"
        },
        fline {
            moveTo(vec(0.0, 0.0, 0.0)); lineTo(vec(1.0, 0.0, 0.0)); lineTo(vec(1.0, 1.0, 0.0)); close()
            filled = true
            pointed = true
            onClick = { }
            hoverColor = vec(1.0, 0.2, 0.2, 1.0)
            label = "clickable — é ✓"
        },
        fline { moveTo(vec(-3.0, 2.0, 1.0)); lineTo(vec(123456.79, 1e-30, -0.0)) },
    )

    @Test
    fun ordinaryFLineViewsKeepTheirRevisions() {
        assertSameRevision(buildThreeGeometryPayload("t", sampleLines(), 12, autoFrame = true, editable = false), "FLine view")
        assertSameRevision(buildThreeGeometryPayload("t", sampleLines(), 12, autoFrame = true, editable = true), "editable FLine view")
        assertSameRevision(
            buildThreeGeometryPayload("t", sampleLines(), 12, autoFrame = true, editable = false, groupKeys = listOf("a", "b", "a"),
                inspector = ThreeInspector()),
            "grouped FLine view",
        )
        assertSameRevision(buildThreeGeometryPayload("t", emptyList(), 12, autoFrame = true, editable = false), "an empty view")
    }

    @Test
    fun meshScenesKeepTheirRevisions() {
        val scene = ThreeSceneBuilder(24).apply {
            mesh(icosphereMesh(2), id = "sphere", label = "Sphere", pickable = true, hoverColor = vec(0.0, 1.0, 0.0, 1.0))
            mesh(square, id = "square", onClick = { }, panel = 1, explode = FloatArray(12) { it * 0.1f }, edges = true)
            mesh(square, label = "unnamed", unlit = true, opacity = 0.5)
            lines(sampleLines())
        }.payload("t")
        assertSameRevision(scene, "mixed mesh and FLine scene")
    }

    @Test
    fun solidsKeepTheirPrefixAndShortCut() {
        val topology = ThreePartTopology(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0), intArrayOf(0, 1, 2), listOf(listOf(0, 1)), intArrayOf(0))
        val solid = ThreeSceneBuilder(24).apply { mesh(square, id = "panel") }.payload("t").drawables.single()
        val extra = ThreeSceneBuilder(24).apply { mesh(icosphereMesh(1), id = "fixture") }.payload("t").drawables.single()
        val inspector = ThreeInspector(artifactDigest = "digest-\"of\"-the-solids", parts = mapOf("panel" to topology))
        val alone = ThreeGeometryPayload(uid = "t", drawables = listOf(solid), inspector = inspector)
        assertEquals("digest-\"of\"-the-solids", threeReviewRevision(alone), "solids alone are their artifact digest")
        assertSameRevision(alone, "solids alone")
        assertSameRevision(alone.copy(drawables = listOf(solid, extra)), "solids beside another mesh")
        assertSameRevision(alone.copy(lines = ThreeGeometryData(mapOf("position" to ThreeAttribute(3, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f))))), "solids beside FLines")
    }

    @Test
    fun ignoredKeysAreIgnoredWhereverTheyAppearAndNothingElseIs() {
        // Map keys are object keys to the old filter too: an attribute named `label` or `style` is dropped.
        val attributes = linkedMapOf(
            "position" to ThreeAttribute(3, floatArrayOf(0f, 1f, 2f)),
            "label" to ThreeAttribute(1, floatArrayOf(7f)),
            "style" to ThreeAttribute(1, floatArrayOf(8f)),
            "weird \"name\" \\ \u0001 é" to ThreeAttribute(1, floatArrayOf(9f), type = "Uint8Array", normalized = true),
        )
        val payload = ThreeGeometryPayload(
            uid = "t",
            fills = ThreeGeometryData(attributes, ThreeIndex("Uint16Array", intArrayOf(0, 1, 2))),
            points = ThreeGeometryData(emptyMap()),
            interactive = listOf(ThreeInteractiveObject(callbackId = "cb", hoverColor = floatArrayOf(1f, 0f, 0f, 1f),
                lines = ThreeGeometryData(attributes), label = "x", topology = """{"segments":[[0,1]],"closed":"a\"b\\c"}""")),
            drawables = listOf(ThreeDrawable(ThreeGeometryData(attributes), ThreeDrawStyle.surface(), label = "l", id = "ü/🙂",
                callbackId = "c", hoverColor = floatArrayOf(0f, 0f, 1f, 1f), transform = FloatArray(16) { if (it % 5 == 0) 1f else 0f })),
        )
        assertSameRevision(payload, "ignored keys everywhere")
        // ...and a value that is not ignored still counts: the guard against a filter that drops too much.
        val moved = payload.copy(fills = ThreeGeometryData(attributes + ("position" to ThreeAttribute(3, floatArrayOf(0f, 1f, 2.5f)))))
        assertNotEquals(threeReviewRevision(payload), threeReviewRevision(moved))
        val relabelled = payload.copy(drawables = payload.drawables.map { it.copy(label = "renamed", callbackId = "other") })
        assertEquals(threeReviewRevision(payload), threeReviewRevision(relabelled), "presentation changed the revision")
    }

    @Test
    fun aNonFiniteValueStillFails() {
        val payload = ThreeGeometryPayload(uid = "t", lines = ThreeGeometryData(mapOf("position" to ThreeAttribute(3, floatArrayOf(0f, Float.NaN, 0f)))))
        assertFails { treeRevision(payload) }
        assertFails { threeReviewRevision(payload) }
    }
}
