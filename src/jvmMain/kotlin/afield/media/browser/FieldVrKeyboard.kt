package afield.media.browser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture

/** A focus-preserving keyboard inside the offscreen IDE; keys go through Chromium's input path. */
class FieldVrKeyboard(
    private val browser: PuppetBrowserControl,
    private val recenter: () -> Unit,
    private val focusKeyboard: () -> Unit = {},
) : AutoCloseable {
    private val removeRequest = browser.onPageRequest("field-vr-input") { value ->
        val message = value.jsonObject
        when (message["action"]?.jsonPrimitive?.content) {
            "text" -> browser.insertText(message["text"]!!.jsonPrimitive.content.take(4096))
            "key" -> press(message["key"]!!.jsonPrimitive.content,
                message["shift"]?.jsonPrimitive?.content == "true",
                message["control"]?.jsonPrimitive?.content == "true",
                message["alt"]?.jsonPrimitive?.content == "true")
            "recenter" -> recenter()
            "keyboard-focus" -> focusKeyboard()
            else -> throw IllegalArgumentException("Unknown VR keyboard action")
        }
        browser.flushInput().thenApply<JsonElement> { JsonNull }
    }

    /** Idempotent; also called after navigation to a different document. */
    fun install(): CompletableFuture<BrowserJavascriptResult> = browser.evaluate(script)

    fun reportInputStatus(status: String): CompletableFuture<BrowserJavascriptResult> = browser.evaluate(
        "(() => { const node = document.getElementById('field-vr-tools')?.shadowRoot?.getElementById('input-status'); " +
            "if (node) node.textContent = ${JsonPrimitive(status)}; return !!node; })()")

    fun press(key: String, shift: Boolean = false, control: Boolean = false, alt: Boolean = false) {
        val descriptor = keys[key] ?: throw IllegalArgumentException("Unsupported VR key: $key")
        val modifiers = buildSet {
            if (shift) add(BrowserModifier.SHIFT)
            if (control) add(BrowserModifier.CONTROL)
            if (alt) add(BrowserModifier.ALT)
        }
        browser.key(BrowserKeyEvent(BrowserKeyEventType.RAW_KEY_DOWN, descriptor.first, descriptor.second,
            modifiers = modifiers, windowsVirtualKeyCode = descriptor.third))
        browser.key(BrowserKeyEvent(BrowserKeyEventType.KEY_UP, descriptor.first, descriptor.second,
            modifiers = modifiers, windowsVirtualKeyCode = descriptor.third))
    }

    override fun close() { removeRequest() }

    companion object {
        internal val script: String by lazy {
            checkNotNull(FieldVrKeyboard::class.java.getResourceAsStream("field-vr-keyboard.js"))
                .bufferedReader().use { it.readText() }
        }
        private val keys = mapOf(
            "Backspace" to Triple("Backspace", "Backspace", 8),
            "Delete" to Triple("Delete", "Delete", 46),
            "Enter" to Triple("Enter", "Enter", 13),
            "Tab" to Triple("Tab", "Tab", 9),
            "Escape" to Triple("Escape", "Escape", 27),
            "ArrowLeft" to Triple("ArrowLeft", "ArrowLeft", 37),
            "ArrowUp" to Triple("ArrowUp", "ArrowUp", 38),
            "ArrowRight" to Triple("ArrowRight", "ArrowRight", 39),
            "ArrowDown" to Triple("ArrowDown", "ArrowDown", 40),
            "Home" to Triple("Home", "Home", 36),
            "End" to Triple("End", "End", 35),
            "F1" to Triple("F1", "F1", 112),
            "a" to Triple("a", "KeyA", 65),
            "c" to Triple("c", "KeyC", 67),
            "v" to Triple("v", "KeyV", 86),
            "x" to Triple("x", "KeyX", 88),
            "z" to Triple("z", "KeyZ", 90),
        )
    }
}
