package afield

import afield.PersistOnExit.needingPersistance
import afield.SavingContext.Companion.format
import afield.stdlib.invoke
import kotlinx.serialization.encodeToString
import afield.logger.lprintln
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Doc(val value: String)

var timingInfo = ConcurrentHashMap<String, LinkedList<Long>>()
var disableTiming = true
var max_window = 100

private val tDP by lazy {
    DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = ','
        decimalSeparator = '.'
    })
}

fun Number.tDP(): String = tDP.format(this)

inline fun <T> logTime(name: String, max_window: Int = afield.max_window, block: () -> T): T {
    if (disableTiming) {
        return block.invoke()
    }
    var a = System.nanoTime()
    var r = block()
    var b = System.nanoTime()
    timingInfo.computeIfAbsent(name) { LinkedList<Long>() }.windowedAppendAndPrint(name, max_window, b - a)
    return r
}

suspend fun <T> logTime_(name: String, max_window: Int = afield.max_window, block: suspend () -> T): T {
    if (disableTiming) {
        return block.invoke()
    }
    var a = System.nanoTime()
    var r = block()
    var b = System.nanoTime()
    timingInfo.computeIfAbsent(name) { LinkedList<Long>() }.windowedAppendAndPrint(name, max_window, b - a)
    return r
}

inline fun LinkedList<Long>.windowedAppendAndPrint(name: String, max_window: Int, data: Long) {
    add(data)
    if (this.size > max_window * 2) {
        max_window { this.removeFirst() }
        var data = ArrayList<Long>(this)
        data.sort()
        var total = data.sum()
        lprintln(
            "info,perf",
            ">>time '$name' = [${data.first().tDP()} | ${data[data.size / 2].tDP()}, ${(total / data.size.toDouble()).tDP()} | ${data.last().tDP()}]"
        )
    }
}

class PersistentString(val defaultValue: (() -> String?) = { null }) {

    var first = true
    var value: String? = null

    operator fun getValue(f: Any?, property: KProperty<*>): String? {
        if (first) {
            first = false
            value = PersistOnExit.load<String>(property.name)
            if (value == null) {
                value = defaultValue.invoke() ?: null
            }
        }
        return value
    }

    operator fun setValue(f: Any?, property: KProperty<*>, v: String?) {
        value = v as String
        needingPersistance[property.name] = v
    }


}

class PersistentInt(val defaultValue: (() -> Int?) = { null }) {

    var first = true
    var value: Int? = null

    operator fun getValue(f: HasProperties, property: KProperty<*>): Int? {
        if (first) {
            first = false
            value = PersistOnExit.load<Int>(property.name)
            if (value == null) {
                value = defaultValue?.invoke() ?: null
            }
        }
        return value!!
    }

    operator fun setValue(f: HasProperties, property: KProperty<*>, v: Int?) {
        value = v as Int
        needingPersistance[property.name] = v
    }

}

class PersistentDouble(val defaultValue: (() -> Double)? = { 0.0 }) {

    var first = true
    var value: Double? = null

    operator fun getValue(f: HasProperties, property: KProperty<*>): Double {
        if (first) {
            first = false
            value = PersistOnExit.load<Double>(property.name)
            if (value == null) {
                value = defaultValue?.invoke() ?: null
            }
        }
        return value!!
    }

    operator fun setValue(f: HasProperties, property: KProperty<*>, v: Double) {
        value = v as Double
        needingPersistance[property.name] = v
    }

}

object PersistOnExit {

    var needingPersistance = ConcurrentHashMap<String, Any>()
    var checkThread = thread {
        while (true) {
            Thread.sleep(1000)
            if (needingPersistance.size > 0) lprintln("info,persist", "persistence number is ${needingPersistance.size}")
            needingPersistance.entries.removeIf {
                save(it.key, it.value)
                true
            }
        }
    }

    fun save(name: String, t: Any?) {
        if (t == null) return
        try {
            val f = System.getProperty("user.dir") + "/.field"
            File(f).mkdirs()
            val p = f + "/$name"

            var text: String? = null

            when (t::class) {
                String::class -> {
                    text = format.encodeToString<String>(t as String)
                }

                Int::class -> {
                    text = format.encodeToString<Int>(t as Int)
                }

                Double::class -> {
                    text = format.encodeToString<Double>(t as Double)
                }

                Float::class -> {
                    text = format.encodeToString<Float>(t as Float)
                }

                else -> {
                    throw IllegalArgumentException("unknown type ${t::class}")
                }
            }

            lprintln("info,persist", "saving $name to $text with path $p")
            File(p).writeText(text)

        } catch (e: Exception) {
            lprintln("error,persist", "problem saving $name — ${e.message}")
        }
    }

