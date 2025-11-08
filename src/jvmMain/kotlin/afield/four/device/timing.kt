package afield.four.device

import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRPerformanceQuery.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VK12.vkResetQueryPool
import org.lwjgl.vulkan.*
import java.nio.ByteBuffer

data class ProfilingOptions(
    val enabled: Boolean = false,
    val timestamps: Boolean = true,
    val pipelineStats: Boolean = true,
    val perfCounters: Boolean = true,
    val maxTimestampsPerFrame: Int = 2048,
    val maxPipeStatsPerFrame: Int = 512,
    val maxPerfQueriesPerFrame: Int = 256
)

object PipeStatIndex {
    const val IA_VERTICES = 0
    const val IA_PRIMITIVES = 1
    const val VS_INVOCATIONS = 2
    const val GS_INVOCATIONS = 3
    const val GS_PRIMITIVES = 4
    const val CLIP_INVOCATIONS = 5
    const val CLIP_PRIMITIVES = 6
    const val FS_INVOCATIONS = 7
    const val TCS_INVOCATIONS = 8
    const val TES_INVOCATIONS = 9
    const val CS_INVOCATIONS = 10
}

class FrameAllocator(private val max: Int) {
    private var next = 0
    fun alloc(count: Int = 1): Int {
        val n = next
        val nn = n + count
        check(nn <= max) { "Query pool overflow: need=$nn max=$max" }
        next = nn
        return n
    }
    fun capacity(): Int = max
    fun reset() { next = 0 }
}

