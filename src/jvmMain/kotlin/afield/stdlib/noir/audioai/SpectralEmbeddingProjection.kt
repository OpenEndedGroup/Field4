@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import afield.stdlib.SmileMdsDistance
import afield.stdlib.smileMds
import afield.stdlib.smileMdsDistances
import afield.stdlib.smileTsne
import afield.stdlib.smileTsneDistances
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.msgpack.core.MessagePack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.round

/** The interchangeable short-time representations and metrics retained by one spectral job. */
enum class SpectralEmbeddingDistance(val label: String) {
    /** Earth mover / Wasserstein-1 distance along normalized perceptual mel-bin position. */
    MEL_WASSERSTEIN("mel Wasserstein-1"),

    /** Angular distance between absolute log-mel spectral envelopes. */
    LOG_MEL_COSINE("log-mel cosine"),

    /** Straight-line distance between absolute log-mel spectral envelopes. */
    LOG_MEL_EUCLIDEAN("log-mel Euclidean"),

    /** Angular distance between compact cepstral spectral-envelope coefficients. */
    MFCC_COSINE("MFCC cosine"),

    /** Straight-line distance between compact cepstral spectral-envelope coefficients. */
    MFCC_EUCLIDEAN("MFCC Euclidean"),
}

/** A bounded and optionally pooled range decoded from a spectral MessagePack artifact. */
data class SpectralEmbeddingFrames(
    val timesSeconds: DoubleArray,
    val melDistributions: Array<FloatArray>,
    val logMelDb: Array<FloatArray>,
    val mfcc: Array<FloatArray>,
    val melFrequenciesHz: FloatArray,
    val sampledFrameIndices: IntArray,
    val sourceFrameCount: Int,
    val pooledFrameCount: Int,
    val frameRateHz: Double,
    val poolMillis: Double,
    val hopMillis: Double,
    val backend: String,
    val libraryVersion: String,
)

/** A Field-owned spectral projection with exact frame times for synchronized audition. */
data class SpectralEmbeddingProjection(
    val timesSeconds: DoubleArray,
    val coordinates: Array<DoubleArray>,
    val sampledFrameIndices: IntArray,
    val sourceFrameCount: Int,
    val pooledFrameCount: Int,
    val poolMillis: Double,
    val hopMillis: Double,
    val backend: String,
    val libraryVersion: String,
    val distance: SpectralEmbeddingDistance,
    val method: String,
    val scores: DoubleArray = doubleArrayOf(),
    val proportion: DoubleArray = doubleArrayOf(),
    val perplexity: Double? = null,
    val seed: Long? = null,
    val cost: Double? = null,
)

/** Locate the dense spectral artifact without confusing it with the plot-sized NPZ preview. */
fun AnalysisResult.spectralEmbeddingMessagePack(): AssetRepresentation =
    artifacts.singleOrNull { it.role == "spectral_embedding_messagepack" }
        ?: error("analysis result $id has no spectral_embedding_messagepack artifact")

/**
 * Decode, temporally pool, and finally sample spectral frames before quadratic projection.
 *
 * Mel mass is averaged and renormalized; log-mel and MFCC vectors use ordinary centered means.
 * [poolMillis] and [hopMillis] are independent, so overlapping perceptual windows can still update
 * at display rate. Sampling to [maxFrames] happens after pooling and retains time order.
 */
