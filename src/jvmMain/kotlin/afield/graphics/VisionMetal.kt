package afield.graphics

import afield.Box
import afield.Handled
import afield.RunsAt
import afield.inMain
import afield.installHandlers
import afield.rawHandlers
import afield.notify
import afield.thisSession
import afield.stdlib.Action
import afield.stdlib.Delegation
import afield.stdlib.Inheritance
import afield.stdlib.actions
import afield.stdlib.fragment
import afield.stdlib.prettySummary
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

private const val VISION_METAL_CHANNEL = "__visionmetal__"

/**
 * Wire version 2 splits the single monolithic program payload into four independently revisioned
 * parts — shader, script, uniforms, geometry — so that reloading one does not disturb the others.
 * Version 1 restarted the JavaScriptCore context, the geometry buffers and the frame clock on every
 * shader edit; a v2 shader reload keeps all three resident.
 *
 * A version-1 head cannot read a version-2 payload: rebuild Field4Vision when this changes.
 */
const val VISION_METAL_VERSION = 2

/**
 * Floats per wire vertex: four `float4`s — position, normal, colour, then uv plus two free
 * per-vertex channels. This is exactly `MemoryLayout<LiveMetalVertex>.stride / 4`, so the native
 * head copies the wire array straight into its vertex buffer.
 */
const val VISION_METAL_VERTEX_FLOATS = 16

/** Metal binds one argument-table slot per named uniform; the ABI reserves sixteen `float4`s. */
const val VISION_METAL_MAX_UNIFORMS = 16

/**
 * One authored vertex. `position.w` and `normal.w` are supplied by the packer, so the fourteen
 * authored floats become the sixteen the head expects.
 */
@Serializable
data class VisionMetalVertex(
    val x: Float, val y: Float, val z: Float,
    val nx: Float = 0f, val ny: Float = 0f, val nz: Float = 1f,
    val r: Float = 1f, val g: Float = 1f, val b: Float = 1f, val a: Float = 1f,
    val u: Float = 0f, val v: Float = 0f,
    val extra0: Float = 0f, val extra1: Float = 0f,
)

/** Who owns the vertex buffer currently resident in the head. */
@Serializable
enum class VisionMetalGeometryOrigin {
    /** The `vertices`/`indices` arrays in this payload. */
    @SerialName("wire") WIRE,

    /** The head's built-in cube — now an explicit request rather than an empty-geometry fallback. */
    @SerialName("builtin") BUILTIN,

    /** Whatever the box's Kotlin/JS `geometry { … }` builder last uploaded across the JS bridge. */
    @SerialName("script") SCRIPT,
}

@Serializable
data class VisionMetalShaderPart(val revision: Int, val source: String)

@Serializable
data class VisionMetalScriptPart(val revision: Int, val source: String, val launchable: Boolean)

/** Declared slot order. The head turns these into `#define u_NAME frame.user[i]` for the shader. */
@Serializable
data class VisionMetalUniformsPart(val revision: Int, val names: List<String>)

@Serializable
data class VisionMetalGeometryPart(
    val revision: Int,
    val origin: VisionMetalGeometryOrigin,
    val vertices: FloatArray = floatArrayOf(),
    val indices: IntArray = intArrayOf(),
) {
    // FloatArray/IntArray members give this data class reference equality; geometry parts are only
    // ever compared by revision, so spell that out rather than leaving a surprising default.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = revision
}

/**
 * One program update. Absent parts are unchanged — the head keeps whatever it already has resident.
 * A shader-only reload therefore carries `shader` and nothing else.
 */
@Serializable
data class VisionMetalProgramPayload(
    val channel: String = VISION_METAL_CHANNEL,
    val type: String = "visionmetal/program",
    val version: Int = VISION_METAL_VERSION,
    val uid: String,
    val revision: Int,
    val shader: VisionMetalShaderPart? = null,
    val script: VisionMetalScriptPart? = null,
    val uniforms: VisionMetalUniformsPart? = null,
    val geometry: VisionMetalGeometryPart? = null,
)

