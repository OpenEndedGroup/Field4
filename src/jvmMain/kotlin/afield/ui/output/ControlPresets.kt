package afield.ui.output

import afield.animation.gesture.reduceTakeIndices
import afield.animation.keyframes.Keyframe
import afield.animation.keyframes.KeyframeEvaluator
import afield.animation.keyframes.keyframes
import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.ControllerControl
import afield.app.server.broadcastControllerControlUpdates
import afield.audio.synth.MidiInput
import afield.document.graph.Box
import afield.ui.controls.ControlLaw
import afield.ui.controls.ControlLawSpec
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector4f
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.midi.ShortMessage

/*
 * Presets, morphing, recording and MIDI learn for `controlGroup` controls.
 *
 * Everything here addresses a control by its *preset key*, `type/label#ordinal` (`slider/speed#0`): the
 * label-derived part of the id a control group already gives each control, so a key names the same
 * control after the box runs again, after Field restarts, and on the tablet. Nothing needs declaring
 * per control: any slider, vec2, colour, checkbox or text in a group can be stored, morphed, recorded
 * or bound to a knob.
 */

// ------------------------------------------------------------------------
// Values

/** One stored control value. Numbers, points and colours morph; flags and text snap. */
sealed interface ControlValue {
    data class Num(val value: Double) : ControlValue
    data class Vec(val x: Double, val y: Double) : ControlValue
    data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double) : ControlValue
    data class Flag(val checked: Boolean) : ControlValue
    data class Text(val text: String) : ControlValue
}

/** The value [control] holds, or null for a control that has none worth storing (button, meter, ...). */
fun controlValueOf(control: ControllerControl): ControlValue? = when (control.type) {
    "slider" -> control.value?.let { ControlValue.Num(it) }
    "vec2" -> if (control.x != null && control.y != null) ControlValue.Vec(control.x!!, control.y!!) else null
    "color" -> if (control.r != null) ControlValue.Rgba(control.r!!, control.g ?: 0.0, control.b ?: 0.0, control.a ?: 1.0) else null
    "checkbox" -> control.checked?.let { ControlValue.Flag(it) }
    "string" -> control.text?.let { ControlValue.Text(it) }
    else -> null
}

/** [control] showing [value], clamped to its range; null when the kinds do not match. */
fun withControlValue(control: ControllerControl, value: ControlValue): ControllerControl? = when {
    control.type == "slider" && value is ControlValue.Num ->
        control.copy(value = value.value.coerceIn(minOf(control.min, control.max), maxOf(control.min, control.max)))
    control.type == "vec2" && value is ControlValue.Vec ->
        control.copy(
            x = value.x.coerceIn(minOf(control.xMin, control.xMax), maxOf(control.xMin, control.xMax)),
            y = value.y.coerceIn(minOf(control.yMin, control.yMax), maxOf(control.yMin, control.yMax))
        )
    control.type == "color" && value is ControlValue.Rgba ->
        control.copy(r = value.r.coerceIn(0.0, 1.0), g = value.g.coerceIn(0.0, 1.0), b = value.b.coerceIn(0.0, 1.0), a = value.a.coerceIn(0.0, 1.0))
    control.type == "checkbox" && value is ControlValue.Flag -> control.copy(checked = value.checked)
    control.type == "string" && value is ControlValue.Text -> control.copy(text = value.text)
    else -> null
}

/**
 * [a] at `f = 0` to [b] at `f = 1`. Numbers, points and colours move in a straight line; a flag or
 * text, which has no in-between, takes [b] from the halfway point. Mismatched kinds snap the same way.
 */
fun lerpControlValue(a: ControlValue, b: ControlValue, f: Double): ControlValue {
    fun l(x: Double, y: Double) = x + (y - x) * f
    return when {
        a is ControlValue.Num && b is ControlValue.Num -> ControlValue.Num(l(a.value, b.value))
        a is ControlValue.Vec && b is ControlValue.Vec -> ControlValue.Vec(l(a.x, b.x), l(a.y, b.y))
        a is ControlValue.Rgba && b is ControlValue.Rgba -> ControlValue.Rgba(l(a.r, b.r), l(a.g, b.g), l(a.b, b.b), l(a.a, b.a))
        else -> if (f < 0.5) a else b
    }
}

/**
 * The blend of [presets] at [t] in `0..1`: the presets sit evenly along `t` in order, and between two
 * neighbours each control is [lerpControlValue]d. A control only one neighbour stores keeps that value.
 */
