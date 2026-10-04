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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Doc(val value: String)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class CompletionCategory(val value: String)

/**
 * The handful of members that answer "what do you actually do with one of these", in the order a person
 * reaches for them — a **ranking prior** for the completion popup, declared where the type is.
 *
 * Why an annotation rather than a heuristic: at a bare `f.` there is no prefix to fuzzy-match, so the
 * popup ranks on category weight plus the order the server sent, and the only term that could float
 * `moveTo` above `breakNext` is `recency` — which lives in `localStorage`, hence empty for a new user, in
 * a fresh browser profile, and in every test. A list in the source is the same answer for all four.
 *
 * Names, not callable references, so one entry covers every overload of a name (`FLine.moveTo` has four)
 * and a property reads the same as a function. A name that does not resolve would be a silent no-op, so
 * `CommonMembersAnnotationTest` fails the build if any listed name is not a member of the annotated
 * class — a typo would otherwise just quietly stop ranking.
 *
 * Keep these short. This is the first page of the manual, not an index: past roughly ten the order stops
 * being a real claim about what people reach for, and [afield.CommonMemberPrior] scores by position, so a
 * long tail earns almost nothing anyway.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class CommonMembers(vararg val value: String)

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

    inline fun <reified T> load(
        name: String,
        storageDirectory: File = File(System.getProperty("user.dir"), ".field"),
    ): T? {
        try {
            val source = File(storageDirectory, name)
            if (!source.exists()) return null
            val text = source.readText()

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
 *
 * [discard] forgets the value, and never waits for an initializer that is running on another thread.
 * That is the point of the class rather than a detail: its one user is `Box.ki`, whose initializer
 * builds a K2 engine (about a second on a cold JVM, under the SYNCHRONIZED lock), and whose discard
 * runs on a Ktor IO thread when a document's last client leaves. A value that would otherwise be
 * dropped uncached goes to [onDiscard] instead: the cached value a discard forgets, an initializer's
 * result when a discard ran while it was being built, and an initializer's result that lost to the
 * setter. So a value that needs releasing is released exactly once, by whichever thread drops it.
 */
class LazyVar<T> private constructor(
    private val initializer: () -> T,
    private val mode: LazyThreadSafetyMode,
    private val onDiscard: ((T) -> Unit)?
) : ReadWriteProperty<Any?, T> {

    private object UNSET

    private val value = AtomicReference<Any?>(UNSET)
    private val discards = AtomicInteger()

    // For SYNCHRONIZED mode
    private val lock = Any()

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val v1 = value.get()
        if (v1 !== UNSET) {
            @Suppress("UNCHECKED_CAST")
            return v1 as T
        }
        return when (mode) {
            LazyThreadSafetyMode.SYNCHRONIZED -> synchronized(lock) {
                val v2 = value.get()
                @Suppress("UNCHECKED_CAST")
                if (v2 !== UNSET) v2 as T else build()
            }
            // PUBLICATION: concurrent builders may all run; the first to publish wins.
            else -> build()
        }
    }

    /**
     * Run the initializer and cache its result, unless it has been overtaken while it ran.
     *
     * If the setter published first (or, for PUBLICATION, another builder did), the result goes to
     * [onDiscard] and the caller gets the published value. If [discard] ran, the result goes to
     * [onDiscard] and is still returned: this caller asked before the discard, and gets what it
     * would have had if it had asked a moment earlier.
     *
     * The check comes after publishing so it cannot race [discard], which bumps [discards] before
     * taking the value: either that take finds the result and releases it, or this check sees the
     * bump and does. The compare-and-sets make sure only one of them does.
     */
    private fun build(): T {
        val generation = discards.get()
        val built = initializer()
        if (!value.compareAndSet(UNSET, built)) {
            val published = value.get()
            onDiscard?.invoke(built)
            @Suppress("UNCHECKED_CAST")
            return if (published !== UNSET) published as T else built
        }
        if (discards.get() != generation && value.compareAndSet(built, UNSET)) onDiscard?.invoke(built)
        return built
    }

    /** Never waits for an initializer in flight: a value set now wins over the one it is building. */
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value.set(value)
    }

    /**
     * Forget the value, so the next get() runs the initializer again, without waiting for an
     * initializer running on another thread. The cached value, if there was one, goes to
     * [onDiscard] here, on the calling thread. An initializer already running hands its result to
     * [onDiscard] when it returns, on its own thread, and does not cache it.
     *
     * @return whether a cached value was discarded (an initializer in flight does not count).
     */
    fun discard(): Boolean {
        discards.incrementAndGet()
        val previous = value.getAndSet(UNSET)
        if (previous === UNSET) return false
        @Suppress("UNCHECKED_CAST")
        onDiscard?.invoke(previous as T)
        return true
    }

    /**
     * How many times [discard] has run. An initializer that reads this when it starts can tell later
     * whether a discard has overtaken it, before its result is even returned (see `Box.buildKi`).
     */
    val generation: Int get() = discards.get()

    /** Returns true iff a value (either set or computed) is currently cached. */
    val isInitialized: Boolean get() = value.get() !== UNSET

    /** The cached value, or null without running the initializer. */
    fun valueOrNull(): T? {
        val v = value.get()
        @Suppress("UNCHECKED_CAST")
        return if (v === UNSET) null else v as T
    }

    companion object {
        fun <T> of(
            mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
            onDiscard: ((T) -> Unit)? = null,
            initializer: () -> T
        ): LazyVar<T> = LazyVar(initializer, mode, onDiscard)
    }
}

/** Convenience factory mirroring `lazy { }`; see [LazyVar] for [onDiscard]. */
fun <T> lazyVar(
    mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
    onDiscard: ((T) -> Unit)? = null,
    initializer: () -> T
): LazyVar<T> = LazyVar.of(mode, onDiscard, initializer)


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
