package afield

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

object CompletionDocPolishMonitor {
    private const val MAX_LOG_CHARS = 180_000
    private const val BROADCAST_MIN_INTERVAL_MS = 220L

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val cachePath: Path = Paths.get(
        System.getProperty("user.home"),
        ".afield",
        "filestore",
        "generated_completion_docs.json"
    )

    private var runState = MutableRunState()
    private var process: Process? = null
    @Volatile
    private var lastBroadcastEpochMs = 0L

    fun status(): CompletionDocPolishStatus =
        synchronized(lock) { runState.toStatus(cacheSummary()) }

    fun start(dryRun: Boolean = false): CompletionDocPolishStatus {
        synchronized(lock) {
            if (runState.running) {
                return runState.copy(message = "Completion-doc polish task is already running.")
                    .toStatus(cacheSummary())
            }

            val cache = cacheSummary()
            if (!cache.exists || cache.pendingDocCount <= 0) {
                runState = MutableRunState(
                    status = "idle",
                    message = cache.message.ifBlank { "No generated completion docs are ready to polish." }
                )
                return runState.toStatus(cache)
            }

            val taskName = if (dryRun) "polishCompletionDocsDryRun" else "polishCompletionDocs"
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
            appendLogLocked("Starting ./gradlew $taskName --args=\"${if (dryRun) "--dry-run --verbose" else "--verbose"}\"\n")
            broadcastWelcomeEvent("completion-doc-polish")
            thread(name = "completion-doc-polish-monitor", isDaemon = true) {
                runProcess(taskName, dryRun)
            }
            return runState.toStatus(cache)
        }
    }

    private fun runProcess(taskName: String, dryRun: Boolean) {
        val wrapper = gradleWrapperCommand()
        val appArgs = if (dryRun) "--dry-run --verbose" else "--verbose"
        val command = listOf(wrapper, taskName, "--args=$appArgs")
        try {
            val startedProcess = ProcessBuilder(command)
                .directory(File(".").canonicalFile)
                .redirectErrorStream(true)
                .start()
            synchronized(lock) {
                process = startedProcess
                runState = runState.copy(message = "$taskName is running.")
            }
            broadcastWelcomeEvent("completion-doc-polish")

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
            broadcastWelcomeEvent("completion-doc-polish")
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
            broadcastWelcomeEvent("completion-doc-polish")
        }
    }

    private fun appendLogLocked(text: String) {
        runState.log.append(text)
    }

    private fun trimLogLocked() {
        val excess = runState.log.length - MAX_LOG_CHARS
        if (excess > 0) {
            runState.log.delete(0, excess)
        }
    }

    private fun cacheSummary(): CompletionDocPolishCacheSummary {
        if (!Files.exists(cachePath)) {
            return CompletionDocPolishCacheSummary(
                cachePath = cachePath.absolutePathString(),
                exists = false,
                message = "No generated completion-doc cache found."
            )
        }

        return runCatching {
            val root = json.parseToJsonElement(Files.readString(cachePath, StandardCharsets.UTF_8)).jsonObject
            val records = root["records"]?.jsonObject.orEmpty()
            val docs = records.values.filter { value ->
                value.jsonObject["documentation"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
            }
            val sourceFiles = docs
                .mapNotNull { value -> value.jsonObject["sourcePath"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
                .toSet()
            CompletionDocPolishCacheSummary(
                cachePath = cachePath.absolutePathString(),
                exists = true,
                pendingDocCount = docs.size,
                pendingSourceFileCount = sourceFiles.size,
                sizeBytes = cachePath.fileSize(),
                lastModifiedEpochMs = cachePath.getLastModifiedTime().toMillis(),
                message = if (docs.isEmpty()) {
                    "Generated completion-doc cache has no polishable records."
                } else {
                    "${docs.size} docs across ${sourceFiles.size} files are ready."
                }
            )
        }.getOrElse { error ->
            CompletionDocPolishCacheSummary(
                cachePath = cachePath.absolutePathString(),
                exists = true,
                sizeBytes = runCatching { cachePath.fileSize() }.getOrDefault(0),
                lastModifiedEpochMs = runCatching { cachePath.getLastModifiedTime().toMillis() }.getOrDefault(0),
                message = "Could not read generated completion-doc cache: ${error.message.orEmpty()}"
            )
        }
    }

    private fun gradleWrapperCommand(): String =
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            ".\\gradlew.bat"
        } else {
            "./gradlew"
        }

    private data class MutableRunState(
        val running: Boolean = false,
        val status: String = "idle",
        val runId: String = "",
        val taskName: String = "",
        val dryRun: Boolean = false,
        val startedAtEpochMs: Long = 0,
        val finishedAtEpochMs: Long = 0,
        val exitCode: Int? = null,
        val message: String = "",
        val log: StringBuilder = StringBuilder()
    ) {
        fun toStatus(cache: CompletionDocPolishCacheSummary): CompletionDocPolishStatus =
            CompletionDocPolishStatus(
                cache = cache,
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
