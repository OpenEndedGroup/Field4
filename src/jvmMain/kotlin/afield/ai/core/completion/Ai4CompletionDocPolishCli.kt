package afield.ai.core.completion

import afield.ai.KotlinSourceIndex
import afield.ai.OpenAiFileStoreCache
import afield.ai.searchWorkspaceSymbols
import afield.ai.core.AttachmentResponse
import afield.ai.core.AgentMemoryState
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
import com.fasterxml.jackson.annotation.JsonClassDescription
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.system.exitProcess

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
    val verbose: Boolean = false,
    val maxToolRounds: Int = 40,
    val maxOutputTokens: Long = 24000,
    val promptFile: Path? = null,
    val diffFile: Path = Paths.get("build", "completion-doc-polish-dry-run.patch")
)

@JsonClassDescription("Patch a writable file by replacing an exact oldText snippet with newText. Prefer this over EditFile for small KDoc/documentation edits.")
data class ReplaceInFileRequest(
    val file: String,
    val oldText: String,
    val newText: String,
    val expectedOccurrences: Int = 1
)

data class ReplaceInFileResponse(
    val success: Boolean,
    val message: String,
    val file: String,
    val occurrences: Int = 0,
    val newContentChars: Int = 0
)

private data class CompletionDocCacheStatus(
    val hasWork: Boolean,
    val message: String,
    val shouldRemoveIfNonDryRun: Boolean = false
)