fun decodeSpectralEmbeddingMessagePack(
    bytes: ByteArray,
    maxFrames: Int = 600,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = null,
    poolMillis: Double? = null,
    hopMillis: Double? = null,
): SpectralEmbeddingFrames {
    require(maxFrames >= 2) { "maxFrames must be at least 2" }
    require(startSeconds.isFinite() && startSeconds >= 0.0) { "startSeconds must be non-negative" }
    require(durationSeconds == null || durationSeconds.isFinite() && durationSeconds > 0.0) {
        "durationSeconds must be finite and positive"
    }
    require(poolMillis == null || poolMillis.isFinite() && poolMillis > 0.0) {
        "poolMillis must be finite and positive"
    }
    require(hopMillis == null || hopMillis.isFinite() && hopMillis > 0.0) {
        "hopMillis must be finite and positive"
    }
    var format: String? = null
    var backend: String? = null
    var libraryVersion: String? = null
    var frameRateHz: Double? = null
    var frameCount: Int? = null
    var melDimensions: Int? = null
    var mfccDimensions: Int? = null
    var timesDtype: String? = null
    var matrixDtype: String? = null
    var melFrequencyDtype: String? = null
    var timesBytes: ByteArray? = null
    var melBytes: ByteArray? = null
    var logMelBytes: ByteArray? = null
    var mfccBytes: ByteArray? = null
    var melFrequencyBytes: ByteArray? = null

    MessagePack.newDefaultUnpacker(bytes).use { unpacker ->
        repeat(unpacker.unpackMapHeader()) {
            when (unpacker.unpackString()) {
                "format" -> format = unpacker.unpackString()
                "backend" -> backend = unpacker.unpackString()
                "library_version" -> libraryVersion = unpacker.unpackString()
                "frame_rate_hz" -> frameRateHz = unpacker.unpackDouble()
                "frame_count" -> frameCount = unpacker.unpackInt()
                "mel_dimensions" -> melDimensions = unpacker.unpackInt()
                "mfcc_dimensions" -> mfccDimensions = unpacker.unpackInt()
                "times_dtype" -> timesDtype = unpacker.unpackString()
                "matrix_dtype" -> matrixDtype = unpacker.unpackString()
                "mel_frequencies_dtype" -> melFrequencyDtype = unpacker.unpackString()
                "times_seconds" -> timesBytes = unpacker.readPayload(unpacker.unpackBinaryHeader())
                "mel_distribution" -> melBytes = unpacker.readPayload(unpacker.unpackBinaryHeader())
                "log_mel_db" -> logMelBytes = unpacker.readPayload(unpacker.unpackBinaryHeader())
                "mfcc" -> mfccBytes = unpacker.readPayload(unpacker.unpackBinaryHeader())
                "mel_frequencies_hz" -> melFrequencyBytes =
                    unpacker.readPayload(unpacker.unpackBinaryHeader())
                else -> unpacker.skipValue()
            }
        }
    }
    require(format == "field.audio.spectral-embedding.v1") {
        "unsupported spectral MessagePack format: $format"
    }
    require(timesDtype == "float64-le") { "unsupported spectral time dtype: $timesDtype" }
    require(matrixDtype == "float16-le") { "unsupported spectral matrix dtype: $matrixDtype" }
    require(melFrequencyDtype == "float32-le") {
        "unsupported mel-frequency dtype: $melFrequencyDtype"
    }
    val frames = requireNotNull(frameCount) { "spectral artifact has no frame count" }
    val melDims = requireNotNull(melDimensions) { "spectral artifact has no mel dimensions" }
    val mfccDims = requireNotNull(mfccDimensions) { "spectral artifact has no MFCC dimensions" }
    require(frames > 0 && melDims > 1 && mfccDims > 0) { "spectral tensor dimensions must be positive" }
    val timePayload = requireNotNull(timesBytes) { "spectral artifact has no times" }
    val melPayload = requireNotNull(melBytes) { "spectral artifact has no mel distribution" }
    val logMelPayload = requireNotNull(logMelBytes) { "spectral artifact has no log-mel matrix" }
    val mfccPayload = requireNotNull(mfccBytes) { "spectral artifact has no MFCC matrix" }
    val frequencyPayload = requireNotNull(melFrequencyBytes) { "spectral artifact has no mel frequencies" }
    require(timePayload.size == frames * Double.SIZE_BYTES) { "spectral time payload size is invalid" }
    require(melPayload.size.toLong() == frames.toLong() * melDims * Short.SIZE_BYTES) {
        "spectral mel payload size is invalid"
    }
    require(logMelPayload.size == melPayload.size) { "spectral log-mel payload size is invalid" }
    require(mfccPayload.size.toLong() == frames.toLong() * mfccDims * Short.SIZE_BYTES) {
        "spectral MFCC payload size is invalid"
    }
    require(frequencyPayload.size == melDims * Float.SIZE_BYTES) {
        "spectral mel-frequency payload size is invalid"
    }

    val timeBuffer = ByteBuffer.wrap(timePayload).order(ByteOrder.LITTLE_ENDIAN)
    val rangeEnd = durationSeconds?.let { startSeconds + it }
    var firstFrame = 0
    while (firstFrame < frames && timeBuffer.getDouble(firstFrame * Double.SIZE_BYTES) < startSeconds) {
        firstFrame++
    }
    var lastFrameExclusive = firstFrame
    while (
        lastFrameExclusive < frames &&
        (rangeEnd == null || timeBuffer.getDouble(lastFrameExclusive * Double.SIZE_BYTES) < rangeEnd)
    ) lastFrameExclusive++
    require(firstFrame < lastFrameExclusive) {
        "spectral embedding has no frames in [$startSeconds, ${rangeEnd ?: "end"}) seconds"
    }
    val rate = requireNotNull(frameRateHz) { "spectral artifact has no frame rate" }
    require(rate.isFinite() && rate > 0.0) { "spectral frame rate must be finite and positive" }
    val nativeMillis = 1000.0 / rate
    val selectedSourceFrames = lastFrameExclusive - firstFrame
    fun framesFor(millis: Double?): Int = if (millis == null) 1 else
        round(millis / nativeMillis).coerceIn(1.0, selectedSourceFrames.toDouble()).toInt()
    val poolFrames = framesFor(poolMillis)
    val hopFrames = framesFor(hopMillis)
    val pooledFrames = (selectedSourceFrames - 1) / hopFrames + 1
    val selectedPooledRows = spectralUniformIndices(pooledFrames, maxFrames)
    val sourceIndices = IntArray(selectedPooledRows.size) { index ->
        firstFrame + selectedPooledRows[index] * hopFrames
    }
    val times = DoubleArray(sourceIndices.size) { timeBuffer.getDouble(sourceIndices[it] * Double.SIZE_BYTES) }
    val mel = poolHalfMatrix(
        melPayload, frames, melDims, firstFrame, lastFrameExclusive,
        poolFrames, hopFrames, selectedPooledRows, normalizeMass = true,
    )
    val logMel = poolHalfMatrix(
        logMelPayload, frames, melDims, firstFrame, lastFrameExclusive,
        poolFrames, hopFrames, selectedPooledRows,
    )
    val mfcc = poolHalfMatrix(
        mfccPayload, frames, mfccDims, firstFrame, lastFrameExclusive,
        poolFrames, hopFrames, selectedPooledRows,
    )
    val frequencyBuffer = ByteBuffer.wrap(frequencyPayload).order(ByteOrder.LITTLE_ENDIAN)
    val frequencies = FloatArray(melDims) { frequencyBuffer.getFloat(it * Float.SIZE_BYTES) }
    return SpectralEmbeddingFrames(
        times, mel, logMel, mfcc, frequencies, sourceIndices, frames, pooledFrames, rate,
        poolFrames * nativeMillis, hopFrames * nativeMillis,
        requireNotNull(backend) { "spectral artifact has no backend" },
        requireNotNull(libraryVersion) { "spectral artifact has no library version" },
    )
}

