@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.ToHTML
import afield.four.util.Camera
import afield.four.util.CameraPose
import afield.four.util.applyPose
import afield.four.util.pose
import afield.h
import afield.four.buffers.Structure
import afield.four.modules.ComputeModulePointer
import afield.four.modules.MeshModulePointer
import afield.four.modules.ModulePointer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import afield.stdlib.linalg.DualQuaternionf
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Snapshot, interpolate and persist the live state of a running sketch. A [KeyframeScope] is a set
 * of named targets — uniform `Structure` bindings, the uniform structures of a `ModulePointer` /
 * `ComputeModulePointer` / `MeshModulePointer`, a stackAuto shader-source slot via
 * [KeyframeScope.stackAuto], or a `Camera` pose via [KeyframeScope.camera]. `capture()` reads them
 * all into a [KeyframeScope.Key]; invoking that `Key` writes them all back.
 *
 * [KeyframeScope.interpolate] blends two keys per value type: numbers and vectors lerp, quaternions
 * slerp, dual quaternions and `CameraPose` sclerp, and strings / [StackSLString] /
 * `SourceBackedBlock` step to the right-hand value at `t >= 1`. `remap` can re-time an individual
 * key's `t`.
 *
 * Persistence goes through the [KValue] serial union, so only the types [KValue.fromAny] recognises
 * survive a save — numbers, vec2/3/4, quaternion, dual quaternion, camera pose, string, StackSL
 * source and source-backed block; anything else is silently dropped. Every `saveKey` also writes a
 * numbered copy into a `<file>.versions` directory with a manifest, which
 * [KeyframeScope.keyVersions] lists and [KeyframeScope.loadLatestKey] follows. [KeyframeScope.missingKey] supplies defaults for
 * keys absent from an older file, optionally per schema version.
 *
 * Keys render themselves as HTML for box output ([KeyDiff], `diffHtml`), and
 * `copyUpdateCodeToClipboard` emits pasteable Kotlin — that one goes through the AWT clipboard.
 */

/**
 * Which Kotlin numeric type a saved number came from, so that [KValue.toAny] can hand back a
 * `Byte`/`Short`/`Int`/`Long`/`Float`/`Double` rather than always a `Double` — a uniform bound to an
 * `Int` has to come back as an `Int`.
 *
 * Every number is stored on the wire as a `Double` regardless of kind, so a `Long` beyond 2^53 does
 * not survive a save/load round trip intact.
 */
@Serializable
enum class NumKind { BYTE, SHORT, INT, LONG, FLOAT, DOUBLE }

/**
 * The closed set of value types a [KeyframeScope.Key] can *persist*: numbers, vec2/3/4, quaternion,
 * dual quaternion, camera pose, string, StackSL source and source-backed block. A key in memory holds
 * plain Kotlin values; this is the serial union they are converted to on the way to disk and back.
 *
 * The set is closed and failure is quiet: [fromAny] returns null for anything it does not recognise
 * and `saveKey` simply drops that entry, so a key that captured, say, a `Boolean` or a list will load
 * back one value short with no warning anywhere.
 */
@Serializable
sealed interface KValue {
    /** Any Kotlin number, kept as a `Double` with the [NumKind] needed to restore its original type. */
    @Serializable @SerialName("num")
    data class Num(val kind: NumKind, val value: Double) : KValue

    /** A `Vector2f`, component by component. */
    @Serializable @SerialName("vec2")
    data class Vec2(val x: Float, val y: Float) : KValue

    /** A `Vector3f`, component by component. */
    @Serializable @SerialName("vec3")
    data class Vec3(val x: Float, val y: Float, val z: Float) : KValue

    /** A `Vector4f`, component by component — the usual RGBA or homogeneous-point uniform. */
    @Serializable @SerialName("vec4")
    data class Vec4(val x: Float, val y: Float, val z: Float, val w: Float) : KValue

