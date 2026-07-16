@file:Suppress("unused")
@file:afield.CompletionCategory("Audio")

package afield.stdlib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

data class FramesSigLoadOptions(
    val minimumCodePointLimit: Int = 4_000_000,
    val codePointLimitPadding: Int = 1_000_000,
    val defaultSmallVectorWidth: Int = 16,
)

data class FramesSigMetadata(
    val raw: Map<String, Any?>,
    val version: Map<String, String>,
    val fileName: String?,
    val codec: String?,
    val sampleRate: Int?,
    val numberChannels: Int?,
    val bitRate: Int?,
    val audioLengthSeconds: Double?,
    val analysisLengthSeconds: Double?,
    val analysisStartTimeSeconds: Double,
    val replayGain: Double?,
)

sealed interface FramesSigProduct {
    val path: String
    val frameCount: Int

    fun sampleAt(timeSeconds: Double, metadata: FramesSigMetadata): FramesSigTimedSample
}

data class FramesSigFloatProduct(
    override val path: String,
    val values: FloatArray,
) : FramesSigProduct {
    override val frameCount: Int get() = values.size

    override fun sampleAt(timeSeconds: Double, metadata: FramesSigMetadata): FramesSigTimedSample {
        val index = frameIndexAtTime(timeSeconds, metadata, frameCount)
        return FramesSigTimedSample(
            path = path,
            index = index,
            timeSeconds = frameTimeAtIndex(index, metadata, frameCount),
            values = floatArrayOf(values[index]),
        )
    }
}

data class FramesSigFloatVectorProduct(
    override val path: String,
    val values: List<FloatArray>,
) : FramesSigProduct {
    override val frameCount: Int get() = values.size
    val vectorWidth: Int get() = values.firstOrNull()?.size ?: 0

    override fun sampleAt(timeSeconds: Double, metadata: FramesSigMetadata): FramesSigTimedSample {
        val index = frameIndexAtTime(timeSeconds, metadata, frameCount)
        return FramesSigTimedSample(
            path = path,
            index = index,
            timeSeconds = frameTimeAtIndex(index, metadata, frameCount),
            values = values[index].copyOf(),
        )
    }
}

data class FramesSigTimedSample(
    val path: String,
    val index: Int,
    val timeSeconds: Double,
    val values: FloatArray,
) {
    val value: Float? get() = values.singleOrNull()
}

data class FramesSigProductInfo(
    val path: String,
    val frameCount: Int,
    val vectorWidth: Int,
    val startTimeSeconds: Double,
    val endTimeSeconds: Double,
)

data class FramesSigDocument(
    val source: File,
    val metadata: FramesSigMetadata,
    val raw: Map<String, Any?>,
    val scalarProducts: Map<String, Double>,
    val floatProducts: Map<String, FramesSigFloatProduct>,
    val floatVectorProducts: Map<String, FramesSigFloatVectorProduct>,
    val loadOptions: FramesSigLoadOptions,
) {
    fun product(path: String): FramesSigProduct? =
        floatProducts[path] ?: floatVectorProducts[path]

    fun sampleAt(path: String, timeSeconds: Double): FramesSigTimedSample? =
        product(path)?.sampleAt(timeSeconds, metadata)

    fun floatValueAt(path: String, timeSeconds: Double): Float? =
        floatProducts[path]?.sampleAt(timeSeconds, metadata)?.value

    fun floatVectorAt(path: String, timeSeconds: Double): FloatArray? =
        floatVectorProducts[path]?.sampleAt(timeSeconds, metadata)?.values

    fun available(): List<FramesSigProductInfo> =
        floatProducts.values
            .sortedBy { it.path }
            .map { it.toInfo(metadata) }

    fun availableArrays(maxVectorWidth: Int = loadOptions.defaultSmallVectorWidth): List<FramesSigProductInfo> =
        floatVectorProducts.values
            .asSequence()
            .filter { it.vectorWidth in 1..maxVectorWidth }
            .sortedBy { it.path }
            .map { it.toInfo(metadata) }
            .toList()

    fun availableAll(maxVectorWidth: Int = loadOptions.defaultSmallVectorWidth): List<FramesSigProductInfo> =
        buildList {
            addAll(available())
            addAll(availableArrays(maxVectorWidth))
        }.sortedBy { it.path }
}

