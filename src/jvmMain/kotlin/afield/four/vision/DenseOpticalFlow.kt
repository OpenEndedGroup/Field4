package afield.four.vision

import afield.four.Renderer
import afield.four.targets.RenderToTexture
import afield.four.texture.HasImageView

/** Which implementation computes a [DenseOpticalFlow]. */
enum class OpticalFlowBackend {
    /** [HARDWARE] when the device has VK_NV_optical_flow enabled, [PYRAMID] everywhere else. */
    AUTO,
    /** [OpticalFlow]: the dedicated VK_NV_optical_flow engine (NVIDIA Ada and later). */
    HARDWARE,
    /** [PyramidOpticalFlow]: coarse-to-fine Lucas–Kanade in ordinary shader passes; any Vulkan device, MoltenVK included. */
    PYRAMID;
    /** The backend [AUTO] would pick on this renderer; other values are returned unchanged. */
    fun resolve(renderer: Renderer): OpticalFlowBackend =
        if(this==AUTO) (if(renderer.device.supportsOpticalFlow) HARDWARE else PYRAMID) else this
}

/** A dense motion field between two same-sized images of one renderer, kept on the GPU.
 *
 * Every backend honours one output contract, so a consumer never needs to know which one ran:
 * [output] is an RGBA32F [RenderToTexture] of [flowWidth] × [flowHeight] = ceil(width/[grid]) × ceil(height/[grid]).
 * `.xy` is [first] → [second] displacement in INPUT pixels, X right and Y down; `.z` is a matching cost where lower
 * is better (its scale is backend-specific, see [backend]); `.w` is 1.
 *
 * Call [evaluate] between Field frames, after the producers of both inputs have submitted; it returns once
 * [output] is ready to sample. The inputs are borrowed — keep them alive, and at their construction size,
 * until [close].
 */
interface DenseOpticalFlow : AutoCloseable {
    val renderer: Renderer
    val first: HasImageView
    val second: HasImageView
    val grid: Int
    val width: Int
    val height: Int
    val flowWidth: Int
    val flowHeight: Int
    /** The backend that actually runs, never [OpticalFlowBackend.AUTO]. */
    val backend: OpticalFlowBackend
    /** Sampled RGBA32F motion field and cost, owned by this operation. */
    val output: RenderToTexture
    /** Wall time of recent [evaluate] calls, including submission and the final fence. */
    val milliseconds: ArrayDeque<Double>
    /** Named GPU timestamp samples (milliseconds) for this backend's stages; empty lists without timestamps. */
    val gpuTimings: Map<String,List<Double>>
    /** Bytes of the images and buffers this operation allocated (driver-private memory excluded). */
    val allocatedBytes: Long
    /** Compute [output] from the current contents of [first] and [second]. [reset] marks a cut: no temporal hints. */
    fun evaluate(reset: Boolean=false)
}

/** Dense optical flow from [first] to [second] on whichever backend [backend] resolves to on this device.
 *
 * ```kotlin
 * val flow = opticalFlow(renderer, previousImage, currentImage, grid = 4)
 * prop<RenderToTexture>(motion) = flow.output
 * flow.evaluate()                 // between frames, after both images were rendered
 * ```
 * [OpticalFlowBackend.HARDWARE] on a device without VK_NV_optical_flow fails, exactly as constructing [OpticalFlow] does.
 */
fun opticalFlow(renderer: Renderer,first: HasImageView,second: HasImageView,grid: Int=4,
    backend: OpticalFlowBackend=OpticalFlowBackend.AUTO): DenseOpticalFlow = when(backend.resolve(renderer)) {
    OpticalFlowBackend.HARDWARE -> OpticalFlow(renderer,first,second,grid)
    else -> PyramidOpticalFlow(renderer,first,second,grid)
}