class GpuProfiler(
    private val device: VkDevice,
    private val physicalDevice: VkPhysicalDevice,
    private val queueFamilyIndex: Int,
    private val framesInFlight: Int,
    val opts: ProfilingOptions
) {

    data class PerfCounterMeta(val name: String, val unit: Int, val scope: Int)
    val perfMeta: List<PerfCounterMeta> get() = _perfMeta
    private var _perfMeta: List<PerfCounterMeta> = emptyList()

    val enabled = opts.enabled
    val timestampsEnabled: Boolean
    val pipeStatsEnabled: Boolean
    val perfQueryEnabled: Boolean

    val timestampPeriodNs: Double

    private val timestampPools = LongArray(framesInFlight)
    private val pipeStatPools  = LongArray(framesInFlight)
    private val perfPools      = LongArray(framesInFlight)

    private val tsAlloc = arrayOfNulls<FrameAllocator>(framesInFlight)
    private val psAlloc = arrayOfNulls<FrameAllocator>(framesInFlight)
    private val pfAlloc = arrayOfNulls<FrameAllocator>(framesInFlight)

    private val hostQueryResetSupported: Boolean
    private val pipelineStatisticsQuerySupported: Boolean
    private val meshShaderStatBitsSupported: Boolean
    private val perfExtensionPresent: Boolean

    private val pipelineStatisticsMask: Int

    init {
        if (!enabled) {
            timestampsEnabled = false
            pipeStatsEnabled = false
            perfQueryEnabled = false
            timestampPeriodNs = 0.0
            hostQueryResetSupported = false
            pipelineStatisticsQuerySupported = false
            meshShaderStatBitsSupported = false
            perfExtensionPresent = false
            pipelineStatisticsMask = 0

        } else {

            val props = VkPhysicalDeviceProperties.malloc()
            vkGetPhysicalDeviceProperties(physicalDevice, props)
            timestampPeriodNs = props.limits().timestampPeriod().toDouble()
            val apiVersion = props.apiVersion()
            props.free()

            val features2 = VkPhysicalDeviceFeatures2.calloc()
            val meshFeat = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc()
            features2.pNext(meshFeat.address())
            vkGetPhysicalDeviceFeatures2(physicalDevice, features2)

            pipelineStatisticsQuerySupported = features2.features().pipelineStatisticsQuery()
            meshShaderStatBitsSupported = meshFeat.meshShader() || meshFeat.taskShader()
            features2.free()
            meshFeat.free()

            hostQueryResetSupported =
                (VK_API_VERSION_MAJOR(apiVersion) > 1) ||
                        (VK_API_VERSION_MAJOR(apiVersion) == 1 && VK_API_VERSION_MINOR(apiVersion) >= 2) ||
                        hasDeviceExtension(physicalDevice, "VK_EXT_host_query_reset")

            perfExtensionPresent = hasDeviceExtension(physicalDevice, "VK_KHR_performance_query")

            timestampsEnabled = opts.timestamps
            pipeStatsEnabled = opts.pipelineStats && pipelineStatisticsQuerySupported
            perfQueryEnabled = opts.perfCounters && perfExtensionPresent

            var mask =
                VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_VERTICES_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_PRIMITIVES_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_VERTEX_SHADER_INVOCATIONS_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_CLIPPING_INVOCATIONS_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_CLIPPING_PRIMITIVES_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_FRAGMENT_SHADER_INVOCATIONS_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_COMPUTE_SHADER_INVOCATIONS_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_GEOMETRY_SHADER_INVOCATIONS_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_GEOMETRY_SHADER_PRIMITIVES_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_TESSELLATION_CONTROL_SHADER_PATCHES_BIT or
                        VK_QUERY_PIPELINE_STATISTIC_TESSELLATION_EVALUATION_SHADER_INVOCATIONS_BIT

            if (meshShaderStatBitsSupported) {
                // Optional: include TASK/MESH bits here if needed.
            }
            pipelineStatisticsMask = mask

            repeat(framesInFlight) { i ->
                if (timestampsEnabled) {
                    val a = FrameAllocator(opts.maxTimestampsPerFrame)
                    tsAlloc[i] = a
                    timestampPools[i] = createQueryPool(VK_QUERY_TYPE_TIMESTAMP, a.capacity(), 0)
                }
                if (pipeStatsEnabled) {
                    val a = FrameAllocator(opts.maxPipeStatsPerFrame)
                    psAlloc[i] = a
                    pipeStatPools[i] = createQueryPool(
                        VK_QUERY_TYPE_PIPELINE_STATISTICS,
                        a.capacity(),
                        pipelineStatisticsMask
                    )
                }
                if (perfQueryEnabled) {
                    val a = FrameAllocator(opts.maxPerfQueriesPerFrame)
                    pfAlloc[i] = a
                    perfPools[i] = createPerfQueryPoolAndChooseCounters(a.capacity())
                }
            }
        }
    }

    fun beginFrame(frameIndex: Int) {
        if (!enabled) return
        tsAlloc[frameIndex]?.reset()
        psAlloc[frameIndex]?.reset()
        pfAlloc[frameIndex]?.reset()

        if (!hostQueryResetSupported) return
        timestampPools[frameIndex].takeIf { it != 0L }?.let {
            vkResetQueryPool(device, it, 0, tsAlloc[frameIndex]!!.capacity())
        }
        pipeStatPools[frameIndex].takeIf { it != 0L }?.let {
            vkResetQueryPool(device, it, 0, psAlloc[frameIndex]!!.capacity())
        }
        perfPools[frameIndex].takeIf { it != 0L }?.let {
            vkResetQueryPool(device, it, 0, pfAlloc[frameIndex]!!.capacity())
        }
    }

    fun endFrame(@Suppress("UNUSED_PARAMETER") frameIndex: Int) = Unit

    // Timestamps
    fun writeTimestamp(cmd: VkCommandBuffer, frameIndex: Int, stage: Int): Int {
        if (!timestampsEnabled) return -1
        val idx = tsAlloc[frameIndex]!!.alloc()
        vkCmdWriteTimestamp(cmd, stage, timestampPools[frameIndex], idx)
        return idx
    }

    // Pipeline stats
    data class PipeStatScope internal constructor(val pool: Long, val index: Int)

    fun beginPipelineStats(cmd: VkCommandBuffer, frameIndex: Int): PipeStatScope? {
        if (!pipeStatsEnabled) return null
        val idx = psAlloc[frameIndex]!!.alloc()
        vkCmdBeginQuery(cmd, pipeStatPools[frameIndex], idx, 0)
        return PipeStatScope(pipeStatPools[frameIndex], idx)
    }

    fun endPipelineStats(cmd: VkCommandBuffer, scope: PipeStatScope?) {
        if (scope == null) return
        vkCmdEndQuery(cmd, scope.pool, scope.index)
    }

    // Perf counters
    data class PerfScope internal constructor(val index: Int)

    fun beginPerfCounters(cmd: VkCommandBuffer, frameIndex: Int): PerfScope? {
        if (!perfQueryEnabled) return null
        val idx = pfAlloc[frameIndex]!!.alloc()
        vkCmdBeginQuery(cmd, perfPools[frameIndex], idx, 0)
        return PerfScope(idx)
    }

    fun endPerfCounters(cmd: VkCommandBuffer, frameIndex: Int, scope: PerfScope?) {
        if (scope == null) return
        vkCmdEndQuery(cmd, perfPools[frameIndex], scope.index)
    }

    fun fetchTimestampNsRange(frameIndex: Int, first: Int, lastInclusive: Int): LongArray {
        if (!timestampsEnabled) return LongArray(0)
        val count = lastInclusive - first + 1
        if (count <= 0) return LongArray(0)

        val buf = MemoryUtil.memAllocLong(count)
        val res = vkGetQueryPoolResults(
            device, timestampPools[frameIndex], first, count,
            buf, java.lang.Long.BYTES.toLong(),
            VK_QUERY_RESULT_64_BIT or VK_QUERY_RESULT_WAIT_BIT
        )
        check(res == VK_SUCCESS) { "vkGetQueryPoolResults (timestamps) failed: $res" }

        val out = LongArray(count) { k -> (buf.get(k) * timestampPeriodNs).toLong() }
        MemoryUtil.memFree(buf)
        return out
    }

    fun fetchPipelineStats(frameIndex: Int, index: Int, numStatWords: Int = 11): LongArray {
        if (!pipeStatsEnabled) return LongArray(0)

        val buf = MemoryUtil.memAllocLong(numStatWords)
        val res = vkGetQueryPoolResults(
            device, pipeStatPools[frameIndex], index, 1,
            buf, java.lang.Long.BYTES.toLong(),
            VK_QUERY_RESULT_64_BIT or VK_QUERY_RESULT_WAIT_BIT
        )
        check(res == VK_SUCCESS) { "vkGetQueryPoolResults (pipeline stats) failed: $res" }

        val out = LongArray(numStatWords) { k -> buf.get(k) }
        MemoryUtil.memFree(buf)
        return out
    }

    fun fetchPerfCounters(frameIndex: Int, index: Int, expectedCount: Int): LongArray {
        if (!perfQueryEnabled) return LongArray(0)

        val buf = MemoryUtil.memAllocLong(expectedCount)
        val res = vkGetQueryPoolResults(
            device, perfPools[frameIndex], index, 1,
            buf, java.lang.Long.BYTES.toLong(),
            VK_QUERY_RESULT_64_BIT or VK_QUERY_RESULT_WAIT_BIT
        )
        check(res == VK_SUCCESS) { "vkGetQueryPoolResults (perf) failed: $res" }

        val out = LongArray(expectedCount) { k -> buf.get(k) }
        MemoryUtil.memFree(buf)
        return out
    }

    private fun createQueryPool(type: Int, count: Int, pipelineStatistics: Int): Long {
        val ci = VkQueryPoolCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
            .queryType(type)
            .queryCount(count)
        if (type == VK_QUERY_TYPE_PIPELINE_STATISTICS) {
            ci.pipelineStatistics(pipelineStatistics)
        }
        val p = MemoryUtil.memAllocLong(1)
        val res = vkCreateQueryPool(device, ci, null, p)
        ci.free()
        check(res == VK_SUCCESS) { "vkCreateQueryPool failed: $res" }
        val handle = p[0]
        MemoryUtil.memFree(p)
        return handle
    }

    private fun createPerfQueryPoolAndChooseCounters(capacity: Int): Long {
        if (!perfQueryEnabled) return 0L

        val lockInfo = VkAcquireProfilingLockInfoKHR.calloc()
            .sType(VK_STRUCTURE_TYPE_ACQUIRE_PROFILING_LOCK_INFO_KHR)
            .timeout(1_000_000_000L)
        val lockRes = vkAcquireProfilingLockKHR(device, lockInfo)
        lockInfo.free()
        check(lockRes == VK_SUCCESS || lockRes == VK_TIMEOUT) { "vkAcquireProfilingLockKHR failed: $lockRes" }

        val pCount = MemoryUtil.memAllocInt(1)
        vkEnumeratePhysicalDeviceQueueFamilyPerformanceQueryCountersKHR(
            physicalDevice, queueFamilyIndex, pCount, null, null
        )
        val counterCount = pCount[0]
        if (counterCount <= 0) {
            MemoryUtil.memFree(pCount)
            vkReleaseProfilingLockKHR(device)
            return 0L
        }

        val counters = VkPerformanceCounterKHR.calloc(counterCount)
        val descriptions = VkPerformanceCounterDescriptionKHR.calloc(counterCount)
        val resEnum = vkEnumeratePhysicalDeviceQueueFamilyPerformanceQueryCountersKHR(
            physicalDevice, queueFamilyIndex, pCount, counters, descriptions
        )
        MemoryUtil.memFree(pCount)
        check(resEnum == VK_SUCCESS) { "Enumerate perf counters failed: $resEnum" }

        val selected = ArrayList<Int>(16)

        fun pickByUnit(unit: Int, max: Int = 4) {
            var taken = 0
            for (i in 0 until counterCount) {
                if (counters[i].unit() == unit) {
                    selected += i
                    taken++
                    if (taken >= max || selected.size >= 16) break
                }
            }
        }

        pickByUnit(VK_PERFORMANCE_COUNTER_UNIT_CYCLES_KHR)
        pickByUnit(VK_PERFORMANCE_COUNTER_UNIT_NANOSECONDS_KHR)
        pickByUnit(VK_PERFORMANCE_COUNTER_UNIT_GENERIC_KHR)

        var i = 0
        while (selected.size < 8 && i < counterCount) {
            if (!selected.contains(i)) selected += i
            i++
        }

        val selCount = selected.size
        val counterIndices = MemoryUtil.memAllocInt(selCount)
        selected.forEachIndexed { k, idx -> counterIndices.put(k, idx) }

        val metas = ArrayList<PerfCounterMeta>(selCount)
        for (idxSel in selected) {
            val c = counters[idxSel]
            val d = descriptions[idxSel]
            metas += PerfCounterMeta(
                name = d.nameString() ?: "counter_$idxSel",
                unit = c.unit(),
                scope = c.scope()
            )
        }
        _perfMeta = metas

        val perfPoolInfo = VkQueryPoolPerformanceCreateInfoKHR.calloc()
            .sType(VK_STRUCTURE_TYPE_QUERY_POOL_PERFORMANCE_CREATE_INFO_KHR)
            .queueFamilyIndex(queueFamilyIndex)
            .pCounterIndices(counterIndices)

        val qpInfo = VkQueryPoolCreateInfo.calloc()
            .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
            .pNext(perfPoolInfo.address())
            .queryType(VK_QUERY_TYPE_PERFORMANCE_QUERY_KHR)
            .queryCount(capacity)

        val pPool = MemoryUtil.memAllocLong(1)
        val resCreate = vkCreateQueryPool(device, qpInfo, null, pPool)

        qpInfo.free()
        perfPoolInfo.free()
        MemoryUtil.memFree(counterIndices)
        counters.free()
        descriptions.free()

        check(resCreate == VK_SUCCESS) { "vkCreateQueryPool (perf) failed: $resCreate" }

        val handle = pPool[0]
        MemoryUtil.memFree(pPool)

        vkReleaseProfilingLockKHR(device)
        return handle
    }

    private fun hasDeviceExtension(physicalDevice: VkPhysicalDevice, name: String): Boolean {
        val pCount = MemoryUtil.memAllocInt(1)
        vkEnumerateDeviceExtensionProperties(physicalDevice, null as? ByteBuffer?, pCount, null)
        val count = pCount[0]
        val props = VkExtensionProperties.calloc(count)
        vkEnumerateDeviceExtensionProperties(physicalDevice, null as? ByteBuffer?, pCount, props)
        MemoryUtil.memFree(pCount)

        var found = false
        for (i in 0 until count) {
            if (name == props[i].extensionNameString()) { found = true; break }
        }
        props.free()
        return found
    }

    fun destroy() {
        if (!enabled) return
        for (i in 0 until framesInFlight) {
            if (timestampPools[i] != 0L) vkDestroyQueryPool(device, timestampPools[i], null)
            if (pipeStatPools[i]  != 0L) vkDestroyQueryPool(device, pipeStatPools[i], null)
            if (perfPools[i]      != 0L) vkDestroyQueryPool(device, perfPools[i], null)
        }
    }

    fun formatPerfValue(v: Long, unit: Int): String = when (unit) {
        VK_PERFORMANCE_COUNTER_UNIT_GENERIC_KHR -> v.toString()
        VK_PERFORMANCE_COUNTER_UNIT_PERCENTAGE_KHR -> "%.2f %%".format(v.toDouble())
        VK_PERFORMANCE_COUNTER_UNIT_NANOSECONDS_KHR -> "%.3f ms".format(v / 1e6)
        VK_PERFORMANCE_COUNTER_UNIT_CYCLES_KHR -> "$v cycles"
        VK_PERFORMANCE_COUNTER_UNIT_BYTES_KHR -> humanBytes(v)
        VK_PERFORMANCE_COUNTER_UNIT_BYTES_PER_SECOND_KHR -> humanBytesPerSec(v)
        VK_PERFORMANCE_COUNTER_UNIT_KELVIN_KHR -> "%.2f K".format(v.toDouble())
        VK_PERFORMANCE_COUNTER_UNIT_WATTS_KHR -> "%.3f W".format(v / 1e3)
        VK_PERFORMANCE_COUNTER_UNIT_VOLTS_KHR -> "%.3f V".format(v / 1e3)
        VK_PERFORMANCE_COUNTER_UNIT_AMPS_KHR -> "%.3f A".format(v / 1e3)
        VK_PERFORMANCE_COUNTER_UNIT_HERTZ_KHR -> "%.3f MHz".format(v / 1e6)
        else -> v.toString()
    }

    private fun humanBytes(v: Long): String {
        val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
        val dv = v.toDouble()
        return when {
            dv >= gb -> "%.2f GiB".format(dv / gb)
            dv >= mb -> "%.2f MiB".format(dv / mb)
            dv >= kb -> "%.2f KiB".format(dv / kb)
            else -> "$v B"
        }
    }
    private fun humanBytesPerSec(v: Long): String = humanBytes(v) + "/s"
}