@Serializable
data class VisionMetalStatus(
    val channel: String,
    val type: String,
    val version: Int,
    val debug_uid: String,
    val uid: String,
    val revision: Int,
    val phase: String,
    val ok: Boolean,
    val message: String,
    /**
     * One-based line in the part named by [phase], when the head could recover one. Metal compiler
     * diagnostics carry user-relative lines because the head emits `#line 1` after its preamble.
     */
    val line: Int = 0,
)

/**
 * The wire encoder. `encodeDefaults` is load-bearing twice over: it puts `channel`/`type`/`version`
 * on every message, and it spells absent parts as explicit nulls, which is how the head tells
 * "unchanged" from "cleared". Public so tests can assert against the real bytes rather than a
 * lookalike configuration.
 */
val visionMetalWireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private val knownVisionMetalPrograms = ConcurrentHashMap<String, VisionMetalProgramState>()
private val visionMetalGlobalListeners = CopyOnWriteArraySet<WebSocketSession>()
@Volatile private var lastVisionMetalProgramUID: String? = null

/**
 * Metal Shading Language source for a native visionOS head: both entry points (`field_vertex` and
 * `field_fragment`) in one property, since Metal compiles them from one translation unit.
 *
 * This replaces the earlier proof-of-concept's use of [afield.stdlib.fragment], which names a GLSL
 * fragment stage and cannot describe a vertex function.
 */
var Box.msl by Inheritance<String?>()

/** Which program name this box's reload commands are currently bound to. */
private var Box._visionMetalActionsFor by Delegation<String?>()

/** Set once the box has been told its Metal source belongs in `msl` rather than `fragment`. */
private var Box._visionMetalWarnedAboutFragment by Delegation<Boolean?>()

/** Number of native Live Metal subscribers currently attached to the replayable program channel. */
fun visionMetalConnectedHeadCount(): Int = visionMetalGlobalListeners.size

/**
 * A durable program resident in the native visionOS Metal renderer.
 *
 * Each part carries its own revision so the head can apply exactly what changed: [updateShader]
 * recompiles the pipeline while the JavaScriptCore context, the vertex buffers and the frame clock
 * stay alive, and [updateGeometry] replaces buffers without recompiling. The program revision is
 * monotonic across all parts and is what status messages correlate against.
 */
class VisionMetalProgramState(val uid: String) {
    private val revision = AtomicInteger(0)
    val currentRevision: Int get() = revision.get()
    private val statusListeners = CopyOnWriteArrayList<(VisionMetalStatus) -> Unit>()

    private val lock = Any()
    private var shaderPart: VisionMetalShaderPart? = null
    private var scriptPart: VisionMetalScriptPart? = null
    private var uniformsPart: VisionMetalUniformsPart? = null
    private var geometryPart: VisionMetalGeometryPart? = null

    @Volatile var lastStatus: VisionMetalStatus? = null
        private set

    /** Declared uniform slot order, in the order the shader's `u_NAME` defines will use. */
    val declaredUniforms: List<String> get() = synchronized(lock) { uniformsPart?.names ?: emptyList() }

    /** The MSL currently resident in the head, or null if none has been published. */
    val shaderSource: String? get() = synchronized(lock) { shaderPart?.source }

    /** The compiled Kotlin/JS currently resident in the head, or null. */
    val scriptSource: String? get() = synchronized(lock) { scriptPart?.source }

    /** Whether the resident script was published in Launch mode. */
    val scriptIsLaunchable: Boolean get() = synchronized(lock) { scriptPart?.launchable ?: false }

    /** Who owns the resident vertex buffers. */
    val geometryOrigin: VisionMetalGeometryOrigin?
        get() = synchronized(lock) { geometryPart?.origin }

    /** Recompile the pipeline only. Geometry, uniform slots, the JS context and the clock survive. */
    fun updateShader(source: String): VisionMetalProgramState = publish { next ->
        shaderPart = VisionMetalShaderPart(next, source)
        VisionMetalProgramPayload(uid = uid, revision = next, shader = shaderPart)
    }

