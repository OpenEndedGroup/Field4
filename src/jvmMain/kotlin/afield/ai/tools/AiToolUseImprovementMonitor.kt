package afield.ai.tools

import afield.app.server.home
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.output.markdown
import afield.ui.output.md
import afield.ui.output.transcript

import afield.ai.conversation.aiChatCodeBlockHtml
import afield.ai.conversation.aiChatHistoryHtml
import afield.ai.conversation.aiChatMarkdownHtml
import afield.app.server.broadcastWelcomeEvent
import afield.audio.synth.*
import afield.data.table.get
import afield.document.workspace.AgentWorktreeProposal
import afield.document.workspace.AiToolUseImprovementStatus
import afield.document.workspace.AiToolUseImprovementView
import afield.document.workspace.AiToolUseLogSummary
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.tools.automation.AgentWorktreeManager
import afield.tools.docs.format
import afield.ui.output.logs
import afield.ui.output.templates.filter
import afield.ui.output.transcriptHtml
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

/**
 * The runner behind the AI tool-use improvement task, which summarises the AI4 tool input/output logs and starts the Gradle task that proposes fixes, optionally in a generated worktree.
 *
 * Logs are read from `~/.afield/logs/ai4_tool_io` (or `AFIELD_AI4_TOOL_IO_LOG_PATH`); the report,
 * dry-run patch and transcript are written under this checkout's `build/`.
 */

object AiToolUseImprovementMonitor {
    private const val MAX_LOG_CHARS = 180_000
    private const val MAX_ARTIFACT_CHARS = 220_000
    private const val BROADCAST_MIN_INTERVAL_MS = 220L
    private const val RECENT_LOG_COUNT = 12
    private val archiveFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    private val lock = Any()
    private val logDir: Path = Paths.get(
        System.getProperty("afield.ai4.toolIoLog.path")
            ?: System.getenv("AFIELD_AI4_TOOL_IO_LOG_PATH")
            ?: Paths.get(System.getProperty("user.home"), ".afield", "logs", "ai4_tool_io").toString()
    ).toAbsolutePath().normalize()
    private val reportPath: Path = Paths.get("build", "ai-tool-use-improvement-report.md").toAbsolutePath().normalize()
    private val dryRunPatchPath: Path = Paths.get("build", "ai-tool-use-improvement-dry-run.patch").toAbsolutePath().normalize()
    // Absolute and always in this checkout's build/, so a proposal running in a generated worktree shows here too.
    private val transcriptPath: Path = Paths.get("build", "ai-tool-use-improvement-transcript.md").toAbsolutePath().normalize()

    private var runState = MutableRunState(dryRun = true)
    private var process: Process? = null

    @Volatile
    private var lastBroadcastEpochMs = 0L

    fun status(): AiToolUseImprovementStatus =
        synchronized(lock) { runState.toStatus(logSummary(), worktreeSummary()) }

    fun view(): AiToolUseImprovementView {
        val patch = artifactText(dryRunPatchPath)
        return AiToolUseImprovementView(
            viewStamp = viewStamp(),
            transcriptHtml = artifactText(transcriptPath).takeIf { it.isNotBlank() }
                ?.let { aiChatHistoryHtml(it, afield.ai.conversation.chatlog.ChatToolFamilies.default) }.orEmpty(),
            reportHtml = artifactText(reportPath).takeIf { it.isNotBlank() }?.let { aiChatMarkdownHtml(it) }.orEmpty(),
            patchHtml = patch.takeIf { it.isNotBlank() }
                ?.let { "<div class=\"ai-chat-markdown\">${aiChatCodeBlockHtml(it, "diff")}</div>" }.orEmpty()
        )
    }

    private fun viewStamp(): String = listOf(transcriptPath, reportPath, dryRunPatchPath).joinToString("|") { path ->
        runCatching { "${path.getLastModifiedTime().toMillis()}:${path.fileSize()}" }.getOrDefault("-")
    }

