package afield.graphics

import afield.*
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min

private const val THREE_CHANNEL = "__threejs__"

@Serializable
data class ThreeAttribute(
    val itemSize: Int,
    val array: FloatArray,
    val type: String = "Float32Array",
    val normalized: Boolean = false
) {
    override fun toString(): String = prettySummary(
        "ThreeAttribute",
        "itemSize" to itemSize,
        "array" to array,
        "type" to type,
        "normalized" to normalized
    )
}

@Serializable
data class ThreeIndex(
    val type: String,
    val array: IntArray
) {
    override fun toString(): String = prettySummary(
        "ThreeIndex",
        "type" to type,
        "array" to array
    )
}

@Serializable
data class ThreeGeometryData(
    val attributes: Map<String, ThreeAttribute>,
    val index: ThreeIndex? = null
) {
    override fun toString(): String = prettySummary(
        "ThreeGeometryData",
        "attributes" to attributes.keys.sorted(),
        "index" to index
    )
}

@Serializable
data class ThreeBounds(
    val min: FloatArray,
    val max: FloatArray
) {
    override fun toString(): String = prettySummary(
        "ThreeBounds",
        "min" to min,
        "max" to max
    )
}

@Serializable
data class ThreeMeta(
    val lineSegments: Int = 0,
    val triangles: Int = 0,
    val points: Int = 0
) {
    override fun toString(): String = prettySummary(
        "ThreeMeta",
        "lineSegments" to lineSegments,
        "triangles" to triangles,
        "points" to points
    )
}

@Serializable
data class ThreeGeometryPayload(
    val type: String = "three/geometry",
    val uid: String,
    val lines: ThreeGeometryData? = null,
    val fills: ThreeGeometryData? = null,
    val points: ThreeGeometryData? = null,
    val bounds: ThreeBounds? = null,
    val meta: ThreeMeta? = null,
    val autoFrame: Boolean = true
) {
    override fun toString(): String = prettySummary(
        "ThreeGeometryPayload",
        "uid" to uid,
        "lines" to (lines != null),
        "fills" to (fills != null),
        "points" to (points != null),
        "bounds" to bounds,
        "meta" to meta,
        "autoFrame" to autoFrame
    )
}

private val threeJson = Json {
    encodeDefaults = true
}

/**
 * Server-side state for a live Three.js geometry viewer.
 *
 * Instances are returned by [Box.threeLines]. Call [update] to push new geometry
 * to all connected viewers.
 */
class ThreeGeometryViewState(val uid: String) {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()
    @Volatile private var lastPayload: String? = null

    /**
     * Attach a websocket session to this viewer and replay the latest payload.
     *
     * This is used by the server's Three.js websocket handler.
     */
    fun attach(session: WebSocketSession) {
        sessions.add(session)
        lastPayload?.let { sendTo(session, it) }
    }

    /**
     * Push a new payload to all attached viewers.
     */
    fun update(payload: ThreeGeometryPayload) {
        val encoded = threeJson.encodeToString(payload)
        lastPayload = encoded
        sessions.forEach { sendTo(it, encoded) }
    }

    private fun sendTo(session: WebSocketSession, payload: String) {
        inMain {
            runCatching { session.send(payload) }
        }
    }

    override fun toString(): String = prettySummary(
        "ThreeGeometryViewState",
        "uid" to uid,
        "sessions" to sessions.size,
        "hasPayload" to (lastPayload != null)
    )
}

private val knownThreeViews = ConcurrentHashMap<String, ThreeGeometryViewState>()

@RunsAt(installHandlers)
fun installThreeJsHandler() {
    rawHandlers.getOrPut(THREE_CHANNEL) { mutableSetOf() }.add { text ->
        val session = thisSession.get()
        val payload = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@add Handled.no
        val channel = payload["channel"]?.jsonPrimitive?.contentOrNull ?: return@add Handled.no
        if (channel != THREE_CHANNEL) return@add Handled.no
        val uid = payload["debug_uid"]?.jsonPrimitive?.contentOrNull ?: return@add Handled.no
        val viewer = knownThreeViews.computeIfAbsent(uid) { ThreeGeometryViewState(uid) }
        viewer.attach(session)
        Handled.yes
    }
}

/**
 * Display a Three.js viewer for the given lines and stream geometry updates to it.
 *
 * This creates an iframe in the output [channel] and sends a geometry payload to `/threejs`.
 *
 * @param channel Output channel name (disclosure panel).
 * @param name Viewer name used to build a stable id within this [Box].
 * @param height Iframe height in pixels.
 * @param samplesPerCubic Sampling density for cubic curves.
 * @param autoFrame If true, the viewer auto-frames the bounds on update.
 */
