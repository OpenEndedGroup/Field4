package afield.graphics

import afield.*
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
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
    val nodes: List<ThreeEditableNode>,
    /** The line's [FLine.label], so an editable figure's hits name themselves too. */
    val label: String? = null,
    /** [flineTopologyJson] for this line — the same string the non-editable path sends. */
    val topology: String = ""
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
    val vertices: List<ThreeInteractiveVertex> = emptyList(),
    /** Index of this FLine among the lines the viewer was given, reported back as `lineIndex`. */
    val lineIndex: Int = -1,
    /**
     * Which assembly group this whole object belongs to, or `-1` when the payload has no inspector.
     *
     * An interactive object is one FLine, so its group is uniform and the viewer moves it by
     * `object.position` rather than by rewriting a per-vertex buffer — same offsets, same region
     * function, a great deal less work per slider frame.
     */
    val group: Int = -1,
    /** The line's [FLine.label], reported back with a hit so one closure can serve a whole figure. */
    val label: String? = null,
    /**
     * The line's segments and closed subpaths as [flineTopologyJson] writes them — the *same* string
     * the interactive SVG puts in `data-fline-elements`, so the two clickable outputs cannot describe
     * the same line differently. Only their picking differs, which it must: one has a click in a plane,
     * the other a raycast hit that already knows whether it landed on a line, a fill or a point.
     */
    val topology: String = ""
) {
    override fun toString(): String = prettySummary(
        "ThreeInteractiveObject",
        "callbackId" to callbackId,
        "hoverColor" to hoverColor?.contentToString(),
        "lines" to (lines != null),
        "fills" to (fills != null),
        "points" to (points != null),
        "vertices" to vertices.size,
        "lineIndex" to lineIndex,
        "label" to label
    )
}

@Serializable
data class ThreeInteractiveVertex(
    val index: Int,
    val position: FloatArray
)

/**
 * A click on a surface drawable, as the viewer posts it — the sibling of [FLineClick].
 *
 * A second wire type rather than more optional fields on [FLineClick], for one reason that settles it:
 * [FLineClick] lives in `rpc.kt` and is shared with the 2D SVG output, which cannot ever produce a
 * `faceIndex`. Widening it would put five permanently-null fields on every SVG click and would make the
 * `three-surface-click` case indistinguishable from a very old browser posting a sparse `fline-click`.
 *
 * Every field is defaulted, so a viewer that predates a field it does not know about still decodes.
 * `null` rather than `NaN` for the optional doubles, for the same reason [FLineClick] does it: JSON has
 * no NaN and a browser that sends one produces a payload the server cannot parse at all.
 */
@Serializable
data class ThreeSurfaceClick(
    val callbackId: String,
    /** [ThreeDrawable.id]. */
    val id: String = "",
    val label: String? = null,
    val drawableIndex: Int = -1,
    /** `vertex`, `edge`, `triangle`, or empty for the classifier's refusal. */
    val kind: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val faceIndex: Int = -1,
    val vertexIndex: Int = -1,
    val edgeIndex: Int = -1,
    val u: Double? = null,
    val v: Double? = null,
    val w: Double? = null,
    /** The point on the named element. */
    val ex: Double? = null,
    val ey: Double? = null,
    val ez: Double? = null,
    val frontFacing: Boolean = true,
    /** The hit triangle's nine ordinates, `[ax, ay, az, bx, …]`, or empty. */
    val triangle: List<Double> = emptyList(),
)

/**
 * The one place a posted [ThreeSurfaceClick] becomes the [ThreeSurfaceHit] a callback sees.
 *
 * Modelled line for line on `FLineClick.toIntersection()` in `outputs.kt`, including the "absent
 * optional fields keep the record's own defaults" rule that lets an older viewer keep working.
 */
internal fun ThreeSurfaceClick.toHit(): ThreeSurfaceHit = ThreeSurfaceHit(
    id = id,
    label = label,
    drawableIndex = drawableIndex,
    kind = when (kind.lowercase()) {
        "vertex" -> ThreeSurfaceHitKind.VERTEX
        "edge" -> ThreeSurfaceHitKind.EDGE
        "triangle", "face" -> ThreeSurfaceHitKind.TRIANGLE
        else -> ThreeSurfaceHitKind.UNKNOWN
    },
    position = afield.stdlib.vec(x, y, z),
    onElement = if (ex != null && ey != null) afield.stdlib.vec(ex, ey, ez ?: 0.0) else null,
    faceIndex = faceIndex,
    vertexIndex = vertexIndex,
    edgeIndex = edgeIndex,
    u = u ?: Double.NaN,
    v = v ?: Double.NaN,
    w = w ?: Double.NaN,
    frontFacing = frontFacing,
    triangle = if (triangle.size >= 9) {
        (0 until 3).map { afield.stdlib.vec(triangle[it * 3], triangle[it * 3 + 1], triangle[it * 3 + 2]) }
    } else emptyList(),
)