    fun start(dryRun: Boolean = true, proposalCount: Int = 1): AiToolUseImprovementStatus {
        synchronized(lock) {
            if (runState.running) {
                return runState.copy(message = "AI tool-use improvement task is already running.")
                    .toStatus(logSummary(), worktreeSummary())
            }

            val logs = logSummary()
            if (!logs.exists || logs.recentLogCount <= 0) {
                runState = MutableRunState(
                    status = "idle",
                    dryRun = dryRun,
                    message = logs.message.ifBlank { "No AI tool I/O logs are ready to review." }
                )
                return runState.toStatus(logs, worktreeSummary())
            }

            val taskName = if (dryRun) "improveAiToolUseDryRun" else "improveAiToolUse"
            val transcriptArg = "--transcript-file ${transcriptPath.absolutePathString()}"
            val appArgs = if (dryRun) {
                "--dry-run --verbose --max-log-files $RECENT_LOG_COUNT $transcriptArg"
            } else {
                "--verbose --max-log-files $RECENT_LOG_COUNT $transcriptArg"
            }
            // The last run's transcript stays on the page until this one starts writing its own.
            runCatching { Files.deleteIfExists(transcriptPath) }
            runState = MutableRunState(
                running = true,
                status = "running",
                runId = UUID.randomUUID().toString(),
                taskName = taskName,
                dryRun = dryRun,
                startedAtEpochMs = Instant.now().toEpochMilli(),
                proposalCount = if (dryRun) 1 else proposalCount.coerceIn(1, 8),
                message = if (dryRun) "Starting $taskName..." else "Starting $taskName in generated worktree(s)..."
            )
            process = null
            appendLogLocked("Starting ./gradlew $taskName --args=\"$appArgs\"\n")
            broadcastWelcomeEvent("ai-tool-use-improvement")
            thread(name = "ai-tool-use-improvement-monitor", isDaemon = true) {
                runProcess(taskName, appArgs, runState.proposalCount)
            }
            return runState.toStatus(logs, worktreeSummary())
        }
    }

    fun clearReviewed(recentOnly: Boolean = true): AiToolUseImprovementStatus {
        synchronized(lock) {
            if (runState.running) {
                return runState.copy(message = "AI tool-use improvement task is running; logs were not moved.")
                    .toStatus(logSummary(), worktreeSummary())
            }
            if (!Files.isDirectory(logDir)) {
                runState = runState.copy(message = "No AI tool I/O log directory found.")
                return runState.toStatus(logSummary(), worktreeSummary())
            }
            val candidates = logFiles().let { files ->
                if (recentOnly) {
                    files.sortedByDescending { runCatching { it.getLastModifiedTime().toMillis() }.getOrDefault(0) }
                        .take(RECENT_LOG_COUNT)
                } else {
                    files
                }
            }
            if (candidates.isEmpty()) {
                runState = runState.copy(message = "No AI tool I/O logs to move.")
                return runState.toStatus(logSummary(), worktreeSummary())
            }

            val archiveDir = logDir
                .resolve("archived-reviewed")
                .resolve(archiveFormatter.format(Instant.now()))
                .toAbsolutePath()
                .normalize()
            Files.createDirectories(archiveDir)
            var moved = 0
            candidates.forEach { source ->
                val target = uniqueArchiveTarget(archiveDir, source.fileName.toString())
                runCatching {
                    Files.move(source, target)
                    moved += 1
                }
            }
            runState = runState.copy(
                status = "idle",
                message = "Moved $moved reviewed AI tool I/O log(s) to ${archiveDir.absolutePathString()}."
            )
            runState.log.clear()
            appendLogLocked(runState.message + "\n")
        }
        broadcastWelcomeEvent("ai-tool-use-improvement")
        return status()
    }

