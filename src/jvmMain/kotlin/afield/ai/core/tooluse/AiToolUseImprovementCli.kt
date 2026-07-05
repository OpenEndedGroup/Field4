package afield.ai.core.tooluse

import afield.ai.core.AgentMemoryState
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
import com.fasterxml.jackson.annotation.JsonClassDescription
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.system.exitProcess

private val defaultToolLogDir: Path = Paths.get(
    System.getenv("AFIELD_AI4_TOOL_IO_LOG_PATH")
        ?: System.getProperty("afield.ai4.toolIoLog.path")
        ?: Paths.get(System.getProperty("user.home"), ".afield", "logs", "ai4_tool_io").toString()
)

private data class ToolUseOptions(
    val logDir: Path = defaultToolLogDir,
    val srcDir: Path = Paths.get("src"),
    val model: String = System.getenv("AFIELD_TOOL_USE_IMPROVEMENT_MODEL")
        ?.takeIf { it.isNotBlank() }
        ?: afield.ai.core.Ai4ModelDefaults.flagshipModel(),
    val dryRun: Boolean = false,
    val verbose: Boolean = false,
    val maxLogFiles: Int = 12,
    val maxLogChars: Int = 120_000,
    val maxToolRounds: Int = 32,
    val maxOutputTokens: Long = 24_000,
    val promptFile: Path? = null,
    val reportFile: Path = Paths.get("build", "ai-tool-use-improvement-report.md"),
    val diffFile: Path = Paths.get("build", "ai-tool-use-improvement-dry-run.patch")
)

@JsonClassDescription("Patch an allowed AI4 source file by replacing an exact oldText snippet with newText. Prefer this over EditFile for small prompt/tool-description edits.")
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

private data class ToolLogFile(
    val ref: String,
    val path: Path,
    val bytes: Long,
    val modifiedEpochMs: Long,
    val text: String
)

