@file:OptIn(DelicateCoroutinesApi::class)

package afield

import afield.logger.lprintln
import afield.server.broadcastControllerControlUpdates
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/*
 * Controls whose value is more than one number, after the Max/MSP objects they borrow from:
 * `multislider` ([multiSlider]), `matrixctrl` ([matrix]), `rslider` ([rangeSlider]) and `umenu`
 * ([menu]). All four travel the same way — the whole value as a list of doubles in one
 * `array-update` message ([ArrayControlUpdate]) — and are cached as comma-separated text in
 * [ControllerControl.text], so a rerun restores them like any other grouped control. The pointer
 * handling is `src/commonMain/resources/arrayControls.js`.
 *
 * The multislider, matrix and range slider also have a **playhead**: a position the box writes and
 * the person only sees. It is pushed like a meter reading ([MeterHandle]: rate-limited, in memory,
 * never cached, never calling back), so a sequencer can drive it from its clock every tick.
 */

/**
 * What [multiSlider], [matrix] and [rangeSlider] return: the control's value, and its playhead.
 *
 * ```kotlin
 * val seq = multiSlider("steps", count = 16) { steps = it }
 * // later, from the sequencer's clock:
 * seq.playhead(step % 16)
 * ```
 */
class ArrayControlHandle<T> internal constructor(
    private val valueHandle: ControlValueHandle<T>,
    private val playheadHandle: MeterHandle
) {
    /** Stable id of the control, shared with the tablet surface. */
    val controlId: String get() = valueHandle.controlId

    /** Shows [value] on the control without calling its callback. See [ControlValueHandle.set]. */
    fun set(value: T): Boolean = valueHandle.set(value)

    /**
     * Moves the playhead to [position] — a bar or column index for a multislider or matrix (the
     * fractional part is ignored), a value on the track for a range slider. A negative position on
     * a multislider or matrix hides it. Returns false when the update was rate-limited away.
     */
    fun playhead(position: Double): Boolean = playheadHandle.set(position)

    /** [playhead] for an integer index. */
    fun playhead(index: Int): Boolean = playhead(index.toDouble())

    /** Hides the playhead. */
    fun hidePlayhead(): Boolean = playhead(-1.0)
}

private fun <T> ControlGroupBuilder.withPlayhead(handle: ControlValueHandle<T>) =
    ArrayControlHandle(handle, playheadHandle(handle.controlId))

private val arrayCallbacks = OwnedCallbackRegistry<(List<Double>) -> Unit>("array")

private fun encodeValues(values: List<Double>): String = values.joinToString(",")

/** The callback box code registered for one of these controls; the phone's path in `server/controllers.kt`. */
internal fun getArrayCallback(controlId: String): ((List<Double>) -> Unit)? = arrayCallbacks[controlId]

/** How these controls keep their value in [ControllerControl.text]. */
internal fun encodeArrayControlValues(values: List<Double>): String = encodeValues(values)

private fun decodeValues(text: String?, size: Int): List<Double>? {
    if (text.isNullOrBlank()) return null
    val values = text.split(',').map { it.trim().toDoubleOrNull() ?: return null }
    return values.takeIf { it.size == size }
}

private fun escapeHtml(text: String) =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun quantize(value: Double, min: Double, max: Double, step: Double): Double {
    val lo = minOf(min, max)
    val hi = maxOf(min, max)
    val snapped = if (step > 0.0) min + Math.round((value - min) / step) * step else value
    return snapped.coerceIn(lo, hi)
}

/** Registers the `array-update` websocket command; called once from `Server.kt`. */
internal fun registerArrayControlUpdateHandler() {
    handlers["array-update"] = mutableSetOf({ message, _ ->
        val update = Json.decodeFromJsonElement<ArrayControlUpdate>(message.payload!!)
        lprintln("debug,controls", "Received array update: ${update.controlId} = ${update.values}")
        val updatedControls =
            updateControllerControlValue(update.controlId) { it.copy(text = encodeValues(update.values)) }
        broadcastControllerControlUpdates(updatedControls, thisSessionInfo.get().filename)

        val callback = arrayCallbacks[update.controlId]
        if (callback != null) {
            GlobalScope.launch {
                try {
                    callback(update.values)
                } catch (e: Exception) {
                    lprintln("error,controls", "Error in array control callback: ${e.message}")
                    e.printStackTrace()
                }
            }
        } else {
            lprintln("warning,controls", "No callback registered for array control: ${update.controlId}")
        }
        Handled.yes
    })
}

/**
 * Shared shape of all four controls: restore, register, call back once with a restored value.
 * [toValue] and [fromValue] convert between the wire's flat list and what the box sees.
 */
