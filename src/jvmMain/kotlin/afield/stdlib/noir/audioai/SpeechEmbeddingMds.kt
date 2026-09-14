@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import afield.stdlib.SmileMdsDistance
import afield.stdlib.smileMds
import afield.stdlib.smileTsne
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.msgpack.core.MessagePack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture

/**
 * Speech embedding: decode, pool, and project the learned speech frames stored in an analysis artifact.
 *
 * `speechEmbeddingAnalysis` submits the `analysis.embed_speech` job, and the worker keeps two
 * things: the full float16 frame tensor, and its own four-axis MDS preview over cosine distance.
 * This file is the second route to a picture. `decodeSpeechEmbeddingMessagePack` reads the tensor
 * into a [SpeechEmbeddingFrames] bounded to a time window, then `.mds()` or `.tsne()` fits the
 * projection in Field through Smile's `smileMds` and `smileTsne`. Changing the range, the pooling,
 * the distance, the dimension count, the perplexity or the seed re-fits locally and never re-runs
 * the speech model.
 *
 * The payload stores float64 times and float16 vectors as little-endian MessagePack binary fields,
 * and a private half-float expansion reads them, so nothing here needs JVM float16 support. The
 * decoder rejects any `format` other than `field.audio.speech-embedding.v1`, and any dtype pair
 * other than those two.
 *
 * Pooling and hop are independent. `poolMillis` is the width of a centred mean window and
 * `hopMillis` is the spacing between the vectors that come out of it. Both round to the frame rate
 * recorded in the payload, and omitting them preserves the model's own frames one for one. Pooling
 * runs as a rolling sum, so a window covering many source rows costs one pass over the range
 * instead of one pass per output row, and `maxFrames` sampling happens after pooling and keeps time
 * order.
 *
 *     client.speechEmbeddingSmileTsnePreview(result).thenAccept { preview ->
 *         display("speech space", speechEmbeddingPreviewVisualizationHtml(preview))
 *     }
 *
 * `withSpeechMds` and `withSpeechTsne` replace a preview's projection axes with the locally fitted
 * ones and stamp the owner, library and method into both the series metadata and the preview's
 * analysis object, which is how a Field-fitted view is told apart from the worker's cached one on
 * the page. Both require at least four dimensions, because
 * `speechEmbeddingPreviewVisualizationHtml` draws axes 0/1 and 2/3 as two phase portraits.
 *
 * Every projection result carries the frame times and source frame indices it was fitted from
 * alongside the backend, model id, model revision and WavLM layer, so a trajectory on screen can be
 * traced back to the rows and the checkpoint behind it. The coordinates themselves are
 * navigational: axis numbering is specific to one fit, and comparing coordinate 0 between two
 * backends or two independently fitted recordings has no meaning. Use the stored vectors for that.
 * See [Speech-reactive learned spaces](../audio_ai_speech_embeddings.md).
 */

/** A bounded, time-preserving frame range decoded from a speech embedding MessagePack artifact. */
data class SpeechEmbeddingFrames(
    val timesSeconds: DoubleArray,
    val vectors: Array<FloatArray>,
    val sampledFrameIndices: IntArray,
    val sourceFrameCount: Int,
    val pooledFrameCount: Int,
    val sourceDimensions: Int,
    val frameRateHz: Double,
    val poolMillis: Double,
    val hopMillis: Double,
    val backend: String,
    val modelId: String,
    val modelRevision: String,
    val wavlmLayer: Int?,
)

/** Smile MDS coordinates attached to the exact speech-frame times that were projected. */
data class SpeechEmbeddingMds(
    val timesSeconds: DoubleArray,
    val coordinates: Array<DoubleArray>,
    val sampledFrameIndices: IntArray,
    val sourceFrameCount: Int,
    val pooledFrameCount: Int,
    val poolMillis: Double,
    val hopMillis: Double,
    val backend: String,
    val modelId: String,
    val modelRevision: String,
    val wavlmLayer: Int?,
    val distance: SmileMdsDistance,
    val scores: DoubleArray,
    val proportion: DoubleArray,
)

