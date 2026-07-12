package afield.four.texture

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Device
import afield.four.modules.fromContext
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView.ImageViewData
import org.lwjgl.stb.STBImage.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.tinyexr.*
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

open class LoadedTexture protected constructor(
    private val sourceName: String,
    val renderer: Renderer = fromContext(),
    imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB,
    decoded: DecodedImage,
    requestedMipLevels: Int = 1,
    private val generateMipmaps: Boolean = false,
    private val mipFilter: Int = VK10.VK_FILTER_LINEAR
) : HasImageView, HasDefaultTextureSampler {
    protected data class DecodedMip(val data: ByteBuffer, val width: Int, val height: Int)
    protected data class DecodedImage(
        val data: ByteBuffer,
        val width: Int,
        val height: Int,
        val mips: List<DecodedMip> = emptyList()
    ) {
        fun mipChain(): List<DecodedMip> =
            if (mips.isEmpty()) listOf(DecodedMip(data, width, height)) else mips
    }
    private data class MipUpload(val level: Int, val width: Int, val height: Int, val bufferOffset: Long)

    val fileName: String
    var height = 0
    @Suppress("UNNECESSARY_LATEINIT")
    lateinit var image: Image
    @Suppress("UNNECESSARY_LATEINIT")
    override lateinit var imageView: ImageView
    var mipLevels = 0
    var width = 0
    var recordedTransition: Boolean
    var stgBuffer: VulkanBuffer? = null
    override var defaultSampler: TextureSampler? = null
    private var mipUploads: List<MipUpload> = emptyList()

    fun setDefaultSampler(
        anisotropyEnable: Boolean = true,
        filter: Int = VK10.VK_FILTER_LINEAR,
        mipmapMode: Int = if (filter == VK10.VK_FILTER_LINEAR) VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR else VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST,
        addressModeU: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeV: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeW: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    ): TextureSampler {
        val sampler = TextureSampler(
            renderer = renderer,
            mipLevels = mipLevels,
            anisotropyEnable = anisotropyEnable,
            filter = filter,
            mipmapMode = mipmapMode,
            addressModeU = addressModeU,
            addressModeV = addressModeV,
            addressModeW = addressModeW
        )
        defaultSampler = sampler
        return sampler
    }

    fun repeatU(
        anisotropyEnable: Boolean = true,
        filter: Int = VK10.VK_FILTER_LINEAR,
        mipmapMode: Int = if (filter == VK10.VK_FILTER_LINEAR) VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR else VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST
    ): TextureSampler = setDefaultSampler(
        anisotropyEnable = anisotropyEnable,
        filter = filter,
        mipmapMode = mipmapMode,
        addressModeU = VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT,
        addressModeV = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeW = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    )

    init {
        recordedTransition = false
        this.fileName = sourceName
        this.width = decoded.width
        this.height = decoded.height
        val decodedMipChain = decoded.mipChain()
        this.mipLevels = if (decoded.mips.isNotEmpty()) decodedMipChain.size else chooseMipLevels(width, height, requestedMipLevels)

        createStgBuffer(renderer.device, decodedMipChain)
        val usage = VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK10.VK_IMAGE_USAGE_SAMPLED_BIT or
            (if (generateMipmaps && mipLevels > 1) VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT else 0)
        val imageData: ImageData = ImageData().width(width).height(height)
            .usage(usage).format(imageFormat)
            .mipLevels(mipLevels)
            .debugName("LoadedTexture ${sourceName.substringAfterLast('/')} ${width}x${height}")

        image = Image(renderer.device, imageData)
        val imageViewData: ImageViewData =
            ImageViewData().format(image.format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(mipLevels)
                .debugName("LoadedTextureView ${sourceName.substringAfterLast('/')} ${width}x${height}")
        imageView = ImageView(renderer.device, image, imageViewData)
    }

    companion object {
        private fun chooseMipLevels(width: Int, height: Int, requested: Int): Int {
            val longestAxis = max(width, height).coerceAtLeast(1)
            val auto = 1 + 31 - Integer.numberOfLeadingZeros(longestAxis)
            return if (requested <= 0) max(1, auto) else max(1, requested)
        }

        private fun hammersley(index: Int, count: Int): Pair<Float, Float> {
            var bits = index
            bits = (bits shl 16) or (bits ushr 16)
            bits = ((bits and 0x55555555) shl 1) or ((bits and 0xAAAAAAAA.toInt()) ushr 1)
            bits = ((bits and 0x33333333) shl 2) or ((bits and 0xCCCCCCCC.toInt()) ushr 2)
            bits = ((bits and 0x0F0F0F0F) shl 4) or ((bits and 0xF0F0F0F0.toInt()) ushr 4)
            bits = ((bits and 0x00FF00FF) shl 8) or ((bits and 0xFF00FF00.toInt()) ushr 8)
            val radicalInverse = (bits.toLong() and 0xFFFFFFFFL) * 2.3283064365386963e-10
            return index.toFloat() / count.toFloat() to radicalInverse.toFloat()
        }

        private fun equirectDirection(u: Float, v: Float): FloatArray {
            val phi = (u - 0.5f) * (2.0 * PI).toFloat()
            val y = sin((0.5f - v) * PI.toFloat())
            val r = sqrt(max(0f, 1f - y * y))
            return floatArrayOf(cos(phi) * r, y, sin(phi) * r)
        }

        private fun sampleEquirectRGBA(source: java.nio.FloatBuffer, width: Int, height: Int, dir: FloatArray, out: FloatArray) {
            val nx = dir[0]
            val ny = dir[1].coerceIn(-1f, 1f)
            val nz = dir[2]
            var u = atan2(nz, nx) / (2.0 * PI).toFloat() + 0.5f
            u -= floor(u)
            val vUnflipped = asin(ny) / PI.toFloat() + 0.5f
            val v = (1f - vUnflipped).coerceIn(0f, 1f)

            val x = u * width - 0.5f
            val y = v * height - 0.5f
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt().coerceIn(0, height - 1)
            val x1 = x0 + 1
            val y1 = (y0 + 1).coerceIn(0, height - 1)
            val tx = x - floor(x)
            val ty = y - floor(y)
            val wrappedX0 = ((x0 % width) + width) % width
            val wrappedX1 = ((x1 % width) + width) % width

            fun channel(px: Int, py: Int, c: Int): Float =
                source.get((py * width + px) * 4 + c)

            for (c in 0 until 4) {
                val a = channel(wrappedX0, y0, c) * (1f - tx) + channel(wrappedX1, y0, c) * tx
                val b = channel(wrappedX0, y1, c) * (1f - tx) + channel(wrappedX1, y1, c) * tx
                out[c] = a * (1f - ty) + b * ty
            }
        }

        private fun buildTangentBasis(n: FloatArray, tangent: FloatArray, bitangent: FloatArray) {
            val upX = if (abs(n[1]) < 0.999f) 0f else 1f
            val upY = if (abs(n[1]) < 0.999f) 1f else 0f
            val upZ = 0f
            var tx = upY * n[2] - upZ * n[1]
            var ty = upZ * n[0] - upX * n[2]
            var tz = upX * n[1] - upY * n[0]
            val tLen = sqrt(max(tx * tx + ty * ty + tz * tz, 1e-8f))
            tx /= tLen
            ty /= tLen
            tz /= tLen
            tangent[0] = tx
            tangent[1] = ty
            tangent[2] = tz
            bitangent[0] = n[1] * tz - n[2] * ty
            bitangent[1] = n[2] * tx - n[0] * tz
            bitangent[2] = n[0] * ty - n[1] * tx
        }

        private fun ggxPrefilterMips(base: ByteBuffer, width: Int, height: Int, requestedMipLevels: Int, sampleCount: Int): List<DecodedMip> {
            val mipLevels = chooseMipLevels(width, height, requestedMipLevels)
            if (mipLevels <= 1) return listOf(DecodedMip(base, width, height))

            val baseFloats = base.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val mips = ArrayList<DecodedMip>(mipLevels)
            mips += DecodedMip(base, width, height)
            val sampleColor = FloatArray(4)
            val tangent = FloatArray(3)
            val bitangent = FloatArray(3)
            val h = FloatArray(3)
            val l = FloatArray(3)

            for (level in 1 until mipLevels) {
                val mipWidth = (width shr level).coerceAtLeast(1)
                val mipHeight = (height shr level).coerceAtLeast(1)
                val roughness = (level.toFloat() / (mipLevels - 1).toFloat()).coerceIn(0.04f, 1f)
                val roughness2 = roughness * roughness
                val roughness4 = roughness2 * roughness2
                val levelSamples = max(16, (sampleCount * (0.65f + roughness * 0.55f)).toInt())
                val out = MemoryUtil.memAlloc(mipWidth * mipHeight * 4 * java.lang.Float.BYTES)
                val outFloats = out.order(ByteOrder.nativeOrder()).asFloatBuffer()

                for (y in 0 until mipHeight) {
                    val v = (y + 0.5f) / mipHeight.toFloat()
                    for (x in 0 until mipWidth) {
                        val u = (x + 0.5f) / mipWidth.toFloat()
                        val n = equirectDirection(u, v)
                        buildTangentBasis(n, tangent, bitangent)

                        var r = 0f
                        var g = 0f
                        var b = 0f
                        var a = 0f
                        var totalWeight = 0f

                        for (sample in 0 until levelSamples) {
                            val (xi0, xi1) = hammersley(sample, levelSamples)
                            val phi = 2f * PI.toFloat() * xi0
                            val cosTheta = sqrt(((1f - xi1) / (1f + (roughness4 - 1f) * xi1)).coerceIn(0f, 1f))
                            val sinTheta = sqrt(max(0f, 1f - cosTheta * cosTheta))
                            val cosPhi = cos(phi)
                            val sinPhi = sin(phi)

                            h[0] = tangent[0] * cosPhi * sinTheta + bitangent[0] * sinPhi * sinTheta + n[0] * cosTheta
                            h[1] = tangent[1] * cosPhi * sinTheta + bitangent[1] * sinPhi * sinTheta + n[1] * cosTheta
                            h[2] = tangent[2] * cosPhi * sinTheta + bitangent[2] * sinPhi * sinTheta + n[2] * cosTheta
                            val hLen = sqrt(max(h[0] * h[0] + h[1] * h[1] + h[2] * h[2], 1e-8f))
                            h[0] /= hLen
                            h[1] /= hLen
                            h[2] /= hLen

                            val nDotH = max(n[0] * h[0] + n[1] * h[1] + n[2] * h[2], 0f)
                            l[0] = 2f * nDotH * h[0] - n[0]
                            l[1] = 2f * nDotH * h[1] - n[1]
                            l[2] = 2f * nDotH * h[2] - n[2]
                            val nDotL = max(n[0] * l[0] + n[1] * l[1] + n[2] * l[2], 0f)
                            if (nDotL > 0f) {
                                sampleEquirectRGBA(baseFloats, width, height, l, sampleColor)
                                r += sampleColor[0] * nDotL
                                g += sampleColor[1] * nDotL
                                b += sampleColor[2] * nDotL
                                a += sampleColor[3] * nDotL
                                totalWeight += nDotL
                            }
                        }

                        val dst = (y * mipWidth + x) * 4
                        val invWeight = if (totalWeight > 1e-6f) 1f / totalWeight else 1f
                        outFloats.put(dst, r * invWeight)
                        outFloats.put(dst + 1, g * invWeight)
                        outFloats.put(dst + 2, b * invWeight)
                        outFloats.put(dst + 3, a * invWeight)
                    }
                }
                out.position(0)
                out.limit(mipWidth * mipHeight * 4 * java.lang.Float.BYTES)
                mips += DecodedMip(out, mipWidth, mipHeight)
            }
            return mips
        }

        private fun decodeFile(path: String): DecodedImage {
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val buf = stbi_load(path, w, h, channels, 4)
                    ?: throw RuntimeException("Image file [$path] not loaded: ${stbi_failure_reason()}")
                return DecodedImage(buf, w.get(), h.get())
            }
        }

        private fun decodeMemory(data: ByteBuffer): DecodedImage {
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val buf = stbi_load_from_memory(data, w, h, channels, 4)
                    ?: throw RuntimeException("Image buffer not loaded: ${stbi_failure_reason()}")
                return DecodedImage(buf, w.get(), h.get())
            }
        }

        private fun decodeExrFile(path: String): DecodedImage {
            // Classic TinyEXR RGBA loader. This used to call the experimental async
            // exr_context_/command_buffer_/fence_ GPU-decode API, but that binding was
            // removed from the LWJGL 3.4.2-SNAPSHOT upstream (the mutable snapshot dropped
            // it), which broke a clean compile. LoadEXRWithLayer(..., layer = null) is the
            // stable convenience path present in every tinyexr build: it parses the version
            // and header, decodes, and hands back a malloc'd interleaved RGBA float image.
            MemoryStack.stackPush().use { stack ->
                val out = stack.mallocPointer(1)
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val err = stack.mallocPointer(1)
                val filenameBuf = stack.UTF8(path)
                // Call the native directly so the layer can be NULL (load the default/first
                // layer). The type-safe LoadEXRWithLayer overloads both require a non-null layer.
                val result = TinyEXR.nLoadEXRWithLayer(
                    MemoryUtil.memAddress(out),
                    MemoryUtil.memAddress(w),
                    MemoryUtil.memAddress(h),
                    MemoryUtil.memAddress(filenameBuf),
                    MemoryUtil.NULL,
                    MemoryUtil.memAddress(err)
                )
                if (result != TinyEXR.TINYEXR_SUCCESS) {
                    val errPtr = err.get(0)
                    val message = if (errPtr != MemoryUtil.NULL) MemoryUtil.memUTF8(errPtr) else "unknown error"
                    if (errPtr != MemoryUtil.NULL) TinyEXR.nFreeEXRErrorMessage(errPtr)
                    throw RuntimeException("TinyEXR failed to load EXR [$path]: $message ($result)")
                }

                val width = w.get(0)
                val height = h.get(0)
                require(width > 0 && height > 0) { "EXR [$path] has invalid size ${width}x${height}" }

                // TinyEXR malloc'd width*height*4 floats (RGBA). Copy into a buffer we own,
                // then free the library's allocation so ownership matches the STB paths above.
                val floatCount = width * height * 4
                val srcAddress = out.get(0)
                val src = MemoryUtil.memFloatBuffer(srcAddress, floatCount)
                val rgba = MemoryUtil.memAlloc(floatCount * java.lang.Float.BYTES)
                val rgbaFloats = rgba.order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until floatCount) rgbaFloats.put(i, src.get(i))
                MemoryUtil.nmemFree(srcAddress)
                rgba.position(0)
                rgba.limit(floatCount * java.lang.Float.BYTES)
                return DecodedImage(rgba, width, height)
            }
        }

        fun fromFile(
            fileName: String,
            renderer: Renderer = fromContext(),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB,
            requestedMipLevels: Int = 1,
            generateMipmaps: Boolean = false,
            mipFilter: Int = VK10.VK_FILTER_LINEAR
        ): LoadedTexture {
            val decoded = decodeFile(fileName)
            try {
                return LoadedTexture(
                    fileName,
                    renderer,
                    imageFormat,
                    decoded,
                    requestedMipLevels = requestedMipLevels,
                    generateMipmaps = generateMipmaps,
                    mipFilter = mipFilter
                )
            } finally {
                stbi_image_free(decoded.data)
            }
        }

        fun fromExrFile(
            fileName: String,
            renderer: Renderer = fromContext(),
            requestedMipLevels: Int = 1,
            generateMipmaps: Boolean = false,
            mipFilter: Int = VK10.VK_FILTER_LINEAR,
            prefilterEnvironment: Boolean = false,
            pmremSamples: Int = 64
        ): LoadedTexture {
            val baseDecoded = decodeExrFile(fileName)
            val decoded = if (prefilterEnvironment) {
                baseDecoded.copy(
                    mips = ggxPrefilterMips(
                        baseDecoded.data,
                        baseDecoded.width,
                        baseDecoded.height,
                        requestedMipLevels,
                        pmremSamples
                    )
                )
            } else {
                baseDecoded
            }
            try {
                return LoadedTexture(
                    fileName,
                    renderer,
                    VK_FORMAT_R32G32B32A32_SFLOAT,
                    decoded,
                    requestedMipLevels = requestedMipLevels,
                    generateMipmaps = generateMipmaps && !prefilterEnvironment,
                    mipFilter = mipFilter
                )
            } finally {
                val freed = HashSet<Long>()
                decoded.mipChain().forEach { mip ->
                    val address = MemoryUtil.memAddress(mip.data)
                    if (freed.add(address)) {
                        MemoryUtil.memFree(mip.data)
                    }
                }
            }
        }

        fun fromEncodedMemory(
            name: String,
            encoded: ByteBuffer,
            renderer: Renderer = fromContext(),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB,
            requestedMipLevels: Int = 1,
            generateMipmaps: Boolean = false,
            mipFilter: Int = VK10.VK_FILTER_LINEAR
        ): LoadedTexture {
            val decoded = decodeMemory(encoded)
            try {
                return LoadedTexture(
                    name,
                    renderer,
                    imageFormat,
                    decoded,
                    requestedMipLevels = requestedMipLevels,
                    generateMipmaps = generateMipmaps,
                    mipFilter = mipFilter
                )
            } finally {
                stbi_image_free(decoded.data)
            }
        }

        fun fromRawRGBA(
            name: String,
            width: Int,
            height: Int,
            rgba: ByteBuffer,
            renderer: Renderer = fromContext(),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB,
            requestedMipLevels: Int = 1,
            generateMipmaps: Boolean = false,
            mipFilter: Int = VK10.VK_FILTER_LINEAR
        ): LoadedTexture {
            val decoded = DecodedImage(rgba, width, height)
            return LoadedTexture(
                name,
                renderer,
                imageFormat,
                decoded,
                requestedMipLevels = requestedMipLevels,
                generateMipmaps = generateMipmaps,
                mipFilter = mipFilter
            )
        }

        operator fun invoke(
            fileName: String,
            renderer: Renderer = fromContext(),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB
        ): LoadedTexture = fromFile(fileName, renderer, imageFormat)

        private val stubCache = java.util.WeakHashMap<Renderer, LoadedTexture>()
        fun stubTexture(
            renderer: Renderer = fromContext(),
            rgba: FloatArray = floatArrayOf(1f, 0.5f, 0.3f, 1f),
            imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB
        ): LoadedTexture {
            stubCache[renderer]?.let { return it }
            val data = MemoryUtil.memAlloc(4) // just leak it
            data.put((rgba[0].coerceIn(0f,1f)*255f).toInt().toByte())
            data.put((rgba[1].coerceIn(0f,1f)*255f).toInt().toByte())
            data.put((rgba[2].coerceIn(0f,1f)*255f).toInt().toByte())
            data.put((rgba[3].coerceIn(0f,1f)*255f).toInt().toByte())
            data.flip()
            val decoded = DecodedImage(data, 1, 1)
            val tex = LoadedTexture("stub_1x1", renderer, imageFormat, decoded)
            stubCache[renderer] = tex
            return tex
        }
    }

    fun cleanup() {
        cleanupStgBuffer()
        imageView.cleanup()
        image.cleanup()
    }

    fun cleanupStgBuffer() {
        if (stgBuffer != null) {
            stgBuffer!!.cleanup()
            stgBuffer = null
        }
    }

    private fun createStgBuffer(device: Device, mipChain: List<DecodedMip>) {
        val sizes = mipChain.map { it.data.remaining() }
        val size = sizes.fold(0L) { acc, next -> acc + next.toLong() }
        stgBuffer = VulkanBuffer(
            renderer,
            size,
            VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )
        val mappedMemory = stgBuffer!!.map()
        val buffer = MemoryUtil.memByteBuffer(mappedMemory, stgBuffer!!.requestedSize.toInt())
        val uploads = ArrayList<MipUpload>(mipChain.size)
        var offset = 0L
        mipChain.forEachIndexed { level, mip ->
            val data = mip.data.duplicate()
            buffer.position(offset.toInt())
            buffer.put(data)
            uploads += MipUpload(level, mip.width, mip.height, offset)
            offset += sizes[level].toLong()
        }
        buffer.position(0)
        mipUploads = uploads
        stgBuffer!!.unmap()
    }


    private fun recordCopyBuffer(stack: MemoryStack, cmd: CommandBuffer, bufferData: VulkanBuffer) {
        val regions = VkBufferImageCopy.calloc(mipUploads.size, stack)
        mipUploads.forEachIndexed { index, mip ->
            regions[index].bufferOffset(mip.bufferOffset).bufferRowLength(0).bufferImageHeight(0)
                .imageSubresource { it: VkImageSubresourceLayers ->
                    it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip.level).baseArrayLayer(0).layerCount(1)
                }.imageOffset { it: VkOffset3D -> it.x(0).y(0).z(0) }.imageExtent { it: VkExtent3D ->
                    it.width(mip.width).height(mip.height).depth(1)
                }
            }

        VK10.vkCmdCopyBufferToImage(
            cmd.vkCommandBuffer, bufferData.buffer, image.vkImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions
        )
    }

    private fun recordImageTransition(stack: MemoryStack, cmd: CommandBuffer, oldLayout: Int, newLayout: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0)
                    .layerCount(1)
            }

        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int

        if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
        } else {
            throw RuntimeException("Unsupported layout transition")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    private fun generateMipChain(stack: MemoryStack, cmd: CommandBuffer) {
        var mipWidth = width
        var mipHeight = height

        for (level in 1 until mipLevels) {
            run {
                val barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.vkImage)
                    .subresourceRange { it: VkImageSubresourceRange ->
                        it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(level - 1)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1)
                    }
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)

                VK10.vkCmdPipelineBarrier(
                    cmd.vkCommandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    barrier
                )
            }

            val srcWidth = mipWidth.coerceAtLeast(1)
            val srcHeight = mipHeight.coerceAtLeast(1)
            mipWidth = (mipWidth / 2).coerceAtLeast(1)
            mipHeight = (mipHeight / 2).coerceAtLeast(1)

            val blit = VkImageBlit.calloc(1, stack)
            blit[0].srcSubresource { it ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level - 1)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }
            blit[0].srcOffsets(0).set(0, 0, 0)
            blit[0].srcOffsets(1).set(srcWidth, srcHeight, 1)
            blit[0].dstSubresource { it ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }
            blit[0].dstOffsets(0).set(0, 0, 0)
            blit[0].dstOffsets(1).set(mipWidth, mipHeight, 1)

            VK10.vkCmdBlitImage(
                cmd.vkCommandBuffer,
                image.vkImage,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                image.vkImage,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                blit,
                mipFilter
            )

            run {
                val barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.vkImage)
                    .subresourceRange { it: VkImageSubresourceRange ->
                        it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(level - 1)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1)
                    }
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)

                VK10.vkCmdPipelineBarrier(
                    cmd.vkCommandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    barrier
                )
            }
        }

        run {
            val barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image.vkImage)
                .subresourceRange { it: VkImageSubresourceRange ->
                    it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(mipLevels - 1)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1)
                }
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)

            VK10.vkCmdPipelineBarrier(
                cmd.vkCommandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0,
                null,
                null,
                barrier
            )
        }
    }

    fun recordTextureTransition(cmd: CommandBuffer) {
        if (stgBuffer != null && !recordedTransition) {
            recordedTransition = true
            MemoryStack.stackPush().use { stack ->
                recordImageTransition(
                    stack, cmd, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                )
                recordCopyBuffer(stack, cmd, stgBuffer!!)
                if (generateMipmaps && mipLevels > 1) {
                    generateMipChain(stack, cmd)
                } else {
                    recordImageTransition(
                        stack, cmd, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    )
                }
            }
        } else {
        }
    }

    fun transferNow() {
        if (stgBuffer != null && !recordedTransition) {
            val cmd = CommandBuffer(renderer.commandPool, true, true)

            cmd.beginRecording()
            recordTextureTransition(cmd)
            cmd.endRecording()

            val fence = afield.four.device.Fence(renderer.device, true)
            fence.reset()
            MemoryStack.stackPush().use { stack ->
                renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
            }
            fence.fenceWait()
            fence.cleanup()
            cmd.cleanup()
        }
    }
}