private fun <T> ControlGroupBuilder.arrayControl(
    type: String,
    label: String,
    size: Int,
    initial: T,
    sanitize: (List<Double>) -> List<Double>,
    toValue: (List<Double>) -> T,
    fromValue: (T) -> List<Double>,
    html: (id: String, values: List<Double>) -> String,
    onUpdate: (T) -> Unit,
    shape: (ControllerControl) -> ControllerControl = { it }
): ControlValueHandle<T> = addControl(
    type,
    label,
    build = { id, previous ->
        val restored = decodeValues(previous?.text, size)?.let(sanitize)
        val values = restored ?: sanitize(fromValue(initial))
        arrayCallbacks.register(id, { values: List<Double> -> onUpdate(toValue(sanitize(values))) })
        if (restored != null) onUpdate(toValue(restored))
        html(id, values) to shape(ControllerControl(id = id, type = type, label = label, text = encodeValues(values)))
    },
    read = { control -> decodeValues(control.text, size)?.let(toValue) },
    write = { old, next -> old.copy(text = encodeValues(sanitize(fromValue(next)))) }
)

/** The square drawing surface a polar control draws into; `arrayControls.js` fills it in. */
private fun polarSurface(classes: String, size: Int) =
    """<svg class="array-visual polar-visual $classes" viewBox="0 0 200 200" style="width:${size}px;height:${size}px"></svg>"""

private fun polarAttribute(polar: Boolean) = if (polar) """ data-polar="true"""" else ""

private fun controlShell(kind: String, id: String, label: String, attributes: String, body: String) = """
    <div class="field-control field-array field-$kind" data-kind="$kind" data-control-id="$id" $attributes>
        <div class="field-header"><span class="field-label">${escapeHtml(label)}</span><span class="field-value array-readout"></span></div>
        $body
    </div>
""".trimIndent()

/**
 * Adds a bank of [count] vertical sliders, edited by sweeping across them — Max's `multislider`.
 *
 * Press and drag across the bars to draw a curve; a fast sweep fills every bar it passes over.
 * Use it for step sequences, envelopes drawn by hand, per-partial amplitudes, per-voice rates.
 *
 * ```kotlin
 * var steps = List(16) { 0.5 }
 * controlGroup("seq") {
 *     multiSlider("velocity", count = 16, initialValues = steps) { steps = it }
 * }
 * ```
 *
 * @param label text shown above the bars
 * @param count number of sliders
 * @param min value at the bottom of a bar
 * @param max value at the top of a bar
 * @param step snapping increment, or `0.0` for continuous values
 * @param initialValues values used when no cached value exists; padded or trimmed to [count]
 * @param height drawn height of the bank in pixels; ignored when [polar]
 * @param polar draw the bars as wedges around a circle — angle picks the bar, distance from the
 *   centre sets its value — for a step sequence that reads as a loop
 * @param size width and height of the polar dial in pixels
 * @param onUpdate called with every bar's value, left to right, as the bars change
 */
fun ControlGroupBuilder.multiSlider(
    label: String,
    count: Int = 16,
    min: Double = 0.0,
    max: Double = 1.0,
    step: Double = 0.0,
    initialValues: List<Double> = List(count) { min },
    height: Int = 96,
    polar: Boolean = false,
    size: Int = 240,
    onUpdate: (List<Double>) -> Unit
): ArrayControlHandle<List<Double>> {
    require(count in 1..512) { "multiSlider count must be between 1 and 512" }
    val sanitize = { values: List<Double> ->
        List(count) { i -> quantize(values.getOrElse(i) { min }, min, max, step) }
    }
    return withPlayhead(arrayControl("multislider", label, count, initialValues, sanitize, { it }, { it }, { id, values ->
        controlShell(
            "multislider", id, label,
            """data-min="$min" data-max="$max" data-step="$step" data-count="$count" data-values="${encodeValues(values)}"${polarAttribute(polar)}""",
            if (polar) polarSurface("multislider-visual", size)
            else """<svg class="array-visual multislider-visual" viewBox="0 0 $count 1" preserveAspectRatio="none" style="height:${height}px"></svg>"""
        )
    }, onUpdate) { it.copy(min = min, max = max, step = step, columns = count, polar = polar) })
}