fun main(args: Array<String>) {
    val options = parseOptions(args.toList()) ?: return
    val workspaceRoot = Paths.get("").toAbsolutePath().normalize()
    val logDir = options.logDir.toAbsolutePath().normalize()
    val srcDir = workspaceRoot.resolve(options.srcDir).normalize()
    val reportFile = workspaceRoot.resolve(options.reportFile).normalize()
    val diffFile = workspaceRoot.resolve(options.diffFile).normalize()
    val logFiles = recentToolLogs(logDir, options.maxLogFiles, options.maxLogChars)
    val sourceManifest = buildSourceManifest(workspaceRoot, srcDir)

    println("AI tool I/O log dir: ${logDir.absolutePathString()}")
    println("Source root: ${srcDir.absolutePathString()}")
    println("Recent logs: ${logFiles.size}")
    println("Model: ${options.model}")
    println("Report: ${reportFile.absolutePathString()}")
    if (options.dryRun) {
        println("Dry run: source edits update an in-memory overlay only.")
        println("Dry-run patch: ${diffFile.absolutePathString()}")
    }

    if (logFiles.isEmpty()) {
        Files.createDirectories(reportFile.parent)
        Files.writeString(
            reportFile,
            "# AI Tool-Use Improvement Report\n\nNo recent AI tool I/O logs were found in `${logDir.absolutePathString()}`.\n",
            StandardCharsets.UTF_8
        )
        if (options.dryRun) Files.deleteIfExists(diffFile)
        println("No recent logs to review.")
        exitProcess(0)
    }

    val summary = buildToolLogSummary(logDir, logFiles)
    val overlay = linkedMapOf<Path, String>()
    val dryRunDiffs = mutableListOf<String>()

    val attachments = buildList {
        add(ContextAttachment("tool-log-summary.md", "markdown", summary, "Digest of recent AI4 tool I/O JSONL logs."))
        add(
            ContextAttachment(
                "src-manifest.txt",
                "text",
                sourceManifest,
                "Editable source files under ${workspaceRoot.relativizeOrAbsolute(srcDir)}. Use AttachmentRequest with these paths to read full files."
            )
        )
        logFiles.forEach { log ->
            add(ContextAttachment(log.ref, "jsonl", "Use AttachmentRequest(\"${log.ref}\") to read this log.", "AI4 tool I/O log ${log.path.fileName}."))
        }
    }

    val bridge = OpenAiIdeCodexBridge(model = options.model, log = CliLog(options.verbose))
    bridge.registerTool(ReplaceInFileRequest::class.java) { _, req ->
        replaceInFile(workspaceRoot, srcDir, overlay, dryRunDiffs, req, options.dryRun)
    }

    val instructions = buildString {
        appendLine(OpenAiIdeCodexBridge.defaultChatThreadInstructions.trim())
        appendLine()
        appendLine("You are running as a freestanding AField AI tool-use improvement task.")
        appendLine("Your job is to learn from recent AI4 tool-call transcripts and prototype Field source improvements so future agents need fewer rounds and fewer risky probes.")
        appendLine("Use AttachmentRequest for raw logs or source files when the digest is not enough.")
        appendLine("You may edit only files under src/ in this checkout. In apply mode this checkout is normally a generated git worktree, not the user's main checkout.")
        appendLine("Prefer ReplaceInFileRequest for small exact source changes. Use EditFile only if exact snippet replacement is impossible.")
        appendLine("Prioritize changes that reduce repeated failed tool calls, stale Field API assumptions, bad macro target mapping, unclear tool-result interpretation, maxToolRounds failures, or missing runtime diagnostics.")
        appendLine("Do not remove the guidance that the current Field box is `thisBox` and not `_`, `_.foo`, or bare `box`.")
        appendLine("Keep prototype edits small, reviewable, and grounded in specific log evidence. Prefer one coherent improvement per generated worktree.")
        if (options.dryRun) {
            appendLine("This is a dry run. ReplaceInFileRequest and EditFile update an in-memory overlay; no source files are written to disk.")
        }
    }.trim()

    val state = bridge.startThread(
        fileKey = "cli://ai-tool-use-improvement",
        fileText = "",
        instructions = instructions
    )

    val ctx = ToolExecutionContext(
        attachments = attachments,
        resolveAttachment = { requested ->
            resolveAttachment(workspaceRoot, srcDir, overlay, logFiles, summary, sourceManifest, requested)
        },
        listFileContents = {
            ListContentsResult(
                contents = buildList {
                    add(
                        ListContentEntry(
                            fileRef = "tool-log-summary.md",
                            property = "summary",
                            language = "markdown",
                            content = summary,
                            boxName = "AI tool-use logs",
                            relation = "logs"
                        )
                    )
                    add(
                        ListContentEntry(
                            fileRef = "src-manifest.txt",
                            property = "manifest",
                            language = "text",
                            content = sourceManifest,
                            boxName = "Source manifest",
                            relation = "editable"
                        )
                    )
                }
            )
        },
        editOtherFile = { req ->
            applyEdit(workspaceRoot, srcDir, overlay, dryRunDiffs, req, options.dryRun)
        },
        agentMemory = AgentMemoryState()
    )

    val prompt = options.promptFile
        ?.let { readTextIfExists(it.toAbsolutePath().normalize()) }
        ?.takeIf { it.isNotBlank() }
        ?: defaultImprovementPrompt(logDir, logFiles, srcDir, workspaceRoot)

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

    writeReport(reportFile, summary, result, dryRunDiffs, options.dryRun)
    if (options.dryRun) writeDryRunPatch(diffFile, dryRunDiffs)
    exitProcess(0)
}

