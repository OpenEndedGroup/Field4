@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.fabricated

import afield.Rect
import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.BufferParameters
import afield.four.buffers.buffer
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.util.Camera
import afield.four.util.GpuRadixSort
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import org.joml.primitives.Rectanglef
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

object GaussianSplatRenderer {
    const val SPLAT_FLOAT_STRIDE = 16
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
        val viewport: Rect? = null
    )

    data class PackedSplats(
        val floats: FloatArray,
        val count: Int,
        val boundsMin: Vector3f,
        val boundsMax: Vector3f
    )

    enum class SortMode {
        DepthBuckets,
        ExactRadix
    }

    class Cloud internal constructor(
        val renderer: Renderer = fromContext(),
        val camera: Camera = fromContext(),
        val table: PlyFloatTable,
        val packed: PackedSplats,
        config: Config,
        val sourceName: String
    ) {
        var enabled: Boolean = true
        var sortEveryFrame: Boolean = true
        var sizeScale: Float = config.sizeScale
        var alphaScale: Float = config.alphaScale
        var minScreenRadius: Float = config.minScreenRadius
        var maxScreenRadius: Float = config.maxScreenRadius
        var alphaCutoff: Float = config.alphaCutoff
        var viewport: Rect? = config.viewport
        var sortMode: SortMode = config.sortMode
        var sortEveryNFrames: Int = config.sortEveryNFrames.coerceAtLeast(1)
        var sortOnlyWhenViewChanges: Boolean = config.sortOnlyWhenViewChanges
        var sortViewEpsilon: Float = config.sortViewEpsilon

        val splatData: BufferParameters = storageBuffer(
            renderer = renderer,
            name = "${sourceName}_SplatData",
            sizeBytes = packed.floats.size.toLong() * Float.SIZE_BYTES
        )

        val depthKeys: BufferParameters = storageBuffer(
            renderer = renderer,
            name = "${sourceName}_DepthKeys",
            sizeBytes = packed.count.toLong() * UInt.SIZE_BYTES,
            perFrame = false
        )

        val sortedIndices: BufferParameters = storageBuffer(
            renderer = renderer,
            name = "${sourceName}_SortedIndices",
            sizeBytes = packed.count.toLong() * UInt.SIZE_BYTES,
            perFrame = false
        )

        val sorter = GpuRadixSort(renderer)
        private val bucketSorter = DepthBucketSorter(
            renderer = renderer,
            sourceName = sourceName,
            bucketBits = config.depthBucketBits.coerceIn(1, 16),
            stable = config.stableBucketSort,
            keys = depthKeys,
            outputIndices = sortedIndices
        )

        private var modelProvider: () -> Matrix4f = { Matrix4f() }
        private var sortedOnce: Boolean = false
        private var sortedModelView: Matrix4f? = null
        private var sortAge: Int = Int.MAX_VALUE
        private var currentSortModelView: Matrix4f? = null
        private var currentDepthMin: Float = 0f
        private var currentInvDepthRange: Float = 1f

        val depthKeyModule: ComputeModulePointer
        val depthKeyMesh: ComputeMesh
        val renderModule: ModulePointer
        val renderMesh: Mesh

        init {
            uploadSplats()

            depthKeyModule = ComputeModule(parameters(renderer) {
                name = "${sourceName}_splat_depth_keys"
                compute = depthKeyComputeShader
            }).pointer()
            depthKeyMesh = ComputeMesh(renderer, depthKeyModule).apply {
                x = workgroupCount(packed.count)
                y = 1
                z = 1
                push.bind("modelView") { Matrix4f(currentSortModelView ?: modelViewMatrix()) }
                push.bind("splatData") { requireNotNull(splatData.result).get() }
                push.bind("depthKeys") { requireNotNull(depthKeys.result).get() }
                push.bind("sortedIndices") { requireNotNull(sortedIndices.result).get() }
                push.bind("depthMin") { currentDepthMin }
                push.bind("invDepthRange") { currentInvDepthRange }
                count = packed.count
                push.bind("resetIndices") {
                    if (!sortedOnce || sortMode == SortMode.ExactRadix) 1 else 0
                }
            }
            depthKeyModule += "depthKeys" to depthKeyMesh

            renderModule = Module(parameters(renderer) {
                name = "${sourceName}_gaussian_splats"
                primitiveType = triangles
                depth = 1
                depthTest = false
                blend = true
                srcColor = VK_BLEND_FACTOR_ONE
                dstColor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                srcAlpha = VK_BLEND_FACTOR_ONE
                dstAlpha = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                viewport = this@Cloud.viewport
                vertex = splatVertexShader
                fragment = splatFragmentShader
            }).pointer()
            renderMesh = Mesh(renderer, renderModule).apply {
                resize(packed.count * 6, 0, renderModule)
                push.bind("modelView") { modelViewMatrix() }
                push.bind("proj") { projectionMatrix() }
                push.bind("splatData") { requireNotNull(splatData.result).get() }
                push.bind("sortedIndices") { requireNotNull(sortedIndices.result).get() }
                push.bind("viewportSize") { viewportSize() }
                push.bind("sizeScale") { sizeScale }
                push.bind("alphaScale") { alphaScale }
                push.bind("minScreenRadius") { minScreenRadius }
                push.bind("maxScreenRadius") { maxScreenRadius }
                push.bind("alphaCutoff") { alphaCutoff }
                count = packed.count
            }
            renderModule += "splats" to renderMesh
        }

        fun setModel(model: Matrix4f): Cloud {
            modelProvider = { Matrix4f(model) }
            return this
        }

        fun bindModel(model: () -> Matrix4f): Cloud {
            modelProvider = { Matrix4f(model()) }
            return this
        }

        fun install(
            target: RenderTarget = renderer.renderList,
            name: String = sourceName
        ): Cloud {
            target.preTargets["$name-depth-sort"] = Target { command, _ ->
                if (!enabled || packed.count <= 0) return@Target

                renderModule.current.parameters.viewport = viewport
                currentSortModelView = modelViewMatrix()
                if (shouldSortThisFrame(requireNotNull(currentSortModelView))) {
                    updateDepthRange(requireNotNull(currentSortModelView))
                    depthKeyModule.render(command, Rectanglef())
                    computeMemoryBarrier(command, requireNotNull(depthKeys.result).get())
                    computeMemoryBarrier(command, requireNotNull(sortedIndices.result).get())

                    if (packed.count > 1) {
                        when (sortMode) {
                            SortMode.DepthBuckets -> bucketSorter.sort(
                                command = command,
                                count = packed.count
                            )

                            SortMode.ExactRadix -> sorter.sort(
                                commandBuffer = command,
                                keys = requireNotNull(depthKeys.result),
                                count = packed.count,
                                values = requireNotNull(sortedIndices.result),
                                signedKeys = false
                            )
                        }
                    }
                    markSorted(requireNotNull(currentSortModelView))
                    graphicsMemoryBarrier(command, requireNotNull(sortedIndices.result).get())
                } else {
                    ageSort()
                }
            }

            target += name to renderModule
            return this
        }

        fun remove(target: RenderTarget = renderer.renderList, name: String = sourceName): Cloud {
            target.preTargets.remove("$name-depth-sort")
            target.modules.remove(name)
            return this
        }

        fun cleanup() {
            sorter.cleanup()
            bucketSorter.cleanup()
            depthKeyModule.current.cleanup()
            renderModule.current.cleanup()
            splatData.result?.buffers?.forEach { it.cleanup() }
            depthKeys.result?.buffers?.forEach { it.cleanup() }
            sortedIndices.result?.buffers?.forEach { it.cleanup() }
        }

        private fun uploadSplats() {
            requireNotNull(splatData.result).write {
                put(packed.floats, padding = false)
            }
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
            include(packed.boundsMin.x, packed.boundsMin.y, packed.boundsMin.z)
            include(packed.boundsMin.x, packed.boundsMin.y, packed.boundsMax.z)
            include(packed.boundsMin.x, packed.boundsMax.y, packed.boundsMin.z)
            include(packed.boundsMin.x, packed.boundsMax.y, packed.boundsMax.z)
            include(packed.boundsMax.x, packed.boundsMin.y, packed.boundsMin.z)
            include(packed.boundsMax.x, packed.boundsMin.y, packed.boundsMax.z)
            include(packed.boundsMax.x, packed.boundsMax.y, packed.boundsMin.z)
            include(packed.boundsMax.x, packed.boundsMax.y, packed.boundsMax.z)

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

        private fun modelViewMatrix(): Matrix4f =
            Matrix4f(camera.modelView()).mul(modelProvider())

        private fun projectionMatrix(): Matrix4f =
            Matrix4f(camera.p.evaluateUniform())

        private fun viewportSize(): Vector2f {
            viewport?.let { return Vector2f(it.w.toFloat(), it.h.toFloat()) }
            val extent = renderer.swapChain.swapChainExtent
            return Vector2f(extent.width().toFloat(), extent.height().toFloat())
        }
    }

    fun load(
        path: String,
        renderer: Renderer = fromContext(),
        camera: Camera = Camera(renderer),
        config: Config = Config(),
        name: String = File(path).nameWithoutExtension
    ): Cloud {
        val table = readPlyAsFloatTable(path)
        return fromTable(table, renderer, camera, config, name)
    }

    fun fromTable(
        table: PlyFloatTable,
        renderer: Renderer = fromContext(),
        camera: Camera = Camera(renderer),
        config: Config = Config(),
        name: String = "gaussianSplats"
    ): Cloud {
        table.requireColumns("x", "y", "z")
        return Cloud(renderer, camera, table, pack(table, config), config, name)
    }

    fun pack(table: PlyFloatTable, config: Config = Config()): PackedSplats {
        table.requireColumns("x", "y", "z")
        val count = table.rowCount
        val out = FloatArray(count * SPLAT_FLOAT_STRIDE)
        val boundsMin = Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val boundsMax = Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

        for (row in 0 until count) {
            val x = table.floatAt(row, "x")
            val y = table.floatAt(row, "y")
            val z = table.floatAt(row, "z")
            boundsMin.min(Vector3f(x, y, z))
            boundsMax.max(Vector3f(x, y, z))

            val opacity = decodeOpacity(table, row, config)
            val color = decodeColor(table, row)
            val scales = decodeScales(table, row, config)
            val rotation = decodeRotation(table, row)
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

    private fun decodeOpacity(table: PlyFloatTable, row: Int, config: Config): Float {
        val raw = table.firstFloatAtOrNull(row, listOf("opacity", "alpha")) ?: 1f
        val alpha = if (config.opacityIsLogit && table.hasColumn("opacity")) sigmoid(raw) else normalizeColor(raw)
        return (alpha * config.opacityMultiplier).coerceIn(0f, 1f)
    }

    private fun decodeColor(table: PlyFloatTable, row: Int): FloatArray {
        val red = table.firstFloatAtOrNull(row, listOf("red", "diffuse_red", "r"))
        val green = table.firstFloatAtOrNull(row, listOf("green", "diffuse_green", "g"))
        val blue = table.firstFloatAtOrNull(row, listOf("blue", "diffuse_blue", "b"))
        if (red != null && green != null && blue != null) {
            return floatArrayOf(normalizeColor(red), normalizeColor(green), normalizeColor(blue))
        }

        val dc0 = table.floatAtOrNull(row, "f_dc_0")
        val dc1 = table.floatAtOrNull(row, "f_dc_1")
        val dc2 = table.floatAtOrNull(row, "f_dc_2")
        if (dc0 != null && dc1 != null && dc2 != null) {
            return floatArrayOf(
                (0.5f + SH_C0 * dc0).coerceIn(0f, 1f),
                (0.5f + SH_C0 * dc1).coerceIn(0f, 1f),
                (0.5f + SH_C0 * dc2).coerceIn(0f, 1f)
            )
        }

        return floatArrayOf(1f, 1f, 1f)
    }

    private fun decodeScales(table: PlyFloatTable, row: Int, config: Config): FloatArray {
        fun readScale(index: Int): Float {
            val raw = table.firstFloatAtOrNull(
                row,
                listOf("scale_$index", "scale$index", "s$index", listOf("sx", "sy", "sz")[index])
            ) ?: config.fallbackScale
            val scale = if (config.scaleIsLog) exp(raw.toDouble()).toFloat() else raw
            return (scale * config.scaleMultiplier).finiteOr(config.fallbackScale)
                .coerceIn(config.minScale, config.maxScale)
        }
        return floatArrayOf(readScale(0), readScale(1), readScale(2))
    }

    private fun decodeRotation(table: PlyFloatTable, row: Int): FloatArray {
        var w = table.firstFloatAtOrNull(row, listOf("rot_0", "rotation_0", "qw", "w")) ?: 1f
        var x = table.firstFloatAtOrNull(row, listOf("rot_1", "rotation_1", "qx")) ?: 0f
        var y = table.firstFloatAtOrNull(row, listOf("rot_2", "rotation_2", "qy")) ?: 0f
        var z = table.firstFloatAtOrNull(row, listOf("rot_3", "rotation_3", "qz")) ?: 0f
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

    val splatVertexShader = """
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) readonly buffer SplatData {
    vec4 splats[];
};

layout(std430, buffer_reference) readonly buffer SortedIndices {
    uint sortedIndices[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;
    mat4 proj;
    SplatData splatData;
    SortedIndices sortedIndices;
    vec2 viewportSize;
    float sizeScale;
    float alphaScale;
    float minScreenRadius;
    float maxScreenRadius;
    float alphaCutoff;
    int count;
} pc;

layout(location = 0) out vec3 vColor;
layout(location = 1) out float vOpacity;
layout(location = 2) out vec2 vGaussianCoord;
layout(location = 3) out float vAlphaCutoff;

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
}

void main() {
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

    vec4 view = pc.modelView * vec4(centerOpacity.xyz, 1.0);
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
    mat3 viewLinear = mat3(pc.modelView);
    mat3 covView = viewLinear * covObject * transpose(viewLinear);

    float fx = abs(pc.proj[0][0]) * pc.viewportSize.x * 0.5;
    float fy = abs(pc.proj[1][1]) * pc.viewportSize.y * 0.5;
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

    vec4 clip = pc.proj * view;
    gl_Position = clip;
    gl_Position.xy += (pixelOffset / max(pc.viewportSize, vec2(1.0))) * 2.0 * clip.w;

    vColor = pow(color.rgb, vec3(2.2));
    vOpacity = centerOpacity.a * pc.alphaScale;
    vGaussianCoord = corner * 3.0;
    vAlphaCutoff = pc.alphaCutoff;
}
""".trimIndent()

    val splatFragmentShader = """
#version 450

layout(location = 0) in vec3 vColor;
layout(location = 1) in float vOpacity;
layout(location = 2) in vec2 vGaussianCoord;
layout(location = 3) in float vAlphaCutoff;

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
    name: String = File(path).nameWithoutExtension
): GaussianSplatRenderer.Cloud =
    GaussianSplatRenderer.load(path, renderer, camera, config, name).install(target, name)

fun RenderTarget.addGaussianSplats(
    path: String,
    camera: Camera = Camera(renderer),
    config: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config(),
    name: String = File(path).nameWithoutExtension
): GaussianSplatRenderer.Cloud =
    GaussianSplatRenderer.load(path, renderer, camera, config, name).install(this, name)
