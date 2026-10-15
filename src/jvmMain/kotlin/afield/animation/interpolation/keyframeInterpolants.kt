package afield.animation.interpolation

import afield.animation.keyframes.keyframes
import afield.app.diagnostics.named
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.with
import afield.ui.visualization.menu

import afield.animation.keyframes.KeyTypeEntry
import afield.animation.keyframes.KeyTypeLibrary
import afield.animation.keyframes.Keyframe
import afield.app.protocol.put
import afield.audio.synth.*
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.app.diagnostics.logging.lprintln
import afield.runtime.macros.Insert
import afield.study.source.hydratingBoxes
import afield.study.source.sourceBackedBlockBox
import kotlin.script.experimental.api.ResultValue

/*
 * Custom key types for the keyframe editor (`keyframes(...)`, afield/math/keyframes.kt): a named
 * segment shape written as a Kotlin lambda.
 *
 *     interpolant("overshoot") { v0 + (v1 - v0) * (1 + 2.7 * (u - 1).pow(3) + 1.7 * (u - 1).pow(2)) }
 *
 * The name becomes a key type, offered in the key menu, and a key of that type shapes the segment
 * that leaves it. The `interpolant` macro (macro_interpolant below) keeps the lambda's source next
 * to the live lambda; the source is saved with the box, in `keyframeInterpolants`, so a curve keeps
 * its shape when the definition is not (yet) run — the box defines it after calling keyframes, or
 * the definition was deleted — by compiling the saved source again.
 */

/**
 * One segment of a keyframe curve, as an [interpolant] sees it: the receiver of its lambda.
 *
 * The lambda returns the curve's value at [t], somewhere between the keys at [t0] (value [v0]) and
 * [t1] (value [v1]). [u] is how far through the segment [t] is, `0.0` at the first key and `1.0` at
 * the second.
 */
class KeySegment internal constructor(
    /** The time being evaluated, between [t0] and [t1]. */
    val t: Double,
    /** How far through the segment [t] is: `(t - t0) / (t1 - t0)`, from 0 to 1. */
    val u: Double,
    /** The time of the key the segment leaves. */
    val t0: Double,
    /** The time of the next key. */
    val t1: Double,
    /** The value of the key the segment leaves. */
    val v0: Double,
    /** The value of the next key. */
    val v1: Double,
    /** The key before this segment's first, if there is one: for shapes that look past the segment. */
    val before: Keyframe? = null,
    /** The key after this segment's second, if there is one. */
    val after: Keyframe? = null,
) {
    /** The segment's length in time. */
    val span: Double get() = t1 - t0

    /** The value [f] of the way from [v0] to [v1]: `lerp(u)` is a straight line, `lerp(f(u))` an easing. */
    fun lerp(f: Double): Double = v0 + (v1 - v0) * f

    /** [a] + ([b] - [a]) × [f]. */
    fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f

    /** The smooth step from 0 to 1 as [f] goes from 0 to 1, with zero slope at both ends. */
    fun smoothstep(f: Double): Double = f.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
}

/** A named key type: its lambda, and the Kotlin source it was written as. */
class KeyInterpolant internal constructor(
    val name: String,
    val boxId: String,
    val source: String,
    @Volatile private var block: (KeySegment.() -> Double)?,
    /** Where the type sits in the key menu; [KeyTypeLibrary] entries say, a box's own are "This box". */
    val entry: KeyTypeEntry? = null,
) {
    /** The value of segment [s] under this type. A definition that cannot run gives the straight line. */
    fun evaluate(s: KeySegment): Double {
        val f = block ?: hydrate() ?: return s.lerp(s.u)
        return try {
            f(s)
        } catch (e: Exception) {
            lprintln("warning,keyframes", "interpolant '$name' failed at t=${s.t}: ${e.message}")
            s.lerp(s.u)
        }
    }

    @Volatile private var hydrationFailed = false

    private fun hydrate(): (KeySegment.() -> Double)? {
        if (hydrationFailed || source.isBlank()) return null
        // Compiling into a box from inside that box's own code re-runs its body; leave it to a caller
        // outside the box (the editor connecting, on its own thread), and draw a straight line meanwhile.
        if (Box.context.get()?.id == boxId) return null
        synchronized(this) {
            block?.let { return it }
            val box = sourceBackedBlockBox(boxId) ?: return null
            // The same guard SourceBackedBlock uses: compiling into a box whose body is running re-runs it.
            if (!hydratingBoxes.get().add(boxId)) return null
            try {
                val expression = "val __interpolant: afield.animation.interpolation.KeySegment.() -> Double = {\n$source\n}\n__interpolant"
                val expanded = runCatching { box.ki.applyMacros(box, expression, skipMacros = setOf("interpolant")) }
                    .getOrDefault(expression)
                val snippet = box.ki.compileSnippetAndWait(
                    expanded,
                    implicits = listOf(box),
                    onCompileError = { d -> d.forEach { lprintln("error,keyframes", "interpolant '$name': ${it.message}") } }
                )
                val value = (snippet?.evalAndWait(implicits = listOf(box), onEvalException = { throw it }) as? ResultValue.Value)?.value
                @Suppress("UNCHECKED_CAST")
                val f = value as? (KeySegment.() -> Double)
                if (f == null) hydrationFailed = true
                block = f
                return f
            } catch (e: Exception) {
                hydrationFailed = true
                lprintln("warning,keyframes", "could not rebuild interpolant '$name' from its saved source: ${e.message}")
                return null
            } finally {
                hydratingBoxes.get().remove(boxId)
            }
        }
    }
}

