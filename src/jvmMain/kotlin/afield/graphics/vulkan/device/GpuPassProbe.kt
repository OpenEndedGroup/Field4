package afield.graphics.vulkan.device

import afield.app.diagnostics.logging.lprintln
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.AutoDisposer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.EXTMeshShader.*

/** Sparse RTT telemetry: one outstanding sample, polled without VK_QUERY_RESULT_WAIT_BIT. */
class GpuPassProbe(private val renderer: Renderer, private val name: String, private val timestampViews: Int = 1) {
    init { require(timestampViews in 1..32) }
    data class Snapshot(val frame: Long, val milliseconds: Double, val invocations: Map<String, Long>)
    @Volatile var snapshot: Snapshot? = null
        private set

    private val device = renderer.device.vkDevice
    private val validBits = renderer.physicalDevice.vkQueueFamilyProps[renderer.graphQueue.queueFamilyIndex].timestampValidBits()
    private val period: Double
    private val timestamps: Long
    private val statistics: Long
    private val counters = buildList {
        if (renderer.device.pipelineStatisticsQueryEnabled) {
            add("vertex" to VK_QUERY_PIPELINE_STATISTIC_VERTEX_SHADER_INVOCATIONS_BIT)
            add("fragment" to VK_QUERY_PIPELINE_STATISTIC_FRAGMENT_SHADER_INVOCATIONS_BIT)
            add("compute" to VK_QUERY_PIPELINE_STATISTIC_COMPUTE_SHADER_INVOCATIONS_BIT)
            if (renderer.device.meshShaderQueriesEnabled) {
                add("task" to VK_QUERY_PIPELINE_STATISTIC_TASK_SHADER_INVOCATIONS_BIT_EXT)
                add("mesh" to VK_QUERY_PIPELINE_STATISTIC_MESH_SHADER_INVOCATIONS_BIT_EXT)
            }
        }
    }.sortedBy { it.second }
    private var pending = false
    @Volatile private var completed = false
    private var nextFrame = 0L
    private var sampledFrame = 0L
    private var samplingStatistics = false

    init {
        MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceProperties.malloc(stack)
            vkGetPhysicalDeviceProperties(renderer.physicalDevice.vkPhysicalDevice, properties)
            period = properties.limits().timestampPeriod().toDouble()
            fun pool(type: Int, count: Int, mask: Int = 0): Long {
                val info = VkQueryPoolCreateInfo.calloc(stack).`sType$Default`()
                    .queryType(type).queryCount(count).pipelineStatistics(mask)
                val out = stack.mallocLong(1)
                check(vkCreateQueryPool(device, info, null, out) == VK_SUCCESS) { "Cannot create RTT query pool" }
                return out[0]
            }
            // A timestamp inside multiview rendering reserves one query per enabled view.
            timestamps = if (validBits > 0) pool(VK_QUERY_TYPE_TIMESTAMP, timestampViews * 2) else 0L
            statistics = if (timestamps != 0L && counters.isNotEmpty())
                pool(VK_QUERY_TYPE_PIPELINE_STATISTICS, 1, counters.fold(0) { mask, c -> mask or c.second }) else 0L
        }
        // Capture raw handles rather than this probe, so the owner ledger does not retain the RTT.
        val dev = device
        val ts = timestamps
        val stats = statistics
        AutoDisposer.register(this, "GpuPassProbe", owner = renderer.device) {
            if (ts != 0L) vkDestroyQueryPool(dev, ts, null)
            if (stats != 0L) vkDestroyQueryPool(dev, stats, null)
        }
    }

    fun begin(command: CommandBuffer, collectStatistics: Boolean = false): Boolean {
        if (!prepare(command, collectStatistics)) return false
        start(command)
        return true
    }

    /** Reset outside rendering; draw-only timestamps can then start at their actual draw boundary. */
    fun prepare(command: CommandBuffer, collectStatistics: Boolean = false): Boolean {
        if (timestamps == 0L) return false
        // A GPU-side reset has not necessarily executed yet. Availability alone could still
        // describe the previous use of this pool, so first require this submission's fence.
        if (pending) {
            if (!completed) return false
            poll()
        }
        if (pending || renderer.frameCounter < nextFrame) return false
        sampledFrame = renderer.frameCounter
        nextFrame = sampledFrame + 60
        // Whole-frame statistics enclose this RTT on some platforms. Same-type queries cannot nest.
        samplingStatistics = collectStatistics && statistics != 0L && renderer.getProfiler()?.pipeStatsEnabled != true
        vkCmdResetQueryPool(command.vkCommandBuffer, timestamps, 0, timestampViews * 2)
        if (samplingStatistics) vkCmdResetQueryPool(command.vkCommandBuffer, statistics, 0, 1)
        return true
    }

    fun start(command: CommandBuffer, afterPriorWork: Boolean = false) {
        vkCmdWriteTimestamp2(command.vkCommandBuffer,
            if (afterPriorWork) VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT else VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
            timestamps, 0)
    }

    // Statistics cover this RTT's drawing only. Starting after dependency RTTs avoids nested queries.
    fun beginStatistics(command: CommandBuffer) {
        if (samplingStatistics) vkCmdBeginQuery(command.vkCommandBuffer, statistics, 0, 0)
    }

    fun endStatistics(command: CommandBuffer) {
        if (samplingStatistics) vkCmdEndQuery(command.vkCommandBuffer, statistics, 0)
    }

    fun end(command: CommandBuffer) {
        vkCmdWriteTimestamp2(command.vkCommandBuffer, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT, timestamps, timestampViews)
        pending = true
        completed = false
        renderer.afterCurrentGraphicsFrameComplete { completed = true }
    }

    private fun poll() = MemoryStack.stackPush().use { stack ->
        val times = stack.mallocLong(timestampViews * 2)
        val status = vkGetQueryPoolResults(device, timestamps, 0, timestampViews * 2, times, 8, VK_QUERY_RESULT_64_BIT)
        if (status == VK_NOT_READY) return@use
        check(status == VK_SUCCESS) { "RTT timestamp readback failed: $status" }
        val values = stack.mallocLong(counters.size.coerceAtLeast(1))
        if (samplingStatistics) {
            val statsStatus = vkGetQueryPoolResults(device, statistics, 0, 1, values, counters.size * 8L, VK_QUERY_RESULT_64_BIT)
            if (statsStatus == VK_NOT_READY) return@use
            check(statsStatus == VK_SUCCESS) { "RTT statistics readback failed: $statsStatus" }
        }
        val mask = -1L ushr (64 - validBits)
        val elapsed = ((times[timestampViews] - times[0]) and mask) * period / 1e6
        val counts = if (!samplingStatistics) emptyMap() else counters.mapIndexed { i, c -> c.first to values[i] }.toMap()
        snapshot = Snapshot(sampledFrame, elapsed, counts)
        pending = false
        lprintln("info,perf", "GPU RTT '$name': ${"%.3f".format(java.util.Locale.ROOT, elapsed)} ms invocations=$counts frame=$sampledFrame")
    }
}
