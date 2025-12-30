package afield

import afield.logger.lprintln
import afield.macros.Insert
import afield.server.Inspector
import afield.server.LogStream
import org.joml.Vector2f
import org.joml.Vector4f
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * printing inside a for loop just gives the last output?
 */
interface HasOutput {
    fun print(message: Any?)
    fun println(message: Any?)
    fun display(message: Any?)
    fun display(channel: String, message: Any?)

    // an error of null,,-1 means 'clear errors'
    fun error(message: Any?, property: String = "code", line: Int = -1)
}

class DefaultOutput : HasOutput {
    var proxy: (Any?, Boolean, Number) -> Unit = { _, _, _ -> }
    var displayProxy: (String, Any?) -> Unit = { _, _ -> }
    var errorProxy: (Any?, String, Int) -> Unit = { _, _, _ -> }

    override fun print(message: Any?) {
        proxy(message, false, -1)
    }

    override fun display(channel: String, message: Any?) {
        displayProxy(channel, message)
    }

    override fun display(message: Any?) {
        displayProxy("output", message)
    }

    override fun println(message: Any?) {
        proxy(message, true, -1)
    }

    override fun error(message: Any?, property: String, line: Int) {
        errorProxy(message, property, line)
    }
}


fun Box._printHere(line: Int, vararg a: Any) {
    output.proxy(a.joinToString("\n"), false, line)
}

fun Box.macro_printHere(contents: List<String>): Insert? {
    var args = contents.joinToString(",")
    if (args.startsWith("(")) args = args.substring(1)
    if (args.endsWith(")")) args = args.substring(0, args.length - 1)

    return Insert(
        here = "_printHere(__line__+1, $args)"
    )
}

fun Box._printlnHere(line: Int, vararg a: Any) {
    output.proxy(a.joinToString("\n"), true, line)
}

fun Box.marco_printlnHere(contents: List<String>): Insert? {
    var args = contents.joinToString(",")
    if (args.startsWith("(")) args = args.substring(1)
    if (args.endsWith(")")) args = args.substring(0, args.length - 1)

    return Insert(
        here = "_printlnHere(__line__+1, $args)"
    )
}

fun Box.tree(channel: String = "debug", a : Any?) {
    val aa = a ?: "null"
    val ln = Inspector.linkFor(aa, "/tree")
    display(channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0'>
""")
}

fun Box.column(channel: String = "debug", a : Any?) {
    val aa = a ?: "null"
    val ln = Inspector.linkFor(aa, "/browse")
    display(channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0'>
""")
}

fun Box.logs(
    channel: String = "internal logging",
    filter: LogStream.Filter = LogStream.Filter(),
    latestOnly: Boolean = false,
    subId: String = "logs-${UUID.randomUUID()}"
) {
    val effectiveFilter = if (latestOnly) filter.copy(limit = 1) else filter
    fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8)
    val qs = buildString {
        append("?subId=${enc(subId)}")
        effectiveFilter.channels?.takeIf { it.isNotEmpty() }?.let { append("&channels=${enc(it.joinToString(","))}") }
        effectiveFilter.messageContains?.let { append("&messageContains=${enc(it)}") }
        effectiveFilter.messageRegex?.let { append("&messageRegex=${enc(it)}") }
        effectiveFilter.blockId?.let { append("&blockId=${enc(it)}") }
        if (effectiveFilter.includeBlockChildren) append("&includeBlockChildren=true")
        effectiveFilter.sinceEpochMs?.let { append("&sinceEpochMs=$it") }
        effectiveFilter.afterSeq?.let { append("&afterSeq=$it") }
        append("&limit=${effectiveFilter.limit}")
        append("&tail=${effectiveFilter.tail}")
        if (latestOnly) append("&latestOnly=true")
    }
    display(channel, """
<iframe src='/logs${qs}' style='border:none;padding:0;margin:0;width:100%;height:260px'>
""")
}

