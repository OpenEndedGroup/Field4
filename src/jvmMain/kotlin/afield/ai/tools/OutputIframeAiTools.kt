package afield.ai.tools

import afield.app.diagnostics.named
import afield.app.protocol.put
import afield.app.server.app
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.audio.synth.*
import afield.document.graph.Box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.tools.docs.screenshot
import afield.ui.output.outputIframe
import afield.ai.tools.BoxTool
import afield.ai.tools.ToolEffect
import afield.ai.tools.aiTool
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> CompletableFuture<T>.awaitIframe(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error != null) continuation.resumeWithException(error)
        else continuation.resume(value)
    }
    continuation.invokeOnCancellation { cancel(true) }
}

/**
 * Opt this box's named output iframe into AI4's tool catalogue for this document.
 *
 * Call after `display(channel, "<iframe ...>")` in the hosting box. Re-running the box replaces
 * its registrations; removing this call or retiring the box removes them. [name] distinguishes
 * several exposed iframes in one document and forms names like `iframe_bob_fill`.
 */
fun Box.exposeOutputIframeTools(channel: String, name: String = channel): List<BoxTool> {
    val label = name.replace(Regex("[^A-Za-z0-9_]"), "_").trim('_')
    require(label.isNotBlank() && label.length <= 32) { "Iframe tool name must contain 1 to 32 letters, digits or underscores" }
    val page = outputIframe(channel)
    val description = "output '$channel' of box '${this.name.ifBlank { id }}'"
    val prefix = "iframe_${label}"

    val inspect = aiTool("${prefix}_inspect") {
        namespace("iframe")
        effects(ToolEffect.READ_DOCUMENT)
        describe("Inspect the live iframe in $description. Empty selector returns the full DOM HTML, page metadata and inline scripts; a CSS selector returns matching elements with HTML, text and bounds.")
        val selector by param<String>("Optional CSS selector; empty reads the whole document.", default = "")
        action { mapOf("json" to page.inspect(selector()).awaitIframe().toString()) }
    }
    val wait = aiTool("${prefix}_wait") {
        namespace("iframe")
        effects(ToolEffect.EXECUTE_CODE)
        describe("Wait until a JavaScript condition is true in the live iframe in $description. Rechecks across navigation.")
        val condition by param<String>("JavaScript expression such as document.querySelector('#message') or window.app?.ready.")
        val timeoutMillis by param<Long>("Maximum wait in milliseconds.", default = 10_000L)
        action {
            page.waitFor(condition(), timeoutMillis()).awaitIframe()
            mapOf("ready" to true, "condition" to condition())
        }
    }
    val navigate = aiTool("${prefix}_navigate") {
        namespace("iframe")
        effects(ToolEffect.CONTROL_RUNTIME, ToolEffect.NETWORK)
        describe("Load a URL into the actual iframe in $description and wait for its load event.")
        val url by param<String>("URL to load in the iframe.")
        action { mapOf("url" to page.navigate(url()).awaitIframe()) }
    }
    val screenshot = aiTool("${prefix}_screenshot") {
        namespace("iframe")
        effects(ToolEffect.CAPTURE_IMAGE)
        describe("Capture the currently visible pixels of the live iframe in $description and return an image to inspect.")
        action { image(page.screenshot().awaitIframe(), "Live iframe in $description, as shown in Electron") }
    }
    val click = aiTool("${prefix}_click") {
        namespace("iframe")
        effects(ToolEffect.EXECUTE_CODE, ToolEffect.CONTROL_RUNTIME)
        describe("Wait for a CSS selector and click its first matching element in the live iframe in $description.")
        val selector by param<String>("CSS selector of the element to click.")
        action { mapOf("json" to page.click(selector()).awaitIframe().toString()) }
    }
    val fill = aiTool("${prefix}_fill") {
        namespace("iframe")
        effects(ToolEffect.EXECUTE_CODE, ToolEffect.CONTROL_RUNTIME)
        describe("Wait for a text input or textarea, fill it, and emit input/change events in the live iframe in $description.")
        val selector by param<String>("CSS selector of the textbox to fill.")
        val text by param<String>("Text to put in the textbox.")
        action { mapOf("json" to page.fill(selector(), text()).awaitIframe().toString()) }
    }
    val evaluate = aiTool("${prefix}_evaluate") {
        namespace("iframe")
        effects(ToolEffect.EXECUTE_CODE, ToolEffect.CONTROL_RUNTIME, ToolEffect.NETWORK)
        describe("Run arbitrary JavaScript in the live iframe in $description and return its JSON-serializable result. Use an async IIFE for asynchronous work.")
        val javascript by param<String>("JavaScript expression or async IIFE.")
        action { mapOf("json" to page.evaluate(javascript()).awaitIframe().toString()) }
    }
    return listOf(inspect, wait, navigate, screenshot, click, fill, evaluate)
}
