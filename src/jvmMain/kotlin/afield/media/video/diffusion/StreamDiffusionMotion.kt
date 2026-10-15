package afield.media.video.diffusion

import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture

/** IDs refer to source render frames, including frames not accepted by inference. */
data class StreamDiffusionFrame(val id: Long, val timeNanos: Long, val generation: Long)
enum class StreamDiffusionMotionUse { DISPLAY, HISTORY, NOISE }
data class StreamDiffusionMotionRequest(
    val current: StreamDiffusionFrame, val reference: StreamDiffusionFrame,
    val use: StreamDiffusionMotionUse
)

/**
 * Optional renderer-owned backward correspondence. RGBA32F, same size as the source:
 * RG = absolute normalized UV in reference, B = confidence [0,1], A = reactive rejection [0,1].
 * UV uses the same orientation as sampling the source RTT; pixel centres are (xy+.5)/extent.
 * Confidence zero means unknown/disoccluded. Reject occluded surfaces using depth/object IDs.
 * Implementations may record a target here, reuse it between requests, or return null for unavailable
 * history. No CPU pixels. The returned target must be sampled and transfer-source capable.
 */
interface StreamDiffusionMotionProvider {
    /** Called after every source draw, before requests; retain only bounded frame state. */
    fun onFrame(frame: StreamDiffusionFrame) {}
    fun record(command: CommandBuffer, request: StreamDiffusionMotionRequest): RenderToTexture?
    /**
     * Optional display hole fill, called immediately after a successful DISPLAY record.
     * Return a sampled single-layer/single-sample RTT matching source extent and colour format,
     * or null to use the current source. referenceImage is the resolved stylized result at
     * request.reference; it is borrowed only for this call. The provider owns its fallback target.
     */
    fun recordFallback(command: CommandBuffer, request: StreamDiffusionMotionRequest,
        source: RenderToTexture, referenceImage: RenderToTexture): RenderToTexture? = null
}