/** Normalized 1-D Wasserstein distance on a shared, uniformly-spaced mel-bin support. */
fun melWassersteinDistance(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size && a.size > 1) { "mel distributions must have equal size greater than one" }
    var aMass = 0.0
    var bMass = 0.0
    a.forEach { require(it.isFinite() && it >= 0f); aMass += it }
    b.forEach { require(it.isFinite() && it >= 0f); bMass += it }
    require(aMass > 0.0 && bMass > 0.0) { "mel distributions must have positive mass" }
    var aCdf = 0.0
    var bCdf = 0.0
    var transport = 0.0
    for (index in 0 until a.lastIndex) {
        aCdf += a[index] / aMass
        bCdf += b[index] / bMass
        transport += abs(aCdf - bCdf)
    }
    return transport / a.lastIndex
}

/** Project the chosen spectral metric with classical MDS. */
fun SpectralEmbeddingFrames.mds(
    dimensions: Int = 4,
    distance: SpectralEmbeddingDistance = SpectralEmbeddingDistance.MEL_WASSERSTEIN,
    positiveCorrection: Boolean = true,
): SpectralEmbeddingProjection {
    val vectors = vectorsFor(distance)
    return if (distance == SpectralEmbeddingDistance.MEL_WASSERSTEIN) {
        val model = smileMdsDistances(
            melDistanceMatrix(), dimensions, distance.label, positiveCorrection,
        )
        spectralProjection(model.coordinates, distance, "MDS", model.scores, model.proportion)
    } else {
        val model = smileMds(
            vectors,
            dimensions,
            if (distance.name.endsWith("COSINE")) SmileMdsDistance.COSINE else SmileMdsDistance.EUCLIDEAN,
            positiveCorrection,
        )
        spectralProjection(model.coordinates, distance, "MDS", model.scores, model.proportion)
    }
}

