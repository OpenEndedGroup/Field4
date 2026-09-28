@file:afield.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.stdlib

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Quaternionf
import afield.*
import afield.Server.Companion.stop
import afield.four.util.FramePresentationLog
import afield.four.util.Options
import afield.four.util.floatToHalfBits
import kotlinx.coroutines.DelicateCoroutinesApi
import afield.stdlib.linalg.*
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.reflect.KProperty

/**
 * The general-purpose helpers that every Field box already has in scope.
 *
 * Box code is compiled with `import afield.stdlib.*` in its preamble and with `Box` as an
 * implicit receiver, so everything here — including the `Box.` extensions — can be called
 * unqualified from inside a box.
 *
 * The substantial part is the property-delegate machinery that `prop` and `@exported` generate
 * against: [Delegation] (stores on the receiver's own property bag), [Inheritance] (walks up
 * `parents` until some ancestor has the property, otherwise throws
 * [MissingInheritedPropertyException] with a message naming the property and the box), and
 * [InheritanceFromContext] (same walk, but prefers each box's live Kotlin variables over raw
 * properties).
 *
 * The rest is small conveniences: graph access (`named`, `+=` / `-=` for
 * connect/disconnect, `__m` markers); execution control (`begin`, `end`, [start], [animate],
 * `aside`, `cell`); frame-presentation logging ([frameLogStart], [frameLogStop],
 * [frameLogMark], [frameLogLine]); matrix and buffer building ([mat4], [mat3], `FloatBuffer.add`,
 * `IntBuffer.add`, `ByteBuffer.putf16`); and terse numeric/collection/string shorthands
 * (`Number.f` / `.i` / `.l` / `.d`, [fa], [ia], `Int.alphas`, `String.times`, `List.m` / `.s`,
 * `String.r`, and [highlightSyntax] behind `String.h` / `String.ss`).
 */

/**
 * Implemented by anything that can hand out a 4×4 transform, so callers can take a pose without
 * caring how it was stored.
 *
 * The one implementation in the tree is glTF's `TRS` (translate / rotate / scale kept separately),
 * whose [m] composes the three on every read — so treat the property as a computation, not a field, and
 * hold the result if you need it more than once in a frame.
 */
interface HasMatrix4f {
    /** This object's transform as a 4×4 matrix. May be recomputed on every read. */
    val m: Matrix4f
}

/**
 * The property delegate behind `prop` and `@exported`: reads and writes go to the receiver's own
 * property bag under the property's name, not to a Kotlin field.
 *
 * That is what makes such a property *persistent and visible* — it survives the box being re-executed,
 * shows up in the property inspector, and can be read by name from elsewhere. `var x by Delegation()`
 * on a `HasProperties` is the whole usage.
 *
 * The three optional hooks are what the generated code uses to add behaviour: [defaultValue] supplies
 * (and, if non-null, **stores**) a value on first read of an unset property, [onGet] observes each read,
 * and [hook] gets to inspect and rewrite each incoming value before it lands — receiving the current
 * value as well as the new one, so it can clamp, coerce or reject.
 *
 * Unchecked casts throughout: this trusts that the bag holds what the property's type says it does, so
 * a name collision between two differently-typed properties surfaces as a `ClassCastException` at the
 * read, not at the write.
 */
