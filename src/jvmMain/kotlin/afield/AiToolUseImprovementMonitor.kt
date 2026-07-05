package afield

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

object AiToolUseImprovementMonitor {
    private const val MAX_LOG_CHARS = 180_000
    private const val BROADCAST_MIN_INTERVAL_MS = 220L
    private const val RECENT_LOG_COUNT = 12

    private val lock = Any()
    private val logDir: Path = Paths.get(
        System.getProperty("afield.ai4.toolIoLog.path")
            ?: System.getenv("AFIELD_AI4_TOOL_IO_LOG_PATH")
            ?: Paths.get(System.getProperty("user.home"), ".afield", "logs", "ai4_tool_io").toString()
    ).toAbsolutePath().normalize()
    private val reportPath: Path = Paths.get("build", "ai-tool-use-improvement-report.md").toAbsolutePath().normalize()
    private val dryRunPatchPath: Path = Paths.get("build", "ai-tool-use-improvement-dry-run.patch").toAbsolutePath().normalize()

    private var runState = MutableRunState(dryRun = true)
    private var process: Process? = null

    @Volatile
    private var lastBroadcastEpochMs = 0L

    fun status(): AiToolUseImprovementStatus =
        synchronized(lock) { runState.toStatus(logSummary(), worktreeSummary()) }

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
            val appArgs = if (dryRun) {
                "--dry-run --verbose --max-log-files $RECENT_LOG_COUNT"
            } else {
                "--verbose --max-log-files $RECENT_LOG_COUNT"
            }
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
                proposal?.let {
                    AgentWorktreeManager.updateProposal(
                        projectRoot,
                        it.id,
                        if (exitCode == 0) "ready" else "failed",
                        if (exitCode == 0) "$taskName finished in this generated worktree." else "$taskName exited with $exitCode.",
                        reportPath = File(runDirectory, "build/ai-tool-use-improvement-report.md").absolutePath,
                        dryRunPatchPath = File(runDirectory, "build/ai-tool-use-improvement-dry-run.patch").absolutePath
                    )
                }
                if (exitCode != 0) failureCount++
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

        val files = runCatching {
            Files.list(logDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".jsonl", ignoreCase = true) }
                    .toList()
            }
        }.getOrDefault(emptyList())
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
                logTail = log.toString()
            )
    }
}
