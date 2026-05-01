package afield

import afield.four.synth.musicClock
import afield.four.synth.patClock
import afield.logger.lprintln
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToLong

private data class DocumentTarget(
    val sessionKey: String,
    val documentName: String,
    val scratch: Boolean
)

private data class CapturedProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)

private val agentBackdoorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun cleanupDetachedSession(info: SessionInformation) {
    if (info.sessions.isNotEmpty()) return
    info.cleanup()
    if (info.ephemeral) {
        documents.entries.removeIf { it.value === info }
        clearProvisional(info.filename)
    }
}

internal fun detachDocumentSession(session: WebSocketSession, info: SessionInformation? = sessions.remove(session)): SessionInformation? {
    val target = info ?: return null
    detachSpreadsheetClient(session)
    target.sessions.remove(session)
    cleanupDetachedSession(target)
    return target
}

private fun registerDocumentSession(session: WebSocketSession, info: SessionInformation) {
    val previous = sessions.put(session, info)
    if (previous != null && previous !== info) {
        previous.sessions.remove(session)
        cleanupDetachedSession(previous)
    }
    thisSessionInfo.set(info)
    info.sessions.add(session)
}

private fun resolveDocumentTarget(documentName: String?, scratch: Boolean, sessionKey: String? = null): DocumentTarget {
    val trimmed = documentName?.trim().takeIf { !it.isNullOrEmpty() }
    val explicitSessionKey = sessionKey?.trim().takeIf { !it.isNullOrEmpty() }
    if (explicitSessionKey != null) {
        val existing = documents[explicitSessionKey]
        return DocumentTarget(
            sessionKey = explicitSessionKey,
            documentName = trimmed ?: existing?.documentName ?: explicitSessionKey.substringAfterLast("/"),
            scratch = scratch || existing?.ephemeral == true
        )
    }
    if (scratch || trimmed == null) {
        val displayName = trimmed ?: "scratch"
        val syntheticPath = File(home, ".agent-scratch/${UUID.randomUUID()}").absolutePath
        return DocumentTarget(
            sessionKey = syntheticPath,
            documentName = displayName,
            scratch = true
        )
    }
    return DocumentTarget(
        sessionKey = home + "/" + trimmed,
        documentName = trimmed,
        scratch = false
    )
}

private fun createSessionInformation(target: DocumentTarget): SessionInformation {
    val root = Box("root")
    root.name = "root"
    root.visible = false
    root.patClock = musicClock()

    runAll(newRoot, root)

    val info = SessionInformation(
        filename = target.sessionKey,
        documentName = target.documentName,
        root = root,
        ephemeral = target.scratch
    )
    root.inside = info
    return info
}

