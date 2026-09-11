@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.fabricated

import afield.Rect
import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.BufferParameters
import afield.four.buffers.DynamicBuffer
import afield.four.buffers.buffer
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.four.targets.StereoCamera
import afield.four.util.Camera
import afield.four.util.GpuRadixSort
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_DST_COLOR
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ZERO
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_MAX
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rendering a Gaussian splat capture: PLY reading, packing, depth sorting and the built-in
 * splat shaders.
 *
 * Splats are not triangles and the differences drive the whole file. Each one is an anisotropic
 * Gaussian — position, a covariance, colour and opacity, sixteen floats — drawn as a screen-aligned
 * quad whose falloff the fragment shader evaluates, and the result is only correct if the quads
 * arrive in depth order, because alpha blending does not commute. So a frame here is a sort
 * followed by a draw, and most of the machinery below exists to make the sort affordable:
 * `SortMode.ExactRadix` runs a GPU radix sort over per-splat depth keys, `DepthBuckets` trades
 * exactness for a cheaper bucketed approximation, and `None` builds none of it, which is the right
 * answer when the data was written back-to-front already or the blend is order-independent.
 *
 * A `Cloud` is sized by capacity rather than by the data it was built from, so one cloud can be
 * rewritten every frame from different files. That is what makes a splat *sequence* playable at
 * all — see `SplatSequence.kt` for the frame cache on the disk side — and it is why a fifteen-source
 * frame is one upload into one pre-sized buffer instead of fifteen allocations.
 *
 * `configureModule` sets the raster state the built-in shaders need and does not set the shader
 * source, because a box using `moduleHere` owns its vertex and fragment text through its own
 * properties. That is the seam `simpleGaussianSplatSupport.kt` uses to hand a box an editable
 * copy of the shaders.
 */

object GaussianSplatRenderer {
    const val SPLAT_FLOAT_STRIDE = 16

    /**
     * Where the source index lives inside a splat's [SPLAT_FLOAT_STRIDE] floats: the `w` of the
     * colour vec4, which the shading has never used. Written by [packInto] as a float — exact to
     * 2^24, so vastly more than the 256 sources a pack can address — and reachable in GLSL as
     *
     *     vec4 color = pc.splatData.splats[base + 1u];
     *     int source = int(color.w + 0.5);
     *
     * The built-in vertex shader does exactly that and passes it on as `flat` at location 4, so a
     * fragment stage can look up per-source data (a camera matrix for retrojection, say) without
     * forking the vertex shader. See [writeMatrixPalette].
     */
    const val SPLAT_SOURCE_FLOAT = 7
    private const val WORKGROUP_SIZE = 256
    private const val SH_C0 = 0.28209479177387814f

    data class Config(
        val scaleMultiplier: Float = 1f,
        val scaleIsLog: Boolean = true,
        val fallbackScale: Float = 0.01f,
        val minScale: Float = 1e-5f,
        val maxScale: Float = 1_000f,
        val opacityIsLogit: Boolean = true,
        val opacityMultiplier: Float = 1f,
        val covarianceInflation: Float = 1f,
        val sizeScale: Float = 1f,
        val alphaScale: Float = 1f,
        val minScreenRadius: Float = 0.75f,
        val maxScreenRadius: Float = 128f,
        val alphaCutoff: Float = 1f / 255f,
        val sortMode: SortMode = SortMode.DepthBuckets,
        val depthBucketBits: Int = 16,
        val sortEveryNFrames: Int = 1,
        val sortOnlyWhenViewChanges: Boolean = true,
        val sortViewEpsilon: Float = 1e-5f,
        val stableBucketSort: Boolean = false,
        val viewport: Rect? = null,
        val blend: Blend = Blend.Over,
        /**
         * Exponent applied to the splat colour in the vertex shader. The default 2.2 is
         * sRGB-to-linear, so blending happens in linear space and the target's own linear-to-sRGB
         * encode on write completes the round trip.
         *
         * **2.2 is correct on Field's normal targets and you probably want to leave it alone.**
         * `SwapChain.calcSurfaceFormat` prefers `B8G8R8A8_SRGB` with `SRGB_NONLINEAR`, and
         * [afield.four.targets.OpenXRHmdTarget] prefers sRGB swapchain formats too, so the encode
         * does happen. Lowering this does not "fix" a dark scene — it double-encodes, and the result
         * is brighter than the data. It is exposed because a `RenderToTexture` target's format is a
         * `var` that a caller can set to `_UNORM`, in which case nothing encodes on write and 2.2
         * costs a factor of four; and because turning it down is a legitimate *artistic* choice as
         * long as you know it is one.
         */
        val colorGamma: Float = 2.2f
    )

    /**
     * How a splat composites with what is already there. The default [Over] is the physically
     * motivated one — premultiplied alpha, which is what a depth-sorted Gaussian scene wants — but
     * the rest are here because an unsorted draw under [Additive] or [Max] is order-independent,
     * which turns "I have not sorted these" from an artefact into a choice.
     */
    data class Blend(
        val srcColor: Int,
        val dstColor: Int,
        val srcAlpha: Int = srcColor,
        val dstAlpha: Int = dstColor,
        val colorBlendOp: Int = VK_BLEND_OP_ADD,
        val alphaBlendOp: Int = colorBlendOp,
        /**
         * True when the result does not depend on the order the splats are drawn in, so sorting
         * buys nothing and [SortMode.None] costs nothing. [Cloud] does not act on this — it is here
         * so a caller can ask the blend whether it still needs the sort it is paying for.
         */
        val orderIndependent: Boolean = false
    ) {
        companion object {
            /** Premultiplied alpha over. Correct only back-to-front, so it wants a sort. */
            val Over = Blend(VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)

            /** Saturates to white where splats pile up. */
            val Additive = Blend(VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, orderIndependent = true)

            /** Like [Additive] but cannot exceed one. */
            val Screen = Blend(
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR, orderIndependent = true
            )

            /** Darkens: the cloud reads as absorption rather than emission. */
            val Multiply = Blend(
                VK_BLEND_FACTOR_DST_COLOR, VK_BLEND_FACTOR_ZERO, orderIndependent = true
            )

            /** Idempotent as well as order-independent: overlap does not accumulate at all. */
            val Max = Blend(
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE,
                colorBlendOp = VK_BLEND_OP_MAX,
                orderIndependent = true
            )
        }
    }

    data class PackedSplats(
        val floats: FloatArray,
        val count: Int,
        val boundsMin: Vector3f,
        val boundsMax: Vector3f
    )

    enum class SortMode {
        DepthBuckets,
        ExactRadix,

        /**
         * Draw in buffer order and build none of the sort machinery — no depth-key compute pass, no
         * radix sort, no bucket sorter, and no per-frame pre-target. Correct when the splats were
         * already written back-to-front (see [packInto]'s `sortAlong`) or when the blend does not
         * care (see [Blend.orderIndependent]). With fifteen sources in one [Cloud] this is the
         * difference between building the sort machinery once and not at all; the `sortedIndices`
         * buffer is still allocated and filled with the identity so one shader serves both paths.
         */
        None
    }

