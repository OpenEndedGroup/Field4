@file:Suppress("MemberVisibilityCanBePrivate", "ReplaceJavaStaticMethodWithKotlinAnalog")

package afield

import afield.logger.lprintln
import afield.stdlib.Delegation
import afield.stdlib.Marker
import afield.stdlib.SummaryStats
import afield.stdlib.SummaryStatsBuffer
import afield.stdlib.SummaryStatsBox
import afield.stdlib.summaryStatsBoxToHtml
import kotlinx.coroutines.yield
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


/**
 * A single Monaco decoration payload for a box.
 *
 * Coordinates are 1-based line/column pairs. The decoration is applied on the client by the
 * `decorations.js` bridge, which expects a class name and optional CSS class definitions.
 *
 * If [clickCallback] is provided, a synthetic action id is generated and wired so Monaco can
 * dispatch a click on this decoration back to the server.
 */
data class RawDecoration(
    val lineStart: Int,
    val colStart: Int,
    val lineEnd: Int,
    val colEnd: Int,
    val classname: String,
    val classDef: Map<String, String> = emptyMap(),
    val clickCallback: (() -> Unit)? = null
) {
    /**
     * Convenience constructor that decorates exactly one character at [l].
     */
    constructor(
        l: Location, className: String, classDef: Map<String, String> = emptyMap(), clickCallback: (() -> Unit)? = null
    ) : this(
        l.line, l.col, l.line, l.col + 1, className, classDef, clickCallback
    )
}

/**
 * Serialized decoration payload cached on the box and pushed to clients.
 */
var Box.code_decorationState by Delegation<String>()
/**
 * Per-decoration callback table keyed by generated action ids.
 */
var Box.code_decorationCallbackTable by Delegation<MutableMap<String, () -> Unit>>(defaultValue = { HashMap() })
/**
 * High-level decoration state for a box.
 *
 * Whenever this map is read, it schedules a decoration update on the UI thread.
 */
val Box.decorationState by Delegation<MutableMap<String, RawDecoration>>(
    defaultValue = { mutableMapOf() },
    onGet = { inside, v ->
        inMain {
            yield()
            setDecorations(inside as Box, v.values.toList())
        }
 })

/*
need to gather this from a map and some fancy delegation tricks
 */
@OptIn(ExperimentalUuidApi::class)
/**
 * Push a set of decorations for [box] to all active sessions.
 *
 * This:
 * 1. Flattens class definitions into a single CSS string.
 * 2. Registers click callbacks and maps them to action ids.
 * 3. Builds the inline decoration payload consumed by the JS bridge.
 *
 * Callers typically update [Box.decorationState] and let the delegate invoke this.
 */
fun setDecorations(box: Box, decor: List<RawDecoration>) {

    box.code_decorationCallbackTable.clear()

    val defs = "\"definition\":\"" + decor.flatMap { it.classDef.entries }
        .associateBy { (key, value) -> key }.values.joinToString(separator = " ") { it.value } + "\""

    val callbacks = "\"callbacks\":{" + decor.mapNotNull {
        if (it.clickCallback != null) {
            val uid = Uuid.random().toString()
            box.code_decorationCallbackTable.put(uid, it.clickCallback)
            actionTable[uid] = { selected ->
                it.clickCallback()
            }
            it.classname to uid
        } else null
    }.joinToString(separator = ",") { "\"${it.first}\":\"${it.second}\"" } + "}"

    val inline =
        "\"inline\":[" + decor.joinToString(separator = ",") { "[${it.lineStart},${it.colStart},${it.lineEnd},${it.colEnd},\"${it.classname}\"]" } + "]"
    val r = "{${defs}, ${callbacks}, ${inline}}"


    val message = Message("set-string").payload(Set_string(box.id, "code_decorationState", r))
    box.inside.sessions.forEach { _send(it, message) }

    box.code_decorationState = r
}

/**
 * A precise source location inside a box property.
 *
 * The coordinates are 1-based and refer to the *current* code string in the box.
 */
data class Location(val inside: Box, val property: String, val line: Int, val col: Int)

/**
 * Update a map entry by computing or inserting a default, then applying [u].
 *
 * @return The updated value.
 */
fun <K, V> MutableMap<K, in V>.update(key: K, d: V, u: (V) -> V): V {
    @Suppress("UNCHECKED_CAST")
    val v = u(computeIfAbsent(key) { d } as V)
    put(key, v)
    return v
}

/**
 * Attach a clickable "toggle" decoration to the call site.
 *
 * The decoration is anchored to the `.toggle(` text in the current stack frame.
 * Clicking the decoration invokes [cb].
 */