    /** Reinstall the JavaScriptCore program. This is the one part whose reload restarts the clock. */
    fun updateScript(source: String, launchable: Boolean = true): VisionMetalProgramState = publish { next ->
        scriptPart = VisionMetalScriptPart(next, source, launchable)
        VisionMetalProgramPayload(uid = uid, revision = next, script = scriptPart)
    }

    /**
     * Redeclare the named uniform slots. The head regenerates the shader's `u_NAME` defines from
     * this list, so it recompiles the pipeline — but leaves script, clock and geometry alone.
     */
    fun updateUniforms(names: List<String>): VisionMetalProgramState {
        requireValidUniforms(names)
        return publish { next ->
            uniformsPart = VisionMetalUniformsPart(next, names)
            VisionMetalProgramPayload(uid = uid, revision = next, uniforms = uniformsPart)
        }
    }

    /** Replace the resident vertex buffers from the JVM side. */
    fun updateGeometry(
        vertices: Collection<VisionMetalVertex>,
        indices: IntArray,
    ): VisionMetalProgramState {
        requireValidGeometry(vertices, indices)
        return publish { next ->
            geometryPart = VisionMetalGeometryPart(
                revision = next,
                origin = VisionMetalGeometryOrigin.WIRE,
                vertices = packVisionMetalVertices(vertices),
                indices = indices,
            )
            VisionMetalProgramPayload(uid = uid, revision = next, geometry = geometryPart)
        }
    }

    /** Ask for the head's built-in cube explicitly. */
    fun useBuiltinGeometry(): VisionMetalProgramState = updateGeometryOrigin(VisionMetalGeometryOrigin.BUILTIN)

    /** Hand the vertex buffers to the box's Kotlin/JS `geometry { … }` builder. */
    fun useScriptGeometry(): VisionMetalProgramState = updateGeometryOrigin(VisionMetalGeometryOrigin.SCRIPT)

    private fun updateGeometryOrigin(origin: VisionMetalGeometryOrigin): VisionMetalProgramState = publish { next ->
        geometryPart = VisionMetalGeometryPart(revision = next, origin = origin)
        VisionMetalProgramPayload(uid = uid, revision = next, geometry = geometryPart)
    }

    /**
     * Publish every part at once. Rerunning a Field box takes this path; the individual `update*`
     * methods are what the explicit reload actions use.
     */
    fun update(
        shader: String,
        script: String = "",
        uniforms: List<String> = emptyList(),
        vertices: Collection<VisionMetalVertex> = emptyList(),
        indices: IntArray = intArrayOf(),
        launchable: Boolean = true,
        geometry: VisionMetalGeometryOrigin =
            if (vertices.isNotEmpty()) VisionMetalGeometryOrigin.WIRE else VisionMetalGeometryOrigin.SCRIPT,
    ): VisionMetalProgramState {
        requireValidUniforms(uniforms)
        if (geometry == VisionMetalGeometryOrigin.WIRE) requireValidGeometry(vertices, indices)
        return publish { next ->
            shaderPart = VisionMetalShaderPart(next, shader)
            scriptPart = VisionMetalScriptPart(next, script, launchable)
            uniformsPart = VisionMetalUniformsPart(next, uniforms)
            geometryPart = VisionMetalGeometryPart(
                revision = next,
                origin = geometry,
                vertices = if (geometry == VisionMetalGeometryOrigin.WIRE) {
                    packVisionMetalVertices(vertices)
                } else {
                    floatArrayOf()
                },
                indices = if (geometry == VisionMetalGeometryOrigin.WIRE) indices else intArrayOf(),
            )
            VisionMetalProgramPayload(
                uid = uid,
                revision = next,
                shader = shaderPart,
                script = scriptPart,
                uniforms = uniformsPart,
                geometry = geometryPart,
            )
        }
    }

