package afield.app.diagnostics

import afield.app.server.app
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.app.protocol.computeIfAbsent
import afield.audio.synth.*
import afield.data.table.get
import afield.document.graph.Box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import afield.app.settings.PersistOnExit.needingPersistance
import afield.document.persistence.SavingContext.Companion.format
import afield.app.diagnostics.logging.lprintln
import afield.data.collections.invoke
import afield.math.linalg.invoke
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

inline fun <T> logTime(name: String, max_window: Int = afield.app.diagnostics.max_window, block: () -> T): T {
    if (disableTiming) {
        return block.invoke()
    }
    var a = System.nanoTime()
    var r = block()
    var b = System.nanoTime()
    timingInfo.computeIfAbsent(name) { LinkedList<Long>() }.windowedAppendAndPrint(name, max_window, b - a)
    return r
}

suspend fun <T> logTime_(name: String, max_window: Int = afield.app.diagnostics.max_window, block: suspend () -> T): T {
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