    inline fun <reified T> load(name: String): T? {
        try {
            val f = System.getProperty("user.dir") + "/.field"
            File(f).mkdirs()
            val p = f + "/$name"

            val text = File(p).readText()

            when (T::class) {
                String::class -> {
                    return format.decodeFromString<String>(text) as T
                }

                Int::class -> {
                    return format.decodeFromString<Int>(text) as T
                }

                Double::class -> {
                    return format.decodeFromString<Double>(text) as T
                }

                Float::class -> {
                    return format.decodeFromString<Float>(text) as T
                }

                else -> {
                    throw IllegalArgumentException("unknown type ${T::class}")
                }
            }
        } catch (e: Exception) {
            lprintln("error,persist", "problem loading $name — ${e.message}")
            return null
        }
    }
}


/**
 * A var-friendly lazy: if never explicitly set, the first get() runs [initializer] and caches it.
 * If explicitly set via the setter, initializer will not run.
 */
class LazyVar<T> private constructor(
    private var initializer: (() -> T)?,
    private val mode: LazyThreadSafetyMode
) : ReadWriteProperty<Any?, T> {

    private object UNSET

    @Volatile
    private var _value: Any? = UNSET

    // For SYNCHRONIZED mode
    private val lock = this

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val v1 = _value
        if (v1 !== UNSET) {
            @Suppress("UNCHECKED_CAST")
            return v1 as T
        }
        return when (mode) {
            LazyThreadSafetyMode.NONE -> initializeIfNeeded()
            LazyThreadSafetyMode.PUBLICATION -> {
                // multiple threads may publish; first wins
                val curr = _value
                if (curr !== UNSET) {
                    @Suppress("UNCHECKED_CAST")
                    curr as T
                } else {
                    val computed = requireNotNull(initializer) { "Initializer cleared" }()
                    _value = computed
                    // optional: drop initializer to free refs
                    initializer = null
                    computed
                }
            }

            LazyThreadSafetyMode.SYNCHRONIZED -> synchronized(lock) {
                val v2 = _value
                if (v2 !== UNSET) {
                    @Suppress("UNCHECKED_CAST")
                    v2 as T
                } else {
                    val computed = requireNotNull(initializer) { "Initializer cleared" }()
                    _value = computed
                    initializer = null
                    computed
                }
            }
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        when (mode) {
            LazyThreadSafetyMode.SYNCHRONIZED -> synchronized(lock) { _value = value }
            else -> _value = value
        }
        // We can drop the initializer to allow GC; the explicit set takes precedence forever
        initializer = null
    }

    /** Mark as not-initialized again; next get() will run a (new) initializer if provided. */
    fun reset(newInitializer: (() -> T)? = initializer) {
        when (mode) {
            LazyThreadSafetyMode.SYNCHRONIZED -> synchronized(lock) {
                _value = UNSET
                initializer = newInitializer
            }

            else -> {
                _value = UNSET
                initializer = newInitializer
            }
        }
    }

    /** Returns true iff a value (either set or computed) is currently cached. */
    val isInitialized: Boolean get() = _value !== UNSET

    companion object {
        fun <T> of(
            mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
            initializer: () -> T
        ): LazyVar<T> = LazyVar(initializer, mode)
    }

    // Non-synchronized fast path for NONE
    @Suppress("UNCHECKED_CAST")
    private fun initializeIfNeeded(): T {
        val v = _value
        if (v !== UNSET) return v as T
        val computed = requireNotNull(initializer) { "Initializer cleared" }()
        _value = computed
        initializer = null
        return computed
    }
}

/** Convenience factory mirroring `lazy { }` */
fun <T> lazyVar(
    mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
    initializer: () -> T
): LazyVar<T> = LazyVar.of(mode, initializer)


data class Named<T>(val name: String, val value: T)

infix fun <T> T.named(name: String) = Named(name, this)