/** Project the chosen spectral metric with fixed-seed exact t-SNE. */
fun SpectralEmbeddingFrames.tsne(
    dimensions: Int = 4,
    distance: SpectralEmbeddingDistance = SpectralEmbeddingDistance.MEL_WASSERSTEIN,
    perplexity: Double = 30.0,
    learningRate: Double = 200.0,
    earlyExaggeration: Double = 12.0,
    maxIterations: Int = 750,
    seed: Long = 184_921L,
): SpectralEmbeddingProjection {
    return if (distance == SpectralEmbeddingDistance.MEL_WASSERSTEIN) {
        val model = smileTsneDistances(
            melDistanceMatrix(), dimensions, distance.label, perplexity, learningRate,
            earlyExaggeration, maxIterations, seed,
        )
        spectralProjection(
            model.coordinates, distance, "t-SNE", perplexity = model.perplexity,
            seed = model.seed, cost = model.cost,
        )
    } else {
        val model = smileTsne(
            vectorsFor(distance), dimensions,
            if (distance.name.endsWith("COSINE")) SmileMdsDistance.COSINE else SmileMdsDistance.EUCLIDEAN,
            perplexity, learningRate, earlyExaggeration, maxIterations, seed,
        )
        spectralProjection(
            model.coordinates, distance, "t-SNE", perplexity = model.perplexity,
            seed = model.seed, cost = model.cost,
        )
    }
}

private fun SpectralEmbeddingFrames.vectorsFor(distance: SpectralEmbeddingDistance): Array<DoubleArray> {
    val source = when (distance) {
        SpectralEmbeddingDistance.MEL_WASSERSTEIN -> melDistributions
        SpectralEmbeddingDistance.LOG_MEL_COSINE,
        SpectralEmbeddingDistance.LOG_MEL_EUCLIDEAN -> logMelDb
        SpectralEmbeddingDistance.MFCC_COSINE,
        SpectralEmbeddingDistance.MFCC_EUCLIDEAN -> mfcc
    }
    return Array(source.size) { row -> DoubleArray(source[row].size) { source[row][it].toDouble() } }
}

private fun SpectralEmbeddingFrames.melDistanceMatrix(): Array<DoubleArray> =
    Array(melDistributions.size) { DoubleArray(melDistributions.size) }.also { result ->
        for (a in melDistributions.indices) for (b in 0 until a) {
            val value = melWassersteinDistance(melDistributions[a], melDistributions[b])
            result[a][b] = value
            result[b][a] = value
        }
    }

private fun SpectralEmbeddingFrames.spectralProjection(
    coordinates: Array<DoubleArray>,
    distance: SpectralEmbeddingDistance,
    method: String,
    scores: DoubleArray = doubleArrayOf(),
    proportion: DoubleArray = doubleArrayOf(),
    perplexity: Double? = null,
    seed: Long? = null,
    cost: Double? = null,
) = SpectralEmbeddingProjection(
    timesSeconds.copyOf(), coordinates, sampledFrameIndices.copyOf(), sourceFrameCount,
    pooledFrameCount, poolMillis, hopMillis, backend, libraryVersion, distance, method,
    scores, proportion, perplexity, seed, cost,
)

