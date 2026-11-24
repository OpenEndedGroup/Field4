/**
 * # The treemap viewer's wire
 *
 * A [Treemap] is drawn by its own viewer, `treemap_viewer.html`, in an iframe: WebGL instanced rectangles, with
 * labels, breadcrumbs and a legend over them. It is fed the way a Three.js view is (`ThreeWire.kt`):
 *
 * - The websocket carries a small JSON **description** of each frame (`treemap/frame`): its size, breadcrumbs,
 *   legend and colourings, and a table of typed arrays.
 * - The arrays are one binary response from `GET /treemap-buffers?uid=…&generation=…`, written straight out of
 *   the arrays the frame was packed into, with the same encoding and content hashes as `/three-buffers`. A viewer
 *   holding an array with the same hash reuses it and asks for only the rest (`only=`), so a change of selection
 *   or colouring fetches one small array, not the layout.
 *
 * One frame is the cells of one layout, parents before children, as eight arrays (the names in [FRAME_ARRAYS]):
 * rectangles (`Float32`, x0 y0 x1 y1 in layout pixels), colours (`Uint8` RGBA), stable node ids (`Uint32`, the
 * same node keeps its id across frames and box runs, which is what the viewer morphs by), parent indices
 * (`Int32`), flags ([CELL_OPEN] …), depths, values (`Float32`), and the names as UTF-8 with their offsets. About
 * 30 bytes a cell, so 100,000 cells are 3 MB.
 *
 * The viewer answers on the same socket with clicks, hovers and camera moves, naming a cell by its index in the
 * frame it was showing. The server keeps the last few frames' cells to turn that index back into a node.
 */
package afield.ui.visualization.treemap

import afield.app.protocol.Handled
import afield.app.protocol.rawHandlers
import afield.app.server.thisSession
import afield.graphics.three.ThreeGeometryBuffers
import afield.graphics.three.ThreeWireBuffer
import afield.graphics.three.ThreeWireBuffers
import afield.graphics.three.ThreeWireSource
import afield.graphics.three.contentHash
import afield.math.linalg.Vec4
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.installHandlers
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal const val TREEMAP_CHANNEL = "__treemap__"

/** The arrays of a frame, in the order of its buffer table. */
internal val FRAME_ARRAYS = listOf("rects", "colors", "ids", "parents", "flags", "depth", "values", "nameOffsets", "names")

/** A cell whose children were laid out inside it. */
internal const val CELL_OPEN = 1
/** A selected cell. */
internal const val CELL_SELECTED = 2
/** An open cell the layout gave a header strip, for its name. */
internal const val CELL_HEADER = 4
/** A leaf of the tree (not merely a cell too small to open). */
internal const val CELL_LEAF = 8

/** A breadcrumb: a node on the path from the root to the focus. */
@Serializable
internal data class TreemapCrumb(val name: String, val value: Double, val id: Int)

/** One row of a legend: a colour, what it means, and the share of the focus it covers (0..1), if that is known. */
@Serializable
internal data class TreemapLegendRow(val label: String, val rgba: List<Int>, val share: Double? = null)

/** The `treemap/frame` description: everything but the arrays, which are in [buffers]. */
@Serializable
internal data class TreemapFrameMessage(
    val uid: String,
    val generation: String,
    val count: Int,
    val arrays: List<String>,
    val buffers: ThreeWireBuffers,
    val width: Double,
    val height: Double,
    val background: List<Int>,
    val labelSize: Double,
    val headerHeight: Double,
    /** Screen pixels per layout unit the layout was made for: padding and headers are that many layout units per pixel. */
    val ppu: Double,
    /** Seconds this frame morphs in over. */
    val transition: Double,
    /** Fit the camera to the drawing again: the focus changed. */
    val refit: Boolean,
    val crumbs: List<TreemapCrumb>,
    val legend: List<TreemapLegendRow>,
    val legendTitle: String? = null,
    val colorings: List<String>,
    val coloring: String? = null,
    val selectionRgba: List<Int>,
    val valueFormat: String,
    val type: String = "treemap/frame",
)

/** One published frame: its description, its arrays, and the cells its indices name. */
internal class TreemapPublished(
    val generation: String,
    val description: ByteArray,
    val buffers: ThreeGeometryBuffers,
    val cells: List<TreemapCell>,
)

