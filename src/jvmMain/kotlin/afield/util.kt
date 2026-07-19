package afield

import afield.PersistOnExit.needingPersistance
import afield.SavingContext.Companion.format
import afield.logger.lprintln
import afield.stdlib.invoke
import java.io.File
import java.lang.reflect.*
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

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class CompletionCategory(val value: String)

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

fun LinkedList<Long>.windowedAppendAndPrint(name: String, max_window: Int, data: Long) {
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
                value = defaultValue.invoke()
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
                value = defaultValue.invoke()
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
                value = defaultValue?.invoke()
            }
        }
        return value!!
    }

    operator fun setValue(f: HasProperties, property: KProperty<*>, v: Double) {
        value = v
        needingPersistance[property.name] = v
    }

}

object PersistOnExit {

    var needingPersistance = ConcurrentHashMap<String, Any>()

    private fun drainPending() {
        needingPersistance.entries.removeIf {
            save(it.key, it.value)
            true
        }
    }

    // Daemon: this is a best-effort background flusher and must never be the reason a
    // JVM stays alive (otherwise every jvm-main smoke test that touches a persisted
    // property would hang until the launcher force-exits). A shutdown hook flushes any
    // last-second changes on graceful exit so we keep the "persist on exit" guarantee.
    var checkThread = thread(isDaemon = true) {
        while (true) {
            Thread.sleep(1000)
            if (needingPersistance.size > 0) lprintln("info,persist", "persistence number is ${needingPersistance.size}")
            drainPending()
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ drainPending() }, "afield-persist-flush"))
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
fun <T> T.namedHere(name: String = "${this!!.javaClass.name}", prefix : String = "${Box.context.get()}") = Named(prefix+"_"+name, this)

/**
 * Dump an object's class hierarchy (superclasses + interfaces) and print:
 * - declared fields + their values
 * - declared methods + their signatures
 *
 * Notes:
 * - "Declared" means exactly what's declared on that type (not inherited).
 * - Values are read reflectively; may fail for some fields due to security/module access.
 * - Reading a field value can throw, and calling toString() can have side-effects; both are guarded.
 */
fun Any?.reflectionDump(
    includeStatic: Boolean = false,
    includeSynthetic: Boolean = false,
    showInterfaces: Boolean = true
) {
    if (this == null) {
        println("<<null>>")
        return
    }

    val rootClass = this.javaClass
    println("=== Reflection dump for instance: ${safeToString(this)}")
    println("=== Runtime class: ${rootClass.name}")
    println()

    val visitedTypes = LinkedHashSet<Class<*>>()
    for (type in rootClass.walkTypes(showInterfaces)) {
        if (!visitedTypes.add(type)) continue

        println("----- TYPE: ${type.name}")
        type.superclass?.let { println("  superclass: ${it.name}") }
        if (showInterfaces) {
            val ifaces = type.interfaces.map { it.name }
            println("  interfaces: ${ifaces.ifEmpty { listOf("<none>") }.joinToString()}")
        }

        println("  [FIELDS]")
        val fields = type.declaredFields
            .asSequence()
            .filter { includeSynthetic || !it.isSynthetic }
            .sortedBy { it.name }
            .toList()

        if (fields.isEmpty()) {
            println("    <none>")
        } else {
            for (f in fields) {
                val isStatic = Modifier.isStatic(f.modifiers)
                if (!includeStatic && isStatic) continue
                dumpField(this, f)
            }
        }

        println("  [METHODS]")
        val methods = type.declaredMethods
            .asSequence()
            .filter { includeSynthetic || !it.isSynthetic }
            .sortedWith(compareBy<Method>({ it.name }, { it.parameterCount }))
            .toList()

        if (methods.isEmpty()) {
            println("    <none>")
        } else {
            for (m in methods) {
                val isStatic = Modifier.isStatic(m.modifiers)
                if (!includeStatic && isStatic) continue
                println("    ${m.toGenericString()}")
            }
        }

        println()
    }
}

fun showMethodNames(a : Any) {
    MethodParameterSpy.printClassMethods(a.javaClass)
}

class MethodParameterSpy {
    // for the morbidly curious
    fun <E : RuntimeException?> genericThrow() {
    }

