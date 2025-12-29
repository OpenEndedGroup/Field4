package afield.ai

import afield.RunsAt
import afield.early
import afield.logger.lprintln
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.io.path.isRegularFile
import kotlin.jvm.java

data class KotlinSourceFileRecord(
    val path: String,
    val contentHash: String,
    val fileId: String?,
    val bytes: Long,
    val modifiedEpochMs: Long
)

data class KotlinDefinitionRecord(
    val name: String,
    val kind: String,
    val filePath: String,
    val line: Int,
    val fileId: String?,
    val contentHash: String
)

data class KotlinSourceIndexDb(
    val version: Int = 1,
    val root: String,
    val apiKeyHash: String?,
    val updatedAtEpochMs: Long,
    val files: List<KotlinSourceFileRecord>,
    val definitions: List<KotlinDefinitionRecord>,
    val vectorStoreId: String? = null,
    val vectorStoreFileHash: String? = null
)


object KotlinSourceIndex {
    private val mapper = OpenAiFileStoreCache.defaultMapper()
    private val cacheDir: Path = Paths.get(System.getProperty("user.home"), ".afield", "filestore")
    private val cachePath: Path = cacheDir.resolve("filestore_cache.json")
    private val indexPath: Path = cacheDir.resolve("kotlin_source_index.json")

    @Volatile
    var latestIndex: KotlinSourceIndexDb? = null
        private set

    fun loadIndex(): KotlinSourceIndexDb? {
        if (!Files.exists(indexPath)) return null
        return runCatching { mapper.readValue(indexPath.toFile(), KotlinSourceIndexDb::class.java) }
            .getOrNull()
    }

    fun startBackgroundSync() {
        thread(isDaemon = true, name = "KotlinSourceIndex") {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            runSync()
        }
    }