/** Smile t-SNE coordinates attached to the exact speech-frame times that were projected. */
data class SpeechEmbeddingTsne(
    val timesSeconds: DoubleArray,
    val coordinates: Array<DoubleArray>,
    val sampledFrameIndices: IntArray,
    val sourceFrameCount: Int,
    val pooledFrameCount: Int,
    val poolMillis: Double,
    val hopMillis: Double,
    val backend: String,
    val modelId: String,
    val modelRevision: String,
    val wavlmLayer: Int?,
    val distance: SmileMdsDistance,
    val perplexity: Double,
    val seed: Long,
    val cost: Double,
)

/** Locate the binary frame-vector artifact emitted by the speech workers. */
fun AnalysisResult.speechEmbeddingMessagePack(): AssetRepresentation =
    artifacts.singleOrNull { it.role == "speech_embedding_messagepack" }
        ?: error(
            "analysis result $id has no speech_embedding_messagepack artifact; " +
                "regenerate it with speech-worker-v2 or newer"
        )

/**
 * Decode a MessagePack speech tensor, retaining at most [maxFrames] pooled rows from a time range.
 *
 * The payload stores float16 vectors and float64 times as little-endian MessagePack binary fields.
 * [poolMillis] selects centered mean pooling before projection; null preserves one native model frame.
 * [hopMillis] controls the spacing between pooled vectors and defaults to the native model hop even
 * when pooling is enabled, which gives a smooth overlapping trajectory. Increase it to reduce the
 * number of projected vectors. Pooling is calculated as a rolling sum before [maxFrames] sampling,
 * so long overlapping windows do not repeatedly decode the same source rows.
 */