    /**
     * A splat cloud on the GPU.
     *
     * Sized by [capacity] rather than by the data it was built with, so [reupload] can rewrite it
     * every frame with a different number of splats — which is what lets fifteen files a frame land
     * in one cloud. [table] and [packed] are null for a cloud built empty by [streaming].
     */
    class Cloud internal constructor(
        val renderer: Renderer = fromContext(),
        val camera: Camera = fromContext(),
        val table: PlyFloatTable?,
        val packed: PackedSplats?,
        val config: Config,
        val sourceName: String,
        val capacity: Int = packed?.count ?: 0,
        /**
         * Give [splatData] one buffer per swapchain image instead of one shared buffer.
         *
         * This is what makes [reupload] safe against a GPU still reading the previous frame, and
         * [FrameBufferGroup] already has the whole contract: a `write` lands in the *current* image's
         * slot and records it as last-written, while `getGPUAddress` resolves to the **last written**
         * slot rather than the current one. So a frame that does not reupload reads the most recent
         * upload instead of whatever its own rotation slot held several frames ago — which is the
         * behaviour a 12 fps source into a 90 Hz display needs.
         *
         * The catch, and the reason this is off for the one-shot path: it only works if the binding
         * hands the *group* to the push constant. Binding `result.get()` pins the address to
         * `renderer.currentImage`, and then a skipped frame really does read an unwritten slot.
         */
        val perFrameSplatData: Boolean = false,
        /**
         * Optional offscreen destination for this cloud's graphics module.
         *
         * Compute sorting travels with the graphics module as module `before` work, so it runs
         * immediately before the cloud regardless of where the caller schedules this RTT in the
         * renderer's target graph.
         */
        val renderToTexture: RenderToTexture? = null,
        /*
         * Optional caller-owned graphics module. Supplying a `moduleHere { ... }` pointer puts the
         * shader in a Field box: its vertex/fragment properties, reload actions, generated bindings,
         * texture resolution, and shader errors all remain part of the normal live-coding path.
         * Prefer [gaussianSplatModuleHere] or [gaussianSplatMultiviewModuleHere] so a new box is
         * seeded with the built-in shader as editable starter source. Use [configureModule] on that
         * module's parameters to retain the built-in raster state.
         */
        suppliedRenderModule: ModulePointer? = null
    ) {
        init {
            require(renderToTexture == null || renderToTexture.renderer === renderer) {
                "A Gaussian splat cloud and its RenderToTexture must use the same Renderer"
            }
            require(suppliedRenderModule == null || suppliedRenderModule.current.parameters.renderer === renderer) {
                "A Gaussian splat cloud and its supplied render module must use the same Renderer"
            }
        }

        private val ownsRenderModule = suppliedRenderModule == null
        private val suppliedRenderModule = suppliedRenderModule

        /** Live splats, which [reupload] may change. Everything downstream reads this, not [packed]. */
        var count: Int = packed?.count ?: 0
            private set

        var boundsMin: Vector3f = packed?.boundsMin ?: Vector3f()
            private set
        var boundsMax: Vector3f = packed?.boundsMax ?: Vector3f()
            private set

        /** False under [SortMode.None]: none of the sort machinery below is built at all. */
        val sortsOnGpu: Boolean = config.sortMode != SortMode.None

        var enabled: Boolean = true
        var sortEveryFrame: Boolean = true
        var sizeScale: Float = config.sizeScale
        var alphaScale: Float = config.alphaScale
        var minScreenRadius: Float = config.minScreenRadius
        var maxScreenRadius: Float = config.maxScreenRadius
        var alphaCutoff: Float = config.alphaCutoff
        var colorGamma: Float = config.colorGamma
        var viewport: Rect? = config.viewport
        var sortMode: SortMode = config.sortMode
        var sortEveryNFrames: Int = config.sortEveryNFrames.coerceAtLeast(1)
        var sortOnlyWhenViewChanges: Boolean = config.sortOnlyWhenViewChanges
        var sortViewEpsilon: Float = config.sortViewEpsilon

        val splatData: BufferParameters = storageBuffer(
            renderer = renderer,
            name = "${sourceName}_SplatData",
            sizeBytes = capacity.toLong() * SPLAT_FLOAT_STRIDE * Float.SIZE_BYTES,
            perFrame = perFrameSplatData
        )

        /** Null under [SortMode.None] — nothing writes depth keys, so nothing needs to hold them. */
        val depthKeys: BufferParameters? = if (!sortsOnGpu) null else storageBuffer(
            renderer = renderer,
            name = "${sourceName}_DepthKeys",
            sizeBytes = capacity.toLong() * UInt.SIZE_BYTES,
            perFrame = false
        )

        /**
         * Always present: the vertex shader indirects through it either way, so one shader serves
         * both paths. Under [SortMode.None] it is filled with the identity once and never touched
         * again, which the GPU reads as a perfectly coalesced pass-through.
         */
        val sortedIndices: BufferParameters = storageBuffer(
            renderer = renderer,
            name = "${sourceName}_SortedIndices",
            sizeBytes = capacity.toLong() * UInt.SIZE_BYTES,
            perFrame = false
        )

        val sorter: GpuRadixSort? = if (sortsOnGpu) GpuRadixSort(renderer) else null
        private val bucketSorter: DepthBucketSorter? = if (!sortsOnGpu) null else DepthBucketSorter(
            renderer = renderer,
            sourceName = sourceName,
            bucketBits = config.depthBucketBits.coerceIn(1, 16),
            stable = config.stableBucketSort,
            keys = requireNotNull(depthKeys),
            outputIndices = sortedIndices
        )

        private var modelProvider: () -> Matrix4f = { Matrix4f() }
        private var stereoCamera: StereoCamera? = null
        private var sortedOnce: Boolean = false
        private var sortedModelView: Matrix4f? = null
        private var sortAge: Int = Int.MAX_VALUE
        private var currentSortModelView: Matrix4f? = null
        private var currentDepthMin: Float = 0f
        private var currentInvDepthRange: Float = 1f

        val depthKeyModule: ComputeModulePointer?
        val depthKeyMesh: ComputeMesh?
        val renderModule: ModulePointer
        val renderMesh: Mesh

        private val rendersMultiview: Boolean =
            renderToTexture?.parameters?.multiviewViewMask?.let { it != 0 } == true

        init {
            if (packed != null) {
                require(packed.count <= capacity) {
                    "$sourceName built with ${packed.count} splats but a capacity of $capacity"
                }
                uploadSplats(packed)
            }

            depthKeyModule = if (!sortsOnGpu) null else ComputeModule(parameters(renderer) {
                name = "${sourceName}_splat_depth_keys"
                compute = depthKeyComputeShader
            }).pointer()
            depthKeyMesh = if (depthKeyModule == null) null else ComputeMesh(renderer, depthKeyModule).apply {
                x = workgroupCount(this@Cloud.count)
                y = 1
                z = 1
                push.bind("modelView") { Matrix4f(currentSortModelView ?: modelViewMatrix()) }
                push.bind("splatData") { requireNotNull(splatData.result) }
                push.bind("depthKeys") { requireNotNull(depthKeys?.result).get() }
                push.bind("sortedIndices") { requireNotNull(sortedIndices.result).get() }
                push.bind("depthMin") { currentDepthMin }
                push.bind("invDepthRange") { currentInvDepthRange }
                count = this@Cloud.count
                push.bind("resetIndices") {
                    if (!sortedOnce || sortMode == SortMode.ExactRadix) 1 else 0
                }
            }
            if (depthKeyModule != null && depthKeyMesh != null) {
                depthKeyModule += "depthKeys" to depthKeyMesh
            }

            renderModule = suppliedRenderModule ?: Module(
                configureModule(
                    parameters(renderer) {
                        vertex = if (rendersMultiview) splatMultiviewVertexShader else splatVertexShader
                        fragment = splatFragmentShader
                    },
                    config = config,
                    renderToTexture = renderToTexture,
                    name = "${sourceName}_gaussian_splats"
                )
            ).pointer()
            renderMesh = Mesh(renderer, renderModule).apply {
                resize(this@Cloud.count * 6, 0, renderModule)
                if (rendersMultiview) {
                    push.bind("modelViewL") { modelViewMatrix(0) }
                    push.bind("modelViewR") { modelViewMatrix(1) }
                    push.bind("projL") { projectionMatrix(0) }
                    push.bind("projR") { projectionMatrix(1) }
                } else {
                    push.bind("modelView") { modelViewMatrix() }
                    push.bind("proj") { projectionMatrix() }
                }
                push.bind("splatData") { requireNotNull(splatData.result) }
                push.bind("sortedIndices") { requireNotNull(sortedIndices.result).get() }
                push.bind("viewportSize") { viewportSize() }
                push.bind("sizeScale") { sizeScale }
                push.bind("alphaScale") { alphaScale }
                push.bind("minScreenRadius") { minScreenRadius }
                push.bind("maxScreenRadius") { maxScreenRadius }
                push.bind("alphaCutoff") { alphaCutoff }
                push.bind("colorGamma") { colorGamma }
                count = this@Cloud.count
            }
            renderModule += "splats" to renderMesh
            renderModule.current.before["depth-sort"] = Target { command, _ ->
                renderModule.current.parameters.viewport = viewport
                if (!sortsOnGpu || !enabled || count <= 0) return@Target

                currentSortModelView = modelViewMatrix()
                if (shouldSortThisFrame(requireNotNull(currentSortModelView))) {
                    updateDepthRange(requireNotNull(currentSortModelView))
                    requireNotNull(depthKeyModule).render(command, Rectanglef())
                    computeMemoryBarrier(command, requireNotNull(depthKeys?.result).get())
                    computeMemoryBarrier(command, requireNotNull(sortedIndices.result).get())

                    if (count > 1) {
                        when (sortMode) {
                            SortMode.DepthBuckets -> requireNotNull(bucketSorter).sort(
                                command = command,
                                count = count
                            )

                            SortMode.ExactRadix -> requireNotNull(sorter).sort(
                                commandBuffer = command,
                                keys = requireNotNull(depthKeys.result),
                                count = count,
                                values = requireNotNull(sortedIndices.result),
                                signedKeys = false
                            )

                            SortMode.None -> Unit
                        }
                    }
                    markSorted(requireNotNull(currentSortModelView))
                    graphicsMemoryBarrier(command, requireNotNull(sortedIndices.result).get())
                } else {
                    ageSort()
                }
            }
            if (!sortsOnGpu) writeIdentityIndices()
        }

        /**
         * Fill [sortedIndices] with `0, 1, 2, ...` for the whole capacity. Under [SortMode.None]
         * nothing else ever writes it, so this happens once and the identity stays valid however
         * [count] later changes — index `i` maps to `i` at every size.
         */
        private fun writeIdentityIndices() {
            if (capacity <= 0) return
            requireNotNull(sortedIndices.result).write {
                // Direct, not heap: the buffer put path requires it.
                val ints = java.nio.ByteBuffer
                    .allocateDirect(capacity * Int.SIZE_BYTES)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asIntBuffer()
                for (i in 0 until capacity) ints.put(i)
                ints.flip()
                put(ints, padding = false)
            }
        }

        fun setModel(model: Matrix4f): Cloud {
            modelProvider = { Matrix4f(model) }
            return this
        }

        fun bindModel(model: () -> Matrix4f): Cloud {
            modelProvider = { Matrix4f(model()) }
            return this
        }

        /**
         * Use [stereo] for per-eye view and projection matrices when this cloud renders into a
         * multiview RTT. Mono targets continue to use [camera]. The cloud's model transform is
         * applied after each eye view, exactly as it is on the mono path.
         */
        fun bindStereoCamera(stereo: StereoCamera): Cloud {
            require(stereo.base.renderer === renderer) {
                "A Gaussian splat cloud and its StereoCamera must use the same Renderer"
            }
            stereoCamera = stereo
            return this
        }

        fun install(
            target: RenderTarget = renderer.renderList,
            name: String = sourceName
        ): Cloud {
            installRenderModule(target, name)
            return this
        }

        fun remove(target: RenderTarget = renderer.renderList, name: String = sourceName): Cloud {
            (renderToTexture?.modules ?: target.modules).remove(name)
            return this
        }

        private fun installRenderModule(target: RenderTarget, name: String) {
            val rtt = renderToTexture
            if (rtt == null) target += name to renderModule
            else rtt += name to renderModule
        }

        fun cleanup() {
            sorter?.cleanup()
            bucketSorter?.cleanup()
            depthKeyModule?.current?.cleanup()
            if (ownsRenderModule) renderModule.current.cleanup()
            splatData.result?.buffers?.forEach { it.cleanup() }
            depthKeys?.result?.buffers?.forEach { it.cleanup() }
            sortedIndices.result?.buffers?.forEach { it.cleanup() }
        }

        private fun uploadSplats(from: PackedSplats) {
            requireNotNull(splatData.result).write {
                put(from.floats, padding = false)
            }
        }

        /**
         * Decode [sources] straight into this cloud's GPU buffer and make them the live contents.
         *
         * This is the streaming entry point: no modules are rebuilt, no buffers reallocated, and the
         * decode writes into the mapped storage rather than into a `FloatArray` that then has to be
         * copied — which at fifteen files a frame is 20 MB per frame of memcpy not done.
         *
         * **Synchronisation — settled, and it is [Cloud.perFrameSplatData] that settles it.**
         * A streaming cloud gives [splatData] one buffer per swapchain image, and
         * [afield.four.buffers.FrameBufferGroup] already has exactly the right contract: a write
         * lands in the current image's slot and records it as last-written, while `getGPUAddress`
         * resolves to the **last written** slot rather than the current one. So writing at 12 fps
         * into a 90 Hz display is fine — the two frames in three that skip a reupload resolve to the
         * most recent upload, not to their own stale rotation slot.
         *
         * Measured on a 3-image swapchain, writing once and then observing ten frames: `get()`
         * cycles slot 0, 1, 2 with the image index while `getLastWritten()` stays pinned to the
         * written slot, differing on two frames in every three. Which is the trap: binding
         * `splatData.result.get()` instead of `splatData.result` pins the push-constant address to
         * `renderer.currentImage` and reads an unwritten slot two frames in three. This class binds
         * the group. If you add another binding, bind the group.
         *
         * @param sortAlong see [packInto]. Under [SortMode.None] this is how the draw order gets to
         *   be correct at all, so pass the view direction (or, for a depth-map capture, the source
         *   camera axis) unless the blend is order-independent.
         * @return what was written, whose `count` is now this cloud's [count].
         */
        fun reupload(
            sources: List<SplatSource>,
            config: Config = this.config,
            sortAlong: Vector3f? = null,
            scratch: PackScratch? = null,
            parallelSort: Boolean = true
        ): PackedFrame {
            val total = sources.sumOf { it.raw.count }
            require(total <= capacity) {
                "$sourceName has capacity $capacity but was handed $total splats"
            }
            var frame: PackedFrame? = null
            requireNotNull(splatData.result).write {
                frame = packInto(sources, target, config, sortAlong, scratch, parallelSort)
                // packInto writes absolutely and leaves the position alone, so advance it here for
                // whatever bookkeeping the write wrapper does on the way out.
                target.position(total * SPLAT_FLOAT_STRIDE)
            }
            return applyPackedFrame(requireNotNull(frame))
        }

        /**
         * Upload a frame that [packInto] prepared on another thread.
         *
         * Only the mapped-buffer copy and the small draw-count update remain on the render thread.
         * The input buffer is duplicated, so this method neither changes its position nor retains
         * it after returning; a producer may safely recycle it immediately afterwards.
         */
        fun reuploadPacked(floats: FloatBuffer, frame: PackedFrame): PackedFrame {
            require(frame.count <= capacity) {
                "$sourceName has capacity $capacity but was handed ${frame.count} packed splats"
            }
            val copy = floats.duplicate().apply {
                position(0)
                limit(frame.count * SPLAT_FLOAT_STRIDE)
            }
            requireNotNull(splatData.result).write {
                put(copy, padding = false)
            }
            return applyPackedFrame(frame)
        }

        private fun applyPackedFrame(written: PackedFrame): PackedFrame {
            count = written.count
            boundsMin = written.boundsMin
            boundsMax = written.boundsMax
            renderMesh.count = count
            renderMesh.resize(count * 6, 0, renderModule)
            depthKeyMesh?.let {
                it.count = count
                it.x = workgroupCount(count)
            }
            sortedOnce = false
            sortAge = Int.MAX_VALUE
            return written
        }

        private fun shouldSortThisFrame(modelView: Matrix4f): Boolean {
            if (!sortedOnce) return true
            if (!sortEveryFrame) return false
            if (sortOnlyWhenViewChanges && isSortedFresh(modelView)) return false
            return sortAge >= sortEveryNFrames.coerceAtLeast(1)
        }

        private fun isSortedFresh(modelView: Matrix4f): Boolean {
            val previous = sortedModelView ?: return false
            return maxAbsDelta(previous, modelView) <= sortViewEpsilon
        }

        private fun markSorted(modelView: Matrix4f) {
            sortedOnce = true
            sortedModelView = Matrix4f(modelView)
            sortAge = 0
        }

        private fun ageSort() {
            if (sortAge < Int.MAX_VALUE) {
                sortAge += 1
            }
        }

        private fun updateDepthRange(modelView: Matrix4f) {
            var minDepth = Float.POSITIVE_INFINITY
            var maxDepth = Float.NEGATIVE_INFINITY
            fun include(x: Float, y: Float, z: Float) {
                val view = modelView.transform(Vector4f(x, y, z, 1f))
                val depth = -view.z
                if (depth.isFinite()) {
                    minDepth = minOf(minDepth, depth)
                    maxDepth = maxOf(maxDepth, depth)
                }
            }
            include(boundsMin.x, boundsMin.y, boundsMin.z)
            include(boundsMin.x, boundsMin.y, boundsMax.z)
            include(boundsMin.x, boundsMax.y, boundsMin.z)
            include(boundsMin.x, boundsMax.y, boundsMax.z)
            include(boundsMax.x, boundsMin.y, boundsMin.z)
            include(boundsMax.x, boundsMin.y, boundsMax.z)
            include(boundsMax.x, boundsMax.y, boundsMin.z)
            include(boundsMax.x, boundsMax.y, boundsMax.z)

            if (!minDepth.isFinite() || !maxDepth.isFinite()) {
                currentDepthMin = 0f
                currentInvDepthRange = 1f
                return
            }

            val range = max(maxDepth - minDepth, 1e-3f)
            val pad = max(range * 0.02f, 1e-3f)
            val paddedMin = minDepth - pad
            val paddedMax = maxDepth + pad
            currentDepthMin = paddedMin
            currentInvDepthRange = 1f / max(paddedMax - paddedMin, 1e-3f)
        }

        private fun maxAbsDelta(a: Matrix4f, b: Matrix4f): Float {
            val aa = FloatArray(16)
            val bb = FloatArray(16)
            a.get(aa)
            b.get(bb)
            var maxDelta = 0f
            for (i in 0 until 16) {
                maxDelta = max(maxDelta, abs(aa[i] - bb[i]))
            }
            return maxDelta
        }

        private fun modelViewMatrix(eye: Int = 0): Matrix4f {
            val view = stereoCamera?.eyeViewMatrices()?.get(eye.coerceIn(0, 1)) ?: camera.modelView()
            return Matrix4f(view).mul(modelProvider())
        }

        private fun projectionMatrix(eye: Int = 0): Matrix4f {
            val projection = stereoCamera?.projectionMatrices()?.get(eye.coerceIn(0, 1))
                ?: camera.p.evaluateUniform()
            return Matrix4f(projection)
        }

        private fun viewportSize(): Vector2f {
            viewport?.let { return Vector2f(it.w.toFloat(), it.h.toFloat()) }
            renderToTexture?.let { return Vector2f(it.width.toFloat(), it.height.toFloat()) }
            val extent = renderer.swapChain.swapChainExtent
            return Vector2f(extent.width().toFloat(), extent.height().toFloat())
        }
    }