    fun onStatus(listener: (VisionMetalStatus) -> Unit): VisionMetalProgramState {
        statusListeners += listener
        lastStatus?.let(listener)
        return this
    }

    /** A late subscriber needs every resident part, not just the one that changed most recently. */
    internal fun replayTo(session: WebSocketSession) {
        val payload = synchronized(lock) {
            if (shaderPart == null && scriptPart == null && uniformsPart == null && geometryPart == null) {
                return
            }
            visionMetalWireJson.encodeToString(
                VisionMetalProgramPayload(
                    uid = uid,
                    revision = revision.get(),
                    shader = shaderPart,
                    script = scriptPart,
                    uniforms = uniformsPart,
                    geometry = geometryPart,
                ),
            )
        }
        sendTo(session, payload)
    }

    internal fun acceptStatus(status: VisionMetalStatus) {
        lastStatus = status
        statusListeners.forEach { listener -> runCatching { listener(status) } }
    }

    private inline fun publish(build: (Int) -> VisionMetalProgramPayload): VisionMetalProgramState {
        val encoded = synchronized(lock) {
            visionMetalWireJson.encodeToString(build(revision.incrementAndGet()))
        }
        lastVisionMetalProgramUID = uid
        visionMetalGlobalListeners.forEach { sendTo(it, encoded) }
        return this
    }

    private fun sendTo(session: WebSocketSession, payload: String) {
        inMain { runCatching { session.send(payload) } }
    }

    override fun toString(): String = prettySummary(
        "VisionMetalProgramState",
        "uid" to uid,
        "revision" to revision.get(),
        "uniforms" to declaredUniforms,
        "status" to lastStatus?.message,
    )
}

private fun requireValidUniforms(names: List<String>) {
    require(names.size <= VISION_METAL_MAX_UNIFORMS) {
        "Vision Metal supports at most $VISION_METAL_MAX_UNIFORMS named float4 uniforms, got ${names.size}"
    }
    require(names.distinct().size == names.size) { "Vision Metal uniform names must be unique" }
    names.forEach { name ->
        require(name.isNotBlank() && name.all { it == '_' || it.isLetterOrDigit() } && !name.first().isDigit()) {
            "Vision Metal uniform name '$name' must be a Metal identifier: letters, digits and underscore"
        }
    }
}

private fun requireValidGeometry(vertices: Collection<VisionMetalVertex>, indices: IntArray) {
    if (vertices.isEmpty()) return
    require(indices.isNotEmpty()) { "Vision Metal custom vertices require triangle indices" }
    require(indices.size % 3 == 0) { "Vision Metal indices must describe whole triangles, got ${indices.size}" }
    require(indices.all { it in vertices.indices }) { "Vision Metal index outside vertex array" }
}

private fun packVisionMetalVertices(vertices: Collection<VisionMetalVertex>): FloatArray {
    val packed = FloatArray(vertices.size * VISION_METAL_VERTEX_FLOATS)
    vertices.forEachIndexed { i, v ->
        val o = i * VISION_METAL_VERTEX_FLOATS
        packed[o] = v.x; packed[o + 1] = v.y; packed[o + 2] = v.z; packed[o + 3] = 1f
        packed[o + 4] = v.nx; packed[o + 5] = v.ny; packed[o + 6] = v.nz; packed[o + 7] = 0f
        packed[o + 8] = v.r; packed[o + 9] = v.g; packed[o + 10] = v.b; packed[o + 11] = v.a
        packed[o + 12] = v.u; packed[o + 13] = v.v; packed[o + 14] = v.extra0; packed[o + 15] = v.extra1
    }
    return packed
}

fun detachVisionMetalGlobalListener(session: WebSocketSession) {
    visionMetalGlobalListeners.remove(session)
}

