@file:OptIn(DelicateCoroutinesApi::class)

package afield

import afield.ai.Ai4CompletionDoc
import afield.ai.core.AI4_AUTO_NAME_PROPERTY
import afield.ai.core.AI4_AUTO_NAME_SIGNATURE_PROPERTY
import afield.ai.core.AiSettingsSaveRequest
import afield.ai.core.aiProviderStatus
import afield.ai.core.saveAiSettings
import afield.ai.core.completion.Ai4KotlinAutocomplete
import afield.ai.SemanticRetrievalQueryRequest
import afield.ai.SemanticRetrievalService
import afield.ai.SemanticRetrievalConfigSaveRequest
import afield.ai.SemanticRetrievalDeleteCacheRequest
import afield.ai.SemanticRetrievalDownloadRequest
import afield.debug.RuntimeDebugProbeService
import afield.four.stream.FieldRTTStreamer
import afield.four.util.FramePresentationLog
import afield.four.util.Options
import afield.graphics.getFLineEditSession
import afield.logger.lblock
import afield.logger.lprint
import afield.logger.lprintln
import afield.python.PythonInterface
import afield.python.PythonSettingsRequest
import afield.server.refreshFileProxyBoxes
import afield.server.installControllerRoutes
import afield.server.broadcastControllerControlUpdates
import afield.server.recordControllerOutputMessage
import afield.server.recordControllerSelection
import afield.server.writeFileProxyContents
import afield.stdlib.FLineIntersection
import afield.stdlib.begin
import com.sun.management.GarbageCollectionNotificationInfo
import com.aallam.openai.api.BetaOpenAI
import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import java.awt.Desktop
import java.io.File
import java.lang.management.ManagementFactory
import java.net.BindException
import java.net.URI
import java.net.ServerSocket
import java.net.URLEncoder
import java.nio.file.Paths
import java.security.KeyStore
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


/***
 */

private val serverStartedAtEpochMs = System.currentTimeMillis()

private object GcPauseMonitor {
    private const val sampleCapacity = 2048
    private val lock = Any()
    private val pauseDurationsMs = ArrayDeque<Long>()
    private val observedCount = AtomicLong(0)
    private val latestPauseMs = AtomicLong(0)

    @Volatile
    private var collectorCount = 0

    @Volatile
    private var monitoring = false

    init {
        start()
    }

    private fun start() {
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        collectorCount = collectors.size
        collectors.forEach { collector ->
            val emitter = collector as? NotificationEmitter ?: return@forEach
            emitter.addNotificationListener(
                { notification: Notification, _ ->
                    if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                        return@addNotificationListener
                    }
                    val userData = notification.userData as? CompositeData ?: return@addNotificationListener
                    val info = GarbageCollectionNotificationInfo.from(userData)
                    record(info.gcInfo.duration)
                },
                null,
                null
            )
        }
        monitoring = collectors.any { it is NotificationEmitter }
    }

    private fun record(durationMs: Long) {
        latestPauseMs.set(durationMs)
        observedCount.incrementAndGet()
        synchronized(lock) {
            pauseDurationsMs.addLast(durationMs)
            while (pauseDurationsMs.size > sampleCapacity) {
                pauseDurationsMs.removeFirst()
            }
        }
    }

    fun stats(): GcPauseStats {
        val snapshot = synchronized(lock) { pauseDurationsMs.toList() }
        if (snapshot.isEmpty()) {
            return GcPauseStats(
                monitoring = monitoring,
                collectorCount = collectorCount,
                observedCount = observedCount.get(),
                sampleCapacity = sampleCapacity,
                latestPauseMs = latestPauseMs.get()
            )
        }

        val sorted = snapshot.sorted()
        fun percentile(percent: Double): Double {
            val index = kotlin.math.ceil(percent * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[index].toDouble()
        }

        val median = if (sorted.size % 2 == 0) {
            val upper = sorted.size / 2
            (sorted[upper - 1] + sorted[upper]) / 2.0
        } else {
            sorted[sorted.size / 2].toDouble()
        }

        return GcPauseStats(
            monitoring = monitoring,
            collectorCount = collectorCount,
            observedCount = observedCount.get(),
            sampleCount = snapshot.size,
            sampleCapacity = sampleCapacity,
            medianPauseMs = median,
            p95PauseMs = percentile(0.95),
            maxPauseMs = sorted.last(),
            latestPauseMs = latestPauseMs.get()
        )
    }
}

private val gcPauseMonitor = GcPauseMonitor

private fun systemProperty(name: String): String = System.getProperty(name).orEmpty()

private fun booleanSystemProperty(name: String): Boolean =
    systemProperty(name).trim().lowercase() in setOf("1", "true", "yes", "on", "enabled")

private fun firstRuntimeArgValue(inputArguments: List<String>, prefix: String): String =
    inputArguments.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix).orEmpty()

private fun currentAotCacheStatus(inputArguments: List<String>): RuntimeAotCacheStatus {
    val activeCache = firstRuntimeArgValue(inputArguments, "-XX:AOTCache=")
    if (activeCache.isNotBlank()) {
        return RuntimeAotCacheStatus(
            state = "working",
            summary = "working",
            cachePath = activeCache,
            autoGenerate = booleanSystemProperty("afield.aot.autoGenerate"),
            attempted = true,
            detail = "JVM launched with -XX:AOTCache."
        )
    }

    val outputCache = firstRuntimeArgValue(inputArguments, "-XX:AOTCacheOutput=")
    if (outputCache.isNotBlank()) {
        return RuntimeAotCacheStatus(
            state = "rebuilding",
            summary = "rebuilding",
            cachePath = outputCache,
            autoGenerate = true,
            attempted = true,
            detail = "Training JVM is writing -XX:AOTCacheOutput."
        )
    }

    val state = systemProperty("afield.aot.state").ifBlank { "not-attempted" }
    val cachePath = systemProperty("afield.aot.cache.path")
    val summary = systemProperty("afield.aot.summary").ifBlank {
        when (state) {
            "disabled" -> "not attempted"
            "missing" -> "missing"
            "stale" -> "stale"
            else -> "not attempted"
        }
    }
    val detail = systemProperty("afield.aot.detail")

    return RuntimeAotCacheStatus(
        state = state,
        summary = summary,
        cachePath = cachePath,
        autoGenerate = booleanSystemProperty("afield.aot.autoGenerate"),
        attempted = booleanSystemProperty("afield.aot.attempted"),
        detail = detail
    )
}

private fun currentRuntimeInfo(): RuntimeInfo {
    val runtime = Runtime.getRuntime()
    val runtimeMxBean = ManagementFactory.getRuntimeMXBean()
    val inputArguments = runtimeMxBean.inputArguments
    return RuntimeInfo(
        javaVersion = systemProperty("java.version"),
        javaRuntimeName = systemProperty("java.runtime.name"),
        javaRuntimeVersion = systemProperty("java.runtime.version"),
        javaVmName = systemProperty("java.vm.name"),
        javaVmVendor = systemProperty("java.vm.vendor"),
        javaVmVersion = systemProperty("java.vm.version"),
        javaVendor = systemProperty("java.vendor"),
        javaHome = systemProperty("java.home"),
        runtimeVersion = Runtime.version().toString(),
        osName = systemProperty("os.name"),
        osVersion = systemProperty("os.version"),
        osArch = systemProperty("os.arch"),
        processId = ProcessHandle.current().pid(),
        uptimeMs = System.currentTimeMillis() - serverStartedAtEpochMs,
        inputArguments = inputArguments,
        availableProcessors = runtime.availableProcessors(),
        maxMemoryBytes = runtime.maxMemory(),
        totalMemoryBytes = runtime.totalMemory(),
        freeMemoryBytes = runtime.freeMemory(),
        aotCacheStatus = currentAotCacheStatus(inputArguments),
        gcPauseStats = gcPauseMonitor.stats(),
        runloopTimingStats = Runloop.runLoop.timingStats(),
        runloopSchedulingStatus = Runloop.runLoop.schedulingStatus()
    )
}

class SessionInformation(
    val filename: String,
    val documentName: String = filename,
    var boxes: ConcurrentHashMap<String, Box> = ConcurrentHashMap<String, Box>(),
    var root: Box,
    var sessions: MutableSet<WebSocketSession> = mutableSetOf<WebSocketSession>(),
    var topologyGeneration: Int = 0,
    var autorunTriggered: Boolean = false,
    val ephemeral: Boolean = false
) {
    @Volatile
    var loading: Boolean = false

    // Sessions the agent backdoor created/loaded. Used to skip AI auto box-naming for them — an
    // agent's boxes have deliberate names (e.g. window/sender) that the auto-namer must not rewrite.
    @Volatile
    var agentManaged: Boolean = false

    // Track sidecar-bearing properties that need to be written to disk on next save.
    val pendingSidecarSaves = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val pendingSidecarSaveVersions = ConcurrentHashMap<Pair<String, String>, Long>()
    private val propertySyncSequences = ConcurrentHashMap<Pair<String, String>, AtomicLong>()
    private val pendingPropertySyncOrigins = ConcurrentHashMap<Pair<String, String>, PropertySyncOrigin>()

    // Layers system state
    var visibleLayers: MutableSet<String> = mutableSetOf("default")
    var activeLayer: String? = null

    private var _fileWatchManager: FileWatchManager? = null

    val fileWatchManager: FileWatchManager
        get() {
            return _fileWatchManager ?: FileWatchManager(this).also {
                it.start()
                _fileWatchManager = it
            }
        }

    fun markSidecarPending(uid: String, property: String) {
        val key = uid to property
        pendingSidecarSaves += key
        pendingSidecarSaveVersions[key] = System.nanoTime()
    }

    fun hasPendingSidecarSave(uid: String, property: String): Boolean {
        return pendingSidecarSaves.contains(uid to property)
    }

    fun pendingSidecarSnapshot(): Map<Pair<String, String>, Long> {
        return pendingSidecarSaves.associateWith { pendingSidecarSaveVersions[it] ?: 0L }
    }

    fun clearSavedSidecars(snapshot: Map<Pair<String, String>, Long>) {
        snapshot.forEach { (key, version) ->
            if (pendingSidecarSaveVersions[key] == version) {
                pendingSidecarSaves.remove(key)
                pendingSidecarSaveVersions.remove(key)
            }
        }
    }

    fun recordPropertySyncOrigin(uid: String, property: String, value: String, clientId: String?, clientSequence: Long?) {
        if (clientId == null || clientSequence == null) return
        pendingPropertySyncOrigins[uid to property] = PropertySyncOrigin(value, clientId, clientSequence)
    }

    fun nextSetStringSync(uid: String, property: String, value: String): Set_string {
        val key = uid to property
        val sequence = propertySyncSequences
            .computeIfAbsent(key) { AtomicLong(0L) }
            .incrementAndGet()
        val origin = pendingPropertySyncOrigins[key]
            ?.takeIf { it.value == value }
            ?.also { pendingPropertySyncOrigins.remove(key, it) }
        return Set_string(
            uid = uid,
            property = property,
            value = value,
            syncSequence = sequence,
            clientId = origin?.clientId,
            clientSequence = origin?.clientSequence
        )
    }

    fun cleanup() {
        _fileWatchManager?.stop()
        _fileWatchManager = null
        // Dispose any afield.four renderer/window owned by this document's boxes so closing
        // a document (or detaching its last session) releases the native GLFW/Vulkan surface
        // instead of leaking it into Window.openWindows. Also dispose each box's Kotlin
        // engine (K2 REPL compilation state) so re-opening scratch documents in a long-lived
        // session does not leak FIR/metadata per box.
        boxes.values.forEach {
            disposeBoxRenderer(it)
            it.disposeKi()
        }
    }
}

private data class PropertySyncOrigin(
    val value: String,
    val clientId: String,
    val clientSequence: Long
)

private fun readWelcomeChangelog(limit: Int = 30): WelcomeChangelogResponse {
    return try {
        val process = ProcessBuilder(
            "git",
            "log",
            "--pretty=format:%h%x09%ct%x09%s",
            "-n",
            limit.coerceIn(1, 100).toString()
        )
            .directory(File("."))
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return WelcomeChangelogResponse(emptyList())
        }

        if (process.exitValue() != 0) {
            return WelcomeChangelogResponse(emptyList())
        }

        val entries = process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val committedEpochMs = (parts[1].toLongOrNull() ?: return@mapNotNull null) * 1000L
                WelcomeChangelogEntry(
                    hash = parts[0],
                    committedEpochMs = committedEpochMs,
                    summary = parts[2]
                )
            }.toList()
        }
        WelcomeChangelogResponse(entries)
    } catch (_: Exception) {
        WelcomeChangelogResponse(emptyList())
    }
}

fun updateBoxVisibility(box: Box, sessionInfo: SessionInformation) {
    val boxLayers = parseLayersList(box.layers)
    val isVisible = boxLayers.any { layer -> sessionInfo.visibleLayers.contains(layer) }
    box.visible = isVisible
}

fun parseLayersList(layersString: String): List<String> {
    return layersString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

fun SessionInformation.scheduleAutorunBoxes(delayMs: Long = 500L) {
    if (autorunTriggered) return
    autorunTriggered = true
    autorunBoxes(boxes.values, delayMs)
}

fun SessionInformation.autorunBoxes(targetBoxes: Collection<Box>, delayMs: Long = 500L) {
    GlobalScope.launch {
        delay(delayMs)

        // Wait for all boxes to be online before starting autorun
        val allBoxes = boxes.values.filter { it.id != "root" && !it.getPropertyRaw("_tombstone").isTrue() }
        var attempts = 0
        while (allBoxes.any { it.offline } && attempts < 100) {
            delay(100)
            attempts++
        }

        targetBoxes.filter { it.autorun.isTrue() && it.id != "root" && !it.getPropertyRaw("_tombstone").isTrue() }
            .forEach { box ->
                launch {
                    try {
                        box.begin()
                    } catch (t: Throwable) {
                        lprintln("warning,autorun", "autorun failed for ${box.name}: ${t.message}")
                    }
                }
            }
    }
}

var sessions = ConcurrentHashMap<WebSocketSession, SessionInformation>()
var documents = ConcurrentHashMap<String, SessionInformation>()
private val welcomeEventSessions = ConcurrentHashMap.newKeySet<WebSocketSession>()

// Sessions grouped by an arbitrary "selection context" name (Hello.shaderSelectionContext).
// Every socket sharing a context receives each other's selection changes, so e.g. an
// editor-only dock and a canvas-only dock can share one selection. Server-mediated because
// separate WKWebView processes (the visionOS docks) can't share BroadcastChannel/localStorage.
private val selectionContextSessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

private fun joinSelectionContext(context: String, session: WebSocketSession) {
    selectionContextSessions.getOrPut(context) { ConcurrentHashMap.newKeySet() }.add(session)
}

private fun leaveSelectionContexts(session: WebSocketSession) {
    selectionContextSessions.values.forEach { it.remove(session) }
    selectionContextSessions.entries.removeIf { it.value.isEmpty() }
}

private fun selectionContextOf(session: WebSocketSession): String? =
    selectionContextSessions.entries.firstOrNull { it.value.contains(session) }?.key

private val aiKotlinNotifyThrottle = ConcurrentHashMap<String, Long>()
private val aiKotlinCompletionsInFlight = AtomicInteger(0)

var min_frame_delay = 1L

/**
 * Tracks per-session back pressure for output messages.
 * When the frontend is slow to acknowledge outputs, the pending count rises.
 * Once it reaches [maxPending], new outputs are held in a rolling buffer of size [bufferSize].
 * Messages that overflow the buffer are counted as truly skipped.
 * When pressure eases, the buffered messages are flushed after the skip notification —
 * they are never counted as skipped.
 */
data class OutputBackPressureSnapshot(
    val pending: Int,
    val skipped: Int,
    val buffered: Int,
    val maxPending: Int,
    val bufferSize: Int
)

class OutputBackPressure(val maxPending: Int = 8, val bufferSize: Int = 4) {
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private val skipped = java.util.concurrent.atomic.AtomicInteger(0)
    private val buffer = ArrayDeque<String>()   // guarded by synchronized(this)

    /**
     * Try to acquire a send slot.
     * Returns the number of previously *truly* skipped messages (≥ 0) if a slot is available,
     * or -1 if the caller should buffer the message instead (back-pressured).
     */
    fun tryAcquire(): Int {
        // Atomically increment only if below the limit, avoiding a TOCTOU race.
        val prev = pending.getAndUpdate { current -> if (current >= maxPending) current else current + 1 }
        if (prev >= maxPending) return -1
        return skipped.getAndSet(0)
    }

    /**
     * Buffer a message for later delivery (call when [tryAcquire] returns -1).
     * The rolling buffer holds the last [bufferSize] messages; older entries are evicted and
     * their count is added to [skipped] so the frontend knows how many were truly dropped.
     */
    fun bufferMessage(message: String) {
        synchronized(this) {
            if (buffer.size >= bufferSize) {
                buffer.removeFirst()
                skipped.incrementAndGet()   // evicted from buffer — truly skipped
            }
            buffer.addLast(message)
        }
    }

    fun hasBufferedMessages(): Boolean = synchronized(this) {
        buffer.isNotEmpty()
    }

    fun snapshot(): OutputBackPressureSnapshot = synchronized(this) {
        OutputBackPressureSnapshot(
            pending = pending.get(),
            skipped = skipped.get(),
            buffered = buffer.size,
            maxPending = maxPending,
            bufferSize = bufferSize
        )
    }

    /**
     * Drain the rolling buffer, acquiring a pending slot for each recoverable message.
     * Returns messages that were successfully acquired; messages that still cannot
     * acquire a slot remain buffered for the next acknowledgement cycle.
     */
    fun drainBuffer(): List<String> {
        val result = mutableListOf<String>()
        synchronized(this) {
            while (buffer.isNotEmpty()) {
                val prev = pending.getAndUpdate { cur -> if (cur >= maxPending) cur else cur + 1 }
                if (prev >= maxPending) break
                result.add(buffer.removeFirst())
            }
        }
        return result
    }

    /** Release a pending slot — call when the frontend acknowledges receipt. */
    fun release() {
        // Guard against going negative if extra ACKs arrive (e.g., duplicate messages).
        pending.updateAndGet { maxOf(0, it - 1) }
    }

    fun releaseAndDrain(): Pair<Int, List<String>> {
        release()
        val skipCount = skipped.getAndSet(0)
        return skipCount to drainBuffer()
    }
}

/** Per-WebSocketSession back pressure state for output messages. */
val sessionOutputPressure = ConcurrentHashMap<WebSocketSession, OutputBackPressure>()

private fun defaultWorkspacePath(): String =
    File(System.getProperty("user.home"), "Documents/AFieldWorkspace").absolutePath

private fun fieldUserConfigDir(): File {
    val osName = System.getProperty("os.name").lowercase(Locale.US)
    return if (osName.contains("windows")) {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
        File(appData, "AField")
    } else {
        File(System.getProperty("user.home"), ".afield")
    }
}

private fun workspacePreferenceFile(): File =
    File(fieldUserConfigDir(), "last-workspace.txt")

private fun workspaceRestartLogFile(): File =
    File(fieldUserConfigDir(), "workspace-restart.log")

private fun readPersistedWorkspacePath(): String? =
    runCatching {
        workspacePreferenceFile().takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

private fun shouldPersistWorkspacePath(): Boolean {
    val setting = System.getenv("AFIELD_SKIP_WORKSPACE_PERSIST")?.trim()?.lowercase(Locale.US)
        ?: System.getProperty("afield.skipWorkspacePersist")?.trim()?.lowercase(Locale.US)
        ?: return true
    return setting !in setOf("1", "true", "yes", "on", "enabled")
}

private fun currentAiSettingsStatus(): AiSettingsStatus {
    val status = aiProviderStatus()
    return AiSettingsStatus(
        hasOpenAiPlatformKey = status.hasOpenAiPlatformKey,
        hasOpenRouterApiKey = status.hasOpenRouterApiKey,
        provider = status.provider,
        source = status.source,
        configPath = status.configPath,
        openRouterConfigPath = status.openRouterConfigPath,
        baseUrl = status.baseUrl,
        flagshipModel = status.modelSettings.flagshipModel,
        fastModel = status.modelSettings.fastModel,
        fallbackModel = status.modelSettings.fallbackModel
    )
}

private val preferencesJson = Json { ignoreUnknownKeys = true }

private fun defaultFieldPreferences(): FieldPreferences =
    FieldPreferences(
        profiling = Options.profiling,
        framePresentationLog = Options.framePresentationLog,
        logShadercSource = Options.logShadercSource,
        enableVR = Options.enableVR,
        enableHDR = Options.enableHDR,
        openXRDebugInput = Options.openXRDebugInput,
        validate = Options.validate
    )

private fun normalizeFieldPreferences(preferences: FieldPreferences): FieldPreferences {
    val sticky = when (preferences.editorPropertyStickiness.trim().lowercase(Locale.US)) {
        "per-box", "perbox" -> "per-box"
        "global" -> "global"
        else -> "none"
    }

    fun normalizeOscAddress(address: String): String {
        val trimmed = address.trim()
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("/") -> trimmed
            else -> "/$trimmed"
        }
    }

    val importClassBlacklist = preferences.importClassBlacklist
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.US) }
        .take(200)
        .ifEmpty { defaultImportClassBlacklist }

    return preferences.copy(
        editorPropertyStickiness = sticky,
        oscEditorPort = preferences.oscEditorPort.coerceIn(1, 65535),
        oscEditorFloatAddress = normalizeOscAddress(preferences.oscEditorFloatAddress),
        oscEditorLaunchAddress = normalizeOscAddress(preferences.oscEditorLaunchAddress),
        oscEditorStopAddress = normalizeOscAddress(preferences.oscEditorStopAddress),
        oscEditorDebounceMs = preferences.oscEditorDebounceMs.coerceIn(0, 5_000),
        oscEditorMaxExecutionMs = preferences.oscEditorMaxExecutionMs.coerceIn(0, 60_000),
        importClassBlacklist = importClassBlacklist
    )
}

