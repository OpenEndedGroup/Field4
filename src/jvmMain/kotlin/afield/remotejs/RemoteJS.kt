@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package afield.remotejs

import afield.*
import afield.logger.lprintln
import afield.runtime.TopologyWatcher
import afield.runtime.MainLanguageRuntime
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.openjdk.nashorn.api.tree.Diagnostic
import java.util.*
import java.util.concurrent.ConcurrentHashMap

val knownOpenRemoteClients = ConcurrentHashMap<String, WebSocketSession>()
val remoteClientCapabilities = ConcurrentHashMap<String, JSONObject>()
val returnHandlers = ConcurrentHashMap<String, (JSONObject) -> Boolean>()

var Box.isRemoteJSRoot by Delegation<Boolean?>()
var Box.code_language by Delegation<String>()

var lastBoxExec : Box? = null

fun topologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isRemoteJSRoot == true || it.isMainLanguage("remotejs") },
        runtimeClass = RemoteJSExectution::class,
        newExecution = { _ -> RemoteJSExectution() },
        language = "Javascript",
        customization = "",
        notifyCategory = "remotejs"
    )
}

@RunsAt(plugins)
fun install() {

    rawHandlers["remote"] = mutableSetOf({ text ->

        val o = JSONObject(text)
        if (o.has("address") && o.getString("address") == "initialize") {
            lprintln("info,remotejs", "initializing connection: $text")
            val payload = o.get("payload")
            val id = when (payload) {
                is JSONObject -> payload.optString("id", o.optString("from", UUID.randomUUID().toString()))
                else -> payload.toString()
            }
            knownOpenRemoteClients[id] = thisSession.get()
            if (payload is JSONObject) remoteClientCapabilities[id] = payload
            knownOpenRemoteClients.entries.removeIf { it.value.outgoing.isClosedForSend }
            remoteClientCapabilities.keys.retainAll(knownOpenRemoteClients.keys)
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
                val uid = fn.substringAfter("|", "")
                val box =
                    sessions.values.firstNotNullOfOrNull { session -> session.boxes.values.firstNotNullOfOrNull { if (it.id == uid) it else null } }

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

@RunsAt(early)
fun installRemoteJSMainLanguage() {
    Box.ioPersistentProperties.add("isRemoteJSRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "remotejs",
            label = "Javascript",
            runtimeClass = RemoteJSExectution::class,
            setLegacyRoot = { box, enabled -> box.isRemoteJSRoot = enabled },
            applyTopology = ::topologyNeedsUpdating,
        ),
    )
}

@OptIn(DelicateCoroutinesApi::class)
fun executeRemoteJS(
    _s: String,
    filename: String = "unknown",
    lifecycleName: String? = null,
    launchable: Boolean = false,
    requiresSandbox: Boolean = false,
    lineOffset: Int = 0,
    timeStart: Double? = null,
    timeEnd: Double? = null,
    doASTA: Boolean = true,
    __var0: Any? = null,
    filterSession: (String, WebSocketSession) -> Boolean = { _, _ -> true },
    diagnosticsTo: (Diagnostic) -> Unit = { print("unhandled diagnostic output from asta\n $it") },
    handleTo: ((JSONObject) -> Boolean)? = null
): Boolean = runBlocking {

    var s = _s
    val astaDiagnostics = mutableListOf<Diagnostic>()
    if (doASTA) {
        s = Asta().parseAndReconstruct(s) {
            // Nashorn's parser is useful for recovering a source location, but the
            // target browser is the authority on whether modern Javascript is valid.
            // Hold these diagnostics until the browser also reports a SyntaxError;
            // eagerly emitting them produces two error zones for one typo.
            astaDiagnostics.add(it)
        }
    }

    val u = UUID.randomUUID().toString()

    val q = JSONObject()
    q.put("code", s)
    q.put("returnTo", u)
    q.put("codeName", filename)
    lifecycleName?.let { q.put("lifecycleName", it) }
    q.put("launchable", launchable)
    q.put("lineOffset", lineOffset)
    if (__var0 != null)
        q.put("__var0", __var0)


    if (!requiresSandbox) q.put("noSandbox", true)

    if (timeStart != null) q.put("timeStart", timeStart)
    if (timeEnd != null) q.put("timeEnd", timeEnd)

    knownOpenRemoteClients.entries.removeIf { it.value.outgoing.isClosedForSend }
    remoteClientCapabilities.keys.retainAll(knownOpenRemoteClients.keys)

    val recipients = knownOpenRemoteClients.entries.filter { filterSession(it.key, it.value) }
    if (recipients.isEmpty()) {
        // With no browser there will be no authoritative response. Preserve the old
        // behavior of showing the local parser diagnostic instead of failing silently.
        astaDiagnostics.forEach(diagnosticsTo)
        return@runBlocking false
    }

    if (handleTo != null) {
        returnHandlers[u] = { response ->
            preferAstaLocationForBrowserSyntaxError(response, astaDiagnostics)
            handleTo(response)
        }
    }

    recipients.forEach {
        it.value.send(q.toString())
    }

    true
}

/**
 * A browser SyntaxError from eval often has no sourceURL location. ASTA has already
 * parsed the identical source and normally has the precise line/column, so enrich the
 * one browser response rather than emitting a second, competing error beforehand.
 */
internal fun preferAstaLocationForBrowserSyntaxError(response: JSONObject, diagnostics: List<Diagnostic>) {
    val payload = response.optJSONObject("payload") ?: return
    if (payload.optString("kind") != "ERROR" || payload.optString("name") != "SyntaxError") return
    val diagnostic = diagnostics.firstOrNull() ?: return

    payload.put("message", diagnostic.message)
    payload.put("line", diagnostic.lineNumber.toInt())
    payload.put("column", diagnostic.columnNumber.toInt())
    payload.put("locationSource", "asta")
}

fun executeFragment(b: Box, code: String, lineOffset: Int = 0, onComplete: (() -> Unit)? = null) {

    lastBoxExec = b
    var c = code
    for (n in 0 until lineOffset) c = "\n" + c

    val sent = executeRemoteJS(
        c,
        filename = target(b),
        launchable = false,
        lineOffset = lineOffset,
        doASTA = true,
        requiresSandbox = true,
        diagnosticsTo = {
            b.output.errorProxy(it.message, "code", it.lineNumber.toInt())
        }) {
        lprintln("info,remotejs", "remote code execution returned json object")
        lprintln("debug,remotejs", it.toString())

        val p = it.getJSONObject("payload")
        val kind = p.getString("kind")
        val message = p.optString("message", "")
        val line = if (p.has("line")) p.getInt("line") else lineOffset

        when (kind) {
            "ERROR" -> b.output.errorProxy(message, "code", line)
            "SUCCESS" -> {
                if (message.trim() == "OK") {
                } else b.output.proxy(message.replace("<json>", ""), true, line)
            }

            else -> b.output.proxy(message, true, line)
        }

        val terminal = kind == "ERROR" || (kind == "SUCCESS" && message.trim() == "OK")
        if (terminal) onComplete?.invoke()
        !terminal
    }
    if (!sent) onComplete?.invoke()
}

/**
 * SPIKE — compile a Kotlin snippet to JS and ship it to the head, reusing the JS transport.
 *
 * This is the Kotlin analogue of [executeFragment]: it runs [KotlinToJsCompiler] first, reports any
 * compile diagnostics onto the box (so errors surface locally, at the Kotlin source line, before the
 * browser ever sees the code), then hands the produced module to [executeRemoteJS] with
 * `doASTA = false` — the compiled JS is already valid and needs no operator rewriting. Returns true
 * if a module was produced and sent.
 */
fun compileAndSendKotlin(
    b: Box,
    kotlinSource: String,
    launchable: Boolean = false,
    selectedHead: KotlinJsHeadSelection? = null,
    fragmentContext: KotlinJsFragmentContext? = null,
    lifecycleName: String = kotlinJsLifecycleName(b, "default"),
    onResponse: ((KotlinJsHeadResponse) -> Boolean)? = null,
    sendGate: ((() -> Boolean) -> Boolean)? = null,
): Boolean {
    lastBoxExec = b
    val directSelection = selectedHead ?: KotlinJsHeadProfiles.resolveForBox(b, kotlinSource)
    val selection = fragmentContext
        ?.let { KotlinJsHeadProfiles.resolveForBox(b, it.boxSource) }
        ?: directSelection
    val moduleSuffix = selection.profile.id.lowercase().replace(Regex("[^a-z0-9]+"), "_")
    val moduleName = "box_${b.id.take(8)}_$moduleSuffix"
    val preparedSource = selection.profile.prepareSourceMapped(
        selection.source,
        activeUserLines = fragmentContext?.activeUserLines,
        stateKey = moduleName,
    )
    val result = KotlinToJsCompiler.compile(
        preparedSource.text,
        moduleName = moduleName,
        // The selected profile wrapped user code before appending file-scope externals.
        wrapImplicitMain = false,
    ).remapDiagnostics(preparedSource)

    result.diagnostics.filter { it.severity == "error" }.forEach {
        b.output.errorProxy(it.message, "code", it.line)
    }
    val js = result.js
    if (!result.success || js == null) {
        if (result.diagnostics.none { it.severity == "error" })
            b.output.errorProxy("Kotlin->JS compile failed:\n${result.rawLog.takeLast(600)}", "code", 0)
        return false
    }

    val send = {
        selection.profile.send(b, selection, js, launchable, lifecycleName, onResponse)
    }
    return sendGate?.invoke(send) ?: send()
}

data class KotlinJsFragmentContext(
    val boxSource: String,
    /** One-based editor lines occupied by the selected fragment. */
    val activeUserLines: IntRange,
)

fun launchAll(b: Box, code: String, lineOffset: Int = 0) {
    lastBoxExec = b

    var c = code
    for (n in 0 until lineOffset) c = "\n" + c


    // TODO, error handler!! if asta throws
    executeRemoteJS(c, filename = target(b), launchable = true, doASTA = true, requiresSandbox = true, lineOffset = lineOffset, diagnosticsTo = {
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
        kind != "ERROR" && kind != "RUN-STOP"
    }
}

fun target(box: Box): String {
    val safeName = box.name.replace(Regex("[^A-Za-z0-9_.-]+"), "_")
    return "box_${safeName}|${box.id}"
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

    knownOpenRemoteClients.entries.firstOrNull()?.value?.send(q.toString())
}


class RemoteJSExectution : BoxExecution {

    override val languageName: String = "Javascript"

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        configureOutputProxies(inside, reply)
        val watermark = ++inside.fragmentGeneration
        send(Message("is-running").payload(IsRunning(execute.uid, true, watermark, "__fragment__")))

        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "remotejs")
        else
            notify("executing remote JS fragment in ${knownOpenRemoteClients.size} browser(s)", "remotejs")

        try {
            afield.remotejs.executeFragment(inside, execute.code, execute.line) {
                send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
            }
        } catch (e: Exception) {
            lprintln("error,remotejs", "exception thrown in remotejs (parser?)")
            lprintln("error,remotejs", e.stackTraceToString())
            notify("Syntax Error in your Javascript", "remotejs")
            send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        configureOutputProxies(inside, reply)
        val watermark = ++inside.fragmentGeneration
        send(Message("is-running").payload(IsRunning(execute.uid, true, watermark, "__fragment__")))

        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "remotejs")
        else
            notify("executing remote JS in ${knownOpenRemoteClients.size} browser(s)", "remotejs")

        try {
            afield.remotejs.executeFragment(inside, execute.code, execute.line) {
                send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
            }
        } catch (e: Exception) {
            lprintln("error,remotejs", "exception thrown in remotejs (parser?)")
            lprintln("error,remotejs", e.stackTraceToString())
            notify("Syntax Error in your Javascript", "remotejs")
            send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
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
        if (knownOpenRemoteClients.isEmpty()) {
            notify("Warning! no connected remote clients", "remotejs")
            reply(Message("complete").payload(Completion(
                boundsStart = completionRequest.position,
                boundsEnd = completionRequest.position,
                metadata = emptyList()
            )))
            return
        }

        complete(inside, completionRequest.allCode, completionRequest.position) {
            lprintln("info,remotejs", it)
            reply(Message("complete").payload(it))
        }
    }

}

fun becomeRemote(b: Box) {
    b.ke = RemoteJSExectution()
}
