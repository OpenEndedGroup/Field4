package afield.server

import afield.Execute
import afield.ExecuteSymbol
import afield.Handled
import afield.Message
import afield.RunsAt
import afield.Server.Companion.stop
import afield.Stop_box
import afield.box
import afield.handlers
import afield.inMain
import afield.installHandlers
import afield.logger.lprintln
import afield.makeKIHere
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement


@RunsAt(installHandlers)
fun executing() {

    handlers["stop-box"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Stop_box>(message.payload!!)
            val box = box(exec.uid)

            stop(box)

            Handled.yes
        })

    handlers["execute-fragment"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->
            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)
            box.ke.executeFragment(box, exec, reply)
            Handled.yes
        })

    handlers["execute-symbol"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->
            val exec = Json.decodeFromJsonElement<ExecuteSymbol>(message.payload!!)
            val box = box(exec.uid)
            box.ke.executeSymbol(box, exec, reply)
            Handled.yes
        })

    handlers["execute-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)

            // experimental

            inMain {
                box.ki = makeKIHere(box)
                box.properties["_preamble"] = "" // remove record of previous preamble line execs
                box.ke.executeAll(box, exec, reply)
            }

            Handled.yes
        })

    handlers["launch-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)

            box.ke.launchAll(box, exec, exec.code, "default", reply)


            Handled.yes
        })

    handlers["launch-fragment"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)

            box.ke.launchAll(box, exec, exec.code, strand = exec.strand, reply)

            Handled.yes
        })

    handlers["stop-all"] = mutableSetOf(
        { message, reply: (Message) -> Unit ->

            val exec = Json.decodeFromJsonElement<Execute>(message.payload!!)
            val box = box(exec.uid)

            lprintln(" stop all has been called ")

            stop(box)

            Handled.yes
        })
}