private fun applyFieldPreferences(preferences: FieldPreferences) {
    val framePresentationLogWasEnabled = Options.framePresentationLog
    CodeUseTelemetryService.configureQuietMode(preferences.quietMode)
    Options.profiling = if (Options.captureCompatibility) false else preferences.profiling
    Options.framePresentationLog = preferences.framePresentationLog
    Options.logShadercSource = preferences.logShadercSource
    Options.enableVR = preferences.enableVR
    Options.enableHDR = preferences.enableHDR
    Options.openXRDebugInput = preferences.openXRDebugInput
    Options.validate = if (Options.captureCompatibility) false else Options.validateOverride ?: preferences.validate
    if (preferences.framePresentationLog && !framePresentationLogWasEnabled) {
        FramePresentationLog.start(
            outputPath = Options.framePresentationLogPath,
            waitAfterPresent = Options.framePresentationLogWaitAfterPresent,
            append = false
        )
    } else if (!preferences.framePresentationLog && FramePresentationLog.enabled) {
        FramePresentationLog.stop()
    }
    OscEditorBackdoor.configure(preferences)
}

private fun persistWorkspacePath(path: String) {
    if (!shouldPersistWorkspacePath()) return
    runCatching {
        val workspace = displayWorkspacePath(path)
        val file = workspacePreferenceFile()
        file.parentFile?.mkdirs()
        file.writeText("$workspace\n", Charsets.UTF_8)
    }.onFailure {
        lprintln("warning,workspace", "could not persist last workspace: ${it.message}")
    }
}

val home = option("workspace") { readPersistedWorkspacePath() ?: defaultWorkspacePath() }
val app = option("app") { File(".").absolutePath }

private fun expandWorkspacePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) throw IllegalArgumentException("workspace path is blank")
    val userHome = System.getProperty("user.home")
    return when {
        trimmed == "~" -> userHome
        trimmed.startsWith("~/") -> userHome + trimmed.removePrefix("~")
        else -> trimmed
    }
}

private fun canonicalWorkspaceFile(path: String): File {
    val workspace = Paths.get(expandWorkspacePath(path)).toAbsolutePath().normalize().toFile()
    if (workspace.exists() && !workspace.isDirectory) {
        throw IllegalArgumentException("workspace path is not a directory")
    }
    if (!workspace.exists() && !workspace.mkdirs()) {
        throw IllegalArgumentException("could not create workspace directory")
    }
    return workspace.canonicalFile
}

private fun displayWorkspacePath(path: String): String {
    val workspace = Paths.get(expandWorkspacePath(path)).toAbsolutePath().normalize().toFile()
    return if (workspace.exists()) workspace.canonicalFile.absolutePath else workspace.absolutePath
}

private fun argsWithWorkspace(args: List<String>, workspace: String): List<String> {
    val updated = mutableListOf<String>()
    var replaced = false
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg.equals("--workspace", ignoreCase = true) -> {
                updated += arg
                updated += workspace
                replaced = true
                index += if (index + 1 < args.size) 2 else 1
            }

            arg.startsWith("--workspace=", ignoreCase = true) -> {
                updated += "--workspace"
                updated += workspace
                replaced = true
                index += 1
            }

            else -> {
                updated += arg
                index += 1
            }
        }
    }
    if (!replaced) {
        updated += "--workspace"
        updated += workspace
    }
    return updated
}

private fun relaunchWithWorkspace(workspace: String) {
    val projectDir = File(app).canonicalFile
    val isWindows = System.getProperty("os.name").lowercase(Locale.US).contains("windows")
    val wrapper = File(projectDir, if (isWindows) "gradlew.bat" else "gradlew")

    if (wrapper.exists()) {
        val logFile = workspaceRestartLogFile()
        logFile.parentFile?.mkdirs()
        val launcher = File(projectDir, "field.sh")
        val powershellLauncher = File(projectDir, "field.ps1")
        val processBuilder = if (isWindows) {
            if (powershellLauncher.exists()) {
                ProcessBuilder(
                    "cmd",
                    "/c",
                    "start",
                    "\"Field4\"",
                    "powershell.exe",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    powershellLauncher.absolutePath
                ).directory(projectDir)
            } else {
                ProcessBuilder("cmd", "/c", "start", "\"Field4\"", wrapper.absolutePath, "runDesktop")
                    .directory(projectDir)
            }
        } else if (launcher.exists()) {
            ProcessBuilder(
                "sh",
                "-c",
                "nohup ./field.sh >> \"\$1\" 2>&1 < /dev/null &",
                "field4-relaunch",
                logFile.absolutePath
            ).directory(projectDir)
        } else {
            ProcessBuilder(
                "sh",
                "-c",
                "nohup ./gradlew runDesktop >> \"\$1\" 2>&1 < /dev/null &",
                "field4-relaunch",
                logFile.absolutePath
            ).directory(projectDir)
        }
        processBuilder.start()
        lprintln("info,server", "relaunching Field via ${wrapper.absolutePath}; restart log: ${logFile.absolutePath}")
        return
    }

    val info = ProcessHandle.current().info()
    val command = info.command().orElseThrow {
        IllegalStateException("current Java command is unavailable")
    }
    val arguments = argsWithWorkspace(info.arguments().orElse(emptyArray()).toList(), workspace)
    if (arguments.none { it == "-cp" || it.startsWith("@") } || arguments.none { it == "afield.Server" }) {
        throw IllegalStateException("current Java command line is incomplete; no Gradle wrapper fallback is available")
    }

    val logFile = workspaceRestartLogFile()
    logFile.parentFile?.mkdirs()
    ProcessBuilder(listOf(command) + arguments)
        .directory(projectDir)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        .start()
    lprintln("info,server", "relaunching Field via current Java command; restart log: ${logFile.absolutePath}")
}

private fun launchFieldDesktopFromProject(projectDir: File, reason: String) {
    val canonicalProject = projectDir.canonicalFile
    val isWindows = System.getProperty("os.name").lowercase(Locale.US).contains("windows")
    val wrapper = File(canonicalProject, if (isWindows) "gradlew.bat" else "gradlew")
    if (!wrapper.exists()) {
        throw IllegalArgumentException("No Gradle wrapper found in ${canonicalProject.absolutePath}")
    }
    val launcher = File(canonicalProject, "field.sh")
    val powershellLauncher = File(canonicalProject, "field.ps1")
    val logFile = workspaceRestartLogFile()
    logFile.parentFile?.mkdirs()
    val processBuilder = if (isWindows) {
        if (powershellLauncher.exists()) {
            ProcessBuilder(
                "cmd",
                "/c",
                "start",
                "\"Field4\"",
                "powershell.exe",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                powershellLauncher.absolutePath
            ).directory(canonicalProject)
        } else {
            ProcessBuilder("cmd", "/c", "start", "\"Field4\"", wrapper.absolutePath, "runDesktop")
                .directory(canonicalProject)
        }
    } else if (launcher.exists()) {
        ProcessBuilder(
            "sh",
            "-c",
            "nohup ./field.sh >> \"\$1\" 2>&1 < /dev/null &",
            "field4-worktree-relaunch",
            logFile.absolutePath
        ).directory(canonicalProject)
    } else {
        ProcessBuilder(
            "sh",
            "-c",
            "nohup ./gradlew runDesktop >> \"\$1\" 2>&1 < /dev/null &",
            "field4-worktree-relaunch",
            logFile.absolutePath
        ).directory(canonicalProject)
    }
    configureAgentWorktreeLaunchEnvironment(processBuilder)
    processBuilder.start()
    lprintln(
        "info,server",
        "launching Field from ${canonicalProject.absolutePath} ($reason); restart log: ${logFile.absolutePath}"
    )
}

private fun configureAgentWorktreeLaunchEnvironment(processBuilder: ProcessBuilder) {
    val env = processBuilder.environment()
    val fieldPort = chooseAvailableLocalPort()
    val httpPort = chooseAvailableLocalPort(excluding = setOf(fieldPort))
    env["PORT"] = fieldPort.toString()
    env["AFIELD_HTTP_PORT"] = httpPort.toString()
    env["AFIELD_PORT_FALLBACK_COUNT"] = "1"

    // A generated worktree needs its own semantic sidecar. Inheriting the parent
    // server's sidecar URL/port can point at the wrong checkout or collide with
    // the launcher-managed sidecar port derived from the worktree path.
    env.remove("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")
    env.remove("AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT")
}

private fun chooseAvailableLocalPort(excluding: Set<Int> = emptySet()): Int {
    repeat(16) {
        val port = ServerSocket(0).use { socket -> socket.localPort }
        if (port !in excluding) return port
    }
    throw IllegalStateException("Could not allocate a local port for generated worktree launch.")
}

val preferSystemBrowser: Boolean = (System.getenv("AFIELD_SYSTEM_BROWSER")
    ?: System.getProperty("afield.systemBrowser") ?: "true").lowercase() != "false"
val visibleBackdoorPlaywright: Boolean = (System.getenv("AFIELD_PLAYWRIGHT_VISIBLE")
    ?: System.getProperty("afield.playwright.visible") ?: "false").lowercase() == "true"
private val outputBackPressureLogMode: String = (System.getenv("AFIELD_OUTPUT_BACKPRESSURE_LOG")
    ?: System.getProperty("afield.outputBackpressureLog") ?: "summary").trim().lowercase(Locale.US)
private val outputBackPressureLogEnabled: Boolean = outputBackPressureLogMode !in setOf("false", "0", "off", "none")
private val outputBackPressureTraceLogEnabled: Boolean =
    outputBackPressureLogMode in setOf("true", "1", "yes", "on", "trace", "debug", "verbose")
private val outputBackPressureLogIntervalMillis: Long =
    if (outputBackPressureTraceLogEnabled) 500L else 5_000L
private val outputBackPressureLogTimes = ConcurrentHashMap<String, Long>()

private fun isBackPressuredOutputMessage(message: Message): Boolean {
    return message.name == "set-output" ||
            message.name == "output" ||
            message.name == "peer-output" ||
            message.originalName == "output"
}

private fun outputBackPressureMessageName(message: Message): String {
    return if (message.originalName.isNullOrBlank()) message.name else "${message.name}/${message.originalName}"
}

private fun outputBackPressureLog(
    session: WebSocketSession,
    event: String,
    message: Message? = null,
    pressure: OutputBackPressure? = sessionOutputPressure[session],
    detail: String = "",
    force: Boolean = false
) {
    if (!outputBackPressureLogEnabled) return
    val now = System.currentTimeMillis()
    val sessionId = Integer.toHexString(System.identityHashCode(session))
    val key = "$sessionId:$event"
    val forceNow = force && (outputBackPressureTraceLogEnabled || event.endsWith("failed-release"))
    if (!forceNow) {
        val previous = outputBackPressureLogTimes[key] ?: 0L
        if (now - previous < outputBackPressureLogIntervalMillis) return
        outputBackPressureLogTimes[key] = now
    } else {
        outputBackPressureLogTimes[key] = now
    }
    val snapshot = pressure?.snapshot()
    val state = if (snapshot == null) {
        "pending=<none> buffered=<none> skipped=<none>"
    } else {
        "pending=${snapshot.pending}/${snapshot.maxPending} buffered=${snapshot.buffered}/${snapshot.bufferSize} skipped=${snapshot.skipped}"
    }
    val msg = message?.let { " message=${outputBackPressureMessageName(it)}" } ?: ""
    val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    lprintln("debug,output-backpressure", "session=$sessionId event=$event$msg $state$suffix")
}