internal fun attachDocumentSession(
    session: WebSocketSession,
    documentName: String?,
    username: String = "agent",
    scratch: Boolean = false,
    sessionKey: String? = null
): SessionInformation {
    val target = resolveDocumentTarget(documentName, scratch, sessionKey)
    File(home).mkdirs()

    if (!target.scratch) {
        lastFn = target.documentName
        if (!target.documentName.isHttpUrl()) {
            recordRecentDocument(target.documentName)
        }
    }

    val found = documents[target.sessionKey]
    if (found != null) {
        registerDocumentSession(session, found)
        sendDocumentLoadStatus(
            session,
            phase = "attached",
            message = "attached to live document",
            count = countLoadableBoxes(found)
        )
        sendExpectedBoxCount(session, found)
        sendDocumentLoadStatus(
            session,
            phase = "resync-started",
            message = "queuing document state",
            count = countLoadableBoxes(found),
            completed = 0
        )
        found.boxes.values.forEach { it.resyncAll() }
        sendDocumentLoadStatus(
            session,
            phase = "resync-queued",
            message = "document state queued",
            count = countLoadableBoxes(found),
            completed = countLoadableBoxes(found),
            terminal = true
        )
        found.topologyGeneration++
        found.scheduleAutorunBoxes()
        return found
    }

    val info = createSessionInformation(target)
    documents[target.sessionKey] = info
    registerDocumentSession(session, info)

    if (!target.scratch) {
        try {
            sendDocumentLoadStatus(
                session,
                phase = "metadata-loading",
                message = "reading metadata"
            )
            val boxes = SavingContext().load(target.sessionKey)
            lprintln("info,agent_backdoor", "loaded boxes from ${target.sessionKey} for ${username}")
            boxes.forEach {
                it.children.removeIf { child -> child.getPropertyRaw("_tombstone").isTrue() }
                it.parents.removeIf { parent -> parent.getPropertyRaw("_tombstone").isTrue() }
            }

            info.root.children.removeIf { it.getPropertyRaw("_tombstone").isTrue() }
            val count = countLoadableBoxes(info)
            sendDocumentLoadStatus(
                session,
                phase = "metadata-loaded",
                message = "metadata loaded",
                count = count,
                completed = count
            )
            sendExpectedBoxCount(session, info)
            sendDocumentLoadStatus(
                session,
                phase = "stdlib-started",
                message = "initializing boxes",
                count = count,
                completed = 0
            )
            var initialized = 0
            info.root.children.forEach {
                it.ki
                initialized++
                if (initialized == count || initialized % 25 == 0) {
                    sendDocumentLoadStatus(
                        session,
                        phase = "stdlib-queued",
                        message = "queued box initialization",
                        count = count,
                        completed = initialized,
                        terminal = initialized == count
                    )
                }
            }
            if (count > 0 && initialized != count) {
                sendDocumentLoadStatus(
                    session,
                    phase = "stdlib-queued",
                    message = "queued box initialization",
                    count = count,
                    completed = initialized,
                    terminal = true
                )
            }
            if (count == 0) {
                sendDocumentLoadStatus(
                    session,
                    phase = "empty",
                    message = "empty document",
                    count = 0,
                    completed = 0,
                    terminal = true
                )
            }
            info.topologyGeneration++
            info.fileWatchManager
            info.scheduleAutorunBoxes()
        } catch (fnf: FileNotFoundException) {
            lprintln("warning,agent_backdoor", "file '${target.sessionKey}' not found; will be created on first save")
            sendExpectedBoxCount(session, info)
            sendDocumentLoadStatus(
                session,
                phase = "empty",
                message = "new document",
                count = 0,
                completed = 0,
                terminal = true
            )
        }
    } else {
        sendExpectedBoxCount(session, info)
        sendDocumentLoadStatus(
            session,
            phase = "empty",
            message = "scratch document",
            count = 0,
            completed = 0,
            terminal = true
        )
    }

    return info
}

private fun countLoadableBoxes(info: SessionInformation): Int {
    return info.boxes.values.count { !it.getPropertyRaw("_tombstone").isTrue() && it.id != "root" }
}

private fun sendExpectedBoxCount(session: WebSocketSession, info: SessionInformation) {
    val count = countLoadableBoxes(info)
    _send(session, Message("expected-box-count").payload(Expected_box_count(count)))
}

private fun sendDocumentLoadStatus(
    session: WebSocketSession,
    phase: String,
    message: String = "",
    count: Int = -1,
    completed: Int = -1,
    terminal: Boolean = false
) {
    _send(
        session,
        Message("document-load-status").payload(
            DocumentLoadStatus(
                phase = phase,
                message = message,
                count = count,
                completed = completed,
                terminal = terminal
            )
        )
    )
}

private fun activeRunningStrands(box: Box): Map<String, Int> {
    val hasActiveJobs = box.jobs.any { !it.isCancelled && it.isActive }
    if (!hasActiveJobs) return emptyMap()
    return box.runGeneration
        .filterValues { it >= 0 }
        .toSortedMap()
}

private fun idsFor(boxes: Collection<Box>): List<String> =
    boxes
        .filter { !it.getPropertyRaw("_tombstone").isTrue() }
        .map { it.id }
        .sorted()