fun morphControlValues(presets: List<Map<String, ControlValue>>, t: Double): Map<String, ControlValue> {
    if (presets.isEmpty()) return emptyMap()
    if (presets.size == 1) return presets[0]
    val s = t.coerceIn(0.0, 1.0) * (presets.size - 1)
    val i = s.toInt().coerceAtMost(presets.size - 2)
    val f = s - i
    val a = presets[i]
    val b = presets[i + 1]
    val out = LinkedHashMap<String, ControlValue>()
    (a.keys + b.keys).forEach { key ->
        val va = a[key]
        val vb = b[key]
        out[key] = when {
            va != null && vb != null -> lerpControlValue(va, vb, f)
            else -> va ?: vb!!
        }
    }
    return out
}

/** [value] as plain data for `metadata.edn`. */
fun controlValueToStored(value: ControlValue): Map<String, Any> = when (value) {
    is ControlValue.Num -> mapOf("value" to value.value)
    is ControlValue.Vec -> mapOf("x" to value.x, "y" to value.y)
    is ControlValue.Rgba -> mapOf("r" to value.r, "g" to value.g, "b" to value.b, "a" to value.a)
    is ControlValue.Flag -> mapOf("checked" to value.checked)
    is ControlValue.Text -> mapOf("text" to value.text)
}

/** The inverse of [controlValueToStored]; tolerant of strings and other numbers, null when unreadable. */
fun controlValueFromStored(stored: Map<*, *>): ControlValue? {
    fun n(k: String): Double? = when (val v = stored[k]) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    return when {
        stored.containsKey("text") -> stored["text"]?.toString()?.let { ControlValue.Text(it) }
        stored.containsKey("checked") -> when (val v = stored["checked"]) {
            is Boolean -> ControlValue.Flag(v)
            is String -> v.toBooleanStrictOrNull()?.let { ControlValue.Flag(it) }
            else -> null
        }
        stored.containsKey("r") -> ControlValue.Rgba(n("r") ?: return null, n("g") ?: 0.0, n("b") ?: 0.0, n("a") ?: 1.0)
        stored.containsKey("x") -> ControlValue.Vec(n("x") ?: return null, n("y") ?: return null)
        stored.containsKey("value") -> n("value")?.let { ControlValue.Num(it) }
        else -> null
    }
}

// ------------------------------------------------------------------------
// Applying values to live controls

/**
 * Shows [values] in [box]'s [channel] controls and calls each changed control's callback, as if a
 * person had set it. [durable] writes the rerun cache through the operation log (a recall); otherwise
 * only the in-memory cache moves (a morph, a knob, a replay — many changes a second).
 * Returns the keys that changed.
 */
internal fun applyControlValues(box: Box, channel: String, values: Map<String, ControlValue>, durable: Boolean): List<String> {
    val changed = mutableListOf<String>()
    val document = box.takeIf { it.isInsideInitialized() }?.inside?.filename
    controlGroupControlsWithKeys(box, channel).forEach { (key, control) ->
        val value = values[key] ?: return@forEach
        val next = withControlValue(control, value) ?: return@forEach
        if (next == control) return@forEach
        val updated = if (durable) updateControllerControlValue(control.id) { next }
        else updateControllerControlValueProgrammatically(control.id) { next }
        if (updated.isEmpty()) return@forEach
        document?.let { broadcastControllerControlUpdates(updated, it) }
        invokeControlCallback(next)
        changed += key
    }
    return changed
}

private fun invokeControlCallback(control: ControllerControl) {
    runCatching {
        when (control.type) {
            "slider" -> control.value?.let { getSliderCallback(control.id)?.invoke(it) }
            "vec2" -> getVec2Callback(control.id)?.invoke(Vector2f(control.x!!.toFloat(), control.y!!.toFloat()))
            "color" -> getColorCallback(control.id)?.invoke(
                Vector4f(control.r!!.toFloat(), control.g!!.toFloat(), control.b!!.toFloat(), control.a!!.toFloat())
            )
            "checkbox" -> control.checked?.let { getCheckboxCallback(control.id)?.invoke(it) }
            "string" -> control.text?.let { getStringCallback(control.id)?.invoke(it) }
        }
    }.onFailure { lprintln("error,controls", "control callback for '${control.label}' threw: ${it.message}") }
}

