package afield

import afield.four.Renderer
import afield.ai.core.AI4_AUTO_NAME_PROPERTY
import afield.ai.core.Ai4BackdoorTools
import afield.ai.core.aiToolMapper
import afield.four.synth.musicClock
import afield.four.synth.patClock
import afield.four.util.Window
import afield.four.util.openWindowsSnapshot
import afield.logger.lprintln
import afield.stdlib.Action
import afield.stdlib.actions
import afield.server.refreshFileProxyBoxes
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val scratch: Boolean,
    /** Open the real document, but with the write-back path switched off. */
    val readOnly: Boolean = false
)

private data class CapturedProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)

private val agentBackdoorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val AGENT_GRAPHICS_CONTEXT_ID = "agent_graphics_context"
private const val AGENT_GRAPHICS_CONTEXT_NAME = "renderer"
private const val INITIAL_FRAME_PREVIEW_PAINT_PAUSE_MS = 100L

private fun nativeCaptureWindow(info: SessionInformation, uid: String?): Window {
    val open = openWindowsSnapshot().toSet()
    val bindings = info.boxes.mapValues { (_, box) ->
        // Inspect existing engines only: capture selection must never initialize a REPL.
        val values = box.properties.values.toList() + box.liveKi?.context?.vars?.values.orEmpty().map { it.value }
        val windows = values.mapNotNull {
            when (it) {
                is Window -> it
                is Renderer -> it.window
                else -> null
            }
        }.filter { it in open }.distinct()
        val parents = box.parents.filter { info.boxes[it.id] === it }.map { it.id }
        NativeCaptureBox(parents, windows)
    }
    return resolveNativeCaptureTarget(bindings, uid)
}