fun Box.threeLines(
    lines: Collection<FLine>,
    channel: String = "debug",
    name: String = "threejs",
    height: Int = 420,
    samplesPerCubic: Int = 48,
    autoFrame: Boolean = true
): ThreeGeometryViewState {
    val uid = "${this.id}_$name"
    val ln = "/threejs?uid=$uid"
    display(
        channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0;width:100%;height:${height}px'>
"""
    )
    val view = knownThreeViews.computeIfAbsent(uid) { ThreeGeometryViewState(uid) }
    val payload = buildThreeGeometryPayload(uid, lines, samplesPerCubic, autoFrame)
    view.update(payload)
    return view
}

/**
 * Vararg convenience overload of [threeLines].
 */
fun Box.threeLines(
    vararg lines: FLine,
    channel: String = "debug",
    name: String = "threejs",
    height: Int = 420,
    samplesPerCubic: Int = 48,
    autoFrame: Boolean = true
): ThreeGeometryViewState = threeLines(
    lines = lines.toList(),
    channel = channel,
    name = name,
    height = height,
    samplesPerCubic = samplesPerCubic,
    autoFrame = autoFrame
)

/**
 * Map convenience overload of [threeLines], combining all values into one viewer.
 */
fun Box.threeLines(
    linesByChannel: Map<String, List<FLine>>,
    channel: String = "debug",
    name: String = "threejs",
    height: Int = 420,
    samplesPerCubic: Int = 48,
    autoFrame: Boolean = true
): ThreeGeometryViewState = threeLines(
    lines = linesByChannel.values.flatten(),
    channel = channel,
    name = name,
    height = height,
    samplesPerCubic = samplesPerCubic,
    autoFrame = autoFrame
)

private class BoundsTracker {
    private var hasBounds = false
    private var minX = 0f
    private var minY = 0f
    private var minZ = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var maxZ = 0f

    fun include(x: Float, y: Float, z: Float) {
        if (!hasBounds) {
            hasBounds = true
            minX = x; maxX = x
            minY = y; maxY = y
            minZ = z; maxZ = z
        } else {
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
        }
    }

    fun include(v: Vec3) = include(v.x, v.y, v.z)

    fun toBounds(): ThreeBounds? {
        if (!hasBounds) return null
        return ThreeBounds(
            min = floatArrayOf(minX, minY, minZ),
            max = floatArrayOf(maxX, maxY, maxZ)
        )
    }
}

private fun buildThreeGeometryPayload(
    uid: String,
    lines: Collection<FLine>,
    samplesPerCubic: Int,
    autoFrame: Boolean
): ThreeGeometryPayload {
    val linePositions = ArrayList<Float>()
    val lineColors = ArrayList<Float>()
    val lineAlphas = ArrayList<Float>()
    val meshPositions = ArrayList<Float>()
    val meshColors = ArrayList<Float>()
    val meshAlphas = ArrayList<Float>()
    val meshIndices = ArrayList<Int>()
    val pointPositions = ArrayList<Float>()
    val pointColors = ArrayList<Float>()
    val pointAlphas = ArrayList<Float>()
    val pointSizes = ArrayList<Float>()
    val bounds = BoundsTracker()
    val samples = samplesPerCubic.coerceAtLeast(2)

    lines.forEach { f ->
        val color = f.color
        if (f.stroked) {
            appendLineSegments(f, samples, color, linePositions, lineColors, lineAlphas, bounds)
        }
        if (f.filled) {
            appendFilledMesh(f, samples, color, meshPositions, meshColors, meshAlphas, meshIndices, bounds)
        }
        if (f.pointed) {
            appendPoints(f, color, pointPositions, pointColors, pointAlphas, pointSizes, bounds)
        }
    }

    val lineGeom = if (linePositions.isNotEmpty()) {
        buildGeometryData(
            positions = linePositions.toFloatArray(),
            colors = lineColors.toFloatArray(),
            alphas = lineAlphas.toFloatArray(),
            sizes = null,
            indices = null
        )
    } else null

    val fillGeom = if (meshPositions.isNotEmpty()) {
        buildGeometryData(
            positions = meshPositions.toFloatArray(),
            colors = meshColors.toFloatArray(),
            alphas = meshAlphas.toFloatArray(),
            sizes = null,
            indices = meshIndices.toIntArray()
        )
    } else null

    val pointGeom = if (pointPositions.isNotEmpty()) {
        buildGeometryData(
            positions = pointPositions.toFloatArray(),
            colors = pointColors.toFloatArray(),
            alphas = pointAlphas.toFloatArray(),
            sizes = pointSizes.toFloatArray(),
            indices = null
        )
    } else null

    val meta = ThreeMeta(
        lineSegments = linePositions.size / 6,
        triangles = meshIndices.size / 3,
        points = pointPositions.size / 3
    )

    return ThreeGeometryPayload(
        uid = uid,
        lines = lineGeom,
        fills = fillGeom,
        points = pointGeom,
        bounds = bounds.toBounds(),
        meta = meta,
        autoFrame = autoFrame
    )
}

private fun buildGeometryData(
    positions: FloatArray,
    colors: FloatArray?,
    alphas: FloatArray?,
    sizes: FloatArray?,
    indices: IntArray?
): ThreeGeometryData {
    val attributes = mutableMapOf<String, ThreeAttribute>()
    attributes["position"] = ThreeAttribute(itemSize = 3, array = positions)
    if (colors != null) {
        attributes["color"] = ThreeAttribute(itemSize = 3, array = colors)
    }
    if (alphas != null) {
        attributes["alpha"] = ThreeAttribute(itemSize = 1, array = alphas)
    }
    if (sizes != null) {
        attributes["size"] = ThreeAttribute(itemSize = 1, array = sizes)
    }
    val index = indices?.takeIf { it.isNotEmpty() }?.let { idx ->
        val maxIndex = idx.maxOrNull() ?: 0
        val type = if (maxIndex > 65535) "Uint32Array" else "Uint16Array"
        ThreeIndex(type = type, array = idx)
    }
    return ThreeGeometryData(attributes = attributes, index = index)
}

private fun appendLineSegments(
    f: FLine,
    samplesPerCubic: Int,
    color: Vec4,
    positions: MutableList<Float>,
    colors: MutableList<Float>,
    alphas: MutableList<Float>,
    bounds: BoundsTracker
) {
    val polyline = ArrayList<Vec3>()
    val alpha = color.w.coerceIn(0f, 1f)

    fun flush() {
        if (polyline.size < 2) return
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            positions.add(a.x); positions.add(a.y); positions.add(a.z)
            positions.add(b.x); positions.add(b.y); positions.add(b.z)
            repeat(2) {
                colors.add(color.x); colors.add(color.y); colors.add(color.z)
                alphas.add(alpha)
            }
            bounds.include(a)
            bounds.include(b)
        }
    }

    f.uniform_linearize(samplesPerCubic) { at, instr ->
        val p = vec(at.x, at.y, at.z)
        if (instr < 0) {
            flush()
            polyline.clear()
            polyline.add(p)
        } else {
            polyline.add(p)
        }
    }
    flush()
}

private fun appendFilledMesh(
    f: FLine,
    samplesPerCubic: Int,
    color: Vec4,
    positions: MutableList<Float>,
    colors: MutableList<Float>,
    alphas: MutableList<Float>,
    indices: MutableList<Int>,
    bounds: BoundsTracker
) {
    val alpha = color.w.coerceIn(0f, 1f)
    f.tessellate(samplesPerCubic) { verts, index ->
        val base = positions.size / 3
        var i = 0
        while (i < verts.size) {
            val x = verts[i]
            val y = verts[i + 1]
            val z = verts[i + 2]
            positions.add(x); positions.add(y); positions.add(z)
            colors.add(color.x); colors.add(color.y); colors.add(color.z)
            alphas.add(alpha)
            bounds.include(x, y, z)
            i += 3
        }
        index.forEach { indices.add(base + it) }
    }
}

private fun appendPoints(
    f: FLine,
    color: Vec4,
    positions: MutableList<Float>,
    colors: MutableList<Float>,
    alphas: MutableList<Float>,
    sizes: MutableList<Float>,
    bounds: BoundsTracker
) {
    val alpha = color.w.coerceIn(0f, 1f)
    f.nodes.forEach { node ->
        if (node is FLine.Close) return@forEach
        val p = node.to
        positions.add(p.x); positions.add(p.y); positions.add(p.z)
        colors.add(color.x); colors.add(color.y); colors.add(color.z)
        alphas.add(alpha)
        val size = ((node.pointSize ?: f.pointSize) * 3.0).toFloat()
        sizes.add(size.coerceAtLeast(0.5f))
        bounds.include(p)
    }
}
