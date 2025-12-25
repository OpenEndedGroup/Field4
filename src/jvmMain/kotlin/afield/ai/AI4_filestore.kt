@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/* =============================================================================
   Hash utilities
   ============================================================================= */

object Sha256 {
    fun hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hexUtf8(s: String): String = hex(s.toByteArray(StandardCharsets.UTF_8))
}

/* =============================================================================
   Persistence model
   ============================================================================= */

enum class DuplicatePolicy {
    /** If contentHash already exists for this keyHash, return existing mapping (do not add new record). */
    REUSE_EXISTING,

    /** If contentHash already exists for this keyHash, append another record (keeps multiple fileIds). */
    APPEND,

    /** If contentHash already exists for this keyHash, replace existing records with the new record. */
    OVERWRITE,

    /** If contentHash already exists for this keyHash, throw. */
    ERROR
}

data class CachePutResult(
    val contentHash: String,
    val keyHash: String,
    val record: FileRecord,
    val action: String // "added", "reused", "appended", "overwritten"
)

data class CacheLookupResult(
    val contentHash: String, val keyHash: String, val records: List<FileRecord>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FileRecord(
    val fileId: String,
    val fileName: String,
    val purpose: String,
    val byteCount: Long,
    val createdAtEpochMs: Long = Instant.now().toEpochMilli()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FileStoreCacheDb(
    val version: Int = 1,

    /**
     * Optional "pepper id" so you can rotate peppers if you want.
     * Keep it stable if you want stable key hashes.
     */
    val pepperId: String = "v1",

    /**
     * byKey[keyHash][contentHash] = list of file records (most recent last).
     */
    val byKey: MutableMap<String, MutableMap<String, MutableList<FileRecord>>> = mutableMapOf()
)

/* =============================================================================
   FileStore cache (thread-safe + atomic writes)
   ============================================================================= */

class OpenAiFileStoreCache(
    private val dbPath: Path,
    private val pepper: String = "afield-openai-keyhash-pepper:v1",
    private val mapper: ObjectMapper = defaultMapper()
) {
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var db: FileStoreCacheDb = loadOrCreate()

    fun keyHash(openAiApiKey: String): String = Sha256.hexUtf8("$pepper|$openAiApiKey")

    fun contentHash(contentBytes: ByteArray): String = Sha256.hex(contentBytes)

    fun lookup(openAiApiKey: String, contentBytes: ByteArray): CacheLookupResult {
        val kHash = keyHash(openAiApiKey)
        val cHash = contentHash(contentBytes)
        val records = lock.read {
            db.byKey[kHash]?.get(cHash)?.toList().orEmpty()
        }
        return CacheLookupResult(contentHash = cHash, keyHash = kHash, records = records)
    }

    fun fileIdsForContent(openAiApiKey: String, contentBytes: ByteArray): List<String> =
        lookup(openAiApiKey, contentBytes).records.map { it.fileId }

    fun newestFileIdForContent(openAiApiKey: String, contentBytes: ByteArray): String? =
        lookup(openAiApiKey, contentBytes).records.lastOrNull()?.fileId

    /**
     * Record a mapping (content -> fileId) without uploading anything.
     */
    fun put(
        openAiApiKey: String,
        contentBytes: ByteArray,
        fileId: String,
        fileName: String,
        purpose: String,
        duplicatePolicy: DuplicatePolicy = DuplicatePolicy.REUSE_EXISTING
    ): CachePutResult {
        val kHash = keyHash(openAiApiKey)
        val cHash = contentHash(contentBytes)
        val newRecord = FileRecord(
            fileId = fileId, fileName = fileName, purpose = purpose, byteCount = contentBytes.size.toLong()
        )

        val result = lock.write {
            val byContent = db.byKey.getOrPut(kHash) { mutableMapOf() }
            val existing = byContent[cHash]

            when {
                existing == null -> {
                    byContent[cHash] = mutableListOf(newRecord)
                    persistLocked()
                    CachePutResult(cHash, kHash, newRecord, action = "added")
                }

                else -> when (duplicatePolicy) {
                    DuplicatePolicy.REUSE_EXISTING -> {
                        // return the newest existing record
                        CachePutResult(cHash, kHash, existing.last(), action = "reused")
                    }

                    DuplicatePolicy.APPEND -> {
                        existing += newRecord
                        persistLocked()
                        CachePutResult(cHash, kHash, newRecord, action = "appended")
                    }

                    DuplicatePolicy.OVERWRITE -> {
                        byContent[cHash] = mutableListOf(newRecord)
                        persistLocked()
                        CachePutResult(cHash, kHash, newRecord, action = "overwritten")
                    }

                    DuplicatePolicy.ERROR -> {
                        throw IllegalStateException(
                            "Content already exists for this keyHash. keyHash=$kHash contentHash=$cHash " + "existingFileIds=${existing.map { it.fileId }}"
                        )
                    }
                }
            }
        }

        return result
    }

    /**
     * High-level helper:
     * - if content already uploaded for this key (per cache), return existing fileId
     * - otherwise uploads then caches the returned fileId
     */
    fun uploadIfNeeded(
        openAiApiKey: String,
        fileName: String,
        contentBytes: ByteArray,
        purpose: String,
        uploader: OpenAiFileUploader,
        duplicatePolicy: DuplicatePolicy = DuplicatePolicy.REUSE_EXISTING
    ): CachePutResult {
        // Fast path: see if we already have something
        if (duplicatePolicy == DuplicatePolicy.REUSE_EXISTING) {
            val existing = newestFileIdForContent(openAiApiKey, contentBytes)
            if (existing != null) {
                val kHash = keyHash(openAiApiKey)
                val cHash = contentHash(contentBytes)

                println(" checking cache ${fileName} ${kHash}; ${cHash} ")
                val rec = lock.read {
                    db.byKey[kHash]?.get(cHash)?.lastOrNull()
                } ?: FileRecord(existing, fileName, purpose, contentBytes.size.toLong())
                return CachePutResult(cHash, kHash, rec, action = "reused")
            }
        }

        println("AI4_filestore: uploading $fileName")
        // Upload then record
        val fileId = uploader.upload(
            openAiApiKey = openAiApiKey, fileName = fileName, contentBytes = contentBytes, purpose = purpose
        )

        val kHash = keyHash(openAiApiKey)
        val cHash = contentHash(contentBytes)
        val rec = FileRecord(fileId, fileName, purpose, contentBytes.size.toLong())

        return try {
            put(
                openAiApiKey = openAiApiKey,
                contentBytes = contentBytes,
                fileId = fileId,
                fileName = fileName,
                purpose = purpose,
                duplicatePolicy = when (duplicatePolicy) {
                    DuplicatePolicy.REUSE_EXISTING -> DuplicatePolicy.OVERWRITE // after upload, prefer to store it
                    else -> duplicatePolicy
                }
            )
        } catch (e: Exception) {
            lock.write {
                val byContent = db.byKey.getOrPut(kHash) { mutableMapOf() }
                byContent[cHash] = mutableListOf(rec)
            }
            println("AI4_filestore: cache persist failed for $fileName: ${e.message}")
            CachePutResult(cHash, kHash, rec, action = "added-unpersisted")
        }
    }

    fun removeByContent(openAiApiKey: String, contentBytes: ByteArray): Boolean {
        val kHash = keyHash(openAiApiKey)
        val cHash = contentHash(contentBytes)
        return lock.write {
            val removed = db.byKey[kHash]?.remove(cHash) != null
            if (removed) persistLocked()
            removed
        }
    }

    fun snapshot(): FileStoreCacheDb = lock.read { db.copy(byKey = deepCopy(db.byKey)) }

    fun flush() {
        lock.write { persistLocked() }
    }

    /* ----------------------------- persistence ----------------------------- */

    private fun loadOrCreate(): FileStoreCacheDb {
        if (!Files.exists(dbPath)) return FileStoreCacheDb()
        return try {
            Files.newBufferedReader(dbPath, StandardCharsets.UTF_8).use { r ->
                mapper.readValue(r, FileStoreCacheDb::class.java)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // If corrupt, keep a backup and start fresh.
            val backup = dbPath.resolveSibling(dbPath.fileName.toString() + ".corrupt")
            try {
                Files.copy(dbPath, backup, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // ignore
            }
            FileStoreCacheDb()
        }
    }

    private fun persistLocked() {
        // Atomic write: write temp then move/replace.
        Files.createDirectories(dbPath.parent)
        val tmp = dbPath.resolveSibling(dbPath.fileName.toString() + ".tmp")

        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { w ->
            mapper.writerWithDefaultPrettyPrinter().writeValue(w, db)
        }

        println(" -- persistLocked ${tmp} ${dbPath} ${db.byKey.size} entries")
        try {
            Files.move(
                tmp, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(tmp, dbPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

        private fun deepCopy(
            src: MutableMap<String, MutableMap<String, MutableList<FileRecord>>>
        ): MutableMap<String, MutableMap<String, MutableList<FileRecord>>> {
            val out = mutableMapOf<String, MutableMap<String, MutableList<FileRecord>>>()
            for ((k, byContent) in src) {
                val byContentCopy = mutableMapOf<String, MutableList<FileRecord>>()
                for ((cHash, records) in byContent) {
                    byContentCopy[cHash] = records.toMutableList()
                }
                out[k] = byContentCopy
            }
            return out
        }
    }
}

/* =============================================================================
   Upload interface + OkHttp implementation (POST /v1/files)
   ============================================================================= */

fun interface OpenAiFileUploader {
    /**
     * Upload bytes as a File and return the OpenAI file id (e.g. "file-...").
     */
    fun upload(
        openAiApiKey: String, fileName: String, contentBytes: ByteArray, purpose: String
    ): String
}

/**
 * Minimal multipart uploader for /v1/files.
 *
 * - purpose is typically "assistants" when you intend to attach to vector stores / assistants.
 * - You can swap this for an openai-java SDK uploader later without touching the cache.
 */
class OkHttpOpenAiFileUploader(
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.openai.com/v1",
    private val mapper: ObjectMapper = OpenAiFileStoreCache.defaultMapper()
) : OpenAiFileUploader {

    override fun upload(
        openAiApiKey: String, fileName: String, contentBytes: ByteArray, purpose: String
    ): String {
        val fileBody = contentBytes.toRequestBody("application/octet-stream".toMediaType())

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("purpose", purpose)
            .addFormDataPart("file", fileName, fileBody).build()

        val req =
            Request.Builder().url("$baseUrl/files").header("Authorization", "Bearer $openAiApiKey").post(multipart)
                .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body == null) {
                throw IOException("File upload failed HTTP ${resp.code}. body=${body ?: "<null>"}")
            }

            val json = mapper.readTree(body)
            val id = json["id"]?.asText()
            if (id.isNullOrBlank()) {
                throw IOException("File upload response missing 'id'. body=$body")
            }
            return id
        }
    }
}

data class VirtualUploadResult(
    val file: VirtualTextFile,
    val result: CachePutResult
)

fun uploadVirtualFiles(
    cache: OpenAiFileStoreCache,
    uploader: OpenAiFileUploader,
    openAiApiKey: String,
    purpose: String,
    files: List<VirtualTextFile>,
    duplicatePolicy: DuplicatePolicy = DuplicatePolicy.REUSE_EXISTING
): List<VirtualUploadResult> {
    return files.map { file ->
        val bytes = file.content.toByteArray(StandardCharsets.UTF_8)
        val result = cache.uploadIfNeeded(
            openAiApiKey = openAiApiKey,
            fileName = file.ref.fileName,
            contentBytes = bytes,
            purpose = purpose,
            uploader = uploader,
            duplicatePolicy = duplicatePolicy
        )
        VirtualUploadResult(file, result)
    }
}


/* =============================================================================
   Public model: canonical bundle
   ============================================================================= */

data class CanonicalDoc(
    val fileName: String, val content: String, val contentType: String = "text/markdown; charset=utf-8"
)

data class CanonicalBundle(
    val name: String, val version: String, val docs: List<CanonicalDoc>
)

/* =============================================================================
   Disk cache schema
   ============================================================================= */

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiLocalRegistry(
    val schemaVersion: Int = 1, val saltB64: String, val keys: MutableMap<String, KeyState> = mutableMapOf()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KeyState(
    val filesBySha256: MutableMap<String, UploadedFileRecord> = mutableMapOf(),
    val vectorStoresByBundle: MutableMap<String, VectorStoreRecord> = mutableMapOf()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UploadedFileRecord(
    val fileId: String, val fileName: String, val purpose: String, val bytes: Int, val createdAtEpochSec: Long
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VectorStoreRecord(
    val vectorStoreId: String, val createdAtEpochSec: Long
)

/* =============================================================================
   OpenAI REST helpers (Files + Vector Stores)
   ============================================================================= */

class OpenAiKnowledgeStore(
    private val cachePath: Path, private val apiBase: URI = URI.create("https://api.openai.com/v1")
) {
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val lock = ReentrantReadWriteLock()
    private var registry: OpenAiLocalRegistry = lock.write { loadOrInit() }

    /* ---------------------------- Hashing -------------------------------- */

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

    fun hashApiKey(apiKey: String): String {
        // Hash = SHA256(salt || apiKey)
        val salt = Base64.getDecoder().decode(registry.saltB64)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(apiKey.toByteArray(StandardCharsets.UTF_8))
        return md.digest().toHex()
    }

    /* ---------------------- Public: file id cache ------------------------ */

    /**
     * If content already uploaded for this key (by sha256), returns existing file_id.
     * Otherwise uploads to OpenAI Files API and records mapping.
     */
    fun ensureFileUploaded(
        apiKey: String,
        fileName: String,
        content: ByteArray,
        purpose: String = "user_data",
        ifAlreadyUploaded: AlreadyUploaded = AlreadyUploaded.ReturnExisting
    ): UploadedFileRecord {
        val keyHash = hashApiKey(apiKey)
        val contentHash = sha256Hex(content)

        lock.read {
            val existing = registry.keys[keyHash]?.filesBySha256?.get(contentHash)
            if (existing != null) {
                if (ifAlreadyUploaded== AlreadyUploaded.ReturnExisting) return existing
            }
        }

        val created = uploadFile(apiKey, fileName, content, purpose)
        val record = UploadedFileRecord(
            fileId = created.id,
            fileName = created.filename ?: fileName,
            purpose = created.purpose ?: purpose,
            bytes = created.bytes ?: content.size,
            createdAtEpochSec = created.createdAt ?: Instant.now().epochSecond
        )

        lock.write {
            val ks = registry.keys.getOrPut(keyHash) { KeyState() }
            ks.filesBySha256[contentHash] = record
            saveLocked()
        }
        return record
    }

    /** Look up a cached file_id for this (key, content) without uploading. */
    fun lookupFileIdForContent(apiKey: String, content: ByteArray): String? {
        val keyHash = hashApiKey(apiKey)
        val contentHash = sha256Hex(content)
        return lock.read { registry.keys[keyHash]?.filesBySha256?.get(contentHash)?.fileId }
    }

    enum class AlreadyUploaded { ReturnExisting, Reupload }

    /* ---------------------- Public: vector store cache -------------------- */

    /**
     * Ensures a vector store exists for (key, bundleName@bundleVersion) and that it contains
     * all docs in the bundle (uploaded as files).
     *
     * Returns vector_store_id you should pass to Responses via file_search.
     */
    fun ensureCanonicalBundleReady(
        apiKey: String, bundle: CanonicalBundle, purposeForDocs: String = "user_data"
    ): String {
        val keyHash = hashApiKey(apiKey)
        val bundleKey = "${bundle.name}@${bundle.version}"

        val existingVsId = lock.read {
            registry.keys[keyHash]?.vectorStoresByBundle?.get(bundleKey)?.vectorStoreId
        }

        val vsId = existingVsId ?: run {
            val created = createVectorStore(
                apiKey = apiKey, name = "afield:${bundle.name}", metadata = mapOf(
                    "bundle" to bundle.name, "version" to bundle.version, "kind" to "canonical"
                )
            )
            val rec = VectorStoreRecord(created.vectorStoreId, Instant.now().epochSecond)
            lock.write {
                val ks = registry.keys.getOrPut(keyHash) { KeyState() }
                ks.vectorStoresByBundle[bundleKey] = rec
                saveLocked()
            }
            created.vectorStoreId
        }

        // 1) Ensure docs are uploaded (deduped by content hash per key)
        val wantedFileIds = bundle.docs.map { doc ->
            ensureFileUploaded(
                apiKey = apiKey,
                fileName = doc.fileName,
                content = doc.content.toByteArray(StandardCharsets.UTF_8),
                purpose = purposeForDocs,
                ifAlreadyUploaded = AlreadyUploaded.ReturnExisting
            ).fileId
        }.toSet()

        // 2) Ensure vector store contains them
        val existingFileIds = listVectorStoreFileIds(apiKey, vsId).toSet()
        val missing = wantedFileIds - existingFileIds
        if (missing.isNotEmpty()) {
            val batch = createVectorStoreFileBatch(apiKey, vsId, missing.toList())
            // Poll a bit so it becomes queryable quickly; if you’d rather not block, delete this.
            waitForFileBatch(apiKey, vsId, batch.batchId, maxWaitMs = 60_000)
        }

        return vsId
    }

    /* =============================================================================
       Low-level REST calls
       ============================================================================= */

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class FileCreateResponse(
        val id: String,
        val filename: String? = null,
        val purpose: String? = null,
        val bytes: Int? = null,
        @JsonProperty("created_at") val createdAt: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class VectorStoreCreateResponse(val id: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class VectorStoreFilesListResponse(
        val data: List<VectorStoreFileItem> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VectorStoreFileItem(
        @JsonProperty("file_id") val fileId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VectorStoreFileBatchCreateResponse(val id: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VectorStoreFileBatchGetResponse(
        val id: String, val status: String? = null, @JsonProperty("last_error") val lastError: LastError? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LastError(val message: String? = null)

    private data class CreatedVectorStore(val vectorStoreId: String)
    private data class CreatedBatch(val batchId: String)

    private fun uploadFile(apiKey: String, fileName: String, content: ByteArray, purpose: String): FileCreateResponse {
        val boundary = "----afield-${UUID.randomUUID()}"
        val body = buildMultipart(
            boundary = boundary, fields = listOf("purpose" to purpose), files = listOf(
                MultipartFile(
                    fieldName = "file", fileName = fileName, contentType = "application/octet-stream", content = content
                )
            )
        )

        val req = HttpRequest.newBuilder(apiBase.resolve("/files")).header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("Files.create failed: HTTP ${resp.statusCode()} ${resp.body().decodeToString()}")
        }
        return mapper.readValue(resp.body())
    }

    private fun createVectorStore(apiKey: String, name: String, metadata: Map<String, String>): CreatedVectorStore {
        val payload = mapOf(
            "name" to name, "metadata" to metadata
        )
        val req = HttpRequest.newBuilder(apiBase.resolve("/vector_stores")).header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("VectorStores.create failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }
        val created = mapper.readValue<VectorStoreCreateResponse>(resp.body())
        return CreatedVectorStore(created.id)
    }

    private fun listVectorStoreFileIds(apiKey: String, vectorStoreId: String, limit: Int = 100): List<String> {
        val uri = apiBase.resolve("/vector_stores/$vectorStoreId/files?limit=$limit")
        val req = HttpRequest.newBuilder(uri).header("Authorization", "Bearer $apiKey").GET().build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("VectorStores.files.list failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }
        val parsed = mapper.readValue<VectorStoreFilesListResponse>(resp.body())
        return parsed.data.mapNotNull { it.fileId }
    }

    private fun createVectorStoreFileBatch(apiKey: String, vectorStoreId: String, fileIds: List<String>): CreatedBatch {
        val payload = mapOf("file_ids" to fileIds)
        val req = HttpRequest.newBuilder(apiBase.resolve("/vector_stores/$vectorStoreId/file_batches"))
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("VectorStores.fileBatches.create failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }
        val created = mapper.readValue<VectorStoreFileBatchCreateResponse>(resp.body())
        return CreatedBatch(created.id)
    }

    private fun waitForFileBatch(
        apiKey: String, vectorStoreId: String, batchId: String, maxWaitMs: Long
    ) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var sleepMs = 250L

        while (System.currentTimeMillis() < deadline) {
            val uri = apiBase.resolve("/vector_stores/$vectorStoreId/file_batches/$batchId")
            val req = HttpRequest.newBuilder(uri).header("Authorization", "Bearer $apiKey").GET().build()

            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                throw RuntimeException("VectorStores.fileBatches.get failed: HTTP ${resp.statusCode()} ${resp.body()}")
            }
            val got = mapper.readValue<VectorStoreFileBatchGetResponse>(resp.body())
            when (got.status?.lowercase()) {
                "completed" -> return
                "failed" -> throw RuntimeException("Vector store file batch failed: ${got.lastError?.message ?: "unknown"}")
            }

            Thread.sleep(sleepMs)
            sleepMs = (sleepMs * 1.6).toLong().coerceAtMost(2_000L)
        }

        // Not fatal; indexing may still complete later.
    }

    /* =============================================================================
       Multipart builder
       ============================================================================= */

    private data class MultipartFile(
        val fieldName: String, val fileName: String, val contentType: String, val content: ByteArray
    )

    private fun buildMultipart(
        boundary: String, fields: List<Pair<String, String>>, files: List<MultipartFile>
    ): ByteArray {
        val crlf = "\r\n"
        val out = ArrayList<ByteArray>(fields.size + files.size + 8)

        fun add(s: String) = out.add(s.toByteArray(StandardCharsets.UTF_8))
        fun addBytes(b: ByteArray) = out.add(b)

        for ((k, v) in fields) {
            add("--$boundary$crlf")
            add("Content-Disposition: form-data; name=\"$k\"$crlf$crlf")
            add(v)
            add(crlf)
        }

        for (f in files) {
            add("--$boundary$crlf")
            add("Content-Disposition: form-data; name=\"${f.fieldName}\"; filename=\"${f.fileName}\"$crlf")
            add("Content-Type: ${f.contentType}$crlf$crlf")
            addBytes(f.content)
            add(crlf)
        }

        add("--$boundary--$crlf")

        val total = out.sumOf { it.size }
        val joined = ByteArray(total)
        var p = 0
        for (chunk in out) {
            System.arraycopy(chunk, 0, joined, p, chunk.size)
            p += chunk.size
        }
        return joined
    }

    /* =============================================================================
       Disk I/O
       ============================================================================= */

    private fun loadOrInit(): OpenAiLocalRegistry {
        Files.createDirectories(cachePath.parent)
        if (!Files.exists(cachePath)) {
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val fresh = OpenAiLocalRegistry(
                schemaVersion = 1, saltB64 = Base64.getEncoder().encodeToString(salt), keys = mutableMapOf()
            )
            writeAtomic(cachePath, mapper.writeValueAsBytes(fresh))
            return fresh
        }

        return try {
            mapper.readValue(Files.readAllBytes(cachePath))
        } catch (e: Exception) {
            // Corrupt cache: keep a backup and start fresh
            val backup = cachePath.resolveSibling(cachePath.fileName.toString() + ".corrupt-" + UUID.randomUUID())
            Files.move(cachePath, backup, StandardCopyOption.REPLACE_EXISTING)
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val fresh = OpenAiLocalRegistry(1, Base64.getEncoder().encodeToString(salt), mutableMapOf())
            writeAtomic(cachePath, mapper.writeValueAsBytes(fresh))
            fresh
        }
    }

    private fun saveLocked() {
        writeAtomic(cachePath, mapper.writeValueAsBytes(registry))
    }

    private fun writeAtomic(path: Path, bytes: ByteArray) {
        println(" -- writing atomically to ${path} ${bytes.size} bytes")
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp-" + UUID.randomUUID())
        Files.write(tmp, bytes)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b) }
}