    private fun runSync() {
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val sourceRoots = listOf(
            root.resolve("./src/jvmMain/kotlin"),
            root.resolve("./src/personal/kotlin")
        ).filter { Files.exists(it) }

        if (sourceRoots.isEmpty()) {
            lprintln("warning,sourceindex", "no source roots found under ${root.toAbsolutePath()}")
            return
        }

        val apiKey = System.getenv("OPENAI_API_KEY")
        val cache = OpenAiFileStoreCache(cachePath)
        val uploader = OkHttpOpenAiFileUploader()
        val keyHash = apiKey?.let { cache.keyHash(it) }
        val previous = loadIndex()
        val previousByPath = previous?.files?.associateBy { it.path }.orEmpty()

        val files = mutableListOf<KotlinSourceFileRecord>()
        val definitions = mutableListOf<KotlinDefinitionRecord>()

        val ktFiles = collectKotlinFiles(sourceRoots)
        ktFiles.forEach { file ->
            val rel = root.relativize(file).toString().replace("\\", "/")
            val bytes = runCatching { Files.readAllBytes(file) }.getOrElse { return@forEach }
            val rawText = runCatching { bytes.toString(StandardCharsets.UTF_8) }.getOrDefault("")
            val isKotlinFile = rel.endsWith(".kt", ignoreCase = true) || rel.endsWith(".kts", ignoreCase = true)
            val uploadText = if (isKotlinFile) {
                buildString {
                    appendLine("// @language=kotlin")
                    appendLine("// @file $rel")
                    appendLine()
                    append(rawText)
                }
            } else {
                rawText
            }
            val uploadBytes = uploadText.toByteArray(StandardCharsets.UTF_8)
            val contentHash = Sha256.hex(uploadBytes)
            val prev = previousByPath[rel]

            val uploadName = if (isKotlinFile) {
                "$rel.txt"
            } else {
                rel
            }

            val fileId = if (apiKey != null) {
                if (prev != null && prev.contentHash == contentHash && !prev.fileId.isNullOrBlank()) {
                    prev.fileId
                } else {
                    val result = runCatching {
                        cache.uploadIfNeeded(
                            openAiApiKey = apiKey,
                            fileName = uploadName,
                            contentBytes = uploadBytes,
                            purpose = "assistants",
                            uploader = uploader
                        )
                    }.getOrElse {
                        lprintln("error,sourceindex", "upload failed for $rel: ${it.message}")
                        null
                    }
                    result?.record?.fileId
                }
            } else {
                prev?.fileId
            }

            val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
            files += KotlinSourceFileRecord(
                path = rel,
                contentHash = contentHash,
                fileId = fileId,
                bytes = uploadBytes.size.toLong(),
                modifiedEpochMs = modified
            )

            parseTopLevelDefinitions(rawText).forEach { def ->
                definitions += KotlinDefinitionRecord(
                    name = def.name,
                    kind = def.kind,
                    filePath = rel,
                    line = def.line,
                    fileId = fileId,
                    contentHash = contentHash
                )
            }
        }

        val fileIds = files.mapNotNull { it.fileId }.distinct()
        val desiredVectorHash = if (fileIds.isNotEmpty()) hashFileIds(fileIds) else null
        var vectorStoreId = previous?.vectorStoreId
        var vectorStoreFileHash = previous?.vectorStoreFileHash

        if (apiKey != null && fileIds.isNotEmpty()) {
            val needsVectorStore = vectorStoreId.isNullOrBlank() || vectorStoreFileHash != desiredVectorHash
            if (needsVectorStore) {
                val ensured = ensureVectorStore(apiKey, fileIds, vectorStoreId, root)
                if (!ensured.isNullOrBlank()) {
                    vectorStoreId = ensured
                    vectorStoreFileHash = desiredVectorHash
                }
            }
        }

        val index = KotlinSourceIndexDb(
            root = ".",
            apiKeyHash = keyHash,
            updatedAtEpochMs = System.currentTimeMillis(),
            files = files,
            definitions = definitions,
            vectorStoreId = vectorStoreId,
            vectorStoreFileHash = vectorStoreFileHash
        )

        runCatching {
            Files.createDirectories(indexPath.parent)
            mapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), index)
        }.onFailure {
            lprintln("error,sourceindex", "failed to persist index: ${it.message}")
        }

        runCatching {
            cache.flush()
        }.onFailure {
            lprintln("error,sourceindex", "failed to persist filestore cache: ${it.message}")
        }

        latestIndex = index
        lprintln("info,sourceindex", "indexed ${files.size} files, ${definitions.size} definitions")
    }

    fun currentVectorStoreId(): String? =
        latestIndex?.vectorStoreId ?: loadIndex()?.vectorStoreId

    private fun hashFileIds(fileIds: List<String>): String =
        Sha256.hexUtf8(fileIds.sorted().joinToString("\n"))

    private fun ensureVectorStore(
        apiKey: String,
        fileIds: List<String>,
        existingId: String?,
        root: Path
    ): String? {
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val name = "afield-src"
        val metadata = mapOf(
            "source" to "kotlin-src",
            "root" to root.toString(),
            "file_count" to fileIds.size.toString()
        )

        fun createNewStore(): String? {
            val created = runCatching {
                createVectorStore(http, apiKey, name, metadata)
            }.getOrElse {
                lprintln("error,sourceindex", "vector store create failed: ${it.message}")
                return null
            }
            val vsId = created
            val batch = runCatching {
                createVectorStoreFileBatch(http, apiKey, vsId, fileIds)
            }.getOrElse {
                lprintln("error,sourceindex", "vector store batch failed: ${it.message}")
                return null
            }
            runCatching { waitForFileBatch(http, apiKey, vsId, batch) }
                .onFailure { lprintln("error,sourceindex", "vector store batch wait failed: ${it.message}") }
            return vsId
        }

        if (existingId.isNullOrBlank()) {
            return createNewStore()
        }

        val updateResult = runCatching {
            val batch = createVectorStoreFileBatch(http, apiKey, existingId, fileIds)
            waitForFileBatch(http, apiKey, existingId, batch)
            existingId
        }.getOrElse {
            lprintln("error,sourceindex", "vector store update failed: ${it.message}")
            null
        }

        return updateResult ?: createNewStore() ?: existingId
    }

    private fun createVectorStore(
        http: HttpClient,
        apiKey: String,
        name: String,
        metadata: Map<String, String>
    ): String {
        val payload = mapOf("name" to name, "metadata" to metadata)
        val req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/vector_stores"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("VectorStores.create failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }
        val created = mapper.readValue(resp.body(), VectorStoreCreateResponse::class.java)
        return created.id
    }

    private fun createVectorStoreFileBatch(
        http: HttpClient,
        apiKey: String,
        vectorStoreId: String,
        fileIds: List<String>
    ): String {
        val payload = mapOf("file_ids" to fileIds)
        val req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/vector_stores/$vectorStoreId/file_batches"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("VectorStores.fileBatches.create failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }
        val created = mapper.readValue(resp.body(), VectorStoreFileBatchCreateResponse::class.java)
        return created.id
    }

    private fun waitForFileBatch(
        http: HttpClient,
        apiKey: String,
        vectorStoreId: String,
        batchId: String,
        maxWaitMs: Long = 60_000
    ) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var sleepMs = 250L

        while (System.currentTimeMillis() < deadline) {
            val req = HttpRequest.newBuilder(
                URI.create("https://api.openai.com/v1/vector_stores/$vectorStoreId/file_batches/$batchId")
            )
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()

            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                throw RuntimeException("VectorStores.fileBatches.get failed: HTTP ${resp.statusCode()} ${resp.body()}")
            }
            val got = mapper.readValue(resp.body(), VectorStoreFileBatchGetResponse::class.java)
            when (got.status?.lowercase()) {
                "completed" -> return
                "failed" -> throw RuntimeException("Vector store file batch failed: ${got.lastError?.message ?: "unknown"}")
            }

            Thread.sleep(sleepMs)
            sleepMs = (sleepMs * 1.6).toLong().coerceAtMost(2_000L)
        }
    }

    private fun collectKotlinFiles(roots: List<Path>): List<Path> {
        val out = mutableListOf<Path>()
        roots.forEach { root ->
            runCatching {
                Files.walk(root).use { stream ->
                    stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                        .forEach { out.add(it) }
                }
            }
        }
        return out
    }

    private data class ParsedDefinition(val kind: String, val name: String, val line: Int)

    private val modifierTokens = setOf(
        "public", "private", "internal", "protected",
        "open", "final", "abstract", "sealed", "data", "enum", "annotation", "value",
        "inline", "suspend", "tailrec", "operator", "infix", "external", "override",
        "const", "lateinit", "companion", "inner"
    )

    private fun parseTopLevelDefinitions(text: String): List<ParsedDefinition> {
        val out = mutableListOf<ParsedDefinition>()
        val state = KotlinParseState()
        val lines = text.split("\n")
        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val depthAtStart = state.depth
            val canParse = depthAtStart == 0 && !state.inBlockComment && !state.inRawString && !state.inString
            if (canParse) {
                parseDefinitionFromLine(rawLine)?.let { def ->
                    out += ParsedDefinition(def.first, def.second, lineNumber)
                }
            }
            state.consumeLine(rawLine)
        }
        return out
    }

    private fun parseDefinitionFromLine(line: String): Pair<String, String>? {
        var s = line.trimStart()
        if (s.isEmpty()) return null
        if (s.startsWith("package ") || s.startsWith("import ")) return null
        if (s.startsWith("//")) return null

        val annotationRegex = Regex("^@\\w[\\w\\.]*\\s*(\\([^)]*\\))?\\s*")
        while (true) {
            val match = annotationRegex.find(s)
            if (match != null && match.range.first == 0) {
                s = s.substring(match.range.last + 1).trimStart()
            } else {
                break
            }
        }

        val tokens = s.split(Regex("\\s+"))
        var idx = 0
        while (idx < tokens.size && modifierTokens.contains(tokens[idx])) {
            idx++
        }
        if (idx >= tokens.size) return null

        val keyword = tokens[idx]
        val remainder = s.substringAfter(keyword, "").trimStart()
        val name = when (keyword) {
            "fun" -> {
                if (remainder.startsWith("interface")) return null
                val head = remainder.substringBefore("(").trim()
                parseQualifiedName(head)
            }
            "class" -> {
                Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")
                    .find(remainder)?.groupValues?.get(1)?.let { unwrapIdentifier(it) }
            }
            "val", "var" -> {
                val head = remainder.substringBefore(":").substringBefore("=").trim()
                parseQualifiedName(head)
            }
            else -> null
        } ?: return null
        if (name.isBlank()) return null
        if (keyword == "class" || keyword == "fun" || keyword == "val" || keyword == "var") {
            return keyword to name
        }
        return null
    }

    private fun parseQualifiedName(raw: String): String? {
        if (raw.isBlank()) return null
        val noGenerics = stripGenericArgs(raw)
        val compact = removeWhitespaceOutsideBackticks(noGenerics).replace("?", "")
        if (compact.isBlank()) return null
        val regex = Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)(\\.`[^`]+`|\\.[A-Za-z_][A-Za-z0-9_]*)*$")
        val match = regex.find(compact) ?: return null
        return match.value.split(".").joinToString(".") { unwrapIdentifier(it) }
    }

    private fun unwrapIdentifier(raw: String): String =
        if (raw.startsWith("`") && raw.endsWith("`") && raw.length > 1) {
            raw.removePrefix("`").removeSuffix("`")
        } else {
            raw
        }

    private fun stripGenericArgs(raw: String): String {
        val sb = StringBuilder(raw.length)
        var depth = 0
        for (ch in raw) {
            when (ch) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun removeWhitespaceOutsideBackticks(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inBackticks = false
        for (ch in raw) {
            if (ch == '`') {
                inBackticks = !inBackticks
                sb.append(ch)
                continue
            }
            if (!inBackticks && ch.isWhitespace()) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private class KotlinParseState {
        var depth: Int = 0
        var inBlockComment: Boolean = false
        var inRawString: Boolean = false
        var inString: Boolean = false
        private var stringChar: Char = '"'

        fun consumeLine(line: String) {
            var i = 0
            var inLineComment = false
            while (i < line.length) {
                if (inLineComment) break
                if (inBlockComment) {
                    if (line.startsWith("*/", i)) {
                        inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (inRawString) {
                    if (line.startsWith("\"\"\"", i)) {
                        inRawString = false
                        i += 3
                    } else {
                        i++
                    }
                    continue
                }
                if (inString) {
                    if (line[i] == '\\') {
                        i += 2
                    } else if (line[i] == stringChar) {
                        inString = false
                        i++
                    } else {
                        i++
                    }
                    continue
                }

                if (line.startsWith("//", i)) {
                    inLineComment = true
                    continue
                }
                if (line.startsWith("/*", i)) {
                    inBlockComment = true
                    i += 2
                    continue
                }
                if (line.startsWith("\"\"\"", i)) {
                    inRawString = true
                    i += 3
                    continue
                }
                val ch = line[i]
                if (ch == '"' || ch == '\'') {
                    inString = true
                    stringChar = ch
                    i++
                    continue
                }
                if (ch == '{') depth++
                if (ch == '}') depth = (depth - 1).coerceAtLeast(0)
                i++
            }
        }
    }
}

@RunsAt(early, priority = 100)
fun syncKotlinSourceIndex() {
    KotlinSourceIndex.startBackgroundSync()
}