/** The preset key for the control labelled [label] (of any storable kind) in [box]'s [channel], or null. */
internal fun presetKeyForLabel(box: Box, channel: String, label: String): String? =
    controlGroupControlsWithKeys(box, channel).firstOrNull { it.second.label == label && controlValueOf(it.second) != null }?.first

// ------------------------------------------------------------------------
// Gesture listeners: every change a person makes, from the browser or the tablet

private val controlGestureListeners = CopyOnWriteArrayList<(Box, ControllerControl) -> Unit>()

internal fun notifyControlGestureListeners(box: Box, control: ControllerControl) {
    controlGestureListeners.forEach { runCatching { it(box, control) } }
}

internal fun addControlGestureListener(listener: (Box, ControllerControl) -> Unit) { controlGestureListeners += listener }
internal fun removeControlGestureListener(listener: (Box, ControllerControl) -> Unit) { controlGestureListeners -= listener }

// ------------------------------------------------------------------------
// Presets

internal const val controlPresetsProperty = "_controlPresets"

/** The label of the morph slider [presetBar] adds; never stored in a preset, or it would morph itself. */
const val presetMorphLabel = "preset morph"

/**
 * Named snapshots of one control group, saved with the box.
 *
 * ```kotlin
 * val presets = controlPresets("look")
 * presets.save("calm")
 * presets.recall("storm")
 * presets.morph(0.25, "calm", "storm")
 * ```
 *
 * A snapshot holds every slider, vec2, colour, checkbox and text in the group, except the morph slider
 * a [presetBar] adds. Recall and morph call each changed control's callback, so the box's variables
 * follow exactly as if the controls had been moved by hand.
 */
class ControlPresets internal constructor(val box: Box, val channel: String) {

    /** The group's current values, by preset key. */
    fun snapshot(): Map<String, ControlValue> {
        val out = LinkedHashMap<String, ControlValue>()
        controlGroupControlsWithKeys(box, channel).forEach { (key, control) ->
            if (control.label == presetMorphLabel) return@forEach
            controlValueOf(control)?.let { out[key] = it }
        }
        return out
    }

    /** Preset names, in the order they were first saved. */
    val names: List<String> get() = stored().keys.toList()

    /** The values stored under [name], or null. */
    operator fun get(name: String): Map<String, ControlValue>? = stored()[name]

    /** Stores the current values as [name] (replacing a preset of that name) and returns them. */
    fun save(name: String): Map<String, ControlValue> {
        val snap = snapshot()
        write(stored().also { it[name] = LinkedHashMap(snap) })
        return snap
    }

    /** Stores [values] as [name] without touching the controls — a preset written in code. */
    fun put(name: String, values: Map<String, ControlValue>) {
        write(stored().also { it[name] = LinkedHashMap(values) })
    }

    /** Shows preset [name] and calls the callbacks of the controls it changes. False if there is none. */
    fun recall(name: String): Boolean {
        val values = stored()[name] ?: return false
        applyControlValues(box, channel, values, durable = true)
        return true
    }

    /** Forgets [name]. */
    fun delete(name: String): Boolean {
        val all = stored()
        val removed = all.remove(name) != null
        if (removed) write(all)
        return removed
    }

    /** The blend at [t] (`0..1`) along [between], in order — every preset when empty; see [morphControlValues]. */
    fun blend(t: Double, between: List<String> = emptyList()): Map<String, ControlValue> {
        val all = stored()
        val chosen = (between.ifEmpty { all.keys.toList() }).mapNotNull { all[it] }
        return morphControlValues(chosen, t)
    }

    /** Shows [blend] at [t] and calls the callbacks it changes. */
    fun morph(t: Double, vararg between: String): List<String> =
        applyControlValues(box, channel, blend(t, between.toList()), durable = false)

    /** [morph] over a list of names. */
    fun morph(t: Double, between: List<String>): List<String> =
        applyControlValues(box, channel, blend(t, between), durable = false)

    private fun stored(): LinkedHashMap<String, LinkedHashMap<String, ControlValue>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, ControlValue>>()
        val all = box.properties[controlPresetsProperty] as? Map<*, *> ?: return out
        val forChannel = all[channel] as? Map<*, *> ?: return out
        forChannel.forEach { (name, values) ->
            val map = values as? Map<*, *> ?: return@forEach
            val decoded = LinkedHashMap<String, ControlValue>()
            map.forEach { (key, stored) -> (stored as? Map<*, *>)?.let(::controlValueFromStored)?.let { decoded[key.toString()] = it } }
            out[name.toString()] = decoded
        }
        return out
    }

    private fun write(presets: Map<String, Map<String, ControlValue>>) {
        val all = LinkedHashMap<String, Any?>()
        (box.properties[controlPresetsProperty] as? Map<*, *>)?.forEach { (k, v) -> all[k.toString()] = v }
        all[channel] = LinkedHashMap<String, Any>().apply {
            presets.forEach { (name, values) ->
                this[name] = LinkedHashMap<String, Any>().apply { values.forEach { (k, v) -> this[k] = controlValueToStored(v) } }
            }
        }
        box.setPropertyRaw(controlPresetsProperty, all, doResync = false)
    }
}