/**
 * Extremely simple streaming texture: call [recordUpload] every frame.
 *
 * Assumptions:
 * - [source] contains at least `width*height*bytesPerPixel` bytes starting at its current position.
 * - Tearing is acceptable (CPU may write while GPU reads).
 */
class StreamingTexture(
    val source: ByteBuffer,
    width: Int,
    height: Int,
    renderer: Renderer = fromContext(),
    imageFormat: Int = VK_FORMAT_R8G8B8A8_SRGB,
    name: String = "stream_${width}x${height}"
) : LoadedTexture(
    name,
    renderer,
    imageFormat,
    DecodedImage(initialSlice(source, width, height, imageFormat), width, height)
), Target {
    private val bytesPerPixel = ImageData().format(imageFormat).bytesPerPixel
    private val expectedBytes: Int = width * height * bytesPerPixel

    // best-effort tracking for repeated uploads
    private var currentLayout: Int = VK10.VK_IMAGE_LAYOUT_UNDEFINED

    init {
        require(width > 0 && height > 0) { "StreamingTexture requires positive dimensions" }
        require(source.remaining() >= expectedBytes) {
            "StreamingTexture source buffer too small: need $expectedBytes bytes, have ${source.remaining()}"
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        recordUpload(command, source)
    }

    fun recordUpload(cmd: CommandBuffer, src: ByteBuffer = source) {
        writeToStaging(src)

        MemoryStack.stackPush().use { stack ->
            recordImageTransition(stack, cmd, currentLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            recordCopyBuffer(stack, cmd, stgBuffer!!)
            recordImageTransition(
                stack,
                cmd,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            )
        }

        currentLayout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    }

    /** Convenience for when you don't already have a frame command buffer. */
    fun uploadNow(src: ByteBuffer = source) {
        val cmd = CommandBuffer(renderer.commandPool, true, true)

        cmd.beginRecording()
        recordUpload(cmd, src)
        cmd.endRecording()

        val fence = afield.four.device.Fence(renderer.device, true)
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence.fenceWait()
        fence.cleanup()
        cmd.cleanup()
    }

    private fun writeToStaging(src: ByteBuffer) {
        require(stgBuffer != null) { "StreamingTexture missing staging buffer" }
        require(src.remaining() >= expectedBytes) {
            "StreamingTexture update buffer too small: need $expectedBytes bytes, have ${src.remaining()}"
        }

        val srcDup = src.duplicate()
        val start = srcDup.position()
        srcDup.limit(start + expectedBytes)

        val mapped = stgBuffer!!.map()
        val dst = MemoryUtil.memByteBuffer(mapped, expectedBytes)
        dst.position(0)
        dst.put(srcDup)
        stgBuffer!!.unmap(0, expectedBytes.toLong())
    }

    private fun recordCopyBuffer(stack: MemoryStack, cmd: CommandBuffer, bufferData: VulkanBuffer) {
        val region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            .imageSubresource { it: VkImageSubresourceLayers ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
            }.imageOffset { it: VkOffset3D -> it.x(0).y(0).z(0) }.imageExtent { it: VkExtent3D ->
                it.width(this.width).height(this.height).depth(1)
            }

        VK10.vkCmdCopyBufferToImage(
            cmd.vkCommandBuffer,
            bufferData.buffer,
            image.vkImage,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            region
        )
    }

    private fun recordImageTransition(stack: MemoryStack, cmd: CommandBuffer, oldLayout: Int, newLayout: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0)
                    .layerCount(1)
            }

        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int

        if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            srcAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
        } else {
            throw RuntimeException("Unsupported layout transition: $oldLayout -> $newLayout")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    companion object {
        private fun initialSlice(src: ByteBuffer, width: Int, height: Int, format: Int): ByteBuffer {
            val expected = width * height * ImageData().format(format).bytesPerPixel
            require(src.remaining() >= expected) {
                "StreamingTexture source buffer too small: need $expected bytes, have ${src.remaining()}"
            }
            val dup = src.duplicate()
            val start = dup.position()
            dup.limit(start + expected)
            return dup
        }
    }
}

/**
 * Streaming texture backed by a mapped shared-memory buffer with a small header:
 * [frame counter (uint64), width (int32), height (int32)] followed by `width*height` bytes of R8 data.
 */
class MappedMonochromeTexture(
    private val mapped: ByteBuffer,
    renderer: Renderer = fromContext(),
    name: String = "mapped_mono",
    private val headerByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    private val headerOffset: Int = 0,
    private val eagerUpload: Boolean = true,
    private val requestedMipLevels: Int = 0, // 0 => auto full chain
    private val generateMipmaps: Boolean = true,
    private val mipFilter: Int = VK10.VK_FILTER_LINEAR
) : LoadedTexture(
    name,
    renderer,
    VK_FORMAT_R8_UNORM,
    decodeInitial(mapped, headerOffset, headerByteOrder)
), Target {
    private data class Header(val frame: Long, val width: Int, val height: Int)

    // Force an upload on first use even if the frame counter starts at 0.
    private var lastFrameCounter: Long = Long.MIN_VALUE
    private var currentLayout: Int = VK10.VK_IMAGE_LAYOUT_UNDEFINED
    private var targetMipLevels: Int = chooseMipLevels(readHeader(mapped, headerOffset, headerByteOrder), requestedMipLevels)

    init {
        recreateImage(readHeader(mapped, headerOffset, headerByteOrder), force = true)
        if (eagerUpload) {
            // Perform an immediate upload so the image is in SHADER_READ_ONLY before first use.
            uploadNowIfChanged()
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        recordUploadIfChanged(command)
    }

    /** Returns true if a new frame was uploaded. */
    fun recordUploadIfChanged(cmd: CommandBuffer): Boolean {
        val header = readHeader()
        val expectedBytes = expectedBytes(header)
        val alreadyUploaded =
            header.frame == lastFrameCounter && currentLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        if (alreadyUploaded) return false

        recordUpload(cmd, header, expectedBytes)
        return true
    }

    /** Convenience when you don't already have a frame command buffer. */
    fun uploadNowIfChanged(): Boolean {
        val header = readHeader()
        val expectedBytes = expectedBytes(header)
        val alreadyUploaded =
            header.frame == lastFrameCounter && currentLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        if (alreadyUploaded) return false

        val cmd = CommandBuffer(renderer.commandPool, true, true)
        cmd.beginRecording()
        recordUpload(cmd, header, expectedBytes)
        cmd.endRecording()

        val fence = afield.four.device.Fence(renderer.device, true)
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence.fenceWait()
        fence.cleanup()
        cmd.cleanup()
        return true
    }

    private fun recordUpload(cmd: CommandBuffer, header: Header, expectedBytes: Int) {
        ensureImage(header, expectedBytes)
        val payload = payloadSlice(expectedBytes)
        writeToStaging(payload, expectedBytes)

        MemoryStack.stackPush().use { stack ->
            recordImageTransition(stack, cmd, currentLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, targetMipLevels)
            recordCopyBuffer(stack, cmd, stgBuffer!!)

            if (generateMipmaps && targetMipLevels > 1) {
                generateMipChain(stack, cmd)
            } else {
                recordImageTransition(
                    stack,
                    cmd,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    targetMipLevels
                )
                currentLayout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            }
        }

        lastFrameCounter = header.frame
    }

    private fun ensureImage(header: Header, expectedBytes: Int) {
        ensureStaging(expectedBytes)
        recreateImage(header, force = false)
    }

    private fun ensureStaging(expectedBytes: Int) {
        val required = expectedBytes.toLong()
        if (stgBuffer == null) {
            stgBuffer = VulkanBuffer(
                renderer,
                required,
                VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            )
        } else if (stgBuffer!!.requestedSize < required) {
            stgBuffer!!.realloc(required)
        }
    }

    private fun writeToStaging(payload: ByteBuffer, expectedBytes: Int) {
        require(stgBuffer != null) { "MappedMonochromeTexture missing staging buffer" }
        val mappedPtr = stgBuffer!!.map()
        val dst = MemoryUtil.memByteBuffer(mappedPtr, expectedBytes)
        dst.position(0)
        dst.put(payload)
        stgBuffer!!.unmap(0, expectedBytes.toLong())
    }

    private fun payloadSlice(expectedBytes: Int): ByteBuffer = payloadSlice(mapped, headerOffset, expectedBytes)

    private fun expectedBytes(header: Header): Int =
        expectedBytes(header, mapped.capacity(), headerOffset)

    private fun readHeader(): Header = readHeader(mapped, headerOffset, headerByteOrder)

    private fun recordCopyBuffer(stack: MemoryStack, cmd: CommandBuffer, bufferData: VulkanBuffer) {
        val region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            .imageSubresource { it: VkImageSubresourceLayers ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
            }.imageOffset { it: VkOffset3D -> it.x(0).y(0).z(0) }.imageExtent { it: VkExtent3D ->
                it.width(this.width).height(this.height).depth(1)
            }

        VK10.vkCmdCopyBufferToImage(
            cmd.vkCommandBuffer,
            bufferData.buffer,
            image.vkImage,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            region
        )
    }

    private fun recordImageTransition(stack: MemoryStack, cmd: CommandBuffer, oldLayout: Int, newLayout: Int, levels: Int = targetMipLevels) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(levels).baseArrayLayer(0)
                    .layerCount(1)
            }

        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int

        if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            srcAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
        } else {
            throw RuntimeException("Unsupported layout transition: $oldLayout -> $newLayout")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    private fun generateMipChain(stack: MemoryStack, cmd: CommandBuffer) {
        var mipWidth = width
        var mipHeight = height

        for (level in 1 until targetMipLevels) {
            // Transition level-1 to TRANSFER_SRC_OPTIMAL
            run {
                val barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.vkImage)
                    .subresourceRange { it: VkImageSubresourceRange ->
                        it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(level - 1)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1)
                    }
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)

                VK10.vkCmdPipelineBarrier(
                    cmd.vkCommandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    barrier
                )
            }

            val srcWidth = mipWidth.coerceAtLeast(1)
            val srcHeight = mipHeight.coerceAtLeast(1)
            mipWidth = (mipWidth / 2).coerceAtLeast(1)
            mipHeight = (mipHeight / 2).coerceAtLeast(1)

            val blit = VkImageBlit.calloc(1, stack)
                blit[0].srcSubresource { it ->
                    it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level - 1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                }
                blit[0].srcOffsets(0).set(0, 0, 0)
            blit[0].srcOffsets(1).set(srcWidth, srcHeight, 1)
            blit[0].dstSubresource { it
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level)
                    .baseArrayLayer(0)
                    .layerCount(1)
                }
            blit[0].dstOffsets(0).set(0, 0, 0)
            blit[0].dstOffsets(1).set(mipWidth, mipHeight, 1)

            VK10.vkCmdBlitImage(
                cmd.vkCommandBuffer,
                image.vkImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                image.vkImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                blit,
                mipFilter
            )

            // Transition level-1 to SHADER_READ_ONLY_OPTIMAL after blit
            run {
                val barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.vkImage)
                    .subresourceRange { it: VkImageSubresourceRange ->
                        it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(level - 1)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1)
                    }
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)

                VK10.vkCmdPipelineBarrier(
                    cmd.vkCommandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    barrier
                )
            }
        }

        // Transition last mip level to SHADER_READ_ONLY_OPTIMAL
        run {
            val barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image.vkImage)
                .subresourceRange { it: VkImageSubresourceRange ->
                    it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(targetMipLevels - 1)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1)
                }
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)

            VK10.vkCmdPipelineBarrier(
                cmd.vkCommandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0,
                null,
                null,
                barrier
            )
        }

        currentLayout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    }

    private fun recreateImage(header: Header, force: Boolean) {
        val desiredMips = chooseMipLevels(header, requestedMipLevels)
        if (!force && header.width == width && header.height == height && desiredMips == mipLevels) return

        // Clean up old GPU resources
        imageView.cleanup()
        image.cleanup()

        val usage = VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK10.VK_IMAGE_USAGE_SAMPLED_BIT or
                (if (generateMipmaps && desiredMips > 1) VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT else 0)

        val imageData: ImageData = ImageData()
            .width(header.width)
            .height(header.height)
            .usage(usage)
            .format(VK_FORMAT_R8_UNORM)
            .mipLevels(desiredMips)
            .debugName("LazyLoadedTexture ${fileName.substringAfterLast('/')} ${header.width}x${header.height}")

        image = Image(renderer.device, imageData)
        val imageViewData: ImageViewData =
            ImageViewData().format(image.format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(desiredMips)
                .debugName("LazyLoadedTextureView ${fileName.substringAfterLast('/')} ${header.width}x${header.height}")
        imageView = ImageView(renderer.device, image, imageViewData)

        width = header.width
        height = header.height
        mipLevels = desiredMips
        targetMipLevels = desiredMips
        recordedTransition = false
        currentLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED
    }

    companion object {
        private const val HEADER_BYTES = 3 * Integer.BYTES
        private const val MONO_BYTES_PER_PIXEL = 1

        fun fromFile(
            fileName: String,
            renderer: Renderer = fromContext(),
            name: String = fileName,
            headerByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
            headerOffset: Int = 0
        ): MappedMonochromeTexture {
            val mapped = mapReadOnly(Paths.get(fileName))
            return MappedMonochromeTexture(mapped, renderer, name, headerByteOrder, headerOffset)
        }

        private fun decodeInitial(mapped: ByteBuffer, headerOffset: Int, order: ByteOrder): DecodedImage {
            val header = readHeader(mapped, headerOffset, order)
            val expected = expectedBytes(header, mapped.capacity(), headerOffset)
            val payload = payloadSlice(mapped, headerOffset, expected)
            return DecodedImage(payload, header.width, header.height)
        }

        private fun chooseMipLevels(header: Header, requested: Int = 0): Int {
            val auto = 1 + 31 - Integer.numberOfLeadingZeros(max(header.width, header.height) - 1).coerceAtLeast(0)
            return if (requested <= 1) max(1, auto) else requested
        }

        private fun mapReadOnly(path: Path): ByteBuffer {
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                val size = ch.size()
                require(size > 0) { "File $path is empty" }
                return ch.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN)
            }
        }

        private fun readHeader(mapped: ByteBuffer, headerOffset: Int, order: ByteOrder): Header {
            val dup = mapped.duplicate().order(order)
            dup.position(headerOffset)
            val frame = dup.int.toLong()
            val width = dup.int
            val height = dup.int
            return Header(frame, width, height)
        }

        private fun expectedBytes(header: Header, capacity: Int, headerOffset: Int): Int {
            val pixels = header.width.toLong() * header.height.toLong()
            require(pixels >= 0) { "Invalid dimensions ${header.width}x${header.height}" }
            val required = pixels * MONO_BYTES_PER_PIXEL
            require(required <= Int.MAX_VALUE) { "Image too large: $required bytes" }
            val payloadStart = headerOffset + HEADER_BYTES
            require(capacity - payloadStart >= required) {
                "Mapped buffer too small: need ${payloadStart + required} bytes, have $capacity"
            }
            return required.toInt()
        }

        private fun payloadSlice(mapped: ByteBuffer, headerOffset: Int, expectedBytes: Int): ByteBuffer {
            val start = headerOffset + HEADER_BYTES
            val dup = mapped.duplicate()
            dup.position(start)
            dup.limit(start + expectedBytes)
            return dup.slice()
        }
    }
}