    /** A `Quaternionf` as raw `x, y, z, w`; stored as-is, and not re-normalised on load. */
    @Serializable @SerialName("quat")
    data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) : KValue

    /**
     * A `DualQuaternionf` — a rigid transform (rotation plus translation) as its real and dual
     * quaternion halves. Unlike [Quat] this *is* normalised on the way back in, so a slightly drifted
     * saved value is repaired on load.
     */
    @Serializable @SerialName("dualquat")
    data class DualQuat(
        val realX: Float,
        val realY: Float,
        val realZ: Float,
        val realW: Float,
        val dualX: Float,
        val dualY: Float,
        val dualZ: Float,
        val dualW: Float
    ) : KValue

    /**
     * A `CameraPose`: the camera's rigid transform as a dual quaternion, plus the focus distance that
     * says how far in front of it the thing it is looking at sits. Also normalised on load.
     */
    @Serializable @SerialName("cameraPose")
    data class CameraPoseValue(
        val realX: Float,
        val realY: Float,
        val realZ: Float,
        val realW: Float,
        val dualX: Float,
        val dualY: Float,
        val dualZ: Float,
        val dualW: Float,
        val focusDistance: Float
    ) : KValue

    /**
     * StackSL source text, tagged as code rather than as a string so that it reloads as a
     * [StackSLString] and interpolation steps it discretely instead of trying to blend it.
     */
    @Serializable @SerialName("stacksl")
    data class StackSL(val value: String) : KValue

    /** A plain `String`. */
    @Serializable @SerialName("string")
    data class Str(val value: String) : KValue

    /**
     * A `SourceBackedBlock` reduced to the box it came from and its Kotlin source — the *live lambda*
     * is not persisted. On load the block re-compiles its source in that box on first invocation, so a
     * key restored into a document where the box is gone throws when the value is finally called.
     */
    @Serializable @SerialName("sourceBlock")
    data class SourceBlock(val boxId: String, val source: String) : KValue

    /**
     * Rebuild the live Kotlin value this entry stands for — the load half of the round trip.
     * A [DualQuat] and a [CameraPoseValue] are re-normalised on the way out, because a rigid transform that
     * has drifted is a broken transform; a plain [Quat] deliberately is **not** (see its own note — it may be
     * standing in for something that is not a rotation). Numbers regain their [NumKind] type, and a
     * [SourceBlock] becomes a `SourceBackedBlock` that has not compiled anything yet.
     */
    fun toAny(): Any = when (this) {
        is Num -> when (kind) {
            NumKind.BYTE -> value.toInt().toByte()
            NumKind.SHORT -> value.toInt().toShort()
            NumKind.INT -> value.toInt()
            NumKind.LONG -> value.toLong()
            NumKind.FLOAT -> value.toFloat()
            NumKind.DOUBLE -> value
        }
        is Vec2 -> Vector2f(x, y)
        is Vec3 -> Vector3f(x, y, z)
        is Vec4 -> Vector4f(x, y, z, w)
        is Quat -> Quaternionf(x, y, z, w)
        is DualQuat -> DualQuaternionf(
            Quaternionf(realX, realY, realZ, realW),
            Quaternionf(dualX, dualY, dualZ, dualW)
        ).normalize()
        is CameraPoseValue -> CameraPose(
            DualQuaternionf(
                Quaternionf(realX, realY, realZ, realW),
                Quaternionf(dualX, dualY, dualZ, dualW)
            ).normalize(),
            focusDistance
        )
        is StackSL -> StackSLString(value)
        is Str -> value
        is SourceBlock -> SourceBackedBlock<Any?>(boxId, source)
    }

    companion object {
        /**
         * The save half of the round trip: the [KValue] for a live value, or **null** for `null` and
         * for any type outside the union — which is exactly how an unsupported value comes to be
         * dropped from a saved key without complaint.
         *
         * A `Number` that is none of the six primitive kinds is stored as a `DOUBLE`.
         */
        fun fromAny(v: Any?): KValue? = when (v) {
            null -> null
            is Byte -> Num(NumKind.BYTE, v.toDouble())
            is Short -> Num(NumKind.SHORT, v.toDouble())
            is Int -> Num(NumKind.INT, v.toDouble())
            is Long -> Num(NumKind.LONG, v.toDouble())
            is Float -> Num(NumKind.FLOAT, v.toDouble())
            is Double -> Num(NumKind.DOUBLE, v)
            is Number -> Num(NumKind.DOUBLE, v.toDouble())

            is Vector2f -> Vec2(v.x, v.y)
            is Vector3f -> Vec3(v.x, v.y, v.z)
            is Vector4f -> Vec4(v.x, v.y, v.z, v.w)
            is Quaternionf -> Quat(v.x, v.y, v.z, v.w)
            is DualQuaternionf -> DualQuat(
                v.real.x,
                v.real.y,
                v.real.z,
                v.real.w,
                v.dual.x,
                v.dual.y,
                v.dual.z,
                v.dual.w
            )
            is CameraPose -> CameraPoseValue(
                v.transform.real.x,
                v.transform.real.y,
                v.transform.real.z,
                v.transform.real.w,
                v.transform.dual.x,
                v.transform.dual.y,
                v.transform.dual.z,
                v.transform.dual.w,
                v.focusDistance
            )
            is StackSLString -> StackSL(v.value)
            is String -> Str(v)
            is SourceBackedBlock<*> -> SourceBlock(v.boxId, v.source)

            else -> null
        }
    }
}

/**
 * What a saved key looks like on disk: a schema version and a name → [KValue] map, pretty-printed as
 * JSON with `type` as the discriminator.
 *
 * You rarely build one by hand — `KeyframeScope.saveKey` / `loadKey` do it — but this is the shape to
 * read if you are diffing key files in a text editor or writing them from another tool.
 */
@Serializable
data class KeyDTO(
    /**
     * The owning scope's `schemaVersion` at the moment of saving. Nothing validates it on load: a
     * mismatch is not an error, it only selects which [KeyframeScope.missingKey] defaults apply.
     */
    val schemaVersion: Int = 0,
    /** The key's entries by name. Values that [KValue.fromAny] could not represent are already gone. */
    val values: Map<String, KValue>
)

/**
 * One row of a key file's version history, as recorded in the `<file>.versions/manifest.json` that
 * every `saveKey` appends to. [KeyframeScope.keyVersions] hands these back, sorted by [index].
 */
@Serializable
data class KeyVersionInfo(
    /**
     * Monotonic version number, starting at 0 and simply "highest so far plus one" — a save counter,
     * not a schema version and not related to [KeyDTO.schemaVersion].
     */
    val index: Int,
    /** When the version was written, as an ISO-8601 `Instant.now()` string in UTC. */
    val timestamp: String,
    /** Whatever note was passed to `saveKey`, for telling two snapshots apart later. */
    val note: String? = null,
    /** File name inside the `.versions` directory, `"%06d.json"` of [index]. */
    val file: String
)

@Serializable
private data class KeyVersionsDTO(
    val versions: List<KeyVersionInfo> = emptyList()
)

/**
 * A string that a [KeyframeScope] should treat as *code* rather than as text: StackSL source.
 *
 * The wrapper is what earns it code semantics — [KeyframeScope.interpolate] steps it to the
 * right-hand value at `t >= 1` instead of blending, it persists as the `stacksl` [KValue] rather than
 * a plain string, and assigning a bare `String` over one keeps the wrapper. Equality is data-class
 * equality on the source text, and [toString] gives the source back unchanged.
 */
data class StackSLString(val value: String) {
    /** The StackSL source itself, so a `StackSLString` prints and concatenates like the string it wraps. */
    override fun toString(): String = value
}

/** [StackSLString] as a function, which is what `Key.updateCode` emits so its output can be pasted back. */
fun stackSLString(value: String) = StackSLString(value)


/**
 * A named set of live things worth snapshotting — uniform `Structure` bindings, a module's live
 * uniforms, a `stack.auto` shader slot, a `Camera` — plus the machinery to capture, blend, save and
 * reload them. This is the object you keep around for the lifetime of a sketch.
 *
 * Register targets with [target] / [stackAuto] / [camera], take a snapshot with [capture], blend two
 * snapshots with [interpolate], and write one back to its targets by invoking it. A scope holds no
 * values of its own: a [Key] does, and a `Key` is a plain map, so it outlives the frame it came from.
 *
 * A key name may be qualified with `@`: everything after the last `@` is the property name used on the
 * target, so `"glow@radius"` and `"blur@radius"` can both drive a `radius` uniform on different
 * structures. [schemaVersion] is stamped into every file this scope saves and chooses which
 * [missingKey] defaults a load applies; nothing ever *rejects* a file for having a different one.
 */