class Delegation<T>(
    var onGet: ((HasProperties, currentValue: T) -> Unit)? = null,
    var hook: ((inside: HasProperties, currentValue: T, incomingValue: T) -> T)? = null,
    val defaultValue: (() -> T)? = null,
) {
    /**
     * Reads the property from [f]'s bag, filling in [defaultValue] on the first read if it is unset.
     *
     * A non-null default is *written back* as a side effect, so the value the box sees is the value
     * anything else reading that property by name will see too. A default that itself returns null is
     * handed straight back without being stored.
     *
     * That guarantee is why the default is **re-read from the bag** after being written rather than
     * handed back directly: [HasProperties.setPropertyRaw] is allowed to normalise what it stores, and
     * for the topology sets (`children` / `parents` / `nested`) it does — `Boxes.asTopologySet` wraps a
     * plain `Set` in a `CopyOnWriteArraySet`, so the stored object is *not* the one passed in. Returning
     * `def` handed the caller a throwaway collection whose mutations went nowhere: `nestInto`'s
     * `parent.nested.add(this)` on a never-yet-read `nested` was silently lost, leaving `nestedIn` set
     * and the parent's `nested` empty. Re-reading costs one map lookup and makes the paragraph above
     * true for normalising properties as well as plain ones.
     */
    operator fun getValue(f: HasProperties, property: KProperty<*>): T {

        var a = f.getPropertyRaw(property.name)
        if (a == null && defaultValue != null) {
            val def = defaultValue()
            if (def != null) {
                f.setPropertyRaw(property.name, def as Any)
                a = f.getPropertyRaw(property.name) ?: def
            } else {
                return def as T
            }
        }

        val v = (a as T)
        onGet?.invoke(f, v)
        return v
    }

    /**
     * Writes the property into [f]'s bag, after letting [hook] rewrite the incoming value.
     *
     * The hook sees the current value (or the default, when there is none yet) alongside the new one and
     * returns what actually gets stored, so it is the place clamping and coercion live.
     *
     * Assigning null to a `Delegation<T?>` **erases** the property rather than storing a null, because
     * [HasProperties.setPropertyRaw] only takes a non-null value. Erased and never-set therefore read
     * back identically — which is what the nullable delegates in this tree already assume, none of them
     * having a non-null [defaultValue] that an erase could resurrect. The hook still runs first, so a
     * null write reaches reverse-link bookkeeping like `nestedIn`'s.
     */
    operator fun setValue(f: HasProperties, property: KProperty<*>, v: T) {
        var _v = v
        hook?.let {
            var a = f.getPropertyRaw(property.name) as T?
            if (a == null) a = defaultValue?.invoke() as T
            _v = it(f, a as T, _v)
        }
        val value = _v
        if (value == null) f.removePropertyRaw(property.name)
        else f.setPropertyRaw(property.name, value)
    }
}

/**
 * JavaScript-ish truthiness for a value out of a property bag, where you have an `Any?` and want a
 * yes/no.
 *
 * True for `true`, for a non-zero `Int`, and for a non-empty `String`. Everything else — null, `false`,
 * 0, and **every other type**, including a non-empty list or a `Long` of 1 — is false. That last part is
 * the trap: this is not a general-purpose "is set" test.
 */
fun Any?.isTrue(): Boolean {
    if (this == null) return false
    return when (this) {
        is Boolean -> this
        is Int -> if (this != 0) return true else false
        is String -> this.length > 0
        else -> false
    }
}

/**
 * The first box in this set called [s]. **Throws** `NoSuchElementException` when there is none.
 *
 * Deleted boxes are skipped by default — a deleted box lingers in the graph carrying a `_tombstone`
 * property rather than vanishing, and without this filter you can find one and wonder why your edits go
 * nowhere. Pass `ignoreDeleted = false` to include them.
 */
fun Set<Box>.named(s: String, ignoreDeleted: Boolean = true) =
    this.first { it.name == s && (!ignoreDeleted || !it.getPropertyRaw("_tombstone").isTrue()) }

/**
 * The box called [s] in this document — the usual way for one box to get hold of another.
 *
 * Searches `root.children`, so it finds any box in the same document regardless of where the call is
 * made from, and it is the document, not this box's own children. Names are not unique, so with two
 * matches you get an arbitrary one; **throws** `NoSuchElementException` with none. Deleted
 * (`_tombstone`) boxes are skipped unless you ask for them.
 */
fun Box.named(s: String, ignoreDeleted: Boolean = true) =
    this.root.children.first { it.name == s && (!ignoreDeleted || !it.getPropertyRaw("_tombstone").isTrue()) }

/**
 * Evaluates [code] as JavaScript in every browser session attached to this workspace, in the context of
 * this box.
 *
 * Fire-and-forget across all connected clients — nothing comes back, and there is no error channel, so a
 * mistake in [code] fails silently in the browser console. Use it to poke at the box's own DOM or to
 * drive something on the page that Kotlin has no handle on.
 */
fun Box.inBrowser(code: String) {
    inside.sessions.forEach {
        _send(it, Message("local-eval").payload(LocalEval(uid = id, prompt = code, kind = "eval")))
    }
}

/**
 * `parent += child` — connects [child] to this box, making it a child in the box graph.
 *
 * Connection is what property inheritance walks, so this is how a box comes to see its parent's exported
 * values. The graph is a DAG, not a tree: a box can be connected to several parents, and `+=` adds rather
 * than moves.
 */
operator fun Box.plusAssign(child: Box) {
    this.connect(child)
}

/** `parent -= child` — breaks the connection to [child], without deleting either box. */
operator fun Box.minusAssign(child: Box) {
    this.disconnect(child)
}

/**
 * Thrown when an inherited (`prop`-style) property has no value anywhere up the box graph and no default.
 *
 * Almost always a *timing* problem rather than a missing property: the parent box that exports the value
 * has not been executed yet, or threw while executing. The message names the property and the box, and
 * says how many parents were searched, so it can be read on its own.
 */
class MissingInheritedPropertyException(message: String) : IllegalStateException(message)