    /**
     * Apply the raster state expected by the built-in splat shaders to either an internal module or
     * a caller's live `moduleHere` module. Shader source is not assigned here, because `moduleHere`
     * owns source through the box's vertex and fragment properties.
     */
    fun configureModule(
        parameters: Parameters,
        config: Config = Config(),
        renderToTexture: RenderToTexture? = null,
        name: String = "gaussian_splats"
    ): Parameters = parameters.apply {
        this.name = name
        primitiveType = triangles
        depth = 1
        depthTest = false
        blend = true
        srcColor = config.blend.srcColor
        dstColor = config.blend.dstColor
        srcAlpha = config.blend.srcAlpha
        dstAlpha = config.blend.dstAlpha
        colorBlendOp = config.blend.colorBlendOp
        alphaBlendOp = config.blend.alphaBlendOp
        viewport = config.viewport
        renderToTexture?.apply()
    }

    fun load(
        path: String,
        renderer: Renderer = fromContext(),
        camera: Camera = Camera(renderer),
        config: Config = Config(),
        name: String = File(path).nameWithoutExtension,
        renderToTexture: RenderToTexture? = null,
        renderModule: ModulePointer? = null
    ): Cloud {
        val table = readPlyAsFloatTable(path)
        return fromTable(table, renderer, camera, config, name, renderToTexture, renderModule)
    }

    fun fromTable(
        table: PlyFloatTable,
        renderer: Renderer = fromContext(),
        camera: Camera = Camera(renderer),
        config: Config = Config(),
        name: String = "gaussianSplats",
        renderToTexture: RenderToTexture? = null,
        renderModule: ModulePointer? = null
    ): Cloud {
        table.requireColumns("x", "y", "z")
        return Cloud(
            renderer = renderer,
            camera = camera,
            table = table,
            packed = pack(table, config),
            config = config,
            sourceName = name,
            renderToTexture = renderToTexture,
            suppliedRenderModule = renderModule
        )
    }

