package afield.ui.output

import afield.animation.gesture.reduceTakeIndices
import afield.animation.keyframes.Keyframe
import afield.animation.keyframes.KeyframeEvaluator
import afield.animation.keyframes.KeyframeScope
import afield.animation.keyframes.keyframes
import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.ControllerControl
import afield.app.server.broadcastControllerControlUpdates
import afield.audio.synth.MidiInput
import afield.document.graph.Box
import afield.document.workspace.FileManager
import afield.ui.controls.ControlLaw
import afield.ui.controls.ControlLawSpec
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector4f
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.midi.ShortMessage
import kotlin.io.path.exists

/*
 * Presets, recording and MIDI learn for `controlGroup` controls.
 *
 * A preset is a keyframer key: declare the controls with the `keyframe*` helpers (keyframer.kt), and
 * [presetBar] stores, recalls and blends `KeyframeScope` keys — saved with version history, browsable
 * in the keyframe collection browser. Recording and MIDI learn address a control by its *control key*,
 * `type/label#ordinal` (`slider/speed#0`): the label-derived part of the id a control group already
 * gives each control, so a key names the same control after a rerun, a restart, and on the tablet.
 */

// ------------------------------------------------------------------------
// Moving live controls from code (recording playback, knobs)

/** The kinds of control a knob or a recording can move. */
private val valueControlTypes = setOf("slider", "vec2", "color", "checkbox", "string")

/**
 * Applies [changes] (control key -> new record, or null to leave it) to [box]'s [channel] controls,
 * shows them, and calls each changed control's callback as if a person had moved it. Only the
 * in-memory rerun cache moves: a knob or a replay makes many changes a second. Returns the keys changed.
 */