private fun Box.isMetarootBox(): Boolean = id == METAROOT_ID

private fun missingInheritedPropertyMessage(box: Box, propertyName: String, parentCount: Int): String {
    val boxName = box.name.ifBlank { box.id }
    if (box.isMetarootBox()) {
        return "Can't find a value for inherited property '$propertyName' in metaroot. " +
            "Metaroot is shared by open tabs/documents and has no parent boxes; this usually means the box in another open tab/document that exports '$propertyName' hasn't executed yet, or failed while executing."
    }
    return "Can't find a value for inherited property '$propertyName' in '$boxName' or any of its $parentCount parent boxes. " +
        "Are you sure the parent boxes that define '$propertyName' have been executed?"
}

private fun missingContextualInheritedPropertyMessage(box: Box, propertyName: String, candidates: List<String>): String {
    val boxName = box.name.ifBlank { box.id }
    val candidateList = candidates.joinToString("', '", prefix = "'", postfix = "'")
    if (box.isMetarootBox()) {
        return "Can't find a value for contextual inherited property '$propertyName' in metaroot. " +
            "Looked for $candidateList in metaroot properties and live Kotlin variables. " +
            "Metaroot is shared by open tabs/documents and has no parent boxes; this usually means the box in another open tab/document that exports '$propertyName' hasn't executed yet, or failed while executing."
    }
    return "Can't find a value for contextual inherited property '$propertyName' in '$boxName' or any of its parent boxes. " +
        "Looked for $candidateList in box properties and live Kotlin variables. " +
        "Are you sure the parent boxes that define '$propertyName' have been executed?"
}

/**
 * The lookup [Inheritance] performs, without a `KProperty` to perform it through: this box's own raw
 * property, then breadth-first up `parents` until some ancestor has one. `null` means nothing up the
 * graph has it.
 *
 * Extracted so a *second* reader of the same slot cannot drift from the first. FieldML is that second
 * reader — an interpreter has no generated `var Box.<name> by Inheritance<T>()` to read through, so
 * `prop` in a FieldML box calls this, and the two agree by construction rather than by comment. Any
 * third reader (a UI inspector, an agent tool) should do the same.
 *
 * Note this has **no** `defaultValue` and throws nothing: the delegate's failure
 * behaviour depends on the declared property's nullability, which only the delegate can see.
 */
fun inheritedPropertyRaw(from: Box, name: String): Any? = inheritedPropertyWalk(from, name).first

/**
 * [inheritedPropertyRaw] plus the number of *ancestors examined*, which is the only other thing
 * [Inheritance] needs — [missingInheritedPropertyMessage] reports it so a missing property reads as
 * the timing problem it usually is.
 */
internal fun inheritedPropertyWalk(from: Box, name: String): Pair<Any?, Int> {
    val search = ContextResolution.search(
        from,
        ContextResolution.Query.propertyNamed(name),
        ContextResolution.Policy.INHERITANCE,
        ContextResolution.Mechanism.INHERITANCE,
    )
    return search.resolution?.value to search.ancestorsExamined
}

/**
 * The property delegate that makes a value *inherited*: reading it looks in this box, then breadth-first
 * up through `parents` until some ancestor has it.
 *
 * This is how a child box picks up a parent's settings without being told about them — the pattern behind
 * `prop`. Writing always writes **locally**, shadowing the ancestor's value for this box and everything
 * below it rather than changing theirs.
 *
 * Breadth-first, so with two ancestors defining the same name the nearer one wins and equally distant ones
 * resolve arbitrarily. When nothing has it: [defaultValue] if you gave one, else null for a nullable
 * property, else [MissingInheritedPropertyException].
 *
 * The walk itself is [inheritedPropertyWalk], shared with FieldML's `prop`.
 */
class Inheritance<T>(val defaultValue: T? = null) {
    /**
     * The value from this box or the nearest ancestor that has it.
     *
     * @throws MissingInheritedPropertyException when nothing up the graph has the property, there is no
     * [defaultValue], and the property's type is not nullable — usually meaning the exporting box has not
     * run yet.
     */
    operator fun getValue(f: Box, property: KProperty<*>): T {
        val (found, num) = inheritedPropertyWalk(f, property.name)
        if (found != null) return found as T
        if (defaultValue != null) return defaultValue as T
        if (!property.returnType.isMarkedNullable) {
            throw MissingInheritedPropertyException(missingInheritedPropertyMessage(f, property.name, num))
        }
        return null as T
    }

    /** Writes the value **locally**, shadowing any ancestor's value rather than modifying it. */
    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) = f.setPropertyRaw(property.name, v as Any)
}

