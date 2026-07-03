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
        synchronized(lock) { runState.toStatus(logSummary()) }

    fun start(dryRun: Boolean = true): AiToolUseImprovementStatus {
        synchronized(lock) {
            if (runState.running) {
                return runState.copy(message = "AI tool-use improvement task is already running.")
                    .toStatus(logSummary())
            }

            val logs = logSummary()
            if (!logs.exists || logs.recentLogCount <= 0) {
                runState = MutableRunState(
                    status = "idle",
                    dryRun = dryRun,
                    message = logs.message.ifBlank { "No AI tool I/O logs are ready to review." }
                )
                return runState.toStatus(logs)
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
                message = "Starting $taskName..."
            )
            process = null
            appendLogLocked("Starting ./gradlew $taskName --args=\"$appArgs\"\n")
            broadcastWelcomeEvent("ai-tool-use-improvement")
            thread(name = "ai-tool-use-improvement-monitor", isDaemon = true) {
                runProcess(taskName, appArgs)
            }
            return runState.toStatus(logs)
        }
    }

    private fun runProcess(taskName: String, appArgs: String) {
        val command = listOf(gradleWrapperCommand(), taskName, "--args=$appArgs")
        try {
            val startedProcess = ProcessBuilder(command)
                .directory(File(".").canonicalFile)
                .redirectErrorStream(true)
                .start()
            synchronized(lock) {
                process = startedProcess
                runState = runState.copy(message = "$taskName is running.")
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
            synchronized(lock) {
                process = null
                runState = runState.copy(
                    running = false,
                    status = if (exitCode == 0) "succeeded" else "failed",
                    finishedAtEpochMs = Instant.now().toEpochMilli(),
                    exitCode = exitCode,
                    message = if (exitCode == 0) "$taskName finished." else "$taskName exited with $exitCode."
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

    private fun gradleWrapperCommand(): String =
        if (System.getProperty("os.name").lowercase().contains("windows")) ".\\gradlew.bat" else "./gradlew"

    private data class MutableRunState(
        val running: Boolean = false,
        val status: String = "idle",
        val runId: String = "",
        val taskName: String = "",
        val dryRun: Boolean = true,
        val startedAtEpochMs: Long = 0,
        val finishedAtEpochMs: Long = 0,
        val exitCode: Int? = null,
        val message: String = "",
        val log: StringBuilder = StringBuilder()
    ) {
        fun toStatus(logs: AiToolUseLogSummary): AiToolUseImprovementStatus =
            AiToolUseImprovementStatus(
                logs = logs,
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
