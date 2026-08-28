package afield.four.browser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture

/** Current observable state of an offscreen Chromium tab. */
data class PuppetBrowserState(
    val url: String = "about:blank",
    val title: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val audible: Boolean = false,
    val focused: Boolean = false,
    val unresponsive: Boolean = false,
    val crashed: Boolean = false,
    val cursor: String = "default",
    val navigationGeneration: Long = 0,
)

/** One browser or protocol event. Payloads deliberately retain Electron/CDP fields verbatim. */
data class PuppetBrowserEvent(
    val sequence: Long,
    val name: String,
    val payload: JsonObject,
)

enum class BrowserJavascriptWorld { MAIN, ISOLATED }
enum class BrowserJavascriptReturnMode { VALUE, HANDLE }

/** A JavaScript result, either copied by value or retained in Chromium as [objectId]. */
data class BrowserJavascriptResult(
    val type: String,
    val value: JsonElement = JsonNull,
    val description: String? = null,
    val unserializableValue: String? = null,
    val objectId: String? = null,
)

class PuppetBrowserCommandException(
    message: String,
    val command: String? = null,
    val details: JsonObject? = null,
) : RuntimeException(message)

enum class BrowserModifier(val cdpBit: Int) {
    ALT(1),
    CONTROL(2),
    META(4),
    SHIFT(8),
}

enum class BrowserMouseButton(val cdpName: String, val cdpBit: Int) {
    NONE("none", 0),
    LEFT("left", 1),
    RIGHT("right", 2),
    MIDDLE("middle", 4),
    BACK("back", 8),
    FORWARD("forward", 16),
}

enum class BrowserPointerType(val cdpName: String) { MOUSE("mouse"), PEN("pen") }
enum class BrowserPointerEventType { MOVE, DOWN, UP, WHEEL }

/** Browser pointer coordinates are CSS pixels, origin at the viewport's top-left. */
data class BrowserPointerEvent(
    val type: BrowserPointerEventType,
    val x: Double,
    val y: Double,
    val button: BrowserMouseButton = BrowserMouseButton.NONE,
    val buttons: Int = 0,
    val modifiers: Set<BrowserModifier> = emptySet(),
    val clickCount: Int = 0,
    val deltaX: Double = 0.0,
    val deltaY: Double = 0.0,
    val pointerType: BrowserPointerType = BrowserPointerType.MOUSE,
    val pressure: Double = 0.0,
    val tiltX: Double = 0.0,
    val tiltY: Double = 0.0,
    val twist: Int = 0,
)

enum class BrowserKeyEventType(val cdpName: String) {
    RAW_KEY_DOWN("rawKeyDown"),
    KEY_DOWN("keyDown"),
    KEY_UP("keyUp"),
    CHAR("char"),
}

data class BrowserKeyEvent(
    val type: BrowserKeyEventType,
    val key: String,
    val code: String = key,
    val modifiers: Set<BrowserModifier> = emptySet(),
    val windowsVirtualKeyCode: Int = 0,
    val nativeVirtualKeyCode: Int = windowsVirtualKeyCode,
    val autoRepeat: Boolean = false,
    val keypad: Boolean = false,
    val text: String? = null,
    val unmodifiedText: String? = text,
)

enum class BrowserTouchEventType(val cdpName: String) {
    START("touchStart"),
    MOVE("touchMove"),
    END("touchEnd"),
    CANCEL("touchCancel"),
}

data class BrowserTouchPoint(
    val id: Int,
    val x: Double,
    val y: Double,
    val radiusX: Double = 1.0,
    val radiusY: Double = 1.0,
    val rotationAngle: Double = 0.0,
    val force: Double = 1.0,
)

/**
 * High-level control surface for a Chromium tab whose pixels are an [ExternalBrowserSurface].
 * Fire-and-forget input methods retain TCP order; commands return futures completed by Electron.
 */
interface PuppetBrowser : ExternalBrowserSurface {
    val state: PuppetBrowserState

    fun addEventListener(listener: (PuppetBrowserEvent) -> Unit): () -> Unit

    fun navigate(url: String): CompletableFuture<PuppetBrowserState>
    fun goBack(): CompletableFuture<PuppetBrowserState>
    fun goForward(): CompletableFuture<PuppetBrowserState>
    fun reload(ignoreCache: Boolean = false): CompletableFuture<PuppetBrowserState>
    fun stop(): CompletableFuture<PuppetBrowserState>
    fun focus(): CompletableFuture<PuppetBrowserState>
    fun blur(): CompletableFuture<PuppetBrowserState>