fun loadFramesSig(file: File, options: FramesSigLoadOptions = FramesSigLoadOptions()): FramesSigDocument {
    require(file.isFile) { "loadFramesSig: file does not exist: ${file.absolutePath}" }

    val yaml = Yaml(configureLoaderOptions(file, options))
    val raw = file.bufferedReader().use { reader -> yaml.load<Any?>(reader) } as? Map<*, *>
        ?: error("loadFramesSig: expected a YAML map at the root of ${file.absolutePath}")

    val root = raw.entries.associate { (key, value) -> key.toString() to value }
    val metadataMap = root.stringMap("metadata")
    val metadata = parseFramesSigMetadata(metadataMap)

    val scalarProducts = linkedMapOf<String, Double>()
    val floatProducts = linkedMapOf<String, FramesSigFloatProduct>()
    val floatVectorProducts = linkedMapOf<String, FramesSigFloatVectorProduct>()

    root.forEach { (key, value) ->
        if (key == "metadata") return@forEach
        collectProducts(
            path = key,
            value = value,
            scalarProducts = scalarProducts,
            floatProducts = floatProducts,
            floatVectorProducts = floatVectorProducts,
        )
    }

    return FramesSigDocument(
        source = file,
        metadata = metadata,
        raw = root,
        scalarProducts = scalarProducts,
        floatProducts = floatProducts,
        floatVectorProducts = floatVectorProducts,
        loadOptions = options,
    )
}

fun loadFramesSig(path: String, options: FramesSigLoadOptions = FramesSigLoadOptions()): FramesSigDocument =
    loadFramesSig(File(path), options)

private fun FramesSigProduct.toInfo(metadata: FramesSigMetadata): FramesSigProductInfo =
    FramesSigProductInfo(
        path = path,
        frameCount = frameCount,
        vectorWidth = when (this) {
            is FramesSigFloatProduct -> 1
            is FramesSigFloatVectorProduct -> vectorWidth
        },
        startTimeSeconds = metadata.analysisStartTimeSeconds,
        endTimeSeconds = frameTimeAtIndex((frameCount - 1).coerceAtLeast(0), metadata, frameCount),
    )

private fun configureLoaderOptions(file: File, options: FramesSigLoadOptions): LoaderOptions {
    val loaderOptions = LoaderOptions()
    val expandedLimit = (file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() + options.codePointLimitPadding)
        .coerceAtLeast(options.minimumCodePointLimit)

    runCatching {
        LoaderOptions::class.java.getMethod("setCodePointLimit", Int::class.javaPrimitiveType)
            .invoke(loaderOptions, expandedLimit)
    }

    return loaderOptions
}

private fun parseFramesSigMetadata(raw: Map<String, Any?>): FramesSigMetadata {
    val version = raw.stringMap("version")
    val audioProperties = raw.stringMap("audio_properties")
    val analysis = audioProperties.stringMap("analysis")
    val tags = raw.stringMap("tags")

    return FramesSigMetadata(
        raw = raw,
        version = version.entries.associate { (key, value) -> key to value.toString() },
        fileName = tags["file_name"]?.toString(),
        codec = audioProperties["codec"]?.toString(),
        sampleRate = audioProperties["sample_rate"].asIntOrNull(),
        numberChannels = audioProperties["number_channels"].asIntOrNull(),
        bitRate = audioProperties["bit_rate"].asIntOrNull(),
        audioLengthSeconds = audioProperties["length"].asDoubleOrNull(),
        analysisLengthSeconds = analysis["length"].asDoubleOrNull(),
        analysisStartTimeSeconds = analysis["start_time"].asDoubleOrNull() ?: 0.0,
        replayGain = audioProperties["replay_gain"].asDoubleOrNull(),
    )
}