/**
 * [Inheritance], but it can also see the *live Kotlin variables* of the boxes it walks — so a plain
 * `val speed = 3.0` in a parent box is inheritable, with no property or annotation needed.
 *
 * The lookup order per box is: live Kotlin variables first, then raw properties. A leading underscore is
 * optional, so `_speed` also matches a variable called `speed`, which is how the generated names stay out
 * of the way of the names a person types.
 *
 * Reading the parent's live variable means you get whatever it holds *now*, including the value from a
 * half-finished edit-and-rerun. Failure behaviour is as [Inheritance], and the exception message lists
 * every name that was tried.
 */
class InheritanceFromContext<T>(val defaultValue: T? = null) {
    /**
     * The value from this box or the nearest ancestor, preferring live Kotlin variables over properties.
     *
     * @throws MissingInheritedPropertyException when neither a variable nor a property of any candidate
     * name exists up the graph, there is no [defaultValue], and the type is not nullable.
     */
    operator fun getValue(f: Box, property: KProperty<*>): T {
        val candidateNames = contextCandidateNames(property.name)
        val found = ContextResolution.walk(
            f,
            ContextResolution.Query(
                description = property.name,
                names = candidateNames,
                acceptProperty = { it != null },
                acceptVariable = { true },
            ),
            ContextResolution.Policy.INHERITANCE_FROM_CONTEXT,
            ContextResolution.Mechanism.INHERITANCE_FROM_CONTEXT,
        )
        if (found != null) return found.value as T

        if (defaultValue != null) return defaultValue as T
        if (!property.returnType.isMarkedNullable) {
            throw MissingInheritedPropertyException(
                missingContextualInheritedPropertyMessage(f, property.name, candidateNames)
            )
        }
        return null as T
    }

    /** Writes the value **locally** as a raw property, shadowing whatever an ancestor had. */
    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) = f.setPropertyRaw(property.name, v as Any)

    private fun contextCandidateNames(propertyName: String): List<String> {
        val names = linkedSetOf(propertyName)
        if (propertyName.startsWith("_")) names.add(propertyName.substring(1))
        return names.toList()
    }
}

/**
 * `(0..9) { i -> … }` — calls the block once per value in the range and collects the results.
 *
 * `map` written as a call, which reads better when the whole point of the loop is to build geometry:
 * `(0..9) { i -> vec(i, 0) }`. Inclusive of both ends, like any Kotlin range.
 */
inline operator fun <T> IntRange.invoke(to: (Int) -> T): List<T> = this.map { to(it) }

/**
 * `10 { i -> … }` — calls the block for `i` in `0 until 10` and collects the results.
 *
 * The terse "do this n times and keep the answers" form. Note the range is **exclusive** of the number
 * itself, so `10 { it }` is `0..9`.
 */
inline operator fun <T> Int.invoke(to: (Int) -> T): List<T> = (0 until this).map { to(it) }

/**
 * `10 alphas { a -> … }` — calls the block with that many evenly spaced values from `0.0` to `1.0`
 * **inclusive**, and collects the results.
 *
 * The parameterise-a-curve loop: `20 alphas { a -> vec(cos(a * 2 * PI), sin(a * 2 * PI)) }` closes exactly,
 * because the last value really is 1.0. Beware `1 alphas { }`: it divides zero by zero and hands the block
 * a single **NaN** — which will not be caught by an `isInfinite` guard. The no-argument [alphas] does not
 * have that edge.
 */
inline infix fun <T> Int.alphas(to: (Double) -> T): List<T> = (0 until this).map { to(it / (this - 1.0)) }

/**
 * This many values evenly spaced from `0.0` to `1.0` **inclusive**, as a lazy sized sequence.
 *
 * `for (a in 20.alphas())` when you want the fractions themselves rather than a mapped list. Nothing is
 * allocated per element, and the `size` is known up front, so it can drive a buffer allocation.
 *
 * Two edges to know: `1.alphas()` yields a single `0.0` (rather than the NaN the infix `alphas` would
 * give), and a negative count yields nothing instead of throwing.
 */
fun Int.alphas(): SizedIterable<Double> {
    val n = this.coerceAtLeast(0)
    return sizedIterable(n) {
        var i = 0
        object : Iterator<Double> {
            override fun hasNext(): Boolean = i < n

            override fun next(): Double {
                if (!hasNext()) throw NoSuchElementException("No more alpha values")
                val value = if (n <= 1) 0.0 else i / (n - 1.0)
                i++
                return value
            }
        }
    }
}

