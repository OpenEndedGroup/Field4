package afield.remotejs

import afield.*
import afield.logger.lprintln
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openjdk.nashorn.api.tree.Diagnostic
import java.util.*

val knownOpenRemoteClients = mutableMapOf<String, WebSocketSession>()
val returnHandlers = mutableMapOf<String, (JSONObject) -> Boolean>()

var Box.isRemoteJSRoot by Delegation<Boolean?>()
var Box.code_language by Delegation<String>()

var lastBoxExec : Box? = null

fun topologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isRemoteJSRoot },
        runtimeClass = RemoteJSExectution::class,
        newExecution = { _ -> RemoteJSExectution() },
        language = "Javascript",
        customization = """this.style.border = "1px dashed #8B4000"; this.style.margin = "-5px" """,
        notifyCategory = "remotejs"
    )
}

@RunsAt(plugins)
fun install() {

    rawHandlers["remote"] = mutableSetOf({ text ->

        val o = JSONObject(text)
        if (o.has("address") && o.getString("address") == "initialize") {
            lprintln("info,remotejs", "initializing connection: $text")
            knownOpenRemoteClients[o.getString("payload")!!] = thisSession.get()
            knownOpenRemoteClients.entries.removeIf { it.value.outgoing.isClosedForSend }
            notify("Connected to ${knownOpenRemoteClients.size} browsers", "remotejs")
        } else if (o.has("address") && returnHandlers.keys.contains(o.getString("address"))) {
            val callAgain = returnHandlers[o.getString("address")]!!(o)
            if (!callAgain) returnHandlers.remove(o.getString("address"))
        } else if (o.has("address") && o.getString("address") == "kv.receive") {
            val source = o.getJSONObject("payload").getString("id")
            lprintln("debug,remotejs", "echoing data to non $source clients ${o.getJSONObject("payload")}")
            executeRemoteJS(
                "__kv.receive('" + o.getJSONObject("payload").getString("name") + "', _field.__var0)",
                __var0 = o.getJSONObject("payload").get("value"),
                filterSession = { id, ws ->
                    id != source
                }, requiresSandbox = false, launchable = false
            )
        } else if (o.has("address") && o.getString("address") == "request.shaderSupport") {
            val v = o.getJSONObject("payload").getString("defaultVertex")
            val f = o.getJSONObject("payload").getString("defaultFragment")
            val on = o.getJSONObject("payload").getString("boxID")

            val box =
                sessions.values.firstNotNullOfOrNull { it.boxes.values.firstNotNullOfOrNull { if (it.id == on) it else null } }

            if (box == null) {
                lprintln("warning,remotejs", "shader upgrade request from non-box ${thisSessionInfo.get().boxes} => $box")
            } else {
                if (box.vertex == null || box.fragment == null) {
                    box.vertex = v
                    box.fragment = f
                }
                box.actions += "moduleHere_reload" to Action("Reload (RemoteJS) Shader", detail="Tells remote browsers to reload the shader associated with this box") {
                    executeRemoteJS(
                        """
                        __reloadShader('${on}', `${box.vertex}`, `${box.fragment}`)
                    """.trimIndent(), requiresSandbox = false, launchable = false, doASTA = false
                    )
                }

                executeRemoteJS(
                    """
                        __reloadShader('${on}', `${box.vertex}`, `${box.fragment}`)
                    """.trimIndent(), requiresSandbox = false, launchable = false, doASTA = false
                )
            }
        } else if (o.has("address") && o.getString("address") == "status.shaderSupport") {
            val v = o.getJSONObject("payload")
            notify("Shader reloaded: '$v'", "remotejs")
        } else if (o.has("address") && o.getString("address") == "(handler error)") {
            //"payload":{"filename":"box_error_checking|ba8d4f90-f57b-4425-ac17-eb13a5485fe2","line":10,"kind":"ERROR","message":"foo is not a function"}
            val v = o.getJSONObject("payload")
            var fn = v.getString("filename")
            if (fn == "unknown") {
                val message = v.getString("message")
                    notify("Event handler got an error '${message}'", "remotejs_error")
                lastBoxExec?.error(message, "code", v.getInt("line"))
            } else {
                val uid = fn.split("|")[1]
                val box =
                    sessions.values.firstNotNullOfOrNull { it.boxes.values.firstNotNullOfOrNull { if (it.id == uid) it else null } }

                val message = v.getString("message")
                if (box == null) {
                    notify("Event handler got an error '${message}'", "remotejs_error")
                } else {
                    notify("Event handler got an error", "remotejs_error")
                    box.error(message, "code", v.getInt("line"))
                }
            }
        } else {
//            lprintln("warning,remotejs", "remote sent a message that I don't understand $o")
            return@mutableSetOf       Handled.no

        }

        Handled.yes
    })

    TopologyWatcher("remotejs") { topologyNeedsUpdating(it) }.start()

}

