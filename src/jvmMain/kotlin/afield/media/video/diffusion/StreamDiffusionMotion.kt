package afield.media.video.diffusion

import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture

/**
 * The interface a renderer implements to give streaming diffusion per-pixel motion:
 * [StreamDiffusionMotionProvider], with the frame and request types it receives.
 *
 * A provider answers each [StreamDiffusionMotionRequest] with an RGBA32F texture mapping pixels of
 * the current frame back to a reference frame, plus confidence and rejection channels. The view
 * uses it for display reprojection, history blending and noise advection.
 */

/** IDs refer to source render frames, including frames not accepted by inference. */
data class StreamDiffusionFrame(
    /** Counts every frame the view rendered, whether or not it was sent to the worker. */
    val id: Long,
    /** That frame's `sourceTimeNanos` reading, which the worker returns with its result. */
    val timeNanos: Long,
    /** The session generation the frame belongs to; it changes on every reset. */
    val generation: Long
)
/**
 * What a correspondence is wanted for: putting the newest result on the frame being rendered,
 * blending that result with the one before it, or carrying the worker's latent noise along the
 * motion instead of drawing it afresh.
 */
enum class StreamDiffusionMotionUse { DISPLAY, HISTORY, NOISE }

/**
 * One question for the provider: where in an earlier frame were the pixels of a later one? A view
 * asks it up to three times a frame, once for each use, and never for a frame it has not announced.
 */
data class StreamDiffusionMotionRequest(
    /** The frame to map from: the one being rendered now, or a result that has just arrived. */
    val current: StreamDiffusionFrame,
    /** The frame to map to, which is always one the provider has already seen. */
    val reference: StreamDiffusionFrame,
    /** Which of the three the answer is for; they differ in how far [StreamDiffusionMotionRequest.reference] lies behind. */
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
    /**
     * Records the work that fills a correspondence texture for one request, and returns it, or null
     * when this pair of frames cannot be mapped. The target is checked against the source extent
     * and the RGBA32F format before anything samples it, so a wrong one is refused rather than read.
     */
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
