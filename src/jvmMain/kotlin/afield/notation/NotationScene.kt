package afield

import afield.graphics.FLine
import afield.stdlib.Vec2
import afield.stdlib.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The drawing layer's scene as data: the root box's `drawing` property, a complete `.excalidraw`
 * document, parsed losslessly.
 *
 * Every element is kept as the [JsonObject] Excalidraw wrote, and [Mark] only *reads* it, so fields
 * this code knows nothing about survive a round trip untouched. Writes go through [withElement],
 * which bumps `version` the way Excalidraw does; that is what lets [merge] decide between two copies
 * of the same element using Excalidraw's own rule, on the server, without the client's help.
 *
 * See `dev_docs/NOTATION.md`.
 */
class NotationScene private constructor(
    /** Top-level fields other than `elements` (`type`, `version`, `appState`, `files`, …), kept as found. */
    private val envelope: JsonObject,
    /** Elements in z order, deleted ones included: they are the tombstones that carry a deletion. */
    val elements: List<JsonObject>,
) {
    private val byId: Map<String, JsonObject> by lazy { elements.associateBy { it.string("id") ?: "" } }

    operator fun get(id: String): JsonObject? = byId[id]

    /** Live (non-deleted) elements in z order. */
    val live: List<JsonObject> get() = elements.filter { !it.isDeletedElement() }

    fun serialize(): String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(envelope + ("elements" to JsonArray(elements))),
    )

    /** Replaces (by id) or appends [element], bumping its version past whatever this scene holds. */
    fun withElement(element: JsonObject): NotationScene = withElements(listOf(element))

    fun withElements(changed: List<JsonObject>): NotationScene {
        if (changed.isEmpty()) return this
        val bumped = changed.associate { element ->
            val id = element.string("id") ?: error("element without an id")
            id to bumpVersion(element, byId[id])
        }
        val out = ArrayList<JsonObject>(elements.size + bumped.size)
        for (e in elements) out += bumped[e.string("id")] ?: e
        for ((id, e) in bumped) if (id !in byId) out += e
        return NotationScene(envelope, out)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val EMPTY: NotationScene = NotationScene(defaultEnvelope(), emptyList())

        /** The scene in [text]; [EMPTY] for no text, and also for text that is not a scene — see [parseOrNull]. */
        fun parse(text: String?): NotationScene = parseOrNull(text) ?: EMPTY

        /** The scene in [text], or null when [text] is present but is not a scene (not "empty"). */
        fun parseOrNull(text: String?): NotationScene? {
            if (text.isNullOrBlank()) return EMPTY
            return try {
                val root = json.parseToJsonElement(text).jsonObject
                val elements = (root["elements"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                NotationScene(JsonObject(root - "elements"), elements)
            } catch (e: Exception) {
                null
            }
        }

        private fun defaultEnvelope() = buildJsonObject {
            put("type", JsonPrimitive("excalidraw"))
            put("version", JsonPrimitive(2))
            put("source", JsonPrimitive("field"))
            put("appState", buildJsonObject {
                put("viewBackgroundColor", JsonPrimitive("transparent"))
                put("gridSize", JsonNull)
            })
            put("files", JsonObject(emptyMap()))
        }

        /**
         * Merges [incoming] into [current], element by element, with Excalidraw's reconciliation
         * rule: the higher `version` wins; on a tie, the lower `versionNonce`. Order follows
         * [incoming] (a client's z order), and elements only [current] has are kept after it — which
         * is how a code write survives a client scene that was sent before the client had seen it.
         */
        fun merge(current: NotationScene, incoming: NotationScene): NotationScene {
            val out = ArrayList<JsonObject>(max(current.elements.size, incoming.elements.size))
            val seen = HashSet<String>()
            for (e in incoming.elements) {
                val id = e.string("id") ?: continue
                seen += id
                val mine = current[id]
                out += if (mine == null || wins(e, mine)) e else mine
            }
            for (e in current.elements) {
                val id = e.string("id") ?: continue
                if (id !in seen) out += e
            }
            return NotationScene(if (incoming.envelope.isEmpty()) current.envelope else incoming.envelope, out)
        }

        private fun wins(challenger: JsonObject, holder: JsonObject): Boolean {
            val a = challenger.long("version") ?: 0
            val b = holder.long("version") ?: 0
            if (a != b) return a > b
            return (challenger.long("versionNonce") ?: 0) < (holder.long("versionNonce") ?: 0)
        }

        internal fun bumpVersion(element: JsonObject, previous: JsonObject?): JsonObject {
            val base = max(previous?.long("version") ?: 0, element.long("version") ?: 0)
            return JsonObject(
                element + mapOf(
                    "version" to JsonPrimitive(base + 1),
                    "versionNonce" to JsonPrimitive(Random.nextInt(1, Int.MAX_VALUE)),
                    "updated" to JsonPrimitive(System.currentTimeMillis()),
                ),
            )
        }
    }
}

/** What changed between two scenes, for reactions. Only live elements are reported as added/updated. */
class NotationChange internal constructor(
    val added: List<JsonObject>,
    val updated: List<JsonObject>,
    val removed: List<JsonObject>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && updated.isEmpty() && removed.isEmpty()

    companion object {
        fun between(before: NotationScene, after: NotationScene): NotationChange {
            val added = ArrayList<JsonObject>()
            val updated = ArrayList<JsonObject>()
            val removed = ArrayList<JsonObject>()
            for (e in after.elements) {
                val id = e.string("id") ?: continue
                val old = before[id]
                val liveNow = !e.isDeletedElement()
                val liveBefore = old != null && !old.isDeletedElement()
                when {
                    liveNow && !liveBefore -> added += e
                    !liveNow && liveBefore -> removed += e
                    liveNow && (old?.long("version") ?: 0) != (e.long("version") ?: 0) -> updated += e
                }
            }
            return NotationChange(added, updated, removed)
        }
    }
}

/** The kinds of mark Excalidraw draws, as Field names them. */
enum class MarkType {
    RECTANGLE, ELLIPSE, DIAMOND, LINE, ARROW, STROKE, TEXT, FRAME, IMAGE, OTHER;

    val isLinear: Boolean get() = this == LINE || this == ARROW || this == STROKE

    companion object {
        fun of(type: String?): MarkType = when (type) {
            "rectangle" -> RECTANGLE
            "ellipse" -> ELLIPSE
            "diamond" -> DIAMOND
            "line" -> LINE
            "arrow" -> ARROW
            "freedraw" -> STROKE
            "text" -> TEXT
            "frame", "magicframe" -> FRAME
            "image" -> IMAGE
            else -> OTHER
        }
    }
}

// ── JSON helpers ────────────────────────────────────────────────────────────────────────────────

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.isDeletedElement(): Boolean = bool("isDeleted") == true

internal fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)

