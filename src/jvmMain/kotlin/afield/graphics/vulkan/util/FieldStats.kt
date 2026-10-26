/**
 * Computes streaming statistics of shader values on the GPU and reads the results back to box code.
 *
 * [ShaderFieldStats] owns a partial buffer and a result buffer (default bindings
 * [FIELD_STATS_DEFAULT_PARTIAL_BINDING] and [FIELD_STATS_DEFAULT_RESULT_BINDING]) for a shader
 * that includes `fieldStats`. Each channel reduces to a [FieldScalarStatsSnapshot], whose
 * [FieldScalarStatsSnapshot.quantile] and [FieldScalarStatsSnapshot.cdf] read a fixed-size
 * centroid sketch; [ShaderFieldStats.scalar] and [ShaderFieldStats.state] give per-channel and
 * per-slot handles with `read()` and `latest()`.
 */

package afield.graphics.vulkan.util

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.BufferParameters
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.buffers.buffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.render.ComputeMesh
import afield.graphics.render.ComputeModule
import afield.graphics.render.ComputeModulePointer
import afield.graphics.render.Parameters
import afield.graphics.render.computeMemoryBarrier
import afield.graphics.render.pointer
import afield.app.diagnostics.logging.lprintln
import afield.math.linalg.Rectanglef
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.min

/**
 * The name a shader includes to get the statistics GLSL: `#include "fieldStats"`.
 *
 * The include expands differently depending on how the box was bound. A box that called
 * `fieldStatsHere` gets the recording functions as well as the reading ones; a box bound as a
 * consumer with `useFieldStats` gets only the readers. An include with no binding at all is a
 * compile error saying which of the two to call.
 */
const val FIELD_STATS_INCLUDE_NAME = "fieldStats"

/** The name of the scratch buffer the recording shader appends subgroup partials to, used to match
 * the shader's declaration to the buffer the host allocates. */
const val FIELD_STATS_PARTIAL_BUFFER_NAME = "FieldStatsPartialBuffer"

/** The name of the buffer the reduce pass writes finished statistics into, and the only one the host
 * reads back. */
const val FIELD_STATS_RESULT_BUFFER_NAME = "FieldStatsResultBuffer"

/** Default binding for the partial buffer, 61, up beside the probe's 63, where a shader's own
 * bindings are unlikely to reach. Override it on the constructor if they do. */
const val FIELD_STATS_DEFAULT_PARTIAL_BINDING = 61

/** Default binding for the result buffer, 62, next door to
 * [FIELD_STATS_DEFAULT_PARTIAL_BINDING]. */
const val FIELD_STATS_DEFAULT_RESULT_BINDING = 62

private val fieldStatsReadbackExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "field-stats-readback").apply { isDaemon = true }
}

/**
 * What a box remembers about the statistics block its shaders include: which block, and whether this
 * box is the one recording into it.
 *
 * One box records and any number may read, so the binding rather than the block decides which GLSL
 * `#include "fieldStats"` expands to here.
 */
data class ShaderFieldStatsBinding(
    /** The statistics block itself, which stays owned by the box that allocated it however many
     * consumers bind to it. */
    val stats: ShaderFieldStats,
    /** True for the box that records samples, which gets the recording functions and the subgroup
     * extensions they need; false for a box that only reads the reduced result. */
    val producer: Boolean
)

/**
 * One channel's statistics for one frame, as the reduce pass left them and the host read them back.
 *
 * Every number here describes the values recorded during a single frame; the block is reset at the
 * start of the next one, so this is a per-frame summary rather than a running total. Counts are
 * exact. The moments are accumulated in 32-bit floats on the GPU and widened to `Double` on the way
 * out, so they carry single precision whatever their type says.
 */
