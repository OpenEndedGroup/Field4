package afield.four.fabricated

import afield.Box
import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.CommandPool
import afield.four.device.Fence
import afield.four.modules.AccelerationStructure
import afield.four.modules.AccelerationStructureTriangles
import afield.four.modules.Parameters
import afield.four.modules.RayTracingDispatch
import afield.four.modules.RayTracingInstance
import afield.four.modules.fromContext
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.texture.image_layout_transition
import afield.logger.lprintln
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
import org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.ArrayDeque
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Docs and smoke-test harness for ray tracing shader support.
 *
 * Every public render method:
 * - Returns immediately with a "not supported" placeholder PNG when
 *   `renderer.device.supportsRayTracing` is false.  This lets tests and docs
 *   builds succeed on hardware that has no RT support.
 * - Runs a deterministic ray tracing example and captures the output to a
 *   PNG when RT is available.
 *
 * Usage pattern (mirrors StandardRendererDocsHarness):
 * ```kotlin
 * val image = RayTracingDocsHarness.renderUvGradient("/tmp/rt-uv.png")
 * display("render", RayTracingDocsHarness.htmlForImage(image, "UV gradient"))
 * println("ray tracing example ready")
 * ```
 */
object RayTracingDocsHarness {

    private const val WAIT_FRAMES = 60
    private const val OUTPUT_WIDTH = 512
    private const val OUTPUT_HEIGHT = 512
    private const val CAPTURE_MAX_DIM = 2048

    // ── Public render methods ─────────────────────────────────────────────────

