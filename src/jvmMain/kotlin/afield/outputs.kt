package afield

import afield.macros.Insert
import afield.server.Inspector
import afield.server.LogStream
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

fun registerSliderCallback(sliderId: String, callback: (Double) -> Unit) {
    sliderCallbacks[sliderId] = callback
}

fun getSliderCallback(sliderId: String): ((Double) -> Unit)? {
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