/** `"ab" * 3` — the string repeated [ln] times. Zero or fewer gives the empty string. */
operator fun String.times(ln: Int): String {
    // Not just an early-out: `StringBuilder(negative)` throws NegativeArraySizeException before the loop
    // runs, so without this `"ab" * -1` crashed instead of returning the documented empty string.
    if (ln <= 0) return ""

    var s = StringBuilder(this.length * ln)
    val thisString = this // why has this@String stopped working?
    ln { s.append(this) }
    return s.toString()
}


/**
 * Runs this box's whole `code` property, as if someone had pressed the run key on it.
 *
 * The programmatic run: one box can start another, which is how a "conductor" box drives a set of others.
 * It launches and returns immediately rather than waiting, and the run's output goes to *that* box's output
 * area, not the caller's.
 *
 * [strand] names the execution lane the code goes into. Two launches on the same strand replace each other,
 * which is exactly how re-running a box supersedes its previous animation; distinct strand names let a box
 * run several things at once. [end] stops whatever is running.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.begin(strand: String = "default") {
    val box = this
    val source = box.getPropertyRaw("code") as? String ?: ""
    val execute = Execute(box.id, "launch", 1, source, false, strand = strand)

    FlightRecorder.recordLaunchEntrypoint("stdlib.begin", box, execute, strand = strand)
    box.ke.launchAll(box, execute, source, strand = strand, {
//        send(it)
    })
}

/**
 * Stops everything this box is running — every strand, not just the default one — and disposes any
 * afield.four renderer it owns, closing that window.
 *
 * The stop key, callable from code. Cancellation is not graceful: running coroutines are cancelled where they
 * stand, so anything that must be cleaned up belongs in a `finally` inside the box, not after the call to
 * this.
 */
fun Box.end() {
    stop(this, entrypoint = "stdlib.end")
}

/**
 * Starts recording the frame-presentation log — per-frame timing for the graphics pipeline — and returns the
 * path it is being written to.
 *
 * The first thing to reach for when frames are janky and you want to know where the time went. With no
 * [path] a location is chosen for you, and [append] decides whether an existing log is added to or replaced.
 *
 * [waitAfterPresent] changes what is measured, not just how it is reported: it makes each frame wait for the
 * present to complete, which gives true per-frame numbers at some cost to throughput. It defaults to the
 * process-wide option, so leave it alone unless you are chasing a presentation stall specifically.
 *
 * Call [frameLogStop] when done; the log keeps growing until you do.
 */
fun frameLogStart(
    path: String? = null,
    waitAfterPresent: Boolean = Options.framePresentationLogWaitAfterPresent,
    append: Boolean = false
): String = FramePresentationLog.start(path, waitAfterPresent, append)

/** Stops frame-presentation logging and closes the file. Harmless if nothing was being logged. */
fun frameLogStop() {
    FramePresentationLog.stop()
}

/**
 * Drops a named marker into the frame log at the current frame — "this is where I clicked the thing".
 *
 * The point of a marker over a plain line is that it is tied to a frame boundary, so you can line an event up
 * against the frame that was slow.
 */
fun frameLogMark(message: String) {
    FramePresentationLog.mark(message)
}

/** Appends a free-text line to the frame log, with no frame association. See [frameLogMark] for events. */
fun frameLogLine(message: String) {
    FramePresentationLog.addLine(message)
}


/**
 * Starts a new [Frames] scope and runs [a] inside it.
 *
 * This is a small convenience wrapper around `frames { ... }` that returns the
 * created [Frames] instance.
 */
fun start(a: suspend Frames.() -> Unit): Frames {
    return frames {
        a()
    }
}

/**
 * Thin wrapper around [frames] that starts a frame-based animation from a plain
 * suspend lambda (no [Frames] receiver). The body is responsible for advancing
 * frames if it needs multi-frame behavior.
 */
fun start(a: suspend () -> Unit): Frames {
    return frames {
        a()
    }
}

/**
 * Creates an animation loop from a lambda.
 * The lambda will be executed every frame when this function is launched.
 *
 * Usage:
 * ```
 * foo()
 * bar(10.0)
 *
 * animate {
 *     gux()
 * }
 * ```
 * This calls foo() and bar() once, then calls gux() every frame.
 */
fun animate(block: suspend Frames.() -> Unit): Frames {
    return eachFrame(block)
}

// matrix help

private fun Matrix4f.duplicate(): Matrix4f {
    return Matrix4f(this)
}

private fun Matrix3f.duplicate(): Matrix3f {
    return Matrix3f(this)
}

/**
 * Writes one element of the matrix by a single **row-major** index: `n / 4` is the row and `n % 4` the
 * column.
 *
 * Note that the generated `Matrix4f` now carries its own `operator set(index, value)` member which indexes
 * the *other* way (`column * 4 + row`), and in Kotlin a member wins over an extension — so `m[5] = 1f` in a
 * box reaches the member, and this extension only applies where the member does not. The two agree on the
 * diagonal and disagree everywhere else, so index a matrix by `m[column, row]` and avoid the question.
 */
