package afield.graphics

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO

/** Bounded PNG sidecars keep screenshots out of the document's text property. */
internal object ThreeReviewEvidence {
    private const val MAX_IMAGE_BYTES = 2_000_000
    private const val MAX_DIRECTORY_BYTES = 64_000_000L
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun store(evidence: JsonObject, record: JsonObject, directory: Path): JsonObject {
        require(evidence["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unknown image evidence version" }
        require(evidence["artifactDigest"] == record["artifactDigest"] && evidence["view"] == record["view"]) { "Images and saved view disagree" }
        for ((key, size) in listOf("viewport" to 2, "cropBounds" to 4)) {
            val values = evidence[key] as? JsonArray ?: error("Missing image $key")
            require(values.size == size && values.all { it.jsonPrimitive.doubleOrNull?.isFinite() == true }) { "Invalid image $key" }
        }
        val decoded = listOf("context", "crop").associateWith { kind ->
            val image = evidence[kind]?.jsonObject ?: error("Missing $kind image")
            val encoded = image["dataUrl"]?.jsonPrimitive?.contentOrNull ?: error("Missing PNG image")
            require(encoded.startsWith("data:image/png;base64,") && encoded.length <= MAX_IMAGE_BYTES * 4 / 3 + 100) { "Image is too large or is not PNG" }
            val bytes = Base64.getDecoder().decode(encoded.substringAfter(','))
            require(bytes.size <= MAX_IMAGE_BYTES && bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() }) { "Invalid PNG image" }
            ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Unreadable PNG image")
                try {
                    reader.input = input
                    val width = reader.getWidth(0); val height = reader.getHeight(0)
                    val limit = if (kind == "context") 1600 else 1024
                    require(width in 1..limit && height in 1..limit &&
                        width == image["width"]?.jsonPrimitive?.intOrNull && height == image["height"]?.jsonPrimitive?.intOrNull) { "Invalid image dimensions" }
                    checkNotNull(reader.read(0)) { "Incomplete PNG image" }
                } finally { reader.dispose() }
            }
            bytes
        }
        val metadata = decoded.mapValues { (kind, bytes) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            JsonObject(evidence.getValue(kind).jsonObject.filterKeys { it in setOf("width", "height") } + mapOf(
                "sha256" to JsonPrimitive(hash), "mimeType" to JsonPrimitive("image/png"), "bytes" to JsonPrimitive(bytes.size),
                "url" to JsonPrimitive("/three-review-image?uid=" + java.net.URLEncoder.encode(record.getValue("viewId").jsonPrimitive.content, Charsets.UTF_8) + "&sha256=" + hash)))
        }
        Files.createDirectories(directory)
        val used = Files.list(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        val newBytes = decoded.filterKeys { !Files.exists(directory.resolve(metadata.getValue(it).getValue("sha256").jsonPrimitive.content + ".png")) }.values.sumOf { it.size.toLong() }
        require(used + newBytes <= MAX_DIRECTORY_BYTES) { "Review images are full; export and archive them before adding more" }
        for ((kind, bytes) in decoded) {
            val path = directory.resolve(metadata.getValue(kind).getValue("sha256").jsonPrimitive.content + ".png")
            if (!Files.exists(path)) {
                val temporary = Files.createTempFile(directory, "image-", ".tmp")
                try { Files.write(temporary, bytes); Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                finally { Files.deleteIfExists(temporary) }
            }
        }
        return JsonObject(evidence.filterKeys { it in setOf("schemaVersion", "artifactDigest", "viewport", "cropBounds") } + metadata)
    }

    fun read(hash: String, notes: List<JsonObject>, directory: Path): ByteArray? {
        if (!hashPattern.matches(hash)) return null
        if (notes.none { note -> (note["evidence"] as? JsonObject)?.let { evidence ->
            listOf("context", "crop").any { (evidence[it] as? JsonObject)?.get("sha256")?.jsonPrimitive?.contentOrNull == hash }
        } == true }) return null
        val file = directory.resolve("$hash.png")
        return if (Files.isRegularFile(file) && Files.size(file) <= MAX_IMAGE_BYTES) Files.readAllBytes(file) else null
    }
}
