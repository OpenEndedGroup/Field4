@file:OptIn(DelicateCoroutinesApi::class)

package afield.ui.visualization

import afield.animation.gesture.bakeGesture
import afield.app.server.Server
import afield.app.server.option
import afield.data.table.cell
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.Box
import afield.document.graph.box
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.forever
import afield.runtime.lifecycle.loop
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.output.button
import afield.ui.output.column
import afield.ui.output.controlGroup
import afield.ui.output.slider
import afield.ui.output.sliders
import afield.ui.output.templates.filter
import afield.ui.output.vec2Control

import afield.animation.gesture.GestureFit
import afield.animation.gesture.GestureLanes
import afield.animation.gesture.lanesToTake
import afield.animation.gesture.measureGestureFit
import afield.animation.gesture.reduceTakeIndices
import afield.animation.gesture.takeToLanes
import afield.animation.keyframes.Keyframe
import afield.animation.keyframes.KeyframeEvaluator
import afield.app.protocol.ArrayControlUpdate
import afield.app.protocol.ControllerControl
import afield.app.protocol.Handled
import afield.app.protocol.handle
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.server.thisSessionInfo
import afield.audio.synth.*
import afield.data.table.get
import afield.data.table.plus
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.execution.plus
import afield.runtime.lifecycle.OwnedCallbackRegistry
import afield.runtime.lifecycle.map
import afield.runtime.lifecycle.plus
import afield.ui.output.ControlGroupBuilder
import afield.ui.output.ControlValueHandle
import afield.ui.output.MeterHandle
import afield.ui.output.registerVec2Callback
import afield.ui.output.update
import afield.ui.output.updateControllerControlValue
import afield.ui.output.updateControllerControlValueInMemory
import afield.app.diagnostics.logging.lprintln
import afield.app.server.broadcastControllerControlUpdates
import afield.math.linalg.Vector2f
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

    // A playhead set inside the controlGroup block arrives before the control is registered, where
    // it would find nothing to move; it waits here until the group is shown.
    @Volatile private var shown = false
    @Volatile private var pending: Double? = null

    internal fun markShown() {
        shown = true
        val position = pending ?: return
        pending = null
        val updated = updateControllerControlValueInMemory(controlId) { it.copy(value = position) }
        playheadHandle.targetDocument?.let { broadcastControllerControlUpdates(updated, it) }
    }

    /** Shows [value] on the control without calling its callback. See [ControlValueHandle.set]. */
    fun set(value: T): Boolean = valueHandle.set(value)

    /**
     * Moves the playhead to [position] — a bar or column index for a multislider or matrix (the
     * fractional part is ignored), a value on the track for a range slider. A negative position on
     * a multislider or matrix hides it. Returns false when the update was rate-limited away.
     */
    fun playhead(position: Double): Boolean {
        if (!shown) {
            pending = position
            return true
        }
        return playheadHandle.set(position)
    }

    /** [playhead] for an integer index. */
    fun playhead(index: Int): Boolean = playhead(index.toDouble())

    /** Hides the playhead. */
    fun hidePlayhead(): Boolean = playhead(-1.0)
}

private fun <T> ControlGroupBuilder.withPlayhead(handle: ControlValueHandle<T>) =
    ArrayControlHandle(handle, playheadHandle(handle.controlId)).also { afterShown(it::markShown) }

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

/**
 * What [gesture] returns: the recorded take, and a way to replay it.
 *
 * The take is a loop. The box owns time: ask [at] where the take is at some number of seconds,
 * read off whatever clock the box keeps, and show it with [playhead].
 */