private fun snapshotFor(box: Box): AgentBoxSnapshot {
    val nestedIn = box.nestedIn?.takeIf { !it.getPropertyRaw("_tombstone").isTrue() }?.id
    return AgentBoxSnapshot(
        uid = box.id,
        name = box.name,
        frame = box.frame,
        runningStrands = activeRunningStrands(box),
        code = box.getPropertyRaw("code") as? String,
        preamble = box.getPropertyRaw("preamble") as? String,
        fragment = box.getPropertyRaw("fragment") as? String,
        vertex = box.getPropertyRaw("vertex") as? String,
        vertex_fragment = box.getPropertyRaw("vertex_fragment") as? String,
        fragment_s = box.getPropertyRaw("fragment_s") as? String,
        vertex_s = box.getPropertyRaw("vertex_s") as? String,
        compute = box.getPropertyRaw("compute") as? String,
        compute_s = box.getPropertyRaw("compute_s") as? String,
        mesh = box.getPropertyRaw("mesh") as? String,
        mesh_s = box.getPropertyRaw("mesh_s") as? String,
        task = box.getPropertyRaw("task") as? String,
        task_s = box.getPropertyRaw("task_s") as? String,
        raygen = box.getPropertyRaw("raygen") as? String,
        raygen_s = box.getPropertyRaw("raygen_s") as? String,
        miss = box.getPropertyRaw("miss") as? String,
        miss_s = box.getPropertyRaw("miss_s") as? String,
        closesthit = box.getPropertyRaw("closesthit") as? String,
        closesthit_s = box.getPropertyRaw("closesthit_s") as? String,
        anyhit = box.getPropertyRaw("anyhit") as? String,
        anyhit_s = box.getPropertyRaw("anyhit_s") as? String,
        intersection = box.getPropertyRaw("intersection") as? String,
        intersection_s = box.getPropertyRaw("intersection_s") as? String,
        callable = box.getPropertyRaw("callable") as? String,
        callable_s = box.getPropertyRaw("callable_s") as? String,
        customization = box.getPropertyRaw("customization") as? String,
        metadata = box.getPropertyRaw("metadata") as? String,
        code_language = box.getPropertyRaw("code_language") as? String,
        autorun = box.getPropertyRaw("autorun").isTrue(),
        layers = box.getPropertyRaw("layers") as? String,
        visible = (box.getPropertyRaw("visible") as? Boolean) ?: true,
        offline = (box.getPropertyRaw("offline") as? Boolean) ?: true,
        _tombstone = box.getPropertyRaw("_tombstone").isTrue(),
        children = idsFor(box.children),
        nested = idsFor(box.nested),
        nestedIn = nestedIn
    )
}

internal fun buildAgentSnapshot(info: SessionInformation): AgentDocumentSnapshot {
    val boxes = info.boxes.values
        .sortedWith(compareBy<Box>({ it.name.lowercase() }, { it.id }))
        .map(::snapshotFor)
    return AgentDocumentSnapshot(
        documentName = info.documentName,
        scratch = info.ephemeral,
        sessionKey = info.filename,
        boxes = boxes
    )
}

private fun applyAgentProperty(box: Box, property: String, value: JsonElement) {
    if (property == "frame") return
    if (value is JsonPrimitive) {
        when {
            value.isString -> {
                if (property == "name") box.name = value.content
                else box.setPropertyRaw(property, value.content, doResync = true)
                if (Box.ioSeparateProperties.containsKey(property) && !box.inside.ephemeral) {
                    box.inside.pendingSidecarSaves += box.id to property
                }
            }
            value.booleanOrNull != null -> box.setPropertyRaw(property, value.booleanOrNull!!, doResync = true)
            value.doubleOrNull != null -> box.setPropertyRaw(property, value.doubleOrNull!!, doResync = true)
            value.contentOrNull == null -> {}
        }
    }
}

private fun currentJavaExecutable(): String {
    val javaHome = System.getProperty("java.home") ?: error("java.home is not set")
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    return File(File(javaHome, "bin"), executable).absolutePath
}

private fun requiredAgentJvmArgs(): List<String> {
    val os = (System.getProperty("os.name") ?: "").lowercase()
    val onWindowsOrLinux = os.contains("windows") || os.contains("linux")

    return buildList {
        add("--add-opens=java.base/java.net=ALL-UNNAMED")
        add("-Dorg.lwjgl.system.stackSize=256")
        add("-Xlog:gc*,safepoint:file=build/gc-safepoint.log:time,uptime,level,tags")
        if (!onWindowsOrLinux) {
            add("-XstartOnFirstThread")
            add("-Djoml.format=false")
            add("-Dorg.lwjgl.util.Debug=true")
            add("-Dorg.lwjgl.util.DebugLoader=true")
        }
        add("--add-modules")
        add("jdk.incubator.vector")
    }
}

private suspend fun gobbleProcessStream(reader: BufferedReader, sink: StringBuilder) {
    withContext(Dispatchers.IO) {
        reader.useLines { lines ->
            lines.forEach { line ->
                sink.appendLine(line)
            }
        }
    }
}