private fun escapeNotifyText(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun cleanupDetachedSession(info: SessionInformation) {
    if (info.sessions.isNotEmpty()) return
    // An automation client asked for this named document to keep running between its invocations.
    if (info.keepRunningWhenDetached && !info.ephemeral) return
    detachRootFromMetaroot(info.root)
    info.cleanup()
    if (info.ephemeral) {
        documents.entries.removeIf { it.value === info }
        clearProvisional(info.filename)
    }
}

internal fun detachDocumentSession(
    session: WebSocketSession,
    info: SessionInformation? = sessions.remove(session)
): SessionInformation? {
    val target = info ?: return null
    detachSpreadsheetClient(session)
    target.sessions.remove(session)
    cleanupDetachedSession(target)
    broadcastWelcomeEvent("session-detached")
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
    UserThemeManager.sendTo(session)
    UserKeybindingsManager.sendTo(session)
    sendSessionWriteStatus(session, info)
    broadcastWelcomeEvent("session-attached")
}

/**
 * Tell the client whether this session's work will be kept.
 *
 * Here rather than in the `hello` handler because this is the one function every attach path funnels
 * through — interactive `hello`, the agent backdoor's `open`, the Playwright page the docs capture
 * drives — and it is already where per-session config is pushed (`UserThemeManager`,
 * `UserKeybindingsManager`). The `hello` handler would have been wrong as well as narrower:
 * `launchDocumentSessionAttach` is a coroutine launch, so the handler returns before the session
 * exists and would have had nothing to report on.
 *
 * Sent for writable sessions too — see [SessionWriteStatus].
 */
private fun sendSessionWriteStatus(session: WebSocketSession, info: SessionInformation) {
    _send(
        session,
        Message("set-session-write-status").payload(
            SessionWriteStatus(
                readOnly = info.readOnly,
                documentName = info.documentName,
                reason = if (info.readOnly) {
                    "This document is open read-only. Nothing you change here will be saved."
                } else {
                    ""
                }
            )
        )
    )
}

private fun resolveDocumentTarget(
    documentName: String?,
    scratch: Boolean,
    sessionKey: String? = null,
    readOnly: Boolean = false
): DocumentTarget {
    val trimmed = documentName?.trim().takeIf { !it.isNullOrEmpty() }
    val explicitSessionKey = sessionKey?.trim().takeIf { !it.isNullOrEmpty() }
    if (explicitSessionKey != null) {
        val existing = documents[explicitSessionKey]
        return DocumentTarget(
            sessionKey = explicitSessionKey,
            documentName = trimmed ?: existing?.documentName ?: explicitSessionKey.substringAfterLast("/"),
            scratch = scratch || existing?.ephemeral == true,
            readOnly = readOnly || existing?.readOnly == true
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
    // The one route to a real, named, existing document. Before `readOnly` existed there was no
    // way to reach one without the write-back path: asking for `scratch` above throws the name
    // away and synthesises a new directory, so "open midi_clip without touching it" was not
    // expressible.
    return DocumentTarget(
        sessionKey = home + "/" + trimmed,
        documentName = trimmed,
        scratch = false,
        readOnly = readOnly
    )
}

private fun createSessionInformation(target: DocumentTarget, agentManaged: Boolean): SessionInformation {
    val root = Box("root")
    root.name = "root"
    root.visible = false
    root.patClock = musicClock()

    runAll(newRoot, root)

    val info = SessionInformation(
        filename = target.sessionKey,
        documentName = target.documentName,
        root = root,
        ephemeral = target.scratch,
        readOnly = target.readOnly || ReadOnlyDocuments.global
    )
    // Only genuine agent-backdoor loads are agent-managed: keep the agent's box names
    // (no AI auto-rename). Interactive browser sessions (the `hello` handshake) must NOT set
    // this, or AI auto box-naming is disabled for every normal terminal-launched session.
    info.agentManaged = agentManaged
    root.inside = info
    attachRootToMetaroot(root)
    return info
}

internal fun attachDocumentSession(
    session: WebSocketSession,
    documentName: String?,
    username: String = "agent",
    scratch: Boolean = false,
    sessionKey: String? = null,
    agentManaged: Boolean = false,
    readOnly: Boolean = false
): SessionInformation {
    val target = resolveDocumentTarget(documentName, scratch, sessionKey, readOnly)
    File(home).mkdirs()

    if (!target.scratch && !isFieldAppRouteDocumentName(target.documentName)) {
        lastFn = target.documentName
        if (!target.documentName.isHttpUrl()) {
            recordRecentDocument(target.documentName)
        }
    }

    // Probed here and nowhere later: the load below calls `ensureDirectory`, so once it has run
    // the directory is there whether or not the document was. A scratch target is a freshly
    // synthesised uuid path and never existed. See `AgentDocumentSnapshot.documentExisted`.
    val existedOnDisk = !target.scratch && SavingContext.documentExistsOnDisk(target.sessionKey)

    val found = documents[target.sessionKey]
    if (found != null) {
        found.attachedToLiveDocument = true
        // A live document existed however its bytes got there -- it may have been created in this
        // Field and not saved yet, in which case the disk probe above says no and is not wrong.
        found.documentExisted = true
        // Once an agent backdoor has touched a live document, keep it agent-managed even if a
        // browser later attaches to the same session — never downgrade agent protection.
        if (agentManaged) found.agentManaged = true
        // `readOnly` is fixed when the session is built, so attaching to one that is already open
        // writable cannot make it read-only. Say so rather than accept the request and ignore it;
        // the snapshot this returns reports the real value, so a caller can check as well as read.
        if (target.readOnly && !found.readOnly) {
            lprintln(
                "warning,agent_backdoor",
                "read-only ignored for ${target.documentName}: already open and writable in this " +
                    "Field. Close it, or launch with --read-only."
            )
        }
        attachRootToMetaroot(found.root)
        registerDocumentSession(session, found)
        sendExpectedBoxCount(session, found)
        sendDocumentLoadStatus(
            session,
            phase = "attached",
            message = "attached to live document",
            count = countLoadableBoxes(found)
        )
        sendDocumentLoadStatus(
            session,
            phase = "resync-started",
            message = "queuing document state",
            count = countLoadableBoxes(found),
            completed = 0
        )
        queueStartupPriorityDocumentResync(found)
        refreshFileProxyBoxes(found, notifyMissing = true)
        sendDocumentLoadStatus(
            session,
            phase = "resync-queued",
            message = "document state queued",
            count = countLoadableBoxes(found),
            completed = countLoadableBoxes(found),
            terminal = true
        )
        queueStartupDeferredDocumentResync(found)
        doResync()
        scheduleStartupActionDocumentResync(found)
        found.topologyGeneration++
        found.scheduleAutorunBoxes()
        // Study cards are drawn by library code, not by a run this page could see; give it the last of each.
        afield.stdlib.BoxStudies.replayTo(session, found)
        return found
    }

    val info = createSessionInformation(target, agentManaged)
    info.documentExisted = existedOnDisk
    info.attachedToLiveDocument = false
    info.loading = !target.scratch
    documents[target.sessionKey] = info
    registerDocumentSession(session, info)
    var finishLoadingInFinally = true

    if (!target.scratch) {
        try {
            val initialFrames = previewInitialBoxFrames(target.sessionKey)
            if (initialFrames.isNotEmpty()) {
                sendExpectedBoxFrames(session, initialFrames, flushNow = true)
                lprintln(
                    "info,agent_backdoor",
                    "sent ${initialFrames.size} initial box frame previews for ${target.sessionKey}"
                )
                Thread.sleep(INITIAL_FRAME_PREVIEW_PAINT_PAUSE_MS)
            }

            sendDocumentLoadStatus(
                session,
                phase = "metadata-started",
                message = "loading metadata",
                completed = 0
            )

            // Phase 1: Load boxes without triggering KotlinInterface init.
            // Box creation and property deserialization is fast; the heavy
            // synchronous KotlinInterface2 construction is deferred so we can
            // report progress to the frontend between boxes.
            val boxes = SavingContext().load(target.sessionKey, initIfCreated = false)
            lprintln("info,agent_backdoor", "loaded boxes from ${target.sessionKey} for ${username}")
            boxes.forEach {
                it.children.removeIf { child -> child.getPropertyRaw("_tombstone").isTrue() }
                it.parents.removeIf { parent -> parent.getPropertyRaw("_tombstone").isTrue() }
            }
            refreshFileProxyBoxes(info, notifyMissing = true)

            info.root.children.removeIf { it.getPropertyRaw("_tombstone").isTrue() }
            attachRootToMetaroot(info.root)

            // Phase 2: Tell the frontend how many boxes to expect BEFORE the
            // slow initialization work begins.
            val startupBoxes = loadableBoxes(info)
            val count = startupBoxes.size
            sendExpectedBoxCount(session, info)
            sendDocumentLoadStatus(
                session,
                phase = "metadata-loaded",
                message = "metadata loaded",
                count = count,
                completed = 0
            )
            sendDocumentLoadStatus(
                session,
                phase = "stdlib-started",
                message = "initializing boxes",
                count = count,
                completed = 0
            )

            // Loading metadata sets many properties with normal resync flags.
            // Clear those immediately and send only first-paint state before
            // KotlinInterface startup gives the regular runloop a chance to
            // drain a full code/editor-state sync.
            queueStartupPriorityDocumentResync(info, startupBoxes)

            // Phase 3: Queue KotlinInterface creation for every box. Accessing
            // box.ki starts stdlib import work asynchronously; the box only
            // becomes truly online when that import clears box.offline.
            var queued = 0
            startupBoxes.forEach {
                it.ki
                queued++
                if (queued == 1 || queued % 10 == 0 || queued == count) {
                    sendDocumentLoadStatus(
                        session,
                        phase = "stdlib-queued",
                        message = "queued box Kotlin runtimes",
                        count = count,
                        completed = startupBoxes.count { !it.offline }
                    )
                }
            }
            if (count > 0 && queued != count) {
                sendDocumentLoadStatus(
                    session,
                    phase = "stdlib-queued",
                    message = "queued box Kotlin runtimes",
                    count = count,
                    completed = startupBoxes.count { !it.offline }
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

            // Phase 5: Report real completion based on boxes leaving offline.
            // This runs away from the websocket hello handler; large documents
            // must not keep the open handshake occupied while compilers finish.
            if (count > 0) {
                finishLoadingInFinally = false
                agentBackdoorScope.launch {
                    var lastOnline = -1
                    var attempts = 0
                    while (attempts < 1200) {
                        val online = startupBoxes.count { !it.offline }
                        discardStartupDeferredResync(info, startupBoxes)
                        if (online == count) {
                            doResync()
                        }
                        if (online != lastOnline || attempts % 10 == 0 || online == count) {
                            sendDocumentLoadStatus(
                                session,
                                phase = if (online == count) "stdlib-complete" else "stdlib-running",
                                message = if (online == count) "box Kotlin runtimes ready" else "compiling box stdlib imports",
                                count = count,
                                completed = online,
                                terminal = online == count
                            )
                            lastOnline = online
                        }
                        if (online != count) {
                            doResync()
                        }
                        if (online == count) break
                        Thread.sleep(100)
                        attempts++
                    }
                    if (lastOnline < count) {
                        sendDocumentLoadStatus(
                            session,
                            phase = "stdlib-timeout",
                            message = "still waiting for box Kotlin runtimes",
                            count = count,
                            completed = lastOnline,
                            terminal = true
                        )
                    }
                    info.loading = false
                    queueStartupDeferredDocumentResync(info, startupBoxes)
                    doResync()
                    scheduleStartupActionDocumentResync(info, startupBoxes)
                    info.topologyGeneration++
                    info.fileWatchManager
                    info.scheduleAutorunBoxes()
                }
            } else {
                info.topologyGeneration++
                info.fileWatchManager
                info.scheduleAutorunBoxes()
            }
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
        } finally {
            if (finishLoadingInFinally) {
                info.loading = false
            }
        }
    } else {
        info.loading = false
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

internal fun launchDocumentSessionAttach(
    session: WebSocketSession,
    documentName: String?,
    username: String = "agent",
    scratch: Boolean = false,
    sessionKey: String? = null,
    agentManaged: Boolean = false,
    readOnly: Boolean = false
) {
    agentBackdoorScope.launch {
        thisSession.set(session)
        try {
            attachDocumentSession(
                session = session,
                documentName = documentName,
                username = username,
                scratch = scratch,
                sessionKey = sessionKey,
                agentManaged = agentManaged,
                readOnly = readOnly
            )
        } catch (t: Throwable) {
            lprintln("error,agent_backdoor", "document attach failed for ${documentName ?: sessionKey ?: "<scratch>"}: ${t.message}")
            lprintln("error,agent_backdoor", t.stackTraceToString())
            _send(
                session,
                Message("notify").payload(
                    Notify("Could not load document: ${escapeNotifyText(t.message ?: t.javaClass.simpleName)}", "setup")
                )
            )
            _send(
                session,
                Message("document-load-status").payload(
                    DocumentLoadStatus(
                        phase = "error",
                        message = "document load failed",
                        terminal = true
                    )
                )
            )
        } finally {
            thisSession.remove()
            thisSessionInfo.remove()
        }
    }
}

private fun loadableBoxes(info: SessionInformation): List<Box> {
    val seen = linkedSetOf<Box>()
    val visited = linkedSetOf<Box>()

    fun include(box: Box) {
        if (box.id == "root") return
        if (box.getPropertyRaw("_tombstone").isTrue()) return
        seen.add(box)
    }

    fun visit(box: Box) {
        if (!visited.add(box)) return
        (box.children + box.nested).forEach { child ->
            include(child)
            visit(child)
        }
    }

    visit(info.root)
    info.boxes.values.forEach(::include)
    return seen.toList()
}

private fun countLoadableBoxes(info: SessionInformation): Int = loadableBoxes(info).size

private val startupPriorityResyncProperties = listOf(
    "frame",
    "name",
    "_tombstone",
    "visible",
    "layers",
    "layerFrames",
    "children",
    "parents",
    "nested",
    "nestedIn",
    "offline",
    "autorun",
    AI4_AUTO_NAME_PROPERTY
)

private val startupPriorityResyncPropertySet = startupPriorityResyncProperties.toSet()
private val startupDeferredResyncPropertySet = startupPriorityResyncPropertySet + "actions"

private fun queueStartupPriorityDocumentResync(info: SessionInformation, boxes: List<Box> = loadableBoxes(info)) {
    discardStartupResync(info, boxes)
    info.root.resyncOnly(startupPriorityResyncProperties)
    boxes.forEach { it.resyncOnly(startupPriorityResyncProperties) }
    doResync()
}

private fun queueStartupDeferredDocumentResync(info: SessionInformation, boxes: List<Box> = loadableBoxes(info)) {
    info.root.resyncAllExcept(startupDeferredResyncPropertySet)
    boxes.forEach { it.resyncAllExcept(startupDeferredResyncPropertySet) }
}

private fun scheduleStartupActionDocumentResync(info: SessionInformation, boxes: List<Box> = loadableBoxes(info)) {
    agentBackdoorScope.launch {
        delay(15_000)
        if (info.sessions.isEmpty()) return@launch
        info.root.resyncOnly(listOf("actions"))
        boxes.forEach { it.resyncOnly(listOf("actions")) }
        doResync()
    }
}

private fun discardStartupResync(info: SessionInformation, boxes: List<Box> = loadableBoxes(info)) {
    val startupSyncBoxes = (listOf(info.root) + boxes).toSet()
    needsResync.removeAll { (box, _) -> box in startupSyncBoxes }
}

private fun discardStartupDeferredResync(info: SessionInformation, boxes: List<Box> = loadableBoxes(info)) {
    val startupSyncBoxes = (listOf(info.root) + boxes).toSet()
    needsResync.removeAll { (box, name) -> box in startupSyncBoxes && name !in startupPriorityResyncPropertySet }
}

private fun previewInitialBoxFrames(sessionKey: String): List<InitialBoxFrame> =
    try {
        SavingContext().readInitialBoxFrames(sessionKey)
    } catch (fnf: FileNotFoundException) {
        emptyList()
    } catch (t: Throwable) {
        lprintln("warning,agent_backdoor", "unable to read initial box frame preview for $sessionKey: ${t.message}")
        emptyList()
    }

private fun sendExpectedBoxCount(session: WebSocketSession, info: SessionInformation) {
    val boxes = loadableBoxes(info)
    sendExpectedBoxFrames(
        session,
        boxes.map { InitialBoxFrame(it.id, it.frame) }
    )
}

private fun sendExpectedBoxFrames(session: WebSocketSession, frames: List<InitialBoxFrame>, flushNow: Boolean = false) {
    val message = Message("expected-box-count").payload(
        Expected_box_count(
            count = frames.size,
            frames = frames
        )
    )
    if (flushNow) {
        val encoded = Json.encodeToString(message)
        runBlocking {
            session.send(Frame.Text(encoded))
        }
    } else {
        _send(session, message)
    }
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

internal fun activeRunningStrands(box: Box): Map<String, Int> {
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

internal fun snapshotFor(box: Box): AgentBoxSnapshot {
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
        notes = box.getPropertyRaw("notes") as? String,
        code_language = box.getPropertyRaw("code_language") as? String,
        default_property = (box.getPropertyRaw("default_property") as? String)?.takeIf { it.isNotBlank() },
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
        boxes = boxes,
        readOnly = info.readOnly,
        documentExisted = info.documentExisted,
        attachedToLiveDocument = info.attachedToLiveDocument,
        executionVerdicts = true
    )
}

private fun applyAgentProperty(box: Box, property: String, value: JsonElement) {
    if (property == "frame") return
    if (value is JsonPrimitive) {
        when {
            value.isString -> {
                if (property == "name") box.name = value.content
                else box.setPropertyRaw(property, value.content, doResync = true)
                if (Box.ioSeparateProperties.containsKey(property) && box.inside.writesToDisk) {
                    box.inside.markSidecarPending(box.id, property)
                }
            }

            value.booleanOrNull != null -> box.setPropertyRaw(property, value.booleanOrNull!!, doResync = true)
            value.doubleOrNull != null -> box.setPropertyRaw(property, value.doubleOrNull!!, doResync = true)
            value.contentOrNull == null -> {}
        }
    }
}

/**
 * String form of a box property for transcript recording, mirroring the UI `set-string`
 * handler in [Server]: `name` reads from [Box.name], everything else from `getPropertyRaw`.
 */
private fun agentPropertyString(box: Box, property: String): String =
    if (property == "name") box.name else box.getPropertyRaw(property)?.toString().orEmpty()

/**
 * Apply a backdoor property mutation and record it into [OperationTranscript], so
 * backdoor-driven semantic edits land in the canonical transcript exactly like
 * interactive UI edits do (see `set-string` in Server.kt). Structural create/move/connect
 * are intentionally not recorded here — the UI does not record those either; the transcript
 * tracks property edits, moves, and execution, and this closes the property-edit gap.
 */
internal fun applyAgentPropertyRecorded(box: Box, property: String, value: JsonElement) {
    if (property == "frame") {
        applyAgentProperty(box, property, value)
        return
    }
    val from = agentPropertyString(box, property)
    applyAgentProperty(box, property, value)
    val to = agentPropertyString(box, property)
    OperationTranscript.recordEdit(box, property, from, to)
}

private fun ensureAgentGraphicsContext(info: SessionInformation): Pair<Box, Boolean> {
    val existing = info.root.children.firstOrNull {
        it.name == AGENT_GRAPHICS_CONTEXT_NAME && it.getPropertyRaw("renderer") is Renderer
    }
    val graphicsBox =
        existing ?: box(AGENT_GRAPHICS_CONTEXT_ID, createIfMissing = true, initIfCreated = true, root = info).also {
            it.name = AGENT_GRAPHICS_CONTEXT_NAME
            it.frame = Rect(0.0, 0.0, 120.0, 80.0)
        }

    val renderer = graphicsBox.getPropertyRaw("renderer") as? Renderer ?: Renderer(Window("Field")).also {
        graphicsBox.setPropertyRaw("window", it.window, doResync = true)
        graphicsBox.setPropertyRaw("renderer", it, doResync = true)
    }

    val hasActiveRendererJob = graphicsBox.jobs.any { it.isActive && !it.isCancelled }
    if (!hasActiveRendererJob) {
        val strand = "default"
        val generation = (graphicsBox.runGeneration[strand] ?: -1) + 1
        graphicsBox.runGeneration[strand] = generation
        graphicsBox.currentRunningStates[strand] = RunningState(generation, "graphics-context")
        send(Message("is-running").payload(IsRunning(graphicsBox.id, true, generation, strand, "graphics-context")))
        val job = inMain {
            try {
                renderer.run()
            } catch (t: Throwable) {
                lprintln("error,agent_backdoor", "graphics context renderer failed: ${t.stackTraceToString()}")
            } finally {
                if (graphicsBox.currentRunningStates[strand]?.watermark == generation) {
                    graphicsBox.currentRunningStates.remove(strand)
                }
                send(
                    Message("is-running").payload(
                        IsRunning(
                            graphicsBox.id,
                            false,
                            generation,
                            strand,
                            "graphics-context"
                        )
                    )
                )
            }
        }
        graphicsBox.jobs += job
    }

    needsResync.add(info.root to "children")
    needsResync.add(graphicsBox to "parents")
    needsResync.add(graphicsBox to "actions")
    graphicsBox.resyncAll()
    info.topologyGeneration++
    doResync()

    return graphicsBox to (existing != null)
}

/**
 * Tear down a [Renderer] owned by [box] and its GLFW window(s), releasing the native
 * Vulkan/OpenXR surface and window that the agent graphics-context path otherwise leaks.
 *
 * Cancelling the render coroutine only stops the loop; it never reaches [Renderer.cleanup].
 * This cancels the box's jobs, then schedules the native teardown on the main render thread
 * (so it runs after the render loop has unwound). Returns true if a renderer was disposed.
 *
 * **Dependents stop first.** [Renderer.cleanup] frees every device-owned buffer, including ones a
 * *child* box is still writing from a launched `loop {}`. Freeing them under that loop was a
 * SIGSEGV in `VulkanBuffer.mapAndWrite` on the next runloop tick (reproduced 2/2 on 2026-09-26,
 * via stop-box on the renderer box and via the document's last session detaching). So the boxes
 * that can reach this renderer — [rendererDependents] — are stopped here, and the teardown waits
 * for their jobs to finish, bounded by [RENDERER_TEARDOWN_JOIN_MS], before touching the device.
 * See `dev_docs/renderer_teardown_order.md`.
 *
 * Idempotent: the renderer/window properties are removed up-front, so a second call — e.g.
 * stop-box followed by document detach — finds nothing and does not double-free.
 */
internal fun disposeBoxRenderer(box: Box): Boolean {
    val renderer = box.getPropertyRaw("renderer") as? Renderer ?: return false

    // Stop the render loop; its jobs are cancelled with the dependents' below.
    renderer.running = false

    // Drop the references before scheduling teardown so a concurrent dispose is a no-op.
    box.properties.remove("renderer")
    box.properties.remove("window")

    val stopped = beginRendererTeardown(box) {
        runCatching {
            val before = openWindowsSnapshot().size
            try {
                renderer.cleanup()
            } finally {
                renderer.windows.forEach { it.cleanup() }
            }
            val after = openWindowsSnapshot().size
            lprintln(
                "info,agent_backdoor",
                "disposed renderer for box ${box.id}; openWindows $before -> $after"
            )
        }.onFailure {
            lprintln("warning,agent_backdoor", "graphics context dispose failed: ${it.stackTraceToString()}")
        }
    }
    offerRelaunchOfStoppedDependents(box, stopped)
    return true
}

/**
 * The box action a renderer box carries after its teardown stopped dependents: one command-palette
 * entry (with that box selected) or `field_backdoor.py --command 'action <uid> relaunch-stopped-dependents'`.
 * See [relaunchRendererDependents].
 */
internal const val RELAUNCH_STOPPED_DEPENDENTS_ACTION = "relaunch-stopped-dependents"

/** By renderer-box id: the dependents its most recent teardown stopped, parents before children. */
private val rendererTeardownStopped = java.util.concurrent.ConcurrentHashMap<String, List<Box>>()

/** The dependents [owner]'s most recent renderer teardown stopped and has not relaunched yet. */
internal fun stoppedByRendererTeardown(owner: Box): List<Box> = rendererTeardownStopped[owner.id].orEmpty()

/**
 * Remember what teardown stopped under [owner], and offer to put it back. Stopping a renderer box
 * stops its dependents (they would otherwise write into freed GPU memory), so restarting only
 * the renderer used to strand them. The offer is the [RELAUNCH_STOPPED_DEPENDENTS_ACTION] action
 * on [owner]; a later teardown of the same box replaces it.
 */
internal fun offerRelaunchOfStoppedDependents(owner: Box, stopped: List<Box>) {
    if (stopped.isEmpty()) {
        rendererTeardownStopped.remove(owner.id)
        owner.actions.remove(RELAUNCH_STOPPED_DEPENDENTS_ACTION)
        return
    }
    rendererTeardownStopped[owner.id] = stopped
    val names = stopped.joinToString { it.name.ifBlank { it.id } }
    owner.actions[RELAUNCH_STOPPED_DEPENDENTS_ACTION] = Action(
        desc = "Relaunch the ${stopped.size} box(es) stopping this renderer stopped",
        detail = "Stopping this renderer also stopped $names. Relaunches this box first if it has no " +
            "renderer, then those boxes, parents before children."
    ) { relaunchRendererDependents(owner) }
    needsResync.add(owner to "actions")
    lprintln(
        "info,agent_backdoor",
        "renderer box ${owner.name.ifBlank { owner.id }}: to start the ${stopped.size} stopped box(es) again, " +
            "run its '$RELAUNCH_STOPPED_DEPENDENTS_ACTION' action (command palette with the box selected, " +
            "or backdoor `action ${owner.id} $RELAUNCH_STOPPED_DEPENDENTS_ACTION`)"
    )
}

/**
 * Launch again the boxes [owner]'s last renderer teardown stopped, in the order they were found
 * (parents before children), preceded by [owner] itself when it holds no renderer, so the
 * children find one. Consumes the record and withdraws the action. Returns the boxes launched.
 */
internal fun relaunchRendererDependents(
    owner: Box,
    launch: (List<Box>) -> Unit = ::launchBoxesInOrder,
): List<Box> {
    val stopped = rendererTeardownStopped.remove(owner.id).orEmpty()
    owner.actions.remove(RELAUNCH_STOPPED_DEPENDENTS_ACTION)
    needsResync.add(owner to "actions")
    val batch = buildList {
        if (owner.getPropertyRaw("renderer") !is Renderer) add(owner)
        stopped.filterTo(this) { it !== owner }
    }
    if (batch.isNotEmpty()) launch(batch)
    lprintln(
        "info,agent_backdoor",
        "relaunched ${batch.size} box(es) for renderer box ${owner.name.ifBlank { owner.id }}: " +
            batch.joinToString { it.name.ifBlank { it.id } }
    )
    return batch
}

/** Launch [boxes] as one ordered batch, the way `launch-batch` does, each on its default property. */
private fun launchBoxesInOrder(boxes: List<Box>) {
    val reply: (Message) -> Unit = { send(it) }
    val requests = boxes.map { box ->
        val property = (box.getPropertyRaw("default_property") as? String)?.ifBlank { null } ?: "code"
        box to Execute(
            uid = box.id,
            type = "launch",
            line = 0,
            code = box.getPropertyRaw(property) as? String ?: "",
            singleLine = false,
            property = property,
        )
    }
    if (requests.all { it.first.ke is KotlinExecution }) {
        KotlinExecution.launchBatch(requests, reply)
    } else {
        requests.forEach { (box, exec) -> box.ke.launchAll(box, exec, exec.code, exec.strand, reply) }
    }
}

/** How long renderer teardown waits for stopped dependents to unwind before disposing anyway. */
internal const val RENDERER_TEARDOWN_JOIN_MS = 2_000L

/**
 * The boxes that can reach [owner]'s renderer: its transitive children. That is the population
 * `fromContext()` and inherited properties (`prop<Renderer>(renderer)`) search — both walk
 * *up* `parents` — so a box outside it can only hold the renderer through a global or a captured
 * reference, which teardown cannot see.
 */
internal fun rendererDependents(owner: Box): List<Box> {
    val seen = LinkedHashSet<Box>()
    val queue = ArrayDeque(owner.children.toList())
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (next === owner || !seen.add(next)) continue
        queue.addAll(next.children.toList())
    }
    return seen.toList()
}

/**
 * The ordering behind [disposeBoxRenderer], without the renderer, so it can be tested with no GPU:
 * stop [owner] and every running [rendererDependents] box *now* (on the caller's thread, the way
 * stop-box does), then on the render thread wait for all their jobs to complete — bounded by
 * [joinTimeoutMs] — and only then run [dispose].
 *
 * The wait suspends rather than blocks: the jobs being cancelled are usually dispatched on the
 * same main runloop that runs [dispose], so blocking it would wait for work that can never run.
 * A dependent that has not finished by the deadline is logged by name and [dispose] runs anyway;
 * device-owned resources then refuse use loudly (see `VulkanBuffer.map`) rather than fault.
 *
 * Returns the dependents it stopped, parents before children, so the caller can offer to launch
 * them again ([offerRelaunchOfStoppedDependents]).
 */
internal fun beginRendererTeardown(
    owner: Box,
    joinTimeoutMs: Long = RENDERER_TEARDOWN_JOIN_MS,
    onRenderThread: (suspend () -> Unit) -> Unit = { block -> inMain { block() } },
    stopBox: (Box) -> Unit = { it.ke.stop(it) },
    dispose: () -> Unit,
): List<Box> {
    val dependents = rendererDependents(owner).filter {
        it.jobs.isNotEmpty() || it.currentRunningStates.isNotEmpty() || it.stopHooks.isNotEmpty()
    }
    val pending = (listOf(owner) + dependents).flatMap { b -> b.jobs.toList().map { b to it } }

    // The owner's own jobs (typically `start(renderer::run)`) are cancelled directly, as before.
    val ownerJobs = owner.jobs.toList()
    owner.jobs.clear()
    owner.clearRunningStates()
    owner.runGeneration["default"] = (owner.runGeneration["default"] ?: -1) + 1
    ownerJobs.forEach { it.cancel() }

    dependents.forEach { dependent ->
        runCatching { stopBox(dependent) }.onFailure {
            lprintln("warning,agent_backdoor", "stopping ${dependent.id} before renderer teardown threw: $it")
        }
    }
    if (dependents.isNotEmpty()) {
        lprintln(
            "info,agent_backdoor",
            "renderer teardown for box ${owner.id}: stopped ${dependents.size} dependent box(es) first: " +
                dependents.joinToString { it.name.ifBlank { it.id } }
        )
    }

    onRenderThread {
        withTimeoutOrNull(joinTimeoutMs) { pending.forEach { (_, job) -> job.join() } }
        val stragglers = pending.filter { (_, job) -> !job.isCompleted }.map { it.first }.distinct()
        if (stragglers.isNotEmpty()) {
            lprintln(
                "warning,agent_backdoor",
                "renderer teardown for box ${owner.id}: ${stragglers.size} box(es) still running after " +
                    "${joinTimeoutMs} ms, disposing anyway: " + stragglers.joinToString { it.name.ifBlank { it.id } }
            )
        }
        dispose()
    }
    return dependents
}

/**
 * Dispose the agent graphics-context box's renderer for [info], if one exists.
 * Returns the graphics box (if found) and whether a renderer was actually disposed.
 */
private fun disposeAgentGraphicsContext(info: SessionInformation): Pair<Box?, Boolean> {
    val graphicsBox = info.root.children.firstOrNull {
        it.name == AGENT_GRAPHICS_CONTEXT_NAME && it.getPropertyRaw("renderer") is Renderer
    } ?: return null to false

    val disposed = disposeBoxRenderer(graphicsBox)

    needsResync.add(info.root to "children")
    needsResync.add(graphicsBox to "parents")
    needsResync.add(graphicsBox to "actions")
    graphicsBox.resyncAll()
    info.topologyGeneration++
    doResync()

    return graphicsBox to disposed
}

private fun currentJavaExecutable(): String {
    val javaHome = System.getProperty("java.home") ?: error("java.home is not set")
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    return File(File(javaHome, "bin"), executable).absolutePath
}

/**
 * The JVM args every `jvm-main` child gets, whatever it is.
 *
 * This is the subprocess mirror of `requiredRunJvmArgs()` in build.gradle.kts, and the two blocks
 * have to stay in step for the same reasons that one is commented: an arg that is load-bearing for
 * the app is load-bearing here too, because the child runs the same code.
 */
private fun requiredAgentJvmArgs(): List<String> {
    val os = (System.getProperty("os.name") ?: "").lowercase()
    val onWindowsOrLinux = os.contains("windows") || os.contains("linux")

    return buildList {
        add("--add-opens=java.base/java.net=ALL-UNNAMED")
        add("--enable-native-access=ALL-UNNAMED")
        // Child smoke JVMs need the same Steam Audio native tree (or explicit library override)
        // as Field itself. The bindings otherwise look for a separate natives resource jar.
        for (property in listOf("steamaudio.library.file", "steamaudio.library.path")) {
            System.getProperty(property)?.takeIf { it.isNotBlank() }?.let { add("-D$property=$it") }
        }
        add("--sun-misc-unsafe-memory-access=allow")
        add("-Dorg.lwjgl.system.stackSize=256")
        add("-Xlog:gc*,safepoint:file=build/gc-safepoint.log:time,uptime,level,tags")
        // Mandatory alongside -XstartOnFirstThread (below), and the reason it is unconditional
        // rather than inside the `if`: it must be a -D on the command line, since AWT reads it once
        // when the first AWT class initialises, and by then a gallery is already drawing.
        //
        // With -XstartOnFirstThread and non-headless AWT, `CFontManager.loadFonts` dispatches to
        // the process's first thread and waits — and a child that opens no window never pumps that
        // run loop, so the first `Font.createGlyphVector` never returns. `Typefaces.warm` refuses
        // to block and throws instead (see its KDoc), which is why the symptom was not a hang but
        // *figures with no text in them*: a caller that treats a missing font as "draw the diagram
        // without labels" produces a plausible, wordless picture and exits 0.
        //
        // Headless AWT keeps the entire font/glyph/raster stack — which afield.stdlib.typography is
        // built on — and drops only the windowing toolkit, which nothing in Field uses: it renders
        // through GLFW/Vulkan and its UI is Electron. See requiredRunJvmArgs() in build.gradle.kts,
        // which has carried this arg for the app JVM all along.
        add("-Djava.awt.headless=true")
        if (!onWindowsOrLinux) {
            add("-XstartOnFirstThread")
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
    val inherited = System.getProperty("java.class.path")
        ?.takeIf { it.isNotBlank() }
        ?: error("java.class.path is blank")

    // The personal jar is deliberately OFF this server's `java.class.path` — PersonalLayer loads it into a
    // child classloader so it can be swapped without a restart (see PersonalLayer's docs). A subprocess
    // inheriting only `java.class.path` therefore cannot see personal code at all, and a `jvm-main`
    // entrypoint living in src/personal fails with ClassNotFoundException.
    //
    // A one-shot subprocess has nothing to gain from the swappable layer: it runs, it exits. So it gets
    // personal flat on its own `-cp`, which is the whole fix and is why an entrypoint under src/personal
    // is testable at all.
    val classpath = (listOf(inherited) + PersonalLayer.roots.map { it.absolutePath })
        .joinToString(File.pathSeparator)
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
/**
 * The body of `agent-create-box`: a new box under [info]'s root, named, framed, with [properties]
 * applied through the recorded property path, and resynced to every attached client.
 *
 * Shared with Field's own agent (`NewBoxRequest`), so a box the in-Field agent makes is the same
 * object, persisted the same way, as one the backdoor makes. [initKi] exists for tests, which have
 * no Kotlin REPL to build.
 */
internal fun createAgentBox(
    info: SessionInformation,
    name: String,
    frame: Rect,
    properties: Map<String, JsonElement>,
    initKi: Boolean = true,
): Box {
    val uid = UUID.randomUUID().toString()
    val created = box(uid, createIfMissing = true, initIfCreated = initKi, root = info)

    created.name = name
    created.frame = frame
    properties.forEach { (property, value) ->
        applyAgentPropertyRecorded(created, property, value)
    }

    needsResync.add(info.root to "children")
    needsResync.add(created to "parents")
    needsResync.add(info.root to "actions")
    needsResync.add(created to "actions")
    created.resyncAll()
    info.topologyGeneration++
    doResync()
    return created
}

fun installAgentBackdoorHandlers() {
    handlers["agent-open-document"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentOpenDocument>(message.payload!!)
        val info = attachDocumentSession(
            session = thisSession.get(),
            documentName = request.documentName,
            username = request.username,
            scratch = request.scratch,
            agentManaged = true,
            readOnly = request.readOnly
        )
        if (request.keepRunning && !info.ephemeral) info.keepRunningWhenDetached = true
        reply(Message("agent-open-document").payload(buildAgentSnapshot(info)))
        Handled.yes
    })

    handlers["agent-snapshot"] = mutableSetOf({ _, reply ->
        val info = thisSessionInfo.get()
        reply(Message("agent-snapshot").payload(buildAgentSnapshot(info)))
        Handled.yes
    })

    // Provider-neutral ai4 tool surface (Tier-1: Macro*/SemanticRetrieval*/agent memory).
    // Reuses the same ToolRegistry the OpenAI harness uses; gates hide any tool whose
    // provider is not wired for the backdoor context. Surfaced over MCP by field_mcp.py.
    handlers["agent-ai-tools-list"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val focusBox = (message.payload as? kotlinx.serialization.json.JsonObject)?.get("focusBox")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        val ctx = Ai4BackdoorTools.buildContext(info, focusBox)
        val tools = Ai4BackdoorTools.registry.describe(ctx)
        reply(Message("agent-ai-tools-list").payload(AgentAiToolsListResponse(tools = tools)))
        Handled.yes
    })

    handlers["agent-ai-tool-call"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentAiToolCallRequest>(message.payload!!)
        val session = thisSession.get()
        val info = thisSessionInfo.get()
        // Tools may run a transcript or hit an index; run off the socket thread with
        // the session thread-locals rebound (mirrors the macro-transcript-command path).
        agentBackdoorScope.launch {
            thisSession.set(session)
            thisSessionInfo.set(info)
            try {
                val ctx = Ai4BackdoorTools.buildContext(info, request.focusBox)
                val argsJson = request.arguments?.toString() ?: "{}"
                val result = Ai4BackdoorTools.registry.executeByName(ctx, request.name, argsJson)
                val resultJson = Json.parseToJsonElement(aiToolMapper.writeValueAsString(result))
                reply(
                    Message("agent-ai-tool-call").payload(
                        AgentAiToolCallResponse(name = request.name, ok = true, result = resultJson,
                            images = (result as? afield.ai.core.CarriesToolImages)?.toolImages.orEmpty()
                                .take(8).map { AgentToolImage(it.dataUrl, it.caption) })
                    )
                )
            } catch (t: Throwable) {
                lprintln("error,agent-backdoor", "ai tool '${request.name}' failed: ${t.message}")
                lprintln("error,agent-backdoor", t.stackTraceToString())
                reply(
                    Message("agent-ai-tool-call").payload(
                        AgentAiToolCallResponse(
                            name = request.name,
                            ok = false,
                            error = t.message ?: t.javaClass.simpleName
                        )
                    )
                )
            } finally {
                thisSession.remove()
                thisSessionInfo.remove()
            }
        }
        Handled.yes
    })

    handlers["agent-set-property"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentSetPropertyRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val target = info.boxes[request.uid]
        if (target == null) {
            reply(
                Message("agent-set-property").payload(
                    AgentSetPropertyResponse(
                        ok = false, uid = request.uid, property = request.property,
                        message = "No box '${request.uid}' in ${info.documentName}."
                    )
                )
            )
            return@mutableSetOf Handled.yes
        }
        // Direct set (string), reusing the create-box property path: handles sidecar
        // persistence for ioSeparateProperties like `notes` (written as a .md sidecar).
        // Records the edit into OperationTranscript, mirroring the UI set-string handler.
        applyAgentPropertyRecorded(target, request.property, JsonPrimitive(request.value))
        needsResync.add(target to request.property)
        doResync()
        reply(
            Message("agent-set-property").payload(
                AgentSetPropertyResponse(
                    ok = true, uid = request.uid, property = request.property,
                    message = "Set ${request.property} on ${target.name.ifBlank { request.uid }} (${request.value.length} chars)."
                )
            )
        )
        Handled.yes
    })

    handlers["agent-get-property"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentGetPropertyRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val target = info.boxes[request.uid]
        val response = if (target == null) {
            AgentGetPropertyResponse(
                ok = false, uid = request.uid, property = request.property,
                message = "No box '${request.uid}' in ${info.documentName}."
            )
        } else {
            val raw = if (request.property == "name") target.name else target.getPropertyRaw(request.property)
            if (raw is String) {
                AgentGetPropertyResponse(
                    ok = true, uid = request.uid, property = request.property, value = raw
                )
            } else {
                AgentGetPropertyResponse(
                    ok = false, uid = request.uid, property = request.property,
                    message = "${target.name.ifBlank { request.uid }} has no string property '${request.property}'."
                )
            }
        }
        reply(Message("agent-get-property").payload(response))
        Handled.yes
    })

    handlers["agent-create-box"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentCreateBox>(message.payload!!)
        val info = thisSessionInfo.get()
        val created = createAgentBox(info, request.name, request.frame, request.properties)

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

    handlers["agent-wait-frames"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AgentWaitFramesRequest>(message.payload!!)
        val frames = request.frames.coerceAtLeast(0L)
        val startFrame = Runloop.runLoop.frame
        val targetFrame = startFrame + frames
        agentBackdoorScope.launch {
            val timedOut = withTimeoutOrNull((request.timeoutSec * 1000.0).roundToLong().coerceAtLeast(1L)) {
                while (Runloop.runLoop.frame < targetFrame) {
                    delay(1)
                }
                false
            } ?: true
            val endFrame = Runloop.runLoop.frame
            reply(
                Message("agent-wait-frames").payload(
                    AgentWaitFramesResponse(
                        ok = !timedOut,
                        requestedFrames = frames,
                        startFrame = startFrame,
                        endFrame = endFrame,
                        elapsedFrames = (endFrame - startFrame).coerceAtLeast(0L),
                        timedOut = timedOut,
                        message = if (timedOut) "Timed out waiting for $frames Runloop frames" else ""
                    )
                )
            )
        }
        Handled.yes
    })

    handlers["agent-ensure-graphics-context"] = mutableSetOf({ _, reply ->
        val info = thisSessionInfo.get()
        inMain {
            val response = runCatching {
                val (graphicsBox, reused) = ensureAgentGraphicsContext(info)
                AgentGraphicsContextResponse(
                    ok = true,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    box = snapshotFor(graphicsBox),
                    reused = reused
                )
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "graphics context setup failed: ${throwable.message}")
                val fallback = info.root
                AgentGraphicsContextResponse(
                    ok = false,
                    error = throwable.message ?: throwable::class.simpleName ?: "Unknown graphics context error",
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    box = snapshotFor(fallback),
                    reused = false
                )
            }
            reply(Message("agent-ensure-graphics-context").payload(response))
        }
        Handled.yes
    })

    handlers["agent-dispose-graphics-context"] = mutableSetOf({ _, reply ->
        val info = thisSessionInfo.get()
        inMain {
            val response = runCatching {
                val (graphicsBox, disposed) = disposeAgentGraphicsContext(info)
                AgentGraphicsContextResponse(
                    ok = true,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    box = snapshotFor(graphicsBox ?: info.root),
                    reused = disposed
                )
            }.getOrElse { throwable ->
                lprintln("warning,agent_backdoor", "graphics context dispose failed: ${throwable.message}")
                AgentGraphicsContextResponse(
                    ok = false,
                    error = throwable.message ?: throwable::class.simpleName ?: "Unknown graphics context error",
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    box = snapshotFor(info.root),
                    reused = false
                )
            }
            reply(Message("agent-dispose-graphics-context").payload(response))
        }
        Handled.yes
    })

    handlers["agent-open-playwright"] = mutableSetOf({ _, reply ->
        val info = thisSessionInfo.get()
        playwrightScope.launch {
            val response = runCatching {
                ensurePlaywrightForSession(info)
            }.getOrElse { throwable ->
                lprintln(
                    "warning,agent_backdoor",
                    "playwright open failed for ${info.documentName}: ${throwable.message}"
                )
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
                lprintln(
                    "warning,agent_backdoor",
                    "playwright input failed for ${request.action}: ${throwable.message}"
                )
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
                lprintln(
                    "warning,agent_backdoor",
                    "jvm-main run failed for ${request.entrypoint}: ${throwable.message}"
                )
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

    handlers["agent-native-window-screenshot"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val request = Json.decodeFromJsonElement<AgentNativeWindowScreenshot>(message.payload!!)
        // Resolve before handing work to the IO scope, while we have the document context.
        val target = runCatching { nativeCaptureWindow(info, request.uid) }
        agentBackdoorScope.launch {
            val response = runCatching {
                val window = target.getOrThrow()
                File(request.path).parentFile?.mkdirs()
                window.raiseAndScreenshot(request.waitFrames, request.path, captureTimeoutMs = request.captureTimeoutMs)
                AgentScreenshotResponse(
                    ok = true,
                    kind = "native-window",
                    path = request.path,
                    uid = request.uid,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = ""
                )
            }.getOrElse { throwable ->
                lprintln(
                    "warning,agent_backdoor",
                    "native window screenshot failed for ${request.path}: ${throwable.message}"
                )
                AgentScreenshotResponse(
                    ok = false,
                    kind = "native-window",
                    path = request.path,
                    uid = request.uid,
                    documentName = info.documentName,
                    scratch = info.ephemeral,
                    sessionKey = info.filename,
                    message = throwable.message ?: throwable::class.simpleName
                    ?: "Unknown native window screenshot error",
                    terminal = throwable is afield.four.NativeWindowCaptureStalledException ||
                        throwable is NativeCaptureTargetException
                )
            }
            reply(Message("agent-native-window-screenshot").payload(response))
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