class KeyframeScope(val schemaVersion: Int = 0) {
    private class Target(
        val resolve: () -> Structure?,
        val bind: ((Function0<*>) -> Unit)? = null,
        val set: ((Any?) -> Unit)? = null,
        val get: (() -> Any?)? = null
    )

    /**
     * The statically-bound targets, by key name, as registered by `target(name, structure)` — exposed
     * so you can see and clear what a scope is watching.
     *
     * Writing to it directly works but skips the bookkeeping [target] does: a name that also has a
     * lazily-resolved target still resolves to that one, because the dynamic table is consulted first.
     */
    val targets = mutableMapOf<String, Structure>()
    private val dynamicTargets = mutableMapOf<String, Target>()
    private val globalMissingKeys = mutableMapOf<String, () -> Any?>()
    private val schemaMissingKeys = mutableMapOf<Int, MutableMap<String, () -> Any?>>()

    /**
     * Per-key re-timing for [interpolate]: `remap["gain"] = { t -> t * t * (3 - 2 * t) }` eases that one
     * value on a smoothstep while everything else stays linear.
     *
     * The function is handed the caller's `t` and its result is used in place of it for that key alone.
     * Nothing clamps it, so returning outside `0..1` extrapolates numbers and vectors — and reaching
     * `>= 1` early is precisely how you make a code value switch before the end of a transition.
     */
    val remap = mutableMapOf<String, (Double) -> (Double)>()

    /**
     * One snapshot: names to values, and the ability to put them all back. Get one from [capture],
     * [interpolate], [loadKey], or by constructing it from a map you wrote by hand (as the docs
     * examples do).
     *
     * A `Key` is a plain mutable map of live values — captured by reference, not deep-copied, so a
     * `SourceBackedBlock` in a key is the same object the target holds. Invoking it (`key()`) writes
     * every value back to its target. Nothing is written on any other operation: editing, diffing and
     * saving all leave the running sketch exactly as it was.
     */
    inner class Key(val values: MutableMap<String, Any?>) : ToHTML {
        /**
         * Write every value in this key back to its registered target — the moment a snapshot becomes
         * the state of the sketch again.
         *
         * Names whose target is no longer registered are skipped in silence, which is what makes an
         * older key file safe to apply to a scope that has been rearranged since. A `SourceBackedBlock`
         * is bound as the live callable itself; every other value is wrapped in a constant lambda. A
         * null value is *not* skipped — it is written like any other, which for a [stackAuto] slot means
         * replacing its shader source with the empty program.
         */
        operator fun invoke() {
            values.forEach { (k, v) ->
                val target = targetFor(k)
                if (target != null) {
                    val binding = bindingForValue(v)
                    target.set?.invoke(v) ?: target.bind?.invoke(binding) ?: target.resolve()?.let { it.bindings[k] = binding }
                }
            }
        }

        /** `key["gain"]` — the snapshotted value, or null when this key has no such name. */
        operator fun get(name: String): Any? = values[name]

        /**
         * `key["gain"] = 0.5` — edit the snapshot in memory. Nothing reaches the sketch until the key
         * is invoked.
         *
         * The incoming value is coerced to keep the *kind* already stored: assigning a plain `String`
         * over a [StackSLString] or a `SourceBackedBlock` re-wraps it rather than degrading it to text,
         * which is what lets a code value be edited as a string. No other coercion happens — assigning
         * a `Double` over an `Int` really does change the type, and the target may not like it.
         */
        operator fun set(name: String, value: Any?) {
            values[name] = coerceEditedValue(values[name], value)
        }

        /** `"gain" in key` — whether this key carries that name at all (a name mapped to null still counts). */
        operator fun contains(name: String): Boolean = values.containsKey(name)

        /** [set] in chainable form, returning this same key (not a copy). */
        fun put(name: String, value: Any?): Key {
            values[name] = coerceEditedValue(values[name], value)
            return this
        }

        /** [put] for several names at once: `key.putAll("gain" to 0.5, "pos" to vec(0, 0))`. */
        fun putAll(vararg entries: Pair<String, Any?>): Key {
            entries.forEach { (name, value) -> values[name] = coerceEditedValue(values[name], value) }
            return this
        }

        /** Drop a name from the snapshot, returning what was there. A removed name is left alone by [invoke]. */
        fun remove(name: String): Any? = values.remove(name)

        /**
         * A separate key with a copy of the *map* — the usual way to keep a "before" snapshot while you
         * edit. Shallow: both keys go on referencing the same value objects, so mutating a `Vector3f`
         * in place is visible through both.
         */
        fun copy(): Key = Key(values.toMutableMap())

        /**
         * What changed between this key (the left side) and [other] (the right): names added, removed
         * and changed, as a [KeyDiff] that renders itself for box output.
         *
         * [leftName] and [rightName] are only column headings. Comparison is component-wise and
         * *exact* for vectors, quaternions and poses — no tolerance — so two visually identical states
         * an interpolation apart will still show as changed.
         */
        fun diff(other: Key, leftName: String = "left", rightName: String = "right"): KeyDiff =
            KeyDiff(
                leftName = leftName,
                rightName = rightName,
                entries = diffEntries(values, other.values)
            )

        /** [diff] straight to HTML, for `display()` or a box output. */
        fun diffHtml(other: Key, leftName: String = "left", rightName: String = "right"): String =
            diff(other, leftName, rightName).toHTML()

        /** [diff] as one line per change (`+`, `-`, `~`), for a log or a console. */
        fun diffText(other: Key, leftName: String = "left", rightName: String = "right"): String =
            diff(other, leftName, rightName).toString()

        /**
         * Edit the key in a block with `this["name"] = …`, and get the key back for chaining. In-place:
         * this is the same key, edited, not a modified copy — and nothing is applied to the sketch.
         */
        fun update(block: Key.() -> Unit): Key {
            block()
            return this
        }

        /** [invoke] in chainable form: write the key back and return it. */
        fun applyKey(): Key {
            invoke()
            return this
        }

        /** [update] then [invoke] — the everyday "change these values and make it so" call. */
        fun updateAndApply(block: Key.() -> Unit): Key {
            block()
            invoke()
            return this
        }

        /**
         * Write this key to disk at [path] (creating parent directories) *and* append a numbered copy to
         * its `<path>.versions` history with an optional [note]. Chainable. See
         * [KeyframeScope.saveKey] for what survives the trip.
         */
        fun save(path: Path, note: String? = null): Key {
            saveKey(path, this, note)
            return this
        }

        /**
         * The key as pasteable Kotlin: an `update { … }` or `updateAndApply { … }` block assigning every
         * value, sorted by name — how a state you arrived at by fiddling becomes source you can commit.
         *
         * Numbers keep their type (`1.5f`, `2L`, `.toByte()`), vectors come out as `vec(…)`, StackSL as
         * `stackSLString(…)` and a source block as a raw string literal — except when its source
         * contains a `"""`, a trailing quote, or a `$`, any of which a raw string would mangle, and it
         * falls back to a fully escaped literal instead. A value outside that set is
         * emitted as `null` behind an "unsupported" block comment, so the code still compiles but
         * quietly nulls that value.
         */
        fun updateCode(keyName: String = "key", apply: Boolean = true): String {
            val header = if (apply) "$keyName.updateAndApply {" else "$keyName.update {"
            return values.entries.sortedBy { it.key }.joinToString(
                separator = "\n",
                prefix = "$header\n",
                postfix = "\n}"
            ) { (name, value) ->
                "    this[${kotlinStringLiteral(name)}] = ${kotlinValueLiteral(value)}"
            }
        }

        /**
         * [updateCode], put on the **system clipboard** and also returned, so a captured look is one
         * call away from being pasted into a box.
         *
         * That clipboard is the real AWT one: it will throw on a headless JVM, and it silently replaces
         * whatever the user had copied.
         */
        fun copyUpdateCodeToClipboard(keyName: String = "key", apply: Boolean = true): String {
            val code = updateCode(keyName, apply)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
            return code
        }

        /**
         * The key as an HTML table of name/value rows, sorted by name — which is what a box shows when
         * a `Key` is the last thing evaluated. Source blocks render with their source and a link to the
         * owning box; a block whose box is not loaded says so in red.
         */
        override fun toHTML(): String = keyframeKeyHtml(values)
    }

