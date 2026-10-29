package afield.lang.remotejs

import afield.app.diagnostics.logging.lprintln
import afield.app.server.sessions
import afield.document.graph.Box
import afield.ui.output.controlGroup
import org.json.JSONObject

/**
 * Where a message from a browser head lands when it names a box rather than a run.
 *
 * A head reaches Field over the WebSocket Field already has, and every message it sends carries an
 * address. `RemoteJS.kt`'s dispatcher tries those addresses in turn and [handle] is its last
 * claimant, so a `false` from here is what makes a message unrecognised. What reaches it is whatever
 * the head has to say about a box while no reply channel is open: `display`, the `slider` and
 * `checkbox` a box asked for, and output a timer or an event handler printed after the run returned.
 * A Kotlin→JS box arrives here as well, and [routeLateOutput] maps its generated-JavaScript line back
 * to the editor on the way through.
 *
 * None of those messages carries a box, only the code name the head evaluated the source under.
 * [handle] and [routeLateOutput] resolve it through [boxFor], and take that lookup as a parameter so
 * a test can supply a box directly.
 *
 * In the other direction this file writes JavaScript as text for the transport to evaluate in the
 * head: [stopScript] when a box is stopped, [controlScript] when a control drawn here is moved.
 * `dev_docs/REMOTEJS_BOX_PARITY.md` measures the whole surface against a Kotlin box's.
 */

/**
 * The parts of the box contract a RemoteJS box gets from messages its head sends *outside* a run's
 * reply channel: `display(channel, html)`, `slider` / `checkbox`, and output printed after the run
 * finished (a timer, an event handler, an animation frame). See `dev_docs/REMOTEJS_BOX_PARITY.md`.
 *
 * Every box is evaluated in the head with `//# sourceURL=box_<name>|<uid>` ([target]), so the head
 * names the box by that code name, read off the calling stack frame; this file turns it back into a
 * [Box].
 */
object RemoteJsBoxSurface {

    /** The error a run gets when no browser head is connected to run it. */
    const val NO_HEAD = "No RemoteJS head is connected: open one (remoteAr() in a Kotlin box, or /extensions/remote_ar/index.html) and run again"

    /** The box uid inside a [target] code name, or null for a name that is not a box's. */
    fun uidOf(codeName: String?): String? {
        if (codeName == null || !codeName.startsWith("box_")) return null
        return codeName.substringAfter("|", "").ifEmpty { null }
    }

    /**
     * The [Box] whose uid a [target] code name carries, looked for across the boxes of every open
     * session, or null when none of them has it.
     */
    fun boxFor(codeName: String?): Box? {
        val uid = uidOf(codeName) ?: return null
        return sessions.values.firstNotNullOfOrNull { session -> session.boxes.values.firstOrNull { it.id == uid } }
    }

    /**
     * Javascript that stops every task the box started: its `_r` / generator task, named exactly
     * [codeName], and each `launch(fn, name)`, named [codeName] + name. Stopping only the exact
     * name left `launch(...)` loops running after **Stop**.
     */
    fun stopScript(codeName: String): String {
        val quoted = JSONObject.quote(codeName)
        return "Object.keys(__tasks).filter(k => k === $quoted || k.indexOf($quoted) === 0)" +
            ".forEach(k => { const t = __tasks[k]; if (t) t.stop() })"
    }

    /**
     * Handles a head message this file owns; returns false for any other address. [invokeControl]
     * sends a control's new value back to the heads (overridden by tests).
     */
    fun handle(
        o: JSONObject,
        find: (String?) -> Box? = ::boxFor,
        invokeControl: (id: String, value: Any) -> Unit = ::sendControlValue,
    ): Boolean {
        val address = o.optString("address", "")
        val payload = o.optJSONObject("payload") ?: return false
        when (address) {
            "box.display" -> {
                val box = find(payload.optString("codeName")) ?: return true.also { unknown(address, payload) }
                box.display(payload.optString("channel", "main"), payload.optString("html", ""))
            }

            "box.controls" -> {
                val box = find(payload.optString("codeName")) ?: return true.also { unknown(address, payload) }
                applyControls(box, payload.optJSONArray("controls")?.map { it as JSONObject } ?: emptyList(), invokeControl)
            }

            else -> return routeLateOutput(payload, find)
        }
        return true
    }

    /**
     * Output a box printed after its run's reply channel closed. The head tags every `print` (and
     * routed `console.log`) with the box's code name; without this the JVM dropped it as an unknown
     * address.
     */
    fun routeLateOutput(payload: JSONObject, find: (String?) -> Box? = ::boxFor): Boolean {
        if (!payload.has("codeName") || payload.optString("kind") != "SUCCESS") return false
        val box = find(payload.optString("codeName")) ?: return false
        val message = payload.optString("message", "").removePrefix("<json>")
        if (message.trim() == "OK") return true
        // A Kotlin→JS box's routed console.log carries a generated-JavaScript line; map it back.
        box.output.proxy(message, true, kotlinJsEditorLine(box, payload))
        return true
    }

    /**
     * Draws the controls a head asked for on `box`, one [controlGroup] per `channel`, and hands each
     * move to `invokeControl`. A spec whose `kind` is neither `slider` nor `checkbox` draws nothing,
     * and the rest of its group is drawn as usual.
     */
    fun applyControls(box: Box, specs: List<JSONObject>, invokeControl: (String, Any) -> Unit) {
        specs.groupBy { it.optString("channel", "controls") }.forEach { (channel, group) ->
            box.controlGroup(channel) {
                for (spec in group) {
                    val id = spec.getString("id")
                    val label = spec.getString("label")
                    when (spec.optString("kind")) {
                        "slider" -> slider(
                            label, spec.optDouble("min", 0.0), spec.optDouble("max", 1.0),
                            spec.optDouble("step", 0.01), spec.optDouble("value", 0.0),
                        ) { v -> invokeControl(id, v) }

                        "checkbox" -> checkbox(label, spec.optBoolean("value", false)) { v -> invokeControl(id, v) }
                    }
                }
            }
        }
    }

    /**
     * Whether a reply ends a run: an error, a bare "OK", or the run's own value (the head marks
     * that `terminal`). Before 2026-10-24 a run ending in a value never settled its receipt.
     */
    fun isTerminal(payload: JSONObject): Boolean {
        val kind = payload.optString("kind")
        val message = payload.optString("message", "")
        return kind == "ERROR" || (kind == "SUCCESS" && (message.trim() == "OK" || payload.optBoolean("terminal")))
    }

    /** Javascript that hands [value] to the head-side callback of control [id]. */
    fun controlScript(id: String, value: Any): String =
        "__fieldControl(${JSONObject.quote(id)}, ${if (value is Boolean || value is Number) value.toString() else JSONObject.quote(value.toString())})"

    private fun sendControlValue(id: String, value: Any) {
        executeRemoteJS(controlScript(id, value), requiresSandbox = false, launchable = false, doASTA = false)
    }

    private fun unknown(address: String, payload: JSONObject) {
        lprintln("warning,remotejs", "$address for a box this server does not have: ${payload.optString("codeName")}")
    }
}
