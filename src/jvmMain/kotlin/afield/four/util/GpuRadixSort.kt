package afield.four.util

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.BufferParameters
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.buffer
import afield.four.device.CommandBuffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.Parameters
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.fromContext
import afield.four.modules.pointer
import afield.four.targets.RenderTarget
import afield.stdlib.linalg.Rectanglef
import kotlin.math.ceil

/**
 * Stable GPU radix sort for uint keys stored in storage buffers.
 *
 * This sorter owns its scratch buffers and inline compute shaders so it can live as a single
 * Kotlin file. It sorts `uint` keys in-place across a pair of ping-pong passes and can optionally
 * carry along a `uint` payload buffer.
 *
 * Requirements:
 * - key/value buffers must be `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT`
 * - each element is a single 32-bit unsigned integer
 * - `bitsPerPass` must evenly divide 32
 */
class GpuRadixSort(
    val renderer: Renderer = fromContext(),
    val workgroupSize: Int = 256,
    val bitsPerPass: Int = 4
) {
    init {
        require(workgroupSize > 0) { "workgroupSize must be > 0" }
        require(bitsPerPass in setOf(1, 2, 4, 8)) { "bitsPerPass must be one of 1, 2, 4 or 8" }
        require(32 % bitsPerPass == 0) { "bitsPerPass must evenly divide 32" }
    }

    data class SortResult(
        val keys: FrameBufferGroup,
        val values: FrameBufferGroup?
    )

    private data class SortPass(
        val histogramModule: afield.four.modules.ComputeModulePointer,
        val histogramMesh: ComputeMesh,
        val prefixModule: afield.four.modules.ComputeModulePointer,
        val prefixMesh: ComputeMesh,
        val scatterModule: afield.four.modules.ComputeModulePointer,
        val scatterMesh: ComputeMesh,
        val histogramBuffer: FrameBufferGroup,
        val prefixBuffer: FrameBufferGroup,
        val bucketBaseBuffer: FrameBufferGroup,
        val outputKeysBuffer: FrameBufferGroup,
        val outputValuesBuffer: FrameBufferGroup?
    )

    private data class SortPlan(
        val keys: FrameBufferGroup,
        val values: FrameBufferGroup?,
        val usesPayloads: Boolean,
        val scratchKeysBuffer: FrameBufferGroup,
        val scratchValuesBuffer: FrameBufferGroup?,
        val histogramBuffer: FrameBufferGroup,
        val prefixBuffer: FrameBufferGroup,
        val bucketBaseBuffer: FrameBufferGroup,
        val passes: List<SortPass>
    )

    private val radix = 1 shl bitsPerPass
    private val radixMask = radix - 1
    private val passCount = 32 / bitsPerPass

    private var scratchCapacity = 0
    private var scratchKeys: BufferParameters? = null
    private var scratchValues: BufferParameters? = null
    private var groupHistogram: BufferParameters? = null
    private var groupPrefix: BufferParameters? = null
    private var bucketBase: BufferParameters? = null
    private var cachedPlan: SortPlan? = null

    fun sort(
        commandBuffer: CommandBuffer,
        keys: VulkanBuffer,
        count: Int,
        values: VulkanBuffer? = null,
        signedKeys: Boolean = false
    ): SortResult {
        return sort(
            commandBuffer = commandBuffer,
            keys = keys.asFrameBufferGroup(),
            count = count,
            values = values?.asFrameBufferGroup(),
            signedKeys = signedKeys
        )
    }

    fun sort(
        commandBuffer: CommandBuffer,
        keys: FrameBufferGroup,
        count: Int,
        values: FrameBufferGroup? = null,
        signedKeys: Boolean = false
    ): SortResult {
        require(count >= 0) { "count must be >= 0" }
        if (count <= 1) return SortResult(keys, values)

        val keyCapacity = keys.get().requestedSize / UInt.SIZE_BYTES
        require(count.toLong() <= keyCapacity) {
            "Requested $count keys but key buffer only has capacity for $keyCapacity uint elements"
        }

        if (values != null) {
            val valueCapacity = values.get().requestedSize / UInt.SIZE_BYTES
            require(count.toLong() <= valueCapacity) {
                "Requested $count payload values but payload buffer only has capacity for $valueCapacity uint elements"
            }
        }

        ensureScratch(count, values != null)
        val plan = getOrCreatePlan(keys, values)
        executePlan(plan, commandBuffer, count, signedKeys)

        return SortResult(keys = keys, values = values)
    }

    fun installInto(
        target: RenderTarget,
        keys: VulkanBuffer,
        count: Int,
        values: VulkanBuffer? = null,
        signedKeys: Boolean = false,
        name: String = "gpuRadixSort"
    ) {
        target.others[name] = Target { command, _ ->
            sort(command, keys, count, values, signedKeys)
        }
    }

    fun installInto(
        target: RenderTarget,
        keys: FrameBufferGroup,
        count: Int,
        values: FrameBufferGroup? = null,
        signedKeys: Boolean = false,
        name: String = "gpuRadixSort"
    ) {
        target.others[name] = Target { command, _ ->
            sort(command, keys, count, values, signedKeys)
        }
    }

    fun installInto(
        target: RenderTarget,
        keys: VulkanBuffer,
        count: Int,
        values: VulkanBuffer? = null,
        signedKeys: Boolean = false,
        name: String = "gpuRadixSort",
        onSorted: (SortResult) -> Unit
    ) {
        target.others[name] = Target { command, _ ->
            onSorted(sort(command, keys, count, values, signedKeys))
        }
    }

    fun installInto(
        target: RenderTarget,
        keys: FrameBufferGroup,
        count: Int,
        values: FrameBufferGroup? = null,
        signedKeys: Boolean = false,
        name: String = "gpuRadixSort",
        onSorted: (SortResult) -> Unit
    ) {
        target.others[name] = Target { command, _ ->
            onSorted(sort(command, keys, count, values, signedKeys))
        }
    }

    fun cleanup() {
        cachedPlan?.cleanupNow()
        scratchKeys?.result?.buffers?.forEach { it.cleanup() }
        scratchValues?.result?.buffers?.forEach { it.cleanup() }
        groupHistogram?.result?.buffers?.forEach { it.cleanup() }
        groupPrefix?.result?.buffers?.forEach { it.cleanup() }
        bucketBase?.result?.buffers?.forEach { it.cleanup() }
        cachedPlan = null
        scratchKeys = null
        scratchValues = null
        groupHistogram = null
        groupPrefix = null
        bucketBase = null
        scratchCapacity = 0
    }

    private fun ensureScratch(count: Int, withValues: Boolean) {
        if (count <= scratchCapacity && (!withValues || scratchValues != null)) return
        dropCachedStateDeferred()

        val groupCount = ceil(count.toDouble() / workgroupSize.toDouble()).toInt().coerceAtLeast(1)

        scratchKeys = storageBuffer("radixScratchKeys", count.toLong() * UInt.SIZE_BYTES)
        scratchValues = null
        if (withValues) {
            scratchValues = storageBuffer("radixScratchValues", count.toLong() * UInt.SIZE_BYTES)
        }
        groupHistogram = storageBuffer("radixGroupHistogram", groupCount.toLong() * radix * UInt.SIZE_BYTES)
        groupPrefix = storageBuffer("radixGroupPrefix", groupCount.toLong() * radix * UInt.SIZE_BYTES)
        bucketBase = storageBuffer("radixBucketBase", radix.toLong() * UInt.SIZE_BYTES)
        scratchCapacity = count
    }

    private fun getOrCreatePlan(keys: FrameBufferGroup, values: FrameBufferGroup?): SortPlan {
        val existing = cachedPlan
        if (existing != null &&
            existing.keys === keys &&
            existing.values === values &&
            existing.scratchKeysBuffer === requireNotNull(scratchKeys).result &&
            existing.scratchValuesBuffer === scratchValues?.result &&
            existing.histogramBuffer === requireNotNull(groupHistogram).result &&
            existing.prefixBuffer === requireNotNull(groupPrefix).result &&
            existing.bucketBaseBuffer === requireNotNull(bucketBase).result
        ) {
            return existing
        }

        existing?.let { deferCleanup(it) }

        val plan = buildPlan(keys, values)
        cachedPlan = plan
        return plan
    }

    private fun buildPlan(keys: FrameBufferGroup, values: FrameBufferGroup?): SortPlan {
        val externalKeys = keys.asStorageBinding("InputKeys")
        val externalValues = values?.asStorageBinding("InputValues")
        val scratchKeysBinding = requireNotNull(scratchKeys).renamed("OutputKeys")
        val scratchValuesBinding = scratchValues?.renamed("OutputValues")
        val histogramBinding = requireNotNull(groupHistogram).renamed("GroupHistogram")
        val prefixBinding = requireNotNull(groupPrefix).renamed("GroupPrefix")
        val bucketBaseBinding = requireNotNull(bucketBase).renamed("BucketBase")

        var inputKeys = externalKeys
        var outputKeys = scratchKeysBinding
        var inputValues = externalValues
        var outputValues = scratchValuesBinding

        val passes = buildList {
            repeat(passCount) {
                val histogramModule = buildHistogramModule(inputKeys, histogramBinding, it)
                val histogramMesh = ComputeMesh(renderer, histogramModule).apply {
                    this.y = 1
                    this.z = 1
                }
                histogramModule += "dispatch" to histogramMesh

                val prefixModule = buildPrefixModule(histogramBinding, prefixBinding, bucketBaseBinding, it)
                val prefixMesh = ComputeMesh(renderer, prefixModule).apply {
                    this.x = 1
                    this.y = 1
                    this.z = 1
                }
                prefixModule += "dispatch" to prefixMesh

                val scatterModule = if (values == null) {
                    buildScatterKeysModule(inputKeys, outputKeys, prefixBinding, bucketBaseBinding, it)
                } else {
                    buildScatterPairsModule(
                        inputKeys = inputKeys,
                        outputKeys = outputKeys,
                        inputValues = requireNotNull(inputValues),
                        outputValues = requireNotNull(outputValues),
                        prefix = prefixBinding,
                        bucketBase = bucketBaseBinding,
                        passIndex = it
                    )
                }
                val scatterMesh = ComputeMesh(renderer, scatterModule).apply {
                    this.y = 1
                    this.z = 1
                }
                scatterModule += "dispatch" to scatterMesh

                add(
                    SortPass(
                        histogramModule = histogramModule,
                        histogramMesh = histogramMesh,
                        prefixModule = prefixModule,
                        prefixMesh = prefixMesh,
                        scatterModule = scatterModule,
                        scatterMesh = scatterMesh,
                        histogramBuffer = requireNotNull(histogramBinding.result),
                        prefixBuffer = requireNotNull(prefixBinding.result),
                        bucketBaseBuffer = requireNotNull(bucketBaseBinding.result),
                        outputKeysBuffer = requireNotNull(outputKeys.result),
                        outputValuesBuffer = outputValues?.result
                    )
                )

                val nextInputKeys = outputKeys.renamed("InputKeys")
                val nextOutputKeys = inputKeys.renamed("OutputKeys")
                inputKeys = nextInputKeys
                outputKeys = nextOutputKeys

                if (values != null) {
                    val nextInputValues = requireNotNull(outputValues).renamed("InputValues")
                    val nextOutputValues = requireNotNull(inputValues).renamed("OutputValues")
                    inputValues = nextInputValues
                    outputValues = nextOutputValues
                }
            }
        }

        return SortPlan(
            keys = keys,
            values = values,
            usesPayloads = values != null,
            scratchKeysBuffer = requireNotNull(scratchKeys).result!!,
            scratchValuesBuffer = scratchValues?.result,
            histogramBuffer = requireNotNull(groupHistogram).result!!,
            prefixBuffer = requireNotNull(groupPrefix).result!!,
            bucketBaseBuffer = requireNotNull(bucketBase).result!!,
            passes = passes
        )
    }

    private fun executePlan(plan: SortPlan, commandBuffer: CommandBuffer, count: Int, signedKeys: Boolean) {
        val groupCount = ceil(count.toDouble() / workgroupSize.toDouble()).toInt().coerceAtLeast(1)
        val signedInt = if (signedKeys) 1 else 0

        // If an upstream compute step produced these buffers earlier in the same frame,
        // make those writes visible before radix pass 0 reads them.
        computeMemoryBarrier(commandBuffer, plan.keys.get().buffer, plan.keys.get().requestedSize)
        plan.values?.let {
            computeMemoryBarrier(commandBuffer, it.get().buffer, it.get().requestedSize)
        }

        plan.passes.forEachIndexed { passIndex, pass ->
            val shift = passIndex * bitsPerPass

            pass.histogramMesh.x = groupCount
            pass.histogramMesh.count = count
            pass.histogramMesh.shift = shift
            pass.histogramMesh.groupCount = groupCount
            pass.histogramMesh.signedKeys = signedInt
            pass.histogramModule.render(commandBuffer, Rectanglef())
            computeMemoryBarrier(commandBuffer, pass.histogramBuffer.get().buffer, pass.histogramBuffer.get().requestedSize)

            pass.prefixMesh.groupCount = groupCount
            pass.prefixModule.render(commandBuffer, Rectanglef())
            computeMemoryBarrier(commandBuffer, pass.prefixBuffer.get().buffer, pass.prefixBuffer.get().requestedSize)
            computeMemoryBarrier(commandBuffer, pass.bucketBaseBuffer.get().buffer, pass.bucketBaseBuffer.get().requestedSize)

            pass.scatterMesh.x = groupCount
            pass.scatterMesh.count = count
            pass.scatterMesh.shift = shift
            pass.scatterMesh.groupCount = groupCount
            pass.scatterMesh.signedKeys = signedInt
            pass.scatterModule.render(commandBuffer, Rectanglef())
            computeMemoryBarrier(commandBuffer, pass.outputKeysBuffer.get().buffer, pass.outputKeysBuffer.get().requestedSize)
            pass.outputValuesBuffer?.let {
                computeMemoryBarrier(commandBuffer, it.get().buffer, it.get().requestedSize)
            }
        }
    }

    private fun dropCachedStateDeferred() {
        cachedPlan?.let { deferCleanup(it) }
        scratchKeys?.result?.let { buffers ->
            renderer.cleanup.add(Runnable { buffers.buffers.forEach { it.cleanup() } })
        }
        scratchValues?.result?.let { buffers ->
            renderer.cleanup.add(Runnable { buffers.buffers.forEach { it.cleanup() } })
        }
        groupHistogram?.result?.let { buffers ->
            renderer.cleanup.add(Runnable { buffers.buffers.forEach { it.cleanup() } })
        }
        groupPrefix?.result?.let { buffers ->
            renderer.cleanup.add(Runnable { buffers.buffers.forEach { it.cleanup() } })
        }
        bucketBase?.result?.let { buffers ->
            renderer.cleanup.add(Runnable { buffers.buffers.forEach { it.cleanup() } })
        }
        cachedPlan = null
        scratchKeys = null
        scratchValues = null
        groupHistogram = null
        groupPrefix = null
        bucketBase = null
        scratchCapacity = 0
    }

    private fun deferCleanup(plan: SortPlan) {
        renderer.cleanup.add(Runnable { plan.cleanupNow() })
    }

    private fun SortPlan.cleanupNow() {
        passes.forEach { pass ->
            pass.histogramModule.current.cleanup()
            pass.prefixModule.current.cleanup()
            pass.scatterModule.current.cleanup()
        }
    }

    private fun storageBuffer(name: String, sizeBytes: Long): BufferParameters {
        return buffer(name = name) {
            size = sizeBytes
            storage()
            hosted()
            // The sorter can run once per in-flight frame, so scratch must be per-frame.
            // Reusing a single histogram/prefix/scratch buffer across swapchain images causes
            // multi-frame contamination that looks like the sort "settles" a few frames later.
            perFrame = true
        }.also { it.toBuffer(renderer) }
    }

    private fun buildHistogramModule(
        inputKeys: BufferParameters,
        histogram: BufferParameters,
        passIndex: Int
    ): afield.four.modules.ComputeModulePointer {
        val shader = """
            #version 450

            layout(local_size_x = $workgroupSize, local_size_y = 1, local_size_z = 1) in;

            layout(set = 0, binding = 0) readonly buffer InputKeys {
                uint keys[];
            } inputKeys;

            layout(set = 0, binding = 1) writeonly buffer GroupHistogram {
                uint counts[];
            } groupHistogram;

            layout(push_constant) uniform PushConstants {
                int count;
                int shift;
                int groupCount;
                int signedKeys;
            } pc;

            shared uint localCounts[$radix];

            uint normalizedKey(uint keyValue) {
                return (pc.signedKeys != 0) ? (keyValue ^ 0x80000000u) : keyValue;
            }

            void main() {
                uint localIndex = gl_LocalInvocationID.x;
                uint groupIndex = gl_WorkGroupID.x;
                uint globalIndex = gl_GlobalInvocationID.x;

                if (localIndex < $radix) {
                    localCounts[localIndex] = 0u;
                }
                barrier();

                if (globalIndex < uint(pc.count)) {
                    uint keyValue = normalizedKey(inputKeys.keys[globalIndex]);
                    uint digit = (keyValue >> uint(pc.shift)) & uint($radixMask);
                    atomicAdd(localCounts[digit], 1u);
                }
                barrier();

                if (localIndex < $radix) {
                    groupHistogram.counts[groupIndex * uint($radix) + localIndex] = localCounts[localIndex];
                }
            }
        """.trimIndent()

        return ComputeModule(
            Parameters(
                renderer = renderer,
                compute = shader,
                name = "GpuRadixSort_Histogram[$passIndex]",
                ssboResolver = { descriptor ->
                    when (descriptor.name) {
                        "InputKeys" -> inputKeys
                        "GroupHistogram" -> histogram
                        else -> null
                    }
                }
            )
        ).pointer()
    }

    private fun buildPrefixModule(
        histogram: BufferParameters,
        prefix: BufferParameters,
        bucketBaseBuffer: BufferParameters,
        passIndex: Int
    ): afield.four.modules.ComputeModulePointer {
        val shader = """
            #version 450

            layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

            layout(set = 0, binding = 0) readonly buffer GroupHistogram {
                uint counts[];
            } groupHistogram;

            layout(set = 0, binding = 1) writeonly buffer GroupPrefix {
                uint offsets[];
            } groupPrefix;

            layout(set = 0, binding = 2) writeonly buffer BucketBase {
                uint base[];
            } bucketBase;

            layout(push_constant) uniform PushConstants {
                int groupCount;
            } pc;

            void main() {
                if (gl_GlobalInvocationID.x != 0u) return;

                uint running = 0u;
                for (uint bucket = 0u; bucket < uint($radix); ++bucket) {
                    bucketBase.base[bucket] = running;

                    uint bucketCount = 0u;
                    for (uint group = 0u; group < uint(pc.groupCount); ++group) {
                        uint index = group * uint($radix) + bucket;
                        groupPrefix.offsets[index] = bucketCount;
                        bucketCount += groupHistogram.counts[index];
                    }

                    running += bucketCount;
                }
            }
        """.trimIndent()

        return ComputeModule(
            Parameters(
                renderer = renderer,
                compute = shader,
                name = "GpuRadixSort_Prefix[$passIndex]",
                ssboResolver = { descriptor ->
                    when (descriptor.name) {
                        "GroupHistogram" -> histogram
                        "GroupPrefix" -> prefix
                        "BucketBase" -> bucketBaseBuffer
                        else -> null
                    }
                }
            )
        ).pointer()
    }

    private fun buildScatterKeysModule(
        inputKeys: BufferParameters,
        outputKeys: BufferParameters,
        prefix: BufferParameters,
        bucketBaseBuffer: BufferParameters,
        passIndex: Int
    ): afield.four.modules.ComputeModulePointer {
        val shader = """
            #version 450

            layout(local_size_x = $workgroupSize, local_size_y = 1, local_size_z = 1) in;

            layout(set = 0, binding = 0) readonly buffer InputKeys {
                uint keys[];
            } inputKeys;

            layout(set = 0, binding = 1) writeonly buffer OutputKeys {
                uint keys[];
            } outputKeys;

            layout(set = 0, binding = 2) readonly buffer GroupPrefix {
                uint offsets[];
            } groupPrefix;

            layout(set = 0, binding = 3) readonly buffer BucketBase {
                uint base[];
            } bucketBase;

            layout(push_constant) uniform PushConstants {
                int count;
                int shift;
                int groupCount;
                int signedKeys;
            } pc;

            shared uint localDigit[$workgroupSize];

            uint normalizedKey(uint keyValue) {
                return (pc.signedKeys != 0) ? (keyValue ^ 0x80000000u) : keyValue;
            }

            void main() {
                uint localIndex = gl_LocalInvocationID.x;
                uint groupIndex = gl_WorkGroupID.x;
                uint globalIndex = gl_GlobalInvocationID.x;
                bool valid = globalIndex < uint(pc.count);

                uint keyValue = 0u;
                uint digit = 0u;
                if (valid) {
                    keyValue = inputKeys.keys[globalIndex];
                    digit = (normalizedKey(keyValue) >> uint(pc.shift)) & uint($radixMask);
                }
                localDigit[localIndex] = digit;
                barrier();

                if (!valid) return;

                uint localRank = 0u;
                for (uint i = 0u; i < localIndex; ++i) {
                    if (localDigit[i] == digit) {
                        localRank += 1u;
                    }
                }

                uint baseIndex = bucketBase.base[digit];
                uint groupOffset = groupPrefix.offsets[groupIndex * uint($radix) + digit];
                uint destination = baseIndex + groupOffset + localRank;
                outputKeys.keys[destination] = keyValue;
            }
        """.trimIndent()

        return ComputeModule(
            Parameters(
                renderer = renderer,
                compute = shader,
                name = "GpuRadixSort_ScatterKeys[$passIndex]",
                ssboResolver = { descriptor ->
                    when (descriptor.name) {
                        "InputKeys" -> inputKeys
                        "OutputKeys" -> outputKeys
                        "GroupPrefix" -> prefix
                        "BucketBase" -> bucketBaseBuffer
                        else -> null
                    }
                }
            )
        ).pointer()
    }

    private fun buildScatterPairsModule(
        inputKeys: BufferParameters,
        outputKeys: BufferParameters,
        inputValues: BufferParameters,
        outputValues: BufferParameters,
        prefix: BufferParameters,
        bucketBase: BufferParameters,
        passIndex: Int
    ): afield.four.modules.ComputeModulePointer {
        val shader = """
            #version 450

            layout(local_size_x = $workgroupSize, local_size_y = 1, local_size_z = 1) in;

            layout(set = 0, binding = 0) readonly buffer InputKeys {
                uint keys[];
            } inputKeys;

            layout(set = 0, binding = 1) writeonly buffer OutputKeys {
                uint keys[];
            } outputKeys;

            layout(set = 0, binding = 2) readonly buffer InputValues {
                uint values[];
            } inputValues;

            layout(set = 0, binding = 3) writeonly buffer OutputValues {
                uint values[];
            } outputValues;

            layout(set = 0, binding = 4) readonly buffer GroupPrefix {
                uint offsets[];
            } groupPrefix;

            layout(set = 0, binding = 5) readonly buffer BucketBase {
                uint base[];
            } bucketBase;

            layout(push_constant) uniform PushConstants {
                int count;
                int shift;
                int groupCount;
                int signedKeys;
            } pc;

            shared uint localDigit[$workgroupSize];

            uint normalizedKey(uint keyValue) {
                return (pc.signedKeys != 0) ? (keyValue ^ 0x80000000u) : keyValue;
            }

            void main() {
                uint localIndex = gl_LocalInvocationID.x;
                uint groupIndex = gl_WorkGroupID.x;
                uint globalIndex = gl_GlobalInvocationID.x;
                bool valid = globalIndex < uint(pc.count);

                uint keyValue = 0u;
                uint payloadValue = 0u;
                uint digit = 0u;
                if (valid) {
                    keyValue = inputKeys.keys[globalIndex];
                    payloadValue = inputValues.values[globalIndex];
                    digit = (normalizedKey(keyValue) >> uint(pc.shift)) & uint($radixMask);
                }
                localDigit[localIndex] = digit;
                barrier();

                if (!valid) return;

                uint localRank = 0u;
                for (uint i = 0u; i < localIndex; ++i) {
                    if (localDigit[i] == digit) {
                        localRank += 1u;
                    }
                }

                uint baseIndex = bucketBase.base[digit];
                uint groupOffset = groupPrefix.offsets[groupIndex * uint($radix) + digit];
                uint destination = baseIndex + groupOffset + localRank;
                outputKeys.keys[destination] = keyValue;
                outputValues.values[destination] = payloadValue;
            }
        """.trimIndent()

        return ComputeModule(
            Parameters(
                renderer = renderer,
                compute = shader,
                name = "GpuRadixSort_ScatterPairs[$passIndex]",
                ssboResolver = { descriptor ->
                    when (descriptor.name) {
                        "InputKeys" -> inputKeys
                        "OutputKeys" -> outputKeys
                        "InputValues" -> inputValues
                        "OutputValues" -> outputValues
                        "GroupPrefix" -> prefix
                        "BucketBase" -> bucketBase
                        else -> null
                    }
                }
            )
        ).pointer()
    }

    private var ComputeMesh.count by ComputeMesh.FromStructure<Int>()
    private var ComputeMesh.shift by ComputeMesh.FromStructure<Int>()
    private var ComputeMesh.groupCount by ComputeMesh.FromStructure<Int>()
    private var ComputeMesh.signedKeys by ComputeMesh.FromStructure<Int>()

    private fun BufferParameters.renamed(name: String): BufferParameters {
        val renamed = BufferParameters(source = source, name = name)
        renamed.perFrame = perFrame
        renamed.size = size
        renamed.usage = usage
        renamed.memory = memory
        renamed.needsDeviceAddress = needsDeviceAddress
        renamed.result = result
        return renamed
    }

    private fun FrameBufferGroup.asStorageBinding(name: String): BufferParameters {
        val first = buffers.firstOrNull() ?: error("FrameBufferGroup for '$name' is empty")
        require(first.usage and org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT != 0) {
            "Buffer '$name' must have VK_BUFFER_USAGE_STORAGE_BUFFER_BIT set"
        }

        val descriptor = BufferParameters(name = name).apply {
            size = first.requestedSize
            usage = first.usage
            memory = first.reqMask
            needsDeviceAddress = first.needsDeviceAddress
            result = this@asStorageBinding
        }

        buffers.forEach { buffer ->
            if (buffer.source == null) {
                buffer.source = descriptor.store()
            }
        }

        return descriptor
    }
}

fun VulkanBuffer.asFrameBufferGroup(renderer: Renderer = this.renderer): FrameBufferGroup {
    return FrameBufferGroup(renderer = renderer, n = 1, buffers = mutableListOf(this))
}