    private fun coerceEditedValue(previous: Any?, next: Any?): Any? =
        when {
            previous is StackSLString && next is String -> StackSLString(next)
            previous is SourceBackedBlock<*> && next is String -> SourceBackedBlock<Any?>(previous.boxId, next)
            else -> next
        }

    private fun bindingForValue(value: Any?): Function0<*> =
        if (value is SourceBackedBlock<*>) value else ({ value })

    private fun valueForBinding(binding: Function0<*>?): Any? =
        when (binding) {
            null -> null
            is SourceBackedBlock<*> -> binding
            else -> binding.invoke()
        }

    private fun targetPropertyName(keyName: String): String =
        keyName.substringAfterLast('@')

    private fun targetFor(name: String): Target? =
        dynamicTargets[name] ?: targets[name]?.let { structure ->
            val propertyName = targetPropertyName(name)
            Target(
                resolve = { structure },
                bind = { value -> structure.bind(propertyName, value) },
                get = { valueForBinding(structure.bindings[propertyName]) }
            )
        }

    /**
     * Register a key [name] against a uniform [structure] held for good — the simplest target, for a
     * `Structure` you built once and will keep.
     *
     * The property bound on the structure is the part of [name] after the last `@`, so `"glow@radius"`
     * drives `radius`. Registering a name twice replaces the earlier target, whichever kind it was.
     */
    fun target(name: String, structure: Structure) {
        targets[name] = structure
        dynamicTargets.remove(name)
    }

    /**
     * Register a key [name] against a structure that is looked up **again on every read and write** —
     * the right choice for anything a shader reload can replace underneath you.
     *
     * A lambda that returns null simply makes the key unreadable for now: [capture] records null for it
     * and [interpolate] skips it, rather than failing.
     */
    fun target(name: String, structure: () -> Structure?) {
        val propertyName = targetPropertyName(name)
        dynamicTargets[name] = Target(
            resolve = structure,
            bind = { value -> structure()?.bind(propertyName, value) },
            get = { valueForBinding(structure()?.bindings?.get(propertyName)) }
        )
        targets.remove(name)
    }

    /**
     * Register a key against one of a graphics module's live uniform structures, resolved through the
     * pointer each time so it survives a shader reload.
     *
     * [name] is both the key name and — after the last `@` — the name of the uniform structure to look
     * up in `module.current.liveUniformStructures`. Note that this addresses the *structure*, not a
     * field inside it.
     */
    fun target(module: ModulePointer, name: String) =
        target(name) { module.current.liveUniformStructures[targetPropertyName(name)] }

    /** [target] for a compute module's live uniform structures. */
    fun target(module: ComputeModulePointer, name: String) =
        target(name) { module.current.liveUniformStructures[targetPropertyName(name)] }

    /** [target] for a mesh-shader module's live uniform structures. */
    fun target(module: MeshModulePointer, name: String) =
        target(name) { module.current.liveUniformStructures[targetPropertyName(name)] }

    /**
     * Register a key against a `stack.auto.<name>` **shader-source** slot, so that the StackSL a module
     * runs is part of the snapshot: capture reads it as a [StackSLString] and applying a key recompiles
     * the slot. This is what lets a keyframe morph a patch's code as well as its numbers.
     *
     * [keyName] defaults to [name], and as elsewhere the slot addressed is the part of [name] after the
     * last `@`. Applying a value writes the shader source through `stackAutoShaderSource`, which
     * **swallows a compile failure**: the error is reported on the owning box as a shader-stage error,
     * the old source stays in place, and the key that failed to apply says nothing about it.
     */
    fun stackAuto(module: ModulePointer, name: String, keyName: String = name) {
        val stackAutoName = targetPropertyName(name)
        dynamicTargets[keyName] = Target(
            resolve = { null },
            set = { value -> stackAutoShaderSource(module, stackAutoName, (value as? StackSLString)?.value ?: value?.toString().orEmpty()) },
            get = { StackSLString(stackAutoShaderSource(module, stackAutoName)) }
        )
        targets.remove(keyName)
    }

    /** [stackAuto] for a compute module; the source is reloaded on the compute path. */
    fun stackAuto(module: ComputeModulePointer, name: String, keyName: String = name) {
        val stackAutoName = targetPropertyName(name)
        dynamicTargets[keyName] = Target(
            resolve = { null },
            set = { value -> stackAutoShaderSource(module, stackAutoName, (value as? StackSLString)?.value ?: value?.toString().orEmpty()) },
            get = { StackSLString(stackAutoShaderSource(module, stackAutoName)) }
        )
        targets.remove(keyName)
    }