class StorageImage(val renderer: Renderer, val width: Int, val height: Int, val imageFormat: Int) : HasImageView {
    @Suppress("UNNECESSARY_LATEINIT")
    lateinit var image: Image
    @Suppress("UNNECESSARY_LATEINIT")
    lateinit override var imageView: ImageView

    var recordedTransition = false
    init {
        var buf: ByteBuffer? = null
        try {
            MemoryStack.stackPush().use { stack ->

                val imageData: ImageData =
                    ImageData().width(width).height(height).usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT).format(imageFormat)

                image = Image(renderer.device, imageData)

                val imageViewData: ImageViewData = ImageViewData().format(image.format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                imageView = ImageView(renderer.device, image, imageViewData)
            }
        } finally {
            if (buf != null) {
                stbi_image_free(buf)
            }
        }
    }

    private fun recordImageTransition(stack: MemoryStack, cmd: CommandBuffer, oldLayout: Int, newLayout: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0)
                    .layerCount(1)
            }

        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int

        if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_GENERAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            srcAccessMask = 0
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
        } else if (oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
        } else {
            throw RuntimeException("Unsupported layout transition")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    fun recordTextureTransition(cmd: CommandBuffer) {
        if (!recordedTransition) {
            recordedTransition = true
            MemoryStack.stackPush().use { stack ->
                recordImageTransition(
                    stack, cmd, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL
                )
            }
        }
    }

    fun transitionNow() {
        val cmd = CommandBuffer(renderer.commandPool, true, true)

        cmd.beginRecording()
        recordTextureTransition(cmd)
        cmd.endRecording()

        val fence = afield.four.device.Fence(renderer.device, true)
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence.fenceWait()
        fence.cleanup()
        cmd.cleanup()
    }


    fun cleanup() {
        imageView.cleanup()
        image.cleanup()
    }

}