/** Presets for the controls in [channel]; see [ControlPresets]. */
fun Box.controlPresets(channel: String = "controls"): ControlPresets = ControlPresets(this, channel)

/**
 * Adds a preset strip to this group: a name field with **store** and **recall** buttons, a button per
 * preset already saved, and a [presetMorphLabel] slider that blends across [morphBetween] (every saved
 * preset, in order, when empty — so a preset stored a moment ago joins the morph at once).
 *
 * ```kotlin
 * controlGroup("look") {
 *     slider("size", 0.0, 1.0) { size = it }
 *     colorPicker("tint") { tint = it }
 *     presetBar()
 * }
 * ```
 *
 * Call it last, so the presets see the controls declared above it.
 */
fun ControlGroupBuilder.presetBar(morphBetween: List<String> = emptyList(), morph: Boolean = true) {
    val presets = ownerBox.controlPresets(ownerChannel)
    var name = presets.names.lastOrNull() ?: "A"
    text("preset", initialValue = name) { name = it.trim().ifEmpty { "A" } }
    button("store preset") { down -> if (down) presets.save(name) }
    button("recall preset") { down -> if (down) presets.recall(name) }
    presets.names.forEach { saved -> button("▸ $saved") { down -> if (down) presets.recall(saved) } }
    if (morph) slider(presetMorphLabel, 0.0, 1.0, 0.001, initialValue = 0.0) { t ->
        if (presets.names.size >= 2) presets.morph(t, morphBetween)
    }
}

// ------------------------------------------------------------------------
// Recording a control into keyframes

/**
 * A take of one control: every change a person makes to it while armed, with its time.
 * [stop] bakes the take into a `keyframes(...)` editor named [curveName], thinned so no key strays
 * more than [tolerance] (a fraction of the control's range) from what was played.
 */
class ControlRecording internal constructor(
    val box: Box,
    val channel: String,
    val label: String,
    val curveName: String,
    val tolerance: Double
) {
    private val samples = CopyOnWriteArrayList<Pair<Long, ControllerControl>>()
    @Volatile var armed = true
        private set

    private val listener: (Box, ControllerControl) -> Unit = { owner, control ->
        if (armed && owner === box && control.channel == channel && control.label == label) {
            samples += System.nanoTime() to control
        }
    }

    init { addControlGestureListener(listener) }

    /** Number of changes captured so far. */
    val sampleCount: Int get() = samples.size

    /** Adds a sample by hand — what a test, or code generating a gesture, uses in place of a hand. */
    fun capture(atSeconds: Double, value: Double) {
        val control = controlGroupControlsWithKeys(box, channel).firstOrNull { it.second.label == label }?.second ?: return
        samples += (atSeconds * 1e9).toLong() to control.copy(value = value, x = value)
    }

    /** The take so far as keys (time in seconds from the first change). A vec2 gives `x`; use [keysY] for `y`. */
    fun keys(): List<Keyframe> = bake { it.value ?: it.x ?: 0.0 }

    /** The `y` lane of a vec2 take. */
    fun keysY(): List<Keyframe> = bake { it.y ?: 0.0 }

    private fun bake(pick: (ControllerControl) -> Double): List<Keyframe> {
        val s = samples.toList().sortedBy { it.first }
        if (s.isEmpty()) return emptyList()
        val t0 = s.first().first
        val range = s.first().second.let { (if (it.type == "vec2") it.xMax - it.xMin else it.max - it.min).let { r -> if (r == 0.0) 1.0 else kotlin.math.abs(r) } }
        val take = DoubleArray(s.size * 3)
        s.forEachIndexed { i, (t, c) ->
            take[i * 3] = (t - t0) / 1e9
            take[i * 3 + 1] = pick(c)
            take[i * 3 + 2] = pick(c)
        }
        return reduceTakeIndices(take, tolerance * range).map { Keyframe(take[it * 3], take[it * 3 + 1], "linear") }
    }

    /** Disarms and writes the take into the curve [curveName] (and `$curveName.y` for a vec2). */
    fun stop(): KeyframeEvaluator {
        armed = false
        removeControlGestureListener(listener)
        val curve = box.keyframes(name = curveName)
        val keys = keys()
        if (keys.isNotEmpty()) curve.setKeys(keys)
        val first = samples.firstOrNull()?.second
        if (first?.type == "vec2") box.keyframes(name = "$curveName.y").also { y -> keysY().takeIf { it.isNotEmpty() }?.let(y::setKeys) }
        return curve
    }
}