@RunsAt(installHandlers)
fun installVisionMetalHandler() {
    rawHandlers.getOrPut(VISION_METAL_CHANNEL) { mutableSetOf() }.add { text ->
        val payload = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@add Handled.no
        if (payload["channel"]?.jsonPrimitive?.contentOrNull != VISION_METAL_CHANNEL) return@add Handled.no
        if (payload["version"]?.jsonPrimitive?.content?.toIntOrNull() != VISION_METAL_VERSION) return@add Handled.yes

        when (payload["type"]?.jsonPrimitive?.contentOrNull) {
            "subscribe-all" -> {
                val session = thisSession.get()
                visionMetalGlobalListeners += session
                // The native immersive renderer presents one active program. Replay the last program
                // updated, rather than activating every historical box in arbitrary map order.
                lastVisionMetalProgramUID?.let { knownVisionMetalPrograms[it]?.replayTo(session) }
            }
            "visionmetal/status" -> {
                val status = runCatching {
                    visionMetalWireJson.decodeFromJsonElement<VisionMetalStatus>(payload)
                }.getOrNull() ?: return@add Handled.yes
                knownVisionMetalPrograms[status.uid]?.acceptStatus(status)
                val marker = if (status.ok) "ok" else "error"
                val where = if (status.line > 0) ":${status.line}" else ""
                println("[vision-metal/$marker] ${status.uid} r${status.revision} ${status.phase}$where: ${status.message}")
            }
        }
        Handled.yes
    }
}

/** The uid the `name`d program of this box publishes under. */
fun Box.visionMetalUID(name: String = "live-metal"): String = "${this.id}_$name"

/** The resident program for this box, or null if it has never published one. */
fun Box.visionMetalProgram(name: String = "live-metal"): VisionMetalProgramState? =
    knownVisionMetalPrograms[visionMetalUID(name)]

/** The resident program for this box, created on first use. */
fun Box.visionMetalProgramOrCreate(name: String = "live-metal"): VisionMetalProgramState =
    knownVisionMetalPrograms.computeIfAbsent(visionMetalUID(name)) { VisionMetalProgramState(it) }

/**
 * Any resident program by its wire uid (`<boxId>_<name>`), without needing the [Box].
 *
 * The reload commands go through the box; this is for reaching a program from somewhere that only has
 * the uid off the wire — a driver box, an agent transcript, or the simulator pre-smoke.
 */
fun visionMetalProgramByUID(uid: String): VisionMetalProgramState? = knownVisionMetalPrograms[uid]

/**
 * The MSL this box means, most specific first: its own [msl] property, then the deprecated use of
 * [afield.stdlib.fragment] that the proof-of-concept relied on, then whatever the head already has.
 */
fun Box.visionMetalShaderSource(name: String = "live-metal"): String? {
    msl?.takeIf { it.isNotBlank() }?.let { return it }
    fragment?.takeIf { it.isNotBlank() }?.let { authored ->
        // Once per box, not once per reload — this fires on every publish and every shader edit.
        if (_visionMetalWarnedAboutFragment != true) {
            _visionMetalWarnedAboutFragment = true
            notify(
                "Box '${this.name}' still keeps its Metal source in `fragment`; move it to `msl`",
                "vision-metal",
            )
        }
        return authored
    }
    return visionMetalProgram(name)?.shaderSource
}

/**
 * Explicit reload commands for a box with a live Metal program.
 *
 * Each republishes exactly one part, so the head applies exactly that: reloading MSL recompiles the
 * pipeline while the JavaScriptCore context, the geometry and the frame clock all stay resident.
 * Installed by [Box.visionMetal] and by [visionMetalProgramOrCreate], so any box that has ever
 * published a program carries them — including one whose Kotlin has since stopped.
 */
