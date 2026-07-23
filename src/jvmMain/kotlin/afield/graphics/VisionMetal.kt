package afield.graphics

import afield.Box
import afield.Handled
import afield.RunsAt
import afield.inMain
import afield.installHandlers
import afield.rawHandlers
import afield.thisSession
import afield.stdlib.prettySummary
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
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
private const val VISION_METAL_VERSION = 1

/** Ten floats on the wire: position, normal, then colour. */
@Serializable
data class VisionMetalVertex(
    val x: Float, val y: Float, val z: Float,
    val nx: Float = 0f, val ny: Float = 0f, val nz: Float = 1f,
    val r: Float = 1f, val g: Float = 1f, val b: Float = 1f, val a: Float = 1f
)

@Serializable
data class VisionMetalProgramPayload(
    val channel: String = VISION_METAL_CHANNEL,
    val type: String = "visionmetal/program",
    val version: Int = VISION_METAL_VERSION,
    val uid: String,
    val revision: Int,
    val shader: String,
    val script: String = "",
    val uniforms: List<String> = emptyList(),
    val vertices: FloatArray = floatArrayOf(),
    val indices: IntArray = intArrayOf()
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
    val message: String
)

private val visionMetalJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private val knownVisionMetalPrograms = ConcurrentHashMap<String, VisionMetalProgramState>()
private val visionMetalGlobalListeners = CopyOnWriteArraySet<WebSocketSession>()
@Volatile private var lastVisionMetalProgramUID: String? = null

/** Number of native Live Metal subscribers currently attached to the replayable program channel. */
fun visionMetalConnectedHeadCount(): Int = visionMetalGlobalListeners.size

/**
 * A durable program resident in the native visionOS Metal renderer. Rerunning a Field box updates
 * this state; the headset compiles new MSL asynchronously and swaps pipelines at a frame boundary.
 */
class VisionMetalProgramState(val uid: String) {
    private val revision = AtomicInteger(0)
    private val statusListeners = CopyOnWriteArrayList<(VisionMetalStatus) -> Unit>()
    @Volatile private var lastPayload: String? = null
    @Volatile var lastStatus: VisionMetalStatus? = null
        private set

    fun update(
        shader: String,
        script: String = "",
        uniforms: List<String> = emptyList(),
        vertices: Collection<VisionMetalVertex> = emptyList(),
        indices: IntArray = intArrayOf()
    ): VisionMetalProgramState {
        require(uniforms.size <= 16) { "Vision Metal currently supports at most 16 named float4 uniforms" }
        require(uniforms.distinct().size == uniforms.size) { "Vision Metal uniform names must be unique" }
        if (vertices.isNotEmpty()) {
            require(indices.isNotEmpty()) { "Vision Metal custom vertices require triangle indices" }
            require(indices.all { it in vertices.indices }) { "Vision Metal index outside vertex array" }
        }
        val packedVertices = FloatArray(vertices.size * 10)
        vertices.forEachIndexed { i, v ->
            val o = i * 10
            packedVertices[o] = v.x; packedVertices[o + 1] = v.y; packedVertices[o + 2] = v.z
            packedVertices[o + 3] = v.nx; packedVertices[o + 4] = v.ny; packedVertices[o + 5] = v.nz
            packedVertices[o + 6] = v.r; packedVertices[o + 7] = v.g
            packedVertices[o + 8] = v.b; packedVertices[o + 9] = v.a
        }
        val payload = VisionMetalProgramPayload(
            uid = uid,
            revision = revision.incrementAndGet(),
            shader = shader,
            script = script,
            uniforms = uniforms.take(16),
            vertices = packedVertices,
            indices = indices
        )
        val encoded = visionMetalJson.encodeToString(payload)
        lastPayload = encoded
        lastVisionMetalProgramUID = uid
        visionMetalGlobalListeners.forEach { sendTo(it, encoded) }
        return this
    }

    fun onStatus(listener: (VisionMetalStatus) -> Unit): VisionMetalProgramState {
        statusListeners += listener
        lastStatus?.let(listener)
        return this
    }

    internal fun replayTo(session: WebSocketSession) {
        lastPayload?.let { sendTo(session, it) }
    }

    internal fun acceptStatus(status: VisionMetalStatus) {
        lastStatus = status
        statusListeners.forEach { listener -> runCatching { listener(status) } }
    }

    private fun sendTo(session: WebSocketSession, payload: String) {
        inMain { runCatching { session.send(payload) } }
    }

    override fun toString(): String = prettySummary(
        "VisionMetalProgramState",
        "uid" to uid,
        "revision" to revision.get(),
        "status" to lastStatus?.message
    )
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
                    visionMetalJson.decodeFromJsonElement<VisionMetalStatus>(payload)
                }.getOrNull() ?: return@add Handled.yes
                knownVisionMetalPrograms[status.uid]?.acceptStatus(status)
                val marker = if (status.ok) "ok" else "error"
                println("[vision-metal/$marker] ${status.uid} r${status.revision} ${status.phase}: ${status.message}")
            }
        }
        Handled.yes
    }
}

/**
 * Publish a live MSL + JavaScript program to the visionOS native renderer.
 *
 * `shader` defines `field_vertex` and `field_fragment` against the ABI documented in the visionOS
 * README. `script` may install `onFrame((time, dt, predictedDisplayTime) -> ...)` and write declared
 * float4 uniforms with `uniform(name, x, y, z, w)` or `uniforms.name = [x, y, z, w]`.
 */
fun Box.visionMetal(
    shader: String,
    script: String = "",
    uniforms: List<String> = emptyList(),
    vertices: Collection<VisionMetalVertex> = emptyList(),
    indices: IntArray = intArrayOf(),
    name: String = "live-metal"
): VisionMetalProgramState {
    val uid = "${this.id}_$name"
    val state = knownVisionMetalPrograms.computeIfAbsent(uid) { VisionMetalProgramState(uid) }
    return state.update(shader, script, uniforms, vertices, indices)
}

/** A complete hot-reloadable shader for the built-in cube; intended as a live-coding starting point. */
val visionMetalExampleShader = """
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float phase = fieldUniform(frame, 0).x;
    float amount = fieldUniform(frame, 1).x;
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
    return out;
}

fragment half4 field_fragment(FieldVarying in [[stage_in]], constant FieldFrame &frame [[buffer(1)]])
{
    float4 tint = fieldUniform(frame, 2);
    float3 rgb = mix(in.color.rgb, tint.rgb, tint.a);
    return half4(half3(rgb), half(in.color.a));
}
""".trimIndent()

val visionMetalExampleScript = """
uniform("amount", 0.025)
uniform("tint", 0.1, 0.8, 1.0, 0.35)
onFrame((time, dt, predictedDisplayTime) => {
    uniform("phase", time * 3.0)
})
""".trimIndent()