fun Marker.toggle(cb: () -> Unit) {
    val location = locationForTextStringOnStack(".toggle(", this.box, end = true, offset = 1)
    if (location != null) this.box.decorationState.put(
        this.name + "toggle", RawDecoration(
            location, "toggle${this.name}", mapOf(
                "toggle${this.name}" to """
                .toggle${this.name} { border-bottom: 0px solid white; }
                .toggle${this.name}:before {content:'X'; color:white;position:relative; left:-0px; font-size:100%}
                .toggle${this.name}:hover::before {content:'X'; color:red;position:relative; left:-0px; font-size:100%}""".replace(
                    "\n", " "
                )
            ), clickCallback = {
                lprintln("info,decorations", "clicked ${this.name}")
                cb()
            })
    )
}

/**
 * Attach an alternating glyph decoration to the call site.
 *
 * Used as a lightweight "I was here" visual pulse when re-running code.
 */
fun Marker.beep(
    tick: Int = properties.update("beep", 0) { it + 1 }
) {
    val location = locationForTextStringOnStack(".beep(", this.box, end = true, offset = 1)
    val color = if (tick % 2 == 0) "white" else "white"
    val glyph = if (tick % 2 == 0) "🞊" else "🞅"
    val q = tick % 2
    lprintln("debug,decorations", "$location -> ${q + 1}")
    if (location != null) this.box.decorationState.put(
        this.name + "_beep",
        RawDecoration(
            location,
            "d$q",
            mapOf("d$q" to ".d$q { border-bottom: 0px solid ${color};} .d${q}:before {content:'${glyph}'; color:${color};position:relative; left:-0px; font-size:100%}")
        ),
    )
}

/**
 * Wraps a string with a decoration emitter.
 *
 * The wrapper can be used to stamp additional glyphs at the call site without
 * mutating the underlying string.
 */
class BoppableString(val s: String, val inside: Marker) : () -> String {
    /**
     * Add a small glyph decoration at the call site.
     *
     * Multiple calls can stack, using [index] to offset the glyph horizontally.
     */
    fun bop(index: Int, color: String = "white", filled: Boolean = true) {
        val location = locationForTextStringOnStack(".bop(", inside.box, end = true, offset = 1)
        val glyph = if (filled) "🞊" else "🞅"
        val className = "d${index}_${inside.name}"
        if (location != null) inside.box.decorationState.put(
            inside.name + "_beep",
            RawDecoration(
                location,
                className,
                mapOf(className to ".${className} { border-bottom: 0px solid ${color};} .${className}:before {content:'${glyph}'; color:${color};position:relative; left:${(index + 2) * 7.5 + 4}px; top:-12px; font-size:100%}")
            ),
        )
    }

    override fun invoke(): String {
        return s
    }
}

/**
 * Creates a [BoppableString] wrapper to drive glyph decorations.
 */
fun Marker.bop(
    text: String
): BoppableString {
    return BoppableString(text, this)
}


/**
 * Minimal rolling history for histogram and time-series sketches.
 *
 * The class also rate-limits SVG output to avoid flooding the editor output.
 */
class LittleHistory {
    private val buffer = SummaryStatsBuffer(maxSize = 100)
    private var previousHistogramStats: SummaryStats? = null
    private var previousHistoryStats: SummaryStats? = null
    private var previousSummary: SummaryStatsBox? = null

    var outputRateLimiter = RateLimiter(10.0, 0.2)
    /**
     * Append a value and cap history length.
     */
    fun add(v: Number?, size: Int = 100) = buffer.setWindow(size).add(v)

    /**
     * Render a histogram SVG for the current history.
     */
    fun histogram(): String {
        val current = buffer.snapshot()
        val html = buffer.histogramSvg(
            previousStats = previousHistogramStats,
            widthPx = 480,
            heightPx = 100,
            padTop = 20,
            padBottom = 15,
            decimals = 2
        )
        previousHistogramStats = current
        return html
    }

    /**
     * Render a line chart SVG for the current history.
     */
    fun history(): String {
        val current = buffer.snapshot()
        val html = buffer.seriesSvg(
            previousStats = previousHistoryStats,
            widthPx = 480,
            heightPx = 100,
            padTop = 20,
            padLeft = 50,
            padRight = 80,
            padBottom = 15,
            decimals = 2
        )
        previousHistoryStats = current
        return html
    }

    /**
     * Render a summary statistics panel (metrics + histogram + series).
     */
    fun stats(): String {
        val current = buffer.summaryBox()
        val html = summaryStatsBoxToHtml(
            summary = current,
            previousSummary = previousSummary,
            widthPx = 480,
            chartHeightPx = 100,
            decimals = 2
        )
        previousSummary = current
        return html
    }
}

private fun Marker.summaryBuffer(): LittleHistory =
    this.properties.computeIfAbsent("__histogram__") { LittleHistory() } as LittleHistory