internal fun applyControlChanges(box: Box, channel: String, changes: Map<String, (ControllerControl) -> ControllerControl?>): List<String> {
    val changed = mutableListOf<String>()
    val document = box.takeIf { it.isInsideInitialized() }?.inside?.filename
    controlGroupControlsWithKeys(box, channel).forEach { (key, control) ->
        val next = changes[key]?.invoke(control) ?: return@forEach
        if (next == control) return@forEach
        val updated = updateControllerControlValueProgrammatically(control.id) { next }
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

private fun clampTo(v: Double, a: Double, b: Double) = v.coerceIn(minOf(a, b), maxOf(a, b))

/** The control key for the control labelled [label] (of a movable kind) in [box]'s [channel], or null. */
internal fun controlKeyForLabel(box: Box, channel: String, label: String): String? =
    controlGroupControlsWithKeys(box, channel).firstOrNull { it.second.label == label && it.second.type in valueControlTypes }?.first

// ------------------------------------------------------------------------
// Gesture listeners: every change a person makes, from the browser or the tablet

private val controlGestureListeners = CopyOnWriteArrayList<(Box, ControllerControl) -> Unit>()

internal fun notifyControlGestureListeners(box: Box, control: ControllerControl) {
    controlGestureListeners.forEach { runCatching { it(box, control) } }
}

internal fun addControlGestureListener(listener: (Box, ControllerControl) -> Unit) { controlGestureListeners += listener }
internal fun removeControlGestureListener(listener: (Box, ControllerControl) -> Unit) { controlGestureListeners -= listener }

// ------------------------------------------------------------------------
// Presets: keys of a KeyframeScope

/** The label of the blend slider [presetBar] adds. It is not a scope target, so no key holds it. */
const val presetMorphLabel = "preset morph"

/**
 * The keys a [presetBar] stores, in [directory]: one file per name, written by [KeyframeScope.saveKey]
 * so each store adds to that key's version history and the folder opens in `keyframeBrowser`.
 */
class PresetBar internal constructor(val scope: KeyframeScope, val directory: Path) {

    /** Saved key names, sorted. */
    val names: List<String> get() = scope.savedKeyNames(directory)

    /** Where key [name] lives. */
    fun path(name: String): Path = directory.resolve(name)

    /** Captures the scope as key [name] (a new version if it exists) and returns it. */
    fun store(name: String, note: String? = null): KeyframeScope.Key = scope.capture().save(path(name), note)

    /** Applies the latest save of [name]; false if there is none. */
    fun recall(name: String): Boolean {
        val p = path(name)
        if (!p.exists()) return false
        scope.loadKey(p)()
        return true
    }

    /**
     * Applies the blend at [t] (`0..1`) along [between], in order — every saved key when empty — via
     * [KeyframeScope.interpolateAlong]. Returns the applied key, or null with fewer than two keys.
     */
    fun morph(t: Double, between: List<String> = emptyList()): KeyframeScope.Key? {
        val chosen = between.ifEmpty { names }.filter { path(it).exists() }
        if (chosen.size < 2) return null
        return scope.interpolateAlong(chosen.map { scope.loadKey(path(it)) }, t).also { it() }
    }
}

/**
 * A preset strip for the controls of [scope]: a name field with **store** and **recall**, a button per
 * saved key, and a [presetMorphLabel] slider blending across [morphBetween] (every saved key, in
 * name order, when empty). Controls join by being declared with the `keyframe*` helpers on [scope].
 *
 * ```kotlin
 * val keys = KeyframeScope()
 * controlGroup("look") {
 *     keyframeSlider(keys, "size", "size", 0.0, 10.0, get = { size }, set = { size = it })
 *     presetBar(keys)
 * }
 * ```
 *
 * Keys are saved in [directory], by default `keys/<box id>/<channel>` in the document's files.
 */
fun ControlGroupBuilder.presetBar(
    scope: KeyframeScope,
    directory: Path? = null,
    morphBetween: List<String> = emptyList(),
    morph: Boolean = true
): PresetBar {
    val bar = PresetBar(scope, directory ?: FileManager.current().path("%document/keys/${ownerBox.id}/$ownerChannel"))
    val saved = bar.names
    var name = saved.lastOrNull() ?: "A"
    inlineRow {
        text("preset", initialValue = name) { name = it.trim().ifEmpty { "A" } }
        button("store preset", "store") { down -> if (down) bar.store(name) }
        button("recall preset", "recall") { down -> if (down) bar.recall(name) }
    }
    if (saved.isNotEmpty()) inlineRow {
        saved.forEach { key -> button("▸ $key", "▸ $key") { down -> if (down) bar.recall(key) } }
    }
    if (morph) slider(presetMorphLabel, 0.0, 1.0, 0.001, initialValue = 0.0) { t -> bar.morph(t, morphBetween) }
    return bar
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
    val key = controlKeyForLabel(this, channel, label) ?: return false
    val v = curve.evaluateValueAt(time)
    val change: (ControllerControl) -> ControllerControl? = { c ->
        when (c.type) {
            "vec2" -> c.copy(x = clampTo(v, c.xMin, c.xMax), y = clampTo(curveY?.evaluateValueAt(time) ?: v, c.yMin, c.yMax))
            "slider" -> c.copy(value = clampTo(v, c.min, c.max))
            else -> null
        }
    }
    return applyControlChanges(this, channel, mapOf(key to change)).isNotEmpty()
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

/** A hardware controller [cc] on MIDI [midiChannel] (0-based) bound to the control with control key [key]. */
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
        if (learning && owner === box && control.channel == channel && control.label != "MIDI learn" && control.type in valueControlTypes) {
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
        val key = controlKeyForLabel(box, channel, label) ?: return false
        writeBinding(MidiBinding(key, midiChannel, cc))
        return true
    }

    /** Removes the binding for the control labelled [label]. */
    fun unbind(label: String) {
        val key = controlKeyForLabel(box, channel, label) ?: return
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
        applyControlChanges(box, channel, targets.associateWith { { control: ControllerControl -> travelValue(control, t) } })
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
internal fun travelValue(control: ControllerControl, t: Double): ControllerControl? = when (control.type) {
    "slider" -> control.copy(
        value = ControlLawSpec(ControlLaw.fromWireName(control.law), control.min, control.max, control.lawExponent, control.lawCenter).valueAt(t)
    )
    "checkbox" -> control.copy(checked = t >= 0.5)
    "vec2" -> control.copy(x = control.xMin + (control.xMax - control.xMin) * t)
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