    /**
     * An empty cloud sized for [capacity] splats, to be filled by [Cloud.reupload] every frame.
     *
     * This is the shape for playing a sequence: one cloud, one draw, one set of modules, and fifteen
     * files a frame decoded straight into its buffer. Size [capacity] for the worst frame in the
     * sequence — a run of 22k-splat files that peaks at 29k needs 15 × 29k, not 15 × 22k — because
     * exceeding it is an error rather than a reallocation.
     *
     * Pair it with `config.sortMode = SortMode.None` and either a `sortAlong` on each [Cloud.reupload]
     * or an order-independent [Blend]; that way none of the GPU sort machinery is built. Leave
     * [SortMode.DepthBuckets] on if you would rather the GPU re-sort per view, which is the right
     * choice when the head moves a long way relative to the depth spread of the content.
     */
    fun streaming(
        capacity: Int,
        renderer: Renderer = fromContext(),
        camera: Camera = Camera(renderer),
        config: Config = Config(),
        name: String = "splatStream",
        renderToTexture: RenderToTexture? = null,
        renderModule: ModulePointer? = null
    ): Cloud {
        require(capacity > 0) { "a streaming cloud needs a capacity, got $capacity" }
        return Cloud(
            renderer = renderer,
            camera = camera,
            table = null,
            packed = null,
            config = config,
            sourceName = name,
            capacity = capacity,
            perFrameSplatData = true,
            renderToTexture = renderToTexture,
            suppliedRenderModule = renderModule
        )
    }

    /**
     * The column indices [pack] needs, resolved once. Every name it looks for is a compile-time
     * constant, but the old per-row spelling rebuilt `"scale_$index"` and a `listOf` of alternatives
     * for every splat and hashed each one — which cost more than all the arithmetic put together.
     * `-1` means the column is absent and the caller's fallback applies.
     */
    private class PackColumns(private val table: PlyFloatTable) {
        private fun first(vararg names: String): Int {
            for (name in names) if (table.hasColumn(name)) return table.columnOffset(name)
            return -1
        }

        val x = first("x"); val y = first("y"); val z = first("z")
        val opacity = first("opacity", "alpha")
        val hasLogitOpacity = table.hasColumn("opacity")
        val red = first("red", "diffuse_red", "r")
        val green = first("green", "diffuse_green", "g")
        val blue = first("blue", "diffuse_blue", "b")
        val dc0 = first("f_dc_0"); val dc1 = first("f_dc_1"); val dc2 = first("f_dc_2")
        val scale = intArrayOf(
            first("scale_0", "scale0", "s0", "sx"),
            first("scale_1", "scale1", "s1", "sy"),
            first("scale_2", "scale2", "s2", "sz")
        )
        val rot = intArrayOf(
            first("rot_0", "rotation_0", "qw", "w"),
            first("rot_1", "rotation_1", "qx"),
            first("rot_2", "rotation_2", "qy"),
            first("rot_3", "rotation_3", "qz")
        )
    }

    fun pack(table: PlyFloatTable, config: Config = Config()): PackedSplats {
        table.requireColumns("x", "y", "z")
        val count = table.rowCount
        val out = FloatArray(count * SPLAT_FLOAT_STRIDE)
        val boundsMin = Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val boundsMax = Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val columns = PackColumns(table)
        val buffer = table.buffer
        val stride = table.stride

        for (row in 0 until count) {
            val rowBase = row * stride
            val x = buffer.get(rowBase + columns.x)
            val y = buffer.get(rowBase + columns.y)
            val z = buffer.get(rowBase + columns.z)
            boundsMin.min(Vector3f(x, y, z))
            boundsMax.max(Vector3f(x, y, z))

            val opacity = decodeOpacity(buffer, rowBase, columns, config)
            val color = decodeColor(buffer, rowBase, columns)
            val scales = decodeScales(buffer, rowBase, columns, config)
            val rotation = decodeRotation(buffer, rowBase, columns)
            val covariance = covarianceFromScaleRotation(scales, rotation, config.covarianceInflation)

            val base = row * SPLAT_FLOAT_STRIDE
            out[base + 0] = x
            out[base + 1] = y
            out[base + 2] = z
            out[base + 3] = opacity

            out[base + 4] = color[0]
            out[base + 5] = color[1]
            out[base + 6] = color[2]
            out[base + 7] = 0f

            out[base + 8] = covariance[0]
            out[base + 9] = covariance[1]
            out[base + 10] = covariance[2]
            out[base + 11] = covariance[3]

            out[base + 12] = covariance[4]
            out[base + 13] = covariance[5]
            out[base + 14] = 0f
            out[base + 15] = 0f
        }

        if (count == 0) {
            boundsMin.set(0f, 0f, 0f)
            boundsMax.set(0f, 0f, 0f)
        }

        return PackedSplats(out, count, boundsMin, boundsMax)
    }

    // ------------------------------------------------------------------------
    // Fused streaming path: compressed file(s) -> the 16-float GPU layout, one pass
    // ------------------------------------------------------------------------

    /**
     * One compressed payload plus where it goes in the world.
     *
     * [transform] is baked into the splats as they are decoded, which is nearly free because the
     * per-file linear map folds into a congruence the decode already performs: the covariance is
     * `A·S²·Aᵀ` with `A` the splat's own rotation, so a file transform `L` just makes it `(L·A)`.
     * One 3×3 product per splat, and because it is the general linear part rather than a quaternion
     * it handles non-uniform scale and shear too.
     *
     * Baking is the right default when the splat data is being rewritten every source frame anyway —
     * you are paying for the pass regardless. The alternative, and the reason to keep this an
     * explicit per-source field rather than folding it into a single model matrix, is a matrix
     * palette: write the source index into one of the three unused floats in the vertex layout,
     * upload the fifteen matrices to their own buffer, and transform in the shader. That wins only
     * if the *arrangement* needs to move faster than the data refreshes — a circle spinning at
     * headset rate over splats arriving at 24 fps. Sorting does not force the choice either way:
     * a world-space depth is `dot(L·p + t, d)` = `dot(p, Lᵀ·d) + dot(t, d)`, so an untransformed
     * position can be sorted in world order against a per-source direction and offset at no extra
     * per-splat cost.
     */
    data class SplatSource(
        val raw: CompressedSplatPly.RawSplats,
        val transform: Matrix4f? = null,
        /** Stable palette identity when [sources] is a sparse subset; defaults to its list index. */
        val sourceIndex: Int? = null
    )

    /** Where one source's splats landed in a shared destination. Contiguous only when unsorted. */
    data class PackedSpan(val source: Int, val offset: Int, val count: Int)

    /** What [packInto] wrote. [spans] is null when a sort interleaved the sources. */
    data class PackedFrame(
        val count: Int,
        val boundsMin: Vector3f,
        val boundsMax: Vector3f,
        val spans: List<PackedSpan>?
    )

    /**
     * Reusable per-frame working memory for [packInto]. A streaming caller should keep one and pass
     * it every frame; the sort keys are 8 bytes per splat, which at 15 files and 24 fps is 72 MB/s
     * of garbage otherwise.
     */
    class PackScratch {
        internal var keys: LongArray = LongArray(0)

        internal fun keys(size: Int): LongArray {
            if (keys.size < size) keys = LongArray(size)
            return keys
        }
    }