@OptIn(DelicateCoroutinesApi::class)
fun executeRemoteJS(
    _s: String,
    filename: String = "unknown",
    launchable: Boolean = false,
    requiresSandbox: Boolean = false,
    timeStart: Double? = null,
    timeEnd: Double? = null,
    doASTA: Boolean = true,
    __var0: Any? = null,
    filterSession: (String, WebSocketSession) -> Boolean = { _, _ -> true },
    diagnosticsTo: (Diagnostic) -> Unit = { print("unhandled diagnostic output from asta\n $it") },
    handleTo: ((JSONObject) -> Boolean)? = null
): Boolean = runBlocking {

    var s = _s
    if (doASTA) {
        s = Asta().parseAndReconstruct(s) {
            diagnosticsTo(it)
        }
    }

    val u = UUID.randomUUID().toString()
    if (handleTo != null) returnHandlers[u] = handleTo

    val q = JSONObject()
    q.put("code", s)
    q.put("returnTo", u)
    q.put("codeName", filename)
    q.put("launchable", launchable)
    if (__var0 != null)
        q.put("__var0", __var0)


    if (!requiresSandbox) q.put("noSandbox", true)

    if (timeStart != null) q.put("timeStart", timeStart)
    if (timeEnd != null) q.put("timeEnd", timeEnd)

    knownOpenRemoteClients.entries.removeIf { it.value.outgoing.isClosedForSend }

    knownOpenRemoteClients.forEach {
        if (filterSession(it.key, it.value))
            it.value.send(q.toString())
    }

    knownOpenRemoteClients.size > 0
}

fun executeFragment(b: Box, code: String, lineOffset: Int = 0) {

    lastBoxExec = b
    var c = code
    for (n in 0 until lineOffset) c = "\n" + c

    executeRemoteJS(
        c,
        filename = target(b),
        launchable = false,
        doASTA = true,
        requiresSandbox = true,
        diagnosticsTo = {
            b.output.errorProxy(it.message, "code", it.lineNumber.toInt())
        }) {
        lprintln("info,remotejs", "remote code execution returned json object")
        lprintln("debug,remotejs", it.toString())

        val p = it.getJSONObject("payload")
        val kind = p.getString("kind")
        val message = p.getString("message")
        val line = if (p.has("line")) p.getInt("line") else lineOffset

        when (kind) {
            "ERROR" -> b.output.errorProxy(message, "code", line)
            "SUCCESS" -> {
                if (message.trim() == "OK") {
                } else b.output.proxy(message.replace("<json>", ""), true, line)
            }

            else -> b.output.proxy(message, true, line)
        }


        true
    }
}

fun launchAll(b: Box, code: String, lineOffset: Int = 0) {
    lastBoxExec = b

    var c = code
    for (n in 0 until lineOffset) c = "\n" + c


    // TODO, error handler!! if asta throws
    executeRemoteJS(c, filename = target(b), launchable = true, doASTA = true, requiresSandbox = true, diagnosticsTo = {
        b.output.errorProxy(it.message, "code", it.lineNumber.toInt())
    }) {
        lprintln("info,remotejs", "remote code launch got a json object through its return channel")
        lprintln("debug,remotejs", it.toString())

        val p = it.getJSONObject("payload")
        val kind = p.getString("kind")
        val message = p.getString("message")
        val line = if (p.has("line")) p.getInt("line") else -1

        when (kind) {
            "ERROR" -> b.output.errorProxy(message, "code", line)
            "SUCCESS" -> {
                if (message.trim() == "OK") {
                } else b.output.proxy(message.replace("<json>", ""), true, line)
            }

            "RUN-START" -> {
                send(Message("is-running").payload(IsRunning(b.id, true, -1)))
            }

            "RUN-STOP" -> {
                send(Message("is-running").payload(IsRunning(b.id, false, -1)))
            }

            else -> b.output.proxy(message, true, line)
        }
        true
    }
}