/**
 * Track a rolling histogram and emit it into the editor output.
 *
 * The call site is used as the output anchor, so the histogram appears next to the code line.
 */
fun <T> Marker.histogram(value: T, dur: Int = 100): T {
    val location = locationForTextStringOnStack("histogram", this.box, end = true, offset = 1)
    val h = summaryBuffer()
    h.add(value as? Number, size = dur)
    h.outputRateLimiter {
        location?.let { this.box._printHere(it.line, h.histogram()) }
    }
    return value
}

/**
 * Track a rolling time series and emit it into the editor output.
 *
 * The call site is used as the output anchor, so the chart appears next to the code line.
 */
fun <T> Marker.history(value: T, dur: Int = 100): T {
    val location = locationForTextStringOnStack("history", this.box, end = true, offset = 1)
    val h = summaryBuffer() // intentional shared state
    h.add(value as? Number, size = dur)
    h.outputRateLimiter {
        location?.let { this.box._printHere(it.line, h.history()) }
    }
    return value
}

/**
 * Track rolling summary stats and emit a compact summary panel next to the call site.
 */
fun <T> Marker.stats(value: T, dur: Int = 100): T {
    val location = locationForTextStringOnStack("stats", this.box, end = true, offset = 1)
    val h = summaryBuffer()
    h.add(value as? Number, size = dur)
    h.outputRateLimiter {
        location?.let { this.box._printHere(it.line, h.stats()) }
    }
    return value
}


/**
 * Locate the call site of [code] inside a box's current source text.
 *
 * This inspects the stack trace, filters to frames belonging to [box], and then searches
 * the corresponding source line for [code]. It returns a [Location] pointing to the match.
 *
 * @param code The snippet to search for (e.g. ".liveNumber(").
 * @param box The box whose source should be searched.
 * @param st Stack trace to inspect (defaults to current stack).
 * @param end If true, the returned column points to the end of the match.
 * @param offset Additional column offset applied after [end].
 */
fun locationForTextStringOnStack(
    code: String,
    box: Box? = null,
    st: Array<StackTraceElement> = Exception().stackTrace,
    end: Boolean = false,
    offset: Int = 0
): Location? {
    val found = st.filter {
        val inside = if (box != null) (it.fileName?.contains(box.id) ?: false) else false
        if (box != null && inside) {
            true
        } else {
            false
        }
    }
    // `firstNotNullOfOrNull`, not `firstNotNullOf`: the function is declared to return `Location?`
    // and every caller null-checks, but `firstNotNullOf` *throws* NoSuchElementException when the
    // anchor text is not on any frame of the box's own code. That happens whenever the call is made
    // from a library helper rather than written literally in the box — `chords("c'maj7")` calling
    // `pat(...)` internally hit it, and so would `Marker.toggle` invoked from a helper function.
    return found.firstNotNullOfOrNull {
        val ln = it.lineNumber
        // A native, synthetic or otherwise line-less frame reports a *non-positive* line number, and
        // `drop(ln - 1)` throws IllegalArgumentException on it rather than returning null. The whole
        // contract of this helper is "a source hint, or null", so throwing from it turns a missing
        // hint into a failed call several layers up: `pat("0 1 2 3")` evaluated inside a box died
        // here with "Requested element count -2 is less than zero", and RectSL's bare-string pattern
        // coercion — which asks `pat` whether a string is a rhythm at all — swallowed that into "a
        // String is read as mini-notation only when it contains numbers", about a string made
        // entirely of numbers.
        if (ln <= 0) return@firstNotNullOfOrNull null
        val lines = box?.code?.split("\n")
        val index = lines?.drop(ln - 1)?.indexOfFirst { it.contains(code) }
        if (index != null && index != -1) {
            Location(
                box,
                "code",
                index + (ln - 1) + 1,
                lines[index + ln - 1].indexOf(code) + (if (end) code.length else 0) + offset
            )
        } else null
    }
}

/**
 * Returns a "live number" - a number that is read directly from the source code at this marker's location.
 * When the source code changes, the returned value changes immediately without recompilation or reevaluation.
 *
 * The function stores the last successfully extracted value in the marker's properties. If extraction fails
 * (e.g., during editing or if the source is temporarily invalid), it returns the stored value instead of
 * the default. This provides stability during interactive editing.
 *
 * **Important**: The argument must be a literal numeric value (e.g., `10.0`, `42`, `1e-5`).
 * Expressions like `1 + 2`, `Math.PI`, or variable references are not supported and will cause
 * the function to return the stored value (or defaultValue on first call).
 *
 * Usage example:
 * ```
 * val m = __m("myMarker")
 * val speed = m.liveNumber(10.0)  // Initially returns 10.0
 * // When user changes "10.0" to "20.0" in the code editor, next call returns 20.0 without recompilation
 * // If editing causes temporary parse failure, returns the last known value (20.0) until editing is complete
 * ```
 *
 * @param defaultValue The initial value to use on first call if source cannot be found or parsed
 * @return The numeric value extracted from the source code, or the last known good value if extraction fails
 */