// Simple DSL for log filters: Box.logs { matching = "foo"; cat = "info" }
class LogFilterBuilder {
    var channels: MutableList<String> = mutableListOf()
    var cat: String? = null           // single channel token
    var matching: String? = null      // substring match
    var regex: String? = null         // regex match
    var blockId: String? = null
    var includeBlockChildren: Boolean = false
    var sinceEpochMs: Long? = null
    var afterSeq: Long? = null
    var limit: Int = 500
    var tail: Boolean = true

    fun channel(vararg tokens: String) { channels.addAll(tokens) }

    internal fun build(latestOnly: Boolean): LogStream.Filter {
        val channelList = (cat?.let { listOf(it) } ?: emptyList()) + channels
        return LogStream.Filter(
            channels = channelList.ifEmpty { null },
            messageContains = matching,
            messageRegex = regex,
            blockId = blockId,
            includeBlockChildren = includeBlockChildren,
            sinceEpochMs = sinceEpochMs,
            afterSeq = afterSeq,
            limit = if (latestOnly) 1 else limit,
            tail = tail
        )
    }
}

fun Box.logs(
    channel: String = "debug",
    latestOnly: Boolean = false,
    subId: String = "logs-${UUID.randomUUID()}",
    build: LogFilterBuilder.() -> Unit
) {
    val filter = LogFilterBuilder().apply(build).build(latestOnly)
    logs(channel = channel, filter = filter, latestOnly = latestOnly, subId = subId)
}

// Slider support
data class SliderConfig(
    val label: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val initialValue: Double,
    val onUpdate: (Double) -> Unit
)

// Global registry for slider callbacks
private val sliderCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Double) -> Unit>()

internal fun registerSliderCallback(sliderId: String, callback: (Double) -> Unit) {
    sliderCallbacks[sliderId] = callback
}

internal fun getSliderCallback(sliderId: String): ((Double) -> Unit)? {
    return sliderCallbacks[sliderId]
}

fun Box.slider(
    label: String,
    min: Double = 0.0,
    max: Double = 1.0,
    step: Double = 0.01,
    initialValue: Double = (min + max) / 2,
    channel: String = "controls",
    onUpdate: (Double) -> Unit
) {
    val sliderId = "slider-${UUID.randomUUID()}"
    registerSliderCallback(sliderId, onUpdate)

    val html = buildSliderHtml(sliderId, label, min, max, step, initialValue)
    display(channel, html)
}

fun Box.sliders(
    channel: String = "controls",
    vararg configs: Pair<String, SliderConfig>
) {
    val html = buildString {
        append("<div class='slider-group'>")
        configs.forEach { (id, config) ->
            val sliderId = "slider-$id-${UUID.randomUUID()}"
            registerSliderCallback(sliderId, config.onUpdate)
            append(buildSliderHtml(sliderId, config.label, config.min, config.max, config.step, config.initialValue))
        }
        append("</div>")
    }
    display(channel, html)
}

private fun buildSliderHtml(
    sliderId: String,
    label: String,
    min: Double,
    max: Double,
    step: Double,
    value: Double
): String {
    val decimalPlaces = calculateDecimalPlaces(step)
    return """
        <div class="slider-control" style="margin: 8px 0; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 4px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
                <label for="$sliderId" style="font-size: 11px; color: #ddd; user-select: none;">$label</label>
                <span id="${sliderId}-value" style="font-size: 10px; color: #aaa; font-family: monospace; min-width: 60px; text-align: right;">$value</span>
            </div>
            <input 
                type="range" 
                id="$sliderId" 
                min="$min" 
                max="$max" 
                step="$step" 
                value="$value"
                data-decimal-places="$decimalPlaces"
                style="width: 100%; cursor: pointer; accent-color: #4a9eff;"
                oninput="handleSliderInput(this)"
            />
        </div>
    """.trimIndent()
}

private fun calculateDecimalPlaces(step: Double): Int {
    if (step >= 1.0) return 0

    // Handle very small numbers and scientific notation
    if (step == 0.0) return 0

    // Use BigDecimal for accurate decimal place calculation
    val bd = java.math.BigDecimal.valueOf(step)
    return bd.stripTrailingZeros().scale().coerceAtLeast(0)
}