/**
 * Server-side click callbacks for surface drawables.
 *
 * A registry of its own rather than a second map in `outputs.kt`'s, because the value type differs —
 * `(ThreeSurfaceHit) -> Unit` against `(FLineIntersection) -> Unit` — and a shared map keyed by a
 * prefix convention would be a cast waiting to be got wrong. The `three-surface-` id prefix is what
 * makes the two namespaces visibly disjoint in a log.
 */
private val surfaceClickCallbacks = ConcurrentHashMap<String, (ThreeSurfaceHit) -> Unit>()

internal fun registerThreeSurfaceClickCallback(callback: (ThreeSurfaceHit) -> Unit): String {
    val id = "three-surface-${UUID.randomUUID()}"
    surfaceClickCallbacks[id] = callback
    return id
}

internal fun getThreeSurfaceClickCallback(callbackId: String): ((ThreeSurfaceHit) -> Unit)? =
    surfaceClickCallbacks[callbackId]

@Serializable
@OptIn(ExperimentalSerializationApi::class)
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
    val editableEnabled: Boolean = false,
    /**
     * Measure / section / explode, when the payload came from [Box.threeInspect].
     *
     * `null` for an ordinary [Box.threeLines] payload, and the viewer then behaves exactly as it did
     * before this existed — no HUD, no clipping planes, no kept copy of the base positions.
     */
    val inspector: ThreeInspector? = null,
    /**
     * The scene as [ThreeDrawable]s — geometry plus per-drawable material state.
     *
     * Empty for every payload that predates [Box.three], and the viewer then takes exactly the path
     * it always took: `lines` / `fills` / `points` above are unchanged, and a payload with no
     * drawables encodes byte for byte as it did before this field existed.
     * `ThreeScenePayloadCompatibilityTest` is what holds that claim, rather than the comment.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val drawables: List<ThreeDrawable> = emptyList(),
    /**
     * The viewports, when this is a contact sheet. Empty or one-element means a single full-bleed view.
     *
     * See [ThreePanel] for why a panel is a scissored viewport rather than a second iframe.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val panels: List<ThreePanel> = emptyList(),
    /** Panels per row, when there is more than one. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val columns: Int = 1,
    /** Scene background as a CSS colour, or `null` for the viewer's transparent default. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val background: String? = null,
    /**
     * Draw the reference grid and the axis tripod.
     *
     * On for FLines, which is what the viewer has always done. **Off for meshes**, because both
     * helpers are a fixed world size — a 10-unit grid and a 0.9-unit tripod — and a mesh is whatever
     * size its file says. The toy car in `geometry_from_disk.md` is 7cm across, so the grid would be
     * 140 times its width and the tripod would swallow it whole. The CPU rasterizer draws neither,
     * and a figure that changes renderer should not sprout furniture.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val helpers: Boolean = true,
    /**
     * The frame size this figure wants, in CSS pixels, for the viewer to report to its host.
     *
     * # Why the viewer has to say it out loud
     *
     * A box output panel sizes itself to its content, and for an iframe it learns the content size
     * from an `afield-iframe-size` event the iframe dispatches on itself (`outputareas2.js`,
     * `bindContentLoadEvents`). Every other embedded viewer gets this from `/iframe_autosize.js`;
     * `threejs_viewer.html` is the one that never included it, because its body is `height: 100%` with
     * `overflow: hidden` and measuring it would only ever report back whatever size it already had.
     *
     * The consequence went unnoticed while the viewer only drew FLines, which want to fill their
     * column anyway. It became very visible when the mesh figures arrived: a six-panel contact sheet
     * asking for 855 pixels of height was silently drawn in 430, and each cell came out about a
     * hundred pixels tall.
     *
     * So the size is sent, and the viewer publishes it. `null` means "say nothing", which is exactly
     * what every payload did before this existed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val frameWidth: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val frameHeight: Int? = null,
    /**
     * Where the camera starts, when the caller picked an angle rather than accepting [autoFrame].
     *
     * A docs figure chooses its viewpoint deliberately — half of `geometry_from_disk.md`'s value is
     * that the toy car is seen from the one angle that shows the draped cloth — so the switch from
     * the rasterizer had to bring [MeshView]'s four numbers with it.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val camera: ThreeCameraSpec? = null
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
        "editableEnabled" to editableEnabled,
        "inspector" to (inspector != null),
        "drawables" to drawables.size,
        "panels" to panels.size,
        "camera" to camera
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
 * The view for a uid, created on first use.
 *
 * `internal` so [Box.three] can register a view the same way [Box.threeLines] does. Going through one
 * function rather than two `computeIfAbsent` calls on a private map is what keeps `subscribe-all` and
 * the websocket attach path seeing every view, however it was made.
 */