private fun collectProducts(
    path: String,
    value: Any?,
    scalarProducts: MutableMap<String, Double>,
    floatProducts: MutableMap<String, FramesSigFloatProduct>,
    floatVectorProducts: MutableMap<String, FramesSigFloatVectorProduct>,
) {
    when (value) {
        is Map<*, *> -> {
            value.forEach { (childKey, childValue) ->
                collectProducts(
                    path = "$path.${childKey.toString()}",
                    value = childValue,
                    scalarProducts = scalarProducts,
                    floatProducts = floatProducts,
                    floatVectorProducts = floatVectorProducts,
                )
            }
        }

        is Number -> scalarProducts[path] = value.toDouble()

        is List<*> -> {
            val flat = value.asFloatArrayOrNull()
            if (flat != null) {
                floatProducts[path] = FramesSigFloatProduct(path, flat)
                return
            }

            val vectors = value.asFloatVectorListOrNull()
            if (vectors != null) {
                floatVectorProducts[path] = FramesSigFloatVectorProduct(path, vectors)
            }
        }
    }
}

private fun Map<String, Any?>.stringMap(key: String): Map<String, Any?> =
    (this[key] as? Map<*, *>)
        ?.entries
        ?.associate { (childKey, childValue) -> childKey.toString() to childValue }
        ?: emptyMap()

private fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> null
}

private fun Any?.asIntOrNull(): Int? = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull()
    else -> null
}

private fun List<*>.asFloatArrayOrNull(): FloatArray? {
    if (isEmpty()) return FloatArray(0)
    val out = FloatArray(size)
    for (i in indices) {
        val value = this[i].asDoubleOrNull() ?: return null
        out[i] = value.toFloat()
    }
    return out
}

private fun List<*>.asFloatVectorListOrNull(): List<FloatArray>? {
    if (isEmpty()) return emptyList()
    val out = ArrayList<FloatArray>(size)
    for (entry in this) {
        val vector = (entry as? List<*>)?.asFloatArrayOrNull() ?: return null
        out += vector
    }
    return out
}

private fun frameIndexAtTime(timeSeconds: Double, metadata: FramesSigMetadata, frameCount: Int): Int {
    if (frameCount <= 1) return 0

    val duration = metadata.analysisLengthSeconds ?: metadata.audioLengthSeconds ?: 0.0
    if (duration <= 0.0) return 0

    val normalized = ((timeSeconds - metadata.analysisStartTimeSeconds) / duration).coerceIn(0.0, 1.0)
    return (normalized * (frameCount - 1)).roundToInt().coerceIn(0, frameCount - 1)
}

private fun frameTimeAtIndex(index: Int, metadata: FramesSigMetadata, frameCount: Int): Double {
    if (frameCount <= 1) return metadata.analysisStartTimeSeconds

    val duration = metadata.analysisLengthSeconds ?: metadata.audioLengthSeconds ?: 0.0
    if (duration <= 0.0) return metadata.analysisStartTimeSeconds

    return metadata.analysisStartTimeSeconds + duration * (index.toDouble() / (frameCount - 1).toDouble())
}

data class Sound2VecLoadOptions(
    val requireManifest: Boolean = false,
    val requireRowAlignment: Boolean = true,
)

data class Sound2VecManifest(
    val raw: Map<String, String>,
    val modelId: String?,
    val device: String?,
    val sampleRate: Int?,
    val clipSeconds: Double?,
    val hopSeconds: Double?,
    val minSeconds: Double?,
    val numFiles: Int?,
    val numChunks: Int?,
    val embeddingDim: Int?,
    val l2Normalized: Boolean?,
)

