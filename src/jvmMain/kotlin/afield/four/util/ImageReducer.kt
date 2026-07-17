package afield.four.util

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.BufferParameters
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.buffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.TextureSampler
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import kotlin.math.ceil

/**
 * ImageReducer - reduces an image ([HasImageView]) to global summary statistics using two Vulkan
 * compute-shader passes.
 *
 * Reduction strategy:
 * 1. First pass: each 16x16 workgroup ([WORKGROUP_SIZE]) processes a tile of the image, reduces it in
 *    shared memory, and writes one intermediate record per workgroup.
 * 2. Second pass: a single 256-thread workgroup reduces all intermediate records (striding over
 *    workgroups > 256) into the final result.
 *
 * Workgroup counts are derived automatically from the image dimensions. Results are read back with
 * [readResults] and may be read one frame late in the per-frame path.
 *
 * ## Source binding model
 * The source image is bound as a *sampled* texture (`sampler2D`, or `sampler2DArray` for a
 * `VK_IMAGE_VIEW_TYPE_2D_ARRAY` view) and read with `texelFetch` — it is **not** bound as a storage
 * image and there is no `imageLoad`. This avoids mutating the source image's layout. Consequently the
 * source image must include `VK_IMAGE_USAGE_SAMPLED_BIT`; [ensureInitialized] requires this and throws
 * otherwise. Any color format that is valid for a sampled 2D texture works; there is no rgba8-only
 * restriction.
 *
 * ## Buffer layout
 * Both buffers are host-visible storage buffers sized from the companion constants:
 * - Intermediate buffer: `ceil(width/16) * ceil(height/16) * INTERMEDIATE_VALUES_PER_RESULT * 4` bytes
 *   (24 floats per workgroup: min.rgba, avgSum.rgba, max.rgba, then the pseudo-quartile accumulators
 *   lowLumaSum/lowCount/highLumaSum/highCount/midSatSum/midCount and lowRgbSum.rgb/highRgbSum.rgb).
 * - Result buffer: `FINAL_VALUES_PER_RESULT * 4` = 104 bytes (26 floats: see [ReductionResult]).
 *   For a 1920x1080 image the intermediate buffer is ~765 KB (8160 workgroups) and the result is 104 B.
 * Read the constants in this file rather than hard-coding these sizes elsewhere — they change in lockstep.
 *
 * ## Usage
 * The `renderer` parameter defaults to `fromContext()`, so `ImageReducer(imageView = ...)` is valid.
 * See [afield.examples.four.ImageReducerExample] for runnable one-shot and per-frame examples.
 * - One-shot (synchronous): [reduceNow] records a command buffer, submits, waits on a fence, returns.
 * - Per-frame: [installInto] adds the reduction to a [RenderTarget]'s `others` map; read with
 *   [readResults] (or use the callback overload, which fires one frame later once the fence has signalled).
 * - Manual: call [reduce] inside your own command-buffer recording, submit, then [readResults].
 *
 * ```
 * val reducer = ImageReducer(renderer, imageView)
 * reducer.reduce(commandBuffer)
 * // Later, after GPU execution has completed (fence wait):
 * val stats = reducer.readResults()
 * println(stats.contrastMichelson)
 * ```
 *
 * ## Notes and caveats
 * - [readResults] guards against a premature read: it throws `IllegalStateException` unless a reduction
 *   is known complete (after [reduceNow]'s fence wait, or when the [installInto] callback path knows the
 *   fence has signalled). [VulkanBuffer.mapAndRead] itself performs no fence check, so this readiness
 *   flag is what prevents reading stale/garbage data before the GPU has finished.
 * - Not thread-safe: intended to be driven from the render thread. This is a convention only — nothing
 *   in the class asserts or enforces it. Separate instances may target different images.
 */