// Additional control types

// 2D Vector control (using JOML Vector2f)
private val vec2Callbacks = java.util.concurrent.ConcurrentHashMap<String, (Vector2f) -> Unit>()

internal fun registerVec2Callback(controlId: String, callback: (Vector2f) -> Unit) {
    vec2Callbacks[controlId] = callback
}

internal fun getVec2Callback(controlId: String): ((Vector2f) -> Unit)? {
    return vec2Callbacks[controlId]
}

fun Box.vec2Control(
    label: String,
    initialValue: Vector2f = Vector2f(0.0f, 0.0f),
    xRange: Pair<Double, Double> = -100.0 to 100.0,
    yRange: Pair<Double, Double> = -100.0 to 100.0,
    step: Double = 1.0,
    channel: String = "controls",
    onUpdate: (Vector2f) -> Unit
) {
    val controlId = "vec2-${UUID.randomUUID()}"
    registerVec2Callback(controlId, onUpdate)

    val decimalPlaces = calculateDecimalPlaces(step)
    val html = """
        <div class="vec2-control" style="margin: 8px 0; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 4px;">
            <div style="font-size: 11px; color: #ddd; margin-bottom: 8px;">$label</div>
            <div style="margin-bottom: 4px;">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px;">
                    <label style="font-size: 10px; color: #aaa;">X:</label>
                    <span id="${controlId}-x-value" style="font-size: 10px; color: #aaa; font-family: monospace; min-width: 50px; text-align: right;">${initialValue.x}</span>
                </div>
                <input type="range" id="${controlId}-x" min="${xRange.first}" max="${xRange.second}" step="$step" value="${initialValue.x}"
                    data-control-id="$controlId" data-axis="x" data-decimal-places="$decimalPlaces"
                    style="width: 100%; cursor: pointer; accent-color: #4a9eff;"
                    oninput="handleVec2Input(this)" />
            </div>
            <div>
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px;">
                    <label style="font-size: 10px; color: #aaa;">Y:</label>
                    <span id="${controlId}-y-value" style="font-size: 10px; color: #aaa; font-family: monospace; min-width: 50px; text-align: right;">${initialValue.y}</span>
                </div>
                <input type="range" id="${controlId}-y" min="${yRange.first}" max="${yRange.second}" step="$step" value="${initialValue.y}"
                    data-control-id="$controlId" data-axis="y" data-decimal-places="$decimalPlaces"
                    style="width: 100%; cursor: pointer; accent-color: #4a9eff;"
                    oninput="handleVec2Input(this)" />
            </div>
        </div>
    """.trimIndent()
    display(channel, html)
}

// Checkbox control
private val checkboxCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

internal fun registerCheckboxCallback(controlId: String, callback: (Boolean) -> Unit) {
    checkboxCallbacks[controlId] = callback
}

internal fun getCheckboxCallback(controlId: String): ((Boolean) -> Unit)? {
    return checkboxCallbacks[controlId]
}

fun Box.checkbox(
    label: String,
    initialValue: Boolean = false,
    channel: String = "controls",
    onUpdate: (Boolean) -> Unit
) {
    val controlId = "checkbox-${UUID.randomUUID()}"
    registerCheckboxCallback(controlId, onUpdate)

    val checkedAttr = if (initialValue) "checked" else ""
    val html = """
        <div class="checkbox-control" style="margin: 8px 0; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 4px;">
            <label style="display: flex; align-items: center; cursor: pointer; user-select: none;">
                <input type="checkbox" id="$controlId" $checkedAttr
                    style="margin-right: 8px; cursor: pointer; width: 16px; height: 16px;"
                    onchange="handleCheckboxInput(this)" />
                <span style="font-size: 11px; color: #ddd;">$label</span>
            </label>
        </div>
    """.trimIndent()
    display(channel, html)
}

// String input control
private val stringCallbacks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

internal fun registerStringCallback(controlId: String, callback: (String) -> Unit) {
    stringCallbacks[controlId] = callback
}

internal fun getStringCallback(controlId: String): ((String) -> Unit)? {
    return stringCallbacks[controlId]
}