/**
 * Adds a grid of toggles, painted by dragging — Max's `matrixctrl`.
 *
 * A press flips a cell, and dragging paints every cell it crosses to that same new state, so one
 * stroke either fills or clears. The value is indexed `[row][column]`. For more than on and off,
 * pass `states` to the other overload.
 *
 * ```kotlin
 * var pattern = 4 { 16 { false } }
 * controlGroup("drums") {
 *     matrix("pattern", rows = 4, columns = 16, initialValue = pattern) { pattern = it }
 * }
 * ```
 *
 * @param label text shown above the grid
 * @param rows number of rows
 * @param columns number of columns
 * @param initialValue cells used when no cached value exists; missing cells are off
 * @param polar draw rows as rings (row 0 outermost) and columns as sectors, clockwise from the top
 * @param size width and height of the polar dial in pixels
 * @param onUpdate called with the whole grid whenever a cell changes
 */
fun ControlGroupBuilder.matrix(
    label: String,
    rows: Int = 4,
    columns: Int = 8,
    initialValue: List<List<Boolean>> = emptyList(),
    polar: Boolean = false,
    size: Int = 240,
    onUpdate: (List<List<Boolean>>) -> Unit
): ArrayControlHandle<List<List<Boolean>>> = matrixControl(
    label, rows, columns, 2,
    toCell = { it != 0 }, fromCell = { if (it == true) 1 else 0 },
    initialValue, polar, size, onUpdate
)

/**
 * Adds a grid whose cells each hold one of [states] values, `0 until states`.
 *
 * A press advances a cell to its next state (wrapping back to 0), and dragging paints that same
 * state across every cell it crosses. State 0 draws black; the others take equally spaced hues,
 * so a step sequencer can mark accents, ties or voices in one grid.
 *
 * ```kotlin
 * var steps = 4 { 16 { 0 } }
 * controlGroup("drums") {
 *     matrix("steps", rows = 4, columns = 16, states = 4, initialValue = steps) { steps = it }
 * }
 * ```
 *
 * @param label text shown above the grid
 * @param rows number of rows
 * @param columns number of columns
 * @param states how many values a cell cycles through, including 0
 * @param initialValue cells used when no cached value exists; missing cells are 0, others are clamped
 * @param polar draw rows as rings (row 0 outermost) and columns as sectors, clockwise from the top
 * @param size width and height of the polar dial in pixels
 * @param onUpdate called with the whole grid whenever a cell changes
 */
fun ControlGroupBuilder.matrix(
    label: String,
    rows: Int = 4,
    columns: Int = 8,
    states: Int,
    initialValue: List<List<Int>> = emptyList(),
    polar: Boolean = false,
    size: Int = 240,
    onUpdate: (List<List<Int>>) -> Unit
): ArrayControlHandle<List<List<Int>>> = matrixControl(
    label, rows, columns, states,
    toCell = { it }, fromCell = { it ?: 0 },
    initialValue, polar, size, onUpdate
)

private fun <C> ControlGroupBuilder.matrixControl(
    label: String,
    rows: Int,
    columns: Int,
    states: Int,
    toCell: (Int) -> C,
    fromCell: (C?) -> Int,
    initialValue: List<List<C>>,
    polar: Boolean,
    size: Int,
    onUpdate: (List<List<C>>) -> Unit
): ArrayControlHandle<List<List<C>>> {
    require(rows in 1..64 && columns in 1..64) { "matrix rows and columns must be between 1 and 64" }
    require(states in 2..64) { "matrix states must be between 2 and 64" }
    val cells = rows * columns
    val toGrid = { flat: List<Double> -> List(rows) { r -> List(columns) { c -> toCell(flat[r * columns + c].toInt()) } } }
    val toFlat = { grid: List<List<C>> ->
        List(cells) { i -> fromCell(grid.getOrNull(i / columns)?.getOrNull(i % columns)).toDouble() }
    }
    val sanitize = { values: List<Double> ->
        List(cells) { i -> Math.round(values.getOrElse(i) { 0.0 }).toInt().coerceIn(0, states - 1).toDouble() }
    }
    return withPlayhead(arrayControl("matrix", label, cells, initialValue, sanitize, toGrid, toFlat, { id, values ->
        controlShell(
            "matrix", id, label,
            """data-rows="$rows" data-columns="$columns" data-states="$states" data-values="${encodeValues(values)}"${polarAttribute(polar)}""",
            if (polar) polarSurface("matrix-visual matrix-polar", size)
            else """<div class="array-visual matrix-visual" style="grid-template-columns:repeat($columns,1fr)"></div>"""
        )
    }, onUpdate) { it.copy(rows = rows, columns = columns, states = states, polar = polar) })
}