fun Box.installVisionMetalActions(name: String = "live-metal") {
    // Reading `actions` queues a UI resync, so don't reinstall on every publish — a shader reload
    // would otherwise cost a resync message per keystroke-driven recompile. Reinstall only if this
    // box has started publishing under a different program name.
    if (_visionMetalActionsFor == name) return
    _visionMetalActionsFor = name
    val existing = actions

    // Shares the `alt r` chord with the Vulkan/GLSL reload: the two never appear on the same box,
    // because that box's shader source lives in a different property.
    existing["visionMetal_reload"] = Action(
        "Reload MSL|alt r",
        "Recompiles this box's Metal shader on the visionOS head, leaving its script, geometry and clock running",
    ) { boxes -> boxes.forEach { it.reloadVisionMetalShader(name) } }

    existing["visionMetal_reload_uniforms"] = Action(
        "Reload Vision Uniforms",
        "Republishes the declared uniform slot order, regenerating the shader's u_NAME defines",
    ) { boxes -> boxes.forEach { it.reloadVisionMetalUniforms(name) } }

    existing["visionMetal_reload_program"] = Action(
        "Reload Vision Program",
        "Republishes MSL, uniforms, geometry ownership and the resident script together",
    ) { boxes -> boxes.forEach { it.reloadVisionMetalProgram(name) } }

    existing["visionMetal_geometry_builtin"] = Action(
        "Vision Geometry: Built-in Cube",
        "Replaces this box's geometry with the head's built-in cube — a known-good mesh for checking a shader",
    ) { boxes ->
        boxes.forEach { box ->
            box.visionMetalProgram(name)?.useBuiltinGeometry()
            notify("Vision geometry: built-in cube in '${box.name}'", "vision-metal")
        }
    }
}

/** Recompile this box's MSL on the head. Script, geometry and clock survive. */
fun Box.reloadVisionMetalShader(name: String = "live-metal"): Boolean {
    val state = visionMetalProgram(name) ?: run {
        notify("No live Metal program in '${this.name}' yet — run the box first", "vision-metal")
        return false
    }
    val source = visionMetalShaderSource(name)
    if (source.isNullOrBlank()) {
        notify("No Metal source in '${this.name}': write the shader into its `msl` property", "vision-metal")
        return false
    }
    state.updateShader(source)
    notify("Reloading MSL in '${this.name}'…", "vision-metal")
    return true
}

/** Redeclare the uniform slot order, regenerating the shader's `u_NAME` defines. */
fun Box.reloadVisionMetalUniforms(
    name: String = "live-metal",
    uniforms: List<String>? = null,
): Boolean {
    val state = visionMetalProgram(name) ?: return false
    val names = uniforms ?: state.declaredUniforms
    state.updateUniforms(names)
    notify(
        if (names.isEmpty()) {
            "No uniforms declared in '${this.name}'"
        } else {
            "Vision uniforms in '${this.name}': ${names.joinToString(", ")}"
        },
        "vision-metal",
    )
    return true
}

/** Republish every resident part, re-reading MSL from the box. */
fun Box.reloadVisionMetalProgram(name: String = "live-metal"): Boolean {
    val state = visionMetalProgram(name) ?: run {
        notify("No live Metal program in '${this.name}' yet — run the box first", "vision-metal")
        return false
    }
    val source = visionMetalShaderSource(name) ?: state.shaderSource ?: ""
    state.update(
        shader = source,
        script = state.scriptSource.orEmpty(),
        uniforms = state.declaredUniforms,
        launchable = state.scriptIsLaunchable,
        geometry = state.geometryOrigin ?: VisionMetalGeometryOrigin.SCRIPT,
    )
    notify("Reloading vision program in '${this.name}'…", "vision-metal")
    return true
}

/**
 * Publish a live MSL + Kotlin/JS program to the visionOS native renderer.
 *
 * `shader` defines `field_vertex` and `field_fragment` against the ABI documented in the visionOS
 * README, and reads uniforms by name as `u_NAME` — the head generates those defines from
 * [uniforms]. In launch mode, `script` may install `onStart`, `onFrame` and `onStop`, build geometry
 * with `geometry { … }`, and write declared uniforms with `uniform(name, …)`.
 *
 * Geometry defaults to [VisionMetalGeometryOrigin.SCRIPT] — the box's Kotlin/JS builder owns the
 * vertex buffers. Pass `vertices`/`indices` to publish a mesh from the JVM instead, or
 * `geometry = VisionMetalGeometryOrigin.BUILTIN` to ask for the head's cube.
 */