fun decodeSpeechEmbeddingMessagePack(
    bytes: ByteArray,
    maxFrames: Int = 384,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = null,
    poolMillis: Double? = null,
    hopMillis: Double? = null,
): SpeechEmbeddingFrames {
    require(maxFrames >= 2) { "maxFrames must be at least 2" }
    require(startSeconds.isFinite() && startSeconds >= 0.0) {
        "startSeconds must be finite and non-negative"
    }
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
    var modelId: String? = null
    var modelRevision: String? = null
    var wavlmLayer: Int? = null
    var frameRateHz: Double? = null
    var shape: IntArray? = null
    var timesDtype: String? = null
    var embeddingDtype: String? = null
    var timesBytes: ByteArray? = null
    var embeddingBytes: ByteArray? = null

    MessagePack.newDefaultUnpacker(bytes).use { unpacker ->
        repeat(unpacker.unpackMapHeader()) {
            when (unpacker.unpackString()) {
                "format" -> format = unpacker.unpackString()
                "backend" -> backend = unpacker.unpackString()
                "model_id" -> modelId = unpacker.unpackString()
                "model_revision" -> modelRevision = unpacker.unpackString()
                "wavlm_layer" -> {
                    val value = unpacker.unpackValue()
                    wavlmLayer = if (value.isNilValue) null else value.asIntegerValue().toInt()
                }
                "frame_rate_hz" -> frameRateHz = unpacker.unpackDouble()
                "shape" -> {
                    val size = unpacker.unpackArrayHeader()
                    shape = IntArray(size) { unpacker.unpackInt() }
                }
                "times_dtype" -> timesDtype = unpacker.unpackString()
                "embedding_dtype" -> embeddingDtype = unpacker.unpackString()
                "times_seconds" -> timesBytes = unpacker.readPayload(unpacker.unpackBinaryHeader())
                "embedding" -> embeddingBytes = unpacker.readPayload(unpacker.unpackBinaryHeader())
                else -> unpacker.skipValue()
            }
        }
    }

    require(format == "field.audio.speech-embedding.v1") {
        "unsupported speech embedding MessagePack format: $format"
    }
    require(timesDtype == "float64-le") { "unsupported speech time dtype: $timesDtype" }
    require(embeddingDtype == "float16-le") { "unsupported speech embedding dtype: $embeddingDtype" }
    val dimensions = requireNotNull(shape) { "speech embedding MessagePack has no shape" }
    require(dimensions.size == 2 && dimensions[0] > 0 && dimensions[1] > 0) {
        "speech embedding shape must be [positive frames, positive dimensions]"
    }
    val frameCount = dimensions[0]
    val vectorDimensions = dimensions[1]
    val timePayload = requireNotNull(timesBytes) { "speech embedding MessagePack has no times" }
    val vectorPayload = requireNotNull(embeddingBytes) { "speech embedding MessagePack has no vectors" }
    require(timePayload.size == frameCount * Double.SIZE_BYTES) {
        "speech embedding time payload has ${timePayload.size} bytes; expected ${frameCount * Double.SIZE_BYTES}"
    }
    require(vectorPayload.size.toLong() == frameCount.toLong() * vectorDimensions * Short.SIZE_BYTES) {
        "speech embedding vector payload has ${vectorPayload.size} bytes; expected " +
            "${frameCount.toLong() * vectorDimensions * Short.SIZE_BYTES}"
    }

    val timeBuffer = ByteBuffer.wrap(timePayload).order(ByteOrder.LITTLE_ENDIAN)
    val endSeconds = durationSeconds?.let { startSeconds + it }
    var firstFrame = 0
    while (firstFrame < frameCount && timeBuffer.getDouble(firstFrame * Double.SIZE_BYTES) < startSeconds) {
        firstFrame++
    }
    var lastFrameExclusive = firstFrame
    while (
        lastFrameExclusive < frameCount &&
        (endSeconds == null || timeBuffer.getDouble(lastFrameExclusive * Double.SIZE_BYTES) < endSeconds)
    ) {
        lastFrameExclusive++
    }
    require(firstFrame < lastFrameExclusive) {
        "speech embedding has no frames in [$startSeconds, ${endSeconds ?: "end"}) seconds"
    }
    val resolvedFrameRate = requireNotNull(frameRateHz) {
        "speech embedding MessagePack has no frame rate"
    }
    require(resolvedFrameRate.isFinite() && resolvedFrameRate > 0.0) {
        "speech embedding frame rate must be finite and positive"
    }
    val nativeFrameMillis = 1000.0 / resolvedFrameRate
    val selectedSourceFrameCount = lastFrameExclusive - firstFrame
    fun requestedFrameCount(millis: Double?): Int {
        if (millis == null) return 1
        return kotlin.math.round(millis / nativeFrameMillis)
            .coerceIn(1.0, selectedSourceFrameCount.toDouble())
            .toInt()
    }
    val poolFrames = requestedFrameCount(poolMillis)
    val hopFrames = requestedFrameCount(hopMillis)
    val pooledFrameCount = (lastFrameExclusive - firstFrame - 1) / hopFrames + 1
    val selectedPoolRows = uniformFrameIndices(0, pooledFrameCount, maxFrames)
    val vectorBuffer = ByteBuffer.wrap(vectorPayload).order(ByteOrder.LITTLE_ENDIAN)
    val times = DoubleArray(selectedPoolRows.size)
    val vectors = Array(selectedPoolRows.size) { FloatArray(vectorDimensions) }
    val indices = IntArray(selectedPoolRows.size)
    val sums = DoubleArray(vectorDimensions)
    var activeStart = firstFrame
    var activeEnd = firstFrame
    var selectedIndex = 0

    fun addSourceRow(source: Int, scale: Double) {
        val rowOffset = source * vectorDimensions * Short.SIZE_BYTES
        for (column in 0 until vectorDimensions) {
            sums[column] += scale * halfToFloat(
                vectorBuffer.getShort(rowOffset + column * Short.SIZE_BYTES)
            ).toDouble()
        }
    }

    repeat(pooledFrameCount) { pooledRow ->
        val anchor = firstFrame + pooledRow * hopFrames
        val targetStart = maxOf(firstFrame, anchor - poolFrames / 2)
        val targetEnd = minOf(lastFrameExclusive, anchor + (poolFrames + 1) / 2)
        while (activeStart < targetStart) {
            addSourceRow(activeStart, -1.0)
            activeStart++
        }
        while (activeEnd < targetEnd) {
            addSourceRow(activeEnd, 1.0)
            activeEnd++
        }
        if (selectedIndex < selectedPoolRows.size && pooledRow == selectedPoolRows[selectedIndex]) {
            val count = activeEnd - activeStart
            indices[selectedIndex] = anchor
            times[selectedIndex] = timeBuffer.getDouble(anchor * Double.SIZE_BYTES)
            for (column in 0 until vectorDimensions) {
                vectors[selectedIndex][column] = (sums[column] / count).toFloat()
            }
            selectedIndex++
        }
    }
    check(selectedIndex == selectedPoolRows.size) { "failed to select every requested pooled speech frame" }
    return SpeechEmbeddingFrames(
        timesSeconds = times,
        vectors = vectors,
        sampledFrameIndices = indices,
        sourceFrameCount = frameCount,
        pooledFrameCount = pooledFrameCount,
        sourceDimensions = vectorDimensions,
        frameRateHz = resolvedFrameRate,
        poolMillis = poolFrames * nativeFrameMillis,
        hopMillis = hopFrames * nativeFrameMillis,
        backend = requireNotNull(backend) { "speech embedding MessagePack has no backend" },
        modelId = requireNotNull(modelId) { "speech embedding MessagePack has no model id" },
        modelRevision = requireNotNull(modelRevision) { "speech embedding MessagePack has no model revision" },
        wavlmLayer = wavlmLayer,
    )
}