operator fun Matrix4f.set(n: Int, v: Float) {
    this[n % 4, n / 4] = v
}

/**
 * Builds a 4×4 transform from any combination of a starting matrix, a translation, a rotation and a scale —
 * everything optional, so `mat4()` is the identity.
 *
 * Composed as `base · S · R`, so a point is **rotated first and scaled afterwards** — with a non-uniform
 * [s] that is visibly not the same as scaling in the object's own frame, so the argument order here is not
 * the order the transforms apply in. [base] is **copied**, never modified. The translation is *set*, not
 * composed: whatever [base] had in its translation column is replaced by [t], and the rotation does not
 * turn [t]. So this builds a pose ("here, facing this way, this big") rather than concatenating transforms —
 * for that, multiply matrices yourself.
 */
fun mat4(base: Matrix4f? = null, t: Vector3f? = null, r: Quaternionf? = null, s: Vector3f? = null): Matrix4f {
    var m = base?.duplicate() ?: Matrix4f()
    if (s != null) m.scale(s)
    if (r != null) m.rotate(r)
    if (t != null) m.setTranslation(t)
    return m
}

/**
 * Builds a 3×3 matrix from an optional starting matrix, rotation and scale — the linear part of [mat4], for
 * normals and 2D transforms.
 *
 * Composed as `base · S · R` — like [mat4], so a point is rotated first and scaled afterwards — with
 * [base] copied rather than modified. The `t` parameter is accepted and **ignored**: a 3×3 matrix has
 * nowhere to put a translation. Use [mat4] if you need one.
 */
fun mat3(base: Matrix3f? = null, t: Vector3f? = null, r: Quaternionf? = null, s: Vector3f? = null): Matrix3f {
    var m = base?.duplicate() ?: Matrix3f()
    if (s != null) m.scale(s)
    if (r != null) m.rotate(r)
    return m
}

// buffer help

/**
 * Appends two floats at the buffer's current position, advances it by two, and returns the buffer so calls
 * chain.
 *
 * The vertex-writing idiom: `buf.add(x, y).add(x2, y2)`. Running past the limit throws with a message that
 * reports the buffer's **whole limit in two-float groups** — "limit 512x2" means the buffer holds 512 pairs
 * in total, not that 512 more calls were available (by then, none were).
 */
fun FloatBuffer.add(x: Float, y: Float): FloatBuffer {
    try {
        this.put(x).put(y)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 2}x2").initCause(f)
    }
}

/**
 * Appends a [Vec2]'s two components at the current position, advances by two, and returns the buffer.
 *
 * Unlike the scalar overloads this writes through the vector's own absolute-position `get`, so overrunning the
 * limit surfaces as an `IndexOutOfBoundsException` rather than the friendly overflow message.
 */
fun FloatBuffer.add(a: Vector2f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 2)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 2}x2").initCause(f)
    }
}

/** Appends a [Vec3]'s three components at the current position and advances by three. Chainable. */
fun FloatBuffer.add(a: Vector3f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 3)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 3}x3").initCause(f)
    }
}

/** Appends a [Vec4]'s four components at the current position and advances by four. Chainable. */
fun FloatBuffer.add(a: Vector4f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 4)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 4}x4").initCause(f)
    }
}

/**
 * Appends three float components and returns this buffer for chaining.
 *
 * Throws if there is not enough remaining space; the overflow message reports
 * the buffer limit in three-float groups.
 */
fun FloatBuffer.add(x: Float, y: Float, z: Float): FloatBuffer {
    try {

        this.put(x).put(y).put(z)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 3}x3").initCause(f)
    }
}

/**
 * Appends any number of values, each converted to `Float`, and returns the buffer.
 *
 * The convenient one — `buf.add(1, 2.5, 3f)` — at the cost of boxing every argument, so prefer the fixed-arity
 * overloads in a per-frame loop over thousands of vertices.
 */
fun FloatBuffer.add(vararg x: Number): FloatBuffer {
    try {
        x.forEach { this.put(it.toFloat()) }
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit()}").initCause(f)
    }
}

/**
 * Appends any number of values, each converted to `Int` (truncating, not rounding), and returns the buffer for
 * chaining — the index-buffer counterpart of `FloatBuffer.add`.
 *
 * Named `add` rather than `put`, because `IntBuffer.put(index, data)` already means "write at an absolute
 * index", and reusing it here would have shadowed that.
 */
