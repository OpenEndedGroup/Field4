package afield.ai.core.completion

import afield.ai.KotlinSourceIndex
import afield.ai.searchWorkspaceSymbols
import afield.ai.core.AttachmentResponse
import afield.ai.core.ContextAttachment
import afield.ai.core.EditFileRequest
import afield.ai.core.EditFileResponse
import afield.ai.core.ListContentEntry
import afield.ai.core.ListContentsResult
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.OpenAiIdeCodexBridge
import afield.ai.core.ToolExecutionContext
import afield.ai.core.WorkspaceSymbolSearch
import afield.ai.core.WorkspaceSymbolSearchResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.pathString

private val defaultGeneratedDocCachePath: Path = Paths.get(
    System.getProperty("user.home"),
    ".afield",
    "filestore",
    "generated_completion_docs.json"
)

private data class PolishOptions(
    val cacheFile: Path = defaultGeneratedDocCachePath,
    val srcDir: Path = Paths.get("src"),
    val model: String = System.getenv("AFIELD_COMPLETION_DOC_POLISH_MODEL")
        ?.takeIf { it.isNotBlank() }
        ?: afield.ai.core.Ai4ModelDefaults.flagshipModel(),
    val dryRun: Boolean = false,
    val maxToolRounds: Int = 40,
    val maxOutputTokens: Long = 24000,
    val promptFile: Path? = null
)

fun main(args: Array<String>) {
    val options = parseOptions(args.toList()) ?: return
    val workspaceRoot = Paths.get("").toAbsolutePath().normalize()
    val cacheFile = options.cacheFile.toAbsolutePath().normalize()
    val srcDir = workspaceRoot.resolve(options.srcDir).normalize()

    println("Completion doc cache: ${cacheFile.absolutePathString()}")
    println("Source root: ${srcDir.absolutePathString()}")
    println("Model: ${options.model}")
    if (options.dryRun) println("Dry run: EditFile requests will be reported but not written.")

    val cacheAttachment = ContextAttachment(
        fileName = "completion-doc-cache.json",
        language = "json",
        code = readTextIfExists(cacheFile),
        description = "Generated completion docs cache at ${cacheFile.absolutePathString()}"
    )
    val sourceManifest = buildSourceManifest(workspaceRoot, srcDir)
    val manifestAttachment = ContextAttachment(
        fileName = "src-manifest.txt",
        language = "text",
        code = sourceManifest,
        description = "Kotlin source files under ${workspaceRoot.relativizeOrAbsolute(srcDir)}. Use AttachmentRequest with these paths to read full files."
    )

    val indexForTools = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
    val bridge = OpenAiIdeCodexBridge(model = options.model, log = CliLog())
    val instructions = buildString {
        appendLine(OpenAiIdeCodexBridge.defaultChatThreadInstructions.trim())
        appendLine()
        appendLine("You are running as a freestanding AField completion-doc polish tool.")
        appendLine("You may read the generated completion-doc cache and source files under src/.")
        appendLine("You may edit only source files under src/ and the generated completion-doc cache file.")
        appendLine("Use AttachmentRequest to inspect specific files from src-manifest.txt.")
        appendLine("Use EditFile only when you have the full new file content.")
        appendLine("Do not make behavior changes. Keep Kotlin signatures intact.")
        appendLine("Prefer integrating useful generated docs into real KDoc near source declarations.")
        appendLine("Keep KDoc concise and specific. Remove or rewrite cache entries only when they are obsolete, duplicated, or clearly superseded by source KDoc.")
        if (!indexForTools?.definitions.isNullOrEmpty()) {
            appendLine("WorkspaceSymbolSearch is available for Kotlin source definitions.")
        }
    }.trim()

    val state = bridge.startThread(
        fileKey = "cli://completion-doc-polish",
        fileText = "",
        instructions = instructions
    )

    val ctx = ToolExecutionContext(
        attachments = listOf(cacheAttachment, manifestAttachment),
        resolveAttachment = { requested ->
            resolveAttachment(workspaceRoot, srcDir, cacheFile, requested)
        },
        listFileContents = {
            ListContentsResult(
                contents = listOf(
                    ListContentEntry(
                        fileRef = "completion-doc-cache.json",
                        property = "cache",
                        language = "json",
                        content = readTextIfExists(cacheFile),
                        boxName = "Generated completion doc cache",
                        relation = "cache"
                    ),
                    ListContentEntry(
                        fileRef = "src-manifest.txt",
                        property = "manifest",
                        language = "text",
                        content = sourceManifest,
                        boxName = "Source manifest",
                        relation = "source"
                    )
                )
            )
        },
        editOtherFile = { req ->
            applyEdit(workspaceRoot, srcDir, cacheFile, req, options.dryRun)
        },
        workspaceSymbolSearch = if (!indexForTools?.definitions.isNullOrEmpty()) {
            workspaceSymbolSearchFromIndex()
        } else {
            { _: WorkspaceSymbolSearch -> WorkspaceSymbolSearchResult(emptyList()) }
        }
    )

    val prompt = options.promptFile
        ?.let { readTextIfExists(it.toAbsolutePath().normalize()) }
        ?.takeIf { it.isNotBlank() }
        ?: defaultPolishPrompt(cacheFile, srcDir)

    val result = bridge.send(
        state = state,
        userPrompt = prompt,
        ctx = ctx,
        maxOutputTokens = options.maxOutputTokens,
        maxToolRounds = options.maxToolRounds,
        onTextDelta = {
            print(it)
            System.out.flush()
        },
        onProgress = { progress ->
            val body = progress.body.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            println("\n[progress] ${progress.title}$body")
        }
    )

    println()
    if (result.commentary.isNotEmpty()) {
        println("[commentary]")
        println(result.commentary.joinToString("\n"))
    }
}