private suspend fun runCapturedProcess(
    command: List<String>,
    workingDirectory: File,
    timeoutMs: Long,
    charset: Charset = Charsets.UTF_8
): CapturedProcessResult = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command)
        .directory(workingDirectory)
        .start()

    val stdout = StringBuilder()
    val stderr = StringBuilder()

    coroutineScope {
        val stdoutJob = async(Dispatchers.IO) {
            gobbleProcessStream(process.inputStream.bufferedReader(charset), stdout)
        }
        val stderrJob = async(Dispatchers.IO) {
            gobbleProcessStream(process.errorStream.bufferedReader(charset), stderr)
        }

        val exitCode = withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<Int> { cont ->
                process.onExit().thenAccept { finished ->
                    cont.resume(finished.exitValue())
                }
                cont.invokeOnCancellation {
                    runCatching { process.destroy() }
                }
            }
        }

        val timedOut = exitCode == null
        if (timedOut) {
            runCatching { process.destroy() }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
            }
        }

        awaitAll(stdoutJob, stderrJob)

        CapturedProcessResult(
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = timedOut
        )
    }
}

private suspend fun runJvmMainViaSubprocess(request: AgentRunJvmMainRequest): AgentRunJvmMainResponse {
    val classpath = System.getProperty("java.class.path")
        ?.takeIf { it.isNotBlank() }
        ?: error("java.class.path is blank")
    val timeoutMs = max(1_000L, (request.timeoutSec * 1000.0).roundToLong())
    val command = buildList {
        add(currentJavaExecutable())
        addAll(requiredAgentJvmArgs())
        add("-cp")
        add(classpath)
        add("afield.AgentJvmMainLauncher")
        add(request.entrypoint)
        addAll(request.args)
    }
    val startedAt = System.nanoTime()
    val result = runCapturedProcess(
        command = command,
        workingDirectory = File(app),
        timeoutMs = timeoutMs
    )
    val durationMs = (System.nanoTime() - startedAt) / 1_000_000L
    val ok = !result.timedOut && result.exitCode == 0
    val message = when {
        result.timedOut -> "Timed out after ${request.timeoutSec}s"
        result.exitCode != null && result.exitCode != 0 -> "Process exited with code ${result.exitCode}"
        else -> null
    }
    return AgentRunJvmMainResponse(
        ok = ok,
        entrypoint = request.entrypoint,
        args = request.args,
        exitCode = result.exitCode,
        stdout = result.stdout,
        stderr = result.stderr,
        durationMs = durationMs,
        timedOut = result.timedOut,
        message = message,
        command = command
    )
}