/** Run Field's Smile MDS over this bounded speech-frame sample. */
fun SpeechEmbeddingFrames.mds(
    dimensions: Int = 4,
    distance: SmileMdsDistance = SmileMdsDistance.COSINE,
    positiveCorrection: Boolean = true,
): SpeechEmbeddingMds {
    val values = Array(vectors.size) { row ->
        DoubleArray(sourceDimensions) { column -> vectors[row][column].toDouble() }
    }
    val projection = smileMds(values, dimensions, distance, positiveCorrection)
    return SpeechEmbeddingMds(
        timesSeconds = timesSeconds.copyOf(),
        coordinates = projection.coordinates,
        sampledFrameIndices = sampledFrameIndices.copyOf(),
        sourceFrameCount = sourceFrameCount,
        pooledFrameCount = pooledFrameCount,
        poolMillis = poolMillis,
        hopMillis = hopMillis,
        backend = backend,
        modelId = modelId,
        modelRevision = modelRevision,
        wavlmLayer = wavlmLayer,
        distance = distance,
        scores = projection.scores,
        proportion = projection.proportion,
    )
}

/** Run a fixed-seed, local-neighbourhood t-SNE over this speech-frame range. */
fun SpeechEmbeddingFrames.tsne(
    dimensions: Int = 4,
    distance: SmileMdsDistance = SmileMdsDistance.COSINE,
    perplexity: Double = 30.0,
    learningRate: Double = 200.0,
    earlyExaggeration: Double = 12.0,
    maxIterations: Int = 750,
    seed: Long = 184_921L,
): SpeechEmbeddingTsne {
    val values = Array(vectors.size) { row ->
        DoubleArray(sourceDimensions) { column -> vectors[row][column].toDouble() }
    }
    val projection = smileTsne(
        values,
        dimensions,
        distance,
        perplexity,
        learningRate,
        earlyExaggeration,
        maxIterations,
        seed,
    )
    return SpeechEmbeddingTsne(
        timesSeconds = timesSeconds.copyOf(),
        coordinates = projection.coordinates,
        sampledFrameIndices = sampledFrameIndices.copyOf(),
        sourceFrameCount = sourceFrameCount,
        pooledFrameCount = pooledFrameCount,
        poolMillis = poolMillis,
        hopMillis = hopMillis,
        backend = backend,
        modelId = modelId,
        modelRevision = modelRevision,
        wavlmLayer = wavlmLayer,
        distance = distance,
        perplexity = projection.perplexity,
        seed = projection.seed,
        cost = projection.cost,
    )
}