/** Attach locally computed projection axes to the service's lightweight spectral curves. */
fun AnalysisPreview.withSpectralProjection(projection: SpectralEmbeddingProjection): AnalysisPreview {
    val dimensions = projection.coordinates.firstOrNull()?.size ?: 0
    require(dimensions >= 4) { "spectral visualization requires at least four projection dimensions" }
    val prefix = if (projection.method == "MDS") "mds_" else "tsne_"
    val axes = List(dimensions) { axis ->
        AnalysisSeries(
            name = "$prefix$axis",
            label = "Field Smile ${projection.method} $axis",
            timesSeconds = projection.timesSeconds.map { it as Double? },
            values = projection.coordinates.map { it[axis] as Double? },
            metadata = buildJsonObject {
                put("projection_owner", "field")
                put("projection_library", "Smile")
                put("projection_method", projection.method)
                put("distance", projection.distance.label)
                put("source_frame_count", projection.sourceFrameCount)
                put("pooled_frame_count", projection.pooledFrameCount)
                put("sampled_frame_count", projection.coordinates.size)
                put("pool_millis", projection.poolMillis)
                put("hop_millis", projection.hopMillis)
            },
        )
    }
    return copy(
        series = series.filterNot { it.name.startsWith("mds_") || it.name.startsWith("tsne_") } + axes,
        analysis = buildJsonObject {
            analysis.forEach { (key, value) -> put(key, value) }
            put("projection_owner", "field")
            put("projection_library", "Smile")
            put("projection_method", projection.method)
            put("projection_series_prefix", prefix)
            put("projection_distance", projection.distance.label)
            put("projection_sampled_frames", projection.coordinates.size)
            put("projection_pooled_frames", projection.pooledFrameCount)
            put("projection_pool_millis", projection.poolMillis)
            put("projection_hop_millis", projection.hopMillis)
            put("projection_start_seconds", projection.timesSeconds.first())
            put("projection_end_seconds", projection.timesSeconds.last())
            projection.perplexity?.let { put("projection_perplexity", it) }
            projection.seed?.let { put("projection_seed", it) }
            projection.cost?.let { put("projection_cost", it) }
        },
    )
}

fun FieldAudioClient.spectralEmbeddingFrames(
    result: AnalysisResult,
    maxFrames: Int = 600,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = null,
    poolMillis: Double? = null,
    hopMillis: Double? = null,
): CompletableFuture<SpectralEmbeddingFrames> =
    downloadBytes(result.spectralEmbeddingMessagePack()).thenApply { bytes ->
        decodeSpectralEmbeddingMessagePack(
            bytes, maxFrames, startSeconds, durationSeconds, poolMillis, hopMillis,
        )
    }

fun FieldAudioClient.spectralEmbeddingSmilePreview(
    result: AnalysisResult,
    maxFrames: Int = 600,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = null,
    dimensions: Int = 4,
    distance: SpectralEmbeddingDistance = SpectralEmbeddingDistance.MEL_WASSERSTEIN,
    positiveCorrection: Boolean = true,
    previewPoints: Int = 1800,
    poolMillis: Double? = null,
    hopMillis: Double? = null,
): CompletableFuture<AnalysisPreview> =
    analysisPreview(result.id, previewPoints).thenCombineAsync(
        spectralEmbeddingFrames(result, maxFrames, startSeconds, durationSeconds, poolMillis, hopMillis)
    ) { preview, frames -> preview.withSpectralProjection(frames.mds(dimensions, distance, positiveCorrection)) }

