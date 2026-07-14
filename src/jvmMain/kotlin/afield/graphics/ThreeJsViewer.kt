package afield.graphics

import org.joml.translate
import afield.*
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
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
data class ThreeEditableNode(
    val nodeIndex: Int,
    val kind: String,
    val to: FloatArray? = null,
    val c1: FloatArray? = null,
    val c2: FloatArray? = null
)

@Serializable
data class ThreeEditableLine(
    val lineIndex: Int,
    val color: FloatArray,
    val thickness: Float,
    val stroked: Boolean,
    val filled: Boolean,
    val pointed: Boolean,
    val pointSize: Float,
    val callbackId: String = "",
    val hoverColor: FloatArray? = null,
    val nodes: List<ThreeEditableNode>
)

@Serializable
data class ThreeEditableScene(
    val sessionId: String,
    val lines: List<ThreeEditableLine>
)

/**
 * Geometry + interaction metadata for a single interactive FLine inside the Three.js viewer.
 *
 * Each interactive FLine (one with [FLine.onClick] or [FLine.hoverColor] set) is serialised
 * as a separate object rather than being batched with the rest of the scene geometry.  This
 * allows the viewer to run Three.js raycasting per-object and fire the registered server-side
 * callback when the user clicks on that specific FLine.
 */
@Serializable
data class ThreeInteractiveObject(
    /** Server-side callback ID. Empty when this object only has hover (no click). */
    val callbackId: String,
    /** Optional hover override colour as [r, g, b, a] each in [0, 1]. */
    val hoverColor: FloatArray? = null,
    val lines: ThreeGeometryData? = null,
    val fills: ThreeGeometryData? = null,
    val points: ThreeGeometryData? = null,
    val vertices: List<ThreeInteractiveVertex> = emptyList()
) {
    override fun toString(): String = prettySummary(
        "ThreeInteractiveObject",
        "callbackId" to callbackId,
        "hoverColor" to hoverColor?.contentToString(),
        "lines" to (lines != null),
        "fills" to (fills != null),
        "points" to (points != null),
        "vertices" to vertices.size
    )
}

@Serializable
data class ThreeInteractiveVertex(
    val index: Int,
    val position: FloatArray
)

@Serializable
data class ThreeGeometryPayload(
    val type: String = "three/geometry",
    val uid: String,
    val lines: ThreeGeometryData? = null,
    val fills: ThreeGeometryData? = null,
    val points: ThreeGeometryData? = null,
    val bounds: ThreeBounds? = null,
    val meta: ThreeMeta? = null,
    val autoFrame: Boolean = true,
    /** Per-FLine interactive objects (separate geometry with click/hover callbacks). */
    val interactive: List<ThreeInteractiveObject> = emptyList(),
    val scene: ThreeEditableScene? = null,
    val editableEnabled: Boolean = false
) {
    override fun toString(): String = prettySummary(
        "ThreeGeometryPayload",
        "uid" to uid,
        "lines" to (lines != null),
        "fills" to (fills != null),
        "points" to (points != null),
        "bounds" to bounds,
        "meta" to meta,
        "autoFrame" to autoFrame,
        "interactive" to interactive.size,
        "scene" to (scene != null),
        "editableEnabled" to editableEnabled
    )
}

private val threeJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
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
        // Global listeners (native "subscribe-all" viewers) see every view's updates, including
        // newly-created views: threeLines() calls update() right after creating the view.
        threeGlobalListeners.forEach { sendTo(it, encoded) }
    }

    private fun sendTo(session: WebSocketSession, payload: String) {
        inMain {
            runCatching { session.send(payload) }
        }
    }

    /** Broadcast the last payload to a newly-attached global listener (replay on subscribe-all). */
    fun replayTo(session: WebSocketSession) {
        lastPayload?.let { sendTo(session, it) }
    }

    override fun toString(): String = prettySummary(
        "ThreeGeometryViewState",
        "uid" to uid,
        "sessions" to sessions.size,
        "hasPayload" to (lastPayload != null)
    )
}

private val knownThreeViews = ConcurrentHashMap<String, ThreeGeometryViewState>()