    companion object {
        private const val fmt = "%24s: %s%n"

        fun printClassConstructors(c: Class<*>) {
            val allConstructors: Array<Constructor<*>> = c.getConstructors()
            System.out.format(fmt, "Number of constructors", allConstructors.size)
            for (currentConstructor in allConstructors) {
                printConstructor(currentConstructor)
            }
            val allDeclConst: Array<Constructor<*>> = c.getDeclaredConstructors()
            System.out.format(
                fmt, "Number of declared constructors",
                allDeclConst.size
            )
            for (currentDeclConst in allDeclConst) {
                printConstructor(currentDeclConst)
            }
        }

        fun printClassMethods(c: Class<*>) {
            val allMethods = c.getDeclaredMethods()
            System.out.format(fmt, "Number of methods", allMethods.size)
            for (m in allMethods) {
                printMethod(m)
            }
        }

        fun printConstructor(c: Constructor<*>) {
            System.out.format("%s%n", c.toGenericString())
            val params: Array<Parameter?> = c.getParameters()
            System.out.format(fmt, "Number of parameters", params.size)
            for (i in params.indices) {
                printParameter(params[i]!!)
            }
        }

        fun printMethod(m: Method) {
            System.out.format("%s%n", m.toGenericString())
            System.out.format(fmt, "Return type", m.getReturnType())
            System.out.format(fmt, "Generic return type", m.getGenericReturnType())

            val params: Array<Parameter?> = m.getParameters()
            for (i in params.indices) {
                printParameter(params[i]!!)
            }
        }

        fun printParameter(p: Parameter) {
            System.out.format(fmt, "Parameter class", p.getType())
            System.out.format(fmt, "Parameter name", p.getName())
            System.out.format(fmt, "Modifiers", p.getModifiers())
            System.out.format(fmt, "Is implicit?", p.isImplicit())
            System.out.format(fmt, "Is name present?", p.isNamePresent())
            System.out.format(fmt, "Is synthetic?", p.isSynthetic())
        }

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                printClassConstructors(Class.forName(args[0]))
                printClassMethods(Class.forName(args[0]))
            } catch (x: ClassNotFoundException) {
                x.printStackTrace()
            }
        }
    }
}

/**
 * Walk class hierarchy:
 * - current class, its superclasses
 * - (optionally) interfaces at each level, including their parent interfaces
 *
 * De-duplicated by caller.
 */
private fun Class<*>.walkTypes(includeInterfaces: Boolean): Sequence<Class<*>> = sequence {
    var c: Class<*>? = this@walkTypes
    while (c != null) {
        yield(c)

        if (includeInterfaces) {
            // BFS over interface graph for this class level
            val queue = ArrayDeque<Class<*>>(c.interfaces.toList())
            val seen = HashSet<Class<*>>()
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                if (seen.add(i)) {
                    yield(i)
                    i.interfaces.forEach(queue::addLast)
                }
            }
        }

        c = c.superclass
    }
}

private fun dumpField(instance: Any, f: Field) {
    val mods = Modifier.toString(f.modifiers).ifBlank { "package-private" }
    val typeName = f.genericType.typeName
    val name = f.name

    val valueStr = runCatching {
        f.tryMakeAccessible()
        val v = f.get(instance)
        safeToString(v)
    }.getOrElse { e ->
        "<unreadable: ${e::class.java.simpleName}: ${e.message}>"
    }

    println("    $mods $typeName $name = $valueStr")
}

private fun Field.tryMakeAccessible() {
    // Works on many JVMs; on newer Java with modules, this can still fail (caught by caller).
    runCatching { isAccessible = true }
    runCatching { trySetAccessible() }
}

private fun safeToString(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"$v\""
    is Char -> "'$v'"
    is Boolean, is Number -> v.toString()
    is Array<*> -> v.contentDeepToString()
    is ByteArray -> v.contentToString()
    is ShortArray -> v.contentToString()
    is IntArray -> v.contentToString()
    is LongArray -> v.contentToString()
    is FloatArray -> v.contentToString()
    is DoubleArray -> v.contentToString()
    is CharArray -> v.contentToString()
    is BooleanArray -> v.contentToString()
    else -> runCatching { v.toString() }
        .getOrElse { "<toString failed: ${it::class.java.simpleName}: ${it.message}>" }
}
