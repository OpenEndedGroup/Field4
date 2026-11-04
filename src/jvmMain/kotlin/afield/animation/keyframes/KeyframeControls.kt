package afield.animation.keyframes

import afield.app.protocol.ControllerControl
import afield.document.graph.Box
import afield.math.linalg.Vector2f
import afield.ui.output.lastControlGroupValue
import afield.math.linalg.Vector4f
import afield.ui.controls.ControlLaw
import afield.ui.controls.defaultSignificantDigits
import afield.ui.output.ControlGroupBuilder
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * One keyframed control declared as a delegated local:
 *
 * ```kotlin
 * val keys = KeyframeScope()
 * var size by keys.slider(0.4, 0.1..1.0)
 * keys.onChange { draw() }
 * controlGroup("look") { keys.controls(); presetBar(keys) }
 * ```
 *
 * The property's name is the key name and, unless `label` is given, the control's label. Declaring
 * the property registers the key target straight away, so `keys.capture()` sees it before any control
 * exists; `keys.controls()` then builds the visible control with the matching `keyframe*` helper
 * (`keyframeSlider`, `keyframeVec2Control`, ...).
 *
 * When the box runs again the local already holds what the panel last showed, from the line that
 * declares it: the declaration reads the running box's control-group cache (the one `controlGroup`
 * restores from), clamped to the slider's range. A first run, a renamed local, or a label shown in two
 * channels of the box starts from the initializer, and `controls()` restores the last case.
 *
 * Who changes the value decides what else happens. Dragging the control, or applying a key (a preset
 * recall, a morph, `key()`), writes the local, moves the control and calls the scope's [KeyframeScope.onChange]
 * listeners. Assigning the local in code (`size = 0.8`) moves the control but calls no listener, so a
 * listener that assigns cannot loop.
 */
class KeyframeControl<T> internal constructor(
    private val scope: KeyframeScope,
    initial: T,
    private val explicitLabel: String?,
    private val copy: (T) -> T,
    private val controlType: String,
    private val restore: (ControllerControl) -> T?,
    private val register: KeyframeScope.(name: String, get: () -> T, set: (T) -> Unit) -> Unit,
    private val build: ControlGroupBuilder.(control: KeyframeControl<T>) -> ((T) -> Unit)
) : ReadWriteProperty<Any?, T> {
    /** The key name — the delegated property's name, once declared. */
    var name: String = ""
        private set

    /** The control's label: the `label` argument, or the property name. */
    val label: String get() = explicitLabel ?: name

    /** The current value; the same thing reading the delegated local returns. */
    var value: T = copy(initial)
        internal set

    private var mirror: ((T) -> Unit)? = null

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): KeyframeControl<T> {
        name = property.name
        val box = Box.context.get()
        if (box != null) {
            val ordinal = scope.declaredControls().count { it.name != name && it.controlType == controlType && it.label == label }
            lastControlGroupValue(box, controlType, label, ordinal)?.let(restore)?.let { value = copy(it) }
        }
        scope.declareControl(this)
        scope.register(name, { value }) { fromKeyOrGesture(it) }
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = copy(value)
        mirror?.invoke(this.value)
    }

    /** A key applying, or a hand moving the control: write, mirror, tell the listeners. */
    internal fun fromKeyOrGesture(v: T) {
        value = copy(v)
        mirror?.invoke(value)
        scope.fireControlChange(name)
    }

    /** A gesture on the control itself: the control already shows [v]. */
    internal fun fromControl(v: T) {
        // A rerun's controls() hands back the value the declaration already restored: not a change.
        if (v == value) return
        value = copy(v)
        scope.fireControlChange(name)
    }

    internal fun buildInto(builder: ControlGroupBuilder) {
        mirror = builder.build(this)
        // The keyframe* helper re-registered the key with the control's own callback; a key applying
        // is not a gesture, so point the key back at this delegate.
        scope.register(name, { value }) { fromKeyOrGesture(it) }
    }
}

/**
 * A keyframed `Double` slider as a delegated local: `var size by keys.slider(0.4, 0.1..1.0)`. The
 * range, [step] and [law] are the ones [keyframeSlider] takes; [label] defaults to the property name.
 */
fun KeyframeScope.slider(
    initial: Double,
    range: ClosedFloatingPointRange<Double> = 0.0..1.0,
    label: String? = null,
    step: Double = 0.01,
    law: ControlLaw = ControlLaw.LINEAR,
    lawExponent: Double = 2.0,
    lawCenter: Double = (range.start + range.endInclusive) / 2,
    significantDigits: Int = defaultSignificantDigits(law),
): KeyframeControl<Double> = KeyframeControl(
    this, initial, label, { it }, "slider", { stored -> stored.value?.coerceIn(range.start, range.endInclusive) },
    register = { n, get, set -> doubleTarget(n, get, set) },
    build = { c ->
        val handle = keyframeSlider(
            this@slider, c.name, c.label, range.start, range.endInclusive, step, law, lawExponent, lawCenter,
            significantDigits, get = { c.value }, set = { c.fromControl(it) }
        )
        val mirror: (Double) -> Unit = { handle.set(it) }
        mirror
    }
)