    /** [stackAuto] for a mesh-shader module; the source is reloaded on the mesh path. */
    fun stackAuto(module: MeshModulePointer, name: String, keyName: String = name) {
        val stackAutoName = targetPropertyName(name)
        dynamicTargets[keyName] = Target(
            resolve = { null },
            set = { value -> stackAutoShaderSource(module, stackAutoName, (value as? StackSLString)?.value ?: value?.toString().orEmpty()) },
            get = { StackSLString(stackAutoShaderSource(module, stackAutoName)) }
        )
        targets.remove(keyName)
    }

    /**
     * Register a `Camera` as a keyable target, so a viewpoint can be snapshotted and morphed like any
     * other value. Capture stores the camera's `CameraPose` — its rigid transform plus focus distance —
     * and applying a key calls `applyPose`, so [interpolate] gives you a screw-motion (sclerp) camera
     * move rather than a separate position and target lerp.
     *
     * A raw `DualQuaternionf` value is accepted too and keeps the camera's *current* distance to target
     * as the focus. Any other type is ignored without complaint, so a `Vector3f` "camera position" key
     * will appear to apply and do nothing.
     */
    fun camera(camera: Camera, keyName: String = "camera") {
        dynamicTargets[keyName] = Target(
            resolve = { null },
            set = { value ->
                when (value) {
                    is CameraPose -> camera.applyPose(value)
                    is DualQuaternionf -> camera.applyPose(CameraPose(value, camera.distanceToTarget()))
                }
            },
            get = { camera.pose() }
        )
        targets.remove(keyName)
    }

    /**
     * The raw "bind a lambda to this target" function for a registered key [name], or null if there is
     * no such key — an escape hatch for driving a target continuously instead of through a [Key].
     *
     * It only ever finds *structure* targets: [stackAuto] and [camera] targets set values through a
     * setter rather than a binding, so they have no bind function and this returns null for them even
     * though the key exists.
     */
    fun findByName(name : String) : ((Function0<*>) -> Unit)? {
        targetFor(name)?.bind?.let { return it }
        return null
    }

    /**
     * Supply the value a *load* should use for a key that the file does not contain — how a scope that
     * has grown a new parameter still loads snapshots taken before it existed.
     *
     * Applies to every file this scope loads, whatever its schema version. Only ever fills in absent
     * names: a name present in the file wins, even if its value is null. Registering the same name
     * twice replaces the earlier default, and the default lands in the loaded [Key] without being
     * applied to anything — invoke the key for that.
     */
    fun missingKey(name: String, value: Any?) {
        globalMissingKeys[name] = { value }
    }

    /**
     * [missingKey] with the default computed at load time rather than fixed now — use it when the
     * sensible fallback is "whatever the target currently holds".
     */
    fun missingKey(name: String, value: () -> Any?) {
        globalMissingKeys[name] = value
    }

    /**
     * A default that applies only when the loaded file's [KeyDTO.schemaVersion] equals [schemaVersion] —
     * for the case where "the old files meant something different" rather than "the old files are
     * missing this".
     *
     * Version-specific defaults are applied after the version-agnostic ones, so for a matching file
     * this wins over a plain [missingKey] for the same name.
     */
    fun missingKey(schemaVersion: Int, name: String, value: Any?) {
        schemaMissingKeys.getOrPut(schemaVersion) { mutableMapOf() }[name] = { value }
    }

    /** The schema-specific [missingKey] with a lambda default, evaluated once per load. */
    fun missingKey(schemaVersion: Int, name: String, value: () -> Any?) {
        schemaMissingKeys.getOrPut(schemaVersion) { mutableMapOf() }[name] = value
    }

    /**
     * Read every registered target *right now* into a new [Key] — the snapshot call.
     *
     * Values are taken as they are, not copied, so a mutable `Vector3f` in a key is still the vector the
     * structure is bound to; keep the key across frames only if you also stopped mutating that vector
     * in place. A target that cannot currently be resolved contributes a null, which [interpolate] skips
     * and `saveKey` drops.
     */
    fun capture() =
        Key((targets.keys + dynamicTargets.keys).map { k -> k to targetFor(k)?.get?.invoke() }
            .associate { it }.toMutableMap())

    /**
     * Blend two snapshots into a new [Key] at position [t], per value type: numbers and vectors lerp,
     * quaternions slerp, dual quaternions and camera poses sclerp, and strings, [StackSLString]s and
     * source blocks **step** to [b]'s value once `t >= 1` — code never gets a half-parsed intermediate.
     *
     * `t` runs 0 at [a] to 1 at [b] but is not clamped, so it extrapolates cheerfully past either end;
     * [remap] can re-time any single key first. Returns a fresh key and touches nothing live — invoke the
     * result to apply it.
     *
     * Entries that are not blendable are simply absent from the result: a name in only one of the two
     * keys, a null on either side, or two values of different types. Numbers come back as `Float` only if
     * *both* sides were `Float`, otherwise as `Double` — so interpolating two `Int` keyframes yields a
     * `Double`, which an `Int` uniform will not accept.
     */
    fun interpolate(a: Key, b: Key, t: Double): Key {
        fun lerp(x: Double, y: Double, tt: Double) = x + (y - x) * tt

        val out = mutableMapOf<String, Any?>()
        val allKeys = a.values.keys + b.values.keys

        for (key in allKeys) {
            val va = a.values[key]
            val vb = b.values[key]
            if (va == null || vb == null) continue

            val tEffective = remap[key]?.invoke(t) ?: t
            val tf = tEffective.toFloat()

            val interpolated: Any = when {
                va is Number && vb is Number -> {
                    val v = lerp(va.toDouble(), vb.toDouble(), tEffective)
                    when {
                        va is Float && vb is Float -> v.toFloat()
                        va is Double && vb is Double -> v
                        else -> v
                    }
                }

                va is Vector2f && vb is Vector2f -> Vector2f(va).lerp(vb, tf)
                va is Vector3f && vb is Vector3f -> Vector3f(va).lerp(vb, tf)
                va is Vector4f && vb is Vector4f -> Vector4f(va).lerp(vb, tf)
                va is afield.stdlib.linalg.Quaternionf && vb is afield.stdlib.linalg.Quaternionf -> afield.stdlib.linalg.Quaternionf(va).slerp(vb, tf)
                va is afield.stdlib.linalg.DualQuaternionf && vb is afield.stdlib.linalg.DualQuaternionf -> afield.stdlib.linalg.DualQuaternionf(va).sclerp(vb, tf)
                va is CameraPose && vb is CameraPose -> va.sclerp(vb, tf)
                va is StackSLString && vb is StackSLString -> if (tEffective >= 1.0) vb else va
                va is String && vb is String -> if (tEffective >= 1.0) vb else va
                va is SourceBackedBlock<*> && vb is SourceBackedBlock<*> -> if (tEffective >= 1.0) vb else va

                else -> continue
            }

            out[key] = interpolated
        }

        return Key(out)
    }