    /**
     * Renders a UV-gradient image using a raygen-only ray tracing pipeline.
     *
     * The raygen shader writes UV coordinates directly to a storage image
     * without calling traceRayEXT, so no acceleration structure is needed.
     * This is the simplest possible ray tracing example: the pipeline behaves
     * like a compute shader that happens to use the VK_KHR_ray_tracing_pipeline
     * entry-point type.
     *
     * Returns a placeholder PNG when ray tracing is not supported.
     */
    suspend fun renderUvGradient(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        if (!renderer.device.supportsRayTracing) {
            lprintln("info,doc", "ray tracing not supported on this device – writing placeholder for $outputPath")
            return writeNotSupportedPlaceholder(outputPath, "UV Gradient (RT not available)")
        }

        val (image, imageView) = createRtOutputImage(renderer)
        transitionImageToGeneral(renderer, image)

        val rtName = "rt-docs-uv-${System.nanoTime()}"
        val errors = mutableListOf<String>()

        val p = Parameters(
            renderer = renderer,
            raygen = UV_GRADIENT_RAYGEN,
            name = rtName,
            imageResolver = { imageView },
            errorHandler = { msg, _ -> errors += msg }
        )

        val rtModule = try {
            afield.four.modules.RayTracingModule(p)
        } catch (e: Exception) {
            lprintln("error,doc", "ray tracing module build failed: ${e.message}")
            image.cleanup()
            imageView.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "UV Gradient (build failed: ${e.message?.take(60)})")
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup()
            imageView.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "UV Gradient (shader error)")
        }

        val pointer = rtModule.pointer()
        val dispatch = RayTracingDispatch(pointer, OUTPUT_WIDTH, OUTPUT_HEIGHT)
        pointer += "dispatch" to dispatch

        renderer += rtName to pointer
        try {
            waitForRendererFrames(renderer)
            return captureRtImage(renderer, image, outputPath)
        } finally {
            pointer.disable()
            renderer.computeList.rayTracingModules.remove(rtName)
            image.cleanup()
            imageView.cleanup()
        }
    }

    /**
     * Renders a triangle scene using a full ray tracing pipeline:
     * raygen → miss → closesthit.
     *
     * The geometry is a single triangle assembled into a BLAS and wrapped in a
     * TLAS.  The raygen shader traces rays from a fixed camera position; the
     * closesthit shader writes barycentric coordinates as RGB; the miss shader
     * writes a dark-blue background.
     *
     * Returns a placeholder PNG when ray tracing is not supported.
     */
    suspend fun renderTriangleScene(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        if (!renderer.device.supportsRayTracing) {
            lprintln("info,doc", "ray tracing not supported on this device – writing placeholder for $outputPath")
            return writeNotSupportedPlaceholder(outputPath, "Triangle Scene (RT not available)")
        }

        val (image, imageView) = createRtOutputImage(renderer)
        transitionImageToGeneral(renderer, image)

        val vertexBuffer = buildTriangleVertexBuffer(renderer)
        val blas = AccelerationStructure(renderer, "rt-docs-triangle-blas")
            .buildBottomLevelNow(
                triangles = listOf(
                    AccelerationStructureTriangles(
                        vertexBuffer = vertexBuffer,
                        maxVertex = 2,
                        primitiveCount = 1
                    )
                )
            )
        val tlas = AccelerationStructure(renderer, "rt-docs-triangle-tlas")
            .buildTopLevelNow(instances = listOf(RayTracingInstance(blas)))

        val rtName = "rt-docs-tri-${System.nanoTime()}"
        val errors = mutableListOf<String>()

        val p = Parameters(
            renderer = renderer,
            raygen = TRIANGLE_RAYGEN,
            miss = TRIANGLE_MISS,
            closesthit = TRIANGLE_CLOSESTHIT,
            name = rtName,
            imageResolver = { imageView },
            accelerationStructureResolver = { tlas },
            errorHandler = { msg, _ -> errors += msg }
        )

        val rtModule = try {
            afield.four.modules.RayTracingModule(p)
        } catch (e: Exception) {
            lprintln("error,doc", "ray tracing module build failed: ${e.message}")
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "Triangle Scene (build failed: ${e.message?.take(60)})")
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "Triangle Scene (shader error)")
        }

        val pointer = rtModule.pointer()
        val dispatch = RayTracingDispatch(pointer, OUTPUT_WIDTH, OUTPUT_HEIGHT)
        pointer += "dispatch" to dispatch

        renderer += rtName to pointer
        try {
            waitForRendererFrames(renderer)
            return captureRtImage(renderer, image, outputPath)
        } finally {
            pointer.disable()
            renderer.computeList.rayTracingModules.remove(rtName)
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
        }
    }

    /**
     * Renders a simple plane where checker tiles are computed in shaders.
     *
     * Geometry is just two triangles (one quad). The closest-hit shader computes
     * tile color from world hit position, so no tile mesh splitting is required.
     *
     * Returns a placeholder PNG when ray tracing is not supported.
     */
    suspend fun renderShaderTileScene(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        if (!renderer.device.supportsRayTracing) {
            lprintln("info,doc", "ray tracing not supported on this device – writing placeholder for $outputPath")
            return writeNotSupportedPlaceholder(outputPath, "Shader Tiles (RT not available)")
        }

        val (image, imageView) = createRtOutputImage(renderer)
        transitionImageToGeneral(renderer, image)

        val vertexBuffer = buildQuadVertexBuffer(renderer)
        val blas = AccelerationStructure(renderer, "rt-docs-shader-tiles-blas")
            .buildBottomLevelNow(
                triangles = listOf(
                    AccelerationStructureTriangles(
                        vertexBuffer = vertexBuffer,
                        maxVertex = 5,
                        primitiveCount = 2
                    )
                )
            )
        val tlas = AccelerationStructure(renderer, "rt-docs-shader-tiles-tlas")
            .buildTopLevelNow(instances = listOf(RayTracingInstance(blas)))

        val rtName = "rt-docs-shader-tiles-${System.nanoTime()}"
        val errors = mutableListOf<String>()

        val p = Parameters(
            renderer = renderer,
            raygen = SHADER_TILES_RAYGEN,
            miss = SHADER_TILES_MISS,
            closesthit = SHADER_TILES_CLOSESTHIT,
            name = rtName,
            imageResolver = { imageView },
            accelerationStructureResolver = { tlas },
            errorHandler = { msg, _ -> errors += msg }
        )

        val rtModule = try {
            afield.four.modules.RayTracingModule(p)
        } catch (e: Exception) {
            lprintln("error,doc", "ray tracing module build failed: ${e.message}")
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "Shader Tiles (build failed: ${e.message?.take(60)})")
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "Shader Tiles (shader error)")
        }

        val pointer = rtModule.pointer()
        val dispatch = RayTracingDispatch(pointer, OUTPUT_WIDTH, OUTPUT_HEIGHT)
        pointer += "dispatch" to dispatch

        renderer += rtName to pointer
        try {
            waitForRendererFrames(renderer)
            return captureRtImage(renderer, image, outputPath)
        } finally {
            pointer.disable()
            renderer.computeList.rayTracingModules.remove(rtName)
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
        }
    }

    /** Wraps a captured PNG as an HTML block suitable for display(...). */
    fun htmlForImage(imageFile: File, caption: String? = null): String {
        val encoded = Base64.getEncoder().encodeToString(imageFile.readBytes())
        val captionHtml = caption?.takeIf { it.isNotBlank() }?.let {
            """<div style="margin-top:10px;color:#b8b8b8;font-size:13px;">$it</div>"""
        } ?: ""
        return """
            <div style="padding:16px;background:#111;border:1px solid #2f2f2f;">
                <img
                    src="data:image/png;base64,$encoded"
                    alt="RayTracing capture"
                    style="display:block;width:100%;height:auto;background:#111;"
                />
                $captionHtml
            </div>
        """.trimIndent()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun createRtOutputImage(renderer: Renderer): Pair<Image, ImageView> {
        val data = ImageData()
            .width(OUTPUT_WIDTH)
            .height(OUTPUT_HEIGHT)
            .format(VK_FORMAT_R8G8B8A8_UNORM)
            .usage(VK_IMAGE_USAGE_STORAGE_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
        val image = Image(renderer.device, data)
        val viewData = ImageViewData().format(image.format).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
        val imageView = ImageView(renderer.device, image, viewData)
        return image to imageView
    }

    private fun transitionImageToGeneral(renderer: Renderer, image: Image) {
        val pool = CommandPool(renderer.device, renderer.graphQueue.queueFamilyIndex)
        val cmd = CommandBuffer(pool, primary = true, oneTimeSubmit = true)
        val fence = Fence(renderer.device, signaled = false)
        try {
            cmd.beginRecording()
            MemoryStack.stackPush().use { stack ->
                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
                image_layout_transition(
                    cmd.vkCommandBuffer,
                    image.vkImage,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    0L,
                    VK_ACCESS_2_SHADER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL,
                    range
                )
            }
            cmd.endRecording()
            MemoryStack.stackPush().use { stack ->
                renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
            }
            fence.fenceWait()
        } finally {
            fence.cleanup()
            cmd.cleanup()
            pool.cleanup()
        }
    }

    private fun buildTriangleVertexBuffer(renderer: Renderer): VulkanBuffer {
        val floats = floatArrayOf(
            -0.5f, -0.5f, 0.0f,   // bottom-left
             0.5f, -0.5f, 0.0f,   // bottom-right
             0.0f,  0.5f, 0.0f    // top-center
        )
        val buffer = VulkanBuffer(
            renderer = renderer,
            requestedSize = floats.size * 4L,
            usage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR or
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            reqMask = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            needsDeviceAddress = true
        )
        buffer.mapAndWrite_bytes { asFloatBuffer().put(floats) }
        return buffer
    }

    private fun buildQuadVertexBuffer(renderer: Renderer): VulkanBuffer {
        val half = 1.25f
        val y = -0.2f
        val zNear = 0.0f
        val zFar = 2.3f
        val floats = floatArrayOf(
            -half, y, zNear,
             half, y, zNear,
             half, y, zFar,

            -half, y, zNear,
             half, y, zFar,
            -half, y, zFar
        )
        val buffer = VulkanBuffer(
            renderer = renderer,
            requestedSize = floats.size * 4L,
            usage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR or
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            reqMask = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            needsDeviceAddress = true
        )
        buffer.mapAndWrite_bytes { asFloatBuffer().put(floats) }
        return buffer
    }

    private suspend fun captureRtImage(renderer: Renderer, image: Image, outputPath: String): File {
        val targetFile = File(outputPath)
        targetFile.parentFile?.mkdirs()
        targetFile.delete()

        val DATA_IMAGE_RE = Regex("base64,([^']+)")
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = image.vkImage,
            format = image.format,
            srcWidth = OUTPUT_WIDTH,
            srcHeight = OUTPUT_HEIGHT,
            currentLayout = VK_IMAGE_LAYOUT_GENERAL,
            maxDim = CAPTURE_MAX_DIM
        ) ?: error("could not read back ray tracing output image")

        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1)
            ?: error("could not parse ray tracing output image data")
        targetFile.writeBytes(Base64.getDecoder().decode(encoded))
        require(targetFile.length() > 0L) { "ray tracing image readback wrote an empty PNG" }
        return targetFile
    }

    private suspend fun waitForRendererFrames(renderer: Renderer) {
        val startFrame = renderer.frameCounter
        val targetFrame = startFrame + WAIT_FRAMES
        val deadline = System.nanoTime() + 10_000_000_000L
        var lastLogged = Long.MIN_VALUE
        while (renderer.frameCounter < targetFrame) {
            if (renderer.frameCounter != lastLogged) {
                lastLogged = renderer.frameCounter
                lprintln("info,doc", " -- RT docs waiting frame ${renderer.frameCounter}/$targetFrame --")
            }
            if (System.nanoTime() > deadline) {
                error("timed out waiting for ray tracing frames: start=$startFrame current=${renderer.frameCounter} target=$targetFrame")
            }
            delay(16)
            yield()
        }
    }

    private fun writeNotSupportedPlaceholder(outputPath: String, label: String): File {
        val w = 512; val h = 512
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = bi.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x1a, 0x1a, 0x22)
            g.fillRect(0, 0, w, h)
            g.color = Color(0x44, 0x44, 0x55)
            val step = 32
            var x = 0
            while (x < w) { g.drawLine(x, 0, x, h); x += step }
            var y = 0
            while (y < h) { g.drawLine(0, y, w, y); y += step }
            g.color = Color(0x88, 0x88, 0xaa)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
            val fm = g.fontMetrics
            val lines = listOf("Ray Tracing", "Not Available", label)
            val totalH = lines.size * fm.height
            var ty = (h - totalH) / 2 + fm.ascent
            for (line in lines) {
                val tw = fm.stringWidth(line)
                g.drawString(line, (w - tw) / 2, ty)
                ty += fm.height
            }
        } finally {
            g.dispose()
        }
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        ImageIO.write(bi, "png", file)
        return file
    }

    private fun rendererFromContextOrDocument(): Renderer {
        try {
            return fromContext()
        } catch (_: IllegalArgumentException) {
        }
        val start = Box.context.get()
        val root = start.root
        val seen = mutableSetOf<Box>()
        val queue = ArrayDeque<Box>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val box = queue.removeFirst()
            if (!seen.add(box)) continue
            box.properties.values.firstOrNull { it is Renderer }?.let { return it as Renderer }
            box.ki.context.vars.values.firstOrNull { it.value is Renderer }?.let { return it.value as Renderer }
            box.children.forEach { queue.add(it) }
        }
        throw IllegalArgumentException("must pass in a value for context parameter ${Renderer::class.java.name}")
    }

    // ── GLSL shaders ──────────────────────────────────────────────────────────

    /** Raygen that writes UV colours to a storage image (no traceRayEXT call). */
    val UV_GRADIENT_RAYGEN = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(set = 0, binding = 0, rgba8) uniform image2D outputImage;

        void main() {
            ivec2 px  = ivec2(gl_LaunchIDEXT.xy);
            vec2  uv  = (vec2(px) + 0.5) / vec2(max(gl_LaunchSizeEXT.xy, uvec2(1)));
            imageStore(outputImage, px, vec4(uv, 0.2, 1.0));
        }
    """.trimIndent()

    /** Raygen for the triangle scene: traces one ray per pixel. */
    val TRIANGLE_RAYGEN = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadEXT vec3 payload;
        layout(set = 0, binding = 0, rgba8)  uniform image2D              outputImage;
        layout(set = 0, binding = 1)          uniform accelerationStructureEXT topLevelAS;

        void main() {
            ivec2 px  = ivec2(gl_LaunchIDEXT.xy);
            vec2  uv  = (vec2(px) + 0.5) / vec2(max(gl_LaunchSizeEXT.xy, uvec2(1)));

            vec3 origin = vec3(0.0, 0.0, -2.0);
            vec3 dir    = normalize(vec3((uv * 2.0 - 1.0) * vec2(1.0, -1.0), 1.0));

            payload = vec3(0.05, 0.05, 0.12);
            traceRayEXT(topLevelAS,
                        gl_RayFlagsOpaqueEXT,
                        0xFF,       // cull mask
                        0, 0, 0,    // sbt offsets / stride / miss index
                        origin, 0.001, dir, 100.0,
                        0);         // payload location

            imageStore(outputImage, px, vec4(payload, 1.0));
        }
    """.trimIndent()

    /** Miss shader: returns a dark-blue background. */
    val TRIANGLE_MISS = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadInEXT vec3 payload;

        void main() {
            payload = vec3(0.05, 0.05, 0.12);
        }
    """.trimIndent()

    /** Closest-hit shader: visualises barycentric coordinates as RGB. */
    val TRIANGLE_CLOSESTHIT = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadInEXT vec3 payload;
        hitAttributeEXT vec2 baryCoord;

        void main() {
            float w = 1.0 - baryCoord.x - baryCoord.y;
            payload  = vec3(w, baryCoord.x, baryCoord.y);
        }
    """.trimIndent()

    /** Raygen for shader-tile scene: traces one ray per pixel toward a floor quad. */
    val SHADER_TILES_RAYGEN = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadEXT vec3 payload;
        layout(set = 0, binding = 0, rgba8)  uniform image2D              outputImage;
        layout(set = 0, binding = 1)          uniform accelerationStructureEXT topLevelAS;

        void main() {
            ivec2 px  = ivec2(gl_LaunchIDEXT.xy);
            vec2  uv  = (vec2(px) + 0.5) / vec2(max(gl_LaunchSizeEXT.xy, uvec2(1)));

            vec3 origin = vec3(0.0, 0.45, -2.6);
            vec3 dir    = normalize(vec3((uv * 2.0 - 1.0) * vec2(1.05, -0.72), 1.0));

            payload = vec3(0.03, 0.04, 0.08);
            traceRayEXT(topLevelAS,
                        gl_RayFlagsOpaqueEXT,
                        0xFF,
                        0, 0, 0,
                        origin, 0.001, dir, 100.0,
                        0);

            imageStore(outputImage, px, vec4(payload, 1.0));
        }
    """.trimIndent()

    /** Miss shader for shader-tile scene. */
    val SHADER_TILES_MISS = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadInEXT vec3 payload;

        void main() {
            payload = vec3(0.03, 0.04, 0.08);
        }
    """.trimIndent()

    /** Closest-hit shader that computes checker tiles procedurally from world hit position. */
    val SHADER_TILES_CLOSESTHIT = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadInEXT vec3 payload;
        hitAttributeEXT vec2 baryCoord;

        void main() {
            vec3 worldHit = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
            float tileScale = 3.6;
            float checker = mod(abs(floor(worldHit.x * tileScale)) + abs(floor(worldHit.z * tileScale)), 2.0);

            vec3 lightTile = vec3(0.78, 0.78, 0.82);
            vec3 darkTile = vec3(0.22, 0.22, 0.26);
            vec3 tileColor = mix(lightTile, darkTile, checker);

            float vignette = clamp(1.0 - 0.13 * length(worldHit.xz), 0.6, 1.0);
            payload = tileColor * vignette;
        }
    """.trimIndent()
}
