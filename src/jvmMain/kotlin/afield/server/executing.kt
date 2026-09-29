package afield.server

import afield.*
import afield.Server.Companion.stop
import afield.logger.lprintln
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private fun clearEditorOutputsForLaunch(uid: String) {
    send(Message("clear-editor-outputs").payload(ClearEditorOutputs(uid)))
}

/**
 * [box], with its main language applied now rather than on the topology watcher's next pass. A document
 * that has just opened, or a root that has just been marked, has not had that pass yet, so a scene run
 * at once went to Kotlin and failed on its RectSL (`Expecting an element`) — as the conversation
 * recorder found on 2026-09-27, launching a scene the moment its document opened.
 */
private fun inItsLanguage(box: Box): Box {
    afield.runtime.refreshMainLanguageRuntimes(box.parents + box)
    return box
}

/**
 * The body of the `stop-box` handler, shared with Field's own agent (`StopBoxRequest`) so that both
 * record the stop in the operation transcript and tear down an owned renderer identically.
 */
internal fun stopBoxRequest(target: Box, entrypoint: String) {
    val box = inItsLanguage(target)
    OperationTranscript.recordStop(box)
    stop(box, entrypoint = entrypoint)
}

/**
 * The body of the `execute-all` handler: reset the box's REPL (Kotlin), clear preamble bookkeeping,
 * and run the whole property on the Runloop with the originating session rebound.
 *
 * Shared with Field's own agent (`RunBoxRequest`), which passes a null session: a headless run has no
 * websocket to answer, and every output site already tolerates that (it falls back to a broadcast).
 * Returns immediately; the verdict arrives through [reply] as `execution-verdict` when
 * [Execute.requestId] is set.
 */
internal fun executeAllRequest(
    target: Box,
    exec: Execute,
    reply: (Message) -> Unit,
    originSession: io.ktor.websocket.WebSocketSession?,
    originSessionInfo: SessionInformation?,
) {
    val box = inItsLanguage(target)
    inMain {
        val previousSession = thisSession.get()
        val previousSessionInfo = thisSessionInfo.get()
        try {
            if (originSession == null) thisSession.remove() else thisSession.set(originSession)
            if (originSessionInfo == null) thisSessionInfo.remove() else thisSessionInfo.set(originSessionInfo)
            FlightRecorder.recordExecuteEntrypoint("execute-all", box, exec)
            // experimental
            if (box.ke is KotlinExecution)
                box.ki = makeKIHere(box, reason = "execute-all handler reset")
            box.properties["_preamble"] = "" // remove record of previous preamble line execs
            box.properties["_introduction"] = "" // ditto for introduction
            box.properties.remove("_attic")
            box.ke.executeAll(box, exec, reply)
        } finally {
            if (previousSession == null) thisSession.remove() else thisSession.set(previousSession)
            if (previousSessionInfo == null) thisSessionInfo.remove() else thisSessionInfo.set(previousSessionInfo)
        }
    }
}

/** The body of the `launch-all` handler, shared with Field's own agent (`LaunchBoxRequest`). */
internal fun launchAllRequest(target: Box, exec: Execute, reply: (Message) -> Unit) {
    val box = inItsLanguage(target)
    clearEditorOutputsForLaunch(exec.uid)
    FlightRecorder.recordLaunchEntrypoint("launch-all", box, exec, strand = "default")
    OperationTranscript.recordLaunch(box, exec, strand = "default")
    box.ke.launchAll(box, exec, exec.code, "default", reply)
}

@RunsAt(installHandlers)
fun executing() {

    handlers["stop-box"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Stop_box>(message.payload!!)
            stopBoxRequest(box(exec.uid), entrypoint = "stop-box")

            Handled.yes
        })

    handlers["execute-fragment"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->
            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = inItsLanguage(box(exec.uid))
            FlightRecorder.recordExecuteEntrypoint("execute-fragment", box, exec)
            OperationTranscript.recordExecuteFragment(box, exec)
            box.ke.executeFragment(box, exec, reply)
            Handled.yes
        })

    handlers["execute-symbol"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->
            val exec = Json.decodeFromJsonElement<ExecuteSymbol>(message.payload!!)
            val box = inItsLanguage(box(exec.uid))
            FlightRecorder.recordExecuteSymbolEntrypoint("execute-symbol", box, exec)
            box.ke.executeSymbol(box, exec, reply)
            Handled.yes
        })

    handlers["execute-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            executeAllRequest(box(exec.uid), exec, reply, thisSession.get(), thisSessionInfo.get())

            Handled.yes
        })

    handlers["launch-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            launchAllRequest(box(exec.uid), exec, reply)

            Handled.yes
        })

    handlers["launch-batch"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val batch = Json.decodeFromJsonElement<ExecuteBatch>(message.payload!!)
            val requests = batch.executions.map { exec ->
                val box = inItsLanguage(box(exec.uid))
                FlightRecorder.recordLaunchEntrypoint("launch-batch", box, exec, strand = exec.strand)
                OperationTranscript.recordLaunch(box, exec, strand = exec.strand)
                box to exec
            }
            requests.map { it.second.uid }.distinct().forEach(::clearEditorOutputsForLaunch)

            val kotlinRequests = requests.filter { it.first.ke is KotlinExecution }
            if (kotlinRequests.size == requests.size) {
                KotlinExecution.launchBatch(kotlinRequests, reply)
            } else {
                requests.forEach { (box, exec) ->
                    box.ke.launchAll(box, exec, exec.code, strand = exec.strand, reply)
                }
            }

            Handled.yes
        })

    handlers["continue-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = inItsLanguage(box(exec.uid))

            FlightRecorder.recordLaunchEntrypoint("continue-all", box, exec, strand = "default")
            box.ke.continueAll(box, exec, exec.code, "default", reply)


            Handled.yes
        })

    handlers["launch-fragment"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = inItsLanguage(box(exec.uid))

            clearEditorOutputsForLaunch(exec.uid)
            FlightRecorder.recordLaunchEntrypoint("launch-fragment", box, exec, strand = exec.strand)
            OperationTranscript.recordLaunch(box, exec, strand = exec.strand)
            box.ke.launchAll(box, exec, exec.code, strand = exec.strand, reply)

            Handled.yes
        })

    handlers["stop-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = inItsLanguage(box(exec.uid))

            lprintln(
                "info,execution",
                "stop all has been called for '${box.name}' (${box.id}) strand=${exec.strand}"
            )

            OperationTranscript.recordStop(box)
            stop(box, entrypoint = "stop-all")

            Handled.yes
        })
}