    // ---- persistence ----

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private fun Key.toDTO(): KeyDTO =
        KeyDTO(
            schemaVersion = schemaVersion,
            values.mapNotNull { (k, v) ->
                val kv = KValue.fromAny(v) ?: return@mapNotNull null // drops null/unsupported
                k to kv
            }.toMap()
        )

    private fun keyFromDTO(dto: KeyDTO): Key {
        val values = mutableMapOf<String, Any?>()
        dto.values.forEach { (name, value) -> values[name] = value.toAny() }
        val defaults = globalMissingKeys.toMutableMap()
        schemaMissingKeys[dto.schemaVersion]?.let { defaults.putAll(it) }
        defaults.forEach { (name, defaultValue) ->
            if (!values.containsKey(name)) values[name] = defaultValue()
        }
        return Key(values)
    }

    /**
     * Write [key] to [path] as pretty JSON, creating parent directories — and, every time, also append a
     * numbered copy to a `<path>.versions` directory with a manifest row carrying [note] and a timestamp.
     * Saving is therefore always additive history, never just an overwrite; nothing prunes it, so a
     * hundred saves leave a hundred files.
     *
     * Only values [KValue.fromAny] recognises are written. Nulls and unsupported types are dropped in
     * silence, and a `SourceBackedBlock` is written as its box id plus source text, not as a live lambda.
     */
    fun saveKey(path: Path, key: Key, note: String? = null) {
        path.parent?.createDirectories()
        val text = json.encodeToString(key.toDTO())
        saveKeyVersion(path, text, note)
        Files.writeString(path, text)
    }

    /**
     * Read a saved key back as a [Key], filling in any [missingKey] defaults for names the file lacks.
     * Reading only: nothing is applied to the sketch until you invoke the returned key.
     *
     * With [version] null this reads [path] itself, i.e. the most recent save. With a version index it
     * reads that entry of the `.versions` history instead, and throws `IllegalArgumentException` when no
     * such version is listed. A file whose values have since left the scope loads fine — the extra names
     * simply have nowhere to go on apply. Unknown JSON keys are ignored, but a malformed file throws.
     */
    fun loadKey(path: Path, version: Int? = null): Key {
        val text = Files.readString(version?.let { keyVersionFile(path, it) } ?: path)
        val dto = json.decodeFromString<KeyDTO>(text)
        return keyFromDTO(dto)
    }

    /**
     * The highest-numbered entry of [path]'s version history, falling back to [path] itself when there is
     * no history at all.
     *
     * In normal use that is the same content as `loadKey(path)`, since every save writes both. It differs
     * when the main file has been edited or replaced by hand behind the history's back — then this
     * deliberately prefers what Field last wrote.
     */
    fun loadLatestKey(path: Path): Key =
        keyVersions(path).lastOrNull()?.let { loadKey(path, it.index) } ?: loadKey(path)

    /** `loadKey(path, version)` by another name, for when the version is not optional at the call site. */
    fun loadKeyVersion(path: Path, version: Int): Key =
        loadKey(path, version)

    /**
     * The save history of [path], oldest first, straight from `<path>.versions/manifest.json` — the list to
     * show a person choosing which snapshot to go back to.
     *
     * Empty when nothing has been saved yet. Also empty, and this is the trap, when the manifest exists but
     * cannot be parsed: the failure is swallowed rather than raised, and since the next save numbers itself
     * from the manifest, a corrupt manifest silently restarts numbering at 0 and overwrites `000000.json`.
     */
    fun keyVersions(path: Path): List<KeyVersionInfo> =
        readKeyVersions(path).versions.sortedBy { it.index }

    private fun saveKeyVersion(path: Path, text: String, note: String?) {
        val dir = keyVersionsDir(path)
        dir.createDirectories()

        val manifest = readKeyVersions(path)
        val nextIndex = (manifest.versions.maxOfOrNull { it.index } ?: -1) + 1
        val fileName = keyVersionFileName(nextIndex)
        Files.writeString(dir.resolve(fileName), text)

        val updated = KeyVersionsDTO(
            manifest.versions + KeyVersionInfo(
                index = nextIndex,
                timestamp = Instant.now().toString(),
                note = note,
                file = fileName
            )
        )
        Files.writeString(keyVersionsManifest(path), json.encodeToString(updated))
    }

    private fun readKeyVersions(path: Path): KeyVersionsDTO {
        val manifest = keyVersionsManifest(path)
        if (!manifest.exists()) return KeyVersionsDTO()
        return runCatching { json.decodeFromString<KeyVersionsDTO>(Files.readString(manifest)) }
            .getOrElse { KeyVersionsDTO() }
    }

    private fun keyVersionsDir(path: Path): Path =
        (path.parent ?: Path.of(".")).resolve("${path.name}.versions")

    private fun keyVersionsManifest(path: Path): Path =
        keyVersionsDir(path).resolve("manifest.json")

    private fun keyVersionFile(path: Path, version: Int): Path {
        val info = keyVersions(path).firstOrNull { it.index == version }
            ?: throw IllegalArgumentException("No key version $version for $path")
        return keyVersionsDir(path).resolve(info.file)
    }

    private fun keyVersionFileName(version: Int): String =
        "%06d.json".format(version)
}

/**
 * How one name differs between two keys, always read left-to-right: `ADDED` means the right key has it
 * and the left does not, `REMOVED` the other way round, `CHANGED` that both have it with unequal values.
 * There is no "same" kind — unchanged names are left out of a diff entirely.
 */
