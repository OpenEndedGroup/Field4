@file:afield.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.stdlib

import afield.*
import afield.Server.Companion.stop
import afield.four.util.FramePresentationLog
import afield.four.util.Options
import afield.four.util.floatToHalfBits
import kotlinx.coroutines.DelicateCoroutinesApi
import org.joml.*
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.reflect.KProperty

interface HasMatrix4f {
    val m: Matrix4f
}

class Delegation<T>(
    var onGet: ((HasProperties, currentValue: T) -> Unit)? = null,
    var hook: ((inside: HasProperties, currentValue: T, incomingValue: T) -> T)? = null,
    val defaultValue: (() -> T)? = null,
) {
    operator fun getValue(f: HasProperties, property: KProperty<*>): T {

        var a = f.getPropertyRaw(property.name)
        if (a == null && defaultValue != null) {
            val def = defaultValue()
            if (def != null) {
                f.setPropertyRaw(property.name, def as Any)
                a = def
            } else {
                return def as T
            }
        }

        val v = (a as T)
        onGet?.invoke(f, v)
        return v
    }

    operator fun setValue(f: HasProperties, property: KProperty<*>, v: T) {
        var _v = v
        hook?.let {
            var a = f.getPropertyRaw(property.name) as T?
            if (a == null) a = defaultValue?.invoke() as T
            _v = it(f, a as T, _v)
        }
        f.setPropertyRaw(property.name, _v as Any)
    }
}

fun Any?.isTrue(): Boolean {
    if (this == null) return false
    return when (this) {
        is Boolean -> this
        is Int -> if (this != 0) return true else false
        is String -> this.length > 0
        else -> false
    }
}

fun Set<Box>.named(s: String, ignoreDeleted: Boolean = true) =
    this.first { it.name == s && (!ignoreDeleted || !it.getPropertyRaw("_tombstone").isTrue()) }

fun Box.named(s: String, ignoreDeleted: Boolean = true) =
    this.root.children.first { it.name == s && (!ignoreDeleted || !it.getPropertyRaw("_tombstone").isTrue()) }

fun Box.inBrowser(code: String) {
    inside.sessions.forEach {
        _send(it, Message("local-eval").payload(LocalEval(uid = id, prompt = code, kind = "eval")))
    }
}

operator fun Box.plusAssign(child: Box) {
    this.connect(child)
}

operator fun Box.minusAssign(child: Box) {
    this.disconnect(child)
}

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

class Inheritance<T>(val defaultValue: T? = null) {
    operator fun getValue(f: Box, property: KProperty<*>): T {
        val a = f.getPropertyRaw(property.name)
        if (a != null) return a as T
        val seen = mutableSetOf<Box>()
        seen.add(f)
        var fringe = setOf(f)
        var num = 0
        while (fringe.isNotEmpty()) {
            val nextFringe = fringe.flatMap { it.parents }.toSet() - seen
            seen.addAll(nextFringe)

            nextFringe.forEach {
                val a = it.getPropertyRaw(property.name)
                num++
                if (a != null) return a as T
            }
            fringe = nextFringe
        }
        if (defaultValue != null) return defaultValue as T
        if (!property.returnType.isMarkedNullable) {
            throw MissingInheritedPropertyException(missingInheritedPropertyMessage(f, property.name, num))
        }
        return null as T
    }

    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) = f.setPropertyRaw(property.name, v as Any)
}

class InheritanceFromContext<T>(val defaultValue: T? = null) {
    operator fun getValue(f: Box, property: KProperty<*>): T {
        val candidateNames = contextCandidateNames(property.name)
        val local = findInBox(f, property.name, candidateNames)
        if (local.first) return local.second as T

        val seen = mutableSetOf<Box>()
        seen.add(f)
        var fringe = setOf(f)
        while (fringe.isNotEmpty()) {
            val nextFringe = fringe.flatMap { it.parents }.toSet() - seen
            seen.addAll(nextFringe)

            nextFringe.forEach { parent ->
                val found = findInBox(parent, property.name, candidateNames)
                if (found.first) return found.second as T
            }
            fringe = nextFringe
        }

        if (defaultValue != null) return defaultValue as T
        if (!property.returnType.isMarkedNullable) {
            throw MissingInheritedPropertyException(
                missingContextualInheritedPropertyMessage(f, property.name, candidateNames)
            )
        }
        return null as T
    }

    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) = f.setPropertyRaw(property.name, v as Any)

    private fun contextCandidateNames(propertyName: String): List<String> {
        val names = linkedSetOf(propertyName)
        if (propertyName.startsWith("_")) names.add(propertyName.substring(1))
        return names.toList()
    }

    private fun findInBox(box: Box, propertyName: String, names: List<String>): Pair<Boolean, Any?> {
        // First prefer live KotlinContext values from this box.
        names.forEach { name ->
            val info = box.ki.context.vars[name]
            if (info != null) return true to info.value
        }

        // Fallback to raw properties for compatibility and function exports.
        val direct = box.getPropertyRaw(propertyName)
        if (direct != null) return true to direct
        names.forEach { name ->
            val value = box.getPropertyRaw(name)
            if (value != null) return true to value
        }

        return false to null
    }
}

inline operator fun <T> IntRange.invoke(to: (Int) -> T): List<T> = this.map { to(it) }
inline operator fun <T> Int.invoke(to: (Int) -> T): List<T> = (0 until this).map { to(it) }
inline infix fun <T> Int.alphas(to: (Double) -> T): List<T> = (0 until this).map { to(it / (this - 1.0)) }

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