class GestureHandle internal constructor(
    private val control: ArrayControlHandle<List<Double>>,
    take: List<Double>,
    private val sanitize: (List<Double>) -> List<Double> = { it },
    /** True for `gesture(pen = true)`: the take keeps pressure, tilt and twist with each position. */
    val pen: Boolean = false,
    /** The pad's value at its left and right edges. */
    val xRange: Pair<Double, Double> = -1.0 to 1.0,
    /** The pad's value at its bottom and top edges. */
    val yRange: Pair<Double, Double> = -1.0 to 1.0,
) {
    /** Stable id of the control, shared with the phone. */
    val controlId: String get() = control.controlId

    // Numbers per sample: (t, x, y), or for a pen (t, x, y, pressure, tiltX, tiltY, twist).
    private val stride = if (pen) PEN_STRIDE else 3

    // Flattened samples, t in seconds from the start of the stroke.
    @Volatile private var take: DoubleArray = take.toDoubleArray()

    internal fun replace(values: List<Double>) {
        take = values.toDoubleArray()
    }

    /** True once a take has been recorded (or given as `initialTake`). */
    val hasTake: Boolean get() = take.size >= 2 * stride

    /** Length of the take in seconds; `0.0` with no take. */
    val duration: Double get() = take.let { if (it.size >= stride) it[it.size - stride] else 0.0 }

    /**
     * Where the take is [seconds] into its loop, interpolated between the recorded samples, or
     * `null` with no take. Seconds past the end wrap round, so `at(clock)` loops forever.
     */
    fun at(seconds: Double): Vector2f? {
        val p = take
        if (p.size < 2 * stride) return null
        val length = p[p.size - stride]
        val t = if (length > 0.0) ((seconds % length) + length) % length else 0.0
        return Vector2f(interpolate(p, t, 1).toFloat(), interpolate(p, t, 2).toFloat())
    }

    // Field [field] of the take [p] at [t] (no wrapping), joined by straight lines as [at] is:
    // 1 x, 2 y, and for a pen 3 pressure, 4 tiltX, 5 tiltY, 6 twist.
    private fun interpolate(p: DoubleArray, t: Double, field: Int): Double {
        val k = stride
        var lo = 0
        var hi = p.size / k - 1
        if (hi <= 0) return p.getOrElse(field) { 0.0 }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (p[mid * k] <= t) lo = mid else hi = mid
        }
        val t0 = p[lo * k]
        val t1 = p[hi * k]
        val f = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
        return p[lo * k + field] + (p[hi * k + field] - p[lo * k + field]) * f
    }

    /** Shows a dot on the pad where the take is [seconds] into its loop. Negative hides it. */
    fun playhead(seconds: Double): Boolean = control.playhead(seconds)

    /** The take as (seconds, position) pairs in time order — the form `initialTake` accepts. */
    val samples: List<Pair<Double, Vector2f>>
        get() = take.let { p -> List(p.size / stride) { i -> p[i * stride] to Vector2f(p[i * stride + 1].toFloat(), p[i * stride + 2].toFloat()) } }

    /**
     * The take with everything the pointer reported at each sample. A take recorded without `pen`
     * has none of it: pressure is `NaN` and tilt and twist 0.
     */
    val penSamples: List<PenSample>
        get() = take.let { p ->
            List(p.size / stride) { i ->
                val o = i * stride
                if (pen) PenSample(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], p[o + 6])
                else PenSample(p[o], p[o + 1], p[o + 2])
            }
        }

    /** The take as flattened (t, x, y) triples, whatever else it records. */
    internal fun rawTake(): DoubleArray = take.let { p ->
        if (stride == 3) p else DoubleArray(p.size / stride * 3) { j -> p[(j / 3) * stride + j % 3] }
    }

    /**
     * One of the pen's dimensions — `"pressure"`, `"tiltX"`, `"tiltY"` or `"twist"` — reduced to a
     * `linear` lane the way [toKeyframes] reduces x and y: the fewest samples that stay within
     * [tolerance] (in that dimension's units: pressure 0..1, degrees for the rest) of the take.
     * Empty for a take recorded without `pen`.
     */
    fun penLane(dimension: String, tolerance: Double = 0.02): List<Keyframe> {
        val field = PEN_FIELDS.indexOf(dimension).takeIf { it >= 0 }?.plus(3)
            ?: throw IllegalArgumentException("a pen lane is one of ${PEN_FIELDS.joinToString()}: not '$dimension'")
        if (!pen || !hasTake) return emptyList()
        val p = take
        val n = p.size / stride
        val flat = DoubleArray(n * 3) { j -> val i = j / 3; if (j % 3 == 0) p[i * stride] else p[i * stride + field] }
        return reduceTakeIndices(flat, tolerance).map { Keyframe(flat[it * 3], flat[it * 3 + 1], "linear") }
    }

    /**
     * The take reduced to two `linear` lanes, x and y, sharing their key times: the fewest recorded
     * samples that keep both axes within [tolerance] (pad units) of the take at every moment. The
     * lanes start at 0 and end at [duration], so they loop as the take does. See [Box.bakeGesture].
     */
    fun toKeyframes(tolerance: Double = 0.02): GestureLanes = takeToLanes(rawTake(), tolerance)

    /** How closely [x] and [y] follow this take; see [GestureFit]. */
    fun fit(x: KeyframeEvaluator, y: KeyframeEvaluator): GestureFit = measureGestureFit(rawTake(), x, y)

    /**
     * Replaces the take with [samples], as if it had been recorded: the pad shows the new trail and
     * the take is saved with the box. Positions are clamped to the pad and time starts at 0.
     */
    fun setTake(samples: List<Pair<Double, Vector2f>>) {
        val old = take
        val t0 = samples.firstOrNull()?.first ?: 0.0
        val flat = sanitize(samples.flatMap { (t, v) ->
            val xy = listOf(t, v.x.toDouble(), v.y.toDouble())
            // A pen take whose path is edited keeps what the pen did at each moment of it.
            if (!pen) xy
            else if (old.size >= stride) xy + (3 until stride).map { interpolate(old, t - t0, it) }
            else xy + listOf(0.5, 0.0, 0.0, 0.0)
        })
        take = flat.toDoubleArray()
        control.set(flat)
    }

    /** Replaces the take with [samples], pressure, tilt and twist included (a pen take keeps them). */
    @JvmName("setPenTake")
    fun setTake(samples: List<PenSample>) {
        val flat = sanitize(samples.flatMap { s ->
            val xy = listOf(s.t, s.x, s.y)
            if (pen) xy + listOf(s.pressure.takeIf { it.isFinite() } ?: 0.5, s.tiltX, s.tiltY, s.twist) else xy
        })
        take = flat.toDoubleArray()
        control.set(flat)
    }

    /**
     * Replaces the take with the curves [x] and [y] — lanes edited in the keyframe editor — so the
     * edited movement replays through the pad. See [lanesToTake].
     */
    fun setTake(x: KeyframeEvaluator, y: KeyframeEvaluator, samplesPerSecond: Double = 60.0) =
        setTake(lanesToTake(x, y, samplesPerSecond))

    internal companion object {
        const val PEN_STRIDE = 7
        val PEN_FIELDS = listOf("pressure", "tiltX", "tiltY", "twist")
    }
}