/**
 * Adds a slider with two thumbs selecting an interval — Max's `rslider`.
 *
 * Drag a thumb to move one end; drag between them to slide the whole interval.
 *
 * With [polar] the track is a whole circle, `min` and `max` meet at the top, and the interval runs
 * clockwise from `start` to `endInclusive` — so it can wrap through the top. A wrapped interval has
 * `start > endInclusive` and covers `start..max` plus `min..endInclusive`; test membership with
 * [arcContains], since Kotlin's own `in` reads such a range as empty. Use it for things that are
 * already circular: phase, hue, a window into a loop.
 *
 * ```kotlin
 * var band = 200.0..2000.0
 * controlGroup("filter") {
 *     rangeSlider("band", min = 20.0, max = 20000.0, initialValue = band) { band = it }
 * }
 * ```
 *
 * @param label text shown above the track
 * @param min smallest selectable value
 * @param max largest selectable value
 * @param step snapping increment, or `0.0` for continuous values
 * @param initialValue interval used when no cached value exists
 * @param polar draw the track as a full circle on which the interval may wrap through `max` to `min`
 * @param size width and height of the polar dial in pixels
 * @param onUpdate called with the interval as either end moves
 */
fun ControlGroupBuilder.rangeSlider(
    label: String,
    min: Double = 0.0,
    max: Double = 1.0,
    step: Double = 0.0,
    initialValue: ClosedFloatingPointRange<Double> = min..max,
    polar: Boolean = false,
    size: Int = 240,
    onUpdate: (ClosedFloatingPointRange<Double>) -> Unit
): ArrayControlHandle<ClosedFloatingPointRange<Double>> {
    val sanitize = { values: List<Double> ->
        val a = quantize(values.getOrElse(0) { min }, min, max, step)
        val b = quantize(values.getOrElse(1) { max }, min, max, step)
        // On the dial the order is the direction of travel, and reversing it means the other arc.
        if (polar) listOf(a, b) else listOf(minOf(a, b), maxOf(a, b))
    }
    return withPlayhead(arrayControl(
        "range", label, 2, initialValue, sanitize,
        { it[0]..it[1] }, { listOf(it.start, it.endInclusive) }, { id, values ->
            controlShell(
                "range", id, label,
                """data-min="$min" data-max="$max" data-step="$step" data-values="${encodeValues(values)}"${polarAttribute(polar)}""",
                if (polar) polarSurface("range-visual range-polar", size)
                else """<div class="array-visual range-visual"><div class="range-track"></div><div class="range-fill"></div><div class="range-thumb" data-end="0"></div><div class="range-thumb" data-end="1"></div><div class="range-playhead"></div></div>"""
            )
        }, onUpdate
    ) { it.copy(min = min, max = max, step = step, polar = polar) })
}

/**
 * Adds a drop-down choice among [options] — Max's `umenu`.
 *
 * ```kotlin
 * controlGroup("synth") {
 *     menu("wave", listOf("sine", "saw", "square")) { index, name -> wave = name }
 * }
 * ```
 *
 * @param label text shown beside the menu
 * @param options the choices, in order
 * @param initialIndex choice used when no cached value exists
 * @param onUpdate called with the chosen index and its text
 */
fun ControlGroupBuilder.menu(
    label: String,
    options: List<String>,
    initialIndex: Int = 0,
    onUpdate: (index: Int, option: String) -> Unit
): ControlValueHandle<Int> {
    require(options.isNotEmpty()) { "menu needs at least one option" }
    val sanitize = { values: List<Double> ->
        listOf(values.getOrElse(0) { 0.0 }.toInt().coerceIn(0, options.lastIndex).toDouble())
    }
    return arrayControl("menu", label, 1, initialIndex, sanitize, { it[0].toInt() }, { listOf(it.toDouble()) }, { id, values ->
        val chosen = values[0].toInt()
        val items = options.mapIndexed { i, o ->
            """<option value="$i"${if (i == chosen) " selected" else ""}>${escapeHtml(o)}</option>"""
        }.joinToString("")
        """
            <div class="field-control field-array field-menu" data-kind="menu" data-control-id="$id" data-values="$chosen">
                <label class="field-label" for="$id">${escapeHtml(label)}</label>
                <select class="field-menu-select" id="$id">$items</select>
            </div>
        """.trimIndent()
    }, { onUpdate(it, options[it]) }) { it.copy(options = options) }
}

/**
 * Whether [value] lies on this interval as a polar [rangeSlider] means it: clockwise from `start`
 * to `endInclusive`, wrapping through the top when `start > endInclusive`. An ordinary range
 * answers exactly as `in` does.
 */
fun ClosedFloatingPointRange<Double>.arcContains(value: Double): Boolean =
    if (start <= endInclusive) value in this else value >= start || value <= endInclusive
