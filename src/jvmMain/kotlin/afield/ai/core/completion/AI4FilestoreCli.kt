package afield.ai

import afield.ai.core.Ai4ModelDefaults
import afield.ai.core.LogEvent
import afield.logger.lprintln
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private data class FilestoreCliConfig(
    val cacheDir: Path,
    val apiKey: String?,
    val purpose: String,
    val files: List<Path>,
    val dirs: List<Path>,
    val virtualRoots: List<URI>,
    val virtualUris: List<URI>,
    val recursive: Boolean,
    val includeHidden: Boolean,
    val showHelp: Boolean
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VectorStoreCreateResponse(val id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VectorStoreFileBatchCreateResponse(val id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VectorStoreFileBatchGetResponse(
    val id: String,
    val status: String? = null,
    @JsonProperty("last_error") val lastError: LastError? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LastError(val message: String? = null)

private data class CreatedVectorStore(val vectorStoreId: String)
private data class CreatedBatch(val batchId: String)

private fun createVectorStore(
    http: HttpClient,
    mapper: ObjectMapper,
    apiKey: String,
    name: String,
    metadata: Map<String, String>
): CreatedVectorStore {
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
    val created = mapper.readValue<VectorStoreCreateResponse>(resp.body())
    return CreatedVectorStore(created.id)
}

private fun createVectorStoreFileBatch(
    http: HttpClient,
    mapper: ObjectMapper,
    apiKey: String,
    vectorStoreId: String,
    fileIds: List<String>
): CreatedBatch {
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
    val created = mapper.readValue<VectorStoreFileBatchCreateResponse>(resp.body())
    return CreatedBatch(created.id)
}

private fun waitForFileBatch(
    http: HttpClient,
    mapper: ObjectMapper,
    apiKey: String,
    vectorStoreId: String,
    batchId: String,
    maxWaitMs: Long
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
        val got = mapper.readValue<VectorStoreFileBatchGetResponse>(resp.body())
        when (got.status?.lowercase()) {
            "completed" -> return
            "failed" -> throw RuntimeException("Vector store file batch failed: ${got.lastError?.message ?: "unknown"}")
        }

        Thread.sleep(sleepMs)
        sleepMs = (sleepMs * 1.6).toLong().coerceAtMost(2_000L)
    }
}

private fun printUsage() {
    lprintln(
        "info,filestore-cli",
        """
AI4 filestore CLI

Usage:
  --cache-dir <path>   Directory for the cache DB (default: the checkout's .field/filestore; see FilestoreDir)
  --api-key <key>      OpenAI API key (default: OPENAI_API_KEY env var)
  --purpose <purpose>  OpenAI file purpose (default: assistants)
  --file <path>        Add a file (repeatable)
  --dir <path>         Add a directory (repeatable)
  --vfs-root <uri>     Add a virtual FS root (repeatable)
  --vfs-file <uri>     Add a virtual file URI (repeatable)
  --no-recursive       Do not scan directories recursively
  --include-hidden     Include hidden files (dotfiles)
  --help               Show this help

Example:
  --cache-dir /tmp/afield-cache --file README.md --dir ./docs
        """.trimIndent()
    )
}

private class TputLog : LogSink {
    override fun log(event: LogEvent) {
        val prefix = "[OpenAiIde] "
        val channelPart = if (event.channel.isNotBlank()) "[${event.channel}] " else ""
        val full = prefix + channelPart + event.message
        val width = resolveTerminalWidth()
        val padded = if (full.length < width) " ".repeat(width - full.length) + full else full
        val ansiDarkGray = "\u001b[90m"
        val ansiReset = "\u001b[0m"
        println(ansiDarkGray + padded + ansiReset)
    }
}

private fun resolveTerminalWidth(): Int {
    val ttyWidth = try {
        val proc = ProcessBuilder("sh", "-lc", "stty size < /dev/tty")
            .redirectErrorStream(true)
            .start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        if (exit != 0 || text.isBlank()) {
            null
        } else {
            val parts = text.split(Regex("\\s+"))
            if (parts.size >= 2) parts[1].toIntOrNull() else null
        }
    } catch (_: Exception) {
        null
    }
    val envWidth = System.getenv("COLUMNS")?.toIntOrNull()
    val width = ttyWidth ?: envWidth ?: 200
    return width.coerceAtLeast(20)
}

private fun workspaceSymbolSearchFromIndex(): (WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult {
    return { req ->
        val query = req.query.trim()
        if (query.isEmpty()) WorkspaceSymbolSearchResult(emptyList()) else {
            searchWorkspaceSymbols(
                index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex(),
                rawQuery = query,
                limit = req.limit,
                includeRelatedContext = req.includeRelatedContext
            )
        }
    }
}

private fun parseArgs(args: Array<String>): FilestoreCliConfig {
    // The directory the rest of Field resolves, so `--cache-dir` defaults to the filestore this
    // checkout actually uses rather than to a global one it may no longer share. See [FilestoreDir].
    var cacheDir = FilestoreDir.path
    var apiKey: String? = null
    var purpose = "assistants"
    var files = mutableListOf<Path>()
    var dirs = mutableListOf<Path>()
    var virtualRoots = mutableListOf<URI>()
    var virtualUris = mutableListOf<URI>()
    var recursive = true
    var includeHidden = false
    var showHelp = false

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--cache-dir" -> {
                cacheDir = Paths.get(args.getOrNull(i + 1) ?: "")
                i += 2
            }
            "--api-key" -> {
                apiKey = args.getOrNull(i + 1)
                i += 2
            }
            "--purpose" -> {
                purpose = args.getOrNull(i + 1) ?: purpose
                i += 2
            }
            "--file" -> {
                val p = args.getOrNull(i + 1)
                if (p != null) files += Paths.get(p).toMutableList()
                i += 2
            }
            "--dir" -> {
                val p = args.getOrNull(i + 1)
                if (p != null) dirs += Paths.get(p).toMutableList()
                i += 2
            }
            "--no-recursive" -> {
                recursive = false
                i += 1
            }
            "--vfs-root" -> {
                val raw = args.getOrNull(i + 1)
                if (raw != null) {
                    parseVirtualRoot(raw)?.let { virtualRoots += it }
                }
                i += 2
            }
            "--vfs-file" -> {
                val raw = args.getOrNull(i + 1)
                if (raw != null) {
                    parseVirtualUri(raw)?.let { virtualUris += it }
                }
                i += 2
            }
            "--include-hidden" -> {
                includeHidden = true
                i += 1
            }
            "--help", "-h" -> {
                showHelp = true
                i += 1
            }
            else -> {
                lprintln("warning,filestore-cli", "Unknown argument: $arg")
                showHelp = true
                i += 1
            }
        }
    }

    return FilestoreCliConfig(
        cacheDir = cacheDir,
        apiKey = apiKey,
        purpose = purpose,
        files = files,
        dirs = dirs,
        virtualRoots = virtualRoots,
        virtualUris = virtualUris,
        recursive = recursive,
        includeHidden = includeHidden,
        showHelp = showHelp
    )
}

private fun parseVirtualRoot(raw: String): URI? {
    return try {
        val uri = URI(raw)
        if (uri.scheme == null) {
            URI.create("afield:///" + raw.trimStart('/'))
        } else {
            uri
        }
    } catch (_: Exception) {
        lprintln("error,filestore-cli", "Invalid --vfs-root URI: $raw")
        null
    }
}

private fun parseVirtualUri(raw: String): URI? {
    return try {
        URI(raw)
    } catch (_: Exception) {
        lprintln("error,filestore-cli", "Invalid --vfs-file URI: $raw")
        null
    }
}

private fun buildVirtualFileSystems(roots: List<URI>): List<VirtualFileSystem> {
    val byScheme = roots.groupBy { it.scheme?.lowercase() }
    val systems = mutableListOf<VirtualFileSystem>()
    val afieldRoots = byScheme["afield"].orEmpty().mapNotNull { root ->
        root.path?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
    }
    if (afieldRoots.isNotEmpty()) {
        systems += AfieldVirtualFileSystem(afieldRoots)
    }
    return systems
}

private fun collectFiles(config: FilestoreCliConfig): List<Path> {
    var out = mutableListOf<Path>()

    config.files.forEach { p ->
        if (Files.exists(p) && p.isRegularFile()) out += p.toMutableList()
    }

    config.dirs.forEach { dir ->
        if (!Files.exists(dir) || !dir.isDirectory()) return@forEach
        val stream = if (config.recursive) Files.walk(dir) else Files.list(dir)
        stream.use { paths ->
            paths.filter { it.isRegularFile() }.forEach { p ->
                if (!config.includeHidden && p.fileName.toString().startsWith(".")) return@forEach
                out += p.toMutableList()
            }
        }
    }

    return out.distinct()
}

fun main(args: Array<String>) {
    lprintln("debug,filestore-cli", System.getenv("COLUMNS"))
    val config = parseArgs(args)
    if (config.showHelp) {
        printUsage()
        return
    }

    val apiKey = config.apiKey ?: System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        lprintln("error,filestore-cli", "Missing API key. Provide --api-key or set OPENAI_API_KEY.")
        return
    }

    val targets = collectFiles(config)
    val virtualFileSystems = buildVirtualFileSystems(config.virtualRoots)
    val virtualFiles = collectVirtualFiles(config.virtualRoots, config.virtualUris, virtualFileSystems)

    if (targets.isEmpty() && virtualFiles.isEmpty()) {
        lprintln("warning,filestore-cli", "No files found. Continuing without uploads.")
    }

    Files.createDirectories(config.cacheDir)
    val cachePath = config.cacheDir.resolve("filestore_cache.json")
    val cache = OpenAiFileStoreCache(cachePath)
    val uploader = OkHttpOpenAiFileUploader()
    val http = HttpClient.newBuilder().build()
    val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    lprintln("info,filestore-cli", "Cache: ${cachePath.toAbsolutePath()}")
    lprintln("info,filestore-cli", "Purpose: ${config.purpose}")
    if (targets.isNotEmpty()) {
        lprintln("info,filestore-cli", "Uploading ${targets.size} file(s)...")
    }
    if (virtualFiles.isNotEmpty()) {
        lprintln("info,filestore-cli", "Uploading ${virtualFiles.size} virtual file(s)...")
    }

    val fileIds = mutableListOf<String>()
    targets.forEach { path ->
        val bytes = Files.readAllBytes(path)
        val fileName = path.fileName.toString()
        val result = cache.uploadIfNeeded(
            openAiApiKey = apiKey,
            fileName = fileName,
            contentBytes = bytes,
            purpose = config.purpose,
            uploader = uploader
        )
        val id = result.record.fileId
        fileIds += id
        lprintln("info,filestore-cli", "${result.action.padEnd(10)} $fileName -> $id (${bytes.size} bytes)")
    }
    val virtualUploads = uploadVirtualFiles(
        cache = cache,
        uploader = uploader,
        openAiApiKey = apiKey,
        purpose = config.purpose,
        files = virtualFiles
    )
    virtualUploads.forEach { upload ->
        val id = upload.result.record.fileId
        fileIds += id
        val bytes = upload.result.record.byteCount
        lprintln("info,filestore-cli", "${upload.result.action.padEnd(10)} ${upload.file.ref.uri} -> $id ($bytes bytes)")
    }

    val uniqueIds = fileIds.distinct()
    if (uniqueIds.isNotEmpty()) {
        lprintln("info,filestore-cli", "file_ids:")
        uniqueIds.forEach { lprintln("info,filestore-cli", "  $it") }
        lprintln("info,filestore-cli", "Chat reference:")
        lprintln("info,filestore-cli", "Use these file_ids in your prompt/context: ${uniqueIds.joinToString(", ")}")
    }

    val vectorStoreId = if (uniqueIds.isNotEmpty()) {
        try {
            val name = "afield-cli-${Instant.now().epochSecond}"
            lprintln("info,filestore-cli", "Creating vector store for file_search...")
            val created = createVectorStore(
                http = http,
                mapper = mapper,
                apiKey = apiKey,
                name = name,
                metadata = mapOf("source" to "afield-cli", "file_count" to uniqueIds.size.toString())
            )
            val batch = createVectorStoreFileBatch(
                http = http,
                mapper = mapper,
                apiKey = apiKey,
                vectorStoreId = created.vectorStoreId,
                fileIds = uniqueIds
            )
            waitForFileBatch(
                http = http,
                mapper = mapper,
                apiKey = apiKey,
                vectorStoreId = created.vectorStoreId,
                batchId = batch.batchId,
                maxWaitMs = 60_000
            )
            lprintln("info,filestore-cli", "Vector store ready: ${created.vectorStoreId}")
            created.vectorStoreId
        } catch (ex: Exception) {
            lprintln("error,filestore-cli", "Vector store setup failed: ${ex.message}")
            null
        }
    } else null

    val bridge = OpenAiIdeCodexBridge(model = Ai4ModelDefaults.flagshipModel(), log = TputLog())
    val indexForTools = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
    val instructions = buildString {
        appendLine(OpenAiIdeCodexBridge.defaultChatThreadInstructions.trim())
        if (vectorStoreId != null) {
            appendLine()
            appendLine("File search is available. Use it when relevant.")
        }
        if (!indexForTools?.definitions.isNullOrEmpty()) {
            appendLine()
            appendLine("WorkspaceSymbolSearch is available for Kotlin source definitions (includes filePath, fileId, line, definitionLine, and definitionBody for fun).")
        }
        if (uniqueIds.isNotEmpty()) {
            val preview = uniqueIds.take(12)
            appendLine()
            appendLine("file_ids: ${preview.joinToString(", ")}${if (uniqueIds.size > preview.size) " ..." else ""}")
        }
    }.trim()

    var state = bridge.startThread(
        fileKey = "cli://filestore",
        fileText = "",
        instructions = instructions
    )

    val ctx = ToolExecutionContext(
        attachments = virtualFilesToAttachments(virtualFiles),
        workspaceSymbolSearch = if (!indexForTools?.definitions.isNullOrEmpty()) workspaceSymbolSearchFromIndex() else null
    )
    val vectorStores = vectorStoreId?.let { listOf(it) } ?: emptyList()

    lprintln("info,filestore-cli", "CLI chat ready. Type /help for commands, /exit to quit.")
    while (true) {
        print("you> ")
        val line = readlnOrNull() ?: break
        val input = line.trim()
        if (input.isEmpty()) continue

        when (input) {
            "/exit" -> break
            "/help" -> {
                lprintln(
                    "info,filestore-cli",
                    """
Commands:
  /help  Show this help
  /exit  Quit
  /files Show current file ids and vector store
                    """.trimIndent()
                )
                continue
            }
            "/files" -> {
                if (uniqueIds.isEmpty()) {
                    lprintln("info,filestore-cli", "No file_ids loaded.")
                } else {
                    lprintln("info,filestore-cli", "file_ids:")
                    uniqueIds.forEach { lprintln("info,filestore-cli", "  $it") }
                }
                if (vectorStoreId != null) {
                    lprintln("info,filestore-cli", "vector_store_id: $vectorStoreId")
                } else {
                    lprintln("info,filestore-cli", "vector_store_id: <none>")
                }
                continue
            }
        }

        val result = bridge.send(
            state = state,
            userPrompt = input,
            ctx = ctx,
            vectorStoreIds = vectorStores,
            onTextDelta = { chunk ->
                print(chunk)
                System.out.flush()
            }
        )
        lprintln("info,filestore-cli", "")
        if (result.commentary.isNotEmpty()) {
            lprintln("info,filestore-cli", "[commentary]\n" + result.commentary.joinToString("\n"))
        }
        state = result.threadState
    }

    lprintln("info,filestore-cli", "bye.")
}