    /**
     * Decode compressed splat payloads straight into [dest] in the 16-float GPU layout, with no
     * [PlyFloatTable] in between.
     *
     * [sources] all land in one destination, back to back: fifteen files a frame become one buffer,
     * one draw and — if you sort — one order that is correct *across* sources rather than fifteen
     * locally-correct orders that interleave wrongly.
     *
     * Two round trips that [pack] performs are skipped here, because the compressed format already
     * stores what the shader wants. Colour is linear 0..1 in the file, so `f_dc = (c - 0.5) / SH_C0`
     * followed by `0.5 + SH_C0 * f_dc` cancels; opacity is stored post-sigmoid, so the logit and the
     * sigmoid cancel too — which also means the ±30 logit clamp never happens and a saturated
     * opacity byte comes out exact. So this is both faster than [pack] and slightly more accurate.
     * Expect agreement to ~1e-4 relative, not bit-equality; `SplatFusedPackTest` pins that.
     *
     * @param dest absolute-indexed, so a persistently mapped Vulkan buffer works directly. Writes
     *   are strictly ascending, which is what write-combined host memory wants.
     * @param sortAlong when non-null, splats are written **farthest first** along this direction, so
     *   a sequential unsorted draw composites correctly under `Blend.Over`. Costs one extra pass
     *   over the positions plus a sort of the whole frame. For a depth-map-derived capture the source
     *   camera axis is the right direction and the order stays valid for any nearby view, which is
     *   what makes sorting once per source frame — rather than once per VR frame — sound.
     * @param scratch see [PackScratch]. Allocated per call when null.
     */
    fun packInto(
        sources: List<SplatSource>,
        dest: FloatBuffer,
        config: Config = Config(),
        sortAlong: Vector3f? = null,
        scratch: PackScratch? = null,
        parallelSort: Boolean = false
    ): PackedFrame {
        require(sources.size <= MAX_PACK_SOURCES) {
            "packInto takes at most $MAX_PACK_SOURCES sources, got ${sources.size}"
        }
        sources.forEachIndexed { index, source ->
            require(source.raw.count <= MAX_PACK_SPLATS_PER_SOURCE) {
                "source $index has ${source.raw.count} splats, over the " +
                    "$MAX_PACK_SPLATS_PER_SOURCE the sort key can address"
            }
            require((source.sourceIndex ?: index) in 0..0xff) {
                "source identity ${source.sourceIndex} is outside the palette-addressable range 0..255"
            }
        }
        val total = sources.sumOf { it.raw.count }
        require(dest.capacity() >= total.toLong() * SPLAT_FLOAT_STRIDE) {
            "destination holds ${dest.capacity() / SPLAT_FLOAT_STRIDE} splats, need $total"
        }

        val bounds = floatArrayOf(
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
        )
        val scaleMultiplier = config.scaleMultiplier
        val inflation = config.covarianceInflation
        // Per source, once: the linear part in column order plus the translation, or null for identity.
        val linear = Array(sources.size) { linearOf(sources[it].transform) }

        var spans: List<PackedSpan>? = null
        if (sortAlong == null) {
            var write = 0
            val built = ArrayList<PackedSpan>(sources.size)
            for (index in sources.indices) {
                val source = sources[index].raw
                val sourceIdentity = sources[index].sourceIndex ?: index
                built += PackedSpan(sourceIdentity, write, source.count)
                for (splat in 0 until source.count) {
                    emitSplat(
                        source, splat, sourceIdentity, dest, write++, scaleMultiplier, inflation, config,
                        linear[index], bounds
                    )
                }
            }
            spans = built
        } else {
            val keys = (scratch ?: PackScratch()).keys(total)
            var at = 0
            for (index in sources.indices) {
                val source = sources[index].raw
                val ranges = source.chunkRanges
                val packed = source.packed
                val lin = linear[index]
                // dot(L*p + t, d) == dot(p, Lt*d) + dot(t, d): fold the transform into the direction
                // and a constant, so a transformed source costs a sort key no more than an untransformed one.
                var dx = sortAlong.x
                var dy = sortAlong.y
                var dz = sortAlong.z
                var offset = 0f
                if (lin != null) {
                    val ox = lin[0] * dx + lin[1] * dy + lin[2] * dz
                    val oy = lin[3] * dx + lin[4] * dy + lin[5] * dz
                    val oz = lin[6] * dx + lin[7] * dy + lin[8] * dz
                    offset = lin[9] * dx + lin[10] * dy + lin[11] * dz
                    dx = ox; dy = oy; dz = oz
                }
                for (splat in 0 until source.count) {
                    val base = source.rangeBase(splat)
                    val word = packed[splat * 4]
                    val x = lerpRange(ranges, base + 0, base + 3, word ushr 21, 2047)
                    val y = lerpRange(ranges, base + 1, base + 4, word ushr 11, 1023)
                    val z = lerpRange(ranges, base + 2, base + 5, word, 2047)
                    keys[at++] = sortKey(x * dx + y * dy + z * dz + offset, index, splat)
                }
            }
            if (parallelSort) java.util.Arrays.parallelSort(keys, 0, total)
            else java.util.Arrays.sort(keys, 0, total)
            var write = 0
            // Descending: Arrays.sort gave us nearest-first, and Over blending wants the far ones down first.
            for (slot in total - 1 downTo 0) {
                val low = (keys[slot] xor Long.MIN_VALUE).toInt()
                val index = (low ushr PACK_SOURCE_SHIFT) and 0xFF
                emitSplat(
                    sources[index].raw, low and MAX_PACK_SPLATS_PER_SOURCE_MASK,
                    sources[index].sourceIndex ?: index, dest, write++,
                    scaleMultiplier, inflation, config, linear[index], bounds
                )
            }
        }

        if (total == 0) java.util.Arrays.fill(bounds, 0f)
        return PackedFrame(
            count = total,
            boundsMin = Vector3f(bounds[0], bounds[1], bounds[2]),
            boundsMax = Vector3f(bounds[3], bounds[4], bounds[5]),
            spans = spans
        )
    }

    /** Single-source convenience. */
    fun packInto(
        source: CompressedSplatPly.RawSplats,
        dest: FloatBuffer,
        config: Config = Config(),
        transform: Matrix4f? = null,
        sortAlong: Vector3f? = null,
        scratch: PackScratch? = null
    ): PackedFrame = packInto(listOf(SplatSource(source, transform)), dest, config, sortAlong, scratch)

    /**
     * A [Matrix4f]'s linear part in column order followed by its translation, or null when it is the
     * identity and the per-splat transform can be skipped entirely. Matrix4f is column-major with
     * `m<column><row>` naming.
     */
    private fun linearOf(transform: Matrix4f?): FloatArray? {
        if (transform == null) return null
        val out = floatArrayOf(
            transform.m00, transform.m01, transform.m02,
            transform.m10, transform.m11, transform.m12,
            transform.m20, transform.m21, transform.m22,
            transform.m30, transform.m31, transform.m32
        )
        val isIdentity = out[0] == 1f && out[1] == 0f && out[2] == 0f &&
            out[3] == 0f && out[4] == 1f && out[5] == 0f &&
            out[6] == 0f && out[7] == 0f && out[8] == 1f &&
            out[9] == 0f && out[10] == 0f && out[11] == 0f
        return if (isIdentity) null else out
    }

    /**
     * A storage buffer sized to hold [capacity] 4x4 matrices, for the per-source palette that
     * [SPLAT_SOURCE_FLOAT] indexes. Allocate once, rewrite with [writeMatrixPalette] as often as you
     * like — a palette is 15 matrices where the splat buffer is hundreds of thousands of splats, so
     * this is the cheap end of the frame by four orders of magnitude.
     */
    fun matrixPaletteBuffer(
        renderer: Renderer = fromContext(),
        name: String = "splatMatrixPalette",
        capacity: Int = MAX_PACK_SOURCES
    ): BufferParameters = storageBuffer(
        renderer = renderer,
        name = name,
        sizeBytes = capacity.toLong() * 16 * Float.SIZE_BYTES,
        perFrame = false
    )

    /**
     * Write [matrices] into [into] as a flat palette, column-major and sixteen floats each, which is
     * the layout a GLSL `mat4 palette[]` in a `buffer_reference` block expects.
     *
     * Pad to [capacity] so the written length does not change between frames — a palette that shrinks
     * leaves last frame's matrices past the end, and a shader indexing by source would read them.
     * The padding is the identity rather than zero, because a zero matrix collapses everything it
     * touches to the origin and that reads as a bug in the data rather than in the indexing.
     *
     * Typical use, alongside a cloud whose splats carry their source index:
     *
     *     val palette = GaussianSplatRenderer.matrixPaletteBuffer(renderer)
     *     palette.result!!.write { GaussianSplatRenderer.writeMatrixPalette(cameras, this) }
     *
     * and in a forked fragment shader:
     *
     *     mat4 retroject = pc.palette.palette[vSource];
     *
     * @return how many matrices were written, padding included.
     */
    fun writeMatrixPalette(
        matrices: List<Matrix4f>,
        into: DynamicBuffer,
        capacity: Int = matrices.size
    ): Int {
        require(capacity >= matrices.size) {
            "palette capacity $capacity cannot hold ${matrices.size} matrices"
        }
        val floats = FloatArray(capacity * 16)
        for (index in 0 until capacity) {
            val matrix = matrices.getOrNull(index)
            if (matrix == null) {
                // identity, not zero
                floats[index * 16] = 1f
                floats[index * 16 + 5] = 1f
                floats[index * 16 + 10] = 1f
                floats[index * 16 + 15] = 1f
            } else {
                for (element in 0 until 16) floats[index * 16 + element] = matrix[element]
            }
        }
        into.put(floats, padding = false)
        return capacity
    }

    private const val PACK_SOURCE_SHIFT = 24
    private const val MAX_PACK_SOURCES = 256
    private const val MAX_PACK_SPLATS_PER_SOURCE_MASK = 0xFFFFFF
    private const val MAX_PACK_SPLATS_PER_SOURCE = MAX_PACK_SPLATS_PER_SOURCE_MASK

    /**
     * Deliberately `a * (1 - t) + b * t` rather than the cheaper `a + (b - a) * t`: it is the
     * spelling [CompressedSplatPly] uses, from the reference decoder, and the two differ by an ulp.
     * An ulp of a log-scale becomes a relative error of the same size once `exp` has been applied,
     * so matching here is what lets `SplatFusedPackTest` hold the fused and table paths to each
     * other tightly enough that a real defect cannot hide in the slack.
     */
    private fun lerpRange(ranges: FloatArray, minAt: Int, maxAt: Int, word: Int, mask: Int): Float {
        val t = (word and mask).toFloat() / mask.toFloat()
        return ranges[minAt] * (1f - t) + ranges[maxAt] * t
    }

    /**
     * 32 bits of depth, 8 of source, 24 of splat index, arranged so a plain `Arrays.sort(long[])`
     * orders by depth. The float goes through the standard monotone bit map (so unsigned integer
     * order matches float order) and the whole word is then flipped into signed-long order.
     */
    private fun sortKey(depth: Float, source: Int, splat: Int): Long {
        val bits = java.lang.Float.floatToRawIntBits(depth)
        val monotone = bits xor ((bits shr 31) or Int.MIN_VALUE)
        val low = (source.toLong() shl PACK_SOURCE_SHIFT) or splat.toLong()
        return (((monotone.toLong() and 0xFFFFFFFFL) shl 32) or low) xor Long.MIN_VALUE
    }