operator fun String.times(ln: Int): String {

    var s = StringBuilder(this.length * ln)
    val thisString = this // why has this@String stopped working?
    ln { s.append(this) }
    return s.toString()
}


@OptIn(DelicateCoroutinesApi::class)
fun Box.begin(strand: String = "default") {
    var box = this
    val execute = Execute(box.id, "launch", 1, code, false, strand = strand)

    FlightRecorder.recordLaunchEntrypoint("stdlib.begin", box, execute, strand = strand)
    box.ke.launchAll(box, execute, code, strand = strand, {
//        send(it)
    })
}

fun Box.end() {
    stop(this, entrypoint = "stdlib.end")
}

fun frameLogStart(
    path: String? = null,
    waitAfterPresent: Boolean = Options.framePresentationLogWaitAfterPresent,
    append: Boolean = false
): String = FramePresentationLog.start(path, waitAfterPresent, append)

fun frameLogStop() {
    FramePresentationLog.stop()
}

fun frameLogMark(message: String) {
    FramePresentationLog.mark(message)
}

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

operator fun Matrix4f.set(n: Int, v: Float) {
    this[n % 4, n / 4] = v
}

fun mat4(base: Matrix4f? = null, t: Vector3f? = null, r: Quaternionf? = null, s: Vector3f? = null): Matrix4f {
    var m = base?.duplicate() ?: Matrix4f()
    if (s != null) m.scale(s)
    if (r != null) m.rotate(r)
    if (t != null) m.setTranslation(t)
    return m
}

fun mat3(base: Matrix3f? = null, t: Vector3f? = null, r: Quaternionf? = null, s: Vector3f? = null): Matrix3f {
    var m = base?.duplicate() ?: Matrix3f()
    if (s != null) m.scale(s)
    if (r != null) m.rotate(r)
    return m
}

// buffer help

fun FloatBuffer.add(x: Float, y: Float): FloatBuffer {
    try {
        this.put(x).put(y)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 2}x2").initCause(f)
    }
}

fun FloatBuffer.add(a: Vector2f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 2)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 2}x2").initCause(f)
    }
}

fun FloatBuffer.add(a: Vector3f): FloatBuffer {
    try {
        a.get(this)
        this.position(this.position() + 3)
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit() / 3}x3").initCause(f)
    }
}

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

fun FloatBuffer.add(vararg x: Number): FloatBuffer {
    try {
        x.forEach { this.put(it.toFloat()) }
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit()}").initCause(f)
    }
}

/**
 * ugh! this collides with IntBuffer.put(index, data)
 */
fun IntBuffer.add(vararg x: Number): IntBuffer {
    try {
        x.forEach { this.put(it.toInt()) }
        return this
    } catch (f: BufferOverflowException) {
        throw Exception("overflow - tried to put two much data into a buffer of limit ${limit()}").initCause(f)
    }
}

var Box._currentlyExecutingFragment by Delegation<Boolean>(defaultValue = { false })

@Doc("used to mark a particular block in the editor that only executes when the cursor is inside the block and you press option/alt-return")
inline fun Box.aside(name: String = "", block: () -> Unit) {
    if (this._currentlyExecutingFragment) block()
}

@Doc("used to mark a particular block in the editor, this helps with grouping auto-execution")
inline fun <T> Box.cell(block: () -> T) = block()

fun fa(vararg f: Number): FloatArray = f.map { it.toFloat() }.toFloatArray()
fun ia(vararg f: Number): IntArray = f.map { it.toInt() }.toIntArray()

val Number.f
    get() = this.toFloat()
val Number.i
    get() = this.toInt()
val Long.i
    get() = this.toInt()
val Number.l
    get() = this.toLong()
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

val String.h: String
    get() = highlightSyntax(this)

val String.ss: String
    get() = highlightSyntax(this)


data class Marker(val name: String, val box: Box) : HasProperties, HasHierarchy {
    val properties = mutableMapOf<String, Any>()
    override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
        properties[name] = value
    }

    override fun getPropertyRaw(name: String, default: Any?): Any? {
        return properties[name] ?: default
    }

    override var children: MutableSet<Box>
        get() = mutableSetOf<Box>()
        set(value) {}
    override var parents: MutableSet<Box>
        get() = mutableSetOf(box)
        set(value) {}
}

fun Box.__m(s: String, onCreation: Marker.() -> Unit = {}) = this.markers.computeIfAbsent(s) {
    val m = Marker(s, box = this)
    m.onCreation()
    m
}

operator fun <T> List<T>.get(range: IntRange) = this.slice(range)


fun ByteBuffer.putf16(value: Float): ByteBuffer {
    val h = floatToHalfBits(value).toInt() and 0xFFFF
    put((h and 0xFF).toByte())
    put(((h ushr 8) and 0xFF).toByte())
    return this
}


typealias Vec2h = afield.four.util.Vec2h
typealias Vec3h = afield.four.util.Vec3h
typealias Vec4h = afield.four.util.Vec4h
typealias Vec8h = afield.four.util.Vec8h


val <T> List<T>.m: MutableList<T>
    get() = this.toMutableList()

val <T> List<T>.s: Set<T>
    get() = this.toSet()


val String.r: Regex
    get() = Regex(this)