/** The element's `customData` with [key] set (or removed, when [value] is null). */
internal fun JsonObject.withCustom(key: String, value: JsonElement?): JsonObject {
    val custom = obj("customData") ?: JsonObject(emptyMap())
    val next = if (value == null) JsonObject(custom - key) else JsonObject(custom + (key to value))
    return with("customData" to next)
}

internal fun JsonObject.custom(key: String): JsonElement? = obj("customData")?.get(key)

/** Points of a linear element in world coordinates (Excalidraw stores them relative to `x, y`). */
internal fun JsonObject.worldPoints(): List<Vec2> {
    val ox = double("x") ?: 0.0
    val oy = double("y") ?: 0.0
    val pts = this["points"] as? JsonArray ?: return emptyList()
    return pts.mapNotNull { p ->
        val a = p as? JsonArray ?: return@mapNotNull null
        val px = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val py = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        vec(ox + px, oy + py)
    }
}

/**
 * [element] with its points replaced by [world] points: `x, y` becomes the first point, `points` are
 * relative to it, and `width`/`height` are the points' extent, which is what Excalidraw expects.
 */
internal fun JsonObject.withWorldPoints(world: List<Vec2>): JsonObject {
    if (world.isEmpty()) return this
    val ox = world[0].x.toDouble()
    val oy = world[0].y.toDouble()
    var minX = 0.0; var maxX = 0.0; var minY = 0.0; var maxY = 0.0
    val rel = world.map {
        val px = it.x - ox
        val py = it.y - oy
        minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
        JsonArray(listOf(JsonPrimitive(px), JsonPrimitive(py)))
    }
    return with(
        "x" to JsonPrimitive(ox),
        "y" to JsonPrimitive(oy),
        "points" to JsonArray(rel),
        "width" to JsonPrimitive(maxX - minX),
        "height" to JsonPrimitive(maxY - minY),
    )
}

/** World-space bounds of an element, unrotated. */
internal fun JsonObject.worldBounds(): Rect {
    val kind = MarkType.of(string("type"))
    if (kind.isLinear) {
        val pts = worldPoints()
        if (pts.isEmpty()) return Rect(double("x") ?: 0.0, double("y") ?: 0.0, 0.0, 0.0)
        val minX = pts.minOf { it.x.toDouble() }
        val minY = pts.minOf { it.y.toDouble() }
        return Rect(minX, minY, pts.maxOf { it.x.toDouble() } - minX, pts.maxOf { it.y.toDouble() } - minY)
    }
    var x = double("x") ?: 0.0
    var y = double("y") ?: 0.0
    var w = double("width") ?: 0.0
    var h = double("height") ?: 0.0
    if (w < 0) { x += w; w = -w }
    if (h < 0) { y += h; h = -h }
    return Rect(x, y, w, h)
}

/** The element's geometry as an [FLine] in world coordinates. */
internal fun JsonObject.toFLine(): FLine {
    val f = FLine()
    val kind = MarkType.of(string("type"))
    if (kind.isLinear) {
        val pts = worldPoints()
        pts.forEachIndexed { i, p -> if (i == 0) f.moveTo(p.x, p.y) else f.lineTo(p.x, p.y) }
        return f
    }
    val b = worldBounds()
    when (kind) {
        MarkType.ELLIPSE -> {
            val cx = b.x + b.w / 2
            val cy = b.y + b.h / 2
            val n = 48
            for (i in 0..n) {
                val t = 2 * Math.PI * i / n
                val px = cx + b.w / 2 * Math.cos(t)
                val py = cy + b.h / 2 * Math.sin(t)
                if (i == 0) f.moveTo(px, py) else f.lineTo(px, py)
            }
        }
        MarkType.DIAMOND -> {
            f.moveTo(b.x + b.w / 2, b.y).lineTo(b.x + b.w, b.y + b.h / 2)
                .lineTo(b.x + b.w / 2, b.y + b.h).lineTo(b.x, b.y + b.h / 2).lineTo(b.x + b.w / 2, b.y)
        }
        else -> {
            f.moveTo(b.x, b.y).lineTo(b.x + b.w, b.y).lineTo(b.x + b.w, b.y + b.h)
                .lineTo(b.x, b.y + b.h).lineTo(b.x, b.y)
        }
    }
    return f
}

internal fun jsonString(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
internal fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement.asArray(): JsonArray? = try { jsonArray } catch (_: Exception) { null }