    private fun emitSplat(
        raw: CompressedSplatPly.RawSplats,
        splat: Int,
        source: Int,
        dest: FloatBuffer,
        destIndex: Int,
        scaleMultiplier: Float,
        inflation: Float,
        config: Config,
        lin: FloatArray?,
        bounds: FloatArray
    ) {
        val ranges = raw.chunkRanges
        val packed = raw.packed
        val base = raw.rangeBase(splat)
        val word = splat * 4

        val position = packed[word]
        val lx = lerpRange(ranges, base + 0, base + 3, position ushr 21, 2047)
        val ly = lerpRange(ranges, base + 1, base + 4, position ushr 11, 1023)
        val lz = lerpRange(ranges, base + 2, base + 5, position, 2047)
        val x: Float; val y: Float; val z: Float
        if (lin == null) {
            x = lx; y = ly; z = lz
        } else {
            x = lin[0] * lx + lin[3] * ly + lin[6] * lz + lin[9]
            y = lin[1] * lx + lin[4] * ly + lin[7] * lz + lin[10]
            z = lin[2] * lx + lin[5] * ly + lin[8] * lz + lin[11]
        }
        if (x < bounds[0]) bounds[0] = x
        if (y < bounds[1]) bounds[1] = y
        if (z < bounds[2]) bounds[2] = z
        if (x > bounds[3]) bounds[3] = x
        if (y > bounds[4]) bounds[4] = y
        if (z > bounds[5]) bounds[5] = z

        val color = packed[word + 3]
        var red = ((color ushr 24) and 255) * (1f / 255f)
        var green = ((color ushr 16) and 255) * (1f / 255f)
        var blue = ((color ushr 8) and 255) * (1f / 255f)
        if (raw.hasChunkColors) {
            // Same spelling as lerpRange, for the same reason.
            red = ranges[base + 12] * (1f - red) + ranges[base + 15] * red
            green = ranges[base + 13] * (1f - green) + ranges[base + 16] * green
            blue = ranges[base + 14] * (1f - blue) + ranges[base + 17] * blue
        }
        val alpha = (((color and 255) * (1f / 255f)) * config.opacityMultiplier).coerceIn(0f, 1f)

        val scale = packed[word + 2]
        val sx = decodeFusedScale(lerpRange(ranges, base + 6, base + 9, scale ushr 21, 2047), scaleMultiplier, config)
        val sy = decodeFusedScale(lerpRange(ranges, base + 7, base + 10, scale ushr 11, 1023), scaleMultiplier, config)
        val sz = decodeFusedScale(lerpRange(ranges, base + 8, base + 11, scale, 2047), scaleMultiplier, config)

        val rotation = packed[word + 1]
        val norm = ROTATION_NORM
        val a = (((rotation ushr 20) and 1023) * (1f / 1023f) - 0.5f) * norm
        val b = (((rotation ushr 10) and 1023) * (1f / 1023f) - 0.5f) * norm
        val c = ((rotation and 1023) * (1f / 1023f) - 0.5f) * norm
        val largest = sqrt(max(0f, 1f - (a * a + b * b + c * c)))
        val qw: Float; val qx: Float; val qy: Float; val qz: Float
        when (rotation ushr 30) {
            0 -> { qw = largest; qx = a; qy = b; qz = c }
            1 -> { qw = a; qx = largest; qy = b; qz = c }
            2 -> { qw = a; qx = b; qy = largest; qz = c }
            else -> { qw = a; qx = b; qy = c; qz = largest }
        }

        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        var r00 = 1f - 2f * (yy + zz); var r01 = 2f * (xy - wz); var r02 = 2f * (xz + wy)
        var r10 = 2f * (xy + wz); var r11 = 1f - 2f * (xx + zz); var r12 = 2f * (yz - wx)
        var r20 = 2f * (xz - wy); var r21 = 2f * (yz + wx); var r22 = 1f - 2f * (xx + yy)
        if (lin != null) {
            // A <- L * A, so the covariance congruence A*S^2*A^T below carries the file transform.
            // lin holds L column-major, so L[row][col] is lin[col * 3 + row].
            val a00 = lin[0] * r00 + lin[3] * r10 + lin[6] * r20
            val a01 = lin[0] * r01 + lin[3] * r11 + lin[6] * r21
            val a02 = lin[0] * r02 + lin[3] * r12 + lin[6] * r22
            val a10 = lin[1] * r00 + lin[4] * r10 + lin[7] * r20
            val a11 = lin[1] * r01 + lin[4] * r11 + lin[7] * r21
            val a12 = lin[1] * r02 + lin[4] * r12 + lin[7] * r22
            val a20 = lin[2] * r00 + lin[5] * r10 + lin[8] * r20
            val a21 = lin[2] * r01 + lin[5] * r11 + lin[8] * r21
            val a22 = lin[2] * r02 + lin[5] * r12 + lin[8] * r22
            r00 = a00; r01 = a01; r02 = a02
            r10 = a10; r11 = a11; r12 = a12
            r20 = a20; r21 = a21; r22 = a22
        }
        val sx2 = sx * sx * inflation
        val sy2 = sy * sy * inflation
        val sz2 = sz * sz * inflation

        val out = destIndex * SPLAT_FLOAT_STRIDE
        dest.put(out, x); dest.put(out + 1, y); dest.put(out + 2, z); dest.put(out + 3, alpha)
        dest.put(out + 4, red); dest.put(out + 5, green); dest.put(out + 6, blue)
        dest.put(out + SPLAT_SOURCE_FLOAT, source.toFloat())
        dest.put(out + 8, r00 * r00 * sx2 + r01 * r01 * sy2 + r02 * r02 * sz2)
        dest.put(out + 9, r00 * r10 * sx2 + r01 * r11 * sy2 + r02 * r12 * sz2)
        dest.put(out + 10, r00 * r20 * sx2 + r01 * r21 * sy2 + r02 * r22 * sz2)
        dest.put(out + 11, r10 * r10 * sx2 + r11 * r11 * sy2 + r12 * r12 * sz2)
        dest.put(out + 12, r10 * r20 * sx2 + r11 * r21 * sy2 + r12 * r22 * sz2)
        dest.put(out + 13, r20 * r20 * sx2 + r21 * r21 * sy2 + r22 * r22 * sz2)
        dest.put(out + 14, 0f); dest.put(out + 15, 0f)
    }

    /** Matches [decodeScales]: the file's scales are logs, and `exp` is taken in double as there. */
    private fun decodeFusedScale(logScale: Float, scaleMultiplier: Float, config: Config): Float {
        val scale = exp(logScale.toDouble()).toFloat()
        return (scale * scaleMultiplier).finiteOr(config.fallbackScale)
            .coerceIn(config.minScale, config.maxScale)
    }

    private val ROTATION_NORM = sqrt(2f)