    fun pointer(event: BrowserPointerEvent)
    fun key(event: BrowserKeyEvent)
    fun insertText(text: String)
    fun setComposition(
        text: String,
        selectionStart: Int = text.length,
        selectionEnd: Int = selectionStart,
        replacementStart: Int? = null,
        replacementEnd: Int? = null,
    )
    fun touch(type: BrowserTouchEventType, points: List<BrowserTouchPoint>, modifiers: Set<BrowserModifier> = emptySet())
    /** Completes after every previously posted pointer, keyboard, IME, and touch event was dispatched. */
    fun flushInput(): CompletableFuture<Unit>

    fun evaluate(
        javascript: String,
        world: BrowserJavascriptWorld = BrowserJavascriptWorld.MAIN,
        returnMode: BrowserJavascriptReturnMode = BrowserJavascriptReturnMode.VALUE,
        userGesture: Boolean = false,
        timeoutMillis: Long = 10_000,
    ): CompletableFuture<BrowserJavascriptResult>

    fun releaseJavascriptObject(objectId: String): CompletableFuture<Unit>
    fun cdp(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        sessionId: String? = null,
    ): CompletableFuture<JsonObject>
    fun forwardCdpEvents(enabled: Boolean): CompletableFuture<Unit>

    /** Register an allowlisted request callable as `window.fieldPuppet.request(name, value)`. */
    fun onPageRequest(
        name: String,
        handler: (JsonElement) -> CompletableFuture<JsonElement>,
    ): () -> Unit
}

internal fun Set<BrowserModifier>.cdpBits(): Int = fold(0) { bits, modifier -> bits or modifier.cdpBit }

internal fun BrowserPointerEvent.toCdpJson(): JsonObject = buildJsonObject {
    put(
        "type",
        when (type) {
            BrowserPointerEventType.MOVE -> "mouseMoved"
            BrowserPointerEventType.DOWN -> "mousePressed"
            BrowserPointerEventType.UP -> "mouseReleased"
            BrowserPointerEventType.WHEEL -> "mouseWheel"
        }
    )
    put("x", x)
    put("y", y)
    put("button", button.cdpName)
    put("buttons", buttons)
    put("modifiers", modifiers.cdpBits())
    put("clickCount", clickCount)
    put("pointerType", pointerType.cdpName)
    put("force", pressure)
    put("tiltX", tiltX)
    put("tiltY", tiltY)
    put("twist", twist)
    if (type == BrowserPointerEventType.WHEEL) {
        put("deltaX", deltaX)
        put("deltaY", deltaY)
    }
}

internal fun BrowserKeyEvent.toCdpJson(): JsonObject = buildJsonObject {
    put("type", type.cdpName)
    put("key", key)
    put("code", code)
    put("modifiers", modifiers.cdpBits())
    put("windowsVirtualKeyCode", windowsVirtualKeyCode)
    put("nativeVirtualKeyCode", nativeVirtualKeyCode)
    put("autoRepeat", autoRepeat)
    put("isKeypad", keypad)
    text?.let { put("text", it) }
    unmodifiedText?.let { put("unmodifiedText", it) }
}

internal fun touchToCdpJson(
    type: BrowserTouchEventType,
    points: List<BrowserTouchPoint>,
    modifiers: Set<BrowserModifier>,
): JsonObject = buildJsonObject {
    put("type", type.cdpName)
    put("modifiers", modifiers.cdpBits())
    put("touchPoints", buildJsonArray {
        points.forEach { point ->
            add(buildJsonObject {
                put("id", point.id)
                put("x", point.x)
                put("y", point.y)
                put("radiusX", point.radiusX)
                put("radiusY", point.radiusY)
                put("rotationAngle", point.rotationAngle)
                put("force", point.force)
            })
        }
    })
}

internal fun puppetBrowserState(json: JsonObject): PuppetBrowserState = PuppetBrowserState(
    url = json.stringOrNull("url") ?: "about:blank",
    title = json.stringOrNull("title") ?: "",
    loading = json.boolean("loading"),
    canGoBack = json.boolean("canGoBack"),
    canGoForward = json.boolean("canGoForward"),
    audible = json.boolean("audible"),
    focused = json.boolean("focused"),
    unresponsive = json.boolean("unresponsive"),
    crashed = json.boolean("crashed"),
    cursor = json.stringOrNull("cursor") ?: "default",
    navigationGeneration = json["navigationGeneration"]?.jsonPrimitive?.longOrNull ?: 0,
)

internal fun browserJavascriptResult(json: JsonObject): BrowserJavascriptResult = BrowserJavascriptResult(
    type = json.stringOrNull("type") ?: "undefined",
    value = json["value"] ?: JsonNull,
    description = json.stringOrNull("description"),
    unserializableValue = json.stringOrNull("unserializableValue"),
    objectId = json.stringOrNull("objectId"),
)

internal fun JsonObject.objectOrNull(name: String): JsonObject? = this[name]?.let {
    if (it is JsonObject) it else null
}

internal fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.boolean(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: false