/**
 * Sessions that asked to see *every* live Three.js view, not just one keyed by uid (the web viewer
 * always knows its uid from the iframe URL; a native/headless client that wants to discover live
 * views does not). [ThreeGeometryViewState.update] broadcasts to these in addition to a view's own
 * attached sessions, so a global listener receives new views the moment they publish.
 */
private val threeGlobalListeners = CopyOnWriteArraySet<WebSocketSession>()

/** Remove a session from the global-listener set — called from the `/ws` finally block. */
fun detachThreeGlobalListener(session: WebSocketSession) {
    threeGlobalListeners.remove(session)
}

@RunsAt(installHandlers)
@OptIn(DelicateCoroutinesApi::class)
fun installThreeJsHandler() {
    rawHandlers.getOrPut(THREE_CHANNEL) { mutableSetOf() }.add { text ->
        val session = thisSession.get()
        val payload = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@add Handled.no
        val channel = payload["channel"]?.jsonPrimitive?.contentOrNull ?: return@add Handled.no
        if (channel != THREE_CHANNEL) return@add Handled.no
        val uid = payload["debug_uid"]?.jsonPrimitive?.contentOrNull ?: return@add Handled.no

        when (payload["type"]?.jsonPrimitive?.contentOrNull) {
            "fline-click" -> {
                val callbackId = payload["callbackId"]?.jsonPrimitive?.contentOrNull
                    ?: return@add Handled.no
                val closestVertexIndex = payload["closestVertexIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                val x = payload["x"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val y = payload["y"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val z = payload["z"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val callback = getFLineClickCallback(callbackId)
                if (callback != null) {
                    val intersection = FLineIntersection(
                        closestVertexIndex = closestVertexIndex,
                        position = vec(x, y, z)
                    )
                    GlobalScope.launch {
                        runCatching { callback(intersection) }
                    }
                }
            }
            "fline-edit-translate" -> {
                val update = runCatching { threeJson.decodeFromJsonElement<FLineTranslatePoints>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val editSession = getFLineEditSession(update.sessionId) ?: return@add Handled.no
                GlobalScope.launch {
                    runCatching { editSession.translate(update) }
                }
            }
            "fline-edit-add-line" -> {
                val update = runCatching { threeJson.decodeFromJsonElement<FLineAddLine>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val editSession = getFLineEditSession(update.sessionId) ?: return@add Handled.no
                GlobalScope.launch {
                    runCatching { editSession.addLine(update) }
                }
            }
            "fline-edit-begin-line" -> {
                val update = runCatching { threeJson.decodeFromJsonElement<FLineBeginLine>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val editSession = getFLineEditSession(update.sessionId) ?: return@add Handled.no
                GlobalScope.launch {
                    runCatching { editSession.beginLine(update) }
                }
            }
            "fline-edit-append-line" -> {
                val update = runCatching { threeJson.decodeFromJsonElement<FLineAppendLine>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val editSession = getFLineEditSession(update.sessionId) ?: return@add Handled.no
                GlobalScope.launch {
                    runCatching { editSession.appendLine(update) }
                }
            }
            "fline-edit-end-line" -> {
                val update = runCatching { threeJson.decodeFromJsonElement<FLineEndLine>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val editSession = getFLineEditSession(update.sessionId) ?: return@add Handled.no
                GlobalScope.launch {
                    runCatching { editSession.endLine(update) }
                }
            }
            "fline-edit-clear" -> {
                val update = runCatching { threeJson.decodeFromJsonElement<FLineClearSession>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val editSession = getFLineEditSession(update.sessionId) ?: return@add Handled.no
                GlobalScope.launch {
                    runCatching { editSession.clear() }
                }
            }
            "subscribe-all" -> {
                // A discovery client (e.g. the native visionOS viewer) that wants every live view,
                // not one uid. Register it as a global listener and replay all known views' last
                // payloads immediately; future updates arrive via update()'s global broadcast.
                threeGlobalListeners.add(session)
                knownThreeViews.values.forEach { it.replayTo(session) }
            }
            else -> {
                // Default: treat as a hello/attach message
                val viewer = knownThreeViews.computeIfAbsent(uid) { ThreeGeometryViewState(uid) }
                viewer.attach(session)
            }
        }
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
    autoFrame: Boolean = true,
    editable: Boolean = false
): ThreeGeometryViewState {
    val uid = "${this.id}_$name"
    val ln = "/threejs?uid=$uid"
    display(
        channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0;width:100%;height:${height}px'>
"""
    )
    val view = knownThreeViews.computeIfAbsent(uid) { ThreeGeometryViewState(uid) }
    val payload = buildThreeGeometryPayload(uid, lines, samplesPerCubic, autoFrame, editable)
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
    autoFrame: Boolean = true,
    editable: Boolean = false
): ThreeGeometryViewState = threeLines(
    lines = lines.toList(),
    channel = channel,
    name = name,
    height = height,
    samplesPerCubic = samplesPerCubic,
    autoFrame = autoFrame,
    editable = editable
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
    autoFrame: Boolean = true,
    editable: Boolean = false
): ThreeGeometryViewState = threeLines(
    lines = linesByChannel.values.flatten(),
    channel = channel,
    name = name,
    height = height,
    samplesPerCubic = samplesPerCubic,
    autoFrame = autoFrame,
    editable = editable
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
    autoFrame: Boolean,
    editable: Boolean
): ThreeGeometryPayload {
    val sourceLines = lines.toList()
    val editSession = if (editable) registerFLineEditSession(sourceLines, null) else null
    val samples = samplesPerCubic.coerceAtLeast(2)
    val callbackIds = sourceLines.map { line ->
        line.onClick?.let { cb ->
            val id = "fline-${UUID.randomUUID()}"
            registerFLineClickCallback(id, cb)
            id
        } ?: ""
    }
    val hoverColors = sourceLines.map { line ->
        line.hoverColor?.let { c -> floatArrayOf(c.x, c.y, c.z, c.w) }
    }

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
    val geometryBounds = BoundsTracker()
    val interactiveObjects = ArrayList<ThreeInteractiveObject>()

    if (!editable) {
        sourceLines.forEachIndexed { lineIndex, f ->
            val callbackId = callbackIds[lineIndex]
            val hoverColor = hoverColors[lineIndex]
            if (callbackId.isNotEmpty() || hoverColor != null) {
                val localLinePositions = ArrayList<Float>()
                val localLineColors = ArrayList<Float>()
                val localLineAlphas = ArrayList<Float>()
                val localMeshPositions = ArrayList<Float>()
                val localMeshColors = ArrayList<Float>()
                val localMeshAlphas = ArrayList<Float>()
                val localMeshIndices = ArrayList<Int>()
                val localPointPositions = ArrayList<Float>()
                val localPointColors = ArrayList<Float>()
                val localPointAlphas = ArrayList<Float>()
                val localPointSizes = ArrayList<Float>()

                appendFLineGeometry(
                    f = f,
                    samplesPerCubic = samples,
                    linePositions = localLinePositions,
                    lineColors = localLineColors,
                    lineAlphas = localLineAlphas,
                    meshPositions = localMeshPositions,
                    meshColors = localMeshColors,
                    meshAlphas = localMeshAlphas,
                    meshIndices = localMeshIndices,
                    pointPositions = localPointPositions,
                    pointColors = localPointColors,
                    pointAlphas = localPointAlphas,
                    pointSizes = localPointSizes,
                    bounds = geometryBounds
                )

                interactiveObjects += ThreeInteractiveObject(
                    callbackId = callbackId,
                    hoverColor = hoverColor,
                    lines = lineGeometryOrNull(localLinePositions, localLineColors, localLineAlphas),
                    fills = fillGeometryOrNull(localMeshPositions, localMeshColors, localMeshAlphas, localMeshIndices),
                    points = pointGeometryOrNull(localPointPositions, localPointColors, localPointAlphas, localPointSizes),
                    vertices = interactiveVertices(f)
                )
            } else {
                appendFLineGeometry(
                    f = f,
                    samplesPerCubic = samples,
                    linePositions = linePositions,
                    lineColors = lineColors,
                    lineAlphas = lineAlphas,
                    meshPositions = meshPositions,
                    meshColors = meshColors,
                    meshAlphas = meshAlphas,
                    meshIndices = meshIndices,
                    pointPositions = pointPositions,
                    pointColors = pointColors,
                    pointAlphas = pointAlphas,
                    pointSizes = pointSizes,
                    bounds = geometryBounds
                )
            }
        }
    }

    val sceneBounds = BoundsTracker()
    var sceneLineSegments = 0
    var sceneTriangles = 0
    var scenePoints = 0

    val scene = if (editable) ThreeEditableScene(
        sessionId = editSession?.sessionId ?: "",
        lines = sourceLines.mapIndexed { lineIndex, line ->
            var boundaryPoints = 0
            var openBoundaryPoints = 0

            ThreeEditableLine(
                lineIndex = lineIndex,
                color = floatArrayOf(line.color.x, line.color.y, line.color.z, line.color.w),
                thickness = (line.thickness ?: 1.0).toFloat(),
                stroked = line.stroked,
                filled = line.filled,
                pointed = line.pointed,
                pointSize = line.pointSize.toFloat(),
                callbackId = callbackIds[lineIndex],
                hoverColor = hoverColors[lineIndex],
                nodes = line.nodes.mapIndexed { nodeIndex, node ->
                    when (node) {
                        is FLine.MoveTo -> {
                            if (line.filled && openBoundaryPoints >= 3) {
                                sceneTriangles += (openBoundaryPoints - 2)
                            }
                            scenePoints += 1
                            boundaryPoints = 1
                            openBoundaryPoints = 1
                            sceneBounds.include(node.to)
                            ThreeEditableNode(
                                nodeIndex = nodeIndex,
                                kind = "move",
                                to = floatArrayOf(node.to.x, node.to.y, node.to.z)
                            )
                        }

                        is FLine.LineTo -> {
                            scenePoints += 1
                            if (boundaryPoints > 0) sceneLineSegments += 1
                            boundaryPoints += 1
                            openBoundaryPoints = boundaryPoints
                            sceneBounds.include(node.to)
                            ThreeEditableNode(
                                nodeIndex = nodeIndex,
                                kind = "line",
                                to = floatArrayOf(node.to.x, node.to.y, node.to.z)
                            )
                        }

                        is FLine.CubicTo -> {
                            scenePoints += 1
                            if (boundaryPoints > 0) sceneLineSegments += samples
                            boundaryPoints += samples
                            openBoundaryPoints = boundaryPoints
                            sceneBounds.include(node.to)
                            sceneBounds.include(node.c1)
                            sceneBounds.include(node.c2)
                            ThreeEditableNode(
                                nodeIndex = nodeIndex,
                                kind = "cubic",
                                to = floatArrayOf(node.to.x, node.to.y, node.to.z),
                                c1 = floatArrayOf(node.c1.x, node.c1.y, node.c1.z),
                                c2 = floatArrayOf(node.c2.x, node.c2.y, node.c2.z)
                            )
                        }

                        is FLine.Close -> {
                            if (boundaryPoints > 1) sceneLineSegments += 1
                            if (line.filled && boundaryPoints >= 3) {
                                sceneTriangles += (boundaryPoints - 2)
                            }
                            boundaryPoints = 0
                            openBoundaryPoints = 0
                            ThreeEditableNode(
                                nodeIndex = nodeIndex,
                                kind = "close"
                            )
                        }
                    }
                }
            )
        }
    ).also {
        if (editable) {
            sourceLines.forEach { line ->
                val openCount = countOpenBoundaryPoints(line, samples)
                if (line.filled && openCount >= 3) {
                    sceneTriangles += (openCount - 2)
                }
            }
        }
    } else null

    val lineGeom = lineGeometryOrNull(linePositions, lineColors, lineAlphas)
    val fillGeom = fillGeometryOrNull(meshPositions, meshColors, meshAlphas, meshIndices)
    val pointGeom = pointGeometryOrNull(pointPositions, pointColors, pointAlphas, pointSizes)

    return ThreeGeometryPayload(
        uid = uid,
        lines = lineGeom,
        fills = fillGeom,
        points = pointGeom,
        bounds = if (editable) sceneBounds.toBounds() else geometryBounds.toBounds(),
        meta = ThreeMeta(
            lineSegments = if (editable) sceneLineSegments else linePositions.size / 6 + interactiveObjects.sumOf { it.lines?.attributes?.get("position")?.array?.size?.div(6) ?: 0 },
            triangles = if (editable) sceneTriangles else meshIndices.size / 3 + interactiveObjects.sumOf { it.fills?.index?.array?.size?.div(3) ?: 0 },
            points = if (editable) scenePoints else pointPositions.size / 3 + interactiveObjects.sumOf { it.points?.attributes?.get("position")?.array?.size?.div(3) ?: 0 }
        ),
        autoFrame = autoFrame,
        interactive = interactiveObjects,
        scene = scene,
        editableEnabled = editable
    )
}

private fun lineGeometryOrNull(
    positions: List<Float>,
    colors: List<Float>,
    alphas: List<Float>
): ThreeGeometryData? = if (positions.isNotEmpty()) {
    buildGeometryData(
        positions = positions.toFloatArray(),
        colors = colors.toFloatArray(),
        alphas = alphas.toFloatArray(),
        sizes = null,
        indices = null
    )
} else null

private fun fillGeometryOrNull(
    positions: List<Float>,
    colors: List<Float>,
    alphas: List<Float>,
    indices: List<Int>
): ThreeGeometryData? = if (positions.isNotEmpty()) {
    buildGeometryData(
        positions = positions.toFloatArray(),
        colors = colors.toFloatArray(),
        alphas = alphas.toFloatArray(),
        sizes = null,
        indices = indices.toIntArray()
    )
} else null

private fun pointGeometryOrNull(
    positions: List<Float>,
    colors: List<Float>,
    alphas: List<Float>,
    sizes: List<Float>
): ThreeGeometryData? = if (positions.isNotEmpty()) {
    buildGeometryData(
        positions = positions.toFloatArray(),
        colors = colors.toFloatArray(),
        alphas = alphas.toFloatArray(),
        sizes = sizes.toFloatArray(),
        indices = null
    )
} else null

private fun appendFLineGeometry(
    f: FLine,
    samplesPerCubic: Int,
    linePositions: MutableList<Float>,
    lineColors: MutableList<Float>,
    lineAlphas: MutableList<Float>,
    meshPositions: MutableList<Float>,
    meshColors: MutableList<Float>,
    meshAlphas: MutableList<Float>,
    meshIndices: MutableList<Int>,
    pointPositions: MutableList<Float>,
    pointColors: MutableList<Float>,
    pointAlphas: MutableList<Float>,
    pointSizes: MutableList<Float>,
    bounds: BoundsTracker
) {
    val color = f.color
    if (f.stroked) {
        appendLineSegments(f, samplesPerCubic, color, linePositions, lineColors, lineAlphas, bounds)
    }
    if (f.filled) {
        appendFilledMesh(f, samplesPerCubic, color, meshPositions, meshColors, meshAlphas, meshIndices, bounds)
    }
    if (f.pointed) {
        appendPoints(f, color, pointPositions, pointColors, pointAlphas, pointSizes, bounds)
    }
}

private fun interactiveVertices(f: FLine): List<ThreeInteractiveVertex> =
    f.nodes.mapIndexedNotNull { index, node ->
        if (node is FLine.Close) null
        else ThreeInteractiveVertex(index, floatArrayOf(node.to.x, node.to.y, node.to.z))
    }

private fun countOpenBoundaryPoints(f: FLine, samplesPerCubic: Int): Int {
    var current = 0
    f.nodes.forEach { node ->
        when (node) {
            is FLine.MoveTo -> {
                current = 1
            }
            is FLine.LineTo -> {
                current += 1
            }
            is FLine.CubicTo -> {
                current += samplesPerCubic
            }
            is FLine.Close -> {
                current = 0
            }
        }
    }
    return current
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