    private class DepthBucketSorter(
        val renderer: Renderer,
        val sourceName: String,
        val bucketBits: Int,
        val stable: Boolean,
        val keys: BufferParameters,
        val outputIndices: BufferParameters
    ) {
        private val bucketCount = 1 shl bucketBits
        private val bucketShift = 32 - bucketBits

        private val bucketCounts = storageBuffer(
            renderer = renderer,
            name = "${sourceName}_DepthBucketCounts",
            sizeBytes = bucketCount.toLong() * UInt.SIZE_BYTES,
            perFrame = false
        )

        private val bucketOffsets = storageBuffer(
            renderer = renderer,
            name = "${sourceName}_DepthBucketOffsets",
            sizeBytes = bucketCount.toLong() * UInt.SIZE_BYTES,
            perFrame = false
        )

        private val histogramModule = ComputeModule(parameters(renderer) {
            name = "${sourceName}_DepthBucketHistogram"
            compute = bucketHistogramShader()
        }).pointer()

        private val histogramMesh = ComputeMesh(renderer, histogramModule).apply {
            y = 1
            z = 1
            push.bind("depthKeys") { requireNotNull(keys.result).get() }
            push.bind("bucketCounts") { requireNotNull(bucketCounts.result).get() }
            count = 0
            bucketShift = this@DepthBucketSorter.bucketShift
        }

        private val prefixModule = ComputeModule(parameters(renderer) {
            name = "${sourceName}_DepthBucketPrefix"
            compute = bucketPrefixShader()
        }).pointer()

        private val prefixMesh = ComputeMesh(renderer, prefixModule).apply {
            x = 1
            y = 1
            z = 1
            push.bind("bucketCounts") { requireNotNull(bucketCounts.result).get() }
            push.bind("bucketOffsets") { requireNotNull(bucketOffsets.result).get() }
        }

        private val scatterModule = ComputeModule(parameters(renderer) {
            name = "${sourceName}_DepthBucketScatter"
            compute = if (stable) stableBucketScatterShader() else parallelBucketScatterShader()
        }).pointer()

        private val scatterMesh = ComputeMesh(renderer, scatterModule).apply {
            y = 1
            z = 1
            push.bind("depthKeys") { requireNotNull(keys.result).get() }
            push.bind("bucketCounts") { requireNotNull(bucketCounts.result).get() }
            push.bind("bucketOffsets") { requireNotNull(bucketOffsets.result).get() }
            push.bind("sortedIndices") { requireNotNull(outputIndices.result).get() }
            count = 0
            bucketShift = this@DepthBucketSorter.bucketShift
        }

        init {
            histogramModule += "histogram" to histogramMesh
            prefixModule += "prefix" to prefixMesh
            scatterModule += "scatter" to scatterMesh
        }

        fun sort(
            command: afield.four.device.CommandBuffer,
            count: Int
        ) {
            val bucketCountsBuffer = requireNotNull(bucketCounts.result).get()
            bucketCountsBuffer.zero(command)

            histogramMesh.x = workgroupCount(count)
            histogramMesh.count = count
            histogramModule.render(command, Rectanglef())
            computeMemoryBarrier(command, bucketCountsBuffer)

            prefixModule.render(command, Rectanglef())
            computeMemoryBarrier(command, bucketCountsBuffer)
            computeMemoryBarrier(command, requireNotNull(bucketOffsets.result).get())

            scatterMesh.x = if (stable) 1 else workgroupCount(count)
            scatterMesh.count = count
            scatterModule.render(command, Rectanglef())
        }

        fun cleanup() {
            histogramModule.current.cleanup()
            prefixModule.current.cleanup()
            scatterModule.current.cleanup()
            bucketCounts.result?.buffers?.forEach { it.cleanup() }
            bucketOffsets.result?.buffers?.forEach { it.cleanup() }
        }

        private fun bucketHistogramShader(): String = """
#version 450
#extension GL_EXT_buffer_reference : require

layout(local_size_x = $WORKGROUP_SIZE, local_size_y = 1, local_size_z = 1) in;

layout(std430, buffer_reference) readonly buffer DepthKeys {
    uint depthKeys[];
};

layout(std430, buffer_reference) buffer BucketCounts {
    uint counts[];
};

layout(push_constant) uniform PushConstants {
    DepthKeys depthKeys;
    BucketCounts bucketCounts;
    int count;
    int bucketShift;
} pc;

void main() {
    uint index = gl_GlobalInvocationID.x;
    if (index >= uint(pc.count)) return;
    uint bucket = pc.depthKeys.depthKeys[index] >> uint(pc.bucketShift);
    atomicAdd(pc.bucketCounts.counts[bucket], 1u);
}
""".trimIndent()

        private fun bucketPrefixShader(): String = """
#version 450
#extension GL_EXT_buffer_reference : require

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

layout(std430, buffer_reference) buffer BucketCounts {
    uint counts[];
};

layout(std430, buffer_reference) writeonly buffer BucketOffsets {
    uint offsets[];
};

layout(push_constant) uniform PushConstants {
    BucketCounts bucketCounts;
    BucketOffsets bucketOffsets;
} pc;

void main() {
    uint running = 0u;
    for (uint bucket = 0u; bucket < uint($bucketCount); ++bucket) {
        uint count = pc.bucketCounts.counts[bucket];
        pc.bucketOffsets.offsets[bucket] = running;
        running += count;
        pc.bucketCounts.counts[bucket] = 0u;
    }
}
""".trimIndent()

        private fun stableBucketScatterShader(): String = """
#version 450
#extension GL_EXT_buffer_reference : require

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

layout(std430, buffer_reference) readonly buffer DepthKeys {
    uint depthKeys[];
};

layout(std430, buffer_reference) buffer BucketCounts {
    uint counts[];
};

layout(std430, buffer_reference) readonly buffer BucketOffsets {
    uint offsets[];
};

layout(std430, buffer_reference) writeonly buffer SortedIndices {
    uint sortedIndices[];
};

layout(push_constant) uniform PushConstants {
    DepthKeys depthKeys;
    BucketCounts bucketCounts;
    BucketOffsets bucketOffsets;
    SortedIndices sortedIndices;
    int count;
    int bucketShift;
} pc;

void main() {
    for (uint index = 0u; index < uint(pc.count); ++index) {
        uint bucket = pc.depthKeys.depthKeys[index] >> uint(pc.bucketShift);
        uint slot = pc.bucketOffsets.offsets[bucket] + pc.bucketCounts.counts[bucket];
        pc.bucketCounts.counts[bucket] += 1u;
        pc.sortedIndices.sortedIndices[slot] = index;
    }
}
""".trimIndent()

        private fun parallelBucketScatterShader(): String = """
#version 450
#extension GL_EXT_buffer_reference : require

layout(local_size_x = $WORKGROUP_SIZE, local_size_y = 1, local_size_z = 1) in;

layout(std430, buffer_reference) readonly buffer DepthKeys {
    uint depthKeys[];
};

layout(std430, buffer_reference) buffer BucketCounts {
    uint counts[];
};

layout(std430, buffer_reference) readonly buffer BucketOffsets {
    uint offsets[];
};

layout(std430, buffer_reference) writeonly buffer SortedIndices {
    uint sortedIndices[];
};

layout(push_constant) uniform PushConstants {
    DepthKeys depthKeys;
    BucketCounts bucketCounts;
    BucketOffsets bucketOffsets;
    SortedIndices sortedIndices;
    int count;
    int bucketShift;
} pc;

void main() {
    uint index = gl_GlobalInvocationID.x;
    if (index >= uint(pc.count)) return;
    uint bucket = pc.depthKeys.depthKeys[index] >> uint(pc.bucketShift);
    uint slot = pc.bucketOffsets.offsets[bucket] + atomicAdd(pc.bucketCounts.counts[bucket], 1u);
    pc.sortedIndices.sortedIndices[slot] = index;
}
""".trimIndent()
    }

    private fun storageBuffer(
        renderer: Renderer,
        name: String,
        sizeBytes: Long,
        perFrame: Boolean = false
    ): BufferParameters =
        buffer(name = name) {
            size = max(4L, sizeBytes)
            storage()
            addressable()
            hosted()
            this.perFrame = perFrame
        }.also { it.toBuffer(renderer) }