data class Sound2VecClip(
    val rowIndex: Int,
    val chunkId: Int,
    val filePath: String,
    val relPath: String,
    val fileName: String,
    val chunkIndex: Int,
    val startSec: Double,
    val endSec: Double,
    val durationSec: Double,
    val sampleRate: Int,
    val numSamples: Int,
    val tsne2dX: Float?,
    val tsne2dY: Float?,
    val tsne3dX: Float?,
    val tsne3dY: Float?,
    val tsne3dZ: Float?,
    val embedding: FloatArray,
) {
    val middleSec: Double get() = (startSec + endSec) * 0.5

    fun contains(timeSeconds: Double): Boolean = timeSeconds in startSec..endSec

    fun tsne2dOrNull(): FloatArray? =
        if (tsne2dX != null && tsne2dY != null) floatArrayOf(tsne2dX, tsne2dY) else null

    fun tsne3dOrNull(): FloatArray? =
        if (tsne3dX != null && tsne3dY != null && tsne3dZ != null) floatArrayOf(tsne3dX, tsne3dY, tsne3dZ) else null

    fun tsne2dVecOrNull(): Vec2? =
        if (tsne2dX != null && tsne2dY != null) Vector2f(tsne2dX, tsne2dY) else null

    fun tsne3dVecOrNull(): Vec3? =
        if (tsne3dX != null && tsne3dY != null && tsne3dZ != null) Vector3f(tsne3dX, tsne3dY, tsne3dZ) else null

    fun audioSubclip(): Sound2VecAudioSubclip =
        Sound2VecAudioSubclip(
            chunkId = chunkId,
            chunkIndex = chunkIndex,
            filePath = filePath,
            relPath = relPath,
            fileName = fileName,
            startSec = startSec,
            endSec = endSec,
            durationSec = durationSec,
            sampleRate = sampleRate,
            numSamples = numSamples,
        )
}

data class Sound2VecAudioSubclip(
    val chunkId: Int,
    val chunkIndex: Int,
    val filePath: String,
    val relPath: String,
    val fileName: String,
    val startSec: Double,
    val endSec: Double,
    val durationSec: Double,
    val sampleRate: Int,
    val numSamples: Int,
)

data class Sound2VecDataset(
    val sourceDir: File,
    val manifest: Sound2VecManifest?,
    val clips: List<Sound2VecClip>,
) {
    private val clipsByNormalizedPath = clips.groupBy { normalizeSound2VecPath(it.filePath) }
    private val clipsByRelPath = clips.groupBy { it.relPath }
    private val clipsByFileName = clips.groupBy { it.fileName }
    private val clipsByChunkId = clips.associateBy { it.chunkId }

    val embeddingDim: Int = clips.firstOrNull()?.embedding?.size ?: manifest?.embeddingDim ?: 0

    fun clipByChunkId(chunkId: Int): Sound2VecClip? = clipsByChunkId[chunkId]

    fun clipsForAudio(filePath: String): List<Sound2VecClip> {
        val normalized = normalizeSound2VecPath(filePath)
        return clipsByNormalizedPath[normalized]
            ?: clipsByRelPath[filePath]
            ?: clipsByFileName[filePath]
            ?: emptyList()
    }

    fun clipAt(filePath: String, timeSeconds: Double): Sound2VecClip? {
        val candidates = clipsForAudio(filePath)
        if (candidates.isEmpty()) return null

        return candidates.firstOrNull { it.contains(timeSeconds) }
            ?: candidates.minByOrNull { (it.middleSec - timeSeconds).absoluteValue }
    }

    fun clipsOverlapping(filePath: String, startSec: Double, endSec: Double): List<Sound2VecClip> =
        clipsForAudio(filePath).filter { it.endSec >= startSec && it.startSec <= endSec }

    fun tsne2dPoints(): List<Vec2> =
        clips.mapNotNull { it.tsne2dVecOrNull() }

    fun tsne3dPoints(): List<Vec3> =
        clips.mapNotNull { it.tsne3dVecOrNull() }

    fun subclipAt(filePath: String, timeSeconds: Double): Sound2VecAudioSubclip? =
        clipAt(filePath, timeSeconds)?.audioSubclip()

    fun subclipsOverlapping(filePath: String, startSec: Double, endSec: Double): List<Sound2VecAudioSubclip> =
        clipsOverlapping(filePath, startSec, endSec).map { it.audioSubclip() }
}

