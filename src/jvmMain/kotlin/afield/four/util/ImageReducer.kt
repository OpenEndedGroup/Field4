package afield.four.util

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.modules.*
import afield.four.texture.HasImageView
import org.lwjgl.vulkan.VK10.*
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max

/**
 * ImageReducer - A utility class for reducing an image to min, average, and max values using compute shaders.
 * 
 * This class takes a HasImageView and uses a two-pass reduction strategy:
 * 1. First pass: Each workgroup processes a tile of the image and computes local min/avg/max
 * 2. Second pass: Reduces all local results to final min/avg/max values
 * 
 * The workgroup sizes are automatically calculated for good performance.
 * Results can be read back asynchronously.
 * 
 * Usage:
 * ```
 * val reducer = ImageReducer(renderer, imageView)
 * val results = reducer.reduce(commandBuffer)
 * // Later, after GPU execution:
 * val (min, avg, max) = reducer.readResults()
 * ```
 */
class ImageReducer(
    val renderer: Renderer,
    private val imageView: HasImageView
) {
    companion object {
        // Workgroup size for the reduction shader (must match shader)
        private const val WORKGROUP_SIZE = 16
        private const val LOCAL_SIZE_X = WORKGROUP_SIZE
        private const val LOCAL_SIZE_Y = WORKGROUP_SIZE
        
        // Number of values per result (min, avg, max for RGBA = 12 floats)
        private const val VALUES_PER_RESULT = 12
    }
    
    // Buffers for intermediate and final results
    private var intermediateBuffer: VulkanBuffer? = null
    private var resultBuffer: VulkanBuffer? = null
    
    // Compute modules for the two-pass reduction
    private var firstPassModule: ComputeModulePointer? = null
    private var secondPassModule: ComputeModulePointer? = null
    
    // Cached dimensions
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var workgroupCountX: Int = 0
    private var workgroupCountY: Int = 0
    
    /**
     * Data class to hold the reduction results
     */
    data class ReductionResult(
        val min: FloatArray,  // RGBA minimum values
        val avg: FloatArray,  // RGBA average values
        val max: FloatArray   // RGBA maximum values
    ) {
        override fun toString(): String {
            return "ReductionResult(min=[${min.joinToString()}], avg=[${avg.joinToString()}], max=[${max.joinToString()}])"
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReductionResult
            if (!min.contentEquals(other.min)) return false
            if (!avg.contentEquals(other.avg)) return false
            if (!max.contentEquals(other.max)) return false
            return true
        }
        
        override fun hashCode(): Int {
            var result = min.contentHashCode()
            result = 31 * result + avg.contentHashCode()
            result = 31 * result + max.contentHashCode()
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
        
        val width = underlyingImage.imageData.width
        val height = underlyingImage.imageData.height
        
        // Check if we need to reinitialize
        if (width == cachedWidth && height == cachedHeight && 
            firstPassModule != null && secondPassModule != null) {
            return
        }
        
        cachedWidth = width
        cachedHeight = height
        
        // Calculate workgroup counts
        workgroupCountX = ceil(width.toFloat() / LOCAL_SIZE_X).toInt()
        workgroupCountY = ceil(height.toFloat() / LOCAL_SIZE_Y).toInt()
        val totalWorkgroups = workgroupCountX * workgroupCountY
        
        // Create intermediate buffer (one result per workgroup)
        val intermediateSize = totalWorkgroups * VALUES_PER_RESULT * 4L // 4 bytes per float
        intermediateBuffer?.cleanup()
        intermediateBuffer = VulkanBuffer(
            renderer,
            intermediateSize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )
        
        // Create result buffer (final min, avg, max)
        val resultSize = VALUES_PER_RESULT * 4L // 4 bytes per float
        resultBuffer?.cleanup()
        resultBuffer = VulkanBuffer(
            renderer,
            resultSize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )
        
        // Create first pass shader
        val firstPassShader = """
            #version 450
            
            layout(local_size_x = $LOCAL_SIZE_X, local_size_y = $LOCAL_SIZE_Y, local_size_z = 1) in;
            
            layout(set = 0, binding = 0, rgba8) uniform readonly image2D inputImage;
            
            layout(set = 0, binding = 1) buffer IntermediateResults {
                float values[];
            } intermediate;
            
            layout(push_constant) uniform PushConstants {
                int imageDimensionsX;
                int imageDimensionsY;
                int workgroupCountX;
            } pc;
            
            // Shared memory for workgroup-local reduction
            shared vec4 localMin[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared vec4 localAvg[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            shared vec4 localMax[$LOCAL_SIZE_X * $LOCAL_SIZE_Y];
            
            void main() {
                ivec2 pixelCoord = ivec2(gl_GlobalInvocationID.xy);
                uint localIndex = gl_LocalInvocationID.y * $LOCAL_SIZE_X + gl_LocalInvocationID.x;
                
                // Load pixel or use neutral values if out of bounds
                vec4 pixel = vec4(0.0);
                bool validPixel = false;
                if (pixelCoord.x < pc.imageDimensionsX && pixelCoord.y < pc.imageDimensionsY) {
                    pixel = imageLoad(inputImage, pixelCoord);
                    validPixel = true;
                }
                
                // Initialize local memory
                if (validPixel) {
                    localMin[localIndex] = pixel;
                    localAvg[localIndex] = pixel;
                    localMax[localIndex] = pixel;
                } else {
                    localMin[localIndex] = vec4(1e10); // Very large value for min
                    localAvg[localIndex] = vec4(0.0);
                    localMax[localIndex] = vec4(-1e10); // Very small value for max
                }
                
                barrier();
                
                // Parallel reduction in shared memory
                for (uint stride = ($LOCAL_SIZE_X * $LOCAL_SIZE_Y) / 2; stride > 0; stride >>= 1) {
                    if (localIndex < stride) {
                        localMin[localIndex] = min(localMin[localIndex], localMin[localIndex + stride]);
                        localAvg[localIndex] += localAvg[localIndex + stride];
                        localMax[localIndex] = max(localMax[localIndex], localMax[localIndex + stride]);
                    }
                    barrier();
                }
                
                // Write results to intermediate buffer
                // Layout: [min.rgba, avg.rgba, max.rgba] per workgroup
                if (localIndex == 0) {
                    uint workgroupIndex = gl_WorkGroupID.y * pc.workgroupCountX + gl_WorkGroupID.x;
                    uint baseOffset = workgroupIndex * 12;
                    
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
                }
            }
        """.trimIndent()
        
        firstPassModule = ComputeModule(Parameters(
            renderer,
            compute = firstPassShader,
            name = "ImageReducer_FirstPass",
            ssboResolver = { buffer ->
                when (buffer.name) {
                    "intermediate" -> intermediateBuffer
                    else -> null
                }
            },
            imageResolver = { img ->
                when (img.name) {
                    "inputImage" -> imageView.imageView
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
            
            void main() {
                uint localIndex = gl_LocalInvocationID.x;
                
                // Load intermediate results or use neutral values
                if (localIndex < pc.totalWorkgroups) {
                    uint baseOffset = localIndex * 12;
                    localMin[localIndex] = vec4(
                        intermediate.values[baseOffset + 0],
                        intermediate.values[baseOffset + 1],
                        intermediate.values[baseOffset + 2],
                        intermediate.values[baseOffset + 3]
                    );
                    localAvg[localIndex] = vec4(
                        intermediate.values[baseOffset + 4],
                        intermediate.values[baseOffset + 5],
                        intermediate.values[baseOffset + 6],
                        intermediate.values[baseOffset + 7]
                    );
                    localMax[localIndex] = vec4(
                        intermediate.values[baseOffset + 8],
                        intermediate.values[baseOffset + 9],
                        intermediate.values[baseOffset + 10],
                        intermediate.values[baseOffset + 11]
                    );
                } else {
                    localMin[localIndex] = vec4(1e10);
                    localAvg[localIndex] = vec4(0.0);
                    localMax[localIndex] = vec4(-1e10);
                }
                
                barrier();
                
                // Parallel reduction in shared memory
                for (uint stride = 128; stride > 0; stride >>= 1) {
                    if (localIndex < stride) {
                        localMin[localIndex] = min(localMin[localIndex], localMin[localIndex + stride]);
                        localAvg[localIndex] += localAvg[localIndex + stride];
                        localMax[localIndex] = max(localMax[localIndex], localMax[localIndex + stride]);
                    }
                    barrier();
                }
                
                // Write final results
                if (localIndex == 0) {
                    vec4 finalMin = localMin[0];
                    vec4 finalAvg = localAvg[0] / float(pc.totalPixels);
                    vec4 finalMax = localMax[0];
                    
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
                }
            }
        """.trimIndent()
        
        secondPassModule = ComputeModule(Parameters(
            renderer,
            compute = secondPassShader,
            name = "ImageReducer_SecondPass",
            ssboResolver = { buffer ->
                when (buffer.name) {
                    "intermediate" -> intermediateBuffer
                    "results" -> resultBuffer
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
        
        // Execute first pass (bind pipeline and dispatch through the module's render)
        firstPass += "reduce" to firstPassMesh
        firstPass.current.render(commandBuffer, org.joml.primitives.Rectanglef())
        
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
        secondPass.current.render(commandBuffer, org.joml.primitives.Rectanglef())
        
        // Memory barrier for host read
        hostReadBarrier(commandBuffer, resultBuffer)
    }
    
    /**
     * Read back the results from the GPU.
     * This should be called after GPU execution has completed (e.g., after fence wait).
     */
    fun readResults(): ReductionResult {
        val buffer = resultBuffer ?: throw IllegalStateException("Result buffer not initialized")
        
        val min = FloatArray(4)
        val avg = FloatArray(4)
        val max = FloatArray(4)
        
        buffer.mapAndRead { floatBuffer: FloatBuffer ->
            floatBuffer.get(min)
            floatBuffer.get(avg)
            floatBuffer.get(max)
        }
        
        return ReductionResult(min, avg, max)
    }
    
    /**
     * Clean up GPU resources
     */
    fun cleanup() {
        intermediateBuffer?.cleanup()
        resultBuffer?.cleanup()
        firstPassModule?.current?.cleanup()
        secondPassModule?.current?.cleanup()
        
        intermediateBuffer = null
        resultBuffer = null
        firstPassModule = null
        secondPassModule = null
    }
}