private fun parseOptions(args: List<String>): PolishOptions? {
    var options = PolishOptions()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> {
                printUsage()
                return null
            }
            "--cache-file" -> {
                options = options.copy(cacheFile = Paths.get(args.valueAfter(i, arg)))
                i++
            }
            "--src-dir" -> {
                options = options.copy(srcDir = Paths.get(args.valueAfter(i, arg)))
                i++
            }
            "--model" -> {
                options = options.copy(model = args.valueAfter(i, arg))
                i++
            }
            "--dry-run" -> options = options.copy(dryRun = true)
            "--max-tool-rounds" -> {
                options = options.copy(maxToolRounds = args.valueAfter(i, arg).toInt())
                i++
            }
            "--max-output-tokens" -> {
                options = options.copy(maxOutputTokens = args.valueAfter(i, arg).toLong())
                i++
            }
            "--prompt-file" -> {
                options = options.copy(promptFile = Paths.get(args.valueAfter(i, arg)))
                i++
            }
            else -> error("Unknown argument '$arg'. Use --help.")
        }
        i++
    }
    return options
}

private fun List<String>.valueAfter(index: Int, option: String): String {
    return getOrNull(index + 1) ?: error("$option requires a value.")
}

private fun printUsage() {
    println(
        """
Usage:
  ./gradlew polishCompletionDocs --args="[...]"

Options:
  --cache-file <path>        Generated docs cache. Defaults to ~/.afield/filestore/generated_completion_docs.json
  --src-dir <path>           Source root relative to the repo. Defaults to src
  --model <model>            Model to use. Defaults to AFIELD_COMPLETION_DOC_POLISH_MODEL or AFIELD_AI_MODEL/flagship.
  --dry-run                  Let the model request edits, but do not write them.
  --prompt-file <path>       Use a custom user prompt.
  --max-tool-rounds <n>      Defaults to 40.
  --max-output-tokens <n>    Defaults to 24000.
        """.trimIndent()
    )
}