/** Arms a recording of the control labelled [label] in [channel]; see [ControlRecording]. */
fun Box.recordControl(label: String, channel: String = "controls", curveName: String = label, tolerance: Double = 0.005): ControlRecording =
    ControlRecording(this, channel, label, curveName, tolerance)

/**
 * Plays [curve] at [time] into the control labelled [label]: the control moves and its callback runs.
 * Call it from an animation loop. A vec2 also needs [curveY].
 */
fun Box.playControl(label: String, curve: KeyframeEvaluator, time: Double, channel: String = "controls", curveY: KeyframeEvaluator? = null): Boolean {
    val key = presetKeyForLabel(this, channel, label) ?: return false
    val v = curve.evaluateValueAt(time)
    val value = if (key.startsWith("vec2/")) ControlValue.Vec(v, curveY?.evaluateValueAt(time) ?: v) else ControlValue.Num(v)
    return applyControlValues(this, channel, mapOf(key to value), durable = false).isNotEmpty()
}

/**
 * A record button for the control labelled [label]: press it, move the control, press it again, and
 * the take lands in the keyframe curve [curveName] (opened below), ready to edit and to [playControl]
 * back. A button rather than a checkbox because a checkbox's state is restored when the box runs again,
 * which would arm a recording nobody asked for.
 */
fun ControlGroupBuilder.recordToggle(label: String, curveName: String = label, tolerance: Double = 0.005) {
    val box = ownerBox
    val channel = ownerChannel
    val key = "${box.id}\u0000$channel\u0000$label"
    button("● rec $label") { down ->
        if (!down) return@button
        val running = activeRecordings.remove(key)
        if (running == null) {
            activeRecordings[key] = box.recordControl(label, channel, curveName, tolerance)
            lprintln("controls", "recording '$label' — press again to stop")
        } else {
            running.stop()
            lprintln("controls", "recorded '$label': ${running.sampleCount} changes → ${running.keys().size} keys in '$curveName'")
        }
    }
}

private val activeRecordings = ConcurrentHashMap<String, ControlRecording>()

// ------------------------------------------------------------------------
// MIDI learn

internal const val controlMidiProperty = "_controlMidi"

/** A hardware controller [cc] on MIDI [midiChannel] (0-based) bound to the control with preset [key]. */
data class MidiBinding(val key: String, val midiChannel: Int, val cc: Int)

/**
 * Knob bindings for one control group, saved with the box.
 *
 * [learn] arms: the next control you touch and the next controller you turn — in either order — are
 * bound. A bound CC moves the control along its own travel (a log slider stays log under the knob),
 * a checkbox flips at the knob's midpoint. [attach] a [MidiInput] to hear hardware; [feed] is the same
 * entry for anything else that produces controller values (RTP-MIDI, OSC, a test).
 */
class ControlMidiMap internal constructor(val box: Box, val channel: String) {
    @Volatile private var learning = false
    @Volatile private var pendingKey: String? = null
    @Volatile private var pendingCc: Pair<Int, Int>? = null
    private val inputs = CopyOnWriteArrayList<MidiInput>()

    /** True while waiting for a control and a CC to bind. */
    val isLearning: Boolean get() = learning