fun Box.visionMetal(
    shader: String,
    script: String = "",
    uniforms: List<String> = emptyList(),
    vertices: Collection<VisionMetalVertex> = emptyList(),
    indices: IntArray = intArrayOf(),
    name: String = "live-metal",
    launchable: Boolean = true,
    geometry: VisionMetalGeometryOrigin =
        if (vertices.isNotEmpty()) VisionMetalGeometryOrigin.WIRE else VisionMetalGeometryOrigin.SCRIPT,
): VisionMetalProgramState {
    installVisionMetalActions(name)
    return visionMetalProgramOrCreate(name).update(
        shader = shader,
        script = script,
        uniforms = uniforms,
        vertices = vertices,
        indices = indices,
        launchable = launchable,
        geometry = geometry,
    )
}

/**
 * A complete hot-reloadable shader for the head's built-in cube; a live-coding starting point.
 *
 * Uniforms are read by their declared names. `uniforms = listOf("phase", "amount", "tint")` makes
 * the head emit `#define u_phase frame.user[0]` and so on ahead of this source.
 */
val visionMetalExampleShader = """
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float phase = u_phase.x;
    float amount = u_amount.x;
    float3 centre = float3(0.45, 0.0, -1.2);
    float3 p = v.position.xyz + v.normal.xyz * sin(phase + v.position.y * 18.0) * amount - centre;
    float3 n = v.normal.xyz;
    float ay = phase * 0.18 + 0.55;
    float cy = cos(ay), sy = sin(ay);
    p.xz = float2(cy * p.x + sy * p.z, -sy * p.x + cy * p.z);
    n.xz = float2(cy * n.x + sy * n.z, -sy * n.x + cy * n.z);
    float ax = 0.35;
    float cx = cos(ax), sx = sin(ax);
    p.yz = float2(cx * p.y - sx * p.z, sx * p.y + cx * p.z);
    n.yz = float2(cx * n.y - sx * n.z, sx * n.y + cx * n.z);
    float4 world = frame.modelMatrix * float4(p + centre, 1.0);
    FieldVarying out;
    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
    out.worldPosition = world.xyz;
    out.normal = n;
    out.color = v.color;
    out.uv = v.uv.xy;
    return out;
}

fragment half4 field_fragment(FieldVarying in [[stage_in]], constant FieldFrame &frame [[buffer(1)]])
{
    float4 tint = u_tint;
    float3 rgb = mix(in.color.rgb, tint.rgb, tint.a);
    return half4(half3(rgb), half(in.color.a));
}
""".trimIndent()

/**
 * The raw-JavaScript companion to [visionMetalExampleShader], for the JVM-side [Box.visionMetal]
 * entry point. It only writes uniforms, so pair it with `geometry =
 * VisionMetalGeometryOrigin.BUILTIN` (or with `vertices`/`indices`) to give the shader something to
 * draw. Kotlin/JS boxes should use [visionMetalExampleKotlinScript] instead.
 */
val visionMetalExampleScript = """
uniform("amount", 0.025)
uniform("tint", 0.1, 0.8, 1.0, 0.35)
onFrame((time, dt, frameNumber, predictedDisplayTime) => {
    uniform("phase", time * 3.0)
})
""".trimIndent()

/**
 * The Kotlin/JS companion to [visionMetalExampleShader]: builds its own geometry in the head, then
 * drives `u_phase` from the compositor clock. This is the shape a `// {{ visionmetal }}` box takes.
 */
val visionMetalExampleKotlinScript = """
onStart {
    geometry {
        color(1.0, 0.35, 0.15)
        cube(0.18, cx = 0.45, cz = -1.2, tintFaces = true)
    }
    uniform("amount", 0.025)
    uniform("tint", 0.1, 0.8, 1.0, 0.35)
    onFrame { frame -> uniform("phase", frame.time * 3.0) }
}
""".trimIndent()