fun main(args: Array<String>) {
    val options = parseOptions(args.toList()) ?: return
    val workspaceRoot = Paths.get("").toAbsolutePath().normalize()
    val cacheFile = options.cacheFile.toAbsolutePath().normalize()
    val srcDir = workspaceRoot.resolve(options.srcDir).normalize()
    val diffFile = workspaceRoot.resolve(options.diffFile).normalize()

    println("Completion doc cache: ${cacheFile.absolutePathString()}")
    println("Source root: ${srcDir.absolutePathString()}")
    println("Model: ${options.model}")
    if (options.dryRun) {
        println("Dry run: patch/edit requests update an in-memory overlay only.")
        println("Dry-run patch: ${diffFile.absolutePathString()}")
    }

    val cacheStatus = completionDocCacheStatus(cacheFile)
    if (!cacheStatus.hasWork) {
        println("No generated completion docs to polish: ${cacheStatus.message}")
        if (options.dryRun) {
            Files.deleteIfExists(diffFile)
            println("Dry-run patch: no changes proposed.")
        } else if (cacheStatus.shouldRemoveIfNonDryRun) {
            removeConsumedCache(cacheFile)
        }
        exitProcess(0)
    }

    val dryRunOverlay = ConcurrentHashMap<Path, String>()
    val dryRunDiffs = mutableListOf<String>()

    val cacheAttachment = ContextAttachment(
        fileName = "completion-doc-cache-ref.txt",
        language = "text",
        code = "Use AttachmentRequest(\"completion-doc-cache.json\") to read the current cache contents.",
        description = "Generated completion docs cache at ${cacheFile.absolutePathString()}."
    )
    val sourceManifest = buildSourceManifest(workspaceRoot, srcDir)
    val manifestAttachment = ContextAttachment(
        fileName = "src-manifest.txt",
        language = "text",
        code = sourceManifest,
        description = "Kotlin source files under ${workspaceRoot.relativizeOrAbsolute(srcDir)}. Use AttachmentRequest with these paths to read full files."
    )

    val indexForTools = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
    val bridge = OpenAiIdeCodexBridge(model = options.model, log = CliLog(options.verbose))
    bridge.registerTool(ReplaceInFileRequest::class.java) { _, req ->
        replaceInFile(workspaceRoot, srcDir, cacheFile, dryRunOverlay, dryRunDiffs, req, options.dryRun)
    }
    val instructions = buildString {
        appendLine(OpenAiIdeCodexBridge.defaultChatThreadInstructions.trim())
        appendLine()
        appendLine("You are running as a freestanding AField completion-doc polish tool.")
        appendLine("You may read the generated completion-doc cache and source files under src/.")
        appendLine("You may edit only source files under src/ and the generated completion-doc cache file.")
        appendLine("Use AttachmentRequest to inspect specific files from src-manifest.txt.")
        appendLine("Prefer ReplaceInFileRequest for small KDoc edits. Use EditFile only as a last resort when exact snippet replacement is impossible.")
        appendLine("Do not make behavior changes. Keep Kotlin signatures intact.")
        appendLine("Prefer integrating useful generated docs into real KDoc near source declarations.")
        appendLine("Keep KDoc concise and specific. Remove or rewrite cache entries only when they are obsolete, duplicated, or clearly superseded by source KDoc.")
        appendLine("Make small, reviewable documentation edits. Avoid rewriting implementation bodies or unrelated formatting.")
        if (options.dryRun) {
            appendLine("This is a dry run. ReplaceInFileRequest and EditFile update an in-memory overlay; later AttachmentRequest reads see that overlay. Do not reread files just to check whether disk changed.")
        }
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
            resolveAttachment(workspaceRoot, srcDir, cacheFile, dryRunOverlay, requested)
        },
        listFileContents = {
            ListContentsResult(
                contents = listOf(
                    ListContentEntry(
                        fileRef = "completion-doc-cache.json",
                        property = "cache",
                        language = "json",
                        content = readTextWithOverlay(cacheFile, dryRunOverlay),
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
            applyEdit(workspaceRoot, srcDir, cacheFile, dryRunOverlay, dryRunDiffs, req, options.dryRun)
        },
        workspaceSymbolSearch = if (!indexForTools?.definitions.isNullOrEmpty()) {
            workspaceSymbolSearchFromIndex()
        } else {
            { _: WorkspaceSymbolSearch -> WorkspaceSymbolSearchResult(emptyList()) }
        },
        agentMemory = AgentMemoryState()
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
        onProgress = if (options.verbose) {
            { progress ->
                val body = progress.body.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                println("\n[progress] ${progress.title}$body")
            }
        } else null
    )

    println()
    if (result.commentary.isNotEmpty()) {
        println("[commentary]")
        println(result.commentary.joinToString("\n"))
    }

    if (!options.dryRun) {
        removeConsumedCache(cacheFile)
    } else {
        writeDryRunPatch(diffFile, dryRunDiffs)
    }
    exitProcess(0)
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
            "--verbose" -> options = options.copy(verbose = true)
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
            "--diff-file" -> {
                options = options.copy(diffFile = Paths.get(args.valueAfter(i, arg)))
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
  ./gradlew polishCompletionDocsDryRun

Options:
  --cache-file <path>        Generated docs cache. Defaults to ~/.afield/filestore/generated_completion_docs.json
  --src-dir <path>           Source root relative to the repo. Defaults to src
  --model <model>            Model to use. Defaults to AFIELD_COMPLETION_DOC_POLISH_MODEL or AFIELD_AI_MODEL/flagship.
  --dry-run                  Let the model request edits, but do not write them. Pass as --args="--dry-run"; bare Gradle --dry-run skips the task.
  --verbose                  Show bridge internals and per-tool progress.
  --prompt-file <path>       Use a custom user prompt.
  --diff-file <path>         Dry-run patch output. Defaults to build/completion-doc-polish-dry-run.patch.
  --max-tool-rounds <n>      Defaults to 40.
  --max-output-tokens <n>    Defaults to 24000.

The tool exposes ReplaceInFileRequest for small exact snippet patches and asks the model to prefer it over full-file EditFile.
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
6. Use ReplaceInFileRequest for small, exact KDoc/documentation patches. Use EditFile only when a small exact replacement cannot work.
7. Clean the cache file if entries are clearly obsolete, low quality, or superseded by KDoc you moved into source.
8. Stop after a coherent first pass; summarize the files changed and any declarations you intentionally left for later.

After a successful non-dry-run, this CLI deletes the generated completion-doc cache file so consumed fast-model work is not applied twice.
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

private fun completionDocCacheStatus(cacheFile: Path): CompletionDocCacheStatus {
    if (!Files.exists(cacheFile)) {
        return CompletionDocCacheStatus(false, "cache file does not exist.")
    }
    val text = readTextIfExists(cacheFile)
    if (text.isBlank()) {
        return CompletionDocCacheStatus(false, "cache file is blank.", shouldRemoveIfNonDryRun = true)
    }
    val node = runCatching { OpenAiFileStoreCache.defaultMapper().readTree(text) }
        .getOrElse { error ->
            return CompletionDocCacheStatus(
                false,
                "cache file is not valid JSON (${error.message}).",
                shouldRemoveIfNonDryRun = false
            )
        }
    val records = node.get("records")
    val recordCount = if (records != null && records.isObject) records.size() else 0
    if (recordCount <= 0) {
        return CompletionDocCacheStatus(false, "cache has no records.", shouldRemoveIfNonDryRun = true)
    }
    return CompletionDocCacheStatus(true, "cache has $recordCount record(s).")
}

private fun resolveAttachment(
    workspaceRoot: Path,
    srcDir: Path,
    cacheFile: Path,
    overlay: Map<Path, String>,
    requested: String
): AttachmentResponse? {
    val path = resolveAllowedPath(workspaceRoot, srcDir, cacheFile, requested) ?: return null
    val language = languageFor(path)
    return AttachmentResponse(
        file = workspaceRoot.relativizeOrAbsolute(path),
        language = language,
        content = readTextWithOverlay(path, overlay)
    )
}

private fun applyEdit(
    workspaceRoot: Path,
    srcDir: Path,
    cacheFile: Path,
    overlay: MutableMap<Path, String>,
    dryRunDiffs: MutableList<String>,
    req: EditFileRequest,
    dryRun: Boolean
): EditFileResponse {
    val path = resolveAllowedPath(workspaceRoot, srcDir, cacheFile, req.file)
        ?: return EditFileResponse(false, "Refusing to edit '${req.file}': only src/ and the completion-doc cache are writable.")
    val file = workspaceRoot.relativizeOrAbsolute(path)
    val response = applyContentChange(
        workspaceRoot = workspaceRoot,
        path = path,
        overlay = overlay,
        dryRunDiffs = dryRunDiffs,
        newContent = req.newContent,
        dryRun = dryRun,
        action = if (dryRun) "DRY RUN edit" else "Wrote"
    )
    return EditFileResponse(response.success, response.message)
}

private fun replaceInFile(
    workspaceRoot: Path,
    srcDir: Path,
    cacheFile: Path,
    overlay: MutableMap<Path, String>,
    dryRunDiffs: MutableList<String>,
    req: ReplaceInFileRequest,
    dryRun: Boolean
): ReplaceInFileResponse {
    val path = resolveAllowedPath(workspaceRoot, srcDir, cacheFile, req.file)
        ?: return ReplaceInFileResponse(false, "Refusing to patch '${req.file}': only src/ and the completion-doc cache are writable.", req.file)
    if (req.oldText.isEmpty()) {
        return ReplaceInFileResponse(false, "oldText must not be empty.", workspaceRoot.relativizeOrAbsolute(path))
    }
    if (req.expectedOccurrences < 1) {
        return ReplaceInFileResponse(false, "expectedOccurrences must be at least 1.", workspaceRoot.relativizeOrAbsolute(path))
    }
    val before = readTextWithOverlay(path, overlay)
    val occurrences = before.countNonOverlapping(req.oldText)
    val file = workspaceRoot.relativizeOrAbsolute(path)
    if (occurrences != req.expectedOccurrences) {
        return ReplaceInFileResponse(
            false,
            "Expected ${req.expectedOccurrences} occurrence(s), found $occurrences in $file.",
            file,
            occurrences
        )
    }
    val after = before.replace(req.oldText, req.newText)
    val response = applyContentChange(
        workspaceRoot = workspaceRoot,
        path = path,
        overlay = overlay,
        dryRunDiffs = dryRunDiffs,
        newContent = after,
        dryRun = dryRun,
        action = if (dryRun) "DRY RUN patch" else "Patched"
    )
    return ReplaceInFileResponse(
        response.success,
        response.message ?: "",
        file,
        occurrences,
        after.length
    )
}

private fun applyContentChange(
    workspaceRoot: Path,
    path: Path,
    overlay: MutableMap<Path, String>,
    dryRunDiffs: MutableList<String>,
    newContent: String,
    dryRun: Boolean,
    action: String
): EditFileResponse {
    val file = workspaceRoot.relativizeOrAbsolute(path)
    if (dryRun) {
        val before = readTextWithOverlay(path, overlay)
        val diff = unifiedDiff(file, before, newContent)
        overlay[path] = newContent
        println("$action: $file (${newContent.length} chars, overlay updated)")
        if (diff.isBlank()) {
            println("DRY RUN diff: no textual changes for $file")
        } else {
            dryRunDiffs += diff
            println(diff.trimEnd())
        }
        return EditFileResponse(true, "Dry run: accepted $file (${newContent.length} chars); no disk write; subsequent AttachmentRequest reads use the in-memory overlay.")
    }
    Files.createDirectories(path.parent)
    Files.writeString(path, newContent, StandardCharsets.UTF_8)
    println("$action $file (${newContent.length} chars).")
    return EditFileResponse(true, "$action $file (${newContent.length} chars).")
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

private fun readTextWithOverlay(path: Path, overlay: Map<Path, String>): String =
    overlay[path.toAbsolutePath().normalize()] ?: readTextIfExists(path)

private fun String.countNonOverlapping(needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var start = 0
    while (true) {
        val next = indexOf(needle, start)
        if (next < 0) return count
        count++
        start = next + needle.length
    }
}

private fun unifiedDiff(displayFile: String, before: String, after: String): String {
    if (before == after) return ""
    val tempDir = Files.createTempDirectory("completion-doc-polish-diff")
    return try {
        val beforeFile = tempDir.resolve("before")
        val afterFile = tempDir.resolve("after")
        Files.writeString(beforeFile, before, StandardCharsets.UTF_8)
        Files.writeString(afterFile, after, StandardCharsets.UTF_8)
        val process = ProcessBuilder(
            "git",
            "diff",
            "--no-index",
            "--src-prefix=a/",
            "--dst-prefix=b/",
            "--",
            beforeFile.toString(),
            afterFile.toString()
        )
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        process.waitFor()
        normalizeDiffHeaders(text, displayFile).ifBlank {
            fallbackFullFileDiff(displayFile, before, after)
        }
    } catch (_: Exception) {
        fallbackFullFileDiff(displayFile, before, after)
    } finally {
        deleteRecursively(tempDir)
    }
}

private fun normalizeDiffHeaders(diff: String, displayFile: String): String {
    if (diff.isBlank()) return ""
    return diff.lineSequence().joinToString("\n") { line ->
        when {
            line.startsWith("diff --git ") -> "diff --git a/$displayFile b/$displayFile"
            line.startsWith("--- ") -> "--- a/$displayFile"
            line.startsWith("+++ ") -> "+++ b/$displayFile"
            else -> line
        }
    }.trimEnd() + "\n"
}

private fun fallbackFullFileDiff(displayFile: String, before: String, after: String): String {
    fun removed(text: String): String = text.lineSequence().joinToString("\n") { "-$it" }
    fun added(text: String): String = text.lineSequence().joinToString("\n") { "+$it" }
    return buildString {
        appendLine("diff --git a/$displayFile b/$displayFile")
        appendLine("--- a/$displayFile")
        appendLine("+++ b/$displayFile")
        appendLine("@@")
        if (before.isNotEmpty()) appendLine(removed(before))
        if (after.isNotEmpty()) appendLine(added(after))
    }
}

private fun writeDryRunPatch(diffFile: Path, dryRunDiffs: List<String>) {
    if (dryRunDiffs.isEmpty()) {
        Files.deleteIfExists(diffFile)
        println("Dry-run patch: no changes proposed.")
        return
    }
    Files.createDirectories(diffFile.parent)
    Files.writeString(
        diffFile,
        dryRunDiffs.joinToString(separator = "\n") { it.trimEnd() } + "\n",
        StandardCharsets.UTF_8
    )
    println("Dry-run patch written: ${diffFile.absolutePathString()}")
}

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

private fun removeConsumedCache(cacheFile: Path) {
    runCatching {
        if (Files.deleteIfExists(cacheFile)) {
            println("Removed consumed completion doc cache: ${cacheFile.absolutePathString()}")
        } else {
            println("Completion doc cache already absent: ${cacheFile.absolutePathString()}")
        }
    }.onFailure {
        println("Warning: failed to remove consumed completion doc cache ${cacheFile.absolutePathString()}: ${it.message}")
    }
}

private fun Path.relativizeOrAbsolute(path: Path): String =
    try {
        relativize(path).pathString
    } catch (_: IllegalArgumentException) {
        path.absolutePathString()
    }

private class CliLog(private val verbose: Boolean) : LogSink {
    override fun log(event: LogEvent) {
        if (!verbose && !event.channel.startsWith("error") && !event.channel.startsWith("warning")) return
        val channel = if (event.channel.isBlank()) "" else "[${event.channel}] "
        println("[completion-doc-polish] $channel${event.message}")
    }
}
