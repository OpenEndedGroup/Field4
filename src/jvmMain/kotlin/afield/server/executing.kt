package afield.server

import afield.*
import afield.Server.Companion.stop
import afield.logger.lprintln
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private fun clearEditorOutputsForLaunch(uid: String) {
    send(Message("clear-editor-outputs").payload(ClearEditorOutputs(uid)))
}

@RunsAt(installHandlers)
fun executing() {

    handlers["stop-box"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Stop_box>(message.payload!!)
            val box = box(exec.uid)

            OperationTranscript.recordStop(box)
            stop(box, entrypoint = "stop-box")

            Handled.yes
        })

    handlers["execute-fragment"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->
            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)
            FlightRecorder.recordExecuteEntrypoint("execute-fragment", box, exec)
            OperationTranscript.recordExecuteFragment(box, exec)
            box.ke.executeFragment(box, exec, reply)
            Handled.yes
        })

    handlers["execute-symbol"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->
            val exec = Json.decodeFromJsonElement<ExecuteSymbol>(message.payload!!)
            val box = box(exec.uid)
            FlightRecorder.recordExecuteSymbolEntrypoint("execute-symbol", box, exec)
            box.ke.executeSymbol(box, exec, reply)
            Handled.yes
        })

    handlers["execute-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)
            val originSession = thisSession.get()
            val originSessionInfo = thisSessionInfo.get()


            inMain {
                val previousSession = thisSession.get()
                val previousSessionInfo = thisSessionInfo.get()
                try {
                    thisSession.set(originSession)
                    thisSessionInfo.set(originSessionInfo)
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

            Handled.yes
        })

    handlers["launch-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)

            clearEditorOutputsForLaunch(exec.uid)
            FlightRecorder.recordLaunchEntrypoint("launch-all", box, exec, strand = "default")
            OperationTranscript.recordLaunch(box, exec, strand = "default")
            box.ke.launchAll(box, exec, exec.code, "default", reply)


            Handled.yes
        })

    handlers["launch-batch"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val batch = Json.decodeFromJsonElement<ExecuteBatch>(message.payload!!)
            val requests = batch.executions.map { exec ->
                val box = box(exec.uid)
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
            val box = box(exec.uid)

            FlightRecorder.recordLaunchEntrypoint("continue-all", box, exec, strand = "default")
            box.ke.continueAll(box, exec, exec.code, "default", reply)


            Handled.yes
        })

    handlers["launch-fragment"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)

            clearEditorOutputsForLaunch(exec.uid)
            FlightRecorder.recordLaunchEntrypoint("launch-fragment", box, exec, strand = exec.strand)
            OperationTranscript.recordLaunch(box, exec, strand = exec.strand)
            box.ke.launchAll(box, exec, exec.code, strand = exec.strand, reply)

            Handled.yes
        })

    handlers["stop-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)

            lprintln(
                "info,execution",
                "stop all has been called for '${box.name}' (${box.id}) strand=${exec.strand}"
            )

            OperationTranscript.recordStop(box)
            stop(box, entrypoint = "stop-all")

            Handled.yes
        })
}