fun FieldAudioClient.spectralEmbeddingSmileTsnePreview(
    result: AnalysisResult,
    maxFrames: Int = 600,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = 10.0,
    dimensions: Int = 4,
    distance: SpectralEmbeddingDistance = SpectralEmbeddingDistance.MEL_WASSERSTEIN,
    perplexity: Double = 30.0,
    learningRate: Double = 200.0,
    earlyExaggeration: Double = 12.0,
    maxIterations: Int = 750,
    seed: Long = 184_921L,
    previewPoints: Int = 1800,
    poolMillis: Double? = null,
    hopMillis: Double? = null,
): CompletableFuture<AnalysisPreview> =
    analysisPreview(result.id, previewPoints).thenCombineAsync(
        spectralEmbeddingFrames(result, maxFrames, startSeconds, durationSeconds, poolMillis, hopMillis)
    ) { preview, frames ->
        preview.withSpectralProjection(
            frames.tsne(
                dimensions, distance, perplexity, learningRate, earlyExaggeration,
                maxIterations, seed,
            )
        )
    }

private fun poolHalfMatrix(
    payload: ByteArray,
    totalFrames: Int,
    dimensions: Int,
    firstFrame: Int,
    lastFrameExclusive: Int,
    poolFrames: Int,
    hopFrames: Int,
    selectedPooledRows: IntArray,
    normalizeMass: Boolean = false,
): Array<FloatArray> {
    require(totalFrames >= lastFrameExclusive)
    val source = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val sums = DoubleArray(dimensions)
    val output = Array(selectedPooledRows.size) { FloatArray(dimensions) }
    var activeStart = firstFrame
    var activeEnd = firstFrame
    var selectedIndex = 0
    fun addRow(row: Int, scale: Double) {
        val offset = row * dimensions * Short.SIZE_BYTES
        for (column in 0 until dimensions) {
            sums[column] += scale * spectralHalfToFloat(
                source.getShort(offset + column * Short.SIZE_BYTES)
            )
        }
    }
    val pooledFrameCount = (lastFrameExclusive - firstFrame - 1) / hopFrames + 1
    repeat(pooledFrameCount) { pooledRow ->
        val anchor = firstFrame + pooledRow * hopFrames
        val targetStart = maxOf(firstFrame, anchor - poolFrames / 2)
        val targetEnd = minOf(lastFrameExclusive, anchor + (poolFrames + 1) / 2)
        while (activeStart < targetStart) { addRow(activeStart++, -1.0) }
        while (activeEnd < targetEnd) { addRow(activeEnd++, 1.0) }
        if (selectedIndex < selectedPooledRows.size && pooledRow == selectedPooledRows[selectedIndex]) {
            val count = activeEnd - activeStart
            var mass = 0.0
            for (column in 0 until dimensions) {
                val value = sums[column] / count
                output[selectedIndex][column] = value.toFloat()
                mass += value
            }
            if (normalizeMass && mass > 0.0) {
                for (column in 0 until dimensions) {
                    output[selectedIndex][column] = (output[selectedIndex][column] / mass).toFloat()
                }
            }
            selectedIndex++
        }
    }
    check(selectedIndex == selectedPooledRows.size) { "failed to select pooled spectral frames" }
    return output
}

private fun spectralUniformIndices(frameCount: Int, maxFrames: Int): IntArray {
    if (frameCount <= maxFrames) return IntArray(frameCount) { it }
    if (maxFrames == 2) return intArrayOf(0, frameCount - 1)
    return IntArray(maxFrames) { index ->
        ((index.toLong() * (frameCount - 1)) / (maxFrames - 1)).toInt()
    }
}

private fun spectralHalfToFloat(value: Short): Float {
    val half = value.toInt() and 0xffff
    val sign = (half and 0x8000) shl 16
    val exponent = (half ushr 10) and 0x1f
    var mantissa = half and 0x03ff
    val bits = when (exponent) {
        0 -> if (mantissa == 0) sign else {
            var power = -14
            while ((mantissa and 0x0400) == 0) { mantissa = mantissa shl 1; power-- }
            mantissa = mantissa and 0x03ff
            sign or ((power + 127) shl 23) or (mantissa shl 13)
        }
        0x1f -> sign or 0x7f800000 or (mantissa shl 13)
        else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
    }
    return Float.fromBits(bits)
}