    private fun runProcess(taskName: String, appArgs: String, proposalCount: Int) {
        val projectRoot = File(".").canonicalFile
        val totalRuns = if (taskName == "improveAiToolUseDryRun") 1 else proposalCount.coerceIn(1, 8)
        var failureCount = 0
        var completedCount = 0
        try {
            repeat(totalRuns) { index ->
                val proposal = if (taskName == "improveAiToolUse") {
                    val created = AgentWorktreeManager.createProposal(
                        projectRoot = projectRoot,
                        taskName = "ai-tool-use",
                        label = "AI tool-use proposal ${index + 1}"
                    )
                    synchronized(lock) {
                        runState = runState.copy(
                            proposalIds = runState.proposalIds + created.id,
                            message = "$taskName will run in ${created.path}."
                        )
                    }
                    appendLog("Generated worktree ${created.id}: ${created.path}\n")
                    created
                } else null
                val runDirectory = proposal?.path?.let { File(it).canonicalFile } ?: projectRoot
                val command = listOf(gradleWrapperCommand(runDirectory), taskName, "--args=$appArgs")
                val startedProcess = ProcessBuilder(command)
                    .directory(runDirectory)
                    .redirectErrorStream(true)
                    .start()
                synchronized(lock) {
                    process = startedProcess
                    runState = runState.copy(
                        message = if (proposal == null) {
                            "$taskName is running."
                        } else {
                            "$taskName is running in generated worktree ${proposal.id}."
                        }
                    )
                }
                proposal?.let {
                    AgentWorktreeManager.updateProposal(
                        projectRoot,
                        it.id,
                        "running",
                        "$taskName is running."
                    )
                }
                broadcastWelcomeEvent("ai-tool-use-improvement")

                startedProcess.inputStream.reader(StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        appendLog(String(buffer, 0, read))
                    }
                }

                val exitCode = startedProcess.waitFor()
                completedCount++
                synchronized(lock) {
                    process = null
                }
                val proposalFinalize = if (proposal != null && exitCode == 0) {
                    AgentWorktreeManager.finalizeProposalChanges(projectRoot, proposal.id)
                        .also { result ->
                            appendLog(
                                if (result.success) {
                                    "Committed generated worktree ${proposal.id}: ${result.message}\n"
                                } else {
                                    "Could not commit generated worktree ${proposal.id}: ${result.message}\n"
                                }
                            )
                        }
                } else null
                proposal?.let {
                    AgentWorktreeManager.updateProposal(
                        projectRoot,
                        it.id,
                        if (exitCode == 0 && proposalFinalize?.success != false) "ready" else "failed",
                        if (exitCode == 0) {
                            proposalFinalize?.message ?: "$taskName finished in this generated worktree."
                        } else {
                            "$taskName exited with $exitCode."
                        },
                        reportPath = File(runDirectory, "build/ai-tool-use-improvement-report.md").absolutePath,
                        dryRunPatchPath = File(runDirectory, "build/ai-tool-use-improvement-dry-run.patch").absolutePath
                    )
                }
                if (exitCode != 0 || proposalFinalize?.success == false) failureCount++
                synchronized(lock) {
                    runState = runState.copy(
                        exitCode = exitCode,
                        message = if (exitCode == 0) {
                            "$taskName finished run $completedCount of $totalRuns."
                        } else {
                            "$taskName run $completedCount of $totalRuns exited with $exitCode."
                        }
                    )
                    trimLogLocked()
                }
                broadcastWelcomeEvent("ai-tool-use-improvement")
            }
            synchronized(lock) {
                process = null
                runState = runState.copy(
                    running = false,
                    status = if (failureCount == 0) "succeeded" else "failed",
                    finishedAtEpochMs = Instant.now().toEpochMilli(),
                    message = if (failureCount == 0) {
                        "$taskName finished."
                    } else {
                        "$taskName finished with $failureCount failed run(s)."
                    }
                )
                trimLogLocked()
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                process = null
                appendLogLocked("\nFailed to run $taskName: ${t.javaClass.simpleName}: ${t.message.orEmpty()}\n")
                runState = runState.copy(
                    running = false,
                    status = "failed",
                    finishedAtEpochMs = Instant.now().toEpochMilli(),
                    exitCode = -1,
                    message = "Could not start $taskName: ${t.message.orEmpty()}"
                )
            }
        } finally {
            broadcastWelcomeEvent("ai-tool-use-improvement")
        }
    }

    private fun appendLog(text: String) {
        synchronized(lock) {
            appendLogLocked(text)
            trimLogLocked()
        }
        val now = System.currentTimeMillis()
        if (now - lastBroadcastEpochMs >= BROADCAST_MIN_INTERVAL_MS) {
            lastBroadcastEpochMs = now
            broadcastWelcomeEvent("ai-tool-use-improvement")
        }
    }

    private fun appendLogLocked(text: String) {
        runState.log.append(text)
    }

    private fun trimLogLocked() {
        val excess = runState.log.length - MAX_LOG_CHARS
        if (excess > 0) runState.log.delete(0, excess)
    }

    private fun logSummary(): AiToolUseLogSummary {
        if (!Files.isDirectory(logDir)) {
            return AiToolUseLogSummary(
                logDir = logDir.absolutePathString(),
                exists = false,
                reportPath = reportPath.absolutePathString(),
                dryRunPatchPath = dryRunPatchPath.absolutePathString(),
                message = "No AI tool I/O log directory found."
            )
        }

        val files = logFiles()
        if (files.isEmpty()) {
            return AiToolUseLogSummary(
                logDir = logDir.absolutePathString(),
                exists = true,
                reportPath = reportPath.absolutePathString(),
                dryRunPatchPath = dryRunPatchPath.absolutePathString(),
                message = "AI tool I/O log directory is empty."
            )
        }

        val sorted = files.sortedByDescending { runCatching { it.getLastModifiedTime().toMillis() }.getOrDefault(0) }
        val recent = sorted.take(RECENT_LOG_COUNT)
        val size = files.sumOf { runCatching { it.fileSize() }.getOrDefault(0) }
        val latest = runCatching { sorted.first().getLastModifiedTime().toMillis() }.getOrDefault(0)
        return AiToolUseLogSummary(
            logDir = logDir.absolutePathString(),
            exists = true,
            logCount = files.size,
            recentLogCount = recent.size,
            sizeBytes = size,
            lastModifiedEpochMs = latest,
            reportPath = reportPath.absolutePathString(),
            dryRunPatchPath = dryRunPatchPath.absolutePathString(),
            message = "${recent.size} recent AI tool I/O log(s) ready for review."
        )
    }

    private fun worktreeSummary(): List<AgentWorktreeProposal> =
        runCatching { AgentWorktreeManager.listProposals(File(".").canonicalFile) }.getOrDefault(emptyList())

    private fun logFiles(): List<Path> =
        runCatching {
            if (!Files.isDirectory(logDir)) return@runCatching emptyList()
            Files.list(logDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".jsonl", ignoreCase = true) }
                    .filter { !afield.ai.tools.tooluse.isToolUseImprovementLog(it) }
                    .toList()
            }
        }.getOrDefault(emptyList())

    private fun uniqueArchiveTarget(directory: Path, fileName: String): Path {
        var candidate = directory.resolve(fileName)
        if (!Files.exists(candidate)) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val suffix = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (Files.exists(candidate)) {
            candidate = directory.resolve("$stem-$index$suffix")
            index += 1
        }
        return candidate
    }

    private fun artifactText(path: Path): String =
        runCatching {
            if (!Files.isRegularFile(path)) return@runCatching ""
            val text = Files.readString(path, StandardCharsets.UTF_8)
            if (text.length <= MAX_ARTIFACT_CHARS) {
                text
            } else {
                text.take(MAX_ARTIFACT_CHARS) + "\n\n[truncated ${text.length - MAX_ARTIFACT_CHARS} chars]"
            }
        }.getOrDefault("")

    private fun gradleWrapperCommand(projectRoot: File): String =
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            File(projectRoot, "gradlew.bat").path
        } else {
            File(projectRoot, "gradlew").path
        }

    private data class MutableRunState(
        val running: Boolean = false,
        val status: String = "idle",
        val runId: String = "",
        val taskName: String = "",
        val dryRun: Boolean = true,
        val proposalCount: Int = 1,
        val proposalIds: List<String> = emptyList(),
        val startedAtEpochMs: Long = 0,
        val finishedAtEpochMs: Long = 0,
        val exitCode: Int? = null,
        val message: String = "",
        val log: StringBuilder = StringBuilder()
    ) {
        fun toStatus(logs: AiToolUseLogSummary, worktrees: List<AgentWorktreeProposal>): AiToolUseImprovementStatus =
            AiToolUseImprovementStatus(
                logs = logs,
                worktrees = worktrees,
                running = running,
                status = status,
                runId = runId,
                taskName = taskName,
                dryRun = dryRun,
                startedAtEpochMs = startedAtEpochMs,
                finishedAtEpochMs = finishedAtEpochMs,
                exitCode = exitCode,
                message = message,
                logTail = log.toString(),
                reportMarkdown = artifactText(reportPath),
                dryRunPatch = artifactText(dryRunPatchPath),
                transcriptPath = transcriptPath.absolutePathString(),
                viewStamp = viewStamp()
            )
    }
}