fun loadSound2Vec(dir: File, options: Sound2VecLoadOptions = Sound2VecLoadOptions()): Sound2VecDataset {
    require(dir.isDirectory) { "loadSound2Vec: directory does not exist: ${dir.absolutePath}" }

    val metadataFile = File(dir, "metadata.csv")
    val embeddingsFile = File(dir, "embeddings.npy")
    val manifestFile = File(dir, "manifest.json")

    require(metadataFile.isFile) { "loadSound2Vec: missing metadata.csv in ${dir.absolutePath}" }
    require(embeddingsFile.isFile) { "loadSound2Vec: missing embeddings.npy in ${dir.absolutePath}" }

    val manifest = when {
        manifestFile.isFile -> parseSound2VecManifest(manifestFile)
        options.requireManifest -> error("loadSound2Vec: missing manifest.json in ${dir.absolutePath}")
        else -> null
    }

    val rows = readCsvRows(metadataFile)
    val embeddings = readNpyFloatMatrix(embeddingsFile)

    if (options.requireRowAlignment) {
        require(rows.size == embeddings.size) {
            "loadSound2Vec: metadata row count ${rows.size} does not match embeddings row count ${embeddings.size}"
        }
    }

    if (manifest?.numChunks != null && options.requireRowAlignment) {
        require(manifest.numChunks == rows.size) {
            "loadSound2Vec: manifest num_chunks ${manifest.numChunks} does not match metadata row count ${rows.size}"
        }
    }

    if (manifest?.embeddingDim != null && embeddings.isNotEmpty() && options.requireRowAlignment) {
        require(manifest.embeddingDim == embeddings.first().size) {
            "loadSound2Vec: manifest embedding_dim ${manifest.embeddingDim} does not match embedding width ${embeddings.first().size}"
        }
    }

    val clipCount = minOf(rows.size, embeddings.size)
    val clips = List(clipCount) { index ->
        val row = rows[index]
        val embedding = embeddings[index]
        Sound2VecClip(
            rowIndex = index,
            chunkId = row.int("chunk_id") ?: index,
            filePath = row.string("file_path") ?: error("loadSound2Vec: metadata row $index missing file_path"),
            relPath = row.string("rel_path") ?: "",
            fileName = row.string("file_name") ?: "",
            chunkIndex = row.int("chunk_index") ?: index,
            startSec = row.double("start_sec") ?: 0.0,
            endSec = row.double("end_sec") ?: 0.0,
            durationSec = row.double("duration_sec") ?: 0.0,
            sampleRate = row.int("sample_rate") ?: manifest?.sampleRate ?: 0,
            numSamples = row.int("num_samples") ?: 0,
            tsne2dX = row.float("tsne_2d_x") ?: row.float("x"),
            tsne2dY = row.float("tsne_2d_y") ?: row.float("y"),
            tsne3dX = row.float("tsne_3d_x") ?: row.float("x"),
            tsne3dY = row.float("tsne_3d_y") ?: row.float("y"),
            tsne3dZ = row.float("tsne_3d_z") ?: row.float("z"),
            embedding = embedding,
        )
    }

    return Sound2VecDataset(
        sourceDir = dir,
        manifest = manifest,
        clips = clips,
    )
}

fun loadSound2Vec(path: String, options: Sound2VecLoadOptions = Sound2VecLoadOptions()): Sound2VecDataset =
    loadSound2Vec(File(path), options)

private fun parseSound2VecManifest(file: File): Sound2VecManifest {
    val element = Json.parseToJsonElement(file.readText()).jsonObject
    val raw = element.mapValues { it.value.toString() }

    return Sound2VecManifest(
        raw = raw,
        modelId = element["model_id"]?.jsonPrimitive?.contentOrNullCompat(),
        device = element["device"]?.jsonPrimitive?.contentOrNullCompat(),
        sampleRate = element["sample_rate"]?.jsonPrimitive?.intOrNull,
        clipSeconds = element["clip_seconds"]?.jsonPrimitive?.doubleOrNullCompat(),
        hopSeconds = element["hop_seconds"]?.jsonPrimitive?.doubleOrNullCompat(),
        minSeconds = element["min_seconds"]?.jsonPrimitive?.doubleOrNullCompat(),
        numFiles = element["num_files"]?.jsonPrimitive?.intOrNull,
        numChunks = element["num_chunks"]?.jsonPrimitive?.intOrNull,
        embeddingDim = element["embedding_dim"]?.jsonPrimitive?.intOrNull,
        l2Normalized = element["l2_normalized"]?.jsonPrimitive?.booleanOrNull,
    )
}