@OptIn(DelicateCoroutinesApi::class)
private fun sendWithOutputBackPressure(
    session: WebSocketSession,
    message: Message,
    encoded: String = Json.encodeToString(message)
) {
    NetStats.sent(encoded.length) // one text frame per session; _send() fans out through here
    if (!isBackPressuredOutputMessage(message)) {
        GlobalScope.launch {
            session.send(encoded)
        }
        return
    }

    val bp = sessionOutputPressure.getOrPut(session) { OutputBackPressure() }
    val skipCount = bp.tryAcquire()
    if (skipCount < 0) {
        bp.bufferMessage(encoded)
        outputBackPressureLog(session, "buffer", message, bp, detail = "encodedBytes=${encoded.length}")
        return
    }

    val buffered = if (skipCount > 0 || bp.hasBufferedMessages()) bp.drainBuffer() else emptyList()
    if (skipCount > 0 || buffered.isNotEmpty()) {
        outputBackPressureLog(
            session,
            "send-with-buffer",
            message,
            bp,
            detail = "drained=${buffered.size} skippedToNotify=$skipCount encodedBytes=${encoded.length}",
            force = true
        )
    }
    GlobalScope.launch {
        try {
            if (skipCount > 0) {
                session.send(
                    Json.encodeToString(
                        Message("notify").payload(Notify("($skipCount outputs skipped)", "output-backpressure"))
                    )
                )
            }
            for (bufferedMsg in buffered) {
                session.send(bufferedMsg)
            }
            session.send(encoded)
        } catch (_: Exception) {
            repeat(1 + buffered.size) { bp.release() }
            outputBackPressureLog(
                session,
                "send-failed-release",
                message,
                bp,
                detail = "released=${1 + buffered.size}",
                force = true
            )
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun releaseAndFlushOutputBackPressure(session: WebSocketSession) {
    val bp = sessionOutputPressure[session] ?: return
    val (skipCount, buffered) = bp.releaseAndDrain()
    outputBackPressureLog(
        session,
        "ack",
        pressure = bp,
        detail = "drained=${buffered.size} skippedToNotify=$skipCount",
        force = skipCount > 0 || buffered.isNotEmpty()
    )
    if (skipCount <= 0 && buffered.isEmpty()) return

    GlobalScope.launch {
        try {
            if (skipCount > 0) {
                session.send(
                    Json.encodeToString(
                        Message("notify").payload(Notify("($skipCount outputs skipped)", "output-backpressure"))
                    )
                )
            }
            for (bufferedMsg in buffered) {
                session.send(bufferedMsg)
            }
        } catch (_: Exception) {
            repeat(buffered.size) { bp.release() }
            outputBackPressureLog(
                session,
                "flush-failed-release",
                pressure = bp,
                detail = "released=${buffered.size}",
                force = true
            )
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
actual fun _send(message: Message) {
    _send(message, document = ".*")
}


@OptIn(DelicateCoroutinesApi::class)
fun _send(message: Message, document: String = ".*") {
    recordControllerOutputMessage(document, message)
    val documentRegex = document.toRegex()
    val m = Json.encodeToString(message)
    sessions.forEach { (session, info) ->
        val matchesDocument = info.filename.matches(documentRegex) ||
                info.documentName.matches(documentRegex) ||
                File(info.filename).name.matches(documentRegex)
        if (!matchesDocument) return@forEach
        recordControllerOutputMessage(info, message)
        sendWithOutputBackPressure(session, message, m)
    }
    if (message.name == "is-running") {
        broadcastWelcomeEvent("running")
    }
}

@OptIn(DelicateCoroutinesApi::class)
internal fun broadcastWelcomeEvent(reason: String) {
    val event = """{"type":"welcome-refresh","reason":${Json.encodeToString(reason)}}"""
    welcomeEventSessions.removeIf { session ->
        if (session.outgoing.isClosedForSend) return@removeIf true
        GlobalScope.launch {
            runCatching { session.send(event) }
        }
        false
    }
}


var lastFn by PersistentString { "welcome" }
var recentFns by PersistentString { "" }
var fieldPreferencesJson by PersistentString { "" }

private fun appRouteDocumentToken(documentName: String): String {
    val trimmed = documentName.trim()
    val path = if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        runCatching { URI(trimmed).path }.getOrNull()?.takeIf { it.isNotEmpty() } ?: trimmed
    } else {
        trimmed
    }
    return path
        .substringBefore('?')
        .substringBefore('#')
        .replace('\\', '/')
        .trim('/')
        .lowercase(Locale.US)
}

internal fun isFieldAppRouteDocumentName(documentName: String?): Boolean {
    val trimmed = documentName?.trim().takeUnless { it.isNullOrEmpty() } ?: return false
    val token = appRouteDocumentToken(trimmed)
    if (token == "welcome.html") return true
    if (token != "welcome") return false
    return trimmed.startsWith("/") ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
}

private fun desktopStartupDocumentName(): String? =
    lastFn?.trim()?.takeIf { it.isNotEmpty() && !isFieldAppRouteDocumentName(it) }

@Volatile
private var cachedFieldPreferencesRaw: String? = null

@Volatile
private var cachedFieldPreferences: FieldPreferences? = null

internal fun currentFieldPreferences(): FieldPreferences {
    val raw = fieldPreferencesJson.orEmpty().trim()
    val cached = cachedFieldPreferences
    if (cached != null && cachedFieldPreferencesRaw == raw) return cached

    val decoded = if (raw.isBlank()) {
        defaultFieldPreferences()
    } else {
        runCatching { preferencesJson.decodeFromString<FieldPreferences>(raw) }
            .getOrElse { defaultFieldPreferences() }
    }
    return normalizeFieldPreferences(decoded).also {
        cachedFieldPreferencesRaw = raw
        cachedFieldPreferences = it
    }
}

private fun persistFieldPreferences(preferences: FieldPreferences) {
    val normalized = normalizeFieldPreferences(preferences)
    val encoded = preferencesJson.encodeToString(normalized)
    fieldPreferencesJson = encoded
    cachedFieldPreferencesRaw = encoded.trim()
    cachedFieldPreferences = normalized
    applyFieldPreferences(normalized)
}

internal fun recordRecentDocument(documentName: String) {
    if (isFieldAppRouteDocumentName(documentName)) return
    recentFns = updateRecentDocuments(recentFns, documentName)
}

@OptIn(DelicateCoroutinesApi::class)
fun _send(session: WebSocketSession?, message: Message) {
    if (session == null) {
        _send(message)
        return
    }
    val m = Json.encodeToString(message)
    recordControllerOutputMessage(sessions[session], message)
    sendWithOutputBackPressure(session, message, m)
}

@OptIn(DelicateCoroutinesApi::class)
internal fun _sendToPeerSessions(
    message: Message,
    sessionInfo: SessionInformation? = thisSessionInfo.get(),
    current: WebSocketSession? = thisSession.get()
) {
    val info = sessionInfo ?: return
    val encoded = Json.encodeToString(message)
    info.sessions.forEach { session ->
        if (session == current) return@forEach
        sendWithOutputBackPressure(session, message, encoded)
    }
}


@OptIn(DelicateCoroutinesApi::class)
fun notify(text: String, category: String = "") {
    thisSession.get()
    _send(thisSession.get(), Message("notify").payload(Notify(text, category)))
}


val thisSession = ThreadLocal<WebSocketSession>()
val thisSessionInfo = ThreadLocal<SessionInformation>()
var globalPlaywright: Playwright? = null
var globalPlaywrightBrowser: Browser? = null
var globalPlaywrightBrowserContext: BrowserContext? = null
var globalPlaywriteContext: Page? = null
var globalServerPort: Int = 9092
var globalLoopbackHttpPort: Int? = null
private const val PLAYWRIGHT_NAVIGATION_TIMEOUT_MS = 12_000.0
private const val PLAYWRIGHT_READY_TIMEOUT_MS = 45_000.0
private const val PLAYWRIGHT_ATTACH_ATTEMPTS = 3
private const val PLAYWRIGHT_VIEWPORT_WIDTH = 2200
private const val PLAYWRIGHT_VIEWPORT_HEIGHT = 2800
private const val PLAYWRIGHT_DEVICE_SCALE_FACTOR = 2.0
private val playwrightExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "afield-playwright").apply {
        isDaemon = true
    }
}
// Single dispatcher over the dedicated "afield-playwright" thread. Everything that
// touches the Playwright Page must run here; DocsUiDriver / DocsUiRecorder marshal
// onto this dispatcher so a script running on the main runloop thread can drive the
// page safely (Playwright's Page is bound to its owning thread).
internal val playwrightDispatcher = playwrightExecutor.asCoroutineDispatcher()
internal val playwrightScope = CoroutineScope(SupervisorJob() + playwrightDispatcher)
private val workspaceSearchDispatcher = Executors.newFixedThreadPool(
    (System.getenv("AFIELD_WORKSPACE_SEARCH_THREADS")?.trim()?.toIntOrNull() ?: 2).coerceIn(1, 4)
) { runnable ->
    Thread(runnable, "afield-workspace-search").apply {
        isDaemon = true
    }
}.asCoroutineDispatcher()
private val workspaceSearchGeneration = AtomicLong(0)

private data class PlaywrightPageHandle(
    val page: Page,
    val createdBrowser: Boolean,
    val createdContext: Boolean,
    val createdPage: Boolean
)

private fun jsonStringLiteral(value: String?): String = if (value == null) "null" else Json.encodeToString(value)

private fun initialFramePreviewSessionKey(file: String?, scratch: Boolean, sessionKey: String?): String? {
    val explicitSessionKey = sessionKey?.trim().takeIf { !it.isNullOrEmpty() }
    if (explicitSessionKey != null) return explicitSessionKey
    if (scratch) return null
    val documentName = file?.trim().takeIf { !it.isNullOrEmpty() } ?: "default"
    if (documentName.startsWith("http://", ignoreCase = true) ||
        documentName.startsWith("https://", ignoreCase = true)
    ) return null
    return File(home, documentName).path
}

private fun indexInitialFramePreviewScript(file: String?, scratch: Boolean, sessionKey: String?): String {
    val target = initialFramePreviewSessionKey(file, scratch, sessionKey) ?: return ""
    val frames = runCatching { SavingContext().readInitialBoxFrames(target) }
        .onFailure { lprintln("warning,server", "could not read initial frame preview for index $target: ${it.message}") }
        .getOrDefault(emptyList())
    if (frames.isEmpty()) return ""

    val framesJson = Json.encodeToString(frames)
    return """
<script id="field-initial-frame-preview">
(() => {
  const frames = $framesJson;
  if (!Array.isArray(frames) || frames.length === 0) return;
  window.__fieldInitialFrameCount = frames.length;
  window.__fieldInitialPlaceholderCount = frames.length;
  const splash = document.getElementById("splash-screen");
  if (splash) {
    splash.classList.add("hidden");
    splash.style.display = "none";
  }
  const layer = document.createElement("div");
  layer.className = "box-placeholder-layer";
  layer.setAttribute("aria-hidden", "true");
  layer.setAttribute("data-origin", "html");
  for (const initial of frames) {
    if (!initial || initial.uid === "root" || !initial.frame) continue;
    const frame = initial.frame;
    const node = document.createElement("div");
    node.className = "box-placeholder";
    node.setAttribute("aria-hidden", "true");
    node.dataset.uid = initial.uid;
    node.uid = initial.uid;
    node.style.transform = `translate(${'$'}{frame.x || 0}px, ${'$'}{frame.y || 0}px)`;
    node.style.width = `${'$'}{frame.w || 0}px`;
    node.style.height = `${'$'}{frame.h || 0}px`;
    if (!frame.w && !frame.h) node.style.display = "none";
    layer.appendChild(node);
  }
  document.body.appendChild(layer);
})();
</script>
""".trim()
}

private fun injectInitialFramePreview(html: String, call: ApplicationCall): String {
    // An editor-only view hides the canvas entirely, so the box-placeholder pre-render would just
    // flash stray boxes before Monaco appears. Skip it there.
    if (call.request.queryParameters.contains("editorOnly")) return html
    val file = call.request.queryParameters["file"]
    val scratch = call.request.queryParameters["scratch"]?.trim()?.lowercase(Locale.US) in setOf("1", "true", "yes")
    val sessionKey = call.request.queryParameters["sessionKey"]
    val script = indexInitialFramePreviewScript(file, scratch, sessionKey)
    if (script.isBlank()) return html
    val marker = "<div class=\"centered-container cheatsheet-hidden\" id=\"cheatsheet\">"
    return if (html.contains(marker)) {
        html.replace(marker, "$script\n\n$marker")
    } else {
        html.replace("<body>", "<body>\n$script")
    }
}

private fun closeGlobalPlaywrightPageState() {
    runCatching { globalPlaywrightBrowserContext?.close() }
    globalPlaywrightBrowserContext = null
    globalPlaywriteContext = null
}

private fun resetGlobalPlaywrightState() {
    closeGlobalPlaywrightPageState()
    runCatching { globalPlaywrightBrowser?.close() }
    runCatching { globalPlaywright?.close() }
    globalPlaywrightBrowser = null
    globalPlaywright = null
}

internal fun buildPlaywrightDocumentUrl(info: SessionInformation, port: Int = globalServerPort): String {
    val params = mutableListOf(
        "file=${URLEncoder.encode(info.documentName, "UTF-8")}",
        "user=playwright"
    )
    if (info.ephemeral) {
        params += "scratch=1"
        params += "sessionKey=${URLEncoder.encode(info.filename, "UTF-8")}"
    }
    return "https://localhost:${port}/index.html?${params.joinToString("&")}"
}

private fun loopbackHttpPortFor(httpsPort: Int): Int? {
    val raw = System.getenv("AFIELD_HTTP_PORT")?.trim()
    if (raw != null && raw.lowercase() in setOf("", "0", "off", "false", "none", "disabled")) {
        return null
    }
    if (raw != null) {
        return raw.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("AFIELD_HTTP_PORT must be a port number or one of off,false,none,disabled; got '$raw'")
    }
    return (httpsPort + 10_000).takeIf { it <= 65535 }
}

private fun ensurePlaywrightPageOnce(targetUrl: String): PlaywrightPageHandle {
    var createdBrowser = false
    var createdContext = false
    var createdPage = false

    val browser = globalPlaywrightBrowser ?: run {
        val pw = globalPlaywright ?: Playwright.create().also { globalPlaywright = it }
        createdBrowser = true
        // Enable hardware GL for the capture browser. Headless chromium defaults to
        // SwiftShader (software rasterization), which pegs the CPU rasterizing docs
        // capture content and makes a MODE=always build ~2.5x slower (~230s vs ~130s
        // on forth+fline; 23min vs 9min full). --enable-gpu + --ignore-gpu-blocklist
        // gets it onto the real GPU; on macOS force the Metal ANGLE backend (verified
        // ANGLE Metal + the 2.5x). If no GPU is available (e.g. some CI), chromium
        // falls back to software rendering on its own, so this only ever helps.
        val browserArgs = buildList {
            add("--disable-background-timer-throttling")
            add("--disable-backgrounding-occluded-windows")
            add("--disable-renderer-backgrounding")
            add("--disable-frame-rate-limit")
            add("--enable-gpu")
            add("--ignore-gpu-blocklist")
            if (System.getProperty("os.name").orEmpty().lowercase(Locale.US).contains("mac")) {
                add("--use-angle=metal")
            }
        }
        pw.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(!visibleBackdoorPlaywright)
                .setArgs(browserArgs)
        ).also { globalPlaywrightBrowser = it }
    }

    val currentPage = globalPlaywriteContext?.takeIf { !it.isClosed() }
    if (currentPage != null) {
        return PlaywrightPageHandle(
            page = currentPage,
            createdBrowser = createdBrowser,
            createdContext = false,
            createdPage = false
        )
    }

    closeGlobalPlaywrightPageState()
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setViewportSize(PLAYWRIGHT_VIEWPORT_WIDTH, PLAYWRIGHT_VIEWPORT_HEIGHT)
            .setScreenSize(PLAYWRIGHT_VIEWPORT_WIDTH, PLAYWRIGHT_VIEWPORT_HEIGHT)
            .setDeviceScaleFactor(PLAYWRIGHT_DEVICE_SCALE_FACTOR)
            .setIgnoreHTTPSErrors(true)
    )
    globalPlaywrightBrowserContext = context
    createdContext = true

    val page = context.newPage()
    globalPlaywriteContext = page
    createdPage = true

    return PlaywrightPageHandle(
        page = page,
        createdBrowser = createdBrowser,
        createdContext = createdContext,
        createdPage = createdPage
    )
}

private fun ensurePlaywrightPage(targetUrl: String): PlaywrightPageHandle =
    runCatching {
        ensurePlaywrightPageOnce(targetUrl)
    }.getOrElse { firstFailure ->
        lprintln("warning,server", "resetting Playwright after failure: ${firstFailure.message}")
        resetGlobalPlaywrightState()
        ensurePlaywrightPageOnce(targetUrl)
    }

private fun playwrightSessionReadyScript(info: SessionInformation) = """
    () => {
      const overlay = document.getElementById("overlaycanvas");
      const currentUrl = String(window.location && window.location.href ? window.location.href : "");
      return !!document.body &&
        !!overlay &&
        !currentUrl.startsWith("about:") &&
        window.fieldClientOpen === true &&
        window.fieldBackdoorDisconnectedOverlayActive !== true &&
        window.fieldBackdoorUiReady === true &&
        typeof window.fieldBackdoorPrepareWindowScreenshot === "function" &&
        typeof window.fieldBackdoorPrepareBoxScreenshot === "function" &&
        window.currentDocumentFilename === ${jsonStringLiteral(info.documentName)} &&
        window.currentDocumentSessionKey === ${jsonStringLiteral(if (info.ephemeral) info.filename else null)} &&
        window.currentDocumentScratch === ${if (info.ephemeral) "true" else "false"};
    }
""".trimIndent()

private fun playwrightSessionStatus(page: Page): String =
    runCatching {
        page.evaluate(
            """
            () => JSON.stringify({
              href: String(window.location && window.location.href ? window.location.href : ""),
              readyState: String(document.readyState || ""),
              hasBody: !!document.body,
              hasOverlay: !!document.getElementById("overlaycanvas"),
              fieldClientOpen: window.fieldClientOpen === true,
              disconnectedOverlayActive: window.fieldBackdoorDisconnectedOverlayActive === true,
              uiReady: window.fieldBackdoorUiReady === true,
              currentDocumentFilename: window.currentDocumentFilename ?? null,
              currentDocumentSessionKey: window.currentDocumentSessionKey ?? null,
              currentDocumentScratch: window.currentDocumentScratch ?? null,
              boxCount: document.querySelectorAll(".box").length
            })
            """.trimIndent()
        ) as? String
    }.getOrNull() ?: "<unavailable>"

private fun isPlaywrightSessionHealthy(page: Page, info: SessionInformation): Boolean =
    runCatching {
        page.evaluate(playwrightSessionReadyScript(info)) as? Boolean
    }.getOrDefault(false) == true

private fun ensurePlaywrightForSessionOnce(info: SessionInformation): AgentOpenPlaywrightResponse {
    val targetUrl = buildPlaywrightDocumentUrl(info)
    val handle = ensurePlaywrightPage(targetUrl)
    val page = handle.page
    val currentUrl = runCatching { page.url() }.getOrDefault("")
    val navigated = handle.createdPage || currentUrl != targetUrl || !isPlaywrightSessionHealthy(page, info)
    if (navigated) {
        page.navigate(
            targetUrl,
            Page.NavigateOptions()
                .setTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT_MS)
                .setWaitUntil(WaitUntilState.COMMIT)
        )
    }
    // After the first committed response, rely on explicit Field-side readiness markers
    // instead of Playwright's own load-state heuristics.
    runCatching {
        page.waitForFunction(
            playwrightSessionReadyScript(info),
            null,
            Page.WaitForFunctionOptions()
                .setTimeout(PLAYWRIGHT_READY_TIMEOUT_MS)
        )
    }.getOrElse { failure ->
        lprintln(
            "warning,server",
            "Playwright readiness wait failed for ${info.documentName} (${info.filename}) at ${page.url()}: ${failure.message}; status=${
                playwrightSessionStatus(
                    page
                )
            }"
        )
        throw failure
    }
    if (visibleBackdoorPlaywright) {
        runCatching { page.bringToFront() }
    }

    return AgentOpenPlaywrightResponse(
        ok = true,
        documentName = info.documentName,
        scratch = info.ephemeral,
        sessionKey = info.filename,
        url = page.url(),
        createdBrowser = handle.createdBrowser,
        createdContext = handle.createdContext,
        createdPage = handle.createdPage,
        navigated = navigated,
        ready = true
    )
}

@Synchronized
internal fun ensurePlaywrightForSession(info: SessionInformation): AgentOpenPlaywrightResponse {
    var lastFailure: Throwable? = null
    repeat(PLAYWRIGHT_ATTACH_ATTEMPTS) { attempt ->
        runCatching {
            return ensurePlaywrightForSessionOnce(info)
        }.onFailure { failure ->
            lastFailure = failure
            lprintln(
                "warning,server",
                "resetting Playwright after session attach failure ${attempt + 1}/$PLAYWRIGHT_ATTACH_ATTEMPTS for ${info.documentName}: ${failure.message}"
            )
            resetGlobalPlaywrightState()
        }
    }
    throw lastFailure ?: IllegalStateException("Playwright session attach failed without an exception")
}

internal fun ensurePlaywrightForSessionIfNeeded(info: SessionInformation): AgentOpenPlaywrightResponse {
    val currentPage = globalPlaywriteContext?.takeIf { !it.isClosed() }
    val targetUrl = buildPlaywrightDocumentUrl(info)
    val currentUrl = currentPage?.let { runCatching { it.url() }.getOrNull() }
    if (currentPage != null && currentUrl == targetUrl && isPlaywrightSessionHealthy(currentPage, info)) {
        return AgentOpenPlaywrightResponse(
            ok = true,
            documentName = info.documentName,
            scratch = info.ephemeral,
            sessionKey = info.filename,
            url = currentUrl,
            createdBrowser = false,
            createdContext = false,
            createdPage = false,
            navigated = false,
            ready = true
        )
    }
    return ensurePlaywrightForSession(info)
}

