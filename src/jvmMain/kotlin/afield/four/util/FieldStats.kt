package afield.four.util

import afield.Box
import afield.four.Renderer
import afield.four.buffers.BufferParameters
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.buffer
import afield.four.device.CommandBuffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.ComputeModulePointer
import afield.four.modules.Parameters
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.pointer
import afield.logger.lprintln
import org.joml.primitives.Rectanglef
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.min

const val FIELD_STATS_INCLUDE_NAME = "fieldStats"
const val FIELD_STATS_PARTIAL_BUFFER_NAME = "FieldStatsPartialBuffer"
const val FIELD_STATS_RESULT_BUFFER_NAME = "FieldStatsResultBuffer"
const val FIELD_STATS_DEFAULT_PARTIAL_BINDING = 61
const val FIELD_STATS_DEFAULT_RESULT_BINDING = 62

private val fieldStatsReadbackExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "field-stats-readback").apply { isDaemon = true }
}

data class ShaderFieldStatsBinding(
    val stats: ShaderFieldStats,
    val producer: Boolean
)

data class FieldScalarStatsSnapshot(
    val generation: Long,
    val calls: Long,
    val finiteCount: Long,
    val invalidCount: Long,
    val nonZeroCount: Long,
    val min: Double,
    val max: Double,
    val mean: Double,
    val variance: Double,
    val sum: Double,
    val sumSquares: Double,
    val standardDeviation: Double,
    val droppedPartials: Long,
    val centroids: List<Pair<Double, Double>>
) {
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

    fun cdf(value: Double): Double {
        if (finiteCount == 0L) return Double.NaN
        val weight = centroids.takeWhile { it.first <= value }.sumOf { it.second }
        return (weight / finiteCount.toDouble()).coerceIn(0.0, 1.0)
    }
}

class ShaderFieldStatsScalar internal constructor(
    private val stats: ShaderFieldStats,
    val index: Int
) {
    fun read(): FieldScalarStatsSnapshot = stats.read(index)
    fun latest(): FieldScalarStatsSnapshot? = stats.latest(index)
}

class ShaderFieldStats(
    val box: Box,
    val channels: Int = 1,
    val sketchSize: Int = 256,
    val maxPartials: Int = 65_536,
    val descriptorSet: Int = 0,
    val partialBinding: Int = FIELD_STATS_DEFAULT_PARTIAL_BINDING,
    val resultBinding: Int = FIELD_STATS_DEFAULT_RESULT_BINDING
) {
    init {
        require(channels in 1..64) { "channels must be in 1..64" }
        require(sketchSize in 16..256 && sketchSize.countOneBits() == 1) {
            "sketchSize must be a power of two in 16..256"
        }
        require(maxPartials >= sketchSize) { "maxPartials must be >= sketchSize" }
    }

    private val partialHeaderBytes = 16L + channels.toLong() * 16L
    private val partialRecordBytes = 48L
    private val resultHeaderBytes = 16L
    private val resultRecordBytes = 48L
    private val centroidBytes = 8L

    private val partialBufferBytes =
        partialHeaderBytes + 2L * channels * maxPartials * partialRecordBytes
    private val resultBufferBytes =
        resultHeaderBytes +
            2L * channels * resultRecordBytes +
            2L * channels * sketchSize * centroidBytes

    private var rendererRef: Renderer? = null
    private var partialParameters: BufferParameters? = null
    private var resultParameters: BufferParameters? = null
    private var resetModule: ComputeModulePointer? = null
    private var reduceModule: ComputeModulePointer? = null
    private var initialized = false
    private val latestSnapshots = AtomicReferenceArray<FieldScalarStatsSnapshot?>(channels)

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
        reduceModule!!.current.render(command, Rectanglef())
        computeMemoryBarrier(command, resultBuffer())
        if (cpuSnapshotsEnabled) {
            renderer.afterCurrentComputeFrameSubmitted { fence ->
                fieldStatsReadbackExecutor.submit {
                    runCatching {
                        fence.fenceWait()
                        readAll().forEachIndexed(latestSnapshots::set)
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

    fun scalar(index: Int): ShaderFieldStatsScalar {
        require(index in 0 until channels) { "index must be in 0 until $channels" }
        return ShaderFieldStatsScalar(this, index)
    }

    fun latest(channel: Int = 0): FieldScalarStatsSnapshot? {
        require(channel in 0 until channels) { "channel must be in 0 until $channels" }
        return latestSnapshots.get(channel)
    }

    fun read(channel: Int = 0): FieldScalarStatsSnapshot {
        require(channel in 0 until channels) { "channel must be in 0 until $channels" }
        return readAll()[channel]
    }

    private fun readAll(): List<FieldScalarStatsSnapshot> {
        val header = IntArray(4)
        val snapshots = MutableList<FieldScalarStatsSnapshot?>(channels) { null }

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
        }

        return snapshots.map { requireNotNull(it) }
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
            hosted()
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
        val producerSource = if (producer) """
            #extension GL_KHR_shader_subgroup_basic : require
            #extension GL_KHR_shader_subgroup_arithmetic : require
            #extension GL_KHR_shader_subgroup_ballot : require

            ${partialBufferDeclaration()}

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
        ${statsConstants()}
        $producerSource
        ${resultBufferDeclaration()}

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
        """.trimIndent()
    }

    private fun statsConstants(): String = """
        const uint FIELD_STATS_CHANNELS = ${channels}u;
        const uint FIELD_STATS_SKETCH_SIZE = ${sketchSize}u;
        const uint FIELD_STATS_MAX_PARTIALS = ${maxPartials}u;
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

        layout(set = $descriptorSet, binding = $resultBinding, std430) buffer $FIELD_STATS_RESULT_BUFFER_NAME {
            uint readableSlot;
            uint generation;
            uint channels;
            uint sketchSize;
            FieldScalarStats stats[${channels * 2}];
            vec2 centroids[];
        } fieldStatsResult;
    """.trimIndent()
}