enum class KeyDiffKind { ADDED, REMOVED, CHANGED }

/** One line of a [KeyDiff]: what the name was on each side, and which way it moved. */
data class KeyDiffEntry(
    /** The key name, as it appears in both snapshots (`@`-qualified if that is how it was registered). */
    val name: String,
    /** Whether this name was added, removed, or changed between left and right. */
    val kind: KeyDiffKind,
    /** The left-hand value; null for an `ADDED` entry, which had none. */
    val left: Any?,
    /** The right-hand value; null for a `REMOVED` entry. */
    val right: Any?
)

/**
 * The difference between two [KeyframeScope.Key]s: only the names that actually moved, sorted by name,
 * ready to render. Produced by `Key.diff`.
 *
 * Comparison is component-wise and exact for vectors, quaternions and camera poses, structural for a
 * source block (same box *and* same source text), and plain `==` for everything else — no float
 * tolerance anywhere, so a value that merely round-tripped through a save may still show as changed.
 */
data class KeyDiff(
    /** Heading for the left-hand column — cosmetic, defaulting to `"left"` at the call site. */
    val leftName: String,
    /** Heading for the right-hand column. */
    val rightName: String,
    /** The changes, name-sorted; empty means the two keys agree. */
    val entries: List<KeyDiffEntry>
) : ToHTML {
    /**
     * The diff as text, one line per change prefixed `+` (added), `-` (removed) or `~` (changed), and
     * the single line "No key differences." when there are none.
     */
    override fun toString(): String =
        if (entries.isEmpty()) "No key differences."
        else entries.joinToString("\n") { entry ->
            when (entry.kind) {
                KeyDiffKind.ADDED -> "+ ${entry.name}: ${keyframeValueText(entry.right)}"
                KeyDiffKind.REMOVED -> "- ${entry.name}: ${keyframeValueText(entry.left)}"
                KeyDiffKind.CHANGED -> "~ ${entry.name}: ${keyframeValueText(entry.left)} -> ${keyframeValueText(entry.right)}"
            }
        }

    /**
     * The diff as a small dark-mode HTML table — one row per change, marker coloured green for added, red
     * for removed and amber for changed, with a header counting the changes. What a box shows when a
     * `KeyDiff` is the value on screen.
     */
    override fun toHTML(): String {
        val rows = entries.joinToString("\n") { entry ->
            val mark = when (entry.kind) {
                KeyDiffKind.ADDED -> "+"
                KeyDiffKind.REMOVED -> "-"
                KeyDiffKind.CHANGED -> "~"
            }
            val color = when (entry.kind) {
                KeyDiffKind.ADDED -> "#8f8"
                KeyDiffKind.REMOVED -> "#f88"
                KeyDiffKind.CHANGED -> "#fc8"
            }
            """
            <tr>
              <td style="padding:2px 8px 2px 0;color:$color;">$mark</td>
              <td style="padding:2px 10px 2px 0;color:#9cdcfe">${keyframeEsc(entry.name)}</td>
              <td style="padding:2px 10px 2px 0;">${keyframeValueHtml(entry.left)}</td>
              <td style="padding:2px 0;">${keyframeValueHtml(entry.right)}</td>
            </tr>
            """.trimIndent()
        }
        val body = if (entries.isEmpty()) {
            "<div style='color:#888;'>No key differences.</div>"
        } else {
            """
            <table style="border-collapse:collapse;">
              <tr>
                <th></th>
                <th style="text-align:left;color:#777;padding-right:10px;">key</th>
                <th style="text-align:left;color:#777;padding-right:10px;">${keyframeEsc(leftName)}</th>
                <th style="text-align:left;color:#777;">${keyframeEsc(rightName)}</th>
              </tr>
              $rows
            </table>
            """.trimIndent()
        }
        return """
        <div style="font:12px/1.45 Iosevka, Menlo, Consolas, monospace; color:#d4d4d4;">
          <div style="margin-bottom:4px;color:#c586c0;">Key diff(${entries.size} changes)</div>
          $body
        </div>
        """.trimIndent()
    }
}

private fun diffEntries(left: Map<String, Any?>, right: Map<String, Any?>): List<KeyDiffEntry> =
    (left.keys + right.keys).sorted().mapNotNull { name ->
        val hasLeft = left.containsKey(name)
        val hasRight = right.containsKey(name)
        when {
            !hasLeft && hasRight -> KeyDiffEntry(name, KeyDiffKind.ADDED, null, right[name])
            hasLeft && !hasRight -> KeyDiffEntry(name, KeyDiffKind.REMOVED, left[name], null)
            !keyValuesEqual(left[name], right[name]) -> KeyDiffEntry(name, KeyDiffKind.CHANGED, left[name], right[name])
            else -> null
        }
    }

private fun keyValuesEqual(a: Any?, b: Any?): Boolean =
    when {
        a is SourceBackedBlock<*> && b is SourceBackedBlock<*> -> a.boxId == b.boxId && a.source == b.source
        a is Vector2f && b is Vector2f -> a.x == b.x && a.y == b.y
        a is Vector3f && b is Vector3f -> a.x == b.x && a.y == b.y && a.z == b.z
        a is Vector4f && b is Vector4f -> a.x == b.x && a.y == b.y && a.z == b.z && a.w == b.w
        a is Quaternionf && b is Quaternionf -> a.x == b.x && a.y == b.y && a.z == b.z && a.w == b.w
        a is DualQuaternionf && b is DualQuaternionf ->
            a.real.x == b.real.x && a.real.y == b.real.y && a.real.z == b.real.z && a.real.w == b.real.w &&
                a.dual.x == b.dual.x && a.dual.y == b.dual.y && a.dual.z == b.dual.z && a.dual.w == b.dual.w
        a is CameraPose && b is CameraPose ->
            a.focusDistance == b.focusDistance && keyValuesEqual(a.transform, b.transform)
        else -> a == b
    }

private fun kotlinStringLiteral(text: String): String = buildString(text.length + 16) {
    append('"')
    text.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(c)
        }
    }
    append('"')
}