data class FieldScalarStatsSnapshot(
    /** Counts completed reduce passes since the block was created, and so identifies the frame this
     * came from. Two reads with the same generation are the same frame's numbers. */
    val generation: Long,
    /** How many shader invocations recorded into this channel, finite or not. */
    val calls: Long,
    /** How many of those values were finite. This is the denominator for [mean] and [variance], and
     * the total weight the quantile sketch is read against. */
    val finiteCount: Long,
    /** How many were NaN or infinite. Non-zero here is the signal to look for: it says the shader is
     * producing values that no average will show. */
    val invalidCount: Long,
    /** How many finite values were not exactly zero: the cheap test for whether a channel is
     * carrying anything at all. */
    val nonZeroCount: Long,
    /** The smallest finite value recorded, exactly, not read off the sketch. Positive infinity when
     * nothing finite was recorded. */
    val min: Double,
    /** The largest finite value recorded, exactly. Negative infinity when nothing finite was
     * recorded. */
    val max: Double,
    /** The mean of the finite values, [sum] over [finiteCount], and zero when there were none. */
    val mean: Double,
    /** The population variance, computed on the GPU as the mean of squares less the square of the
     * mean and clamped at zero. That form loses precision when the values are large and their spread
     * is small, which is where the clamp earns its place. */
    val variance: Double,
    /** The sum of the finite values. */
    val sum: Double,
    /** The sum of their squares. */
    val sumSquares: Double,
    /** The square root of [variance]. */
    val standardDeviation: Double,
    /** How many subgroup partials the recording pass had to throw away because the partial buffer was
     * full. Non-zero means every number here was computed from a subset, and the fix is a larger
     * `maxPartials`. */
    val droppedPartials: Long,
    /** The quantile sketch: value-and-weight pairs in ascending value order, weights summing to
     * [finiteCount], empty entries dropped. Read it through [quantile] and [cdf] rather than
     * directly. A centroid's value is one recorded subgroup's mean standing in for a whole span of
     * them, so the sketch summarises group averages rather than individual samples. */
    val centroids: List<Pair<Double, Double>>
) {
    /**
     * The value at quantile [q], read off the centroid sketch, with `q` clamped to 0..1.
     *
     * Walks the centroids accumulating weight and returns the value of the first one that reaches
     * `q` of [finiteCount], so the answer is always one of the recorded centroid values rather than
     * an interpolation between two. Returns NaN when nothing finite was recorded. Because a centroid
     * is a subgroup mean, extreme quantiles are pulled in toward the middle — use [min] and [max]
     * for the true ends.
     */
    fun quantile(q: Double): Double {
        if (finiteCount == 0L || centroids.isEmpty()) return Double.NaN
        val target = q.coerceIn(0.0, 1.0) * finiteCount.toDouble()
        var cumulative = 0.0
        var answer = centroids.first().first
        for ((value, weight) in centroids) {
            answer = value
            cumulative += weight
            if (cumulative >= target) break
        }
        return answer
    }

    /**
     * The fraction of recorded values at or below [value], read off the centroid sketch and clamped
     * to 0..1.
     *
     * Sums the weight of every leading centroid whose value is at most [value] — leading, because the
     * walk stops at the first centroid above it. Returns NaN when nothing finite was recorded. The
     * inverse of [quantile] to the sketch's resolution, which is one centroid per lane.
     */
    fun cdf(value: Double): Double {
        if (finiteCount == 0L) return Double.NaN
        val weight = centroids.takeWhile { it.first <= value }.sumOf { it.second }
        return (weight / finiteCount.toDouble()).coerceIn(0.0, 1.0)
    }
}

/**
 * One smoothing slot's current reading: a statistic of a channel, carried across frames with
 * exponential smoothing instead of being recomputed from scratch.
 *
 * A per-frame mean jumps around enough to be useless for driving anything. A slot takes one statistic
 * — the mean, or a quantile — and smooths it on the GPU as part of the reduce pass, so a shader can
 * read a stable number with `fieldStatsSmoothedMean` or `fieldStatsSmoothedQuantile` and box code can
 * read the same number here. Slots are configured by the shader that uses them, not from Kotlin.
 */
data class FieldStatsStateSnapshot(
    /** Which statistic is being smoothed: 0 for an unused slot, 1 for the mean, 2 for the quantile at
     * [parameter]. */
    val mode: Int,
    /** Which channel's statistic this slot follows. */
    val channel: Int,
    /** The quantile this slot tracks when [mode] is 2, between 0 and 1. Unread for the mean. */
    val parameter: Double,
    /** How many frames it takes the smoothed value to cover half the distance to a new reading. The
     * per-frame blend is `1 - 2^(-1/halfLife)`, so larger is slower; the shader side floors it just
     * above zero. */
    val halfLife: Double,
    /** The smoothed value as of this frame. Zero on a slot that has not run yet. */
    val value: Double,
    /** Whether the slot has a real reading. A slot starts uninitialised and takes its first frame's
     * statistic whole rather than blending up from zero, and a slot whose mode, channel, quantile or
     * half-life changes restarts the same way, so a reconfigured slot does not drift from its old
     * value to its new one. */
    val initialized: Boolean
)

/**
 * A handle on one channel of a statistics block, so box code can hold onto a channel rather than
 * passing its index around.
 *
 * Obtained from [ShaderFieldStats.scalar]. It stores the index and nothing else, so two handles on
 * the same channel are interchangeable.
 */
class ShaderFieldStatsScalar internal constructor(
    private val stats: ShaderFieldStats,
    /** Which channel this handle refers to. */
    val index: Int
) {
    /** Reads the result buffer as it stands, without waiting for the GPU. See [ShaderFieldStats.read]
     * for what that means for the numbers. */
    fun read(): FieldScalarStatsSnapshot = stats.read(index)

    /** The most recent fence-synchronised read-back for this channel, or `null` if none has completed
     * yet. This is the one to call each frame. */
    fun latest(): FieldScalarStatsSnapshot? = stats.latest(index)
}

/**
 * A handle on one smoothing slot of a statistics block, obtained from [ShaderFieldStats.state].
 *
 * The slot's configuration comes from the shader; this side only reads it.
 */
class ShaderFieldStatsState internal constructor(
    private val stats: ShaderFieldStats,
    /** Which slot this handle refers to. */
    val index: Int
) {
    /** Reads the result buffer as it stands, without waiting for the GPU. */
    fun read(): FieldStatsStateSnapshot = stats.readState(index)

    /** The most recent fence-synchronised read-back for this slot, or `null` if none has completed
     * yet. */
    fun latest(): FieldStatsStateSnapshot? = stats.latestState(index)
}