fun IntBuffer.add(vararg x: Number): IntBuffer {
    try {
        x.forEach { this.put(it.toInt()) }
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit()}").initCause(f)
    }
}

/**
 * True while this box is running a *fragment* — a selected region or an `alt-return` block — rather than its
 * whole body.
 *
 * Set by the execution machinery around the run, and the flag [aside] tests. Read it if you want code that
 * behaves differently when someone is poking at part of the box; false during a normal full run.
 */
var Box._currentlyExecutingFragment by Delegation<Boolean>(defaultValue = { false })

/**
 * A block that runs **only** when you explicitly ask for it — put the cursor inside and press
 * option/alt-return — and is skipped by an ordinary run of the box.
 *
 * The place to keep the one-off experiments, the expensive setup and the debug dumps that would otherwise have
 * to live commented out. [name] is a label for the editor's benefit and does not affect execution.
 */
@Doc("used to mark a particular block in the editor that only executes when the cursor is inside the block and you press option/alt-return")
inline fun Box.aside(name: String = "", block: () -> Unit) {
    if (this._currentlyExecutingFragment) block()
}

/**
 * Marks a block as one notebook-style cell, so the editor can treat it as a unit for auto-execution and
 * re-running.
 *
 * At runtime it is nothing at all — it calls the block immediately and returns its value, so wrapping code in
 * `cell { }` changes only what the editor does with it, never what it computes.
 */
@Doc("used to mark a particular block in the editor, this helps with grouping auto-execution")
inline fun <T> Box.cell(block: () -> T) = block()

/** A `FloatArray` from any numbers, converted for you — `fa(1, 2.5, 3f)`. The terse array literal. */
fun fa(vararg f: Number): FloatArray = f.map { it.toFloat() }.toFloatArray()

/** An `IntArray` from any numbers, **truncated** toward zero rather than rounded — `ia(1, 2.9)` is `[1, 2]`. */
fun ia(vararg f: Number): IntArray = f.map { it.toInt() }.toIntArray()

/**
 * This number as a `Float` — `3.d.f`, `x.f` — the shorthand that keeps Kotlin's numeric conversions out of the
 * way in graphics code.
 *
 * Narrowing from `Double` loses precision, as always.
 */
val Number.f
    get() = this.toFloat()

/** This number as an `Int`, **truncated** toward zero: `1.9.i` is 1 and `(-1.9).i` is -1. */
val Number.i
    get() = this.toInt()

/** This `Long` as an `Int`, wrapping silently if it does not fit — the low 32 bits, not a clamp. */
val Long.i
    get() = this.toInt()

/** This number as a `Long`, truncated toward zero if it was fractional. */
val Number.l
    get() = this.toLong()

/** This number as a `Double`. Widening, so nothing is lost — except that a `Float`'s error comes with it. */
val Number.d
    get() = this.toDouble()


private fun escapeSyntaxHtml(input: String): String {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

/**
 * HTML-escapes a string and wraps its numbers, punctuation and brackets in `<span class='ss-…'>`, so a value
 * printed into a box output reads as code rather than as prose.
 *
 * Purely lexical and language-agnostic: it finds floats, integers, single punctuation characters and brackets,
 * and knows nothing of keywords, strings or comments. Tabs become `<span class='tab'></span>`. Everything else
 * is escaped, so the result is always safe to drop into HTML. Reachable more briefly as `String.h` / `String.ss`.
 */
fun highlightSyntax(input: String): String {
    // Named groups: float, int, punct, bracket
    val pattern = Regex(
        """
        (?<float>[+-]?\d+\.\d+)
        |(?<int>[+-]?\d+)
        |(?<punct>[.,;:?!+\-*/%=<>|&])
        |(?<bracket>[\[\]\{\}\(\)])
        """.trimIndent(), RegexOption.COMMENTS
    )

    val groupNames = listOf("float", "int", "punct", "bracket")

    var cursor = 0
    return buildString(input.length + 64) {
        pattern.findAll(input).forEach { m ->
            append(escapeSyntaxHtml(input.substring(cursor, m.range.first)).replace("\t", "<span class='tab'></span>"))
            val replacement = groupNames.firstNotNullOfOrNull { name ->
                m.groups[name]?.value?.let { "<span class='ss-$name'>${escapeSyntaxHtml(it)}</span>" }
            }
            append(replacement ?: escapeSyntaxHtml(m.value))
            cursor = m.range.last + 1
        }
        append(escapeSyntaxHtml(input.substring(cursor)).replace("\t", "<span class='tab'></span>"))
    }
}

/** This string [highlightSyntax]-ed — HTML-escaped with numbers and punctuation span-wrapped. */
val String.h: String
    get() = highlightSyntax(this)

/** Alias for [String.h]: this string [highlightSyntax]-ed, ready to print into a box output. */
val String.ss: String
    get() = highlightSyntax(this)


/**
 * A named property bag attached to a box — somewhere to hang state that belongs to *part* of a box rather than to
 * the box itself.
 *
 * Obtained by name with [Box.__m], which creates it on first use and returns the same instance thereafter, so it
 * survives across frames while the box is running. Its properties live in a plain in-memory map: they are **not**
 * saved with the document and are not visible to the property inspector, unlike a real box property.
 *
 * It presents itself as a leaf of the box graph — no children, and its single parent is the owning box — so
 * inheritance-shaped code can walk into it without special-casing.
 */
data class Marker(val name: String, val box: Box) : HasProperties, HasHierarchy {
    /** This marker's state, as a plain map. In memory only; nothing here is persisted with the document. */
    val properties = mutableMapOf<String, Any>()

    /** Stores a value under [name]. The `doResync` flag is ignored — a marker has no client to notify. */
    override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
        properties[name] = value
    }

    /** The value stored under [name], or [default] if this marker has never been given one. */
    override fun getPropertyRaw(name: String, default: Any?): Any? {
        return properties[name] ?: default
    }

    /** Erases [name]. The `doResync` flag is ignored — a marker has no client to notify. */
    override fun removePropertyRaw(name: String, doResync: Boolean) {
        properties.remove(name)
    }

    /** Always empty: a marker is a leaf. Assigning to it does nothing. */
    override var children: MutableSet<Box>
        get() = mutableSetOf<Box>()
        set(value) {}

    /** Just the owning box, so a parent walk reaches it. Assigning to it does nothing. */
    override var parents: MutableSet<Box>
        get() = mutableSetOf(box)
        set(value) {}
}