internal fun threeViewFor(uid: String): ThreeGeometryViewState =
    knownThreeViews.computeIfAbsent(uid) { ThreeGeometryViewState(uid) }

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
                // Same wire type and same mapping as the SVG output's handler in `Server.kt`, so the
                // two clickable outputs cannot disagree about what a click returns. `threeJson`
                // ignores the envelope's own keys (`type`, `channel`, `debug_uid`).
                val click = runCatching { threeJson.decodeFromJsonElement<FLineClick>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val callback = getFLineClickCallback(click.callbackId)
                if (callback != null) {
                    val intersection = click.toIntersection()
                    GlobalScope.launch {
                        runCatching { callback(intersection) }
                    }
                }
            }
            "three-surface-click" -> {
                // The mesh half of the same idea, and deliberately the same five lines: decode, look
                // the callback up, hand it the record, never let a user closure's exception reach the
                // websocket read loop.
                val click = runCatching { threeJson.decodeFromJsonElement<ThreeSurfaceClick>(payload) }.getOrNull()
                    ?: return@add Handled.no
                val callback = getThreeSurfaceClickCallback(click.callbackId)
                if (callback != null) {
                    val hit = click.toHit()
                    GlobalScope.launch {
                        runCatching { callback(hit) }
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
    val ln = threeViewerIframeSource(uid)
    display(
        channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0;width:100%;height:${height}px'>
"""
    )
    val view = threeViewFor(uid)
    val payload = buildThreeGeometryPayload(uid, lines, samplesPerCubic, autoFrame, editable)
    view.update(payload)
    return view
}

/**
 * The `src` of a viewer iframe, for a view uid.
 *
 * The uid is `"${box.id}_$name"`, and `name` is a caller's prose that lands in a **single-quoted**
 * `src` attribute as well as in a query string. An apostrophe — `name = "the client's triangulation"`
 * — closed the attribute early, so the iframe loaded with no `uid` at all and the viewer sat on
 * "waiting for data…" forever, with no error in the browser console, the server log or the box output.
 * Found 2026-08-04 by taking a capture to the pixels and wondering why two of three panes were blank.
 *
 * [URLEncoder] is form encoding, so a space becomes `+`. That is exactly what the client decodes:
 * `URLSearchParams` (`threejs_viewer.html:212`, `:2044`) turns `+` back into a space and `%27` back
 * into an apostrophe, so the uid round-trips byte for byte and the `__threejs__` routing key still
 * matches. The server's `/threejs` route never reads the parameter at all (`Server.kt:4450`).
 *
 * `internal` so `ThreeJsViewerIframeSourceTest` can hold the encoding to that contract.
 */
internal fun threeViewerIframeSource(uid: String): String =
    "/threejs?uid=" + URLEncoder.encode(uid, "UTF-8")

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

internal class BoundsTracker {
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

/**
 * The payload both viewer modes are built from.
 *
 * `internal` rather than private so `ThreeJsEditableFillParityTest` can assert the one number the two
 * modes must agree on: `meta.triangles`. Editable mode re-triangulates client-side (the user drags
 * points, and the fill has to follow between round-trips), so the two triangulations are written
 * twice — here, and as `tessellateCoplanarGroups` in `threejs_viewer.html`.
 *
 * This count is the cheap guard. The real one is `ThreeJsViewerTessellationParityTest`, which *executes*
 * the viewer's JavaScript on GraalJS and compares it to [PlanarTessellation] triangle for triangle; see
 * `ViewerJavaScript`.
 *
 * @param groupKeys one assembly-group name per source line, or `null` for an ordinary payload. When
 * present, every vertex of the batched geometry gets a `group` attribute holding the index of its
 * line's group in [distinctGroupNames], and every interactive object gets a scalar `group`. That
 * attribute is what the viewer's `inspector-explode` region displaces.
 *
 * The per-line vertex ranges are *recorded* — the buffer length before and after each line is
 * appended — rather than threaded through [appendFLineGeometry] and its five callees as another
 * out-parameter. The result is identical and five signatures stay the length they are.
 *
 * @param inspector the measure / section / explode block, or `null`. With `null` the payload is byte
 * for byte what it was before any of this existed, and the viewer takes its old path.
 */
internal fun buildThreeGeometryPayload(
    uid: String,
    lines: Collection<FLine>,
    samplesPerCubic: Int,
    autoFrame: Boolean,
    editable: Boolean,
    groupKeys: List<String>? = null,
    inspector: ThreeInspector? = null
): ThreeGeometryPayload {
    val sourceLines = lines.toList()
    require(groupKeys == null || groupKeys.size == sourceLines.size) {
        "one assembly-group key per line, please: ${groupKeys!!.size} keys for ${sourceLines.size} lines"
    }
    val groupNames = groupKeys?.let { distinctGroupNames(it) }
    val groupOfLine = groupKeys?.map { groupNames!!.indexOf(it) }
    val lineGroups = if (groupOfLine != null) ArrayList<Float>() else null
    val meshGroups = if (groupOfLine != null) ArrayList<Float>() else null
    val pointGroups = if (groupOfLine != null) ArrayList<Float>() else null
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
                    group = groupOfLine?.get(lineIndex) ?: -1,
                    callbackId = callbackId,
                    hoverColor = hoverColor,
                    lines = lineGeometryOrNull(localLinePositions, localLineColors, localLineAlphas),
                    fills = fillGeometryOrNull(localMeshPositions, localMeshColors, localMeshAlphas, localMeshIndices),
                    points = pointGeometryOrNull(localPointPositions, localPointColors, localPointAlphas, localPointSizes),
                    vertices = interactiveVertices(f),
                    lineIndex = lineIndex,
                    label = f.label,
                    // Only a clickable line needs it; hover has nothing to say about which element.
                    topology = if (callbackId.isNotEmpty()) flineTopologyJson(f) else ""
                )
            } else {
                val lineVerticesBefore = linePositions.size / 3
                val meshVerticesBefore = meshPositions.size / 3
                val pointVerticesBefore = pointPositions.size / 3
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
                if (groupOfLine != null) {
                    val group = groupOfLine[lineIndex].toFloat()
                    repeat(linePositions.size / 3 - lineVerticesBefore) { lineGroups!!.add(group) }
                    repeat(meshPositions.size / 3 - meshVerticesBefore) { meshGroups!!.add(group) }
                    repeat(pointPositions.size / 3 - pointVerticesBefore) { pointGroups!!.add(group) }
                }
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
                label = line.label,
                topology = if (callbackIds[lineIndex].isNotEmpty()) flineTopologyJson(line) else "",
                nodes = line.nodes.mapIndexed { nodeIndex, node ->
                    when (node) {
                        is FLine.MoveTo -> {
                            scenePoints += 1
                            boundaryPoints = 1
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
                            boundaryPoints = 0
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
            // Editable mode re-triangulates client-side, with the same rule this side runs
            // (`threejs_viewer.html`, `tessellateCoplanarGroups`), so the reported triangle count is
            // the real one. It used to be a per-subpath fan estimate — `boundaryPoints - 2` — which
            // both over-counted a holed face and under-counted a multi-plane one, and disagreed with
            // the number the same line reports with `editable = false`.
            sourceLines.forEach { line ->
                if (line.filled) {
                    line.tessellate(samples) { _, indices -> sceneTriangles += indices.size / 3 }
                }
            }
        }
    } else null

    val lineGeom = lineGeometryOrNull(linePositions, lineColors, lineAlphas).withGroups(lineGroups)
    val fillGeom = fillGeometryOrNull(meshPositions, meshColors, meshAlphas, meshIndices).withGroups(meshGroups)
    val pointGeom = pointGeometryOrNull(pointPositions, pointColors, pointAlphas, pointSizes)
        .withGroups(pointGroups)

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
        editableEnabled = editable,
        inspector = inspector?.copy(groups = groupNames ?: inspector.groups)
    )
}

/**
 * Attach the per-vertex assembly-group index, when there is one.
 *
 * A separate pass rather than another parameter on the three `…GeometryOrNull` builders: the group of a
 * vertex is a property of *which line appended it*, which those builders do not know and should not
 * have to. The count is asserted here rather than trusted, because a group array one vertex short of
 * the position array is a silent off-by-one that three.js renders as a plausible-looking wrong
 * explosion — the vertices simply pick up their neighbour's group.
 */
private fun ThreeGeometryData?.withGroups(groups: List<Float>?): ThreeGeometryData? {
    if (this == null || groups == null || groups.isEmpty()) return this
    val vertexCount = (attributes["position"]?.array?.size ?: 0) / 3
    check(groups.size == vertexCount) {
        "the assembly-group attribute has ${groups.size} entries for $vertexCount vertices"
    }
    return copy(attributes = attributes + ("group" to ThreeAttribute(itemSize = 1, array = groups.toFloatArray())))
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