/** [slider] for a `Float` local, through [keyframeFloatSlider]: `var gain by keys.slider(0.35f, 0.0..4.0)`. */
fun KeyframeScope.slider(
    initial: Float,
    range: ClosedFloatingPointRange<Double> = 0.0..1.0,
    label: String? = null,
    step: Double = 0.01,
    law: ControlLaw = ControlLaw.LINEAR,
    lawExponent: Double = 2.0,
    lawCenter: Double = (range.start + range.endInclusive) / 2,
    significantDigits: Int = defaultSignificantDigits(law),
): KeyframeControl<Float> = KeyframeControl(
    this, initial, label, { it }, "slider", { stored -> stored.value?.coerceIn(range.start, range.endInclusive)?.toFloat() },
    register = { n, get, set -> floatTarget(n, get, set) },
    build = { c ->
        val handle = keyframeFloatSlider(
            this@slider, c.name, c.label, range.start, range.endInclusive, step, law, lawExponent, lawCenter,
            significantDigits, get = { c.value }, set = { c.fromControl(it) }
        )
        val mirror: (Float) -> Unit = { handle.set(it.toDouble()) }
        mirror
    }
)

/** A keyframed checkbox as a delegated `Boolean` local, through [keyframeCheckbox]. */
fun KeyframeScope.checkbox(initial: Boolean, label: String? = null): KeyframeControl<Boolean> = KeyframeControl(
    this, initial, label, { it }, "checkbox", { stored -> stored.checked },
    register = { n, get, set -> booleanTarget(n, get, set) },
    build = { c ->
        val handle = keyframeCheckbox(this@checkbox, c.name, c.label, get = { c.value }, set = { c.fromControl(it) })
        val mirror: (Boolean) -> Unit = { handle.set(it) }
        mirror
    }
)

/** A keyframed XY pad as a delegated `Vector2f` local, through [keyframeVec2Control]. */
fun KeyframeScope.vec2(
    initial: Vector2f,
    xRange: ClosedFloatingPointRange<Double> = -1.0..1.0,
    yRange: ClosedFloatingPointRange<Double> = -1.0..1.0,
    label: String? = null,
    step: Double = 0.01,
): KeyframeControl<Vector2f> = KeyframeControl(
    this, initial, label, { Vector2f(it) }, "vec2",
    { stored -> if (stored.x == null && stored.y == null) null else Vector2f((stored.x ?: initial.x.toDouble()).toFloat(), (stored.y ?: initial.y.toDouble()).toFloat()) },
    register = { n, get, set -> vector2Target(n, get, set) },
    build = { c ->
        val handle = keyframeVec2Control(
            this@vec2, c.name, c.label, xRange.start to xRange.endInclusive, yRange.start to yRange.endInclusive, step,
            get = { c.value }, set = { c.fromControl(it) }
        )
        val mirror: (Vector2f) -> Unit = { handle.set(it) }
        mirror
    }
)

/** A keyframed RGBA colour picker as a delegated `Vector4f` local, through [keyframeColorPicker]. */
fun KeyframeScope.color(initial: Vector4f, label: String? = null): KeyframeControl<Vector4f> = KeyframeControl(
    this, initial, label, { Vector4f(it) }, "color",
    { stored ->
        if (stored.r == null && stored.g == null && stored.b == null && stored.a == null) null
        else Vector4f(
            (stored.r ?: initial.x.toDouble()).toFloat(), (stored.g ?: initial.y.toDouble()).toFloat(),
            (stored.b ?: initial.z.toDouble()).toFloat(), (stored.a ?: initial.w.toDouble()).toFloat()
        )
    },
    register = { n, get, set -> vector4Target(n, get, set) },
    build = { c ->
        val handle = keyframeColorPicker(this@color, c.name, c.label, get = { c.value }, set = { c.fromControl(it) })
        val mirror: (Vector4f) -> Unit = { handle.set(it) }
        mirror
    }
)

/** A keyframed text field as a delegated `String` local, through [keyframeText]. Keys step between strings. */
fun KeyframeScope.text(initial: String, label: String? = null): KeyframeControl<String> = KeyframeControl(
    this, initial, label, { it }, "string", { stored -> stored.text },
    register = { n, get, set -> stringTarget(n, get, set) },
    build = { c ->
        val handle = keyframeText(this@text, c.name, c.label, get = { c.value }, set = { c.fromControl(it) })
        val mirror: (String) -> Unit = { handle.set(it) }
        mirror
    }
)

/** What `keys.controls()` inside a `controlGroup { }` does: build every declared control, in order. */
internal fun addKeyframeControls(builder: ControlGroupBuilder, scope: KeyframeScope) {
    scope.declaredControls().forEach { it.buildInto(builder) }
}
