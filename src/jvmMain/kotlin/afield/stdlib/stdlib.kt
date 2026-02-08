package afield.stdlib

import afield.*
import afield.Server.Companion.stop
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
        // doing this means we can't declare props without
//        throw NullPointerException("can't find property ${property.name} in ${f.name} or any of its ${num} ancestors")
        return null as T
    }

    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) = f.setPropertyRaw(property.name, v as Any)
}

inline operator fun <T> IntRange.invoke(to: (Int) -> T): List<T> = this.map { to(it) }
inline operator fun <T> Int.invoke(to: (Int) -> T): List<T> = (0 until this).map { to(it) }
inline infix fun <T> Int.alphas(to: (Double) -> T): List<T> = (0 until this).map { to(it / (this - 1.0)) }

operator fun String.times(ln: Int): String {

    var s = StringBuilder(this.length * ln)
    val thisString = this // why has this@String stopped working?
    ln { s.append(this) }
    return s.toString()
}


@OptIn(DelicateCoroutinesApi::class)
fun Box.begin(strand: String = "default") {
    var box = this

    box.ke.launchAll(box, Execute(box.id, "launch", 1, code, false), code, strand = strand, {
//        send(it)
    })
}

fun Box.end() {
    stop(this)
}


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


fun highlightSyntax(input: String): String {
    // Named groups: float, int, punct, bracket
    val pattern = Regex(
        """
        (?<float>\d+\.\d+)
        |(?<int>\d+)
        |(?<punct>[.,;:?!])
        |(?<bracket>[\[\]\{\}\(\)])
        """.trimIndent(), RegexOption.COMMENTS
    )

    val groupNames = listOf("float", "int", "punct", "bracket")


    return pattern.replace(input) { m ->
        // Find the first named group that matched and wrap it
        groupNames.firstNotNullOfOrNull { name ->
                m.groups[name]?.value?.let { "<span class='ss-$name'>$it</span>" }
            } ?: m.value
    }.replace("\t", "<span class='tab'></span>")
}

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


data class Vec2h(val v: Vector2f)
data class Vec3h(val v: Vector3f)
data class Vec4h(val v: Vector4f)