private const val INTERPOLANTS_PROPERTY = "keyframeInterpolants"

// Live definitions, per box: what this run of the box said.
private val liveInterpolants = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, KeyInterpolant>>()

/**
 * Defines a key type named [name] for this box's keyframe editors: a segment shape, written as
 * the value the curve should have at each point of the segment.
 *
 * ```kotlin
 * interpolant("overshoot") { lerp(1 + 2.7 * (u - 1).pow(3) + 1.7 * (u - 1).pow(2)) }
 * interpolant("wobble") { lerp(u) + 0.08 * sin(u * 6 * PI) * (1 - u) }
 * ```
 *
 * Inside the lambda: [KeySegment.u], from 0 to 1 across the segment, the actual time
 * [KeySegment.t], the keys either side ([KeySegment.t0], [KeySegment.v0] and [KeySegment.t1],
 * [KeySegment.v1]), and [KeySegment.lerp] for "this far from one key's value to the next". The type
 * appears in the key menu of every editor in this box, and is saved with the box.
 */
fun Box.interpolant(name: String, block: KeySegment.() -> Double) = registerInterpolant(name, "", block)

/** What the `interpolant` macro rewrites a definition to: the lambda, and its [source] to save. */
fun Box.registerInterpolant(name: String, source: String, block: KeySegment.() -> Double): KeyInterpolant {
    val defined = KeyInterpolant(name, id, source, block)
    liveInterpolants.getOrPut(id) { java.util.concurrent.ConcurrentHashMap() }[name] = defined
    if (source.isNotBlank()) saveInterpolantSource(this, name, source)
    return defined
}

private fun saveInterpolantSource(box: Box, name: String, source: String) {
    val existing = box.getPropertyRaw(INTERPOLANTS_PROPERTY) as? String
    val all = runCatching { if (existing != null) org.json.JSONObject(existing) else org.json.JSONObject() }
        .getOrDefault(org.json.JSONObject())
    if (all.optString(name, null) == source) return
    all.put(name, source)
    box.setPropertyRaw(INTERPOLANTS_PROPERTY, all.toString())
}

/**
 * The key types [box] defines: this run's, then any saved with the box that this run has not
 * (re)defined, rebuilt from their source the first time they are needed.
 */
internal fun interpolantsFor(box: Box?): Map<String, KeyInterpolant> {
    if (box == null) return emptyMap()
    val live = liveInterpolants[box.id] ?: java.util.concurrent.ConcurrentHashMap()
    val saved = (box.getPropertyRaw(INTERPOLANTS_PROPERTY) as? String)
        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        ?: return live
    saved.keys().forEach { name ->
        if (!live.containsKey(name)) {
            live.getOrPut(name) { KeyInterpolant(name, box.id, saved.getString(name), null) }
        }
    }
    liveInterpolants[box.id] = live
    return live
}

/** The key type [name] in [box]: this run's definition, else one saved with the box. */
internal fun interpolantNamed(box: Box?, name: String): KeyInterpolant? {
    if (box != null) {
        liveInterpolants[box.id]?.get(name)?.let { return it }
        interpolantsFor(box)[name]?.let { return it }
    }
    return libraryInterpolants[name]
}

/** The library's types, as interpolants: every editor has them. */
internal val libraryInterpolants: Map<String, KeyInterpolant> by lazy {
    KeyTypeLibrary.entries.associate { it.name to KeyInterpolant(it.name, "", "", it.shape, it) }
}

/** The `interpolant("name") { body }` macro: keeps the body's source next to the lambda. */
fun Box.macro_interpolant(args: List<String>): Insert? {
    val raw = args.joinToString("")
    val open = raw.indexOf('{')
    val close = raw.lastIndexOf('}')
    if (open < 0 || close <= open) return null
    val head = raw.substring(0, open)
    val name = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").find(head)?.groupValues?.get(1) ?: return null
    val body = raw.substring(open + 1, close).trim()
    fun literal(text: String) = buildString {
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
    return Insert(here = "registerInterpolant(${literal(name)}, ${literal(body)}) {\n$body\n}")
}