class Server {
    companion object {

        @Serializable
        data class OfflineImportRequest(
            val recommendedName: String? = null,
            val ednText: String? = null,
            val sourceUrl: String? = null
        )

        @Serializable
        data class OfflineImportResponse(
            val documentName: String,
            val openUrl: String,
            val projectUrl: String
        )

        lateinit var ourServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        var ai_assistant: String? by PersistentString()
        var ai_thread: String? by PersistentString()

        var args: Array<String> = emptyArray()

        private fun openSystemBrowser(url: String) {
            try {
                if (Desktop.isDesktopSupported()) {
                    val d = Desktop.getDesktop()
                    if (d.isSupported(Desktop.Action.BROWSE)) {
                        d.browse(URI(url))
                        return
                    }
                }
            } catch (t: Throwable) {
                lprintln("warning,server", "Failed Desktop browse: ${t.message}")
            }

            val command = when {
                Options.isMac -> listOf("open", url)
                Options.isWindows -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                else -> listOf("xdg-open", url)
            }

            try {
                ProcessBuilder(command).start()
            } catch (t: Throwable) {
                lprintln("warning,server", "Failed to launch system browser via $command: ${t.message}")
            }
        }

        private fun revealInSystemFileManager(target: File) {
            val file = target.canonicalFile
            val commands = when {
                Options.isMac -> listOf(listOf("open", "-R", file.absolutePath))
                Options.isWindows -> listOf(listOf("explorer.exe", "/select,${file.absolutePath}"))
                else -> listOf(
                    listOf("xdg-open", file.absolutePath),
                    listOf("gio", "open", file.absolutePath)
                )
            }

            for (command in commands) {
                try {
                    ProcessBuilder(command).start()
                    return
                } catch (t: Throwable) {
                    lprintln("warning,server", "File manager launch failed (${command.joinToString(" ")}): ${t.message}")
                }
            }

            try {
                if (Desktop.isDesktopSupported()) {
                    val d = Desktop.getDesktop()
                    if (d.isSupported(Desktop.Action.OPEN)) {
                        d.open(file)
                        return
                    }
                }
            } catch (t: Throwable) {
                lprintln("warning,server", "Desktop open failed for ${file.absolutePath}: ${t.message}")
            }

            throw IllegalStateException("could not launch file manager")
        }

        private fun ensureLocalKeystore(target: File, password: String, alias: String): File {
            if (target.exists()) return target

            target.parentFile?.mkdirs()
            val keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toFile()

            val command = listOf(
                keytool.absolutePath,
                "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", target.absolutePath,
                "-storepass", password,
                "-keypass", password,
                "-validity", "3650",
                "-dname", "CN=localhost, OU=Field, O=Field, L=Local, ST=Local, C=US",
                "-ext", "SAN=DNS:localhost,DNS:mi2,IP:127.0.0.1"
            )

            val process = ProcessBuilder(command).inheritIO().start()
            val exit = process.waitFor()
            if (exit != 0) error("Failed to generate keystore: keytool exited with $exit")

            return target
        }

        private fun isPortBindException(error: Throwable): Boolean {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val queue = ArrayDeque<Throwable>()
            queue.add(error)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                if (current is BindException) return true

                current.cause?.let { queue.add(it) }
                current.suppressed.forEach { queue.add(it) }
            }
            return false
        }

        @OptIn(DelicateCoroutinesApi::class, BetaOpenAI::class)
        fun server() {
            lprintln("info,startup", "preparing runtime…")
            Options.doVulkanSDK()

            lprintln("info,server", "app directory is $app")
            System.setProperty("joml.format", "false")

            initializeClassCache()
            applyFieldPreferences(currentFieldPreferences())

            runAll(plugins)
            runAll(installHandlers)
            UserThemeManager.start()
            NetStats.start() // pinned "net" row: WebSocket packet/KB rates to/from the front end

            inMain {
                while (true) {
                    try {
                        val now = System.currentTimeMillis()
                        while (System.currentTimeMillis() - now < 1000 * 5) {
                            delay(500)
                            doResync()
                            syncExecutionStatus()
                        }

                        documents.forEach { _, info ->
                            if (info.ephemeral) return@forEach
                            if (info.loading) return@forEach
                            refreshFileProxyBoxes(info, notifyMissing = false)
                            // Only persist when we know sidecar-bearing properties changed.
                            if (info.pendingSidecarSaves.isNotEmpty()) {
//                            lblock(" saving to ${info.filename}")
                                run {
                                    try {
                                        // Include root box in saved boxes to persist root properties like bookmarks
                                        val allBoxes = info.boxes.values.toMutableSet()
                                        allBoxes.add(info.root)
                                        val pendingSnapshot = info.pendingSidecarSnapshot()
                                        SavingContext().save(info.filename, allBoxes, onComplete = {
                                            info.clearSavedSidecars(pendingSnapshot)
                                        })
                                    } catch (ex: Exception) {
                                        lprintln("error,server", "trouble saving")
                                        ex.printStackTrace()
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        lprintln("error,server", "exception inside saving / resync loop -- will retry")
                        t.printStackTrace()
                    }
                }
            }

            inMain {
                var t = System.currentTimeMillis()
                while (true) {
                    yield()
                    val now = System.currentTimeMillis()
                    if (now - t < min_frame_delay) {
                        Thread.sleep(min_frame_delay - (now - t))
                    }
                    t = System.currentTimeMillis()
                }
            }

            handlers["initialize"] = mutableSetOf({ message, reply ->
                sendProvisionalSet(thisSessionInfo.get().filename)

                Handled.yes
            })

            handlers["macro-transcript-command"] = mutableSetOf({ message, reply ->
                val request = Json.decodeFromJsonElement<MacroTranscriptCommandRequest>(message.payload!!)
                val documentPath = thisSessionInfo.get().filename
                val command = request.command.trim().lowercase()
                val session = thisSession.get()
                val sessionInfo = thisSessionInfo.get()

                fun macroResultForRequest(): OperationTranscript.MacroResult = when (command) {
                    "state", "status" -> OperationTranscript.macroState(documentPath, request.scope, request.projectName)
                    "start", "mark-start" -> OperationTranscript.markStart(request.label, request.selection)
                    "end", "save", "mark-end", "end-and-save" ->
                        OperationTranscript.markEndAndSave(request.name, request.label, request.scope, request.projectName)
                    "list" -> OperationTranscript.listMacros(documentPath, request.scope, request.projectName)
                    "read", "load" -> OperationTranscript.readMacro(documentPath, request.name, request.scope, request.projectName)
                    "reveal", "reveal-scope", "reveal-macros" -> {
                        val dir = OperationTranscript.macroDirectory(documentPath, request.scope, request.projectName)
                        if (dir.success && dir.path.isNotBlank()) {
                            runCatching {
                                revealInSystemFileManager(File(dir.path))
                                dir.copy(message = "Revealed ${dir.scope} macro folder.")
                            }.getOrElse {
                                OperationTranscript.MacroResult(false, "Could not reveal ${dir.scope} macro folder: ${it.message ?: "unknown error"}.")
                            }
                        } else {
                            dir
                        }
                    }
                    "transcript-page", "page", "entries" -> {
                        val hideWaits = request.mode.split(",", " ", ";").any {
                            it.equals("hide-wait", ignoreCase = true) ||
                                it.equals("hide-waits", ignoreCase = true) ||
                                it.equals("no-wait", ignoreCase = true) ||
                                it.equals("no-waits", ignoreCase = true)
                        }
                        OperationTranscript.transcriptPage(
                            documentPath,
                            request.offset,
                            request.limit,
                            includeWaits = !hideWaits
                        )
                    }
                    "undo-transcript-selection", "undo-selection" ->
                        OperationTranscript.undoTranscriptSelection(
                            documentPath = documentPath,
                            indices = request.indices,
                            strict = !request.mode.equals("generous", ignoreCase = true)
                        )
                    "ai-undo-prompt", "prompt-undo-selection" ->
                        OperationTranscript.aiUndoPrompt(documentPath, request.indices, request.selection)
                    "write" -> OperationTranscript.writeMacro(
                        documentPath,
                        request.name,
                        request.content,
                        request.scope,
                        request.projectName
                    )
                    "save-transcript-selection", "save-selection", "save-selected" ->
                        OperationTranscript.saveTranscriptSelectionAsMacro(
                            documentPath = documentPath,
                            name = request.name,
                            indices = request.indices,
                            selection = request.selection,
                            scope = request.scope,
                            projectName = request.projectName
                        )
                    "run" -> {
                        val run = OperationTranscript.runMacro(
                            documentPath = documentPath,
                            name = request.name,
                            targetBoxes = request.selection,
                            direction = "do",
                            scope = request.scope,
                            projectName = request.projectName
                        )
                        OperationTranscript.MacroResult(
                            success = run.success,
                            message = run.message,
                            name = run.name,
                            operationCount = run.applied,
                            scope = request.scope,
                            projectName = request.projectName,
                            direction = run.direction,
                            applied = run.applied,
                            skipped = run.skipped,
                            errors = run.errors,
                            output = run.output
                        )
                    }
                    "undo" -> {
                        val run = OperationTranscript.runMacro(
                            documentPath = documentPath,
                            name = request.name,
                            targetBoxes = request.selection,
                            direction = "undo",
                            scope = request.scope,
                            projectName = request.projectName
                        )
                        OperationTranscript.MacroResult(
                            success = run.success,
                            message = run.message,
                            name = run.name,
                            operationCount = run.applied,
                            scope = request.scope,
                            projectName = request.projectName,
                            direction = run.direction,
                            applied = run.applied,
                            skipped = run.skipped,
                            errors = run.errors,
                            output = run.output
                        )
                    }
                    else -> OperationTranscript.MacroResult(false, "Unknown macro command '${request.command}'.")
                }

                fun sendMacroResult(result: OperationTranscript.MacroResult) {
                    val detail = result.errors.firstOrNull()?.let { " First error: $it" }.orEmpty()
                    notify(result.message + detail, if (result.success) "macro" else "error")
                    reply(
                        Message("macro-transcript-command").payload(
                            MacroTranscriptCommandResponse(
                                success = result.success,
                                message = result.message,
                                name = result.name,
                                path = result.path,
                                operationCount = result.operationCount,
                                macros = result.macros,
                                content = result.content,
                                recording = result.recording,
                                draft = result.draft,
                                selection = result.selection,
                                documentMacros = result.documentMacros,
                                projectMacros = result.projectMacros,
                                workspaceMacros = result.workspaceMacros,
                                projectName = result.projectName,
                                scope = result.scope,
                                direction = result.direction,
                                applied = result.applied,
                                skipped = result.skipped,
                                errors = result.errors,
                                entries = result.entries,
                                offset = result.offset,
                                limit = result.limit,
                                total = result.total,
                                hasMore = result.hasMore,
                                prompt = result.prompt,
                                output = result.output
                            )
                        )
                    )
                }

                fun macroNotifyText(text: String): String =
                    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

                if (command == "run" || command == "undo") {
                    notify("${if (command == "undo") "Undoing" else "Running"} macro '${macroNotifyText(request.name)}'...", "macro")
                    GlobalScope.launch(Dispatchers.IO) {
                        thisSession.set(session)
                        thisSessionInfo.set(sessionInfo)
                        try {
                            sendMacroResult(macroResultForRequest())
                        } catch (t: Throwable) {
                            lprintln("error,operation-transcript", "Macro command '${request.command}' failed: ${t.message}")
                            lprintln("error,operation-transcript", t.stackTraceToString())
                            sendMacroResult(
                                OperationTranscript.MacroResult(
                                    false,
                                    "Macro '${request.name}' failed: ${t.message ?: t.javaClass.simpleName}.",
                                    name = request.name,
                                    scope = request.scope,
                                    projectName = request.projectName,
                                    errors = listOf(t.message ?: t.javaClass.name)
                                )
                            )
                        } finally {
                            thisSession.remove()
                            thisSessionInfo.remove()
                        }
                    }
                    return@mutableSetOf Handled.yes
                }

                sendMacroResult(macroResultForRequest())
                Handled.yes
            })


            handlers["hello"] = mutableSetOf({ message, reply ->
                val hello = Json.decodeFromJsonElement<Hello>(message.payload!!)
                lprintln("info,server", "adding a session for $hello to ${thisSession.get()}")
                launchDocumentSessionAttach(
                    session = thisSession.get(),
                    documentName = hello.filename,
                    username = hello.username,
                    scratch = hello.scratch,
                    sessionKey = hello.sessionKey
                )

                hello.shaderSelectionContext?.takeIf { it.isNotBlank() }?.let {
                    joinSelectionContext(it, thisSession.get())
                }

                Handled.yes
            })

            handlers["set-string"] = mutableSetOf({ message, reply ->

                val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)
                val isEditorStateProperty = set.property.endsWith("_editorState")
                val targetBox = box(set.uid)
                thisSessionInfo.get().recordPropertySyncOrigin(
                    set.uid,
                    set.property,
                    set.value,
                    set.clientId,
                    set.clientSequence
                )

                val oldValue = if (set.property == "name") {
                    targetBox.name
                } else {
                    targetBox.getPropertyRaw(set.property)?.toString().orEmpty()
                }

                if (set.property == "name") {
                    lprintln("debug,server", "got set-string for name ${set.value}")
                    targetBox.name = set.value
                    OperationTranscript.recordEdit(targetBox, set.property, oldValue, targetBox.name)
                    if (targetBox.getPropertyRaw(AI4_AUTO_NAME_PROPERTY).isTrue()) {
                        targetBox.setPropertyRaw(AI4_AUTO_NAME_PROPERTY, false)
                    }
                    targetBox.setPropertyRaw(AI4_AUTO_NAME_SIGNATURE_PROPERTY, "", doResync = false)
                } else {
                    if (set.property == "contents") {
                        try {
                            writeFileProxyContents(targetBox, set.value)
                        } catch (e: Exception) {
                            notify(e.message ?: "Could not write file proxy contents", "error")
                        }
                    }
                    // Monaco view/cursor state should persist with the document, but live rebroadcast
                    // makes multiple clients on the same box fight over their local editor position.
                    targetBox.setPropertyRaw(set.property, set.value, doResync = !isEditorStateProperty)
                    OperationTranscript.recordEdit(
                        targetBox,
                        set.property,
                        oldValue,
                        targetBox.getPropertyRaw(set.property)?.toString().orEmpty()
                    )
                }

                // If this is a sidecar-backed property, mark it for persistence.
                if (Box.ioSeparateProperties.containsKey(set.property) && !thisSessionInfo.get().ephemeral) {
                    thisSessionInfo.get().markSidecarPending(set.uid, set.property)
                }

                Handled.yes
            })
            handlers["provisional-accept"] = mutableSetOf({ message, reply ->
                val target = Json.decodeFromJsonElement<ProvisionalTarget>(message.payload!!)
                acceptProvisional(thisSessionInfo.get().filename, target.uid, target.property)
                Handled.yes
            })
            handlers["provisional-record-accepted-value"] = mutableSetOf({ message, reply ->
                val target = Json.decodeFromJsonElement<ProvisionalAcceptedValue>(message.payload!!)
                recordAcceptedProvisionalValue(
                    thisSessionInfo.get().filename,
                    target.uid,
                    target.property,
                    target.value
                )
                Handled.yes
            })
            handlers["provisional-reject"] = mutableSetOf({ message, reply ->
                val target = Json.decodeFromJsonElement<ProvisionalTarget>(message.payload!!)
                rejectProvisional(thisSessionInfo.get().filename, target.uid, target.property)
                Handled.yes
            })
            handlers["provisional-accept-all"] = mutableSetOf({ message, reply ->
                acceptAllProvisional(thisSessionInfo.get().filename)
                Handled.yes
            })
            handlers["provisional-reject-all"] = mutableSetOf({ message, reply ->
                rejectAllProvisional(thisSessionInfo.get().filename)
                Handled.yes
            })
            handlers["set-viewport"] = mutableSetOf({ message, reply ->
                val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)
                try {
                    thisSessionInfo.get().boxes.forEach {
                        it.value.viewport = set.value
                    }
                    doResync()
                    Handled.yes
                } catch (n: NullPointerException) {
                    // this is fine, we're just early on the set-viewport
                    Handled.yes
                }
            })

            handlers["set-rect"] = mutableSetOf({ message, reply ->

                val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)

                // Updates box frame or property; resyncs on main thread
                if (set.property == "frame") {
                    val b = box(set.uid)
                    val oldFrame = b.frame
                    b.frame = set.value
                    OperationTranscript.recordMove(b, oldFrame, b.frame)
                    inMain {
                        doResync() // subsequent calls do nothing, so this effectively combines multiple moves to the same update
                    }
                } else {
                    val b = box(set.uid)
                    val oldValue = b.getPropertyRaw(set.property)?.toString().orEmpty()
                    b.setPropertyRaw(set.property, set.value, doResync = true)
                    OperationTranscript.recordEdit(
                        b,
                        set.property,
                        oldValue,
                        b.getPropertyRaw(set.property)?.toString().orEmpty()
                    )
                    inMain {
                        doResync()
                    }
                }

                Handled.yes
            })

            handlers["set-rects"] = mutableSetOf({ message, reply ->
                val set = Json.decodeFromJsonElement<Set_rects>(message.payload!!)

                if (set.values.isEmpty()) return@mutableSetOf Handled.yes

                val frameUpdates = mutableListOf<Set_rect>()
                var hasNonFrameUpdates = false

                set.values.forEach { update ->
                    if (update.property == "frame") {
                        val b = box(update.uid)
                        val oldFrame = b.frame
                        b.frame = update.value
                        OperationTranscript.recordMove(b, oldFrame, b.frame)
                        needsResync.remove(b to "frame")
                        frameUpdates += Set_rect(uid = update.uid, property = "frame", value = b.frame)
                    } else {
                        hasNonFrameUpdates = true
                        val b = box(update.uid)
                        val oldValue = b.getPropertyRaw(update.property)?.toString().orEmpty()
                        b.setPropertyRaw(update.property, update.value, doResync = true)
                        OperationTranscript.recordEdit(
                            b,
                            update.property,
                            oldValue,
                            b.getPropertyRaw(update.property)?.toString().orEmpty()
                        )
                    }
                }

                if (frameUpdates.isNotEmpty()) {
                    val messageOut = Message("set-rects").payload(Set_rects(frameUpdates))
                    thisSessionInfo.get().sessions.forEach { _send(it, messageOut) }
                }

                if (hasNonFrameUpdates) {
                    inMain {
                        doResync()
                    }
                }

                Handled.yes
            })

            handlers["complete"] = mutableSetOf({ message, reply ->

                val exec = Json.decodeFromJsonElement<CompletionRequest>(message.payload!!)

                val box = box(exec.uid)

                box.ke.complete(box, exec, reply)

                Handled.yes
            })

            handlers["symbol-context"] = mutableSetOf({ message, reply ->
                val exec = Json.decodeFromJsonElement<SymbolContextRequest>(message.payload!!)
                val box = box(exec.uid)

                GlobalScope.launch(Dispatchers.Default) {
                    val result = box.ke.describeSymbolContext(box, exec)
                    reply(Message("symbol-context").payload(result))
                }

                Handled.yes
            })

            handlers["glsl-context"] = mutableSetOf({ message, reply ->
                val exec = Json.decodeFromJsonElement<SymbolContextRequest>(message.payload!!)
                val box = box(exec.uid)

                GlobalScope.launch(Dispatchers.Default) {
                    reply(Message("glsl-context").payload(GlslCompletionSupport.describeContext(box, exec)))
                }

                Handled.yes
            })

            handlers["debug-probe"] = mutableSetOf({ message, reply ->
                val request = Json.decodeFromJsonElement<DebugProbeRequest>(message.payload!!)
                val box = box(request.uid)

                GlobalScope.launch(Dispatchers.Default) {
                    val execution = box.ke as? KotlinExecution
                    if (execution == null) {
                        reply(
                            Message("debug-probe-event").payload(
                                DebugProbeEvent(
                                    requestId = request.requestId,
                                    uid = request.uid,
                                    phase = "error",
                                    symbol = request.symbol,
                                    line = request.line,
                                    html = "Debug probes are only available for Kotlin boxes.",
                                    channel = "debug probe",
                                    annotation = "<i>debug</i>",
                                    done = true
                                )
                            )
                        )
                        return@launch
                    }

                    RuntimeDebugProbeService.startProbe(box, request, execution, reply)
                }

                Handled.yes
            })

            handlers["complete-kotlin-ai"] = mutableSetOf({ message, reply ->
                val exec = Json.decodeFromJsonElement<CompletionRequest>(message.payload!!)
                val box = box(exec.uid)
                lprintln(
                    "info,ai_completions",
                    "kotlin-ai request uid=${exec.uid} prop=${exec.property} line=${exec.line} col=${exec.column} pos=${exec.position} len=${exec.allCode.length}"
                )
                val bounds = runCatching {
                    getTokenBounds(exec.allCode, exec.position)
                }.getOrElse { CompletionTokenBounds(exec.position, exec.position) }
                val activeToken = exec.allCode
                    .substring(bounds.start.coerceIn(0, exec.allCode.length), bounds.end.coerceIn(0, exec.allCode.length))
                    .trim()

                fun normalizeAiInsertion(raw: String): String {
                    if (activeToken.isBlank() || raw.isBlank()) return raw
                    if (raw.trimStart().startsWith(activeToken)) return raw
                    val identifierToken = activeToken.all { it.isLetterOrDigit() || it == '_' }
                    val startsLikeIdentifier = raw.firstOrNull()?.let { it.isLetterOrDigit() || it == '_' } == true
                    return if (identifierToken && startsLikeIdentifier) activeToken + raw else raw
                }

                if (box.ke !is KotlinExecution) {
                    lprintln("info,ai_completions", "kotlin-ai skip: box is not KotlinExecution (uid=${exec.uid})")
                    reply(
                        Message("complete-kotlin-ai").payload(
                            Completion(
                                boundsStart = bounds.start,
                                boundsEnd = bounds.end,
                                metadata = emptyList()
                            )
                        )
                    )
                    return@mutableSetOf Handled.yes
                }

                val activeAiCompletions = aiKotlinCompletionsInFlight.incrementAndGet()
                val throttleKey = "${exec.uid}:${exec.property}"
                val now = System.currentTimeMillis()
                val last = aiKotlinNotifyThrottle[throttleKey] ?: 0L
                if (activeAiCompletions == 1 || now - last > 1500) {
                    aiKotlinNotifyThrottle[throttleKey] = now
                    notify("Kotlin AI completion… <<spinner>>", "*ai_completions")
                }

                val docName = File(thisSessionInfo.get().filename).name
                val fileKey = "afield://$docName/${exec.uid}/${exec.property}"

                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val t0 = System.nanoTime()
                        val candidates = runCatching {
                            Ai4KotlinAutocomplete.complete(
                                fileKey = fileKey,
                                code = exec.allCode,
                                line = exec.line,
                                column = exec.column,
                                position = exec.position
                            )
                        }.onFailure {
                            lprintln(
                                "warning,ai_completions",
                                "kotlin-ai failed uid=${exec.uid}: ${it.javaClass.simpleName}: ${it.message}"
                            )
                        }.getOrElse { emptyList() }
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        lblock(
                            "info, ai_completions", "responses are"

                        ) {
                            lprint(
                                "info, ai_completions",
                                "kotlin-ai response uid=${exec.uid} prop=${exec.property} candidates=${candidates.size} in ${ms}ms"
                            )
                            candidates.forEach {
                                lprint("info, ai_completions", "${it.text} ${it.documentation}")
                            }
                        }

                        val metadata = candidates.take(3).mapIndexed { idx, candidate ->
                            val insertion = normalizeAiInsertion(candidate.text)
                            val display = insertion.lineSequence().firstOrNull()?.trim().orEmpty()
                            val doc = buildString {
                                val notes = candidate.documentation.ifBlank { "GPT-5.1-codex-mini" }
                                append(notes.trim())
                                if (insertion.isNotBlank()) {
                                    append("\n\n```kotlin\n")
                                    append(insertion.trimEnd())
                                    append("\n```")
                                }
                            }.trimEnd()

                            Metadata(
                                icon = "keyword",
                                tail = "AI ${idx + 1}",
                                text = insertion,
                                displayText = if (display.isNotEmpty()) display else "AI completion ${idx + 1}",
                                documentation = doc,
                                category = "ai"
                            )
                        }

                        reply(
                            Message("complete-kotlin-ai").payload(
                                Completion(
                                    boundsStart = bounds.start,
                                    boundsEnd = bounds.end,
                                    metadata = metadata
                                )
                            )
                        )
                    } finally {
                        val remaining = aiKotlinCompletionsInFlight.decrementAndGet()
                        if (remaining <= 0) {
                            aiKotlinCompletionsInFlight.set(0)
                            notify("Kotlin AI completion finished", "*ai_completions")
                        }
                    }
                }

                Handled.yes
            })