/** What packing and sending the latest frame cost. */
data class TreemapFrameStats(val cells: Int, val descriptionBytes: Int, val bufferBytes: Long, val packMs: Double)

internal val treemapJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun rgbaBytes(c: Vec4): List<Int> = listOf(
    (c.x * 255).toInt().coerceIn(0, 255), (c.y * 255).toInt().coerceIn(0, 255),
    (c.z * 255).toInt().coerceIn(0, 255), (c.w * 255).toInt().coerceIn(0, 255),
)

/**
 * Pack [cells] into a frame's arrays and build its description, [buffers] first so their hashes are in it.
 * [colorOf] gives each cell's fill, [idOf] each node's stable id, and [flagsOf] its [CELL_OPEN] … bits.
 */
internal fun packTreemapFrame(
    cells: List<TreemapCell>,
    generation: String,
    colorOf: (TreemapCell) -> Vec4,
    idOf: (TreemapNode) -> Int,
    flagsOf: (TreemapCell) -> Int,
    nameOf: (TreemapNode) -> String,
    describe: (ThreeWireBuffers) -> TreemapFrameMessage,
): TreemapPublished {
    val n = cells.size
    val rects = FloatArray(4 * n)
    val colors = ByteArray(4 * n)
    val ids = IntArray(n)
    val parents = IntArray(n)
    val flags = ByteArray(n)
    val depth = ByteArray(n)
    val values = FloatArray(n)
    val nameOffsets = IntArray(n + 1)
    val names = java.io.ByteArrayOutputStream(n * 12)
    val indexOf = java.util.IdentityHashMap<TreemapNode, Int>(n * 2)
    for ((i, c) in cells.withIndex()) {
        rects[4 * i] = c.x0.toFloat(); rects[4 * i + 1] = c.y0.toFloat()
        rects[4 * i + 2] = c.x1.toFloat(); rects[4 * i + 3] = c.y1.toFloat()
        val rgba = colorOf(c)
        colors[4 * i] = (rgba.x * 255).toInt().coerceIn(0, 255).toByte()
        colors[4 * i + 1] = (rgba.y * 255).toInt().coerceIn(0, 255).toByte()
        colors[4 * i + 2] = (rgba.z * 255).toInt().coerceIn(0, 255).toByte()
        colors[4 * i + 3] = (rgba.w * 255).toInt().coerceIn(0, 255).toByte()
        ids[i] = idOf(c.node)
        parents[i] = c.node.parent?.let { indexOf[it] } ?: -1
        indexOf[c.node] = i
        flags[i] = flagsOf(c).toByte()
        depth[i] = c.depth.coerceAtMost(255).toByte()
        values[i] = c.node.value.toFloat()
        nameOffsets[i] = names.size()
        names.write(nameOf(c.node).toByteArray(Charsets.UTF_8))
    }
    nameOffsets[n] = names.size()
    val sources = listOf(
        ThreeWireSource.Floats(rects), ThreeWireSource.Bytes(colors), ThreeWireSource.Ints("Uint32Array", 4, ids),
        ThreeWireSource.Ints("Int32Array", 4, parents), ThreeWireSource.Bytes(flags), ThreeWireSource.Bytes(depth),
        ThreeWireSource.Floats(values), ThreeWireSource.Ints("Uint32Array", 4, nameOffsets), ThreeWireSource.Bytes(names.toByteArray()),
    )
    var bytes = 0L
    val entries = sources.map { s ->
        val offset = (bytes + 7) / 8 * 8
        bytes = offset + s.byteLength
        ThreeWireBuffer(s.type, offset, s.count, s.contentHash())
    }
    val table = ThreeWireBuffers(generation, bytes, entries)
    val description = treemapJson.encodeToString(TreemapFrameMessage.serializer(), describe(table)).encodeToByteArray()
    return TreemapPublished(generation, description, ThreeGeometryBuffers(bytes, sources), cells)
}

/**
 * The server side of one treemap viewer: the viewers attached to it, the frame they were last sent, and the
 * [Treemap] whose callbacks their clicks reach. A view lives as long as its box and channel; running the box again
 * hands the same view a new [Treemap].
 */