private fun defaultPolishPrompt(cacheFile: Path, srcDir: Path): String = """
Clean up and integrate AField completion docs.

Inputs:
- Generated completion doc cache: ${cacheFile.absolutePathString()} (also attached as completion-doc-cache.json)
- Source root: ${srcDir.absolutePathString()} (manifest attached as src-manifest.txt)

Task:
1. Inspect the cache for useful generated KDoc or parameter descriptions created by the fast completion-doc model.
2. For entries that correspond to real Kotlin declarations under src/, integrate the useful prose into source KDoc near those declarations.
3. Prefer source KDoc over keeping polished text only in the cache.
4. Avoid duplicated parameter lists. Do not restate obvious names or types unless the description adds meaning.
5. Preserve behavior, signatures, defaults, imports, and formatting style.
6. Clean the cache file if entries are clearly obsolete, low quality, or superseded by KDoc you moved into source.
7. Stop after a coherent first pass; summarize the files changed and any declarations you intentionally left for later.
""".trimIndent()

private fun workspaceSymbolSearchFromIndex(): (WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult {
    return { req ->
        val query = req.query.trim()
        if (query.isEmpty()) {
            WorkspaceSymbolSearchResult(emptyList())
        } else {
            searchWorkspaceSymbols(
                index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex(),
                rawQuery = query,
                limit = req.limit
            )
        }
    }
}

private fun resolveAttachment(
    workspaceRoot: Path,
    srcDir: Path,
    cacheFile: Path,
    requested: String
): AttachmentResponse? {
    val path = resolveAllowedPath(workspaceRoot, srcDir, cacheFile, requested) ?: return null
    val language = languageFor(path)
    return AttachmentResponse(
        file = workspaceRoot.relativizeOrAbsolute(path),
        language = language,
        content = readTextIfExists(path)
    )
}

private fun applyEdit(
    workspaceRoot: Path,
    srcDir: Path,
    cacheFile: Path,
    req: EditFileRequest,
    dryRun: Boolean
): EditFileResponse {
    val path = resolveAllowedPath(workspaceRoot, srcDir, cacheFile, req.file)
        ?: return EditFileResponse(false, "Refusing to edit '${req.file}': only src/ and the completion-doc cache are writable.")
    if (dryRun) {
        return EditFileResponse(true, "Dry run: would write ${workspaceRoot.relativizeOrAbsolute(path)} (${req.newContent.length} chars).")
    }
    Files.createDirectories(path.parent)
    Files.writeString(path, req.newContent, StandardCharsets.UTF_8)
    return EditFileResponse(true, "Wrote ${workspaceRoot.relativizeOrAbsolute(path)} (${req.newContent.length} chars).")
}

private fun resolveAllowedPath(
    workspaceRoot: Path,
    srcDir: Path,
    cacheFile: Path,
    requested: String
): Path? {
    val trimmed = requested.trim()
    if (trimmed == "completion-doc-cache.json" || trimmed == cacheFile.absolutePathString()) return cacheFile
    val candidate = when {
        trimmed.startsWith("/") -> Paths.get(trimmed)
        else -> workspaceRoot.resolve(trimmed)
    }.toAbsolutePath().normalize()
    return when {
        candidate == cacheFile -> candidate
        candidate.startsWith(srcDir) -> candidate
        else -> null
    }
}

private fun buildSourceManifest(workspaceRoot: Path, srcDir: Path): String {
    if (!Files.isDirectory(srcDir)) return "Source directory not found: ${srcDir.absolutePathString()}\n"
    val files = Files.walk(srcDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { it.extension in setOf("kt", "kts", "java") }
            .sorted()
            .map { workspaceRoot.relativizeOrAbsolute(it.toAbsolutePath().normalize()) }
            .toList()
    }
    return buildString {
        appendLine("Kotlin/JVM source manifest for completion-doc polishing.")
        appendLine("Use AttachmentRequest with one of these file refs to read full content.")
        appendLine()
        files.forEach { appendLine(it) }
    }
}

private fun languageFor(path: Path): String = when (path.extension.lowercase()) {
    "json" -> "json"
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    else -> "text"
}

private fun readTextIfExists(path: Path): String =
    if (Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else ""

private fun Path.relativizeOrAbsolute(path: Path): String =
    try {
        relativize(path).pathString
    } catch (_: IllegalArgumentException) {
        path.absolutePathString()
    }

private class CliLog : LogSink {
    override fun log(event: LogEvent) {
        val channel = if (event.channel.isBlank()) "" else "[${event.channel}] "
        println("[completion-doc-polish] $channel${event.message}")
    }
}