fun Box.stringInput(
    label: String,
    initialValue: String = "",
    channel: String = "controls",
    onUpdate: (String) -> Unit
) {
    val controlId = "string-${UUID.randomUUID()}"
    registerStringCallback(controlId, onUpdate)

    val escapedValue = initialValue.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    val html = """
        <div class="string-control" style="margin: 8px 0; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 4px;">
            <label style="display: block; font-size: 11px; color: #ddd; margin-bottom: 4px;">$label</label>
            <input type="text" id="$controlId" value="$escapedValue"
                style="width: 100%; padding: 4px 8px; background: rgba(0,0,0,0.3); color: #ddd; border: 1px solid rgba(255,255,255,0.1); border-radius: 3px; font-size: 11px; font-family: monospace;"
                onchange="handleStringInput(this)" />
        </div>
    """.trimIndent()
    display(channel, html)
}

// Color picker control (vec4, normalized - using JOML Vector4f)
private val colorCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Vector4f) -> Unit>()

internal fun registerColorCallback(controlId: String, callback: (Vector4f) -> Unit) {
    colorCallbacks[controlId] = callback
}

internal fun getColorCallback(controlId: String): ((Vector4f) -> Unit)? {
    return colorCallbacks[controlId]
}

fun Box.colorPicker(
    label: String,
    initialValue: Vector4f = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
    channel: String = "controls",
    onUpdate: (Vector4f) -> Unit
) {
    val controlId = "color-${UUID.randomUUID()}"
    registerColorCallback(controlId, onUpdate)

    // Convert normalized values to RGB hex
    val r = (initialValue.x * 255).toInt().coerceIn(0, 255)
    val g = (initialValue.y * 255).toInt().coerceIn(0, 255)
    val b = (initialValue.z * 255).toInt().coerceIn(0, 255)
    val hexColor = String.format("#%02X%02X%02X", r, g, b)

    val html = """
        <div class="color-control" style="margin: 8px 0; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 4px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                <label style="font-size: 11px; color: #ddd;">$label</label>
                <span id="${controlId}-value" style="font-size: 10px; color: #aaa; font-family: monospace;">${String.format("rgba(%.2f, %.2f, %.2f, %.2f)", initialValue.x, initialValue.y, initialValue.z, initialValue.w)}</span>
            </div>
            <div style="display: flex; gap: 8px; align-items: center;">
                <input type="color" id="$controlId" value="$hexColor"
                    style="width: 60px; height: 30px; cursor: pointer; border: 1px solid rgba(255,255,255,0.2); background: transparent;"
                    onchange="handleColorInput(this)" />
                <div style="flex: 1;">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px;">
                        <label style="font-size: 9px; color: #aaa;">Alpha:</label>
                        <span id="${controlId}-alpha-value" style="font-size: 9px; color: #aaa; font-family: monospace;">${String.format("%.2f", initialValue.w)}</span>
                    </div>
                    <input type="range" id="${controlId}-alpha" min="0" max="1" step="0.01" value="${initialValue.w}"
                        data-control-id="$controlId"
                        style="width: 100%; cursor: pointer; accent-color: #4a9eff;"
                        oninput="handleColorAlphaInput(this)" />
                </div>
            </div>
        </div>
    """.trimIndent()
    display(channel, html)
}

// Convenience method for syncing controls to box properties
fun Box.syncToProperty(propertyName: String, initialValue: Any, channel: String = "controls", label: String = propertyName) {
    when (initialValue) {
        is Double -> {
            var value = initialValue
            slider(label, 0.0, 1.0, 0.01, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            // Mark as persistable
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is Boolean -> {
            var value = initialValue
            checkbox(label, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is String -> {
            var value = initialValue
            stringInput(label, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is Vector2f -> {
            var value = initialValue
            vec2Control(label, value, channel = channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is Vector4f -> {
            var value = initialValue
            colorPicker(label, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        else -> {
            lprintln("warning,controls", "syncToProperty: unsupported type for property '$propertyName': ${initialValue::class}")
        }
    }
}
