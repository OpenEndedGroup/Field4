package afield.app.server

import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.app.server.Server.Companion.stop
import afield.app.diagnostics.logging.lprintln
import afield.runtime.kotlin.SpareEngines
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    val requested = System.nanoTime()
    val timedReply = verdictTimed("execute.all", requested, box.id) { message ->
        // The run has its verdict: build the engine the next one will take (see SpareEngines).
        if (message.name == "execution-verdict" && box.ke is KotlinExecution) SpareEngines.prepare(box)
        reply(message)
    }
    inMain {
        LatencySpans.record("execute.queue", requested, box = box.id)
        // Before the engine is replaced, and before this thread's session is set (this can
        // suspend): a box blocked in a propUp pull is still evaluating in that engine.
        ReactiveRuns.awaitNotBlocked(box)
        val previousSession = thisSession.get()
        val previousSessionInfo = thisSessionInfo.get()
        try {
            if (originSession == null) thisSession.remove() else thisSession.set(originSession)
            if (originSessionInfo == null) thisSessionInfo.remove() else thisSessionInfo.set(originSessionInfo)
            FlightRecorder.recordExecuteEntrypoint("execute-all", box, exec)
            // experimental
            box.properties["_preamble"] = "" // remove record of previous preamble line execs
            // (A spare that already ran the preamble records it again here.)
            if (box.ke is KotlinExecution)
                box.replaceKi(LatencySpans.time("engine.reset", box = box.id) { makeKIHere(box, reason = "execute-all handler reset") })
            box.properties["_introduction"] = "" // ditto for introduction
            box.properties.remove("_attic")
            box.ke.executeAll(box, exec, timedReply)
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
    box.ke.launchAll(box, exec, exec.code, "default", verdictTimed("launch.all", System.nanoTime(), box.id, reply))
}

/** [reply], recording a [LatencySpans] span named [name] from [start] to the first `execution-verdict` through it. */
private fun verdictTimed(name: String, start: Long, box: String, reply: (Message) -> Unit): (Message) -> Unit {
    val done = AtomicBoolean(false)
    return { message ->
        if (message.name == "execution-verdict" && done.compareAndSet(false, true)) {
            val ok = (message.payload as? JsonObject)?.get("success")?.toString()
            LatencySpans.record(name, start, box = box, detail = "success=$ok")
        }
        reply(message)
    }
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
            box.ke.executeFragment(box, exec, verdictTimed("execute.fragment", System.nanoTime(), box.id, reply))
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

    // Dispatched on the websocket's coroutine, not the Runloop, so it works while box code has the
    // Runloop blocked: the box's next loop back-edge or call throws (afield.runtime.lifecycle.BoxInterrupt).
    handlers["interrupt-box"] = mutableSetOf(
        { _, _: (Message) -> Unit ->
            val asked = afield.runtime.lifecycle.BoxInterrupt.request("Interrupt Running Box command")
            lprintln("info,execution,interrupt", "interrupt-box requested=$asked")
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
