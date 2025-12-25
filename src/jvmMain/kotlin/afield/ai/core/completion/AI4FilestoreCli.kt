package afield.ai

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
    println(
        """
AI4 filestore CLI

Usage:
  --cache-dir <path>   Directory for the cache DB (default: ~/.afield/filestore)
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
    override fun log(msg: String) {
        val prefix = "[OpenAiIde] "
        val full = prefix + msg
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
            val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
            val definitions = index?.definitions.orEmpty()
            val limit = req.limit.coerceAtLeast(1)
            val exact = ArrayList<WorkspaceSymbolMatch>()
            val prefix = ArrayList<WorkspaceSymbolMatch>()
            val contains = ArrayList<WorkspaceSymbolMatch>()

            val fileCache = HashMap<String, List<String>>()
            fun fileLines(path: String): List<String>? {
                return fileCache.getOrPut(path) {
                    val direct = Paths.get(path)
                    val resolved = if (Files.exists(direct)) direct else Paths.get(System.getProperty("user.dir")).resolve(path)
                    if (!Files.exists(resolved)) return@getOrPut emptyList()
                    runCatching { Files.readAllLines(resolved, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
                }.takeIf { it.isNotEmpty() }
            }

            fun definitionLineText(def: KotlinDefinitionRecord): String? {
                val lines = fileLines(def.filePath) ?: return null
                val idx = def.line - 1
                return lines.getOrNull(idx)?.trimEnd()
            }

            fun functionBody(def: KotlinDefinitionRecord, maxChars: Int = 6000): String? {
                if (def.kind != "fun") return null
                val lines = fileLines(def.filePath) ?: return null
                val startIdx = (def.line - 1).coerceIn(0, lines.lastIndex)
                var depth = 0
                var found = false
                val sb = StringBuilder()
                var chars = 0
                for (i in startIdx..lines.lastIndex) {
                    val line = lines[i]
                    var j = 0
                    while (j < line.length) {
                        val ch = line[j]
                        if (!found) {
                            if (ch == '{') {
                                found = true
                                depth = 1
                                sb.append(ch)
                            }
                        } else {
                            sb.append(ch)
                            if (ch == '{') depth++
                            if (ch == '}') {
                                depth--
                                if (depth == 0) return sb.toString().trimEnd()
                            }
                        }
                        if (found) {
                            chars++
                            if (chars >= maxChars) return sb.toString().trimEnd()
                        }
                        j++
                    }
                    if (found) {
                        sb.append('\n')
                        chars++
                        if (chars >= maxChars) return sb.toString().trimEnd()
                    }
                }
                return if (found) sb.toString().trimEnd() else null
            }

            fun toMatch(def: KotlinDefinitionRecord): WorkspaceSymbolMatch {
                val location = if (!def.fileId.isNullOrBlank()) {
                    "${def.filePath}:${def.line} (fileId=${def.fileId})"
                } else {
                    "${def.filePath}:${def.line}"
                }
                val lineText = definitionLineText(def)
                val body = functionBody(def)
                return WorkspaceSymbolMatch(
                    symbol = def.name,
                    kind = def.kind,
                    locationHint = location,
                    filePath = def.filePath,
                    fileId = def.fileId,
                    line = def.line,
                    definitionLine = lineText,
                    definitionBody = body
                )
            }

            for (def in definitions) {
                val name = def.name
                if (name.equals(query, ignoreCase = true)) {
                    exact.add(toMatch(def))
                    if (exact.size >= limit) break
                    continue
                }
                if (name.startsWith(query, ignoreCase = true)) {
                    prefix.add(toMatch(def))
                    continue
                }
                if (name.contains(query, ignoreCase = true)) {
                    contains.add(toMatch(def))
                }
            }

            val matches = ArrayList<WorkspaceSymbolMatch>(limit)
            for (bucket in listOf(exact, prefix, contains)) {
                for (item in bucket) {
                    matches.add(item)
                    if (matches.size >= limit) break
                }
                if (matches.size >= limit) break
            }
            WorkspaceSymbolSearchResult(matches)
        }
    }
}

private fun parseArgs(args: Array<String>): FilestoreCliConfig {
    var cacheDir = Paths.get(System.getProperty("user.home"), ".afield", "filestore")
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
                println("Unknown argument: $arg")
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
        println("Invalid --vfs-root URI: $raw")
        null
    }
}

private fun parseVirtualUri(raw: String): URI? {
    return try {
        URI(raw)
    } catch (_: Exception) {
        println("Invalid --vfs-file URI: $raw")
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
    println(System.getenv("COLUMNS"))
    val config = parseArgs(args)
    if (config.showHelp) {
        printUsage()
        return
    }

    val apiKey = config.apiKey ?: System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Missing API key. Provide --api-key or set OPENAI_API_KEY.")
        return
    }

    val targets = collectFiles(config)
    val virtualFileSystems = buildVirtualFileSystems(config.virtualRoots)
    val virtualFiles = collectVirtualFiles(config.virtualRoots, config.virtualUris, virtualFileSystems)

    if (targets.isEmpty() && virtualFiles.isEmpty()) {
        println("No files found. Continuing without uploads.")
    }

    Files.createDirectories(config.cacheDir)
    val cachePath = config.cacheDir.resolve("filestore_cache.json")
    val cache = OpenAiFileStoreCache(cachePath)
    val uploader = OkHttpOpenAiFileUploader()
    val http = HttpClient.newBuilder().build()
    val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    println("Cache: ${cachePath.toAbsolutePath()}")
    println("Purpose: ${config.purpose}")
    if (targets.isNotEmpty()) {
        println("Uploading ${targets.size} file(s)...")
    }
    if (virtualFiles.isNotEmpty()) {
        println("Uploading ${virtualFiles.size} virtual file(s)...")
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
        println("${result.action.padEnd(10)} $fileName -> $id (${bytes.size} bytes)")
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
        println("${upload.result.action.padEnd(10)} ${upload.file.ref.uri} -> $id ($bytes bytes)")
    }

    val uniqueIds = fileIds.distinct()
    if (uniqueIds.isNotEmpty()) {
        println("\nfile_ids:")
        uniqueIds.forEach { println("  $it") }
        println("\nChat reference:")
        println("Use these file_ids in your prompt/context: ${uniqueIds.joinToString(", ")}")
    }

    val vectorStoreId = if (uniqueIds.isNotEmpty()) {
        try {
            val name = "afield-cli-${Instant.now().epochSecond}"
            println("\nCreating vector store for file_search...")
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
            println("Vector store ready: ${created.vectorStoreId}")
            created.vectorStoreId
        } catch (ex: Exception) {
            println("Vector store setup failed: ${ex.message}")
            null
        }
    } else null

    val bridge = OpenAiIdeCodexBridge(model = "gpt-5.2", log = TputLog())
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

    println("\nCLI chat ready. Type /help for commands, /exit to quit.")
    while (true) {
        print("you> ")
        val line = readLine() ?: break
        val input = line.trim()
        if (input.isEmpty()) continue

        when (input) {
            "/exit" -> break
            "/help" -> {
                println(
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
                    println("No file_ids loaded.")
                } else {
                    println("file_ids:")
                    uniqueIds.forEach { println("  $it") }
                }
                if (vectorStoreId != null) {
                    println("vector_store_id: $vectorStoreId")
                } else {
                    println("vector_store_id: <none>")
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
        println()
        if (result.commentary.isNotEmpty()) {
            println("\n[commentary]\n" + result.commentary.joinToString("\n"))
        }
        state = result.threadState
    }

    println("bye.")
}