    private fun workgroupCount(count: Int): Int =
        ((count + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).coerceAtLeast(1)

    private var ComputeMesh.count by ComputeMesh.FromStructure<Int>()
    private var ComputeMesh.bucketShift by ComputeMesh.FromStructure<Int>()
    private var ComputeMesh.resetIndices by ComputeMesh.BoundFromStructure<Int>()
    private var Mesh.count by Mesh.FromStructure<Int>()

    private fun decodeOpacity(
        buffer: FloatBuffer,
        rowBase: Int,
        columns: PackColumns,
        config: Config
    ): Float {
        val raw = if (columns.opacity >= 0) buffer.get(rowBase + columns.opacity) else 1f
        val alpha =
            if (config.opacityIsLogit && columns.hasLogitOpacity) sigmoid(raw) else normalizeColor(raw)
        return (alpha * config.opacityMultiplier).coerceIn(0f, 1f)
    }

    private fun decodeColor(buffer: FloatBuffer, rowBase: Int, columns: PackColumns): FloatArray {
        if (columns.red >= 0 && columns.green >= 0 && columns.blue >= 0) {
            return floatArrayOf(
                normalizeColor(buffer.get(rowBase + columns.red)),
                normalizeColor(buffer.get(rowBase + columns.green)),
                normalizeColor(buffer.get(rowBase + columns.blue))
            )
        }

        if (columns.dc0 >= 0 && columns.dc1 >= 0 && columns.dc2 >= 0) {
            return floatArrayOf(
                (0.5f + SH_C0 * buffer.get(rowBase + columns.dc0)).coerceIn(0f, 1f),
                (0.5f + SH_C0 * buffer.get(rowBase + columns.dc1)).coerceIn(0f, 1f),
                (0.5f + SH_C0 * buffer.get(rowBase + columns.dc2)).coerceIn(0f, 1f)
            )
        }

        return floatArrayOf(1f, 1f, 1f)
    }

    private fun decodeScales(
        buffer: FloatBuffer,
        rowBase: Int,
        columns: PackColumns,
        config: Config
    ): FloatArray {
        fun readScale(index: Int): Float {
            val at = columns.scale[index]
            val raw = if (at >= 0) buffer.get(rowBase + at) else config.fallbackScale
            val scale = if (config.scaleIsLog) exp(raw.toDouble()).toFloat() else raw
            return (scale * config.scaleMultiplier).finiteOr(config.fallbackScale)
                .coerceIn(config.minScale, config.maxScale)
        }
        return floatArrayOf(readScale(0), readScale(1), readScale(2))
    }

    private fun decodeRotation(buffer: FloatBuffer, rowBase: Int, columns: PackColumns): FloatArray {
        fun read(index: Int, absent: Float): Float {
            val at = columns.rot[index]
            return if (at >= 0) buffer.get(rowBase + at) else absent
        }
        var w = read(0, 1f)
        var x = read(1, 0f)
        var y = read(2, 0f)
        var z = read(3, 0f)
        val length = sqrt(w * w + x * x + y * y + z * z)
        if (length <= 1e-12f || !length.isFinite()) return floatArrayOf(1f, 0f, 0f, 0f)
        w /= length
        x /= length
        y /= length
        z /= length
        return floatArrayOf(w, x, y, z)
    }

    private fun covarianceFromScaleRotation(scale: FloatArray, rotation: FloatArray, inflation: Float): FloatArray {
        val w = rotation[0]
        val x = rotation[1]
        val y = rotation[2]
        val z = rotation[3]

        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        val r00 = 1f - 2f * (yy + zz)
        val r01 = 2f * (xy - wz)
        val r02 = 2f * (xz + wy)
        val r10 = 2f * (xy + wz)
        val r11 = 1f - 2f * (xx + zz)
        val r12 = 2f * (yz - wx)
        val r20 = 2f * (xz - wy)
        val r21 = 2f * (yz + wx)
        val r22 = 1f - 2f * (xx + yy)

        val sx2 = scale[0] * scale[0] * inflation
        val sy2 = scale[1] * scale[1] * inflation
        val sz2 = scale[2] * scale[2] * inflation

        val cxx = r00 * r00 * sx2 + r01 * r01 * sy2 + r02 * r02 * sz2
        val cxy = r00 * r10 * sx2 + r01 * r11 * sy2 + r02 * r12 * sz2
        val cxz = r00 * r20 * sx2 + r01 * r21 * sy2 + r02 * r22 * sz2
        val cyy = r10 * r10 * sx2 + r11 * r11 * sy2 + r12 * r12 * sz2
        val cyz = r10 * r20 * sx2 + r11 * r21 * sy2 + r12 * r22 * sz2
        val czz = r20 * r20 * sx2 + r21 * r21 * sy2 + r22 * r22 * sz2

        return floatArrayOf(cxx, cxy, cxz, cyy, cyz, czz)
    }

    private fun normalizeColor(value: Float): Float {
        val finite = value.finiteOr(1f)
        return (if (finite > 1f) finite / 255f else finite).coerceIn(0f, 1f)
    }

    private fun sigmoid(value: Float): Float {
        val v = value.finiteOr(0f).toDouble()
        return if (v >= 0.0) {
            (1.0 / (1.0 + exp(-v))).toFloat()
        } else {
            val e = exp(v)
            (e / (1.0 + e)).toFloat()
        }
    }

    private fun Float.finiteOr(default: Float): Float =
        if (isFinite()) this else default

    val depthKeyComputeShader = """
#version 450
#extension GL_EXT_buffer_reference : require

layout(local_size_x = $WORKGROUP_SIZE, local_size_y = 1, local_size_z = 1) in;

layout(std430, buffer_reference) readonly buffer SplatData {
    vec4 splats[];
};

layout(std430, buffer_reference) writeonly buffer DepthKeys {
    uint depthKeys[];
};

layout(std430, buffer_reference) writeonly buffer SortedIndices {
    uint sortedIndices[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;
    SplatData splatData;
    DepthKeys depthKeys;
    SortedIndices sortedIndices;
    float depthMin;
    float invDepthRange;
    int count;
    int resetIndices;
} pc;

void main() {
    uint index = gl_GlobalInvocationID.x;
    if (index >= uint(pc.count)) return;

    vec3 center = pc.splatData.splats[index * 4u + 0u].xyz;
    vec4 view = pc.modelView * vec4(center, 1.0);
    float depth = max(-view.z, 0.0);
    float normalizedDepth = clamp((depth - pc.depthMin) * pc.invDepthRange, 0.0, 1.0);
    uint quantizedDepth = uint(normalizedDepth * 4294967040.0);

    pc.depthKeys.depthKeys[index] = 0xffffffffu - quantizedDepth;
    if (pc.resetIndices != 0) {
        pc.sortedIndices.sortedIndices[index] = index;
    }
}
""".trimIndent()

    private fun splatVertexShaderSource(multiview: Boolean): String {
        val stereoPrelude = if (multiview) {
            """
#extension GL_EXT_multiview : require
#define SPLAT_EYE int(gl_ViewIndex)
""".trimIndent()
        } else {
            "#define SPLAT_EYE 0"
        }
        val cameraPush = if (multiview) {
            """
    mat4 modelViewL;
    mat4 modelViewR;
    mat4 projL;
    mat4 projR;
""".trimIndent()
        } else {
            """
    mat4 modelView;
    mat4 proj;
""".trimIndent()
        }
        val selectCamera = if (multiview) {
            """
    mat4 modelView = SPLAT_EYE == 0 ? pc.modelViewL : pc.modelViewR;
    mat4 proj = SPLAT_EYE == 0 ? pc.projL : pc.projR;
""".trimIndent()
        } else {
            """
    mat4 modelView = pc.modelView;
    mat4 proj = pc.proj;
""".trimIndent()
        }
        return """
#version 450
#extension GL_EXT_buffer_reference : require
$stereoPrelude

layout(std430, buffer_reference) readonly buffer SplatData {
    vec4 splats[];
};

layout(std430, buffer_reference) readonly buffer SortedIndices {
    uint sortedIndices[];
};

layout(push_constant) uniform PushConstants {
$cameraPush
    SplatData splatData;
    SortedIndices sortedIndices;
    vec2 viewportSize;
    float sizeScale;
    float alphaScale;
    float minScreenRadius;
    float maxScreenRadius;
    float alphaCutoff;
    float colorGamma;
    int count;
} pc;

layout(location = 0) out vec3 vColor;
layout(location = 1) out float vOpacity;
layout(location = 2) out vec2 vGaussianCoord;
layout(location = 3) out float vAlphaCutoff;
layout(location = 4) flat out int vSource;

const vec2 CORNERS[6] = vec2[](
    vec2(-1.0, -1.0),
    vec2( 1.0, -1.0),
    vec2( 1.0,  1.0),
    vec2( 1.0,  1.0),
    vec2(-1.0,  1.0),
    vec2(-1.0, -1.0)
);

void hideVertex() {
    gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
    vColor = vec3(0.0);
    vOpacity = 0.0;
    vGaussianCoord = vec2(9.0);
    vAlphaCutoff = 1.0;
    vSource = 0;
}

void main() {
$selectCamera
    uint sortedSlot = uint(gl_VertexIndex) / 6u;
    uint cornerIndex = uint(gl_VertexIndex) % 6u;
    if (sortedSlot >= uint(pc.count)) {
        hideVertex();
        return;
    }

    uint splatIndex = pc.sortedIndices.sortedIndices[sortedSlot];
    uint base = splatIndex * 4u;
    vec4 centerOpacity = pc.splatData.splats[base + 0u];
    vec4 color = pc.splatData.splats[base + 1u];
    vec4 covA = pc.splatData.splats[base + 2u]; // xx xy xz yy
    vec4 covB = pc.splatData.splats[base + 3u]; // yz zz _ _

    if (centerOpacity.a <= 0.0) {
        hideVertex();
        return;
    }

    vec4 view = modelView * vec4(centerOpacity.xyz, 1.0);
    float depth = -view.z;
    if (depth <= 0.0001) {
        hideVertex();
        return;
    }

    mat3 covObject = mat3(
        covA.x, covA.y, covA.z,
        covA.y, covA.w, covB.x,
        covA.z, covB.x, covB.y
    );
    mat3 viewLinear = mat3(modelView);
    mat3 covView = viewLinear * covObject * transpose(viewLinear);

    float fx = abs(proj[0][0]) * pc.viewportSize.x * 0.5;
    float fy = abs(proj[1][1]) * pc.viewportSize.y * 0.5;
    float invDepth = 1.0 / depth;
    float invDepth2 = invDepth * invDepth;
    vec3 jx = vec3(fx * invDepth, 0.0, fx * view.x * invDepth2);
    vec3 jy = vec3(0.0, fy * invDepth, fy * view.y * invDepth2);

    float c00 = dot(jx, covView * jx) + 0.3;
    float c01 = dot(jx, covView * jy);
    float c11 = dot(jy, covView * jy) + 0.3;

    float mid = 0.5 * (c00 + c11);
    float radius = length(vec2(0.5 * (c00 - c11), c01));
    float lambda1 = max(mid + radius, pc.minScreenRadius * pc.minScreenRadius);
    float lambda2 = max(mid - radius, pc.minScreenRadius * pc.minScreenRadius);

    vec2 axis1 = (abs(c01) > 1e-5)
        ? normalize(vec2(lambda1 - c11, c01))
        : ((c00 >= c11) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    vec2 axis2 = vec2(-axis1.y, axis1.x);

    float sigma1 = sqrt(lambda1) * pc.sizeScale;
    float sigma2 = sqrt(lambda2) * pc.sizeScale;
    float maxRadius = max(sigma1, sigma2) * 3.0;
    if (maxRadius > pc.maxScreenRadius) {
        float shrink = pc.maxScreenRadius / maxRadius;
        sigma1 *= shrink;
        sigma2 *= shrink;
    }

    vec2 corner = CORNERS[cornerIndex];
    vec2 pixelOffset = 3.0 * (corner.x * sigma1 * axis1 + corner.y * sigma2 * axis2);

    vec4 clip = proj * view;
    gl_Position = clip;
    gl_Position.xy += (pixelOffset / max(pc.viewportSize, vec2(1.0))) * 2.0 * clip.w;

    vColor = pow(color.rgb, vec3(pc.colorGamma));
    vOpacity = centerOpacity.a * pc.alphaScale;
    vGaussianCoord = corner * 3.0;
    vAlphaCutoff = pc.alphaCutoff;
    // Which of the packed sources this splat came from; see SPLAT_SOURCE_FLOAT.
    vSource = int(color.w + 0.5);
}
""".trimIndent()
    }

    /** Default single-view shader, retained as a public source for callers and tests. */
    val splatVertexShader = splatVertexShaderSource(multiview = false)

    /** Multiview variant: one draw, with camera matrices selected by `gl_ViewIndex`. */
    val splatMultiviewVertexShader = splatVertexShaderSource(multiview = true)

    val splatFragmentShader = """
#version 450

layout(location = 0) in vec3 vColor;
layout(location = 1) in float vOpacity;
layout(location = 2) in vec2 vGaussianCoord;
layout(location = 3) in float vAlphaCutoff;
// Declared, and unused by the default shading: it is the hook a fork of this shader uses to
// index per-source data -- a retrojection camera matrix, a texture layer, an id colour.
layout(location = 4) flat in int vSource;

layout(location = 0) out vec4 uFragColor;

void main() {
    float alpha = clamp(vOpacity * exp(-0.5 * dot(vGaussianCoord, vGaussianCoord)), 0.0, 1.0);
    if (alpha < vAlphaCutoff) discard;

    uFragColor = vec4(vColor * alpha, alpha);
}
""".trimIndent()
}

fun gaussianSplats(
    path: String,
    renderer: Renderer = fromContext(),
    target: RenderTarget = renderer.renderList,
    camera: Camera = Camera(renderer),
    config: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config(),
    name: String = File(path).nameWithoutExtension,
    renderToTexture: RenderToTexture? = null,
    renderModule: ModulePointer? = null
): GaussianSplatRenderer.Cloud =
    GaussianSplatRenderer.load(path, renderer, camera, config, name, renderToTexture, renderModule)
        .install(target, name)

fun RenderTarget.addGaussianSplats(
    path: String,
    camera: Camera = Camera(renderer),
    config: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config(),
    name: String = File(path).nameWithoutExtension,
    renderToTexture: RenderToTexture? = null,
    renderModule: ModulePointer? = null
): GaussianSplatRenderer.Cloud =
    GaussianSplatRenderer.load(path, renderer, camera, config, name, renderToTexture, renderModule)
        .install(this, name)