@RunsAt(installHandlers)
fun installAgentBackdoorHandlers() {
    handlers["agent-open-document"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentOpenDocument>(message.payload!!)
        val info = attachDocumentSession(
            session = thisSession.get(),
            documentName = request.documentName,
            username = request.username,
            scratch = request.scratch
        )
        reply(Message("agent-open-document").payload(buildAgentSnapshot(info)))
        Handled.yes
    })

    handlers["agent-snapshot"] = mutableSetOf({ _, reply ->
        val info = thisSessionInfo.get()
        reply(Message("agent-snapshot").payload(buildAgentSnapshot(info)))
        Handled.yes
    })

    handlers["agent-create-box"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentCreateBox>(message.payload!!)
        val info = thisSessionInfo.get()
        val uid = UUID.randomUUID().toString()
        val created = box(uid, createIfMissing = true, initIfCreated = true, root = info)

        created.name = request.name
        created.frame = request.frame
        request.properties.forEach { (property, value) ->
            applyAgentProperty(created, property, value)
        }

        needsResync.add(info.root to "children")
        needsResync.add(created to "parents")
        needsResync.add(info.root to "actions")
        needsResync.add(created to "actions")
        created.resyncAll()
        info.topologyGeneration++
        doResync()

        reply(
            Message("agent-create-box").payload(
                AgentCreateBoxResponse(
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    box = snapshotFor(created)
                )
            )
        )
        Handled.yes
    })

    handlers["agent-open-playwright"] = mutableSetOf({ _, reply ->
        val info = thisSessionInfo.get()
        playwrightScope.launch {
            val response = runCatching {
                ensurePlaywrightForSession(info)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "playwright open failed for ${info.documentName}: ${throwable.message}")
                AgentOpenPlaywrightResponse(
                    ok = false,
                    error = throwable.message ?: throwable::class.simpleName ?: "Unknown Playwright error",
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    url = buildPlaywrightDocumentUrl(info),
                    ready = false
                )
            }
            reply(Message("agent-open-playwright").payload(response))
        }
        Handled.yes
    })

    handlers["agent-open-editor"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val request = Json.decodeFromJsonElement<AgentOpenEditorRequest>(message.payload!!)
        playwrightScope.launch {
            val response = runCatching {
                ensurePlaywrightForSessionIfNeeded(info)
                openEditorForCapture(info, request)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "editor open failed for ${request.uid}: ${throwable.message}")
                AgentOpenEditorResponse(
                    ok = false,
                    uid = request.uid,
                    property = request.property,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = throwable.message ?: throwable::class.simpleName ?: "Unknown editor open error"
                )
            }
            reply(Message("agent-open-editor").payload(response))
        }
        Handled.yes
    })

    handlers["agent-playwright-input"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val request = Json.decodeFromJsonElement<AgentPlaywrightInputRequest>(message.payload!!)
        playwrightScope.launch {
            val response = runCatching {
                val currentPage = globalPlaywriteContext?.takeIf { !it.isClosed() }
                if (currentPage == null) {
                    ensurePlaywrightForSessionIfNeeded(info)
                }
                performPlaywrightInput(info, request)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "playwright input failed for ${request.action}: ${throwable.message}")
                AgentPlaywrightInputResponse(
                    ok = false,
                    action = request.action,
                    key = request.key,
                    uid = request.uid,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = throwable.message ?: throwable::class.simpleName ?: "Unknown Playwright input error"
                )
            }
            reply(Message("agent-playwright-input").payload(response))
        }
        Handled.yes
    })

    handlers["agent-run-jvm-main"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentRunJvmMainRequest>(message.payload!!)
        agentBackdoorScope.launch {
            val response = runCatching {
                runJvmMainViaSubprocess(request)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "jvm-main run failed for ${request.entrypoint}: ${throwable.message}")
                AgentRunJvmMainResponse(
                    ok = false,
                    entrypoint = request.entrypoint,
                    args = request.args,
                    stdout = "",
                    stderr = "",
                    durationMs = 0,
                    timedOut = false,
                    message = throwable.message ?: throwable::class.simpleName ?: "Unknown jvm-main error"
                )
            }
            reply(Message("agent-run-jvm-main").payload(response))
        }
        Handled.yes
    })

    handlers["agent-window-screenshot"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val request = Json.decodeFromJsonElement<AgentWindowScreenshot>(message.payload!!)
        playwrightScope.launch {
            val kind = if (request.editorOnly) "editor" else "window"
            val response = runCatching {
                ensurePlaywrightForSessionIfNeeded(info)
                captureWindowScreenshot(info, request)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "window screenshot failed for ${request.path}: ${throwable.message}")
                AgentScreenshotResponse(
                    ok = false,
                    kind = kind,
                    path = request.path,
                    uid = request.uid,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = throwable.message ?: throwable::class.simpleName ?: "Unknown screenshot error"
                )
            }
            reply(Message("agent-window-screenshot").payload(response))
        }
        Handled.yes
    })

    handlers["agent-box-screenshot"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val request = Json.decodeFromJsonElement<AgentBoxScreenshot>(message.payload!!)
        playwrightScope.launch {
            val response = runCatching {
                ensurePlaywrightForSessionIfNeeded(info)
                captureBoxScreenshot(info, request)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "box screenshot failed for ${request.uid}: ${throwable.message}")
                AgentScreenshotResponse(
                    ok = false,
                    kind = "box",
                    path = request.path,
                    uid = request.uid,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = throwable.message ?: throwable::class.simpleName ?: "Unknown screenshot error"
                )
            }
            reply(Message("agent-box-screenshot").payload(response))
        }
        Handled.yes
    })

    handlers["agent-window-video"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val request = Json.decodeFromJsonElement<AgentWindowVideo>(message.payload!!)
        playwrightScope.launch {
            val kind = if (request.editorOnly) "editor" else "window"
            val response = runCatching {
                ensurePlaywrightForSessionIfNeeded(info)
                captureWindowVideo(info, request)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "window video failed for ${request.path}: ${throwable.message}")
                AgentVideoResponse(
                    ok = false,
                    kind = kind,
                    path = request.path,
                    uid = request.uid,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = throwable.message ?: throwable::class.simpleName ?: "Unknown video error"
                )
            }
            reply(Message("agent-window-video").payload(response))
        }
        Handled.yes
    })

    handlers["agent-box-video"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val request = Json.decodeFromJsonElement<AgentBoxVideo>(message.payload!!)
        playwrightScope.launch {
            val response = runCatching {
                ensurePlaywrightForSessionIfNeeded(info)
                captureBoxVideo(info, request)
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "box video failed for ${request.uid}: ${throwable.message}")
                AgentVideoResponse(
                    ok = false,
                    kind = "box",
                    path = request.path,
                    uid = request.uid,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = throwable.message ?: throwable::class.simpleName ?: "Unknown video error"
                )
            }
            reply(Message("agent-box-video").payload(response))
        }
        Handled.yes
    })
}