private fun readCsvRows(file: File): List<Map<String, String>> {
    val lines = file.readLines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()

    val header = parseCsvLine(lines.first())
    return lines.drop(1).map { line ->
        val values = parseCsvLine(line)
        buildMap(header.size) {
            for (index in header.indices) {
                put(header[index], values.getOrElse(index) { "" })
            }
        }
    }
}

private fun parseCsvLine(line: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index += 2
                continue
            }

            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                out += current.toString()
                current.setLength(0)
            }

            else -> current.append(char)
        }
        index += 1
    }

    out += current.toString()
    return out
}

private fun readNpyFloatMatrix(file: File): List<FloatArray> =
    DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
        val magic = ByteArray(6)
        input.readFully(magic)
        require(magic.contentEquals(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))) {
            "loadSound2Vec: ${file.absolutePath} is not a valid .npy file"
        }

        val major = input.readUnsignedByte()
        val minor = input.readUnsignedByte()
        val headerLength = when (major) {
            1 -> readLittleEndianShort(input)
            2, 3 -> readLittleEndianInt(input)
            else -> error("loadSound2Vec: unsupported .npy version $major.$minor")
        }

        val headerBytes = ByteArray(headerLength)
        input.readFully(headerBytes)
        val header = headerBytes.toString(Charsets.US_ASCII)

        val descr = Regex("'descr'\\s*:\\s*'([^']+)'").find(header)?.groupValues?.get(1)
            ?: error("loadSound2Vec: could not read dtype from ${file.absolutePath}")
        val fortranOrder = Regex("'fortran_order'\\s*:\\s*(True|False)").find(header)?.groupValues?.get(1) == "True"
        val shapeText = Regex("'shape'\\s*:\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1)
            ?: error("loadSound2Vec: could not read shape from ${file.absolutePath}")

        require(!fortranOrder) { "loadSound2Vec: Fortran-ordered arrays are not supported in ${file.absolutePath}" }
        require(descr == "<f4" || descr == "|f4") { "loadSound2Vec: only float32 embeddings are supported, found $descr" }

        val dims = shapeText.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }

        require(dims.isNotEmpty() && dims.size <= 2) {
            "loadSound2Vec: expected a 1D or 2D array in ${file.absolutePath}, found shape (${dims.joinToString()})"
        }

        val rows = if (dims.size == 1) 1 else dims[0]
        val cols = dims.last()
        val payload = input.readBytes()
        val expectedBytes = rows * cols * Float.SIZE_BYTES
        require(payload.size >= expectedBytes) {
            "loadSound2Vec: expected $expectedBytes bytes of embedding data in ${file.absolutePath}, found ${payload.size}"
        }

        val buffer = ByteBuffer.wrap(payload, 0, expectedBytes).order(ByteOrder.LITTLE_ENDIAN)
        List(rows) {
            FloatArray(cols) { buffer.float }
        }
    }

private fun readLittleEndianShort(input: DataInputStream): Int {
    val bytes = ByteArray(2)
    input.readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
}

private fun readLittleEndianInt(input: DataInputStream): Int {
    val bytes = ByteArray(4)
    input.readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun Map<String, String>.string(key: String): String? = this[key]?.takeIf { it.isNotBlank() }

private fun Map<String, String>.int(key: String): Int? = this[key]?.toIntOrNull()

private fun Map<String, String>.float(key: String): Float? = this[key]?.toFloatOrNull()

private fun Map<String, String>.double(key: String): Double? = this[key]?.toDoubleOrNull()

private fun normalizeSound2VecPath(path: String): String = File(path).absoluteFile.normalize().path

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullCompat(): String? =
    toString()
        .trim()
        .removeSurrounding("\"")
        .takeIf { it != "null" }

private fun kotlinx.serialization.json.JsonPrimitive.doubleOrNullCompat(): Double? =
    contentOrNullCompat()?.toDoubleOrNull()