            handlers["resolve-completion"] = mutableSetOf({ message, reply ->
                val exec = Json.decodeFromJsonElement<CompletionResolveRequest>(message.payload!!)
                val docName = File(thisSessionInfo.get().filename).name
                val fileKey = "afield://$docName/${exec.uid}/${exec.property}"

                GlobalScope.launch(Dispatchers.IO) {
                    val doc = runCatching {
                        Ai4CompletionDoc.resolveDoc(
                            fileKey = fileKey,
                            label = exec.label,
                            displayText = exec.displayText,
                            insertText = exec.insertText,
                            detail = exec.detail,
                            existingDoc = exec.existingDocumentation,
                            allCode = exec.allCode,
                            line = exec.line,
                            column = exec.column,
                            documentationSourceName = exec.documentationSourceName,
                            documentationSourcePath = exec.documentationSourcePath,
                            documentationSourceLine = exec.documentationSourceLine
                        )
                    }.getOrElse { "" }
                    reply(Message("resolve-completion").payload(CompletionResolveResult(doc)))
                }

                Handled.yes
            })


            handlers["perform-action"] = mutableSetOf(
                { message, reply: (Message) -> Unit ->
                    val exec = Json.decodeFromJsonElement<Perform_action>(message.payload!!)
                    val action = actionTable[exec.uid_action]
                    if (action == null) {
                        lprintln("warning,server", "no such action '$action'")
                    } else {
                        val previousProperty = currentActionProperty.get()
                        currentActionProperty.set(exec.uid_property.ifBlank { "code" })
                        try {
                            action.invoke(exec.uid_currentSelection.mapNotNull { box(it, createIfMissing = false) })
                        } finally {
                            currentActionProperty.set(previousProperty)
                        }
                    }
                    Handled.yes
                })

            handlers["copy-boxes"] = mutableSetOf({ message, reply ->
                val req = Json.decodeFromJsonElement<CopyBoxes>(message.payload!!)
                val info = thisSessionInfo.get()
                val boxes = req.uids.mapNotNull { uid ->
                    if (uid == "root") info.root else info.boxes[uid]
                }.toSet()
                val boxesWithNested = collectAllNestedBoxes(boxes)
                val edn = SavingContext().exportEdnPackage(boxesWithNested, info.filename)
                val filename = File(info.filename).nameWithoutExtension.ifBlank { "document" }
                val countMessage = formatBoxCountMessage(boxes.size, boxesWithNested.size, "Copied")
                notify("$countMessage to clipboard", "copy")
                reply(Message("copy-boxes").payload(CopyBoxesResult(edn, filename)))
                Handled.yes
            })

            handlers["export-document"] = mutableSetOf({ _, reply ->
                val info = thisSessionInfo.get()
                // Include root box in export to preserve root properties like bookmarks
                val allBoxes = info.boxes.values.toMutableSet()
                allBoxes.add(info.root)
                val edn = SavingContext().exportEdnPackage(allBoxes, info.filename)
                val filename = File(info.filename).nameWithoutExtension.ifBlank { "document" }
                reply(Message("export-document").payload(ExportDocumentResult(edn, filename)))
                Handled.yes
            })

            handlers["paste-boxes"] = mutableSetOf({ message, reply ->
                val req = Json.decodeFromJsonElement<PasteBoxes>(message.payload!!)
                val info = thisSessionInfo.get()
                val usedIds = info.boxes.keys
                val ctx = SavingContext()
                val transferIds = ctx.readEdnTransfersFromString(req.edn).map { it.id }.toSet()
                // Keep refs to boxes that already exist in this document, but only remap ids that belong to
                // the pasted payload itself.
                val allowedRefIds = transferIds + usedIds + "root"
                val idMap = mutableMapOf<String, String>()
                val filter: (String) -> String = { source ->
                    when {
                        source == "root" -> "root"
                        source !in transferIds -> source
                        else -> idMap.getOrPut(source) {
                            if (usedIds.contains(source)) UUID.randomUUID().toString() else source
                        }
                    }
                }

                val newBoxes = ctx.loadFromEdnText(req.edn, info.filename, filter, allowedRefIds)

                // Only translate top-level boxes, not nested children. Nested boxes have frames that are
                // relative to their parent's position (offset by -parent.x, -(parent.y + parent.h)).
                // Translating them would incorrectly add the offset twice since they move with their parent.
                val nestedBoxes = newBoxes.flatMap { it.nested }.toSet()
                newBoxes.forEach { b ->
                    if (b.id != "root" && b !in nestedBoxes) {
                        b.frame = b.frame.copy(x = b.frame.x + 10.0, y = b.frame.y + 10.0)
                    }
                    b.resyncAll()
                }
                info.topologyGeneration++
                doResync()
                notify("Pasted ${newBoxes.size} box${if (newBoxes.size == 1) "" else "es"} (+10,+10)", "paste")
                info.autorunBoxes(newBoxes)
                reply(Message("paste-boxes").payload(PasteBoxesResult(newBoxes.map { it.id })))
                Handled.yes
            })

            handlers["slideshow-command"] = mutableSetOf({ message, reply ->
                val cmd = Json.decodeFromJsonElement<SlideShowCommand>(message.payload!!)
                val info = thisSessionInfo.get()

                // Find the box with an active slideshow
                val slideShowBox = info.boxes.values.firstOrNull { it.getPropertyRaw("slideShow") != null }
                if (slideShowBox != null) {
                    val slideShow = slideShowBox.getPropertyRaw("slideShow") as? SlideShowInstance
                    if (slideShow != null) {
                        GlobalScope.launch {
                            when {
                                cmd.command == "next" -> slideShow.next()
                                cmd.command == "previous" -> slideShow.previous()
                                cmd.command == "start" -> slideShow.start()
                                cmd.command == "home" -> slideShow.home()
                                cmd.command == "stop" -> slideShow.stop()
                                cmd.command.startsWith("callback:") -> {
                                    // Handle custom link callbacks
                                    val callbackName = cmd.command.substring(9) // Remove "callback:" prefix
                                    val executed = slideShow.executeCallback(callbackName)
                                    if (!executed) {
                                        lprintln("warning,slideshow", "No callback registered for: $callbackName")
                                    }
                                }

                                else -> {
                                    // Try to parse as a step number
                                    val stepNum = cmd.command.toIntOrNull()
                                    if (stepNum != null) {
                                        slideShow.goToStep(stepNum)
                                    }
                                }
                            }
                        }
                    }
                }
                Handled.yes
            })

            handlers["controller-selection"] = mutableSetOf({ message, reply ->
                val selection = Json.decodeFromJsonElement<ControllerSelectionState>(message.payload!!)
                recordControllerSelection(thisSessionInfo.get(), selection)
                // Fan the selection out to peers sharing this session's selection context
                // (never back to the originator, or it would echo).
                val me = thisSession.get()
                selectionContextOf(me)?.let { context ->
                    selectionContextSessions[context]?.forEach { peer ->
                        if (peer != me) _send(peer, message)
                    }
                }
                Handled.yes
            })

