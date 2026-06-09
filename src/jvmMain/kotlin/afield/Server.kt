@file:OptIn(DelicateCoroutinesApi::class)

package afield

import afield.ai.Ai4CompletionDoc
import afield.ai.core.completion.Ai4KotlinAutocomplete
import afield.debug.RuntimeDebugProbeService
import afield.four.util.Options
import afield.graphics.getFLineEditSession
import afield.logger.lblock
import afield.logger.lprint
import afield.logger.lprintln
import afield.server.refreshFileProxyBoxes
import afield.server.writeFileProxyContents
import afield.stdlib.FLineIntersection
import afield.stdlib.begin
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import java.awt.Desktop
import java.io.File
import java.net.BindException
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Paths
import java.security.KeyStore
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


/***
 */

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

    // Track sidecar-bearing properties that need to be written to disk on next save.
    val pendingSidecarSaves = ConcurrentHashMap.newKeySet<Pair<String, String>>()

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

    fun cleanup() {
        _fileWatchManager?.stop()
        _fileWatchManager = null
    }
}

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

        targetBoxes.filter { it.autorun.isTrue() && it.id != "root" && !it.getPropertyRaw("_tombstone").isTrue() }.forEach { box ->
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

val home = option("workspace") { System.getProperty("user.home") + "/Documents/AFieldWorkspace" }
val app = option("app") { File(".").absolutePath }

val preferSystemBrowser: Boolean = (System.getenv("AFIELD_SYSTEM_BROWSER")
    ?: System.getProperty("afield.systemBrowser") ?: "true").lowercase() != "false"
val visibleBackdoorPlaywright: Boolean = (System.getenv("AFIELD_PLAYWRIGHT_VISIBLE")
    ?: System.getProperty("afield.playwright.visible") ?: "false").lowercase() == "true"
private val outputBackPressureLogEnabled: Boolean = (System.getenv("AFIELD_OUTPUT_BACKPRESSURE_LOG")
    ?: System.getProperty("afield.outputBackpressureLog") ?: "true").lowercase() != "false"
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
    if (!force) {
        val previous = outputBackPressureLogTimes[key] ?: 0L
        if (now - previous < 500L) return
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
private fun sendWithOutputBackPressure(session: WebSocketSession, message: Message, encoded: String = Json.encodeToString(message)) {
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
            outputBackPressureLog(session, "send-failed-release", message, bp, detail = "released=${1 + buffered.size}", force = true)
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
            outputBackPressureLog(session, "flush-failed-release", pressure = bp, detail = "released=${buffered.size}", force = true)
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
actual fun _send(message: Message) {
    _send(message, document = ".*")
}


@OptIn(DelicateCoroutinesApi::class)
fun _send(message: Message, document: String = ".*") {
    val documentRegex = document.toRegex()
    val m = Json.encodeToString(message)
    sessions.forEach { (session, info) ->
        if (!info.filename.matches(documentRegex)) return@forEach
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

internal fun recordRecentDocument(documentName: String) {
    recentFns = updateRecentDocuments(recentFns, documentName)
}

@OptIn(DelicateCoroutinesApi::class)
fun _send(session: WebSocketSession?, message: Message) {
    if (session == null) {
        _send(message)
        return
    }
    val m = Json.encodeToString(message)
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
internal val playwrightScope = CoroutineScope(SupervisorJob() + playwrightExecutor.asCoroutineDispatcher())
private val workspaceSearchDispatcher = Executors.newFixedThreadPool(
    (System.getenv("AFIELD_WORKSPACE_SEARCH_THREADS")?.trim()?.toIntOrNull() ?: 2).coerceIn(1, 4)
) { runnable ->
    Thread(runnable, "afield-workspace-search").apply {
        isDaemon = true
    }
}.asCoroutineDispatcher()

private data class PlaywrightPageHandle(
    val page: Page,
    val createdBrowser: Boolean,
    val createdContext: Boolean,
    val createdPage: Boolean
)

private fun jsonStringLiteral(value: String?): String = if (value == null) "null" else Json.encodeToString(value)

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

private fun ensurePlaywrightPageOnce(targetUrl: String): PlaywrightPageHandle {
    var createdBrowser = false
    var createdContext = false
    var createdPage = false

    val browser = globalPlaywrightBrowser ?: run {
        val pw = globalPlaywright ?: Playwright.create().also { globalPlaywright = it }
        createdBrowser = true
        pw.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(!visibleBackdoorPlaywright)
                .setArgs(
                    listOf(
                        "--disable-background-timer-throttling",
                        "--disable-backgrounding-occluded-windows",
                        "--disable-renderer-backgrounding",
                        "--disable-frame-rate-limit"
                    )
                )
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
            "Playwright readiness wait failed for ${info.documentName} (${info.filename}) at ${page.url()}: ${failure.message}; status=${playwrightSessionStatus(page)}"
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
    if (currentPage != null && currentUrl == targetUrl) {
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
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1"
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
            Options.doVulkanSDK()

            lprintln("info,server", "app directory is $app")
            System.setProperty("joml.format", "false")

            initializeClassCache()

            runAll(plugins)
            runAll(installHandlers)
            UserThemeManager.start()

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
                                        SavingContext().save(info.filename, allBoxes)
                                        info.pendingSidecarSaves.clear()
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


            handlers["hello"] = mutableSetOf({ message, reply ->
                val hello = Json.decodeFromJsonElement<Hello>(message.payload!!)
                lprintln("info,server", "adding a session for $hello to ${thisSession.get()}")
                attachDocumentSession(
                    session = thisSession.get(),
                    documentName = hello.filename,
                    username = hello.username,
                    scratch = hello.scratch,
                    sessionKey = hello.sessionKey
                )

                Handled.yes
            })

            handlers["set-string"] = mutableSetOf({ message, reply ->

                val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)
                val isEditorStateProperty = set.property.endsWith("_editorState")
                val targetBox = box(set.uid)

                if (set.property == "name") {
                    lprintln("debug,server", "got set-string for name ${set.value}")
                    targetBox.name = set.value
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
                }

                // If this is a sidecar-backed property, mark it for persistence.
                if (Box.ioSeparateProperties.containsKey(set.property) && !thisSessionInfo.get().ephemeral) {
                    thisSessionInfo.get().pendingSidecarSaves += set.uid to set.property
                }

                Handled.yes
            })
            handlers["provisional-accept"] = mutableSetOf({ message, reply ->
                val target = Json.decodeFromJsonElement<ProvisionalTarget>(message.payload!!)
                acceptProvisional(thisSessionInfo.get().filename, target.uid, target.property)
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
                    b.frame = set.value
                    inMain {
                        doResync() // subsequent calls do nothing, so this effectively combines multiple moves to the same update
                    }
                } else {
                    box(set.uid).setPropertyRaw(set.property, set.value, doResync = true)
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
                        b.frame = update.value
                        needsResync.remove(b to "frame")
                        frameUpdates += Set_rect(uid = update.uid, property = "frame", value = b.frame)
                    } else {
                        hasNonFrameUpdates = true
                        box(update.uid).setPropertyRaw(update.property, update.value, doResync = true)
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
                    val result = (box.ke as? KotlinExecution)
                        ?.describeSymbolContext(box, exec)
                        ?: SymbolContextResponse()
                    reply(Message("symbol-context").payload(result))
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
                            lprintln("warning,ai_completions", "kotlin-ai failed uid=${exec.uid}: ${it.javaClass.simpleName}: ${it.message}")
                        }.getOrElse { emptyList() }
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        lblock(
                            "info, ai_completions", "responses are"

                        ) {
                            lprint("info, ai_completions", "kotlin-ai response uid=${exec.uid} prop=${exec.property} candidates=${candidates.size} in ${ms}ms")
                            candidates.forEach {
                                lprint("info, ai_completions", "${it.text} ${it.documentation}")
                            }
                        }

                        val metadata = candidates.take(3).mapIndexed { idx, candidate ->
                            val display = candidate.text.lineSequence().firstOrNull()?.trim().orEmpty()
                            val doc = buildString {
                                val notes = candidate.documentation.ifBlank { "GPT-5.1-codex-mini" }
                                append(notes.trim())
                                if (candidate.text.isNotBlank()) {
                                    append("\n\n```kotlin\n")
                                    append(candidate.text.trimEnd())
                                    append("\n```")
                                }
                            }.trimEnd()

                            Metadata(
                                icon = "keyword",
                                tail = "AI ${idx + 1}",
                                text = candidate.text,
                                displayText = if (display.isNotEmpty()) display else "AI completion ${idx + 1}",
                                documentation = doc
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
                            column = exec.column
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
                        action.invoke(exec.uid_currentSelection.mapNotNull { box(it, createIfMissing = false) })
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

            handlers["slider-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<SliderUpdate>(message.payload!!)
                lprintln("debug,slider", "Received slider update: ${update.sliderId} = ${update.value}")

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
                lprintln("debug,controls", "Received file upload: ${update.controlId} with ${update.files.size} file(s)")

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
                lprintln("debug,controls", "Received color update: ${update.controlId} = rgba(${update.r}, ${update.g}, ${update.b}, ${update.a})")

                val callback = getColorCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(Vector4f(update.r.toFloat(), update.g.toFloat(), update.b.toFloat(), update.a.toFloat()))
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
                                lprintln("debug,layers", "Saved frame for box ${box.id} in layer $oldActiveLayer: ${box.frame}")
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
                                lprintln("debug,layers", "Applied frame for box ${box.id} from layer ${update.layer}: ${layerFrame}")

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

            val preferredPort = System.getenv("PORT")?.trim()?.toIntOrNull() ?: 9092
            val portFallbackCount = System.getenv("AFIELD_PORT_FALLBACK_COUNT")?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 20
            var port = preferredPort
            val httpsHost = "0.0.0.0"
            val keystorePath = System.getenv("AFIELD_KEYSTORE_PATH") ?: File(app, "config/field4-local.p12").absolutePath
            val keystorePassword = System.getenv("AFIELD_KEYSTORE_PASS") ?: "changeit"
            val keystoreAlias = System.getenv("AFIELD_KEYSTORE_ALIAS") ?: "field4"
            val keystoreFile = ensureLocalKeystore(File(keystorePath), keystorePassword, keystoreAlias)
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(keystoreFile.inputStream(), keystorePassword.toCharArray())
            }

            fun createServer(bindPort: Int): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
                return embeddedServer(Netty, configure = {
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

                    get("/app-url") {
                        call.respondText("https://localhost:${port}/welcome", ContentType.Text.Plain)
                    }

                    get("/api/welcome-docs") {
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
                        val docs = welcomeDir(home, recentDocuments).map {
                            it.copy(
                                runningSessionCount = liveWorkspaceCounts[it.filename] ?: 0,
                                runningExecutionCount = runningExecutionCounts[it.filename] ?: 0
                            )
                        }
                        val fallback = lastFn ?: "welcome"
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
                        call.respond(WelcomeDocsResponse(docs, fallback, defaultDocument, allTags, runningDocuments))
                    }

                    get("/api/welcome-changelog") {
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                        val response = withContext(workspaceSearchDispatcher) {
                            readWelcomeChangelog(limit)
                        }
                        call.respond(response)
                    }

                    put("/api/document-tags") {
                        try {
                            val request = call.receive<DocumentTagsUpdateRequest>()
                            val response = withContext(workspaceSearchDispatcher) {
                                updateDocumentTags(home, request.filename, request.tags)
                            }
                            call.respond(response)
                        } catch (e: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid tag update")))
                        }
                    }

                    post("/api/duplicate-document") {
                        try {
                            val request = call.receive<DuplicateDocumentRequest>()
                            val response = withContext(workspaceSearchDispatcher) {
                                duplicateWorkspaceDocument(home, request.sourceFilename, request.newFilename)
                            }
                            recordRecentDocument(response.filename)
                            call.respond(response)
                        } catch (e: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid duplicate request")))
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
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid export request")))
                        }
                    }

                    get("/api/workspace-search") {
                        val query = call.request.queryParameters["q"].orEmpty()
                        val maxDocuments = call.request.queryParameters["maxDocuments"]?.toIntOrNull() ?: 50
                        val maxMatchesPerDocument =
                            call.request.queryParameters["maxMatchesPerDocument"]?.toIntOrNull() ?: 5
                        val maxScanMillis =
                            call.request.queryParameters["maxScanMillis"]?.toLongOrNull() ?: 3_000L

                        val response = withContext(workspaceSearchDispatcher) {
                            val requestJob = coroutineContext.job
                            searchWorkspace(
                                base = home,
                                rawQuery = query,
                                maxDocuments = maxDocuments,
                                maxMatchesPerDocument = maxMatchesPerDocument,
                                maxScanMillis = maxScanMillis,
                                cancellationCheck = {
                                    if (!requestJob.isActive) throw CancellationException("workspace search cancelled")
                                }
                            )
                        }
                        call.respond(response)
                    }

                    route("/welcome") {
                        handle {
                            val originalContent = File("./src/commonMain/resources/welcome.html").readText(Charsets.UTF_8)
                            val modifiedContent = filter(originalContent)
                            call.respondText(modifiedContent, ContentType.Text.Html)
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
                            val projectUrl = "https://localhost:${port}/${encodedName}/${SavingContext.METADATA_EDN}"

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
                                                    val message3 = message.replyTo?.let { message2.toName(it) } ?: message2
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
                            // Clean up back pressure state for this session.
                            sessionOutputPressure.remove(ts)
                        }
                    }
                    route("/dir") {
                        handle {
                            val originalContent = File("./src/commonMain/resources/dir.html").readText(Charsets.UTF_8)
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
                            val originalContent = File("./src/commonMain/resources/tree.html").readText(Charsets.UTF_8)
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
                                call.respond(HttpStatusCode.BadRequest, "missing or invalid lazy value fragment parameters")
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
                                if (requested.isAbsolute) requested.canonicalFile else File(workspaceRoot, document).canonicalFile
                            }
                            val screenshotRoot = File(documentDir, "history_screenshots").canonicalFile
                            val file = File(documentDir, relativePath).canonicalFile
                            val insideWorkspace = documentDir == workspaceRoot ||
                                documentDir.path.startsWith(workspaceRoot.path + File.separator)
                            val liveDocumentAllowed = liveDocument != null && documentDir == File(liveDocument.filename).canonicalFile
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

                    files("./build/js/packages/afield/kotlin")
                    files("./src/jsChatMain/resources/static/")
                    files("./src/jsMain/kotlin/resources/")
                    files("./src/jsMain/resources/")
                    files("./src/jsMain/resources/monaco")
                    files("./src/commonMain/kotlin/resources/")
                    files("./src/commonMain/resources/")

                    route("/chat") {
                        handle {
                            call.respondFile(File("./build/js/packages/afield-js-chat/kotlin/static/chat.html"))
                        }
                    }

                    static("/") {
                        intercept(ApplicationCallPipeline.Call) {
                            var effectivePath = URI(call.request.uri).path
                            if (effectivePath.endsWith("/")) effectivePath = effectivePath + "index.html"
                            if (effectivePath.endsWith("index.html")) {
                                // List directories in the order you want to search for index.html
                                val directories = listOf(
                                    File("./build/kotlin-webpack/js/productionExecutable/"),
                                    File("./build/kotlin-webpack/jsChat/productionExecutable/"),
                                    File("./src/jsChatMain/resources/"),
                                    File("./src/jsMain/resources/"),
                                    File("./src/commonMain/resources/"),
//                                    File("./build/vite/dist/")
                                )

                                val indexFile = directories.map { File(it, effectivePath.trim('.', '/')) }
                                    .firstOrNull { it.exists() }

                                if (indexFile != null) {
                                    val originalContent = indexFile.readText(Charsets.UTF_8)

                                    lprintln("info,server", "serving index file from ${indexFile.absolutePath}")

                                    val modifiedContent = filter(originalContent)
                                    call.respondText(modifiedContent, ContentType.Text.Html)
                                    finish() // Stop further processing of this call
                                }
                            }
                        }
                        files(home + "/")
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
                                lprintln("warning,server", "project file not found or outside project directory: $relativeUri")
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
                            lprintln("warning,server", "failed to find route for $fullUri; checked ${dynamicRoutes.getRoutes().entries}")
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
                        lprintln("warning,server", "Requested port ${preferredPort} was unavailable; using fallback port ${candidatePort}")
                    }
                    break
                } catch (t: Throwable) {
                    if (!isPortBindException(t)) throw t
                    bindFailure = t
                    lprintln("warning,server", "Port ${candidatePort} is already in use; trying next port")
                }
            }

            if (!started) {
                throw bindFailure ?: IllegalStateException("Unable to bind Field server to any port in range ${preferredPort}..${preferredPort + portFallbackCount - 1}")
            }

            globalServerPort = port
            val initialURL = "https://localhost:${port}/index.html?file=${lastFn}"
            lprintln("info,server", asciiWelcome)
            lprintln("info,server", "welcome to Field, ß-033")
            lprintln("info,server", "last document: $initialURL")
            lprintln("info,server", "welcome page: https://localhost:${port}/welcome.html")
            lprintln("info,server", "---")

            if (preferSystemBrowser) {
//                    thread { openSystemBrowser(initialURL) }
            }
            else {
                thread {
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