private fun parseOptions(args: List<String>): ToolUseOptions? {
    var options = ToolUseOptions()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> {
                printUsage()
                return null
            }
            "--log-dir" -> {
                options = options.copy(logDir = Paths.get(args.valueAfter(i, arg)))
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
            "--max-log-files" -> {
                options = options.copy(maxLogFiles = args.valueAfter(i, arg).toInt().coerceAtLeast(1))
                i++
            }
            "--max-log-chars" -> {
                options = options.copy(maxLogChars = args.valueAfter(i, arg).toInt().coerceAtLeast(1000))
                i++
            }
            "--max-tool-rounds" -> {
                options = options.copy(maxToolRounds = args.valueAfter(i, arg).toInt().coerceAtLeast(1))
                i++
            }
            "--max-output-tokens" -> {
                options = options.copy(maxOutputTokens = args.valueAfter(i, arg).toLong().coerceAtLeast(1000))
                i++
            }
            "--prompt-file" -> {
                options = options.copy(promptFile = Paths.get(args.valueAfter(i, arg)))
                i++
            }
            "--report-file" -> {
                options = options.copy(reportFile = Paths.get(args.valueAfter(i, arg)))
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

private fun List<String>.valueAfter(index: Int, option: String): String =
    getOrNull(index + 1) ?: error("$option requires a value.")

private fun printUsage() {
    println(
        """
Usage:
  ./gradlew improveAiToolUse --args="[...]"
  ./gradlew improveAiToolUseDryRun

Options:
  --log-dir <path>          AI4 tool I/O JSONL directory. Defaults to ~/.afield/logs/ai4_tool_io.
  --src-dir <path>          Source tree writable by the task. Defaults to src.
  --model <model>           Model to use. Defaults to AFIELD_TOOL_USE_IMPROVEMENT_MODEL or AFIELD_AI_MODEL/flagship.
  --dry-run                 Let the model request edits, but do not write source files.
  --verbose                 Show bridge internals and per-tool progress.
  --max-log-files <n>       Number of recent JSONL logs to include. Defaults to 12.
  --max-log-chars <n>       Max chars read from each log. Defaults to 120000.
  --prompt-file <path>      Use a custom user prompt.
  --report-file <path>      Markdown report. Defaults to build/ai-tool-use-improvement-report.md.
  --diff-file <path>        Dry-run patch output. Defaults to build/ai-tool-use-improvement-dry-run.patch.
  --max-tool-rounds <n>     Defaults to 32.
  --max-output-tokens <n>   Defaults to 24000.
        """.trimIndent()
    )
}

private fun defaultImprovementPrompt(
    logDir: Path,
    logs: List<ToolLogFile>,
    srcDir: Path,
    workspaceRoot: Path
): String = buildString {
    appendLine("Prototype AField source improvements from recent conversation/tool-call logs.")
    appendLine()
    appendLine("Inputs:")
    appendLine("- Tool I/O log directory: ${logDir.absolutePathString()}")
    appendLine("- Recent log count: ${logs.size}")
    appendLine("- Log digest is attached as tool-log-summary.md.")
    appendLine("- Source manifest is attached as src-manifest.txt.")
    appendLine()
    appendLine("Writable source root: ${workspaceRoot.relativizeOrAbsolute(srcDir)}")
    appendLine()
    appendLine("Task:")
    appendLine("1. Read the digest and inspect raw logs only when they contain evidence worth following.")
    appendLine("2. Identify a small Field source improvement that would reduce round trips, repeated failed calls, stale syntax probes, unclear macro/tool behavior, or missing diagnostics.")
    appendLine("3. Make at most a few small edits under src/. Prefer exact ReplaceInFileRequest patches.")
    appendLine("4. Preserve existing style and avoid unrelated formatting churn.")
    appendLine("5. In your final answer, summarize the evidence, the worktree-ready changes made or proposed, and any follow-up validation wanted.")
}.trim()

private fun recentToolLogs(logDir: Path, maxFiles: Int, maxChars: Int): List<ToolLogFile> {
    if (!Files.isDirectory(logDir)) return emptyList()
    val files = Files.list(logDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".jsonl", ignoreCase = true) }
            .sorted { a, b ->
                runCatching { b.getLastModifiedTime().toMillis().compareTo(a.getLastModifiedTime().toMillis()) }
                    .getOrDefault(0)
            }
            .limit(maxFiles.toLong())
            .toList()
    }
    return files.mapIndexed { index, path ->
        val full = readTextIfExists(path)
        val text = if (full.length <= maxChars) full else full.takeLast(maxChars)
        ToolLogFile(
            ref = "tool-log-${index + 1}.jsonl",
            path = path.toAbsolutePath().normalize(),
            bytes = runCatching { path.fileSize() }.getOrDefault(0),
            modifiedEpochMs = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(0),
            text = text
        )
    }
}

private fun buildToolLogSummary(logDir: Path, logs: List<ToolLogFile>): String {
    val toolCounts = linkedMapOf<String, Int>()
    val toolFailureCounts = linkedMapOf<String, Int>()
    var responseCount = 0
    var maxRoundCount = 0
    var exceptionCount = 0
    val notable = mutableListOf<String>()

    logs.forEach { log ->
        var calls = 0
        var results = 0
        var failures = 0
        log.text.lineSequence().forEach { line ->
            when {
                line.contains("\"event\":\"tool_call\"") -> {
                    calls++
                    val name = extractJsonString(line, "toolName").ifBlank { "unknown" }
                    toolCounts[name] = (toolCounts[name] ?: 0) + 1
                }
                line.contains("\"event\":\"tool_result\"") -> {
                    results++
                    val name = extractJsonString(line, "toolName").ifBlank { "unknown" }
                    val failed = line.contains("\\\"success\\\":false") ||
                        line.contains("\"success\":false") ||
                        line.contains("\\\"error\\\":true")
                    if (failed) {
                        failures++
                        toolFailureCounts[name] = (toolFailureCounts[name] ?: 0) + 1
                    }
                }
                line.contains("\"event\":\"tool_exception\"") -> {
                    exceptionCount++
                    notable += "${log.ref}: exception in ${extractJsonString(line, "toolName")}: ${extractJsonString(line, "message")}".take(360)
                }
                line.contains("\"event\":\"response\"") -> responseCount++
                line.contains("\"event\":\"max_tool_rounds\"") -> {
                    maxRoundCount++
                    notable += "${log.ref}: max tool rounds reached".take(360)
                }
            }
        }
        notable += "${log.ref}: ${calls} tool call(s), ${results} result(s), ${failures} failed result(s), ${log.bytes} bytes"
    }

    fun countLines(counts: Map<String, Int>): String =
        if (counts.isEmpty()) "- none\n" else counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString("") { "- ${it.key}: ${it.value}\n" }

    return buildString {
        appendLine("# AI4 Tool I/O Log Digest")
        appendLine()
        appendLine("- Log directory: `${logDir.absolutePathString()}`")
        appendLine("- Recent logs included: ${logs.size}")
        appendLine("- Response events: $responseCount")
        appendLine("- Max-tool-round events: $maxRoundCount")
        appendLine("- Tool exceptions: $exceptionCount")
        appendLine()
        appendLine("## Tool Calls")
        append(countLines(toolCounts))
        appendLine()
        appendLine("## Failed Tool Results")
        append(countLines(toolFailureCounts))
        appendLine()
        appendLine("## Included Logs")
        logs.forEach {
            appendLine("- `${it.ref}`: `${it.path.fileName}` (${it.bytes} bytes, modified ${it.modifiedEpochMs})")
        }
        appendLine()
        appendLine("## Notable Lines")
        notable.takeLast(80).forEach { appendLine("- $it") }
    }
}

private fun extractJsonString(line: String, key: String): String {
    val pattern = Regex(""""${Regex.escape(key)}":"((?:\\.|[^"\\])*)"""")
    val raw = pattern.find(line)?.groupValues?.getOrNull(1) ?: return ""
    return raw
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
}

private fun buildSourceManifest(workspaceRoot: Path, srcDir: Path): String {
    if (!Files.isDirectory(srcDir)) return "Source directory not found: ${srcDir.absolutePathString()}\n"
    val extensions = setOf(
        "kt", "kts", "java", "js", "mjs", "ts", "html", "css",
        "md", "txt", "json", "xml", "yaml", "yml",
        "glsl", "vert", "frag", "comp", "mesh", "task"
    )
    val files = Files.walk(srcDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { path ->
                val name = path.fileName.toString()
                path.extension.lowercase() in extensions ||
                    name.equals("AGENTS.md", ignoreCase = true)
            }
            .sorted()
            .map { workspaceRoot.relativizeOrAbsolute(it.toAbsolutePath().normalize()) }
            .toList()
    }
    return buildString {
        appendLine("Editable source manifest for AI tool-use improvement.")
        appendLine("Use AttachmentRequest with one of these file refs to read full content.")
        appendLine("Edits are accepted only for paths under ${workspaceRoot.relativizeOrAbsolute(srcDir)}.")
        appendLine()
        files.forEach { appendLine(it) }
    }
}

private fun resolveAttachment(
    workspaceRoot: Path,
    srcDir: Path,
    overlay: Map<Path, String>,
    logs: List<ToolLogFile>,
    summary: String,
    sourceManifest: String,
    requested: String
): AttachmentResponse? {
    val normalized = requested.trim().replace('\\', '/')
    if (normalized == "tool-log-summary.md") {
        return AttachmentResponse(normalized, "markdown", summary)
    }
    if (normalized == "src-manifest.txt") {
        return AttachmentResponse(normalized, "text", sourceManifest)
    }
    logs.firstOrNull { it.ref == normalized || it.path.fileName.toString() == normalized }?.let { log ->
        return AttachmentResponse(log.ref, "jsonl", log.text)
    }
    val path = resolveAllowedPath(workspaceRoot, srcDir, normalized) ?: return null
    if (!Files.isRegularFile(path) && path !in overlay) return null
    return AttachmentResponse(workspaceRoot.relativizeOrAbsolute(path), languageFor(path), readTextWithOverlay(path, overlay))
}

private fun replaceInFile(
    workspaceRoot: Path,
    srcDir: Path,
    overlay: MutableMap<Path, String>,
    dryRunDiffs: MutableList<String>,
    req: ReplaceInFileRequest,
    dryRun: Boolean
): ReplaceInFileResponse {
    val path = resolveAllowedPath(workspaceRoot, srcDir, req.file)
        ?: return ReplaceInFileResponse(false, "File is not editable by this task: ${req.file}. Only src/ is writable.", req.file)
    if (req.oldText.isEmpty()) {
        return ReplaceInFileResponse(false, "oldText must be non-empty.", workspaceRoot.relativizeOrAbsolute(path))
    }
    val before = readTextWithOverlay(path, overlay)
    val occurrences = before.countNonOverlapping(req.oldText)
    if (occurrences != req.expectedOccurrences) {
        return ReplaceInFileResponse(
            false,
            "Expected ${req.expectedOccurrences} occurrence(s), found $occurrences in ${workspaceRoot.relativizeOrAbsolute(path)}.",
            workspaceRoot.relativizeOrAbsolute(path),
            occurrences
        )
    }
    val after = before.replace(req.oldText, req.newText)
    val response = applyContentChange(workspaceRoot, path, overlay, dryRunDiffs, after, dryRun, if (dryRun) "DRY RUN patch" else "Patched")
    return ReplaceInFileResponse(
        response.success,
        response.message ?: "",
        workspaceRoot.relativizeOrAbsolute(path),
        occurrences,
        after.length
    )
}

private fun applyEdit(
    workspaceRoot: Path,
    srcDir: Path,
    overlay: MutableMap<Path, String>,
    dryRunDiffs: MutableList<String>,
    req: EditFileRequest,
    dryRun: Boolean
): EditFileResponse {
    val path = resolveAllowedPath(workspaceRoot, srcDir, req.file)
        ?: return EditFileResponse(false, "File is not editable by this task: ${req.file}. Only src/ is writable.")
    return applyContentChange(workspaceRoot, path, overlay, dryRunDiffs, req.newContent, dryRun, if (dryRun) "DRY RUN edit" else "Edited")
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
        if (diff.isNotBlank()) {
            dryRunDiffs += diff
            println(diff.trimEnd())
        }
        return EditFileResponse(true, "Dry run: accepted $file; no disk write; subsequent AttachmentRequest reads use the in-memory overlay.")
    }
    Files.createDirectories(path.parent)
    Files.writeString(path, newContent, StandardCharsets.UTF_8)
    println("$action $file (${newContent.length} chars).")
    return EditFileResponse(true, "$action $file (${newContent.length} chars).")
}

private fun resolveAllowedPath(workspaceRoot: Path, srcDir: Path, requested: String): Path? {
    val trimmed = requested.trim()
    val candidate = when {
        trimmed.startsWith("/") -> Paths.get(trimmed)
        else -> workspaceRoot.resolve(trimmed)
    }.toAbsolutePath().normalize()
    return candidate.takeIf { it.startsWith(srcDir) }
}

private fun languageFor(path: Path): String = when (path.extension.lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs" -> "javascript"
    "ts" -> "typescript"
    "html" -> "html"
    "css" -> "css"
    "md" -> "markdown"
    "json" -> "json"
    "yaml", "yml" -> "yaml"
    "glsl", "vert", "frag", "comp", "mesh", "task" -> "glsl"
    "jsonl" -> "jsonl"
    else -> "text"
}

private fun readTextIfExists(path: Path): String =
    if (Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else ""

private fun readTextWithOverlay(path: Path, overlay: Map<Path, String>): String =
    overlay[path.toAbsolutePath().normalize()] ?: readTextIfExists(path)

private fun String.countNonOverlapping(needle: String): Int {
    var count = 0
    var start = 0
    while (true) {
        val next = indexOf(needle, start)
        if (next < 0) return count
        count++
        start = next + needle.length
    }
}

private fun writeReport(
    reportFile: Path,
    summary: String,
    result: OpenAiIdeCodexBridge.SendResult,
    dryRunDiffs: List<String>,
    dryRun: Boolean
) {
    Files.createDirectories(reportFile.parent)
    Files.writeString(
        reportFile,
        buildString {
            appendLine("# AI Tool-Use Improvement Report")
            appendLine()
            appendLine("Mode: ${if (dryRun) "dry run" else "apply"}")
            appendLine("Response id: `${result.rawResponseId}`")
            appendLine("Tool calls made by improvement agent: ${result.toolTranscript.size}")
            appendLine("Dry-run patch hunks: ${dryRunDiffs.size}")
            appendLine()
            appendLine("## Log Digest")
            appendLine()
            appendLine(summary)
            appendLine()
            appendLine("## Agent Result")
            appendLine()
            appendLine(result.text.ifBlank { "_No final text._" })
            if (result.commentary.isNotEmpty()) {
                appendLine()
                appendLine("## Commentary")
                appendLine()
                appendLine(result.commentary.joinToString("\n\n"))
            }
            if (dryRunDiffs.isNotEmpty()) {
                appendLine()
                appendLine("## Dry-Run Patch")
                appendLine()
                appendLine("```diff")
                appendLine(dryRunDiffs.joinToString("\n") { it.trimEnd() })
                appendLine("```")
            }
        },
        StandardCharsets.UTF_8
    )
    println("Report written: ${reportFile.absolutePathString()}")
}

private fun unifiedDiff(displayFile: String, before: String, after: String): String {
    if (before == after) return ""
    val tempDir = Files.createTempDirectory("ai-tool-use-improvement-diff")
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
        println("[ai-tool-use-improvement] $channel${event.message}")
    }
}