            handlers["slider-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<SliderUpdate>(message.payload!!)
                lprintln("debug,slider", "Received slider update: ${update.sliderId} = ${update.value}")
                val updatedControls = updateControllerControlValue(update.sliderId) { it.copy(value = update.value) }
                broadcastControllerControlUpdates(updatedControls, thisSessionInfo.get().filename)

                // Get the callback for this slider and invoke it
                val callback = getSliderCallback(update.sliderId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(update.value)
                        } catch (e: Exception) {
                            lprintln("error,slider", "Error in slider callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,slider", "No callback registered for slider: ${update.sliderId}")
                }

                Handled.yes
            })

            handlers["vec2-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<Vec2Update>(message.payload!!)
                lprintln("debug,controls", "Received vec2 update: ${update.controlId} = (${update.x}, ${update.y})")
                val updatedControls =
                    updateControllerControlValue(update.controlId) { it.copy(x = update.x, y = update.y) }
                broadcastControllerControlUpdates(updatedControls, thisSessionInfo.get().filename)

                val callback = getVec2Callback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(Vector2f(update.x.toFloat(), update.y.toFloat()))
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in vec2 callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for vec2: ${update.controlId}")
                }

                Handled.yes
            })

            handlers["checkbox-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<CheckboxUpdate>(message.payload!!)
                lprintln("debug,controls", "Received checkbox update: ${update.controlId} = ${update.value}")
                val updatedControls = updateControllerControlValue(update.controlId) { it.copy(checked = update.value) }
                broadcastControllerControlUpdates(updatedControls, thisSessionInfo.get().filename)

                val callback = getCheckboxCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(update.value)
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in checkbox callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for checkbox: ${update.controlId}")
                }

                Handled.yes
            })

            handlers["fline-click"] = mutableSetOf({ message, reply ->
                val click = Json.decodeFromJsonElement<FLineClick>(message.payload!!)
                lprintln("debug,fline", "Received FLine click: ${click.callbackId}")

                val callback = getFLineClickCallback(click.callbackId)
                if (callback != null) {
                    val intersection = FLineIntersection(
                        closestVertexIndex = click.closestVertexIndex,
                        position = Vector3f(click.x.toFloat(), click.y.toFloat(), click.z.toFloat())
                    )
                    GlobalScope.launch {
                        try {
                            callback(intersection)
                        } catch (e: Exception) {
                            lprintln("error,fline", "Error in FLine click callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,fline", "No callback registered for FLine: ${click.callbackId}")
                }

                Handled.yes
            })

            handlers["custom-marking-request"] = mutableSetOf({ message, reply ->
                val request = Json.decodeFromJsonElement<CustomMarkingRequest>(message.payload!!)
                val info = thisSessionInfo.get()
                val box = info.boxes[request.uid]
                if (box != null) {
                    val entries = prepareCustomMarkingEntries(box)
                    val response = CustomMarkingResponse(request.uid, entries)
                    reply(Message(message.replyTo ?: "custom-marking-response").payload(response))
                }
                Handled.yes
            })

            handlers["custom-marking-fire"] = mutableSetOf({ message, reply ->
                val fire = Json.decodeFromJsonElement<CustomMarkingFire>(message.payload!!)
                GlobalScope.launch {
                    try {
                        fireCustomMarkingCallback(fire.callbackId)
                    } catch (e: Exception) {
                        lprintln("error,custom-marking", "Error in custom marking callback: ${e.message}")
                        e.printStackTrace()
                    }
                }
                Handled.yes
            })

            handlers["fline-edit-translate"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<FLineTranslatePoints>(message.payload!!)
                val session = getFLineEditSession(update.sessionId)
                if (session == null) {
                    lprintln("warning,fline", "No edit session registered for FLine session: ${update.sessionId}")
                    return@mutableSetOf Handled.yes
                }

                GlobalScope.launch {
                    try {
                        session.translate(update)
                    } catch (e: Exception) {
                        lprintln("error,fline", "Error applying FLine edit translation: ${e.message}")
                        e.printStackTrace()
                    }
                }

                Handled.yes
            })

            handlers["fline-edit-add-line"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<FLineAddLine>(message.payload!!)
                val session = getFLineEditSession(update.sessionId)
                if (session == null) {
                    lprintln("warning,fline", "No edit session registered for FLine session: ${update.sessionId}")
                    return@mutableSetOf Handled.yes
                }

                GlobalScope.launch {
                    try {
                        session.addLine(update)
                    } catch (e: Exception) {
                        lprintln("error,fline", "Error adding FLine to edit session: ${e.message}")
                        e.printStackTrace()
                    }
                }

                Handled.yes
            })

            handlers["fline-edit-begin-line"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<FLineBeginLine>(message.payload!!)
                val session = getFLineEditSession(update.sessionId)
                if (session == null) {
                    lprintln("warning,fline", "No edit session registered for FLine session: ${update.sessionId}")
                    return@mutableSetOf Handled.yes
                }

                GlobalScope.launch {
                    try {
                        session.beginLine(update)
                    } catch (e: Exception) {
                        lprintln("error,fline", "Error beginning live FLine stroke: ${e.message}")
                        e.printStackTrace()
                    }
                }

                Handled.yes
            })

            handlers["fline-edit-append-line"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<FLineAppendLine>(message.payload!!)
                val session = getFLineEditSession(update.sessionId)
                if (session == null) {
                    lprintln("warning,fline", "No edit session registered for FLine session: ${update.sessionId}")
                    return@mutableSetOf Handled.yes
                }

                GlobalScope.launch {
                    try {
                        session.appendLine(update)
                    } catch (e: Exception) {
                        lprintln("error,fline", "Error appending live FLine stroke: ${e.message}")
                        e.printStackTrace()
                    }
                }

                Handled.yes
            })

            handlers["fline-edit-end-line"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<FLineEndLine>(message.payload!!)
                val session = getFLineEditSession(update.sessionId)
                if (session == null) {
                    lprintln("warning,fline", "No edit session registered for FLine session: ${update.sessionId}")
                    return@mutableSetOf Handled.yes
                }

                GlobalScope.launch {
                    try {
                        session.endLine(update)
                    } catch (e: Exception) {
                        lprintln("error,fline", "Error ending live FLine stroke: ${e.message}")
                        e.printStackTrace()
                    }
                }

                Handled.yes
            })

            handlers["fline-edit-clear"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<FLineClearSession>(message.payload!!)
                val session = getFLineEditSession(update.sessionId)
                if (session == null) {
                    lprintln("warning,fline", "No edit session registered for FLine session: ${update.sessionId}")
                    return@mutableSetOf Handled.yes
                }

                GlobalScope.launch {
                    try {
                        session.clear()
                    } catch (e: Exception) {
                        lprintln("error,fline", "Error clearing FLine edit session: ${e.message}")
                        e.printStackTrace()
                    }
                }

                Handled.yes
            })

            handlers["string-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<StringUpdate>(message.payload!!)
                lprintln("debug,controls", "Received string update: ${update.controlId} = ${update.value}")
                val updatedControls = updateControllerControlValue(update.controlId) { it.copy(text = update.value) }
                broadcastControllerControlUpdates(updatedControls, thisSessionInfo.get().filename)

                val callback = getStringCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(update.value)
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in string callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for string: ${update.controlId}")
                }

                Handled.yes
            })

            handlers["file-upload-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<FileUploadUpdate>(message.payload!!)
                lprintln(
                    "debug,controls",
                    "Received file upload: ${update.controlId} with ${update.files.size} file(s)"
                )

                // Always store for polling (Python widgets read via getFileUploadData)
                storeFileUploadData(update.controlId, update.files)

                // Also call explicit callback if registered (Kotlin widgets)
                val callback = getFileUploadCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(update.files)
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in file upload callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }

                Handled.yes
            })

            handlers["color-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<ColorUpdate>(message.payload!!)
                lprintln(
                    "debug,controls",
                    "Received color update: ${update.controlId} = rgba(${update.r}, ${update.g}, ${update.b}, ${update.a})"
                )
                val updatedControls = updateControllerControlValue(update.controlId) {
                    it.copy(
                        r = update.r,
                        g = update.g,
                        b = update.b,
                        a = update.a,
                        value = update.a
                    )
                }
                broadcastControllerControlUpdates(updatedControls, thisSessionInfo.get().filename)

                val callback = getColorCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(
                                Vector4f(
                                    update.r.toFloat(),
                                    update.g.toFloat(),
                                    update.b.toFloat(),
                                    update.a.toFloat()
                                )
                            )
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in color callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for color: ${update.controlId}")
                }

                Handled.yes
            })

            // Layers system handlers
            handlers["set-visible-layers"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<SetVisibleLayers>(message.payload!!)
                lprintln("debug,layers", "Setting visible layers: ${update.layers}, animated: ${update.animated}")
                thisSessionInfo.get().visibleLayers.clear()
                thisSessionInfo.get().visibleLayers.addAll(update.layers)
                // Update visibility of all boxes based on their layers
                thisSessionInfo.get().boxes.values.forEach { box ->
                    updateBoxVisibility(box, thisSessionInfo.get())
                }

                // If animated, send custom visibility change messages
                if (update.animated) {
                    thisSessionInfo.get().boxes.values.forEach { box ->
                        val visibilityMessage = Message("set-visibility-animated").payload(
                            mapOf(
                                "uid" to box.id,
                                "visible" to box.visible,
                                "duration" to update.duration
                            )
                        )
                        box.inside.sessions.forEach { _send(it, visibilityMessage) }
                    }
                } else {
                    doResync()
                }
                Handled.yes
            })

            handlers["set-active-layer"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<SetActiveLayer>(message.payload!!)
                lprintln("debug,layers", "Setting active layer: ${update.layer}, animated: ${update.animated}")
                val oldActiveLayer = thisSessionInfo.get().activeLayer

                // No-op if the active layer is unchanged.
                if (update.layer == oldActiveLayer) {
                    Handled.yes
                } else {

                    // When leaving an active layer, save current positions to that layer's frames
                    if (oldActiveLayer != null) {
                        thisSessionInfo.get().boxes.values.forEach { box ->
                            // Only save if box is visible in the old layer
                            val boxLayers = parseLayersList(box.layers)
                            if (boxLayers.contains(oldActiveLayer)) {
                                box.layerFrames[oldActiveLayer] = box.frame
                                lprintln(
                                    "debug,layers",
                                    "Saved frame for box ${box.id} in layer $oldActiveLayer: ${box.frame}"
                                )
                            }
                        }
                    }

                    thisSessionInfo.get().activeLayer = update.layer

                    // Apply frame overrides for the new active layer
                    if (update.layer != null) {
                        thisSessionInfo.get().boxes.values.forEach { box ->
                            val layerFrame = box.layerFrames[update.layer]
                            if (layerFrame != null) {
                                box.frame = layerFrame
                                lprintln(
                                    "debug,layers",
                                    "Applied frame for box ${box.id} from layer ${update.layer}: ${layerFrame}"
                                )

                                // Send animated rect if animation is requested
                                if (update.animated) {
                                    val animMessage = Message("set-animated-rect").payload(
                                        Set_animated_rect(box.id, "frame", layerFrame, update.duration)
                                    )
                                    box.inside.sessions.forEach { _send(it, animMessage) }
                                }
                            }
                        }
                    }

                    // Do regular resync if not animated (for non-frame properties and boxes without layer frames)
                    if (!update.animated) {
                        doResync()
                    }

                    Handled.yes
                }
            })

            handlers["get-all-layers"] = mutableSetOf({ message, reply ->
                val allLayers = mutableSetOf<String>()
                thisSessionInfo.get().boxes.values.forEach { box ->
                    val layers = parseLayersList(box.layers)
                    allLayers.addAll(layers)
                    allLayers.addAll(box.layerFrames.keys)
                }
                if (allLayers.isEmpty()) {
                    allLayers.add("default")
                }
                reply(Message("get-all-layers").payload(AllLayersResult(allLayers.sorted())))
                Handled.yes
            })

            handlers["output-ack"] = mutableSetOf({ _, _ ->
                // Release one pending output slot for this session and flush any rolling-buffer output
                // that can now fit through the pressure gate.
                releaseAndFlushOutputBackPressure(thisSession.get())
                Handled.yes
            })


            registerTemplate(home)
            persistWorkspacePath(home)

            val preferredPort = System.getenv("PORT")?.trim()?.toIntOrNull() ?: 9092
            val portFallbackCount =
                System.getenv("AFIELD_PORT_FALLBACK_COUNT")?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 20
            var port = preferredPort
            val httpsHost = "0.0.0.0"
            val keystorePath =
                System.getenv("AFIELD_KEYSTORE_PATH") ?: File(app, "config/field4-local.p12").absolutePath
            val keystorePassword = System.getenv("AFIELD_KEYSTORE_PASS") ?: "changeit"
            val keystoreAlias = System.getenv("AFIELD_KEYSTORE_ALIAS") ?: "field4"
            val keystoreFile = ensureLocalKeystore(File(keystorePath), keystorePassword, keystoreAlias)
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(keystoreFile.inputStream(), keystorePassword.toCharArray())
            }

            fun createServer(bindPort: Int): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
                return embeddedServer(Netty, configure = {
                    loopbackHttpPortFor(bindPort)?.let { localHttpPort ->
                        connector {
                            this.host = "127.0.0.1"
                            this.port = localHttpPort
                        }
                    }
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = keystoreAlias,
                        keyStorePassword = { keystorePassword.toCharArray() },
                        privateKeyPassword = { keystorePassword.toCharArray() }
                    ) {
                        this.host = httpsHost
                        this.port = bindPort
                    }
                }) {
                    install(ContentNegotiation) {
                        json()
                    }

                    install(CORS) {
                        allowHeader(HttpHeaders.ContentType)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Delete)
                        anyHost()
                    }

                    intercept(ApplicationCallPipeline.Setup) {
                        if (call.request.path() == "/api/import-offline-doc") {
                            call.response.headers.append("Access-Control-Allow-Private-Network", "true")
                            call.response.headers.append(HttpHeaders.Vary, "Origin")
                            call.response.headers.append(HttpHeaders.Vary, "Access-Control-Request-Private-Network")
                        }
                    }

                    install(Compression) {
                        gzip()
                        // The welcome-docs stream flushes one NDJSON line per document as it is
                        // parsed; routing it through the gzip buffer would coalesce those flushes
                        // and defeat the point of streaming, so leave it uncompressed.
                        excludeContentType(ContentType.parse("application/x-ndjson"))
                    }

                    install(WebSockets) {
                        pingPeriod = 10.seconds
                        timeout = 30.minutes
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }

                    install(PartialContent)
                    install(AutoHeadResponse)

                    routing {
                        get("/health") {
                            call.respondText("ok", ContentType.Text.Plain)
                        }

                        installControllerRoutes(::currentFieldPreferences)

                        get("/app-url") {
                            val preferences = currentFieldPreferences().also(::applyFieldPreferences)
                            val requestScheme = call.request.local.scheme
                            val requestPort = call.request.local.serverPort
                            val baseUrl = "$requestScheme://localhost:$requestPort"
                            val lastDocument = desktopStartupDocumentName()
                            val url = if (preferences.autoOpenLastDocumentInDesktop && lastDocument != null) {
                                val target = URLEncoder.encode(lastDocument, "UTF-8")
                                "$baseUrl/index.html?file=$target"
                            } else {
                                "$baseUrl/welcome"
                            }
                            call.respondText(url, ContentType.Text.Plain)
                        }

                        get("/api/welcome-docs") {
                            val preferences = currentFieldPreferences().also(::applyFieldPreferences)
                            val recentDocuments = parseRecentDocuments(recentFns)
                            val liveSessions = documents.values
                                .asSequence()
                                .filter { it.sessions.isNotEmpty() }
                                .toList()
                            val liveWorkspaceCounts = liveSessions
                                .filter { !it.ephemeral }
                                .groupingBy { it.documentName }
                                .fold(0) { count, info -> count + info.sessions.size }
                            val runningExecutionCounts = documents.values
                                .asSequence()
                                .filter { !it.ephemeral }
                                .map { info ->
                                    info.documentName to info.boxes.values.count { box ->
                                        box.currentRunningStates.isNotEmpty() ||
                                                box.jobs.any { job -> !job.isCancelled && job.isActive }
                                    }
                                }
                                .filter { (_, count) -> count > 0 }
                                .groupBy({ it.first }, { it.second })
                                .mapValues { (_, counts) -> counts.sum() }
                            val workspaceDocs = withContext(workspaceSearchDispatcher) {
                                welcomeDir(home, recentDocuments)
                            }
                            val projects = withContext(workspaceSearchDispatcher) {
                                listWorkspaceProjects(home)
                            }
                            val docs = workspaceDocs.map {
                                it.copy(
                                    runningSessionCount = liveWorkspaceCounts[it.filename] ?: 0,
                                    runningExecutionCount = runningExecutionCounts[it.filename] ?: 0
                                )
                            }
                            val fallback = desktopStartupDocumentName() ?: "welcome"
                            val defaultDocument = docs.firstOrNull()?.filename ?: fallback
                            val allTags = docs.flatMap { it.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                            val workspaceDocumentNames = docs.mapTo(linkedSetOf()) { it.filename }
                            val runningDocuments = liveSessions
                                .filter { it.documentName !in workspaceDocumentNames }
                                .sortedWith(
                                    compareByDescending<SessionInformation> { it.sessions.size }
                                        .thenBy { it.documentName.lowercase() }
                                        .thenBy { it.filename.lowercase() }
                                )
                                .map { info ->
                                    val params = mutableListOf(
                                        "file=${URLEncoder.encode(info.documentName, "UTF-8")}",
                                        "sessionKey=${URLEncoder.encode(info.filename, "UTF-8")}"
                                    )
                                    if (info.ephemeral) {
                                        params += "scratch=1"
                                    }
                                    RunningDocumentEntry(
                                        documentName = info.documentName,
                                        sessionCount = info.sessions.size,
                                        ephemeral = info.ephemeral,
                                        openUrl = "/index.html?${params.joinToString("&")}"
                                    )
                                }
                            val currentWorkspace = runCatching { File(home).canonicalFile.absolutePath }
                                .getOrElse { File(home).absolutePath }
                            val defaultWorkspace = runCatching { displayWorkspacePath(defaultWorkspacePath()) }
                                .getOrElse { defaultWorkspacePath() }
                            // Non-blocking snapshots: these return the last computed value instantly
                            // and refresh in the background, so the welcome poll never runs the
                            // telemetry SQLite scans or the semantic corpus rescan on a request or
                            // workspace-search dispatcher thread (which would otherwise stall a
                            // document the user clicks right after the list appears).
                            val codeUseSummary = CodeUseTelemetryService.summarySnapshot()
                            val semanticSettings = SemanticRetrievalService.settingsStatusSnapshot()
                            call.respond(
                                WelcomeDocsResponse(
                                    docs,
                                    fallback,
                                    defaultDocument,
                                    allTags,
                                    runningDocuments,
                                    currentWorkspace,
                                    defaultWorkspace,
                                    currentAiSettingsStatus(),
                                    preferences,
                                    projects,
                                    currentRuntimeInfo(),
                                    PythonInterface.currentPythonSettingsStatus(),
                                    CompletionDocPolishMonitor.status(),
                                    AiToolUseImprovementMonitor.status(),
                                    codeUseSummary,
                                    semanticSettings
                                )
                            )
                        }

                        // Streaming counterpart to /api/welcome-docs: emits one WelcomeEntry per
                        // line (NDJSON) as each document's metadata is summarized, in the same
                        // sorted order, so the welcome screen can paint documents as they arrive
                        // instead of blocking on the whole workspace scan.
                        get("/api/welcome-docs/stream") {
                            val recentDocuments = parseRecentDocuments(recentFns)
                            val liveWorkspaceCounts = documents.values
                                .asSequence()
                                .filter { it.sessions.isNotEmpty() && !it.ephemeral }
                                .groupingBy { it.documentName }
                                .fold(0) { count, info -> count + info.sessions.size }
                            val runningExecutionCounts = documents.values
                                .asSequence()
                                .filter { !it.ephemeral }
                                .map { info ->
                                    info.documentName to info.boxes.values.count { box ->
                                        box.currentRunningStates.isNotEmpty() ||
                                                box.jobs.any { job -> !job.isCancelled && job.isActive }
                                    }
                                }
                                .filter { (_, count) -> count > 0 }
                                .groupBy({ it.first }, { it.second })
                                .mapValues { (_, counts) -> counts.sum() }

                            val candidates = withContext(workspaceSearchDispatcher) {
                                welcomeDirCandidates(home, recentDocuments)
                            }

                            call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                                val now = System.currentTimeMillis()
                                val savingContext = SavingContext()
                                for (candidate in candidates) {
                                    val entry = withContext(workspaceSearchDispatcher) {
                                        summarizeWelcomeCandidate(candidate, now, savingContext)
                                    } ?: continue
                                    val merged = entry.copy(
                                        runningSessionCount = liveWorkspaceCounts[entry.filename] ?: 0,
                                        runningExecutionCount = runningExecutionCounts[entry.filename] ?: 0
                                    )
                                    write(Json.encodeToString(merged))
                                    write("\n")
                                    flush()
                                }
                            }
                        }

                        get("/api/completion-doc-polish") {
                            call.respond(CompletionDocPolishMonitor.status())
                        }

                        post("/api/completion-doc-polish") {
                            try {
                                val request = runCatching { call.receive<CompletionDocPolishRunRequest>() }
                                    .getOrElse { CompletionDocPolishRunRequest() }
                                call.respond(CompletionDocPolishMonitor.start(request.dryRun))
                            } catch (e: Exception) {
                                lprintln("error,server", "completion-doc polish launch failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "completion-doc polish launch failed")
                                )
                            }
                        }

                        get("/api/ai-tool-use-improvement") {
                            call.respond(AiToolUseImprovementMonitor.status())
                        }

                        post("/api/ai-tool-use-improvement") {
                            try {
                                val request = runCatching { call.receive<AiToolUseImprovementRunRequest>() }
                                    .getOrElse { AiToolUseImprovementRunRequest() }
                                call.respond(AiToolUseImprovementMonitor.start(request.dryRun, request.proposalCount))
                            } catch (e: Exception) {
                                lprintln("error,server", "AI tool-use improvement launch failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "AI tool-use improvement launch failed")
                                )
                            }
                        }

                        post("/api/ai-tool-use-improvement/clear-reviewed") {
                            try {
                                val request = runCatching { call.receive<AiToolUseImprovementClearRequest>() }
                                    .getOrElse { AiToolUseImprovementClearRequest() }
                                call.respond(AiToolUseImprovementMonitor.clearReviewed(request.recentOnly))
                            } catch (e: Exception) {
                                lprintln("error,server", "AI tool-use log clear failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "AI tool-use log clear failed")
                                )
                            }
                        }

                        get("/api/agent-worktrees") {
                            call.respond(AgentWorktreeManager.listProposals(File(app).canonicalFile))
                        }

                        post("/api/agent-worktrees/delete") {
                            try {
                                val request = call.receive<AgentWorktreeActionRequest>()
                                val response = AgentWorktreeManager.deleteProposal(File(app).canonicalFile, request.id)
                                call.respond(if (response.success) HttpStatusCode.OK else HttpStatusCode.BadRequest, response)
                                if (response.success) broadcastWelcomeEvent("agent-worktree-deleted")
                            } catch (e: Exception) {
                                lprintln("error,server", "agent worktree delete failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    AgentWorktreeActionResponse(false, message = e.message ?: "delete failed")
                                )
                            }
                        }

                        post("/api/agent-worktrees/merge") {
                            try {
                                val request = call.receive<AgentWorktreeActionRequest>()
                                val response = AgentWorktreeManager.mergeProposal(File(app).canonicalFile, request.id)
                                call.respond(if (response.success) HttpStatusCode.OK else HttpStatusCode.BadRequest, response)
                                if (response.success) broadcastWelcomeEvent("agent-worktree-merged")
                            } catch (e: Exception) {
                                lprintln("error,server", "agent worktree merge failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    AgentWorktreeActionResponse(false, message = e.message ?: "merge failed")
                                )
                            }
                        }

                        post("/api/agent-worktrees/restart") {
                            try {
                                val request = call.receive<AgentWorktreeActionRequest>()
                                val proposal = AgentWorktreeManager.proposal(File(app).canonicalFile, request.id)
                                    ?: throw IllegalArgumentException("Unknown generated worktree '${request.id}'")
                                val projectDir = File(proposal.path).canonicalFile
                                if (!projectDir.isDirectory) {
                                    throw IllegalArgumentException("Generated worktree is missing: ${proposal.path}")
                                }
                                call.respond(
                                    AgentWorktreeActionResponse(
                                        true,
                                        proposal,
                                        "Restarting Field from generated worktree ${proposal.id}."
                                    )
                                )

                                thread(name = "field-agent-worktree-switch", isDaemon = false) {
                                    try {
                                        Thread.sleep(200)
                                    } catch (_: InterruptedException) {
                                    }
                                    val launched = runCatching {
                                        launchFieldDesktopFromProject(projectDir, "agent worktree ${proposal.id}")
                                    }.onFailure {
                                        lprintln("error,server", "agent worktree restart failed: ${it.message}")
                                    }
                                    if (launched.isFailure) return@thread
                                    runCatching {
                                        if (this@Companion::ourServer.isInitialized) {
                                            ourServer.stop(500, 2000)
                                        }
                                    }
                                    exitProcess(0)
                                }
                            } catch (e: Exception) {
                                lprintln("error,server", "agent worktree restart failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    AgentWorktreeActionResponse(false, message = e.message ?: "restart failed")
                                )
                            }
                        }

                        get("/api/welcome-changelog") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                            val response = withContext(workspaceSearchDispatcher) {
                                readWelcomeChangelog(limit)
                            }
                            call.respond(response)
                        }

                        get("/api/code-use/summary") {
                            call.respond(CodeUseTelemetryService.summary())
                        }

                        get("/api/code-use/snippets") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                            call.respond(CodeUseTelemetryService.recentSnippets(limit))
                        }

                        get("/api/code-use/launches") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                            call.respond(CodeUseTelemetryService.recentLaunches(limit))
                        }

                        get("/api/code-use/failures") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                            call.respond(CodeUseTelemetryService.failures(limit))
                        }

                        get("/api/code-use/symbols") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                            call.respond(CodeUseTelemetryService.symbols(limit))
                        }

                        get("/api/code-use/unresolved") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                            call.respond(CodeUseTelemetryService.unresolved(limit))
                        }

                        post("/api/code-use/analyze") {
                            try {
                                val request = call.receive<CodeUseSemanticAnalyzeRequest>()
                                call.respond(CodeUseTelemetryService.semanticAnalyzeCode(request))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid code-use semantic analysis request"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Code-use semantic analysis failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Code-use semantic analysis failed")
                                )
                            }
                        }

                        get("/api/semantic-search/status") {
                            call.respond(SemanticRetrievalService.status())
                        }

                        get("/api/semantic-search/model") {
                            call.respond(SemanticRetrievalService.modelInfo())
                        }

                        get("/api/semantic-search/settings") {
                            call.respond(SemanticRetrievalService.settingsStatus())
                        }

                        post("/api/semantic-search/settings") {
                            try {
                                val request = call.receive<SemanticRetrievalConfigSaveRequest>()
                                call.respond(SemanticRetrievalService.saveConfig(request))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid semantic search settings"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Semantic search settings save failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Semantic search settings save failed")
                                )
                            }
                        }

                        post("/api/semantic-search/download") {
                            try {
                                val request = call.receive<SemanticRetrievalDownloadRequest>()
                                call.respond(SemanticRetrievalService.downloadPreset(request))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid semantic search download request"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Semantic search model download failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to (e.message ?: "Semantic search model download failed"))
                                )
                            }
                        }

                        post("/api/semantic-search/cache/delete") {
                            try {
                                val request = call.receive<SemanticRetrievalDeleteCacheRequest>()
                                call.respond(SemanticRetrievalService.deleteCache(request))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid semantic search cache request"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Semantic search cache delete failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Semantic search cache delete failed")
                                )
                            }
                        }

                        post("/api/semantic-search/rebuild") {
                            try {
                                call.respond(SemanticRetrievalService.startRebuild())
                            } catch (e: Exception) {
                                lprintln("error,server", "Semantic retrieval rebuild failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Semantic retrieval rebuild failed")
                                )
                            }
                        }

                        post("/api/semantic-search/query") {
                            try {
                                val request = call.receive<SemanticRetrievalQueryRequest>()
                                call.respond(SemanticRetrievalService.query(request))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid semantic retrieval query"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Semantic retrieval query failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Semantic retrieval query failed")
                                )
                            }
                        }

                        get("/api/maven-classpath") {
                            call.respond(MavenClasspathManager.status())
                        }

                        get("/api/maven-search") {
                            try {
                                val query = call.request.queryParameters["q"].orEmpty()
                                val rows = call.request.queryParameters["rows"]?.toIntOrNull() ?: 12
                                call.respond(MavenClasspathManager.search(query, rows))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid Maven search"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Maven search failed: ${e.message}")
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Maven search failed"))
                            }
                        }

                        post("/api/maven-repositories") {
                            try {
                                val request = call.receive<MavenRepositoryRequest>()
                                call.respond(MavenClasspathManager.addRepository(request.url))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid Maven repository"))
                                )
                            }
                        }

                        delete("/api/maven-repositories") {
                            try {
                                val url = call.request.queryParameters["url"].orEmpty()
                                call.respond(MavenClasspathManager.removeRepository(url))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid Maven repository"))
                                )
                            }
                        }

                        post("/api/maven-dependencies") {
                            try {
                                val request = call.receive<MavenDependencyRequest>()
                                call.respond(MavenClasspathManager.addDependency(request.coordinate))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid Maven coordinate"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Maven dependency add failed: ${e.message}")
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Maven dependency add failed"))
                            }
                        }

                        delete("/api/maven-dependencies") {
                            try {
                                val coordinate = call.request.queryParameters["coordinate"].orEmpty()
                                call.respond(MavenClasspathManager.removeDependency(coordinate))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid Maven coordinate"))
                                )
                            }
                        }

                        post("/api/maven-resolve") {
                            try {
                                val request = call.receive<MavenResolveRequest>()
                                call.respond(MavenClasspathManager.resolve(request.coordinates))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid Maven resolve request"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "Maven resolve failed: ${e.message}")
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Maven resolve failed"))
                            }
                        }

                        post("/api/maven-upgrade") {
                            try {
                                call.respond(MavenClasspathManager.upgradeDependencies())
                            } catch (e: Exception) {
                                lprintln("error,server", "Maven upgrade failed: ${e.message}")
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Maven upgrade failed"))
                            }
                        }

                        post("/api/ai-settings") {
                            try {
                                val request = call.receive<AiSettingsUpdateRequest>()
                                val status = saveAiSettings(
                                    AiSettingsSaveRequest(
                                        provider = request.provider,
                                        openAiPlatformKey = request.openAiPlatformKey,
                                        openRouterApiKey = request.openRouterApiKey,
                                        openRouterBaseUrl = request.openRouterBaseUrl,
                                        flagshipModel = request.flagshipModel,
                                        fastModel = request.fastModel,
                                        fallbackModel = request.fallbackModel
                                    )
                                )
                                call.respond(
                                    AiSettingsUpdateResponse(
                                        hasOpenAiPlatformKey = status.hasOpenAiPlatformKey,
                                        hasOpenRouterApiKey = status.hasOpenRouterApiKey,
                                        provider = status.provider,
                                        source = status.source,
                                        configPath = status.configPath,
                                        openRouterConfigPath = status.openRouterConfigPath,
                                        baseUrl = status.baseUrl,
                                        flagshipModel = status.modelSettings.flagshipModel,
                                        fastModel = status.modelSettings.fastModel,
                                        fallbackModel = status.modelSettings.fallbackModel,
                                        message = "AI settings saved."
                                    )
                                )
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid AI settings"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "saving AI settings failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "could not save AI settings")
                                )
                            }
                        }

                        get("/api/python-settings") {
                            call.respond(PythonInterface.currentPythonSettingsStatus())
                        }

                        post("/api/python-settings") {
                            try {
                                val request = call.receive<PythonSettingsRequest>()
                                call.respond(PythonInterface.savePythonSettings(request))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid Python settings"))
                                )
                            } catch (e: SerializationException) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid Python settings payload"))
                            } catch (e: Exception) {
                                lprintln("error,server", "saving Python settings failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "could not save Python settings")
                                )
                            }
                        }

                        post("/api/preferences") {
                            try {
                                val request = call.receive<FieldPreferencesUpdateRequest>()
                                persistFieldPreferences(request.preferences)
                                call.respond(
                                    FieldPreferencesUpdateResponse(
                                        preferences = currentFieldPreferences(),
                                        message = "Preferences saved. Renderer and desktop startup changes may need a restart or fresh window."
                                    )
                                )
                            } catch (e: SerializationException) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid preferences payload"))
                            } catch (e: Exception) {
                                lprintln("error,server", "saving preferences failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "could not save preferences")
                                )
                            }
                        }

                        post("/api/osc-editor-learn") {
                            val preferencesBeforeLearn = currentFieldPreferences()
                            var pausedBackdoor = false
                            try {
                                val request = call.receive<OscEditorLearnRequest>()
                                val learnPort = request.port.coerceIn(1, 65535)
                                if (preferencesBeforeLearn.oscEditorEnabled && preferencesBeforeLearn.oscEditorPort == learnPort) {
                                    pausedBackdoor = true
                                    OscEditorBackdoor.stop()
                                }

                                val address = OscEditorBackdoor.learnNextAddress(
                                    learnPort,
                                    request.timeoutMs,
                                    request.ignoreAddresses.toSet()
                                )
                                if (address.isNullOrBlank()) {
                                    call.respond(
                                        HttpStatusCode.RequestTimeout,
                                        mapOf("error" to "no OSC message received")
                                    )
                                } else {
                                    call.respond(
                                        OscEditorLearnResponse(
                                            address = address,
                                            message = "Learned $address."
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                lprintln("error,server", "OSC learn failed: ${e.message}")
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "OSC learn failed"))
                            } finally {
                                if (pausedBackdoor) {
                                    OscEditorBackdoor.configure(currentFieldPreferences())
                                }
                            }
                        }

                        post("/api/workspace-switch") {
                            try {
                                val request = call.receive<WorkspaceSwitchRequest>()
                                val target = canonicalWorkspaceFile(request.path)
                                val current = runCatching { File(home).canonicalFile }
                                    .getOrElse { File(home).absoluteFile }

                                if (target == current) {
                                    persistWorkspacePath(target.absolutePath)
                                    call.respond(
                                        WorkspaceSwitchResponse(
                                            workspace = target.absolutePath,
                                            current = true,
                                            restarting = false,
                                            message = "Already using this workspace."
                                        )
                                    )
                                    return@post
                                }

                                call.respond(
                                    WorkspaceSwitchResponse(
                                        workspace = target.absolutePath,
                                        current = false,
                                        restarting = true,
                                        message = "Restarting Field with the selected workspace."
                                    )
                                )

                                persistWorkspacePath(target.absolutePath)
                                thread(name = "field-workspace-switch", isDaemon = false) {
                                    try {
                                        Thread.sleep(200)
                                    } catch (_: InterruptedException) {
                                    }

                                    lprintln(
                                        "info,server",
                                        "switching workspace from ${current.absolutePath} to ${target.absolutePath}"
                                    )
                                    runCatching {
                                        if (this@Companion::ourServer.isInitialized) {
                                            ourServer.stop(500, 2000)
                                        }
                                    }.onFailure {
                                        lprintln(
                                            "warning,server",
                                            "error while stopping server for workspace switch: ${it.message}"
                                        )
                                    }

                                    runCatching {
                                        relaunchWithWorkspace(target.absolutePath)
                                    }.onFailure {
                                        lprintln(
                                            "error,server",
                                            "failed to relaunch with workspace ${target.absolutePath}: ${it.message}"
                                        )
                                    }

                                    exitProcess(0)
                                }
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid workspace path"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "workspace switch failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "workspace switch failed")
                                )
                            }
                        }

                        put("/api/document-tags") {
                            try {
                                val request = call.receive<DocumentTagsUpdateRequest>()
                                val response = withContext(workspaceSearchDispatcher) {
                                    updateDocumentTags(home, request.filename, request.tags)
                                }
                                call.respond(response)
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid tag update"))
                                )
                            }
                        }

                        post("/api/duplicate-document") {
                            try {
                                val request = call.receive<DuplicateDocumentRequest>()
                                val response = withContext(workspaceSearchDispatcher) {
                                    duplicateWorkspaceDocument(home, request.sourceFilename, request.newFilename)
                                }
                                recordRecentDocument(response.filename)
                                broadcastWelcomeEvent("document-duplicated")
                                call.respond(response)
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid duplicate request"))
                                )
                            }
                        }

                        post("/api/reveal-document") {
                            try {
                                val request = call.receive<RevealDocumentRequest>()
                                val documentDir = withContext(workspaceSearchDispatcher) {
                                    resolveWorkspaceDocumentDir(home, request.filename)
                                }
                                revealInSystemFileManager(documentDir)
                                call.respond(
                                    RevealDocumentResponse(
                                        filename = documentDir.name,
                                        path = documentDir.absolutePath,
                                        message = "Revealed ${documentDir.name}."
                                    )
                                )
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid reveal request"))
                                )
                            } catch (e: Exception) {
                                lprintln("error,server", "document reveal failed: ${e.message}")
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "could not reveal this document")
                                )
                            }
                        }

                        post("/api/projects") {
                            try {
                                val request = call.receive<ProjectCreateRequest>()
                                val response = withContext(workspaceSearchDispatcher) {
                                    createWorkspaceProject(home, request.name, request.documents)
                                }
                                broadcastWelcomeEvent("project-created")
                                call.respond(response)
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid project request"))
                                )
                            }
                        }

                        post("/api/projects/add-document") {
                            try {
                                val request = call.receive<ProjectAddDocumentRequest>()
                                val response = withContext(workspaceSearchDispatcher) {
                                    addDocumentToWorkspaceProject(home, request.projectName, request.filename)
                                }
                                broadcastWelcomeEvent("project-updated")
                                call.respond(response)
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid project update"))
                                )
                            }
                        }

                        post("/api/projects/delete") {
                            try {
                                val request = call.receive<ProjectDeleteRequest>()
                                val response = withContext(workspaceSearchDispatcher) {
                                    deleteWorkspaceProject(home, request.name)
                                }
                                broadcastWelcomeEvent("project-deleted")
                                call.respond(response)
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid project delete"))
                                )
                            }
                        }

                        get("/api/export-tagged-documents") {
                            try {
                                val tag = call.request.queryParameters["tag"].orEmpty()
                                val archive = withContext(workspaceSearchDispatcher) {
                                    exportTaggedDocumentsZip(home, tag)
                                }
                                call.response.headers.append(
                                    HttpHeaders.ContentDisposition,
                                    "attachment; filename=\"${archive.filename}\""
                                )
                                call.response.headers.append("X-Field-Document-Count", archive.documentCount.toString())
                                call.respondBytes(archive.bytes, ContentType.parse("application/zip"))
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (e.message ?: "invalid export request"))
                                )
                            }
                        }

                        get("/api/workspace-search") {
                            val query = call.request.queryParameters["q"].orEmpty()
                            val maxDocuments = call.request.queryParameters["maxDocuments"]?.toIntOrNull() ?: 50
                            val maxMatchesPerDocument =
                                call.request.queryParameters["maxMatchesPerDocument"]?.toIntOrNull() ?: 5
                            val maxScanMillis =
                                call.request.queryParameters["maxScanMillis"]?.toLongOrNull() ?: 3_000L

                            val searchGeneration = workspaceSearchGeneration.incrementAndGet()
                            val response = try {
                                withContext(workspaceSearchDispatcher) {
                                    val requestJob = coroutineContext.job
                                    searchWorkspace(
                                        base = home,
                                        rawQuery = query,
                                        maxDocuments = maxDocuments,
                                        maxMatchesPerDocument = maxMatchesPerDocument,
                                        maxScanMillis = maxScanMillis,
                                        cancellationCheck = {
                                            if (!requestJob.isActive) throw CancellationException("workspace search cancelled")
                                            if (workspaceSearchGeneration.get() != searchGeneration) {
                                                throw CancellationException("workspace search superseded")
                                            }
                                        }
                                    )
                                }
                            } catch (e: CancellationException) {
                                if (workspaceSearchGeneration.get() == searchGeneration) throw e
                                WorkspaceSearchResponse(
                                    query = query.trim(),
                                    documents = emptyList(),
                                    totalDocuments = 0,
                                    totalMatches = 0,
                                    truncated = true
                                )
                            }
                            call.respond(response)
                        }

                        route("/welcome") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/welcome.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }

                        route("/code-use") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/code-use.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }

                        // Experimental visionOS front end — PLACEHOLDER / reserved fork point
                        // (see dev_docs/vision_frontend.md). The Kotlin/JS fork is deferred: KMP won't
                        // give a clean second bundle sharing only commonMain, and the native shell points
                        // at /welcome + /index.html first anyway. This route serves a static placeholder so
                        // the prefix is claimed and documented; it grows a real bundle at first divergence.
                        // Served raw: we deliberately skip filter()/injectInitialFramePreview().
                        route("/vision") {
                            handle {
                                val indexFile = File("./src/jsVision/resources/index.html")
                                if (indexFile.exists()) {
                                    call.respondText(indexFile.readText(Charsets.UTF_8), ContentType.Text.Html)
                                } else {
                                    call.respond(HttpStatusCode.NotFound, "vision index.html not found")
                                }
                            }

                            // Thin lifecycle control for the streaming docks: toggle an open
                            // document's graphics/streaming boxes on/off over HTTP, so a stream can
                            // be started/stopped without hand-running boxes in the editor. Content
                            // stays in the boxes (e.g. a FieldRTTStreamer box); this only flips them.
                            //   POST /vision/stream?action=start&doc=<name>[&boxes=uid1,uid2]
                            //   POST /vision/stream?action=stop&doc=<name>
                            // The document must already be open (attached). `boxes` is an ordered
                            // list (renderer-owning box first); default is every non-root box.
                            post("/stream") {
                                val q = call.request.queryParameters
                                val action = (q["action"] ?: "start").lowercase()
                                val docName = q["doc"]
                                val info = documents.values.firstOrNull {
                                    docName == null || it.documentName == docName || it.filename == docName
                                }
                                if (info == null) {
                                    call.respond(HttpStatusCode.NotFound,
                                        "no open document" + (docName?.let { " named '$it'" } ?: "") +
                                            "; open it first")
                                    return@post
                                }
                                val boxSpec = q["boxes"]
                                val targets = if (!boxSpec.isNullOrBlank())
                                    boxSpec.split(",").mapNotNull { uid -> info.boxes[uid.trim()] }
                                else info.boxes.values.filter {
                                    it.id != "root" && !it.getPropertyRaw("_tombstone").isTrue()
                                }.sortedByDescending { it.name.equals("window", ignoreCase = true) }
                                if (targets.isEmpty()) {
                                    call.respond(HttpStatusCode.BadRequest,
                                        "no matching boxes in '${info.documentName}'")
                                    return@post
                                }
                                when (action) {
                                    "start" -> GlobalScope.launch {
                                        // Begin in order (renderer-owning box first), with a beat
                                        // between so a window box's Renderer is up before a
                                        // dependent box inherits it.
                                        targets.forEach { box ->
                                            runCatching { box.begin() }.onFailure {
                                                lprintln("warning,vision-stream",
                                                    "begin ${box.name}: ${it.message}")
                                            }
                                            delay(400)
                                        }
                                    }
                                    "stop" -> targets.reversed().forEach { box ->
                                        // Close the encoder + drop the streamer from the render list
                                        // before stopping the box, so the co-process doesn't orphan.
                                        (box.getPropertyRaw("streamer") as? FieldRTTStreamer)
                                            ?.let { runCatching { it.stop() } }
                                        runCatching { stop(box) }.onFailure {
                                            lprintln("warning,vision-stream",
                                                "stop ${box.name}: ${it.message}")
                                        }
                                    }
                                    else -> {
                                        call.respond(HttpStatusCode.BadRequest,
                                            "action must be 'start' or 'stop'")
                                        return@post
                                    }
                                }
                                call.respondText(
                                    """{"ok":true,"action":"$action","document":"${info.documentName}",""" +
                                        """"boxes":[${targets.joinToString(",") { "\"${it.name}\"" }}]}""",
                                    ContentType.Application.Json
                                )
                            }
                        }

                        post("/api/import-offline-doc") {
                            try {
                                val payload = call.receive<OfflineImportRequest>()
                                val rawName = payload.recommendedName ?: "imported-document"
                                val ednText = payload.ednText ?: throw IllegalArgumentException("missing ednText")

                                fun sanitize(base: String): String {
                                    val cleaned = base.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
                                    return cleaned.ifBlank { "imported-document" }
                                }

                                val baseName = sanitize(rawName.removeSuffix(".edn"))
                                var candidate = baseName
                                var counter = 2
                                while (File(home, candidate).exists()) {
                                    candidate = "${baseName}_${counter}"
                                    counter++
                                }

                                val targetDir = File(home, candidate)
                                targetDir.mkdirs()

                                // Persist the incoming EDN as-is; Field will expand sidecar files on next save.
                                val metadataFile = File(targetDir, SavingContext.METADATA_EDN)
                                metadataFile.writeText(ednText)

                                val encodedName = URLEncoder.encode(candidate, "UTF-8")
                                val openUrl = "https://localhost:${port}/index.html?file=${encodedName}"
                                val projectUrl =
                                    "https://localhost:${port}/${encodedName}/${SavingContext.METADATA_EDN}"

                                call.respond(OfflineImportResponse(candidate, openUrl, projectUrl))
                            } catch (ex: Exception) {
                                lprintln("error,server", "offline import failed: ${ex.message}")
                                call.respond(HttpStatusCode.BadRequest, ex.message ?: "import failed")
                            }
                        }

                        post("/api/shutdown-instance") {
                            call.respond(HttpStatusCode.OK, "Shutting down this Field instance")

                            thread(name = "field-instance-shutdown", isDaemon = true) {
                                try {
                                    Thread.sleep(150)
                                } catch (_: InterruptedException) {
                                }

                                runCatching {
                                    if (this@Companion::ourServer.isInitialized) {
                                        ourServer.stop(500, 2000)
                                    }
                                }

                                exitProcess(0)
                            }
                        }

                        webSocket("/welcome-events") {
                            welcomeEventSessions.add(this)
                            runCatching { send("""{"type":"welcome-refresh","reason":"connected"}""") }
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Close) break
                                }
                            } finally {
                                welcomeEventSessions.remove(this)
                            }
                        }

                        webSocket("/ws") {
                            val ts = this
                            thisSession.set(ts)
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        NetStats.received(frame.data.size) // front end -> backend, raw wire bytes
                                        sessions.entries.removeIf { (session, info) ->
                                            if (session.outgoing.isClosedForSend) {
                                                detachDocumentSession(session, info)
                                                true
                                            } else false
                                        }

                                        var message: Message? = null
                                        try {
                                            message = Json.decodeFromString<Message>(text)
                                        } catch (t: SerializationException) {
                                        }

                                        if (message == null) {
                                            launch {
                                                thisSession.set(ts)
                                                thisSessionInfo.set(sessions.get(ts))
                                                try {
                                                    rawHandlers.values.forEach {
                                                        it.forEach {
                                                            it(text)
                                                        }
                                                    }
                                                } catch (t: Throwable) {
                                                    t.printStackTrace()
                                                }
                                            }
                                        } else {
                                            launch { // should this block or not?...
                                                thisSession.set(ts)
                                                thisSessionInfo.set(sessions.get(ts))
                                                val savedScope = this.coroutineContext
                                                try {
                                                    val r = handle(message) { message2 ->
                                                        val message3 =
                                                            message.replyTo?.let { message2.toName(it) } ?: message2
                                                        val m = Json.encodeToString(message3)
                                                        // Keeps _print() responsive while user code hogs the main execution thread,
                                                        // but still applies output back pressure when the browser falls behind.
                                                        sendWithOutputBackPressure(ts, message3, m)
                                                    }
                                                } catch (t: Throwable) {
                                                    t.printStackTrace()
                                                }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                detachDocumentSession(ts)
                                leaveSelectionContexts(ts)
                                // Clean up back pressure state for this session.
                                sessionOutputPressure.remove(ts)
                            }
                        }
                        route("/dir") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/dir.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/browse") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/browser.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/tree") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/tree.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/h2-explorer") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/h2_explorer.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/lazy-value-fragment") {
                            handle {
                                val root = call.request.queryParameters["root"]
                                val kind = call.request.queryParameters["kind"]
                                val offset = call.request.queryParameters["offset"]?.toIntOrNull()
                                val depth = call.request.queryParameters["depth"]?.toIntOrNull() ?: 0

                                if (root.isNullOrBlank() || kind.isNullOrBlank() || offset == null) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        "missing or invalid lazy value fragment parameters"
                                    )
                                    return@handle
                                }

                                val html = ValueVisualizer.renderLazyFragment(root, kind, offset, depth)
                                if (html == null) {
                                    call.respond(HttpStatusCode.NotFound, "unknown or expired lazy value token")
                                    return@handle
                                }

                                call.respondText(html, ContentType.Text.Html)
                            }
                        }
                        route("/inspect") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/inspector.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/logs") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/logs.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/keyframe") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/keyframe_editor.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/threejs") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/threejs_viewer.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }
                        route("/history") {
                            handle {
                                val originalContent =
                                    File("./src/commonMain/resources/history.html").readText(Charsets.UTF_8)
                                val modifiedContent = filter(originalContent)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            }
                        }

                        route("/history-screenshot") {
                            get {
                                val document = call.request.queryParameters["file"]?.takeIf { it.isNotBlank() }
                                val relativePath = call.request.queryParameters["path"]?.takeIf { it.isNotBlank() }
                                if (document == null || relativePath == null) {
                                    call.respond(HttpStatusCode.BadRequest, "missing file or path")
                                    return@get
                                }

                                val workspaceRoot = File(home).canonicalFile
                                val liveDocument = documents[document]
                                    ?: documents.values.firstOrNull {
                                        it.documentName == document ||
                                                it.filename == document ||
                                                File(it.filename).name == document
                                    }
                                val documentDir = if (liveDocument != null) {
                                    File(liveDocument.filename).canonicalFile
                                } else {
                                    val requested = File(document)
                                    if (requested.isAbsolute) requested.canonicalFile else File(
                                        workspaceRoot,
                                        document
                                    ).canonicalFile
                                }
                                val screenshotRoot = File(documentDir, "history_screenshots").canonicalFile
                                val file = File(documentDir, relativePath).canonicalFile
                                val insideWorkspace = documentDir == workspaceRoot ||
                                        documentDir.path.startsWith(workspaceRoot.path + File.separator)
                                val liveDocumentAllowed =
                                    liveDocument != null && documentDir == File(liveDocument.filename).canonicalFile
                                if ((!insideWorkspace && !liveDocumentAllowed) ||
                                    !file.path.startsWith(screenshotRoot.path + File.separator) ||
                                    file.extension.lowercase() !in setOf("png", "jpg", "jpeg") ||
                                    !file.isFile ||
                                    file.length() <= 8
                                ) {
                                    call.respond(HttpStatusCode.NotFound, "history screenshot not found")
                                    return@get
                                }

                                call.respondFile(file)
                            }
                        }

                        route("/min") {
                            get("{...}") {
                                val relativeUri = URI(call.request.uri).path.removePrefix("/min/")
                                val roots = listOf(
                                    File("./src/jsMain/resources/monaco/min").canonicalFile
                                )
                                val file = roots.asSequence()
                                    .map { root -> root to File(root, relativeUri).canonicalFile }
                                    .firstOrNull { (root, candidate) ->
                                        candidate.isFile &&
                                                (candidate == root || candidate.path.startsWith(root.path + File.separator))
                                    }
                                    ?.second

                                if (file != null) {
                                    call.respondFile(file)
                                } else {
                                    call.respond(HttpStatusCode.NotFound, "Monaco asset not found")
                                }
                            }
                        }
                        get("/index.html") {
                            val directories = listOf(
                                File("./build/kotlin-webpack/js/productionExecutable/"),
                                File("./src/jsMain/resources/"),
                                File("./src/commonMain/resources/"),
                            )
                            val indexFile = directories
                                .map { File(it, "index.html") }
                                .firstOrNull { it.exists() }

                            if (indexFile != null) {
                                val originalContent = indexFile.readText(Charsets.UTF_8)
                                lprintln("info,server", "serving index file from ${indexFile.absolutePath}")
                                val modifiedContent = injectInitialFramePreview(filter(originalContent), call)
                                call.respondText(modifiedContent, ContentType.Text.Html)
                            } else {
                                call.respond(HttpStatusCode.NotFound, "index.html not found")
                            }
                        }
                        // Cache static assets so page reloads don't re-fetch them.
                        // Without this they're served with no Cache-Control, so every
                        // load re-downloads them -- and the docs capture browser reloads
                        // the page for each screenshot, so it was re-fetching the multi-MB
                        // afield.mjs + fonts on every capture.
                        //
                        // But we deliberately do NOT cache for the Electron desktop app
                        // (the real-user entry point): it loads the page once per tab, so
                        // caching gains it nothing, and a stale disk cache would serve an
                        // old afield.mjs after a dev rebuild (Ktor emits no Last-Modified/
                        // ETag, so there's no revalidation to save us). Electron is
                        // identifiable by its User-Agent; everything else (the Playwright
                        // capture browser, plain browsers) gets the cache. Assets are
                        // immutable for a server run, so a plain max-age is safe there.
                        val assetCache: StaticContentConfig<File>.() -> Unit = {
                            modify { file, call ->
                                if ("Electron" in call.request.headers[HttpHeaders.UserAgent].orEmpty()) return@modify
                                val seconds = when (file.extension.lowercase()) {
                                    "mjs", "js", "css", "wasm", "map" -> 3600
                                    "woff2", "woff", "ttf", "otf", "eot" -> 604800
                                    "png", "jpg", "jpeg", "gif", "svg", "webp", "ico" -> 86400
                                    else -> 0
                                }
                                if (seconds > 0)
                                    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=$seconds")
                            }
                        }
                        staticFiles(remotePath = "", dir = File("./build/js/packages/afield/kotlin"), block = assetCache)
                        staticFiles(remotePath = "", dir = File("./src/jsMain/resources/"), block = assetCache)
                        staticFiles(remotePath = "", dir = File("./src/jsMain/resources/monaco"), block = assetCache)
                        staticFiles(remotePath = "", dir = File("./src/commonMain/resources/"), block = assetCache)

                        val indexHtmlInterceptor = createRouteScopedPlugin("IndexHtmlInterceptor") {
                            onCall { call ->
                                var effectivePath = URI(call.request.uri).path
                                if (effectivePath.endsWith("/")) effectivePath = effectivePath + "index.html"
                                if (effectivePath.endsWith("index.html")) {
                                    // List directories in the order you want to search for index.html
                                    val directories = listOf(
                                        File("./build/kotlin-webpack/js/productionExecutable/"),
                                        File("./src/jsMain/resources/"),
                                        File("./src/commonMain/resources/"),
//                                    File("./build/vite/dist/")
                                    )

                                    val indexFile = directories.map { File(it, effectivePath.trim('.', '/')) }
                                        .firstOrNull { it.exists() }

                                    if (indexFile != null) {
                                        val originalContent = indexFile.readText(Charsets.UTF_8)

                                        lprintln("info,server", "serving index file from ${indexFile.absolutePath}")

                                        val modifiedContent = injectInitialFramePreview(filter(originalContent), call)
                                        call.respondText(modifiedContent, ContentType.Text.Html)
                                    }
                                }
                            }
                        }
                        route("/") {
                            install(indexHtmlInterceptor)
                            staticFiles(remotePath = "", dir = File(home))
                        }

                        route("/project") {
                            get("{...}") {
                                lprintln("debug,server", "routing /project for ${call.request.uri}")
                                // Serve files from the project directory ($home)
                                val fullUri = call.request.uri
                                val pathOnly = URI(fullUri).path
                                val relativeUri = pathOnly.removePrefix("/project/")

                                val file = File(home, relativeUri)
                                if (file.exists() && file.isFile && file.canonicalPath.startsWith(File(home).canonicalPath)) {
                                    // Security check: ensure the file is within the home directory
                                    call.respondFile(file)
                                } else {
                                    lprintln(
                                        "warning,server",
                                        "project file not found or outside project directory: $relativeUri"
                                    )
                                    call.respond(HttpStatusCode.NotFound, "File not found in project directory")
                                }
                            }
                        }

                        route("/local") {
                            get("{...}") {
                                lprintln("debug,server", "routing /local dynamically ${call.request.uri}")
                                // Obtain the full request URI, strip any query or fragment, and remove the "/local" prefix
                                val fullUri = call.request.uri
                                val pathOnly = URI(fullUri).path
                                val relativeUri = pathOnly.removePrefix("/local/")

                                // Check the dynamic mappings for a matching prefix
                                val match = dynamicRoutes.getRoutes().entries.filter { relativeUri.startsWith(it.key) }
                                    .maxByOrNull { it.key.length } // use longest match if overlapping

                                if (match != null) {
                                    // Remove the mapping prefix to get the file's relative path
                                    val fileRelativePath = relativeUri.removePrefix(match.key)
                                    val file = File(match.value, fileRelativePath)
                                    if (file.exists() && file.isFile) {
                                        call.respondFile(file)
                                        return@get
                                    } else {
                                        lprintln("warning,server", "route found but missing file $fileRelativePath")
                                        call.respond(HttpStatusCode.NotFound, "File '${file}' not found")
                                        return@get
                                    }
                                }
                                lprintln(
                                    "warning,server",
                                    "failed to find route for $fullUri; checked ${dynamicRoutes.getRoutes().entries}"
                                )
                                call.respond(HttpStatusCode.NotFound, "No dynamic route for $fullUri")
                            }
                        }
                    }

                }
            }

            var started = false
            var bindFailure: Throwable? = null
            for (offset in 0 until portFallbackCount) {
                val candidatePort = preferredPort + offset
                port = candidatePort
                try {
                    ourServer = createServer(candidatePort)
                    ourServer.start(wait = false)
                    started = true
                    if (offset > 0) {
                        lprintln(
                            "warning,server",
                            "Requested port ${preferredPort} was unavailable; using fallback port ${candidatePort}"
                        )
                    }
                    break
                } catch (t: Throwable) {
                    if (!isPortBindException(t)) throw t
                    bindFailure = t
                    lprintln("warning,server", "Port ${candidatePort} is already in use; trying next port")
                }
            }

            if (!started) {
                throw bindFailure
                    ?: IllegalStateException("Unable to bind Field server to any port in range ${preferredPort}..${preferredPort + portFallbackCount - 1}")
            }

            globalServerPort = port
            globalLoopbackHttpPort = loopbackHttpPortFor(port)
            lprintln("info,startup", "server listening on https://localhost:$port")
            val initialURL = desktopStartupDocumentName()
                ?.let { "https://localhost:${port}/index.html?file=${URLEncoder.encode(it, "UTF-8")}" }
                ?: "https://localhost:${port}/welcome"
            lprintln("info,server", asciiWelcome)
            lprintln("info,server", "welcome to Field, ß-033")
            lprintln("info,server", "last document: $initialURL")
            lprintln("info,server", "welcome page: https://localhost:${port}/welcome")
            globalLoopbackHttpPort?.let { httpPort ->
                val automationUrl = desktopStartupDocumentName()
                    ?.let { "http://localhost:${httpPort}/index.html?file=${URLEncoder.encode(it, "UTF-8")}" }
                    ?: "http://localhost:${httpPort}/welcome"
                lprintln("info,server", "local automation page: $automationUrl")
            }
            lprintln("info,server", "---")

            if (preferSystemBrowser) {
//                    thread { openSystemBrowser(initialURL) }
            } else {
                thread {
                    lprintln("info,startup", "opening UI window…")
                    Playwright.create().let { pw ->
                        globalPlaywright = pw
                        val browser = pw.chromium().launch(
                            BrowserType.LaunchOptions()
                                .setHeadless(false)
                                .setArgs(
                                    listOf(
                                        "--app=${initialURL}",
                                    )
                                )
                        )
                        globalPlaywrightBrowser = browser
                        val ctx = browser.newContext(
                            Browser.NewContextOptions()
                                .setViewportSize(null)
                                .setIgnoreHTTPSErrors(true)
                        )
                        globalPlaywrightBrowserContext = ctx
                        val page = ctx.newPage()
                        page.navigate(initialURL)
                        page.waitForLoadState(LoadState.NETWORKIDLE)
                        lprintln("info,server", "network idle")

                        globalPlaywriteContext = page
                    }
                }
            }
        }


        fun stop(box: Box, entrypoint: String = "server.stop") {
            FlightRecorder.recordStopEntrypoint(entrypoint, box)
            box.ke.stop(box)
            // If this box owns an afield.four Renderer (e.g. the agent graphics-context box),
            // cancelling its jobs above never disposes the native window/surface. Do that now.
            disposeBoxRenderer(box)
            broadcastWelcomeEvent("running")
        }

        @JvmStatic
        fun main(args: Array<String>) {
//            if (System.getProperty("java.awt.headless") == null) {
//                val envHeadless = System.getenv("AFIELD_HEADLESS")?.lowercase()
//                val shouldBeHeadless = envHeadless == "1" || envHeadless == "true" || envHeadless == "yes"
//                System.setProperty("java.awt.headless", if (shouldBeHeadless) "true" else "false")
//            }

//            if (System.getProperty("java.awt.headless") != "true") {
//                try {
//                    Toolkit.getDefaultToolkit()
//                    lprintln("info,server", "AWT toolkit initialized on main thread")
//                } catch (e: Throwable) {
//                    lprintln("warn,server", "AWT init failed: ${e.message}")
//                }
//            }
//            val logEawt = System.getenv("AFIELD_LOG_EAWT")?.lowercase() in listOf("1", "true", "yes")
//            if (logEawt) {
//                val loader = Thread.currentThread().contextClassLoader
//                val eawtResource = loader?.getResource("com/apple/eawt/QuitHandler.class")
//                lprintln("info,server", "eawt QuitHandler resource: ${eawtResource ?: "<missing>"}")
//                lprintln("info,server", "context classloader: ${loader ?: "<null>"}")
//            }

//            Configuration.GLFW_CHECK_THREAD0.set(false)

            // Pin a "startup" channel (plus any AFIELD_PIN_CHANNELS) to a non-scrolling region at the
            // top of the terminal so the boot stages annotate in place; degrades to plain stdout on
            // non-TTYs. The startup row collapses a few seconds after the first frame renders.
            afield.logger.PinnedConsole.installWithStartup()
            lprintln("info,startup", "booting Field…")

            runAll(early)

            Companion.args = args

            thread {
                server()
            }

            Runloop.runLoop.start()

        }

    }
}