/** Replace a worker preview's MDS axes with coordinates computed locally in Field by Smile. */
fun AnalysisPreview.withSpeechMds(projection: SpeechEmbeddingMds): AnalysisPreview {
    val dimensions = projection.coordinates.firstOrNull()?.size ?: 0
    require(dimensions >= 4) { "speech visualization requires at least four MDS dimensions" }
    require(projection.coordinates.size == projection.timesSeconds.size) {
        "speech MDS coordinates and times must have equal length"
    }
    val axes = List(dimensions) { axis ->
        AnalysisSeries(
            name = "mds_$axis",
            label = "Field Smile MDS $axis",
            timesSeconds = projection.timesSeconds.map { it as Double? },
            values = projection.coordinates.map { it[axis] as Double? },
            metadata = buildJsonObject {
                put("projection_owner", "field")
                put("projection_library", "Smile")
                put("projection_method", "MDS")
                put("projection_series_prefix", "mds_")
                put("distance", projection.distance.name.lowercase())
                put("source_frame_count", projection.sourceFrameCount)
                put("pooled_frame_count", projection.pooledFrameCount)
                put("sampled_frame_count", projection.coordinates.size)
                put("pool_millis", projection.poolMillis)
                put("hop_millis", projection.hopMillis)
                put("start_seconds", projection.timesSeconds.first())
                put("end_seconds", projection.timesSeconds.last())
            },
        )
    }
    return copy(
        series = series.filterNot { it.name.startsWith("mds_") } + axes,
        analysis = buildJsonObject {
            analysis.forEach { (key, value) -> put(key, value) }
            put("projection_owner", "field")
            put("projection_library", "Smile")
            put("projection_method", "MDS")
            put("projection_series_prefix", "mds_")
            put("projection_distance", projection.distance.name.lowercase())
            put("projection_sampled_frames", projection.coordinates.size)
            put("projection_pooled_frames", projection.pooledFrameCount)
            put("projection_pool_millis", projection.poolMillis)
            put("projection_hop_millis", projection.hopMillis)
            put("projection_start_seconds", projection.timesSeconds.first())
            put("projection_end_seconds", projection.timesSeconds.last())
        },
    )
}

/** Replace a worker preview's MDS axes with a Field-owned Smile t-SNE projection. */
fun AnalysisPreview.withSpeechTsne(projection: SpeechEmbeddingTsne): AnalysisPreview {
    val dimensions = projection.coordinates.firstOrNull()?.size ?: 0
    require(dimensions >= 4) { "speech visualization requires at least four t-SNE dimensions" }
    require(projection.coordinates.size == projection.timesSeconds.size) {
        "speech t-SNE coordinates and times must have equal length"
    }
    val axes = List(dimensions) { axis ->
        AnalysisSeries(
            name = "tsne_$axis",
            label = "Field Smile t-SNE $axis",
            timesSeconds = projection.timesSeconds.map { it as Double? },
            values = projection.coordinates.map { it[axis] as Double? },
            metadata = buildJsonObject {
                put("projection_owner", "field")
                put("projection_library", "Smile")
                put("projection_method", "t-SNE")
                put("distance", projection.distance.name.lowercase())
                put("perplexity", projection.perplexity)
                put("seed", projection.seed)
                put("cost", projection.cost)
                put("source_frame_count", projection.sourceFrameCount)
                put("pooled_frame_count", projection.pooledFrameCount)
                put("sampled_frame_count", projection.coordinates.size)
                put("pool_millis", projection.poolMillis)
                put("hop_millis", projection.hopMillis)
                put("start_seconds", projection.timesSeconds.first())
                put("end_seconds", projection.timesSeconds.last())
            },
        )
    }
    return copy(
        series = series.filterNot { it.name.startsWith("mds_") || it.name.startsWith("tsne_") } + axes,
        analysis = buildJsonObject {
            analysis.forEach { (key, value) -> put(key, value) }
            put("projection_owner", "field")
            put("projection_library", "Smile")
            put("projection_method", "t-SNE")
            put("projection_series_prefix", "tsne_")
            put("projection_distance", projection.distance.name.lowercase())
            put("projection_perplexity", projection.perplexity)
            put("projection_seed", projection.seed)
            put("projection_cost", projection.cost)
            put("projection_sampled_frames", projection.coordinates.size)
            put("projection_pooled_frames", projection.pooledFrameCount)
            put("projection_pool_millis", projection.poolMillis)
            put("projection_hop_millis", projection.hopMillis)
            put("projection_start_seconds", projection.timesSeconds.first())
            put("projection_end_seconds", projection.timesSeconds.last())
        },
    )
}