/**
 * The [Marker] called [s] on this box, created on first use — the get-or-create for per-box scratch state.
 *
 * `__m("trail") { properties["points"] = mutableListOf<Vec3>() }`: [onCreation] runs **only** the first time,
 * which is what makes this safe to call every frame from inside an animation loop. The marker and its contents
 * outlive an individual frame but not the process, and are not saved with the document.
 */
fun Box.__m(s: String, onCreation: Marker.() -> Unit = {}) = this.markers.computeIfAbsent(s) {
    val m = Marker(s, box = this)
    m.onCreation()
    m
}

/**
 * `list[2..5]` — a new list of the elements in that **inclusive** range, Python-slice syntax with Kotlin's range
 * semantics.
 *
 * A copy, not a view. **Throws** `IndexOutOfBoundsException` if the range runs off the end, unlike Python, which
 * would return a short list with no error.
 */
operator fun <T> List<T>.get(range: IntRange) = this.slice(range)


/**
 * Appends [value] as a 16-bit half float, **little-endian**, two bytes, and returns the buffer for chaining.
 *
 * The packing step for fp16 vertex attributes and push constants. Half floats top out around ±65504 and carry
 * roughly three decimal digits, so a value past that range becomes an infinity and a very small one becomes zero —
 * fine for colours and normals, not for large world coordinates. Byte order is written explicitly here and does
 * **not** follow the buffer's own `order()`.
 */
fun ByteBuffer.putf16(value: Float): ByteBuffer {
    val h = floatToHalfBits(value).toInt() and 0xFFFF
    put((h and 0xFF).toByte())
    put(((h ushr 8) and 0xFF).toByte())
    return this
}


/** A pair of 16-bit half floats, for packed vertex attributes and uniforms. Half the bytes of a [Vec2], a
 * fraction of the precision — see `ByteBuffer.putf16` for the range. */
typealias Vec2h = afield.four.util.Vec2h

/** Three 16-bit half floats — the packed transit form of a [Vec3] for GPU buffers. */
typealias Vec3h = afield.four.util.Vec3h

/** Four 16-bit half floats — the packed transit form of a [Vec4], and enough precision for a colour. */
typealias Vec4h = afield.four.util.Vec4h
// Vec8h dropped: the 8-lane fp16 vector is the real generated afield.stdlib.linalg.Vector8h.


/** This list as a `MutableList` — a **copy**, so editing it leaves the original alone. */
val <T> List<T>.m: MutableList<T>
    get() = this.toMutableList()

/** This list as a `Set`, dropping duplicates and keeping first-seen order. A copy. */
val <T> List<T>.s: Set<T>
    get() = this.toSet()


/**
 * This string compiled as a [Regex] — `"\\d+".r`.
 *
 * Compiles on every read, so hoist it out of a loop. **Throws** `PatternSyntaxException` on a malformed pattern.
 */
val String.r: Regex
    get() = Regex(this)