class ImageReducer(
    val renderer: Renderer = fromContext(),
    private val imageView: HasImageView
) {
    companion object {
        // Workgroup size for the reduction shader (must match shader)
        private const val WORKGROUP_SIZE = 16
        private const val LOCAL_SIZE_X = WORKGROUP_SIZE
        private const val LOCAL_SIZE_Y = WORKGROUP_SIZE

        // Number of values per workgroup in the intermediate pass.
        // Layout:
        // min.rgba + avgSum.rgba + max.rgba +
        // lowLumaSum + lowCount + highLumaSum + highCount + midSatSum + midCount +
        // lowRgbSum.rgb + highRgbSum.rgb
        private const val INTERMEDIATE_VALUES_PER_RESULT = 24

        // Number of values in the final result.
        // Layout:
        // min.rgba + avg.rgba + max.rgba +
        // lowAvgLuma + highAvgLuma + midSatAvg + contrastRange + contrastMichelson + lowFrac + highFrac + midFrac +
        // lowAvgRgb.rgb + highAvgRgb.rgb
        private const val FINAL_VALUES_PER_RESULT = 26
    }



    // Buffers for intermediate and final results
    private var intermediateBufferParameters: BufferParameters? = null
    private var intermediateBuffer: VulkanBuffer? = null
    private var resultBufferParameters: BufferParameters? = null
    private var resultBuffer: VulkanBuffer? = null

    // Compute modules for the two-pass reduction
    private var firstPassModule: ComputeModulePointer? = null
    private var secondPassModule: ComputeModulePointer? = null

    // Cached dimensions
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cachedSourceViewType: Int = 0
    private var workgroupCountX: Int = 0
    private var workgroupCountY: Int = 0

    // Previous-frame luminance stats (used to gate pseudo-quartile style buckets on the next frame)
    private var hasPreviousLumaStats: Boolean = false
    private var previousMinLuma: Float = 0f
    private var previousAvgLuma: Float = 0.5f
    private var previousMaxLuma: Float = 1f

    // Track per-swapchain-image readiness so we can fire callbacks one frame later (after the fence signals).
    private var pendingCallbacksPerImage: BooleanArray? = null

    // Guards [readResults] against a premature read: set true once a reduction is known complete
    // (after [reduceNow]'s fence wait, or when the per-frame callback path knows the fence has
    // signalled) and reset to false whenever a new reduction is recorded via [reduce].
    private var resultsReady: Boolean = false

    // Sampling resources when the source image is only sampled
    private var textureSampler: TextureSampler? = null
    private val textureProvider by lazy {
        object : HasImageViewArray {
            override val imageViewArray = listOf(imageView)
        }
    }

    /**
     * Data class to hold the reduction results
     */
    data class ReductionResult(
        val min: FloatArray,  // RGBA minimum values
        val avg: FloatArray,  // RGBA average values
        val max: FloatArray,  // RGBA maximum values
        val lowAvgLuma: Float,       // average luminance of pixels below previous-frame average luminance
        val highAvgLuma: Float,      // average luminance of pixels at/above previous-frame average luminance
        val lowAvgRgb: FloatArray,   // average RGB of pixels below previous-frame average luminance
        val highAvgRgb: FloatArray,  // average RGB of pixels at/above previous-frame average luminance
        val midSatAvg: Float,        // average saturation inside previous-frame pseudo quartile band
        val contrastRange: Float,    // highAvgLuma - lowAvgLuma
        val contrastMichelson: Float,// (high-low)/(high+low)
        val lowFraction: Float,      // fraction of pixels in low bucket
        val highFraction: Float,     // fraction of pixels in high bucket
        val midFraction: Float       // fraction of pixels in pseudo quartile band
    ) {
        override fun toString(): String {
            return "ReductionResult(min=[${min.joinToString()}], avg=[${avg.joinToString()}], max=[${max.joinToString()}], " +
                "lowAvgLuma=$lowAvgLuma, highAvgLuma=$highAvgLuma, " +
                "lowAvgRgb=[${lowAvgRgb.joinToString()}], highAvgRgb=[${highAvgRgb.joinToString()}], " +
                "midSatAvg=$midSatAvg, " +
                "contrastRange=$contrastRange, contrastMichelson=$contrastMichelson, " +
                "lowFraction=$lowFraction, highFraction=$highFraction, midFraction=$midFraction)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReductionResult
            if (!min.contentEquals(other.min)) return false
            if (!avg.contentEquals(other.avg)) return false
            if (!max.contentEquals(other.max)) return false
            if (lowAvgLuma != other.lowAvgLuma) return false
            if (highAvgLuma != other.highAvgLuma) return false
            if (!lowAvgRgb.contentEquals(other.lowAvgRgb)) return false
            if (!highAvgRgb.contentEquals(other.highAvgRgb)) return false
            if (midSatAvg != other.midSatAvg) return false
            if (contrastRange != other.contrastRange) return false
            if (contrastMichelson != other.contrastMichelson) return false
            if (lowFraction != other.lowFraction) return false
            if (highFraction != other.highFraction) return false
            if (midFraction != other.midFraction) return false
            return true
        }

        override fun hashCode(): Int {
            var result = min.contentHashCode()
            result = 31 * result + avg.contentHashCode()
            result = 31 * result + max.contentHashCode()
            result = 31 * result + lowAvgLuma.hashCode()
            result = 31 * result + highAvgLuma.hashCode()
            result = 31 * result + lowAvgRgb.contentHashCode()
            result = 31 * result + highAvgRgb.contentHashCode()
            result = 31 * result + midSatAvg.hashCode()
            result = 31 * result + contrastRange.hashCode()
            result = 31 * result + contrastMichelson.hashCode()
            result = 31 * result + lowFraction.hashCode()
            result = 31 * result + highFraction.hashCode()
            result = 31 * result + midFraction.hashCode()
            return result
        }
    }

    /**
     * Initialize or update the compute shaders and buffers based on image dimensions
     */
    private fun ensureInitialized() {
        // Get image dimensions from the underlying image
        val underlyingImage = imageView.imageView.underlyingImage
        require(underlyingImage != null) { "ImageView must have an underlying Image" }

        // We currently bind the source image as a sampled texture so we do not mutate its layout.
        // Require sampled usage up-front to avoid invalid descriptors.
        val supportsSampled = underlyingImage.imageData.usage and VK10.VK_IMAGE_USAGE_SAMPLED_BIT != 0
        require(supportsSampled) {
            "ImageReducer requires the source image to include VK_IMAGE_USAGE_SAMPLED_BIT (usage=${underlyingImage.imageData.usage})"
        }

        val width = underlyingImage.imageData.width
        val height = underlyingImage.imageData.height
        val sourceViewType = imageView.imageView.imageViewData.viewType
        val sourceIsArrayView = sourceViewType == VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY
        val inputImageDeclaration = if (sourceIsArrayView) {
            "layout(set = 0, binding = 0) uniform sampler2DArray inputImage;"
        } else {
            "layout(set = 0, binding = 0) uniform sampler2D inputImage;"
        }
        val inputImageFetch = if (sourceIsArrayView) {
            "texelFetch(inputImage, ivec3(pixelCoord, 0), 0)"
        } else {
            "texelFetch(inputImage, pixelCoord, 0)"
        }

        // Check if we need to reinitialize
        if (width == cachedWidth && height == cachedHeight && sourceViewType == cachedSourceViewType &&
            firstPassModule != null && secondPassModule != null) {
            return
        }

        cachedWidth = width
        cachedHeight = height
        cachedSourceViewType = sourceViewType

        // Calculate workgroup counts
        workgroupCountX = ceil(width.toFloat() / LOCAL_SIZE_X).toInt()
        workgroupCountY = ceil(height.toFloat() / LOCAL_SIZE_Y).toInt()
        val totalWorkgroups = workgroupCountX * workgroupCountY

        // Create intermediate buffer (one result per workgroup)
        val intermediateSize = totalWorkgroups * INTERMEDIATE_VALUES_PER_RESULT * 4L // 4 bytes per float
        intermediateBuffer?.cleanup()

        intermediateBufferParameters = buffer {
            size = intermediateSize
            storage()
            hosted()
            perFrame = false
        }

        intermediateBuffer = intermediateBufferParameters?.toBuffer(renderer)?.buffers[0]

        // Create result buffer (final min, avg, max)
        val resultSize = FINAL_VALUES_PER_RESULT * 4L // 4 bytes per float
        resultBuffer?.cleanup()
        resultBufferParameters = buffer {
            size = resultSize
            storage()
            perFrame = false
            hosted()
        }
        resultBuffer = resultBufferParameters?.toBuffer(renderer)?.buffers[0]

        // Ensure we have a sampler for sampled binding mode
        if (textureSampler == null) {
            textureSampler = TextureSampler(renderer, imageView.imageView.imageViewData.mipLevels, true)
        }

        // Create first pass shader (sampled texture path)
        val firstPassShader = """
            #version 450

            layout(local_size_x = $LOCAL_SIZE_X, local_size_y = $LOCAL_SIZE_Y, local_size_z = 1) in;

            $inputImageDeclaration

            layout(set = 0, binding = 1) buffer IntermediateResults {
                float values[];
            } intermediate;

            layout(push_constant) uniform PushConstants {
                int imageDimensionsX;
                int imageDimensionsY;
                int workgroupCountX;
                float prevAvgLuma;
                float prevPseudoQ1;
                float prevPseudoQ3;
            } pc;

            // Shared memory for workgroup-local reduction
            shared vec4 localMin[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared vec4 localAvg[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared vec4 localMax[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared float localLowLumaSum[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared float localLowCount[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared float localHighLumaSum[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared float localHighCount[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared float localMidSatSum[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared float localMidCount[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared vec3 localLowRgbSum[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared vec3 localHighRgbSum[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];

            void main() {
                ivec2 pixelCoord = ivec2(gl_GlobalInvocationID.xy);
                uint localIndex = gl_LocalInvocationID.y * $LOCAL_SIZE_X + gl_LocalInvocationID.x;

                // Load pixel or use neutral values if out of bounds
                vec4 pixel = vec4(0.0);
                bool validPixel = false;
                if (pixelCoord.x < pc.imageDimensionsX && pixelCoord.y < pc.imageDimensionsY) {
                    pixel = $inputImageFetch;
                    validPixel = true;
                }

                // Initialize local memory
                if (validPixel) {
                    localMin[localIndex] = pixel;
                    localAvg[localIndex] = pixel;
                    localMax[localIndex] = pixel;

                    float luma = dot(pixel.rgb, vec3(0.2126, 0.7152, 0.0722));
                    float maxC = max(max(pixel.r, pixel.g), pixel.b);
                    float minC = min(min(pixel.r, pixel.g), pixel.b);
                    float sat = (maxC > 1e-6) ? ((maxC - minC) / maxC) : 0.0;

                    bool isLow = luma < pc.prevAvgLuma;
                    bool isHigh = !isLow;
                    float q1 = min(pc.prevPseudoQ1, pc.prevPseudoQ3);
                    float q3 = max(pc.prevPseudoQ1, pc.prevPseudoQ3);
                    bool isMid = (luma >= q1) && (luma <= q3);

                    localLowLumaSum[localIndex] = isLow ? luma : 0.0;
                    localLowCount[localIndex] = isLow ? 1.0 : 0.0;
                    localHighLumaSum[localIndex] = isHigh ? luma : 0.0;
                    localHighCount[localIndex] = isHigh ? 1.0 : 0.0;
                    localMidSatSum[localIndex] = isMid ? sat : 0.0;
                    localMidCount[localIndex] = isMid ? 1.0 : 0.0;
                    localLowRgbSum[localIndex] = isLow ? pixel.rgb : vec3(0.0);
                    localHighRgbSum[localIndex] = isHigh ? pixel.rgb : vec3(0.0);
                } else {
                    localMin[localIndex] = vec4(1e10); // Very large value for min
                    localAvg[localIndex] = vec4(0.0);
                    localMax[localIndex] = vec4(-1e10); // Very small value for max
                    localLowLumaSum[localIndex] = 0.0;
                    localLowCount[localIndex] = 0.0;
                    localHighLumaSum[localIndex] = 0.0;
                    localHighCount[localIndex] = 0.0;
                    localMidSatSum[localIndex] = 0.0;
                    localMidCount[localIndex] = 0.0;
                    localLowRgbSum[localIndex] = vec3(0.0);
                    localHighRgbSum[localIndex] = vec3(0.0);
                }

                barrier();

                // Parallel reduction in shared memory
                for (uint stride = ($LOCAL_SIZE_X * $LOCAL_SIZE_Y) / 2; stride > 0; stride >>= 1) {
                    if (localIndex < stride) {
                        localMin[localIndex] = min(localMin[localIndex], localMin[localIndex + stride]);
                        localAvg[localIndex] += localAvg[localIndex + stride];
                        localMax[localIndex] = max(localMax[localIndex], localMax[localIndex + stride]);
                        localLowLumaSum[localIndex] += localLowLumaSum[localIndex + stride];
                        localLowCount[localIndex] += localLowCount[localIndex + stride];
                        localHighLumaSum[localIndex] += localHighLumaSum[localIndex + stride];
                        localHighCount[localIndex] += localHighCount[localIndex + stride];
                        localMidSatSum[localIndex] += localMidSatSum[localIndex + stride];
                        localMidCount[localIndex] += localMidCount[localIndex + stride];
                        localLowRgbSum[localIndex] += localLowRgbSum[localIndex + stride];
                        localHighRgbSum[localIndex] += localHighRgbSum[localIndex + stride];
                    }
                    barrier();
                }

                // Write results to intermediate buffer
                // Layout: [min.rgba, avgSum.rgba, max.rgba, lowLumaSum, lowCount, highLumaSum, highCount, midSatSum, midCount, lowRgbSum.rgb, highRgbSum.rgb] per workgroup
                if (localIndex == 0) {
                    uint workgroupIndex = gl_WorkGroupID.y * pc.workgroupCountX + gl_WorkGroupID.x;
                    uint baseOffset = workgroupIndex * ${INTERMEDIATE_VALUES_PER_RESULT};

                    // Write min
                    intermediate.values[baseOffset + 0] = localMin[0].r;
                    intermediate.values[baseOffset + 1] = localMin[0].g;
                    intermediate.values[baseOffset + 2] = localMin[0].b;
                    intermediate.values[baseOffset + 3] = localMin[0].a;

                    // Write avg
                    intermediate.values[baseOffset + 4] = localAvg[0].r;
                    intermediate.values[baseOffset + 5] = localAvg[0].g;
                    intermediate.values[baseOffset + 6] = localAvg[0].b;
                    intermediate.values[baseOffset + 7] = localAvg[0].a;

                    // Write max
                    intermediate.values[baseOffset + 8] = localMax[0].r;
                    intermediate.values[baseOffset + 9] = localMax[0].g;
                    intermediate.values[baseOffset + 10] = localMax[0].b;
                    intermediate.values[baseOffset + 11] = localMax[0].a;

                    // Write pseudo-quartile accumulators
                    intermediate.values[baseOffset + 12] = localLowLumaSum[0];
                    intermediate.values[baseOffset + 13] = localLowCount[0];
                    intermediate.values[baseOffset + 14] = localHighLumaSum[0];
                    intermediate.values[baseOffset + 15] = localHighCount[0];
                    intermediate.values[baseOffset + 16] = localMidSatSum[0];
                    intermediate.values[baseOffset + 17] = localMidCount[0];

                    intermediate.values[baseOffset + 18] = localLowRgbSum[0].r;
                    intermediate.values[baseOffset + 19] = localLowRgbSum[0].g;
                    intermediate.values[baseOffset + 20] = localLowRgbSum[0].b;
                    intermediate.values[baseOffset + 21] = localHighRgbSum[0].r;
                    intermediate.values[baseOffset + 22] = localHighRgbSum[0].g;
                    intermediate.values[baseOffset + 23] = localHighRgbSum[0].b;
                }
            }
        """.trimIndent()

        val sampler = textureSampler ?: TextureSampler(renderer, imageView.imageView.imageViewData.mipLevels, true).also {
            textureSampler = it
        }

        firstPassModule = ComputeModule(Parameters(
            renderer,
            compute = firstPassShader,
            name = "ImageReducer_FirstPass",
            ssboResolver = { buffer ->
                when (buffer.name) {
                    "IntermediateResults" -> intermediateBufferParameters
                    else -> null
                }
            },
            textureResolver = { texture ->
                when (texture.name) {
                    "inputImage" -> textureProvider to sampler
                    else -> null
                }
            }
        )).pointer()

        // Create second pass shader
        val secondPassShader = """
            #version 450

            layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

            layout(set = 0, binding = 0) buffer IntermediateResults {
                float values[];
            } intermediate;

            layout(set = 0, binding = 1) buffer FinalResults {
                float values[];
            } results;

            layout(push_constant) uniform PushConstants {
                int totalWorkgroups;
                int totalPixels;
            } pc;

            shared vec4 localMin[256];
            shared vec4 localAvg[256];
            shared vec4 localMax[256];
            shared float localLowLumaSum[256];
            shared float localLowCount[256];
            shared float localHighLumaSum[256];
            shared float localHighCount[256];
            shared float localMidSatSum[256];
            shared float localMidCount[256];
            shared vec3 localLowRgbSum[256];
            shared vec3 localHighRgbSum[256];

            void main() {
                uint localIndex = gl_LocalInvocationID.x;

                // Accumulate across all workgroups in strides of 256 so we don't drop workgroups > local_size_x.
                vec4 minVal = vec4(1e10);
                vec4 avgVal = vec4(0.0);
                vec4 maxVal = vec4(-1e10);
                float lowLumaSumVal = 0.0;
                float lowCountVal = 0.0;
                float highLumaSumVal = 0.0;
                float highCountVal = 0.0;
                float midSatSumVal = 0.0;
                float midCountVal = 0.0;
                vec3 lowRgbSumVal = vec3(0.0);
                vec3 highRgbSumVal = vec3(0.0);

                for (uint idx = localIndex; idx < uint(pc.totalWorkgroups); idx += 256) {
                    uint baseOffset = idx * ${INTERMEDIATE_VALUES_PER_RESULT};
                    vec4 minIn = vec4(
                        intermediate.values[baseOffset + 0],
                        intermediate.values[baseOffset + 1],
                        intermediate.values[baseOffset + 2],
                        intermediate.values[baseOffset + 3]
                    );
                    vec4 avgIn = vec4(
                        intermediate.values[baseOffset + 4],
                        intermediate.values[baseOffset + 5],
                        intermediate.values[baseOffset + 6],
                        intermediate.values[baseOffset + 7]
                    );
                    vec4 maxIn = vec4(
                        intermediate.values[baseOffset + 8],
                        intermediate.values[baseOffset + 9],
                        intermediate.values[baseOffset + 10],
                        intermediate.values[baseOffset + 11]
                    );

                    minVal = min(minVal, minIn);
                    avgVal += avgIn;
                    maxVal = max(maxVal, maxIn);
                    lowLumaSumVal += intermediate.values[baseOffset + 12];
                    lowCountVal += intermediate.values[baseOffset + 13];
                    highLumaSumVal += intermediate.values[baseOffset + 14];
                    highCountVal += intermediate.values[baseOffset + 15];
                    midSatSumVal += intermediate.values[baseOffset + 16];
                    midCountVal += intermediate.values[baseOffset + 17];
                    lowRgbSumVal += vec3(
                        intermediate.values[baseOffset + 18],
                        intermediate.values[baseOffset + 19],
                        intermediate.values[baseOffset + 20]
                    );
                    highRgbSumVal += vec3(
                        intermediate.values[baseOffset + 21],
                        intermediate.values[baseOffset + 22],
                        intermediate.values[baseOffset + 23]
                    );
                }

                localMin[localIndex] = minVal;
                localAvg[localIndex] = avgVal;
                localMax[localIndex] = maxVal;
                localLowLumaSum[localIndex] = lowLumaSumVal;
                localLowCount[localIndex] = lowCountVal;
                localHighLumaSum[localIndex] = highLumaSumVal;
                localHighCount[localIndex] = highCountVal;
                localMidSatSum[localIndex] = midSatSumVal;
                localMidCount[localIndex] = midCountVal;
                localLowRgbSum[localIndex] = lowRgbSumVal;
                localHighRgbSum[localIndex] = highRgbSumVal;

                barrier();

                // Parallel reduction in shared memory
                for (uint stride = 128; stride > 0; stride >>= 1) {
                    if (localIndex < stride) {
                        localMin[localIndex] = min(localMin[localIndex], localMin[localIndex + stride]);
                        localAvg[localIndex] += localAvg[localIndex + stride];
                        localMax[localIndex] = max(localMax[localIndex], localMax[localIndex + stride]); 
                        localLowLumaSum[localIndex] += localLowLumaSum[localIndex + stride];
                        localLowCount[localIndex] += localLowCount[localIndex + stride];
                        localHighLumaSum[localIndex] += localHighLumaSum[localIndex + stride];
                        localHighCount[localIndex] += localHighCount[localIndex + stride];
                        localMidSatSum[localIndex] += localMidSatSum[localIndex + stride];
                        localMidCount[localIndex] += localMidCount[localIndex + stride];
                        localLowRgbSum[localIndex] += localLowRgbSum[localIndex + stride];
                        localHighRgbSum[localIndex] += localHighRgbSum[localIndex + stride];
                    }
                    barrier();
                }

                // Write final results
                if (localIndex == 0) {
                    vec4 finalMin = localMin[0];
                    vec4 finalAvg = localAvg[0] / float(pc.totalPixels);
                    vec4 finalMax = localMax[0];
                    float finalAvgLuma = dot(finalAvg.rgb, vec3(0.2126, 0.7152, 0.0722));
                    float lowAvgLuma = (localLowCount[0] > 0.0) ? (localLowLumaSum[0] / localLowCount[0]) : finalAvgLuma;
                    float highAvgLuma = (localHighCount[0] > 0.0) ? (localHighLumaSum[0] / localHighCount[0]) : finalAvgLuma;
                    float midSatAvg = (localMidCount[0] > 0.0) ? (localMidSatSum[0] / localMidCount[0]) : 0.0;
                    float contrastRange = highAvgLuma - lowAvgLuma;
                    float contrastMichelson = contrastRange / max(highAvgLuma + lowAvgLuma, 1e-6);
                    float totalPx = max(float(pc.totalPixels), 1.0);
                    float lowFrac = localLowCount[0] / totalPx;
                    float highFrac = localHighCount[0] / totalPx;
                    float midFrac = localMidCount[0] / totalPx;
                    vec3 lowAvgRgb = (localLowCount[0] > 0.0) ? (localLowRgbSum[0] / localLowCount[0]) : finalAvg.rgb;
                    vec3 highAvgRgb = (localHighCount[0] > 0.0) ? (localHighRgbSum[0] / localHighCount[0]) : finalAvg.rgb;

                    results.values[0] = finalMin.r;
                    results.values[1] = finalMin.g;
                    results.values[2] = finalMin.b;
                    results.values[3] = finalMin.a;

                    results.values[4] = finalAvg.r;
                    results.values[5] = finalAvg.g;
                    results.values[6] = finalAvg.b;
                    results.values[7] = finalAvg.a;

                    results.values[8] = finalMax.r;
                    results.values[9] = finalMax.g;
                    results.values[10] = finalMax.b;
                    results.values[11] = finalMax.a;

                    results.values[12] = lowAvgLuma;
                    results.values[13] = highAvgLuma;
                    results.values[14] = midSatAvg;
                    results.values[15] = contrastRange;
                    results.values[16] = contrastMichelson;
                    results.values[17] = lowFrac;
                    results.values[18] = highFrac;
                    results.values[19] = midFrac;
                    results.values[20] = lowAvgRgb.r;
                    results.values[21] = lowAvgRgb.g;
                    results.values[22] = lowAvgRgb.b;
                    results.values[23] = highAvgRgb.r;
                    results.values[24] = highAvgRgb.g;
                    results.values[25] = highAvgRgb.b;
                }
            }
        """.trimIndent()

        secondPassModule = ComputeModule(Parameters(
            renderer,
            compute = secondPassShader,
            name = "ImageReducer_SecondPass",
            ssboResolver = { buffer ->
                when (buffer.name) {
                    "IntermediateResults" -> intermediateBufferParameters
                    "FinalResults" -> resultBufferParameters
                    else -> null
                }
            }
        )).pointer()
    }

    /**
     * Execute the reduction on the GPU.
     * Must be called within a command buffer recording.
     */
    fun reduce(commandBuffer: CommandBuffer) {
        ensureInitialized()
        // A fresh reduction is being recorded; previously read-back results are now stale.
        resultsReady = false

        val firstPass = firstPassModule ?: throw IllegalStateException("First pass module not initialized")
        val secondPass = secondPassModule ?: throw IllegalStateException("Second pass module not initialized")

        // Create compute meshes
        val firstPassMesh = ComputeMesh(renderer, firstPass)
        firstPassMesh.x = workgroupCountX
        firstPassMesh.y = workgroupCountY
        firstPassMesh.z = 1

        // Initialize push constants structure from module
        firstPassMesh.initializePushConstantsFromModule(firstPass)

        // Set push constants for first pass
        firstPassMesh.push.set("imageDimensionsX", cachedWidth)
        firstPassMesh.push.set("imageDimensionsY", cachedHeight)
        firstPassMesh.push.set("workgroupCountX", workgroupCountX)
        val prevMinLuma = if (hasPreviousLumaStats) previousMinLuma else 0f
        val prevAvgLuma = if (hasPreviousLumaStats) previousAvgLuma else 0.5f
        val prevMaxLuma = if (hasPreviousLumaStats) previousMaxLuma else 1f
        val prevPseudoQ1 = 0.5f * (prevMinLuma + prevAvgLuma)
        val prevPseudoQ3 = 0.5f * (prevAvgLuma + prevMaxLuma)
        firstPassMesh.push.set("prevAvgLuma", prevAvgLuma)
        firstPassMesh.push.set("prevPseudoQ1", kotlin.math.min(prevPseudoQ1, prevPseudoQ3))
        firstPassMesh.push.set("prevPseudoQ3", kotlin.math.max(prevPseudoQ1, prevPseudoQ3))

        // Execute first pass (bind pipeline and dispatch through the module's render)
        firstPass += "reduce" to firstPassMesh
        firstPass.current.render(commandBuffer, afield.stdlib.linalg.Rectanglef())

        // Memory barrier between passes
        computeMemoryBarrier(commandBuffer, intermediateBuffer)

        // Execute second pass
        val secondPassMesh = ComputeMesh(renderer, secondPass)
        secondPassMesh.x = 1
        secondPassMesh.y = 1
        secondPassMesh.z = 1

        // Initialize push constants structure from module
        secondPassMesh.initializePushConstantsFromModule(secondPass)

        val totalWorkgroups = workgroupCountX * workgroupCountY
        val totalPixels = cachedWidth * cachedHeight
        secondPassMesh.push.set("totalWorkgroups", totalWorkgroups)
        secondPassMesh.push.set("totalPixels", totalPixels)

        secondPass += "reduce" to secondPassMesh
        secondPass.current.render(commandBuffer, afield.stdlib.linalg.Rectanglef())

        // Memory barrier for host read
        hostReadBarrier(commandBuffer, resultBuffer)
    }

    private fun luminance(rgba: FloatArray): Float {
        val r = rgba.getOrElse(0) { 0f }
        val g = rgba.getOrElse(1) { 0f }
        val b = rgba.getOrElse(2) { 0f }
        return (0.2126f * r) + (0.7152f * g) + (0.0722f * b)
    }

    /**
     * Read back the results from the GPU.
     * This should be called after GPU execution has completed (e.g., after fence wait).
     */
    fun readResults(): ReductionResult {
        val buffer = resultBuffer ?: throw IllegalStateException("Result buffer not initialized")
        check(resultsReady) {
            "ImageReducer.readResults() called before a reduction completed — results would be stale/garbage. " +
                "Use reduceNow(), read via the installInto(...) { } callback, or wait on your submit fence first."
        }

        val min = FloatArray(4)
        val avg = FloatArray(4)
        val max = FloatArray(4)
        val extras = FloatArray(14)

        buffer.mapAndRead {
            get(min)
            get(avg)
            get(max)
            get(extras)
        }

        val result = ReductionResult(
            min = min,
            avg = avg,
            max = max,
            lowAvgLuma = extras[0],
            highAvgLuma = extras[1],
            lowAvgRgb = floatArrayOf(extras[8], extras[9], extras[10]),
            highAvgRgb = floatArrayOf(extras[11], extras[12], extras[13]),
            midSatAvg = extras[2],
            contrastRange = extras[3],
            contrastMichelson = extras[4],
            lowFraction = extras[5],
            highFraction = extras[6],
            midFraction = extras[7]
        )

        previousMinLuma = luminance(result.min)
        previousAvgLuma = luminance(result.avg)
        previousMaxLuma = luminance(result.max)
        hasPreviousLumaStats = true

        return result
    }

    /**
     * Clean up GPU resources
     */
    fun cleanup() {
        intermediateBuffer?.cleanup()
        resultBuffer?.cleanup()
        firstPassModule?.current?.cleanup()
        secondPassModule?.current?.cleanup()
        textureSampler?.cleanup()

        intermediateBuffer = null
        resultBuffer = null
        firstPassModule = null
        secondPassModule = null
        textureSampler = null
        pendingCallbacksPerImage = null
        hasPreviousLumaStats = false
        previousMinLuma = 0f
        previousAvgLuma = 0.5f
        previousMaxLuma = 1f
    }

    /**
     * Execute the reduction once and return the results synchronously.
     * This is a convenience method that creates a command buffer, executes the reduction,
     * waits for completion, and returns the results.
     *
     * Usage:
     * ```
     * val reducer = ImageReducer(renderer, imageView)
     * val results = reducer.reduceNow()
     * println("Min: ${results.min.contentToString()}")
     * ```
     */
    fun reduceNow(): ReductionResult {
        val cmd = CommandBuffer(renderer.commandPool, true, true)
        val fence = Fence(renderer.device, true)

        try {
            cmd.beginRecording()
            reduce(cmd)
            cmd.endRecording()

            fence.reset()
            MemoryStack.stackPush().use { stack ->
                renderer.graphQueue.submit(
                    stack.pointers(cmd.vkCommandBuffer),
                    null,
                    null,
                    null,
                    fence
                )
            }
            fence.fenceWait()

            resultsReady = true
            return readResults()
        } finally {
            fence.cleanup()
            cmd.cleanup()
        }
    }

    /**
     * Install this reducer into a RenderTarget's others map for per-frame execution.
     * The reduction will be executed every frame as part of the render target's rendering.
     * Results can be read back asynchronously after the frame completes.
     *
     * Usage:
     * ```
     * val reducer = ImageReducer(renderer, imageView)
     * reducer.installInto(renderTarget, "imageStats")
     *
     * // In your render loop, after frame completion:
     * val results = reducer.readResults()
     * ```
     *
     * @param target The RenderTarget to install into
     * @param name The name to use in the target's others map
     */
    fun installInto(target: RenderTarget, name: String = "imageReducer") {
        target.others[name] = Target { command, index ->
            reduce(command)
        }
    }

    /**
     * Install this reducer into a RenderTarget with a callback that receives the results.
     * The callback is invoked after each frame's reduction completes.
     * Note: The callback is invoked synchronously during rendering, so keep it fast.
     *
     * Usage:
     * ```
     * val reducer = ImageReducer(renderer, imageView)
     * reducer.installInto(renderTarget, "imageStats") { result ->
     *     println("Frame stats - Min: ${result.min[0]}, Avg: ${result.avg[0]}, Max: ${result.max[0]}")
     * }
     * ```
     *
     * @param target The RenderTarget to install into
     * @param name The name to use in the target's others map
     * @param onResults Callback invoked with the reduction results after each frame
     */
    fun installInto(target: RenderTarget, name: String = "imageReducer", onResults: (ReductionResult) -> Unit) {
        if (pendingCallbacksPerImage == null) {
            pendingCallbacksPerImage = BooleanArray(renderer.numImages)
        }
        val pending = pendingCallbacksPerImage!!

        target.others[name] = Target { command, index ->
            // The fence for this swapchain image is waited at the start of its next reuse,
            // so when we are invoked for the same index again the previous reduction is complete.
            if (index in pending.indices && pending[index] && resultBuffer != null) {
                resultsReady = true
                onResults(readResults())
                pending[index] = false
            }

            reduce(command)
            // Add barrier to ensure results are available before reading
            hostReadBarrier(command, resultBuffer)

            if (index in pending.indices) pending[index] = true
        }
    }
}