class TreemapView internal constructor(val uid: String) {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()

    /** The treemap answering this view's clicks, hovers and camera moves: the latest made for its box and channel. */
    @Volatile internal var treemap: Treemap? = null

    private var published: TreemapPublished? = null
    /** Frames just before [published]: a viewer fetches a frame's buffers after its description arrives. */
    private val recent = ArrayDeque<TreemapPublished>()

    /** What the last frame cost to pack, for tests and the bench. */
    @Volatile var lastFrame: TreemapFrameStats? = null
        internal set

    /** Sees every description sent, whether or not a viewer is attached. For tests. */
    internal var onBroadcast: ((ByteArray) -> Unit)? = null

    val hasViewer: Boolean get() = sessions.isNotEmpty()

    @Synchronized
    internal fun attach(session: WebSocketSession) {
        sessions.add(session)
        published?.let { send(session, it.description) }
    }

    internal fun detach(session: WebSocketSession) {
        sessions.remove(session)
    }

    @Synchronized
    internal fun publish(frame: TreemapPublished) {
        published?.let { recent.addLast(it) }
        while (recent.size > RECENT_FRAMES) recent.removeFirst()
        published = frame
        onBroadcast?.invoke(frame.description)
        sessions.forEach { send(it, frame.description) }
    }

    @Synchronized
    internal fun buffers(generation: String): ThreeGeometryBuffers? = frame(generation)?.buffers

    /** The cells of [generation], while it is current or recent. */
    @Synchronized
    internal fun cells(generation: String): List<TreemapCell>? = frame(generation)?.cells

    private fun frame(generation: String): TreemapPublished? =
        published?.takeIf { it.generation == generation } ?: recent.lastOrNull { it.generation == generation }

    /** The latest frame's cells. */
    @Synchronized
    internal fun currentCells(): List<TreemapCell> = published?.cells.orEmpty()

    private fun send(session: WebSocketSession, message: ByteArray) {
        inMain { runCatching { session.send(Frame.Text(true, message)) } }
    }

    private companion object {
        const val RECENT_FRAMES = 6
    }
}

private val treemapViews = ConcurrentHashMap<String, TreemapView>()

internal fun treemapViewFor(uid: String): TreemapView = treemapViews.computeIfAbsent(uid) { TreemapView(it) }

/** What `/treemap-buffers` serves: a view's arrays for a current or recent generation. */
internal fun treemapBuffers(uid: String, generation: String): ThreeGeometryBuffers? = treemapViews[uid]?.buffers(generation)

/** A websocket closed: stop sending its views anything. */
fun detachTreemapSession(session: WebSocketSession) {
    treemapViews.values.forEach { it.detach(session) }
}

/** The `src` of a treemap viewer iframe, form-encoded as the Three.js viewer's is. */
internal fun treemapViewerSource(uid: String): String = "/treemap?uid=" + URLEncoder.encode(uid, "UTF-8")

internal fun newGeneration(): String = UUID.randomUUID().toString()

@RunsAt(installHandlers)
@OptIn(DelicateCoroutinesApi::class)
fun installTreemapHandler() {
    rawHandlers.getOrPut(TREEMAP_CHANNEL) { mutableSetOf() }.add { text ->
        val payload = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@add Handled.no
        if (payload["channel"]?.jsonPrimitive?.contentOrNull != TREEMAP_CHANNEL) return@add Handled.no
        val uid = payload["debug_uid"]?.jsonPrimitive?.contentOrNull ?: return@add Handled.no
        val session = thisSession.get()
        val type = payload["type"]?.jsonPrimitive?.contentOrNull
        if (type == "hello" || type == null) {
            treemapViewFor(uid).attach(session)
            return@add Handled.yes
        }
        val view = treemapViews[uid] ?: return@add Handled.no
        val map = view.treemap ?: return@add Handled.yes
        // Off the websocket's read loop: a user callback must never stall it, or throw into it.
        GlobalScope.launch {
            runCatching { map.receiveFromViewer(view, type, payload) }
                .onFailure { afield.app.diagnostics.logging.lprintln("error,treemap", "treemap '$uid' $type: ${it.message}") }
        }
        Handled.yes
    }
}

internal fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
internal fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
internal fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: false
internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
