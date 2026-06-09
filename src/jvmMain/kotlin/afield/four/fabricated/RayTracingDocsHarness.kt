package afield.four.fabricated

import afield.Box
import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.CommandPool
import afield.four.device.Fence
import afield.four.modules.AccelerationStructure
import afield.four.modules.AccelerationStructureAabbs
import afield.four.modules.AccelerationStructureTriangles
import afield.four.modules.Parameters
import afield.four.modules.RayTracingDispatch
import afield.four.modules.RayTracingGroups
import afield.four.modules.RayTracingHitGroup
import afield.four.modules.RayTracingInstance
import afield.four.modules.RayTracingShaderStage
import afield.four.modules.ShaderKind
import afield.four.modules.fromContext
import afield.four.modules.pointer
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
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
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
            return writeNotSupportedPlaceholder(outputPath, "UV Gradient (${summarizeShaderErrors(errors)})")
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
            return writeNotSupportedPlaceholder(outputPath, "Triangle Scene (${summarizeShaderErrors(errors)})")
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
            return writeNotSupportedPlaceholder(outputPath, "Shader Tiles (${summarizeShaderErrors(errors)})")
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
     * Renders an Amiga Juggler-inspired scene: three reflective spheres above a
     * checkerboard floor.
     *
     * The floor uses a triangle hit group while the spheres are a procedural
     * AABB BLAS resolved by an intersection shader. Sphere instances select
     * their mirror-material hit group through SBT record offsets.
     *
     * Returns a placeholder PNG when ray tracing is not supported.
     */
    suspend fun renderMirrorBallsScene(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        if (!renderer.device.supportsRayTracing) {
            lprintln("info,doc", "ray tracing not supported on this device – writing placeholder for $outputPath")
            return writeNotSupportedPlaceholder(outputPath, "Mirror Balls (RT not available)")
        }

        val (image, imageView) = createRtOutputImage(renderer)
        transitionImageToGeneral(renderer, image)

        val floorVertexBuffer = buildMirrorFloorVertexBuffer(renderer)
        val sphereAabbBuffer = buildSphereAabbBuffer(renderer)

        val floorBlas = AccelerationStructure(renderer, "rt-docs-mirror-floor-blas")
            .buildBottomLevelNow(
                triangles = listOf(
                    AccelerationStructureTriangles(
                        vertexBuffer = floorVertexBuffer,
                        maxVertex = 5,
                        primitiveCount = 2
                    )
                )
            )
        val sphereBlas = AccelerationStructure(renderer, "rt-docs-mirror-sphere-blas")
            .buildBottomLevelNow(
                aabbs = listOf(
                    AccelerationStructureAabbs(
                        aabbBuffer = sphereAabbBuffer,
                        primitiveCount = 1
                    )
                )
            )

        val floorY = -0.62f
        val sphereScale = 0.34f
        val instances = listOf(
            RayTracingInstance(
                accelerationStructure = floorBlas,
                shaderBindingTableRecordOffset = 0
            ),
            RayTracingInstance(
                accelerationStructure = sphereBlas,
                transform = scaleTranslateTransform(sphereScale, 0.0f, floorY + sphereScale, 2.35f),
                shaderBindingTableRecordOffset = 1
            ),
            RayTracingInstance(
                accelerationStructure = sphereBlas,
                transform = scaleTranslateTransform(sphereScale, -0.78f, floorY + sphereScale, 1.72f),
                shaderBindingTableRecordOffset = 1
            ),
            RayTracingInstance(
                accelerationStructure = sphereBlas,
                transform = scaleTranslateTransform(sphereScale, 0.78f, floorY + sphereScale, 1.72f),
                shaderBindingTableRecordOffset = 1
            )
        )

        val tlas = AccelerationStructure(renderer, "rt-docs-mirror-balls-tlas")
            .buildTopLevelNow(instances = instances)

        val rtName = "rt-docs-mirror-balls-${System.nanoTime()}"
        val errors = mutableListOf<String>()

        val groups = RayTracingGroups(
            raygen = RayTracingShaderStage("raygen", ShaderKind.RAYGEN_SHADER, MIRROR_BALLS_RAYGEN, sourceName = "mirror_balls.rgen"),
            miss = mutableListOf(
                RayTracingShaderStage("miss", ShaderKind.MISS_SHADER, MIRROR_BALLS_MISS, sourceName = "mirror_balls.rmiss")
            ),
            hit = mutableListOf(
                RayTracingHitGroup(
                    name = "checker-floor",
                    closestHit = RayTracingShaderStage(
                        "floorClosestHit",
                        ShaderKind.CLOSEST_HIT_SHADER,
                        MIRROR_BALLS_FLOOR_CLOSESTHIT,
                        sourceName = "mirror_balls_floor.rchit"
                    )
                ),
                RayTracingHitGroup(
                    name = "mirror-spheres",
                    closestHit = RayTracingShaderStage(
                        "sphereClosestHit",
                        ShaderKind.CLOSEST_HIT_SHADER,
                        MIRROR_BALLS_SPHERE_CLOSESTHIT,
                        sourceName = "mirror_balls_sphere.rchit"
                    ),
                    intersection = RayTracingShaderStage(
                        "sphereIntersection",
                        ShaderKind.INTERSECTION_SHADER,
                        MIRROR_BALLS_SPHERE_INTERSECTION,
                        sourceName = "mirror_balls_sphere.rint"
                    )
                )
            )
        )

        val p = Parameters(
            renderer = renderer,
            rayTracingGroups = groups,
            maxRayRecursionDepth = 2,
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
            floorVertexBuffer.cleanup(); sphereAabbBuffer.cleanup()
            floorBlas.cleanup(); sphereBlas.cleanup(); tlas.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "Mirror Balls (build failed: ${e.message?.take(60)})")
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup(); imageView.cleanup()
            floorVertexBuffer.cleanup(); sphereAabbBuffer.cleanup()
            floorBlas.cleanup(); sphereBlas.cleanup(); tlas.cleanup()
            return writeNotSupportedPlaceholder(outputPath, "Mirror Balls (${summarizeShaderErrors(errors)})")
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
            floorVertexBuffer.cleanup(); sphereAabbBuffer.cleanup()
            floorBlas.cleanup(); sphereBlas.cleanup(); tlas.cleanup()
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

    private fun buildMirrorFloorVertexBuffer(renderer: Renderer): VulkanBuffer {
        val half = 2.7f
        val y = -0.62f
        val zNear = 0.2f
        val zFar = 4.8f
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

    private fun buildSphereAabbBuffer(renderer: Renderer): VulkanBuffer {
        val floats = floatArrayOf(
            -1f, -1f, -1f,
             1f,  1f,  1f
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

    private fun scaleTranslateTransform(scale: Float, tx: Float, ty: Float, tz: Float): FloatArray = floatArrayOf(
        scale, 0f, 0f, tx,
        0f, scale, 0f, ty,
        0f, 0f, scale, tz
    )

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

    private fun summarizeShaderErrors(errors: List<String>): String {
        if (errors.isEmpty()) return "shader error"
        return errors.joinToString(separator = " | ")
            .replace('\n', ' ')
            .take(120)
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

    /** Raygen for the mirror-balls scene: traces one primary ray per pixel. */
    val MIRROR_BALLS_RAYGEN = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        struct Payload {
            vec3 color;
            int bouncesLeft;
        };

        layout(location = 0) rayPayloadEXT Payload payload;
        layout(set = 0, binding = 0, rgba8) uniform image2D outputImage;
        layout(set = 0, binding = 1) uniform accelerationStructureEXT topLevelAS;

        void main() {
            ivec2 px = ivec2(gl_LaunchIDEXT.xy);
            vec2 uv = (vec2(px) + 0.5) / vec2(max(gl_LaunchSizeEXT.xy, uvec2(1)));
            vec2 ndc = uv * 2.0 - 1.0;

            vec3 origin = vec3(0.0, 0.18, -3.45);
            vec3 dir = normalize(vec3(ndc.x * 1.05, -ndc.y * 0.78 + 0.08, 1.0));

            payload.color = vec3(0.02, 0.03, 0.05);
            payload.bouncesLeft = 1;

            traceRayEXT(topLevelAS,
                        gl_RayFlagsOpaqueEXT,
                        0xFF,
                        0, 0, 0,
                        origin, 0.001, dir, 100.0,
                        0);

            vec3 color = pow(clamp(payload.color, vec3(0.0), vec3(1.0)), vec3(0.95));
            imageStore(outputImage, px, vec4(color, 1.0));
        }
    """.trimIndent()

    /** Miss shader for the mirror-balls scene. */
    val MIRROR_BALLS_MISS = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        struct Payload {
            vec3 color;
            int bouncesLeft;
        };

        layout(location = 0) rayPayloadInEXT Payload payload;

        void main() {
            vec3 d = normalize(gl_WorldRayDirectionEXT);
            float horizon = clamp(0.5 + 0.5 * d.y, 0.0, 1.0);
            vec3 skyLow = vec3(0.06, 0.07, 0.10);
            vec3 skyHigh = vec3(0.22, 0.24, 0.30);
            payload.color = mix(skyLow, skyHigh, pow(horizon, 0.8));
        }
    """.trimIndent()

    /** Closest-hit shader for the checkerboard floor. */
    val MIRROR_BALLS_FLOOR_CLOSESTHIT = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        struct Payload {
            vec3 color;
            int bouncesLeft;
        };

        layout(location = 0) rayPayloadInEXT Payload payload;
        hitAttributeEXT vec2 baryCoord;

        void main() {
            vec3 worldHit = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
            float checker = mod(floor(worldHit.x * 2.3) + floor(worldHit.z * 2.3), 2.0);

            vec3 lightTile = vec3(0.80, 0.80, 0.84);
            vec3 darkTile = vec3(0.11, 0.11, 0.13);
            vec3 tileColor = mix(lightTile, darkTile, checker);

            float distanceFade = clamp(1.15 - 0.12 * length(worldHit.xz - vec2(0.0, 2.0)), 0.48, 1.0);
            float stripe = 0.85 + 0.15 * smoothstep(0.0, 0.04, abs(fract(worldHit.x * 2.3) - 0.5));
            payload.color = tileColor * distanceFade * stripe;
        }
    """.trimIndent()

    /** Analytic intersection shader for a unit sphere inside the procedural AABB BLAS. */
    val MIRROR_BALLS_SPHERE_INTERSECTION = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        hitAttributeEXT vec3 sphereNormal;

        void main() {
            vec3 origin = gl_ObjectRayOriginEXT;
            vec3 dir = gl_ObjectRayDirectionEXT;

            float a = dot(dir, dir);
            float b = 2.0 * dot(origin, dir);
            float c = dot(origin, origin) - 1.0;
            float disc = b * b - 4.0 * a * c;
            if (disc < 0.0) return;

            float rootDisc = sqrt(disc);
            float invDenom = 0.5 / a;
            float t0 = (-b - rootDisc) * invDenom;
            float t1 = (-b + rootDisc) * invDenom;
            float t = t0;
            if (t <= 0.001) t = t1;
            if (t <= 0.001) return;

            vec3 hit = origin + t * dir;
            sphereNormal = normalize(hit);
            reportIntersectionEXT(t, 0);
        }
    """.trimIndent()

    /** Closest-hit shader for reflective spheres. */
    val MIRROR_BALLS_SPHERE_CLOSESTHIT = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        struct Payload {
            vec3 color;
            int bouncesLeft;
        };

        layout(location = 0) rayPayloadInEXT Payload payload;
        layout(set = 0, binding = 1) uniform accelerationStructureEXT topLevelAS;
        hitAttributeEXT vec3 sphereNormal;

        void main() {
            vec3 worldHit = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
            vec3 normal = normalize(gl_ObjectToWorldEXT * vec4(sphereNormal, 0.0)).xyz;
            vec3 viewDir = normalize(-gl_WorldRayDirectionEXT);
            vec3 reflected = normalize(reflect(gl_WorldRayDirectionEXT, normal));

            vec3 skyTint = mix(vec3(0.16, 0.18, 0.22), vec3(0.55, 0.57, 0.62), clamp(0.5 + 0.5 * reflected.y, 0.0, 1.0));
            float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), 5.0);
            vec3 baseMetal = vec3(0.72, 0.74, 0.78);
            vec3 fallback = mix(baseMetal, skyTint, 0.75);

            if (payload.bouncesLeft <= 0) {
                payload.color = fallback;
                return;
            }

            payload.bouncesLeft -= 1;
            payload.color = fallback;

            traceRayEXT(topLevelAS,
                        gl_RayFlagsOpaqueEXT,
                        0xFF,
                        0, 0, 0,
                        worldHit + normal * 0.01,
                        0.001,
                        reflected,
                        100.0,
                        0);

            vec3 reflectedColor = payload.color;
            payload.color = mix(baseMetal * 0.55, reflectedColor, 0.88 + 0.12 * fresnel);
        }
    """.trimIndent()
}
