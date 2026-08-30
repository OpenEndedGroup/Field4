package afield.four.fabricated

import afield.Box
import afield.four.Renderer
import afield.four.Target
import afield.four.TexturePreviewCache
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.CommandPool
import afield.four.device.Fence
import afield.four.modules.AccelerationStructure
import afield.four.modules.AccelerationStructureAabbs
import afield.four.modules.AccelerationStructureTriangles
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.Parameters
import afield.four.modules.RayTracingDispatch
import afield.four.modules.RayTracingGroups
import afield.four.modules.RayTracingHitGroup
import afield.four.modules.RayTracingInstance
import afield.four.modules.RayTracingShaderStage
import afield.four.modules.ShaderKind
import afield.four.modules.fromContext
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.texture.TextureSampler
import afield.four.texture.image_layout_transition
import afield.four.util.Camera
import afield.four.util.Options
import afield.logger.lprintln
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import afield.stdlib.linalg.Vector3f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
import org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST
import org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.io.File
import java.util.ArrayDeque
import java.util.Base64
import kotlin.math.PI
import kotlin.math.tan

/**
 * Docs and smoke-test harness for ray tracing shader support.
 *
 * Every public render method runs a deterministic ray tracing example and captures
 * the output to a PNG.  If ray tracing is unavailable, or the module fails to build,
 * the method *throws*: it does not write a stand-in image.
 *
 * It used to write a "Ray Tracing / Not Available" card instead, so that docs builds
 * and tests would succeed on hardware with no RT support.  They did succeed -- and on
 * 2026-07-09 four of those cards were committed as the worked-example figures on
 * `public/docs/ray_tracing_examples.md`, under a page that claimed every image on it
 * was rendered by Field.  Nothing in the build could tell the difference, because a
 * placeholder is a perfectly valid PNG.  A capture that cannot happen is an honest
 * gap; a capture that quietly substitutes a picture of its own failure is not.
 *
 * `tools/check_doc_assets.py` is the backstop: it rejects a published figure whose
 * pixels match the card this harness used to write.
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
    private const val MIRROR_BALLS_REFLECTION_BOUNCES = 2
    private val liveScenes = java.util.WeakHashMap<Renderer, MutableMap<String, LiveMirrorBallsScene>>()

    class LiveMirrorBallsScene internal constructor(
        val name: String,
        private val renderer: Renderer,
        private val image: Image,
        private val imageView: ImageView,
        private val floorVertexBuffer: VulkanBuffer,
        private val sphereAabbBuffer: VulkanBuffer,
        private val floorBlas: AccelerationStructure,
        private val sphereBlas: AccelerationStructure,
        private val tlas: AccelerationStructure,
        private val rayTracingPointer: afield.four.modules.RayTracingModulePointer,
        private val blitPointer: afield.four.modules.ModulePointer,
        private val sampler: TextureSampler,
        var camera: Camera,
        private val spheres: MutableList<MirrorSphere>
    ) {
        data class MirrorSphere(
            var x: Float,
            var y: Float,
            var z: Float,
            var scale: Float
        )

        private var cleaned = false
        private val sphereLock = Any()
        private var revision = 0L
        private var builtRevision = 0L
        private val startedAtNanos = System.nanoTime()
        @Volatile private var animator: ((Double, LiveMirrorBallsScene) -> Unit)? = null

        val sphereCount: Int
            get() = synchronized(sphereLock) { spheres.size }

        fun sphere(index: Int): MirrorSphere = synchronized(sphereLock) {
            require(index in spheres.indices) { "sphere index $index is out of range 0..${spheres.lastIndex}" }
            spheres[index].copy()
        }

        fun setSphere(index: Int, x: Float, y: Float, z: Float): LiveMirrorBallsScene {
            val scale = synchronized(sphereLock) {
                require(index in spheres.indices) { "sphere index $index is out of range 0..${spheres.lastIndex}" }
                spheres[index].scale
            }
            return setSphere(index, x, y, z, scale)
        }

        fun setSphere(index: Int, x: Float, y: Float, z: Float, scale: Float): LiveMirrorBallsScene {
            synchronized(sphereLock) {
                require(index in spheres.indices) { "sphere index $index is out of range 0..${spheres.lastIndex}" }
                val s = spheres[index]
                if (s.x == x && s.y == y && s.z == z && s.scale == scale) return this
                s.x = x
                s.y = y
                s.z = z
                s.scale = scale
                revision++
            }
            return this
        }

        fun animate(block: (Double, LiveMirrorBallsScene) -> Unit): LiveMirrorBallsScene {
            animator = block
            return this
        }

        fun clearAnimation(): LiveMirrorBallsScene {
            animator = null
            return this
        }

        fun useCamera(camera: Camera): LiveMirrorBallsScene {
            this.camera = camera
            return this
        }

        internal fun updateTlas(command: CommandBuffer) {
            animator?.invoke((System.nanoTime() - startedAtNanos) / 1_000_000_000.0, this)
            val snapshot = synchronized(sphereLock) {
                if (builtRevision == revision) return
                builtRevision = revision
                mirrorBallsInstances(floorBlas, sphereBlas, spheres)
            }
            tlas.buildTopLevel(command, snapshot)
        }

        fun cleanup() {
            if (cleaned) return
            cleaned = true
            rayTracingPointer.disable()
            blitPointer.disable()
            renderer.computeList.rayTracingModules.remove(name)
            renderer.renderList.modules.remove("$name-blit")
            liveScenes[renderer]?.remove(name)
            rayTracingPointer.current.cleanup()
            blitPointer.current.cleanup()
            sampler.cleanup()
            imageView.cleanup()
            image.cleanup()
            floorVertexBuffer.cleanup()
            sphereAabbBuffer.cleanup()
            floorBlas.cleanup()
            sphereBlas.cleanup()
            tlas.cleanup()
        }
    }

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
     * Throws when ray tracing is not supported, rather than writing a stand-in PNG.
     */
    suspend fun renderUvGradient(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        if (!renderer.device.supportsRayTracing) {
            rayTracingUnavailable(outputPath, "UV Gradient")
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
            captureFailed(outputPath, "UV Gradient", "ray tracing module build failed: ${e.message}", e)
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup()
            imageView.cleanup()
            captureFailed(outputPath, "UV Gradient", "ray tracing module invalid: ${summarizeShaderErrors(errors)}")
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
     * Throws when ray tracing is not supported, rather than writing a stand-in PNG.
     */
    suspend fun renderTriangleScene(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        if (!renderer.device.supportsRayTracing) {
            rayTracingUnavailable(outputPath, "Triangle Scene")
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
            captureFailed(outputPath, "Triangle Scene", "ray tracing module build failed: ${e.message}", e)
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
            captureFailed(outputPath, "Triangle Scene", "ray tracing module invalid: ${summarizeShaderErrors(errors)}")
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
     * Throws when ray tracing is not supported, rather than writing a stand-in PNG.
     */
    suspend fun renderShaderTileScene(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        if (!renderer.device.supportsRayTracing) {
            rayTracingUnavailable(outputPath, "Shader Tiles")
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
            captureFailed(outputPath, "Shader Tiles", "ray tracing module build failed: ${e.message}", e)
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup(); imageView.cleanup()
            vertexBuffer.cleanup(); blas.cleanup(); tlas.cleanup()
            captureFailed(outputPath, "Shader Tiles", "ray tracing module invalid: ${summarizeShaderErrors(errors)}")
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
     * Throws when ray tracing is not supported, rather than writing a stand-in PNG.
     */
    suspend fun renderMirrorBallsScene(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        camera: Camera = cameraFromContextOrDefault(renderer)
    ): File {
        if (!renderer.device.supportsRayTracing) {
            rayTracingUnavailable(outputPath, "Mirror Balls")
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
        val sphereY = floorY + sphereScale
        val instances = listOf(
            RayTracingInstance(
                accelerationStructure = floorBlas,
                shaderBindingTableRecordOffset = 0
            ),
            RayTracingInstance(
                accelerationStructure = sphereBlas,
                transform = scaleTranslateTransform(sphereScale, 0.0f, sphereY, 2.35f),
                shaderBindingTableRecordOffset = 1
            ),
            RayTracingInstance(
                accelerationStructure = sphereBlas,
                transform = scaleTranslateTransform(sphereScale, -0.78f, sphereY, 1.72f),
                shaderBindingTableRecordOffset = 1
            ),
            RayTracingInstance(
                accelerationStructure = sphereBlas,
                transform = scaleTranslateTransform(sphereScale, 0.78f, sphereY, 1.72f),
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
            maxRayRecursionDepth = MIRROR_BALLS_REFLECTION_BOUNCES + 1,
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
            captureFailed(outputPath, "Mirror Balls", "ray tracing module build failed: ${e.message}", e)
        }

        if (!rtModule.valid || errors.isNotEmpty()) {
            lprintln("error,doc", "ray tracing module invalid: $errors")
            image.cleanup(); imageView.cleanup()
            floorVertexBuffer.cleanup(); sphereAabbBuffer.cleanup()
            floorBlas.cleanup(); sphereBlas.cleanup(); tlas.cleanup()
            captureFailed(outputPath, "Mirror Balls", "ray tracing module invalid: ${summarizeShaderErrors(errors)}")
        }

        val pointer = rtModule.pointer()
        pointer.seedRayCameraUniforms({ camera }) { OUTPUT_WIDTH.toFloat() / OUTPUT_HEIGHT.toFloat() }
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

    /**
     * Installs the mirror-balls ray tracing scene into the renderer window.
     *
     * Unlike [renderMirrorBallsScene], this does not capture or save a PNG. It
     * creates a persistent ray tracing storage image, dispatches the ray tracing
     * pipeline every frame through [Renderer.computeList], then blits the result
     * into the renderer's normal window render target. Call [LiveMirrorBallsScene.cleanup]
     * to remove it.
     */
    fun installMirrorBallsSceneInWindow(
        renderer: Renderer = rendererFromContextOrDocument(),
        camera: Camera = cameraFromContextOrDefault(renderer),
        name: String = "rt-live-mirror-balls",
        width: Int = renderer.window.width.coerceAtLeast(1),
        height: Int = renderer.window.height.coerceAtLeast(1)
    ): LiveMirrorBallsScene {
        require(renderer.device.supportsRayTracing) { "Device does not support VK_KHR_ray_tracing_pipeline." }

        liveScenes.getOrPut(renderer) { mutableMapOf() }.remove(name)?.cleanup()

        val (image, imageView) = createRtOutputImage(renderer, width, height)
        transitionImageToGeneral(renderer, image)

        val floorVertexBuffer = buildMirrorFloorVertexBuffer(renderer)
        val sphereAabbBuffer = buildSphereAabbBuffer(renderer)

        val floorBlas = AccelerationStructure(renderer, "$name-floor-blas")
            .buildBottomLevelNow(
                triangles = listOf(
                    AccelerationStructureTriangles(
                        vertexBuffer = floorVertexBuffer,
                        maxVertex = 5,
                        primitiveCount = 2
                    )
                )
            )
        val sphereBlas = AccelerationStructure(renderer, "$name-sphere-blas")
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
        val sphereY = floorY + sphereScale
        val sphereStates = mutableListOf(
            LiveMirrorBallsScene.MirrorSphere(0.0f, sphereY, 2.35f, sphereScale),
            LiveMirrorBallsScene.MirrorSphere(-0.78f, sphereY, 1.72f, sphereScale),
            LiveMirrorBallsScene.MirrorSphere(0.78f, sphereY, 1.72f, sphereScale)
        )
        val instances = mirrorBallsInstances(floorBlas, sphereBlas, sphereStates)

        val tlas = AccelerationStructure(renderer, "$name-tlas")
            .buildTopLevelNow(instances = instances)

        val errors = mutableListOf<String>()
        val p = Parameters(
            renderer = renderer,
            rayTracingGroups = mirrorBallsGroups(),
            maxRayRecursionDepth = MIRROR_BALLS_REFLECTION_BOUNCES + 1,
            name = name,
            imageResolver = { imageView },
            accelerationStructureResolver = { tlas },
            errorHandler = { msg, throwable ->
                errors += msg
                throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            }
        )

        val rtModule = afield.four.modules.RayTracingModule(p)
        if (!rtModule.valid || errors.isNotEmpty()) {
            image.cleanup(); imageView.cleanup()
            floorVertexBuffer.cleanup(); sphereAabbBuffer.cleanup()
            floorBlas.cleanup(); sphereBlas.cleanup(); tlas.cleanup()
            error("mirror-balls live ray tracing module invalid: ${summarizeShaderErrors(errors)}")
        }

        val layout = RtOutputLayoutTracker(image)
        val rtPointer = rtModule.pointer()
        rtPointer.current.before["$name-image-to-general"] = layout.transitionTarget(
            VK_IMAGE_LAYOUT_GENERAL,
            VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
            VK_ACCESS_2_SHADER_WRITE_BIT
        )
        rtPointer.current.after["$name-image-to-sampled"] = layout.transitionTarget(
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_2_SHADER_WRITE_BIT,
            VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
        )
        rtPointer += "dispatch" to RayTracingDispatch(rtPointer, width, height)

        val sampler = TextureSampler(
            renderer,
            imageView.mipLevels,
            false,
            VK_FILTER_NEAREST,
            VK_SAMPLER_MIPMAP_MODE_NEAREST
        )
        val blitPointer = installImageBlitToWindow(renderer, imageView, sampler, "$name-blit")

        val liveScene = LiveMirrorBallsScene(
            name = name,
            renderer = renderer,
            image = image,
            imageView = imageView,
            floorVertexBuffer = floorVertexBuffer,
            sphereAabbBuffer = sphereAabbBuffer,
            floorBlas = floorBlas,
            sphereBlas = sphereBlas,
            tlas = tlas,
            rayTracingPointer = rtPointer,
            blitPointer = blitPointer,
            sampler = sampler,
            camera = camera,
            spheres = sphereStates
        )
        rtPointer.seedRayCameraUniforms({ liveScene.camera }) { width.toFloat() / height.toFloat() }
        rtPointer.current.before["$name-update-tlas"] = Target { command, _ ->
            liveScene.updateTlas(command)
        }

        renderer += name to rtPointer

        return liveScene.also {
            liveScenes.getOrPut(renderer) { mutableMapOf() }[name] = it
        }
    }

    fun installMirrorBallsSceneInWindow(
        renderer: Renderer,
        name: String,
        width: Int = renderer.window.width.coerceAtLeast(1),
        height: Int = renderer.window.height.coerceAtLeast(1)
    ): LiveMirrorBallsScene = installMirrorBallsSceneInWindow(
        renderer = renderer,
        camera = cameraFromContextOrDefault(renderer),
        name = name,
        width = width,
        height = height
    )

    fun renderMirrorBallsSceneInWindow(
        renderer: Renderer = rendererFromContextOrDocument(),
        camera: Camera = cameraFromContextOrDefault(renderer),
        name: String = "rt-live-mirror-balls",
        width: Int = renderer.window.width.coerceAtLeast(1),
        height: Int = renderer.window.height.coerceAtLeast(1)
    ): LiveMirrorBallsScene = installMirrorBallsSceneInWindow(renderer, camera, name, width, height)

    fun renderMirrorBallsSceneInWindow(
        renderer: Renderer,
        name: String,
        width: Int = renderer.window.width.coerceAtLeast(1),
        height: Int = renderer.window.height.coerceAtLeast(1)
    ): LiveMirrorBallsScene = installMirrorBallsSceneInWindow(renderer, name, width, height)

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

    private fun createRtOutputImage(
        renderer: Renderer,
        width: Int = OUTPUT_WIDTH,
        height: Int = OUTPUT_HEIGHT
    ): Pair<Image, ImageView> {
        val data = ImageData()
            .width(width)
            .height(height)
            .format(VK_FORMAT_R8G8B8A8_UNORM)
            .usage(VK_IMAGE_USAGE_STORAGE_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
        val image = Image(renderer.device, data)
        val viewData = ImageViewData().format(image.format).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
        val imageView = ImageView(renderer.device, image, viewData)
        return image to imageView
    }

    private class RtOutputLayoutTracker(
        private val image: Image
    ) {
        private var currentLayout = VK_IMAGE_LAYOUT_GENERAL

        fun transitionTarget(
            nextLayout: Int,
            srcAccess: Long,
            dstAccess: Long
        ): Target = object : Target {
            override fun render(command: CommandBuffer, index: Int) {
                if (currentLayout == nextLayout) return
                MemoryStack.stackPush().use { stack ->
                    val range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
                    image_layout_transition(
                        command.vkCommandBuffer,
                        image.vkImage,
                        VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        srcAccess,
                        dstAccess,
                        currentLayout,
                        nextLayout,
                        range
                    )
                }
                currentLayout = nextLayout
            }
        }
    }

    private fun installImageBlitToWindow(
        renderer: Renderer,
        imageView: ImageView,
        sampler: TextureSampler,
        name: String
    ): afield.four.modules.ModulePointer {
        val sourceProvider = object : HasImageViewArray {
            override val imageViewArray: List<HasImageView>
                get() = listOf(imageView)
        }

        val module = Module(parameters(renderer) {
            this.name = name
            color = 1
            depth = 1
            depthFormat = Options.defaultDepthFormat
            depthTest = false
            blend = false
            vertex = RT_IMAGE_BLIT_VERTEX
            fragment = RT_IMAGE_BLIT_FRAGMENT
            textureResolver = { tex ->
                if (tex.name == "sourceTex") sourceProvider to sampler else null
            }
        }).pointer()

        val mesh = Mesh(renderer, module)
        mesh.resize(3, 0, module)
        module.current.meshes[name] = mesh
        renderer += name to module
        return module
    }

    private fun mirrorBallsGroups(): RayTracingGroups = RayTracingGroups(
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

    private fun afield.four.modules.RayTracingModulePointer.seedRayCameraUniforms(
        cameraProvider: () -> Camera,
        aspectProvider: () -> Float
    ) {
        current.liveUniformStructures["RayCamera"]?.let { rayCamera ->
            rayCamera.bind("origin") { Vector3f(cameraProvider().at) }
            rayCamera.bind("right") { Vector3f(cameraProvider().right) }
            rayCamera.bind("up") { rayCameraUp(cameraProvider()) }
            rayCamera.bind("forward") { Vector3f(cameraProvider().forward) }
            rayCamera.bind("tanHalfFovY") { tanHalfFovY(cameraProvider()) }
            rayCamera.bind("aspect") { aspectProvider().coerceAtLeast(0.0001f) }
        }
    }

    private fun rayCameraUp(camera: Camera): Vector3f {
        val right = camera.right
        val forward = camera.forward
        val up = Vector3f(right).cross(forward)
        if (up.lengthSquared() < 1e-12f) return Vector3f(0f, 1f, 0f)
        return up.normalize()
    }

    private fun tanHalfFovY(camera: Camera): Float {
        val configured = camera.p.fov
        val radians = if (configured > PI) Math.toRadians(configured) else configured
        return tan(0.5 * radians).toFloat()
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

    private fun mirrorBallsInstances(
        floorBlas: AccelerationStructure,
        sphereBlas: AccelerationStructure,
        spheres: List<LiveMirrorBallsScene.MirrorSphere>
    ): List<RayTracingInstance> = buildList {
        add(
            RayTracingInstance(
                accelerationStructure = floorBlas,
                shaderBindingTableRecordOffset = 0
            )
        )
        spheres.forEach { sphere ->
            add(
                RayTracingInstance(
                    accelerationStructure = sphereBlas,
                    transform = scaleTranslateTransform(sphere.scale, sphere.x, sphere.y, sphere.z),
                    shaderBindingTableRecordOffset = 1
                )
            )
        }
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

    private fun summarizeShaderErrors(errors: List<String>): String {
        if (errors.isEmpty()) return "shader error"
        return errors.joinToString(separator = " | ")
            .replace('\n', ' ')
            .take(120)
    }

    /**
     * A docs figure has no honest fallback.  When a capture cannot run, the build must
     * stop -- not hand back a picture that *looks* like a render and gets committed as
     * one.  See this object's KDoc for the four figures that reached `main` that way.
     */
    private fun rayTracingUnavailable(outputPath: String, scene: String): Nothing = error(
        "$scene: this device has no VK_KHR_ray_tracing_pipeline, so $outputPath was not " +
            "rendered. Nothing is written in its place -- run this capture on a machine with " +
            "ray tracing support (Linux CI does; MoltenVK on macOS does not)."
    )

    /** As [rayTracingUnavailable], but for a device that has RT and still could not render. */
    private fun captureFailed(
        outputPath: String,
        scene: String,
        detail: String,
        cause: Throwable? = null
    ): Nothing = throw IllegalStateException("$scene: $outputPath was not rendered -- $detail", cause)

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

    private fun cameraFromContextOrDefault(renderer: Renderer): Camera {
        try {
            return fromContext()
        } catch (_: IllegalArgumentException) {
        }
        return Camera(renderer).also {
            it.lookAt(
                Vector3f(0.0f, 0.18f, -3.45f),
                Vector3f(0.0f, -0.05f, 2.1f),
                Vector3f(0.0f, 1.0f, 0.0f)
            )
        }
    }

    // ── GLSL shaders ──────────────────────────────────────────────────────────

    private val RT_IMAGE_BLIT_VERTEX = """
        #version 460
        layout(location = 0) out vec2 fragUV;

        void main() {
            vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            fragUV = pos;
        }
    """.trimIndent()

    private val RT_IMAGE_BLIT_FRAGMENT = """
        #version 460
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;

        layout(set = 0, binding = 0) uniform sampler2D sourceTex;

        void main() {
            outColor = texture(sourceTex, fragUV);
        }
    """.trimIndent()

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

            vec3 origin = vec3(0.0, 2.0, -2.0);
            vec3 dir    = normalize(vec3(uv.x * 2.0 - 1.0,
                                         -(uv.y * 2.0 - 1.0) - 0.75,
                                          1.0));

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
        layout(set = 0, binding = 2) uniform RayCamera {
            vec3 origin;
            float tanHalfFovY;
            vec3 right;
            float aspect;
            vec3 up;
            float _pad0;
            vec3 forward;
            float _pad1;
        } rayCamera;

        void main() {
            const float rayEpsilon = 0.001;
            ivec2 px = ivec2(gl_LaunchIDEXT.xy);
            vec2 uv = (vec2(px) + 0.5) / vec2(max(gl_LaunchSizeEXT.xy, uvec2(1)));
            vec2 ndc = uv * 2.0 - 1.0;

            vec3 origin = rayCamera.origin;
            vec3 dir = normalize(rayCamera.forward
                               - ndc.x * rayCamera.aspect * rayCamera.tanHalfFovY * rayCamera.right
                               - ndc.y * rayCamera.tanHalfFovY * rayCamera.up);

            payload.color = vec3(0.02, 0.03, 0.05);
            payload.bouncesLeft = $MIRROR_BALLS_REFLECTION_BOUNCES;

            traceRayEXT(topLevelAS,
                        gl_RayFlagsOpaqueEXT,
                        0xFF,
                        0, 0, 0,
                        origin, rayEpsilon, dir, 100.0,
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
            const float checkerFrequency = 2.3;
            float checker = mod(floor(worldHit.x * checkerFrequency) + floor(worldHit.z * checkerFrequency), 2.0);

            vec3 lightTile = vec3(0.80, 0.80, 0.84);
            vec3 darkTile = vec3(0.11, 0.11, 0.13);
            vec3 tileColor = mix(lightTile, darkTile, checker);

            float distanceFade = clamp(1.15 - 0.12 * length(worldHit.xz - vec2(0.0, 2.0)), 0.48, 1.0);
            float stripe = 0.85 + 0.15 * smoothstep(0.0, 0.04, abs(fract(worldHit.x * checkerFrequency) - 0.5));
            payload.color = tileColor * distanceFade * stripe;
        }
    """.trimIndent()

    /** Analytic intersection shader for a unit sphere inside the procedural AABB BLAS. */
    val MIRROR_BALLS_SPHERE_INTERSECTION = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        hitAttributeEXT vec3 sphereNormal;

        void main() {
            const float rayEpsilon = 0.001;
            vec3 origin = gl_ObjectRayOriginEXT;
            vec3 dir = gl_ObjectRayDirectionEXT;

            // Solve a*t^2 + b*t + c = 0 for a ray against a unit sphere at the origin.
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
            if (t <= rayEpsilon) t = t1;
            if (t <= rayEpsilon) return;

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
            const float rayEpsilon = 0.001;
            vec3 worldHit = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
            vec3 normal = normalize(vec3(gl_ObjectToWorldEXT * vec4(sphereNormal, 0.0)));
            vec3 viewDir = normalize(-gl_WorldRayDirectionEXT);
            vec3 reflected = reflect(gl_WorldRayDirectionEXT, normal);

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
                        rayEpsilon,
                        reflected,
                        100.0,
                        0);

            vec3 reflectedColor = payload.color;
            payload.color = mix(baseMetal * 0.55, reflectedColor, 0.88 + 0.12 * fresnel);
        }
    """.trimIndent()
}
