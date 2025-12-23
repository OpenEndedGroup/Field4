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
    val recursive: Boolean,
    val includeHidden: Boolean,
    val showHelp: Boolean
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class VectorStoreCreateResponse(val id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class VectorStoreFileBatchCreateResponse(val id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class VectorStoreFileBatchGetResponse(
    val id: String,
    val status: String? = null,
    @JsonProperty("last_error") val lastError: LastError? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class LastError(val message: String? = null)

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

private fun parseArgs(args: Array<String>): FilestoreCliConfig {
    var cacheDir = Paths.get(System.getProperty("user.home"), ".afield", "filestore")
    var apiKey: String? = null
    var purpose = "assistants"
    var files = mutableListOf<Path>()
    var dirs = mutableListOf<Path>()
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
        recursive = recursive,
        includeHidden = includeHidden,
        showHelp = showHelp
    )
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
    if (targets.isEmpty()) {
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
    val instructions = buildString {
        appendLine(OpenAiIdeCodexBridge.defaultChatThreadInstructions.trim())
        if (vectorStoreId != null) {
            appendLine()
            appendLine("File search is available. Use it when relevant.")
        }
    }.trim()

    var state = bridge.startThread(
        fileKey = "cli://filestore",
        fileText = "",
        instructions = instructions
    )

    val ctx = ToolExecutionContext()
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