/**
 * One sample of a [gesture] take with what the pointer reported besides its position, as the
 * browser's `PointerEvent` gives it: [pressure] 0 to 1 (a mouse with a button down reports 0.5),
 * [tiltX] and [tiltY] the pen's lean in degrees toward +x (right) and toward the user (down the
 * screen), -90 to 90, and [twist] its turn about its own axis, 0 to 359 degrees. A take recorded
 * without `pen` has `NaN` pressure and no tilt or twist.
 */
data class PenSample(
    val t: Double, val x: Double, val y: Double,
    val pressure: Double = Double.NaN, val tiltX: Double = 0.0, val tiltY: Double = 0.0, val twist: Double = 0.0,
)

/**
 * Adds an XY pad that records what you do with it — a gesture you can loop.
 *
 * Drag on the pad and [onUpdate] gets each position, as [vec2Control] would. **Shift-drag** records a
 * take: the whole stroke, with its timing, kept with the box like any other control value. Replay it
 * from the box's own clock:
 *
 * ```kotlin
 * var p = Vector2f(0f, 0f)
 * lateinit var pad: GestureHandle
 * controlGroup("gesture") {
 *     pad = gesture("move") { p = it }
 * }
 * val start = System.nanoTime()
 * loop {
 *     val t = (System.nanoTime() - start) / 1e9
 *     pad.at(t)?.let { p = it }
 *     pad.playhead(t)
 * }
 * ```
 *
 * @param label text shown above the pad
 * @param xRange value at the left and right edges
 * @param yRange value at the bottom and top edges
 * @param initialValue live position used when no cached value exists
 * @param initialTake a take to start from, as (seconds, position) pairs in time order; a recorded
 *   take replaces it and is what a rerun restores
 * @param size width and height of the pad in pixels
 * @param pen record what the pointer reports besides its position — pressure, tilt and twist, as a
 *   pen or stylus gives them ([GestureHandle.penSamples]) — with each sample of a take
 * @param onUpdate called with the position as you drag
 */