/** Download and decode the binary embedding artifact without blocking Field's runloop. */
fun FieldAudioClient.speechEmbeddingFrames(
    result: AnalysisResult,
    maxFrames: Int = 384,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = null,
    poolMillis: Double? = null,
    hopMillis: Double? = null,
): CompletableFuture<SpeechEmbeddingFrames> =
    downloadBytes(result.speechEmbeddingMessagePack()).thenApply { bytes ->
        decodeSpeechEmbeddingMessagePack(
            bytes, maxFrames, startSeconds, durationSeconds, poolMillis, hopMillis
        )
    }

/**
 * Fetch the lightweight analysis curves, project the binary frame vectors with Smile in Field,
 * and return one preview ready for [speechEmbeddingPreviewVisualizationHtml].
 */
fun FieldAudioClient.speechEmbeddingSmilePreview(
    result: AnalysisResult,
    maxFrames: Int = 384,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = null,
    dimensions: Int = 4,
    distance: SmileMdsDistance = SmileMdsDistance.COSINE,
    positiveCorrection: Boolean = true,
    previewPoints: Int = 1800,
    poolMillis: Double? = null,
    hopMillis: Double? = null,
): CompletableFuture<AnalysisPreview> =
    analysisPreview(result.id, previewPoints).thenCombineAsync(
        speechEmbeddingFrames(
            result, maxFrames, startSeconds, durationSeconds, poolMillis, hopMillis
        )
    ) { preview, frames ->
        preview.withSpeechMds(frames.mds(dimensions, distance, positiveCorrection))
    }

/** Fetch lightweight analysis curves and attach a fixed-seed Smile t-SNE computed in Field. */
fun FieldAudioClient.speechEmbeddingSmileTsnePreview(
    result: AnalysisResult,
    maxFrames: Int = 600,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = 10.0,
    dimensions: Int = 4,
    distance: SmileMdsDistance = SmileMdsDistance.COSINE,
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
        speechEmbeddingFrames(
            result, maxFrames, startSeconds, durationSeconds, poolMillis, hopMillis
        )
    ) { preview, frames ->
        preview.withSpeechTsne(
            frames.tsne(
                dimensions,
                distance,
                perplexity,
                learningRate,
                earlyExaggeration,
                maxIterations,
                seed,
            )
        )
    }

private fun uniformFrameIndices(
    firstFrame: Int,
    lastFrameExclusive: Int,
    maxFrames: Int,
): IntArray {
    val frameCount = lastFrameExclusive - firstFrame
    if (frameCount <= maxFrames) return IntArray(frameCount) { firstFrame + it }
    if (maxFrames == 2) return intArrayOf(firstFrame, lastFrameExclusive - 1)
    return IntArray(maxFrames) { index ->
        firstFrame + ((index.toLong() * (frameCount - 1)) / (maxFrames - 1)).toInt()
    }
}

private fun halfToFloat(value: Short): Float {
    val half = value.toInt() and 0xffff
    val sign = (half and 0x8000) shl 16
    val exponent = (half ushr 10) and 0x1f
    var mantissa = half and 0x03ff
    val bits = when (exponent) {
        0 -> {
            if (mantissa == 0) sign
            else {
                var power = -14
                while ((mantissa and 0x0400) == 0) {
                    mantissa = mantissa shl 1
                    power--
                }
                mantissa = mantissa and 0x03ff
                sign or ((power + 127) shl 23) or (mantissa shl 13)
            }
        }
        0x1f -> sign or 0x7f800000 or (mantissa shl 13)
        else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
    }
    return Float.fromBits(bits)
}