fun languageNameForProperty(box: Box, property: String): String {
    if (property == "code") return box.ke.languageName
    else return when (property) {
        "vertex" -> "GLSL (vertex)"
        "fragment" -> "GLSL (fragment)"
        "vertex_fragment" -> "Slang (vertex+fragment)"
        "compute" -> "GLSL (compute)"
        "mesh" -> "GLSL (mesh)"
        "task" -> "GLSL (task)"
        "raygen" -> "GLSL (ray generation)"
        "miss" -> "GLSL (miss)"
        "closesthit" -> "GLSL (closest hit)"
        "anyhit" -> "GLSL (any hit)"
        "intersection" -> "GLSL (intersection)"
        "callable" -> "GLSL (callable)"
        "vertex_s" -> "Slang (vertex)"
        "fragment_s" -> "Slang (fragment)"
        "compute_s" -> "Slang (compute)"
        "mesh_s" -> "Slang (mesh)"
        "task_s" -> "Slang (task)"
        "raygen_s" -> "Slang (ray generation)"
        "miss_s" -> "Slang (miss)"
        "closesthit_s" -> "Slang (closest hit)"
        "anyhit_s" -> "Slang (any hit)"
        "intersection_s" -> "Slang (intersection)"
        "callable_s" -> "Slang (callable)"
        else -> "Kotlin"
    }
}