private fun kotlinRawStringLiteral(text: String): String {
    // A raw string reads far better for a block of source, but Kotlin still expands `$` inside one,
    // so a body mentioning `$name` or `${…}` cannot go out raw: the pasted code would either fail to
    // compile (unresolved reference) or silently interpolate something else. kotlinStringLiteral
    // escapes `$` along with the quotes and newlines, so fall through to it in that case too.
    if (!text.contains("\"\"\"") && !text.endsWith("\"") && !text.contains('$')) return "\"\"\"$text\"\"\""
    return kotlinStringLiteral(text)
}

private fun kotlinNumberLiteral(value: Number): String =
    when (value) {
        is Byte -> value.toString() + ".toByte()"
        is Short -> value.toString() + ".toShort()"
        is Int -> value.toString()
        is Long -> value.toString() + "L"
        is Float -> {
            val f = value
            when {
                f.isNaN() -> "Float.NaN"
                f == Float.POSITIVE_INFINITY -> "Float.POSITIVE_INFINITY"
                f == Float.NEGATIVE_INFINITY -> "Float.NEGATIVE_INFINITY"
                // The `f` suffix is what keeps the round trip honest. Without it a captured Float came back as
                // `1.5`, a *Double* literal, and since coerceEditedValue deliberately does not re-coerce
                // numbers the pasted code stored a Double where the capture held a Float.
                else -> value.toString() + "f"
            }
        }
        is Double -> {
            val d = value
            when {
                d.isNaN() -> "Double.NaN"
                d == Double.POSITIVE_INFINITY -> "Double.POSITIVE_INFINITY"
                d == Double.NEGATIVE_INFINITY -> "Double.NEGATIVE_INFINITY"
                else -> value.toString()
            }
        }
        else -> value.toDouble().toString()
    }

private fun kotlinValueLiteral(value: Any?): String =
    when (value) {
        null -> "null"
        is StackSLString -> "stackSLString(${kotlinStringLiteral(value.value)})"
        is SourceBackedBlock<*> -> kotlinRawStringLiteral(value.source)
        is String -> kotlinStringLiteral(value)
        is Byte, is Short, is Int, is Long, is Float, is Double -> kotlinNumberLiteral(value as Number)
        is Number -> value.toDouble().toString()
        is Vector2f -> "vec(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)})"
        is Vector3f -> "vec(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)}, ${kotlinNumberLiteral(value.z)})"
        is Vector4f -> "vec(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)}, ${kotlinNumberLiteral(value.z)}, ${kotlinNumberLiteral(value.w)})"
        is Quaternionf -> "afield.stdlib.linalg.Quaternionf(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)}, ${kotlinNumberLiteral(value.z)}, ${kotlinNumberLiteral(value.w)})"
        is DualQuaternionf -> "afield.stdlib.linalg.DualQuaternionf(afield.stdlib.linalg.Quaternionf(${kotlinNumberLiteral(value.real.x)}, ${kotlinNumberLiteral(value.real.y)}, ${kotlinNumberLiteral(value.real.z)}, ${kotlinNumberLiteral(value.real.w)}), afield.stdlib.linalg.Quaternionf(${kotlinNumberLiteral(value.dual.x)}, ${kotlinNumberLiteral(value.dual.y)}, ${kotlinNumberLiteral(value.dual.z)}, ${kotlinNumberLiteral(value.dual.w)}))"
        is CameraPose -> "afield.four.util.CameraPose(${kotlinValueLiteral(value.transform)}, ${kotlinNumberLiteral(value.focusDistance)})"
        else -> "/* unsupported ${value::class.java.name} */ null"
    }

/** A key's [KeyframeScope.Key.toHTML] table, for `display()` and anywhere else HTML is what is wanted. */
val KeyframeScope.Key.h: String
    get() = toHTML()

private fun keyframeEsc(s: String): String = buildString(s.length) {
    s.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
}

private fun keyframeSourceBlockHtml(v: SourceBackedBlock<*>): String {
    val boxHtml = sourceBackedBlockBox(v.boxId)?.h
        ?: "<span style='color:#f88;'>missing box ${keyframeEsc(v.boxId)}</span>"
    return """
    <div style="display:grid;gap:3px;">
      <div><span style="color:#777;">sourceBlock in </span>$boxHtml</div>
      <pre style="margin:0;padding:4px 6px;border-left:2px solid #555;background:rgba(255,255,255,0.04);white-space:pre-wrap;color:#d4d4d4;">${keyframeEsc(v.source)}</pre>
    </div>
    """.trimIndent()
}

private fun keyframeValueHtml(v: Any?): String = when (v) {
    is SourceBackedBlock<*> -> keyframeSourceBlockHtml(v)
    is StackSLString -> v.value.h
    else -> v.h
}

private fun keyframeValueText(v: Any?): String = when (v) {
    null -> "null"
    is StackSLString -> v.value
    is SourceBackedBlock<*> -> "sourceBlock@${v.boxId} { ${v.source.replace('\n', ' ')} }"
    is Vector2f -> "vec(${v.x}, ${v.y})"
    is Vector3f -> "vec(${v.x}, ${v.y}, ${v.z})"
    is Vector4f -> "vec(${v.x}, ${v.y}, ${v.z}, ${v.w})"
    is Quaternionf -> "quat(${v.x}, ${v.y}, ${v.z}, ${v.w})"
    is DualQuaternionf -> "dualquat(real=quat(${v.real.x}, ${v.real.y}, ${v.real.z}, ${v.real.w}), dual=quat(${v.dual.x}, ${v.dual.y}, ${v.dual.z}, ${v.dual.w}))"
    is CameraPose -> "cameraPose(position=${v.position}, focus=${v.focusDistance})"
    else -> v.toString()
}

private fun keyframeKeyHtml(values: Map<String, Any?>): String {
    val rows = values.entries.sortedBy { it.key }.joinToString("\n") { (name, value) ->
        """
        <tr>
          <td style="padding:2px 10px 2px 0;color:#9cdcfe">${keyframeEsc(name)}</td>
          <td style="padding:2px 0;color:#d4d4d4">${keyframeValueHtml(value)}</td>
        </tr>
        """.trimIndent()
    }

    return """
    <div style="font:12px/1.45 Iosevka, Menlo, Consolas, monospace; color:#d4d4d4;">
      <div style="margin-bottom:4px;color:#c586c0;">Key(${values.size} values)</div>
      <table style="border-collapse:collapse;">$rows</table>
    </div>
    """.trimIndent()
}