    /** The current bindings. */
    val bindings: List<MidiBinding>
        get() {
            val stored = (box.properties[controlMidiProperty] as? Map<*, *>)?.get(channel) as? Map<*, *> ?: return emptyList()
            return stored.mapNotNull { (key, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                val ch = (m["channel"] as? Number)?.toInt() ?: return@mapNotNull null
                val cc = (m["cc"] as? Number)?.toInt() ?: return@mapNotNull null
                MidiBinding(key.toString(), ch, cc)
            }
        }

    private val listener: (Box, ControllerControl) -> Unit = { owner, control ->
        if (learning && owner === box && control.channel == channel && control.label != "MIDI learn" && controlValueOf(control) != null) {
            pendingKey = controlGroupControlsWithKeys(box, channel).firstOrNull { it.second.id == control.id }?.first
            tryBind()
        }
    }

    init { addControlGestureListener(listener) }

    /** Arms learning; see the class doc. */
    fun learn() {
        learning = true
        pendingKey = null
        pendingCc = null
    }

    /** Disarms without binding. */
    fun cancel() { learning = false }

    /** Binds [cc] on [midiChannel] to the control labelled [label], in code. */
    fun bind(label: String, cc: Int, midiChannel: Int = 0): Boolean {
        val key = presetKeyForLabel(box, channel, label) ?: return false
        writeBinding(MidiBinding(key, midiChannel, cc))
        return true
    }

    /** Removes the binding for the control labelled [label]. */
    fun unbind(label: String) {
        val key = presetKeyForLabel(box, channel, label) ?: return
        write(bindings.filterNot { it.key == key })
    }

    /** A controller value arriving: learns it if armed, then drives whatever it is bound to. */
    fun feed(midiChannel: Int, cc: Int, value: Int) {
        if (learning) {
            pendingCc = midiChannel to cc
            if (tryBind()) return
        }
        val t = value.coerceIn(0, 127) / 127.0
        val targets = bindings.filter { it.midiChannel == midiChannel && it.cc == cc }.map { it.key }.toSet()
        if (targets.isEmpty()) return
        val values = LinkedHashMap<String, ControlValue>()
        controlGroupControlsWithKeys(box, channel).forEach { (key, control) ->
            if (key !in targets) return@forEach
            travelValue(control, t)?.let { values[key] = it }
        }
        applyControlValues(box, channel, values, durable = false)
    }

    /** Listens to hardware [input]; detach by closing it. */
    fun attach(input: MidiInput) {
        if (inputs.any { it === input }) return
        inputs += input
        input.onMessage { message, _ ->
            if (message is ShortMessage && message.command == ShortMessage.CONTROL_CHANGE) feed(message.channel, message.data1, message.data2)
        }
    }

    private fun tryBind(): Boolean {
        val key = pendingKey ?: return false
        val (ch, cc) = pendingCc ?: return false
        writeBinding(MidiBinding(key, ch, cc))
        learning = false
        pendingKey = null
        pendingCc = null
        lprintln("controls", "MIDI learn: CC $cc on channel ${ch + 1} → $key")
        return true
    }

    private fun writeBinding(binding: MidiBinding) {
        // One knob per control and one control per knob: a re-learn moves the binding.
        write(bindings.filterNot { it.key == binding.key || (it.cc == binding.cc && it.midiChannel == binding.midiChannel) } + binding)
    }

    private fun write(list: List<MidiBinding>) {
        val all = LinkedHashMap<String, Any?>()
        (box.properties[controlMidiProperty] as? Map<*, *>)?.forEach { (k, v) -> all[k.toString()] = v }
        all[channel] = LinkedHashMap<String, Any>().apply { list.forEach { this[it.key] = mapOf("channel" to it.midiChannel, "cc" to it.cc) } }
        box.setPropertyRaw(controlMidiProperty, all, doResync = false)
    }
}

/** [control] at travel [t] (`0..1`), as a knob would place it. */
internal fun travelValue(control: ControllerControl, t: Double): ControlValue? = when (control.type) {
    "slider" -> ControlValue.Num(
        ControlLawSpec(ControlLaw.fromWireName(control.law), control.min, control.max, control.lawExponent, control.lawCenter).valueAt(t)
    )
    "checkbox" -> ControlValue.Flag(t >= 0.5)
    "vec2" -> ControlValue.Vec(control.xMin + (control.xMax - control.xMin) * t, control.y ?: 0.0)
    else -> null
}

private val midiMaps = ConcurrentHashMap<String, ControlMidiMap>()

/** The MIDI map for [channel], one per box and channel however often the box runs; see [ControlMidiMap]. */
fun Box.midiLearn(channel: String = "controls"): ControlMidiMap =
    midiMaps.computeIfAbsent("$id\u0000$channel") { ControlMidiMap(this, channel) }

/** A **MIDI learn** button: press it, touch a control, turn a knob. */
fun ControlGroupBuilder.midiLearnButton(input: MidiInput? = null) {
    val map = ownerBox.midiLearn(ownerChannel)
    input?.let(map::attach)
    button("MIDI learn") { down -> if (down) map.learn() }
}