fun target(box: Box): String {
    return "box_" + box.name + "|" + box.id
}

fun stop(b: Box) {
    executeRemoteJS(
        "if (__tasks[\"" + target(b) + "\"]) __tasks[\"" + target(b) + "\"].stop()",
        filename = target(b),
        launchable = true,
        doASTA = true,
        requiresSandbox = true
    ) {
        false
    }
}

fun complete(inside: Box, file: String, position: Int, success: (Completion) -> Unit) = runBlocking {

    lastBoxExec = inside

    val u = UUID.randomUUID().toString()
    returnHandlers.put(u, {
        lprintln("info,remotejs", "return from completion $it")

        var s = it.getJSONObject("payload").getString("message").trim()
        if (s.startsWith("<json>")) s = s.substring("<json>".length);
//
        var j = JSONArray(s)
        lprintln("debug,remotejs", j.toString())
        j.forEach { entry -> lprintln("debug,remotejs", entry.toString()) }

        success(
            Completion(
                boundsStart = position, boundsEnd = position, metadata = j.map {
                    val o = (it as JSONObject)
                    afield.Metadata(
                        icon = "method",
                        tail = "",
                        text = o.getString("add"),
                        displayText = o.getString("title"),
                        documentation = o.getString("from").replace(Regex("<[^>]*>"), ""),
                    )
                }

            ))
//            Completion(position + 1 - extra, position + 1, o.getString("title"));

        false
    })

    val q = JSONObject()
    q.put("__var0", file)
    q.put("code", "window.completeMe($position, _field.__var0, true)")
    q.put("codeName", target(inside))
    q.put("returnTo", u)

    knownOpenRemoteClients.entries.removeIf { it.value.outgoing.isClosedForSend }

    knownOpenRemoteClients.forEach {
        it.value.send(q.toString())
    }
}


class RemoteJSExectution : BoxExecution {

    override val languageName: String = "Javascript"

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        configureOutputProxies(inside, reply)

        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "remotejs")
        else
            notify("executing remote JS fragment in ${knownOpenRemoteClients.size} browser(s)", "remotejs")

        try {
            afield.remotejs.executeFragment(inside, execute.code, execute.line)
        } catch (e: Exception) {
            lprintln("error,remotejs", "exception thrown in remotejs (parser?)")
            lprintln("error,remotejs", e.stackTraceToString())
            notify("Syntax Error in your Javascript", "remotejs")
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        configureOutputProxies(inside, reply)

        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "remotejs")
        else
            notify("executing remote JS in ${knownOpenRemoteClients.size} browser(s)", "remotejs")

        try {
            afield.remotejs.executeFragment(inside, execute.code, execute.line)
        } catch (e: Exception) {
            lprintln("error,remotejs", "exception thrown in remotejs (parser?)")
            lprintln("error,remotejs", e.stackTraceToString())
            notify("Syntax Error in your Javascript", "remotejs")
        }

    }

    override fun launchAll(inside: Box, execute: Execute, code : String, strand: String, reply: (Message) -> Unit) {
        configureOutputProxies(inside, reply)

        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "remotejs")
        else
            notify("launching remote JS in ${knownOpenRemoteClients.size} browser(s)", "remotejs")

        try {
            afield.remotejs.launchAll(inside, execute.code, 0)
        } catch (e: Exception) {
            lprintln("error,remotejs", "exception thrown in remotejs (parser?)")
            lprintln("error,remotejs", e.stackTraceToString())
            notify("Syntax Error in your Javascript", "remotejs")
        }

    }

    override fun stop(inside: Box) {
        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "remotejs")

        afield.remotejs.stop(inside)
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        // For JavaScript, construct a function call: symbolName()
        val functionCall = "${executeSymbol.symbol}()"

        // Create an Execute object for executeFragment
        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = functionCall,
            singleLine = true
        )

        // Execute as a fragment
        executeFragment(inside, exec, reply)
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "remotejs")

        complete(inside, inside.code, completionRequest.position) {
            lprintln("info,remotejs", it)
            reply(Message("complete").payload(it))
        }
    }

}

fun becomeRemote(b: Box) {
    b.ke = RemoteJSExectution()
}