/**
 * A statistics block: the two GPU buffers, the two compute passes that reset and reduce them, and the
 * read-back that brings each frame's numbers to box code.
 *
 * Allocate one through `Box.fieldStatsHere` rather than constructing it; that registers the binding
 * the `fieldStats` include reads. Per frame it resets the partial buffer before the recording shader
 * runs, reduces the partials afterwards, and — unless [cpuSnapshotsEnabled] is off — queues a
 * read-back behind the frame's fence whose result shows up in [latest].
 *
 * All of the sizing is fixed at construction, and the buffers are sized from it: a wider block costs
 * memory whether or not the shader fills it, and the partial buffer is the large one.
 */
class ShaderFieldStats(
    /** The box that owns this block. Its name and id are what a failed read-back is reported
     * against. */
    val box: Box,
    /** How many independent statistics this block tracks, 1 to 64. A channel is a slot the shader
     * chooses when it records, not a component of a vector: four channels is how you watch four
     * unrelated quantities. */
    val channels: Int = 1,
    /** How many centroids the quantile sketch keeps per channel: a power of two from 16 to 256, and
     * also the reduce pass's workgroup size. Smaller is a coarser [FieldScalarStatsSnapshot.quantile]
     * and nothing else: the counts and moments are reduced over every partial whatever this is. */
    val sketchSize: Int = 256,
    /** How many subgroup partials one channel may record in a frame before the rest are counted as
     * [FieldScalarStatsSnapshot.droppedPartials]. This is the term that dominates the partial
     * buffer's size, at 48 bytes per partial per channel, and the block is double-buffered, so that
     * figure is allocated twice over. */
    val maxPartials: Int = 65_536,
    /** How many smoothing slots the block carries, 1 to 64. Slots cost almost nothing and are
     * configured from the shader, so the default leaves room for a few without asking. */
    val stateSlots: Int = 8,
    /** The descriptor set both buffers are bound in. */
    val descriptorSet: Int = 0,
    /** Binding for the partial buffer. Change it only if the shader's own bindings reach this far
     * up. */
    val partialBinding: Int = FIELD_STATS_DEFAULT_PARTIAL_BINDING,
    /** Binding for the result buffer. */
    val resultBinding: Int = FIELD_STATS_DEFAULT_RESULT_BINDING
) {
    init {
        require(channels in 1..64) { "channels must be in 1..64" }
        require(sketchSize in 16..256 && sketchSize.countOneBits() == 1) {
            "sketchSize must be a power of two in 16..256"
        }
        require(maxPartials >= sketchSize) { "maxPartials must be >= sketchSize" }
        require(stateSlots in 1..64) { "stateSlots must be in 1..64" }
    }

    private val partialHeaderBytes = 16L + channels.toLong() * 16L
    private val partialRecordBytes = 48L
    private val resultHeaderBytes = 16L
    private val resultRecordBytes = 48L
    private val stateRecordBytes = 48L
    private val centroidBytes = 8L

    private val partialBufferBytes =
        partialHeaderBytes + 2L * channels * maxPartials * partialRecordBytes
    private val resultBufferBytes =
        resultHeaderBytes +
            2L * channels * resultRecordBytes +
            stateSlots * stateRecordBytes +
            2L * channels * sketchSize * centroidBytes

    private var rendererRef: Renderer? = null
    private var partialParameters: BufferParameters? = null
    private var resultParameters: BufferParameters? = null
    private var resetModule: ComputeModulePointer? = null
    private var reduceModule: ComputeModulePointer? = null
    private var initialized = false
    private val latestSnapshots = AtomicReferenceArray<FieldScalarStatsSnapshot?>(channels)
    private val latestStates = AtomicReferenceArray<FieldStatsStateSnapshot?>(stateSlots)

    /**
     * Whether each frame queues a read-back of the result buffer to the CPU. On by default.
     *
     * Turn it off for a block only the shader reads — `fieldStatsQuantile` and the smoothing slots
     * all work on the GPU without this — and [latest] then stays at whatever it last held. [read]
     * still works, since it goes to the buffer directly.
     */
    @Volatile
    var cpuSnapshotsEnabled: Boolean = true

    internal fun partialBufferParameters(renderer: Renderer): BufferParameters {
        ensureRenderer(renderer)
        return partialParameters!!
    }

    internal fun resultBufferParameters(renderer: Renderer): BufferParameters {
        ensureRenderer(renderer)
        return resultParameters!!
    }

    internal fun beforeProducer(command: CommandBuffer, renderer: Renderer) {
        ensureRenderer(renderer)
        val partial = partialBuffer()
        val result = resultBuffer()
        if (!initialized) {
            partial.zero(command)
            result.zero(command)
            initialized = true
        }
        resetModule!!.current.render(command, Rectanglef())
        computeMemoryBarrier(command, partial)
        computeMemoryBarrier(command, result)
    }

    internal fun afterProducer(command: CommandBuffer, renderer: Renderer) {
        ensureRenderer(renderer)
        computeMemoryBarrier(command, partialBuffer())
        computeMemoryBarrier(command, resultBuffer())
        reduceModule!!.current.render(command, Rectanglef())
        computeMemoryBarrier(command, resultBuffer())
        if (cpuSnapshotsEnabled) {
            renderer.afterCurrentComputeFrameSubmitted { fence ->
                fieldStatsReadbackExecutor.submit {
                    runCatching {
                        fence.fenceWait()
                        val readback = readAll()
                        readback.snapshots.forEachIndexed(latestSnapshots::set)
                        readback.states.forEachIndexed(latestStates::set)
                    }.onFailure {
                        lprintln(
                            "error,graphics",
                            "field stats readback failed for '${box.name}' (${box.id}): ${it.message}"
                        )
                    }
                }
            }
        }
    }

    /** A handle on channel [index], for code that would rather hold a channel than an index. Throws
     * for an index outside `0 until channels`. */
    fun scalar(index: Int): ShaderFieldStatsScalar {
        require(index in 0 until channels) { "index must be in 0 until $channels" }
        return ShaderFieldStatsScalar(this, index)
    }

    /**
     * The most recent fence-synchronised read-back for [channel], or `null` if none has completed
     * yet.
     *
     * This is the per-frame call. The read-back runs on a background thread once the frame's fence
     * has signalled, so what comes back is a frame or two old but internally consistent — every
     * number in it is from the same reduce pass. Returns `null` while the first frames are still in
     * flight, and whenever [cpuSnapshotsEnabled] has been off since the block was made.
     */
    fun latest(channel: Int = 0): FieldScalarStatsSnapshot? {
        require(channel in 0 until channels) { "channel must be in 0 until $channels" }
        return latestSnapshots.get(channel)
    }

    /**
     * Reads [channel] out of the result buffer immediately, without waiting for the GPU.
     *
     * There is no fence wait here, so this can land mid-reduce and see a frame's numbers half
     * written. It also reads all channels and slots to answer for one. Use it from a test or a
     * one-shot inspection where the frame boundary is known; use [latest] in a running box.
     */
    fun read(channel: Int = 0): FieldScalarStatsSnapshot {
        require(channel in 0 until channels) { "channel must be in 0 until $channels" }
        return readAll().snapshots[channel]
    }

    /** A handle on smoothing slot [index]. Throws for an index outside `0 until stateSlots`. */
    fun state(index: Int): ShaderFieldStatsState {
        require(index in 0 until stateSlots) { "index must be in 0 until $stateSlots" }
        return ShaderFieldStatsState(this, index)
    }

    /** The most recent fence-synchronised read-back for slot [index], or `null` if none has completed
     * yet. The per-frame call for a smoothing slot. */
    fun latestState(index: Int): FieldStatsStateSnapshot? {
        require(index in 0 until stateSlots) { "index must be in 0 until $stateSlots" }
        return latestStates.get(index)
    }

    /** Reads slot [index] out of the result buffer immediately, with the same no-fence caveat as
     * [read]. */
    fun readState(index: Int): FieldStatsStateSnapshot {
        require(index in 0 until stateSlots) { "index must be in 0 until $stateSlots" }
        return readAll().states[index]
    }

    private data class Readback(
        val snapshots: List<FieldScalarStatsSnapshot>,
        val states: List<FieldStatsStateSnapshot>
    )

    private fun readAll(): Readback {
        val header = IntArray(4)
        val snapshots = MutableList<FieldScalarStatsSnapshot?>(channels) { null }
        val states = MutableList<FieldStatsStateSnapshot?>(stateSlots) { null }

        resultBuffer().mapAndRead_bytes {
            val data = duplicate().order(ByteOrder.nativeOrder())
            repeat(4) { header[it] = data.getInt(it * 4) }
            val slot = header[0].coerceIn(0, 1)
            repeat(channels) { channel ->
                val counts = IntArray(4)
                val moments = FloatArray(4)
                val extras = FloatArray(4)
                val centroids = mutableListOf<Pair<Double, Double>>()
                val recordIndex = slot * channels + channel
                val recordOffset = resultHeaderBytes.toInt() + recordIndex * resultRecordBytes.toInt()
                repeat(4) { counts[it] = data.getInt(recordOffset + it * 4) }
                repeat(4) { moments[it] = data.getFloat(recordOffset + 16 + it * 4) }
                repeat(4) { extras[it] = data.getFloat(recordOffset + 32 + it * 4) }

                val centroidBase = resultHeaderBytes.toInt() +
                    channels * 2 * resultRecordBytes.toInt() +
                    stateSlots * stateRecordBytes.toInt() +
                    (slot * channels + channel) * sketchSize * centroidBytes.toInt()
                repeat(sketchSize) { index ->
                    val offset = centroidBase + index * centroidBytes.toInt()
                    val value = data.getFloat(offset).toDouble()
                    val weight = data.getFloat(offset + 4).toDouble()
                    if (weight > 0.0) centroids += value to weight
                }

                snapshots[channel] = FieldScalarStatsSnapshot(
                    generation = header[1].toUInt().toLong(),
                    calls = counts[0].toUInt().toLong(),
                    finiteCount = counts[1].toUInt().toLong(),
                    invalidCount = counts[2].toUInt().toLong(),
                    nonZeroCount = counts[3].toUInt().toLong(),
                    min = moments[0].toDouble(),
                    max = moments[1].toDouble(),
                    mean = moments[2].toDouble(),
                    variance = moments[3].toDouble(),
                    sum = extras[0].toDouble(),
                    sumSquares = extras[1].toDouble(),
                    standardDeviation = extras[2].toDouble(),
                    droppedPartials = extras[3].toLong(),
                    centroids = centroids
                )
            }

            val stateBase = resultHeaderBytes.toInt() + channels * 2 * resultRecordBytes.toInt()
            repeat(stateSlots) { index ->
                val offset = stateBase + index * stateRecordBytes.toInt()
                states[index] = FieldStatsStateSnapshot(
                    mode = data.getInt(offset),
                    channel = data.getInt(offset + 4),
                    parameter = data.getFloat(offset + 8).toDouble(),
                    halfLife = data.getFloat(offset + 12).toDouble(),
                    value = data.getFloat(offset + 16).toDouble(),
                    initialized = data.getInt(offset + 20) != 0
                )
            }
        }

        return Readback(
            snapshots = snapshots.map { requireNotNull(it) },
            states = states.map { requireNotNull(it) }
        )
    }

    private fun partialBuffer(): VulkanBuffer =
        partialParameters!!.toBuffer(rendererRef!!).getAt(0, write = false)

    private fun resultBuffer(): VulkanBuffer =
        resultParameters!!.toBuffer(rendererRef!!).getAt(0, write = false)

    private fun ensureRenderer(renderer: Renderer) {
        if (rendererRef === renderer && partialParameters != null) return
        rendererRef = renderer
        initialized = false

        partialParameters = buffer(from = null, name = FIELD_STATS_PARTIAL_BUFFER_NAME) {
            size = partialBufferBytes
            storage()
            dest()
        }.also { it.toBuffer(renderer) }

        resultParameters = buffer(from = null, name = FIELD_STATS_RESULT_BUFFER_NAME) {
            size = resultBufferBytes
            storage()
            dest()
            // CPU reads the reduced result back each frame; ask for HOST_CACHED memory
            // (streamReads) so the readback is not served from write-combined memory.
            streamReads()
        }.also { it.toBuffer(renderer) }

        resetModule = buildResetModule(renderer)
        reduceModule = buildReduceModule(renderer)
    }

    private fun resolver(renderer: Renderer): (BufferParameters) -> BufferParameters? = { requested ->
        when (requested.name) {
            FIELD_STATS_PARTIAL_BUFFER_NAME -> partialBufferParameters(renderer)
            FIELD_STATS_RESULT_BUFFER_NAME -> resultBufferParameters(renderer)
            else -> null
        }
    }

    private fun buildResetModule(renderer: Renderer): ComputeModulePointer {
        val shader = """
            #version 450
            layout(local_size_x = 64) in;

            ${statsConstants()}
            ${partialBufferDeclaration()}
            ${resultBufferDeclaration()}

            void main() {
                uint channel = gl_GlobalInvocationID.x;
                uint nextSlot = 1u - fieldStatsResult.readableSlot;
                if (channel == 0u) {
                    fieldStatsPartial.activeSlot = nextSlot;
                    fieldStatsPartial.channels = FIELD_STATS_CHANNELS;
                    fieldStatsPartial.maxPartials = FIELD_STATS_MAX_PARTIALS;
                    fieldStatsPartial.sketchSize = FIELD_STATS_SKETCH_SIZE;
                    fieldStatsResult.channels = FIELD_STATS_CHANNELS;
                    fieldStatsResult.sketchSize = FIELD_STATS_SKETCH_SIZE;
                }
                if (channel < FIELD_STATS_CHANNELS) {
                    uint index = nextSlot * FIELD_STATS_CHANNELS + channel;
                    fieldStatsPartial.partialCounts[index] = 0u;
                    fieldStatsPartial.droppedPartials[index] = 0u;
                }
            }
        """.trimIndent()

        val module = ComputeModule(
            Parameters(
                renderer = renderer,
                compute = shader,
                name = "FieldStats Reset",
                ssboResolver = resolver(renderer)
            )
        ).pointer()
        module += "reset" to ComputeMesh(renderer, module).apply { x = 1 }
        return module
    }

    private fun buildReduceModule(renderer: Renderer): ComputeModulePointer {
        val shader = """
            #version 450
            layout(local_size_x = $sketchSize) in;

            ${statsConstants()}
            ${partialBufferDeclaration()}
            ${resultBufferDeclaration()}

            shared uint sCalls[FIELD_STATS_SKETCH_SIZE];
            shared uint sFinite[FIELD_STATS_SKETCH_SIZE];
            shared uint sInvalid[FIELD_STATS_SKETCH_SIZE];
            shared uint sNonZero[FIELD_STATS_SKETCH_SIZE];
            shared float sSum[FIELD_STATS_SKETCH_SIZE];
            shared float sSumSq[FIELD_STATS_SKETCH_SIZE];
            shared float sMin[FIELD_STATS_SKETCH_SIZE];
            shared float sMax[FIELD_STATS_SKETCH_SIZE];
            shared float sCentroidValue[FIELD_STATS_SKETCH_SIZE];
            shared float sCentroidWeight[FIELD_STATS_SKETCH_SIZE];

            float fieldStatsReducedQuantile(float q) {
                float totalWeight = float(sFinite[0]);
                if (totalWeight <= 0.0) return 0.0;
                float target = clamp(q, 0.0, 1.0) * totalWeight;
                float cumulative = 0.0;
                float answer = sCentroidValue[0];
                for (uint i = 0u; i < FIELD_STATS_SKETCH_SIZE; i++) {
                    if (sCentroidWeight[i] <= 0.0) break;
                    answer = sCentroidValue[i];
                    cumulative += sCentroidWeight[i];
                    if (cumulative >= target) break;
                }
                return answer;
            }

            void main() {
                uint lane = gl_LocalInvocationID.x;
                uint channel = gl_WorkGroupID.x;
                if (channel >= FIELD_STATS_CHANNELS || lane >= FIELD_STATS_SKETCH_SIZE) return;

                uint slot = fieldStatsPartial.activeSlot;
                uint countIndex = slot * FIELD_STATS_CHANNELS + channel;
                uint partialCount = min(
                    fieldStatsPartial.partialCounts[countIndex],
                    FIELD_STATS_MAX_PARTIALS
                );

                uint calls = 0u;
                uint finiteCount = 0u;
                uint invalidCount = 0u;
                uint nonZeroCount = 0u;
                float sum = 0.0;
                float sumSq = 0.0;
                float minValue = uintBitsToFloat(0x7f800000u);
                float maxValue = uintBitsToFloat(0xff800000u);
                float centroidWeight = 0.0;
                float sketchValue = uintBitsToFloat(0x7f800000u);

                uint base = (slot * FIELD_STATS_CHANNELS + channel) * FIELD_STATS_MAX_PARTIALS;
                uint rangeStart = lane * partialCount / FIELD_STATS_SKETCH_SIZE;
                uint rangeEnd = (lane + 1u) * partialCount / FIELD_STATS_SKETCH_SIZE;
                uint representative = rangeStart + (rangeEnd - rangeStart) / 2u;
                for (uint i = rangeStart; i < rangeEnd; i++) {
                    FieldStatsPartial partial = fieldStatsPartial.partials[base + i];
                    calls += partial.counts.x;
                    finiteCount += partial.counts.y;
                    invalidCount += partial.counts.z;
                    nonZeroCount += partial.counts.w;
                    sum += partial.moments.x;
                    sumSq += partial.moments.y;
                    minValue = min(minValue, partial.moments.z);
                    maxValue = max(maxValue, partial.moments.w);
                    centroidWeight += partial.sketchPoint.y;
                    if (i == representative) {
                        sketchValue = partial.sketchPoint.x;
                    }
                }

                sCalls[lane] = calls;
                sFinite[lane] = finiteCount;
                sInvalid[lane] = invalidCount;
                sNonZero[lane] = nonZeroCount;
                sSum[lane] = sum;
                sSumSq[lane] = sumSq;
                sMin[lane] = minValue;
                sMax[lane] = maxValue;
                sCentroidValue[lane] = centroidWeight > 0.0
                    ? sketchValue
                    : uintBitsToFloat(0x7f800000u);
                sCentroidWeight[lane] = centroidWeight;
                barrier();

                for (uint stride = FIELD_STATS_SKETCH_SIZE / 2u; stride > 0u; stride >>= 1u) {
                    if (lane < stride) {
                        sCalls[lane] += sCalls[lane + stride];
                        sFinite[lane] += sFinite[lane + stride];
                        sInvalid[lane] += sInvalid[lane + stride];
                        sNonZero[lane] += sNonZero[lane + stride];
                        sSum[lane] += sSum[lane + stride];
                        sSumSq[lane] += sSumSq[lane + stride];
                        sMin[lane] = min(sMin[lane], sMin[lane + stride]);
                        sMax[lane] = max(sMax[lane], sMax[lane + stride]);
                    }
                    barrier();
                }

                for (uint size = 2u; size <= FIELD_STATS_SKETCH_SIZE; size <<= 1u) {
                    for (uint stride = size >> 1u; stride > 0u; stride >>= 1u) {
                        uint other = lane ^ stride;
                        if (other > lane) {
                            bool ascending = (lane & size) == 0u;
                            bool swapValues = ascending
                                ? sCentroidValue[lane] > sCentroidValue[other]
                                : sCentroidValue[lane] < sCentroidValue[other];
                            if (swapValues) {
                                float value = sCentroidValue[lane];
                                float weight = sCentroidWeight[lane];
                                sCentroidValue[lane] = sCentroidValue[other];
                                sCentroidWeight[lane] = sCentroidWeight[other];
                                sCentroidValue[other] = value;
                                sCentroidWeight[other] = weight;
                            }
                        }
                        barrier();
                    }
                }

                uint resultIndex = slot * FIELD_STATS_CHANNELS + channel;
                uint centroidIndex =
                    (slot * FIELD_STATS_CHANNELS + channel) * FIELD_STATS_SKETCH_SIZE + lane;
                fieldStatsResult.centroids[centroidIndex] =
                    vec2(sCentroidValue[lane], sCentroidWeight[lane]);

                if (lane == 0u) {
                    float n = float(sFinite[0]);
                    float mean = n > 0.0 ? sSum[0] / n : 0.0;
                    float variance = n > 0.0 ? max(sSumSq[0] / n - mean * mean, 0.0) : 0.0;
                    FieldScalarStats result;
                    result.calls = sCalls[0];
                    result.finiteCount = sFinite[0];
                    result.invalidCount = sInvalid[0];
                    result.nonZeroCount = sNonZero[0];
                    result.minValue = sMin[0];
                    result.maxValue = sMax[0];
                    result.mean = mean;
                    result.variance = variance;
                    result.sum = sSum[0];
                    result.sumSquares = sSumSq[0];
                    result.standardDeviation = sqrt(variance);
                    result.droppedPartials = float(fieldStatsPartial.droppedPartials[countIndex]);
                    fieldStatsResult.stats[resultIndex] = result;

                    if (sFinite[0] > 0u) {
                        for (uint stateSlot = 0u; stateSlot < FIELD_STATS_STATE_SLOTS; stateSlot++) {
                            FieldStatsState state = fieldStatsResult.states[stateSlot];
                            if (state.mode == FIELD_STATS_STATE_UNUSED || state.channel != channel) {
                                continue;
                            }

                            bool configurationChanged =
                                state.appliedMode != state.mode ||
                                state.appliedChannel != state.channel ||
                                state.appliedParameter != state.parameter ||
                                state.appliedHalfLife != state.halfLife;
                            float current = state.mode == FIELD_STATS_STATE_MEAN
                                ? mean
                                : fieldStatsReducedQuantile(state.parameter);
                            float halfLife = max(state.halfLife, 0.000001);
                            float alpha = 1.0 - exp2(-1.0 / halfLife);
                            state.value = state.initialized == 0u || configurationChanged
                                ? current
                                : mix(state.value, current, alpha);
                            state.initialized = 1u;
                            state.appliedMode = state.mode;
                            state.appliedChannel = state.channel;
                            state.appliedParameter = state.parameter;
                            state.appliedHalfLife = state.halfLife;
                            fieldStatsResult.states[stateSlot] = state;
                        }
                    }

                    if (channel == 0u) {
                        fieldStatsResult.readableSlot = slot;
                        atomicAdd(fieldStatsResult.generation, 1u);
                    }
                }
            }
        """.trimIndent()

        val module = ComputeModule(
            Parameters(
                renderer = renderer,
                compute = shader,
                name = "FieldStats Reduce",
                ssboResolver = resolver(renderer)
            )
        ).pointer()
        module += "reduce" to ComputeMesh(renderer, module).apply { x = channels }
        return module
    }

    internal fun includeSource(producer: Boolean): String {
        val producerExtensions = if (producer) """
            #extension GL_KHR_shader_subgroup_basic : require
            #extension GL_KHR_shader_subgroup_arithmetic : require
            #extension GL_KHR_shader_subgroup_ballot : require
        """.trimIndent() else ""

        val producerSource = if (producer) """
            ${partialBufferDeclaration()}

            void fieldStatsConfigureState(
                uint stateSlot,
                uint mode,
                uint channel,
                float parameter,
                float halfLife
            ) {
                if (stateSlot >= FIELD_STATS_STATE_SLOTS || channel >= FIELD_STATS_CHANNELS) return;
                if (any(notEqual(gl_GlobalInvocationID, uvec3(0u)))) return;

                fieldStatsResult.states[stateSlot].mode = mode;
                fieldStatsResult.states[stateSlot].channel = channel;
                fieldStatsResult.states[stateSlot].parameter = parameter;
                fieldStatsResult.states[stateSlot].halfLife = halfLife;
            }

            void fieldStatsRecord(uint channel, float value) {
            if (channel >= FIELD_STATS_CHANNELS) return;

            bool finiteValue = !isnan(value) && !isinf(value);
            uint calls = subgroupBallotBitCount(subgroupBallot(true));
            uint finiteCount = subgroupAdd(finiteValue ? 1u : 0u);
            uint invalidCount = calls - finiteCount;
            uint nonZeroCount = subgroupAdd(finiteValue && value != 0.0 ? 1u : 0u);
            float sum = subgroupAdd(finiteValue ? value : 0.0);
            float sumSq = subgroupAdd(finiteValue ? value * value : 0.0);
            float minValue = subgroupMin(finiteValue ? value : uintBitsToFloat(0x7f800000u));
            float maxValue = subgroupMax(finiteValue ? value : uintBitsToFloat(0xff800000u));

            if (subgroupElect()) {
                uint slot = fieldStatsPartial.activeSlot;
                uint countIndex = slot * FIELD_STATS_CHANNELS + channel;
                uint partialIndex = atomicAdd(fieldStatsPartial.partialCounts[countIndex], 1u);
                if (partialIndex >= FIELD_STATS_MAX_PARTIALS) {
                    atomicAdd(fieldStatsPartial.droppedPartials[countIndex], 1u);
                    return;
                }

                FieldStatsPartial partial;
                partial.counts = uvec4(calls, finiteCount, invalidCount, nonZeroCount);
                partial.moments = vec4(sum, sumSq, minValue, maxValue);
                partial.sketchPoint = vec4(
                    finiteCount > 0u ? sum / float(finiteCount) : 0.0,
                    float(finiteCount),
                    0.0,
                    0.0
                );
                uint base = (slot * FIELD_STATS_CHANNELS + channel) * FIELD_STATS_MAX_PARTIALS;
                fieldStatsPartial.partials[base + partialIndex] = partial;
            }
            }

            void fieldStatsRecord(int channel, float value) {
                fieldStatsRecord(uint(channel), value);
            }

            void fieldStatsRecordVec4(uint firstChannel, vec4 value) {
                fieldStatsRecord(firstChannel + 0u, value.x);
                fieldStatsRecord(firstChannel + 1u, value.y);
                fieldStatsRecord(firstChannel + 2u, value.z);
                fieldStatsRecord(firstChannel + 3u, value.w);
            }
        """.trimIndent() else ""

        return """
        $producerExtensions
        ${statsConstants()}
        ${resultBufferDeclaration()}
        $producerSource

        FieldScalarStats fieldStatsRead(uint channel) {
            uint slot = fieldStatsResult.readableSlot;
            return fieldStatsResult.stats[slot * FIELD_STATS_CHANNELS + channel];
        }

        float fieldStatsQuantile(uint channel, float q) {
            uint slot = fieldStatsResult.readableSlot;
            uint base = (slot * FIELD_STATS_CHANNELS + channel) * FIELD_STATS_SKETCH_SIZE;
            float totalWeight = float(fieldStatsRead(channel).finiteCount);
            if (totalWeight <= 0.0) return 0.0;
            float target = clamp(q, 0.0, 1.0) * totalWeight;
            float cumulative = 0.0;
            float answer = fieldStatsResult.centroids[base].x;
            for (uint i = 0u; i < FIELD_STATS_SKETCH_SIZE; i++) {
                vec2 point = fieldStatsResult.centroids[base + i];
                if (point.y <= 0.0) break;
                answer = point.x;
                cumulative += point.y;
                if (cumulative >= target) break;
            }
            return answer;
        }

        float fieldStatsCdf(uint channel, float value) {
            uint slot = fieldStatsResult.readableSlot;
            uint base = (slot * FIELD_STATS_CHANNELS + channel) * FIELD_STATS_SKETCH_SIZE;
            float totalWeight = float(fieldStatsRead(channel).finiteCount);
            if (totalWeight <= 0.0) return 0.0;
            float cumulative = 0.0;
            for (uint i = 0u; i < FIELD_STATS_SKETCH_SIZE; i++) {
                vec2 point = fieldStatsResult.centroids[base + i];
                if (point.y <= 0.0 || point.x > value) break;
                cumulative += point.y;
            }
            return clamp(cumulative / totalWeight, 0.0, 1.0);
        }

        float fieldStatsState(uint stateSlot) {
            if (stateSlot >= FIELD_STATS_STATE_SLOTS) return 0.0;
            return fieldStatsResult.states[stateSlot].initialized != 0u
                ? fieldStatsResult.states[stateSlot].value
                : 0.0;
        }

        float fieldStatsSmoothedMean(
            uint stateSlot,
            uint channel,
            float halfLife
        ) {
            ${if (producer) "fieldStatsConfigureState(stateSlot, FIELD_STATS_STATE_MEAN, channel, 0.0, halfLife);" else ""}
            return fieldStatsState(stateSlot);
        }

        float fieldStatsSmoothedQuantile(
            uint stateSlot,
            uint channel,
            float q,
            float halfLife
        ) {
            ${if (producer) "fieldStatsConfigureState(stateSlot, FIELD_STATS_STATE_QUANTILE, channel, clamp(q, 0.0, 1.0), halfLife);" else ""}
            return fieldStatsState(stateSlot);
        }
        """.trimIndent()
    }

    private fun statsConstants(): String = """
        const uint FIELD_STATS_CHANNELS = ${channels}u;
        const uint FIELD_STATS_SKETCH_SIZE = ${sketchSize}u;
        const uint FIELD_STATS_MAX_PARTIALS = ${maxPartials}u;
        const uint FIELD_STATS_STATE_SLOTS = ${stateSlots}u;
        const uint FIELD_STATS_STATE_UNUSED = 0u;
        const uint FIELD_STATS_STATE_MEAN = 1u;
        const uint FIELD_STATS_STATE_QUANTILE = 2u;
    """.trimIndent()

    private fun partialBufferDeclaration(): String = """
        struct FieldStatsPartial {
            uvec4 counts;
            vec4 moments;
            vec4 sketchPoint;
        };

        layout(set = $descriptorSet, binding = $partialBinding, std430) buffer $FIELD_STATS_PARTIAL_BUFFER_NAME {
            uint activeSlot;
            uint channels;
            uint maxPartials;
            uint sketchSize;
            uint partialCounts[${channels * 2}];
            uint droppedPartials[${channels * 2}];
            FieldStatsPartial partials[];
        } fieldStatsPartial;
    """.trimIndent()

    private fun resultBufferDeclaration(): String = """
        struct FieldScalarStats {
            uint calls;
            uint finiteCount;
            uint invalidCount;
            uint nonZeroCount;
            float minValue;
            float maxValue;
            float mean;
            float variance;
            float sum;
            float sumSquares;
            float standardDeviation;
            float droppedPartials;
        };

        struct FieldStatsState {
            uint mode;
            uint channel;
            float parameter;
            float halfLife;
            float value;
            uint initialized;
            uint appliedMode;
            uint appliedChannel;
            float appliedParameter;
            float appliedHalfLife;
            uint padding0;
            uint padding1;
        };

        layout(set = $descriptorSet, binding = $resultBinding, std430) buffer $FIELD_STATS_RESULT_BUFFER_NAME {
            uint readableSlot;
            uint generation;
            uint channels;
            uint sketchSize;
            FieldScalarStats stats[${channels * 2}];
            FieldStatsState states[$stateSlots];
            vec2 centroids[];
        } fieldStatsResult;
    """.trimIndent()
}