fun ControlGroupBuilder.gesture(
    label: String,
    xRange: Pair<Double, Double> = -1.0 to 1.0,
    yRange: Pair<Double, Double> = -1.0 to 1.0,
    initialValue: Vector2f = Vector2f(0f, 0f),
    initialTake: List<Pair<Double, Vector2f>> = emptyList(),
    size: Int = 240,
    pen: Boolean = false,
    onUpdate: (Vector2f) -> Unit
): GestureHandle {
    val (x0, x1) = xRange
    val (y0, y1) = yRange
    // A take is (t, x, y) triples — with `pen`, (t, x, y, pressure, tiltX, tiltY, twist) sevens: t
    // from 0 and never decreasing, x and y on the pad, the pen's values in PointerEvent's ranges, at
    // most 4096 samples.
    val k = if (pen) GestureHandle.PEN_STRIDE else 3
    val sanitize = { values: List<Double> ->
        val n = minOf(values.size / k, 4096)
        val t0 = values.getOrElse(0) { 0.0 }
        var last = 0.0
        List(n * k) { i ->
            val v = values[i]
            when (i % k) {
                0 -> maxOf(last, v - t0).also { last = it }
                1 -> v.coerceIn(minOf(x0, x1), maxOf(x0, x1))
                2 -> v.coerceIn(minOf(y0, y1), maxOf(y0, y1))
                3 -> if (v.isFinite()) v.coerceIn(0.0, 1.0) else 0.5
                4, 5 -> if (v.isFinite()) v.coerceIn(-90.0, 90.0) else 0.0
                else -> if (v.isFinite()) ((v % 360.0) + 360.0) % 360.0 else 0.0
            }
        }
    }
    val flatInitial = initialTake.flatMap { (t, v) ->
        listOf(t, v.x.toDouble(), v.y.toDouble()) + if (pen) listOf(0.5, 0.0, 0.0, 0.0) else emptyList()
    }
    var restoredTake: List<Double> = sanitize(flatInitial)
    lateinit var handle: GestureHandle
    val value = addControl(
        "gesture",
        label,
        build = { id, previous ->
            previous?.text?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.takeIf { it.size >= 2 * k && it.size % k == 0 }
                ?.let { restoredTake = sanitize(it) }
            val x = previous?.x ?: initialValue.x.toDouble()
            val y = previous?.y ?: initialValue.y.toDouble()
            registerVec2Callback(id, onUpdate)
            arrayCallbacks.register(id, { take: List<Double> -> handle.replace(sanitize(take)) })
            if (previous?.x != null) onUpdate(Vector2f(x.toFloat(), y.toFloat()))
            val html = controlShell(
                "gesture", id, label,
                """data-x-min="$x0" data-x-max="$x1" data-y-min="$y0" data-y-max="$y1" data-x="$x" data-y="$y"${if (pen) " data-stride=\"7\"" else ""} data-values="${encodeValues(restoredTake)}"""",
                polarSurface("gesture-visual", size)
            )
            html to ControllerControl(
                id = id, type = "gesture", label = label, text = encodeValues(restoredTake),
                xMin = x0, xMax = x1, yMin = y0, yMax = y1, x = x, y = y,
                columns = if (pen) GestureHandle.PEN_STRIDE else 0
            )
        },
        read = { c -> c.text?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } },
        write = { old, next -> old.copy(text = encodeValues(sanitize(next))) }
    )
    handle = GestureHandle(withPlayhead(value), restoredTake, sanitize, pen, xRange, yRange)
    return handle
}