fun Marker.liveNumber(defaultValue: Number = 0.0): Double {
    // Find the location where liveNumber was called in the source code
    val location = locationForTextStringOnStack(".liveNumber(", this.box, end = true, offset = 0)

    // Create a unique key for this specific liveNumber call based on its location
    // This allows multiple liveNumber calls on the same marker to maintain separate cached values
    val cacheKey = if (location != null) {
        "__liveNumber__${location.line}:${location.col}__"
    } else {
        "__liveNumber__default__"
    }

    // Get or initialize the cached previous value from the marker's properties
    // Use safe cast to handle potential type mismatches in the properties map
    val cachedValue = (this.properties.computeIfAbsent(cacheKey) {
        defaultValue.toDouble()
    } as? Double) ?: defaultValue.toDouble()

    val errorKey = "${cacheKey}__error__"
    val errorClass = "liveNumberError"
    val errorCss = """
        .${errorClass} {
            color: #ff6b6b;
            background: rgba(255, 80, 80, 0.18);
            border-bottom: 1px solid rgba(255, 80, 80, 0.95);
        }
    """.trimIndent().replace("\n", " ")

    fun clearLiveNumberError() {
        this.box.decorationState.remove(errorKey)
    }

    fun setLiveNumberError(lineNumber: Int, startPos: Int, endPos: Int, lineText: String) {
        var startCol = startPos + 1
        var endCol = endPos + 1
        if (endCol <= startCol) {
            if (startPos < lineText.length) {
                endCol = startCol + 1
            } else {
                return
            }
        }
        this.box.decorationState[errorKey] = RawDecoration(
            lineNumber,
            startCol,
            lineNumber,
            endCol,
            errorClass,
            mapOf(errorClass to errorCss)
        )
    }

    if (location == null) {
        lprintln("warn,decorations", "liveNumber: Could not find location for marker ${this.name}, using cached value: $cachedValue")
        clearLiveNumberError()
        return cachedValue
    }

    // Get the source code and split it into lines
    val lines = this.box.code.split("\n")

    // Get the line where liveNumber is called
    val lineIndex = location.line - 1
    if (lineIndex < 0 || lineIndex >= lines.size) {
        lprintln("warn,decorations", "liveNumber: Line index out of bounds: $lineIndex, using cached value: $cachedValue")
        clearLiveNumberError()
        return cachedValue
    }

    val line = lines[lineIndex]

    // Use the column information from location to find the exact position
    // location.col points to just after ".liveNumber(" due to end=true in locationForTextStringOnStack
    val startPos = location.col

    // Verify we're at the expected position (column is 0-based)
    if (startPos < 0 || startPos > line.length) {
        lprintln("warn,decorations", "liveNumber: Column position out of bounds: ${location.col}, using cached value: $cachedValue")
        clearLiveNumberError()
        return cachedValue
    }

    var endPos = startPos
    var parenDepth = 1

    // Find the matching closing parenthesis, handling nested parentheses
    while (endPos < line.length && parenDepth > 0) {
        when (line[endPos]) {
            '(' -> parenDepth++
            ')' -> parenDepth--
        }
        if (parenDepth > 0) endPos++
    }

    if (parenDepth != 0) {
        lprintln("warn,decorations", "liveNumber: Could not find matching closing parenthesis, using cached value: $cachedValue")
        setLiveNumberError(location.line, startPos, endPos, line)
        return cachedValue
    }

    // Extract the argument text and trim whitespace
    val argText = line.substring(startPos, endPos).trim()

    // If argument is empty, return cached value
    if (argText.isEmpty()) {
        setLiveNumberError(location.line, startPos, endPos, line)
        return cachedValue
    }

    // Try to parse the argument as a double
    // This handles integers, floats, and scientific notation (e.g., 1.0, 42, 1e-5)
    val parsedValue = argText.toDoubleOrNull()
    if (parsedValue == null) {
        lprintln("warn,decorations", "liveNumber: Could not parse argument as number: '$argText' - only literal numeric values are supported, using cached value: $cachedValue")
        setLiveNumberError(location.line, startPos, endPos, line)
        return cachedValue
    }

    // Store the successfully parsed value for future use
    this.properties[cacheKey] = parsedValue
    clearLiveNumberError()

    lprintln("debug,decorations", "liveNumber: Extracted value $parsedValue from source (marker: ${this.name})")
    return parsedValue
}
