package afield.runtime.lifecycle

import afield.app.protocol.handlers
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.ui.output.templates.templates

import afield.app.protocol.computeIfAbsent
import afield.audio.synth.*
import afield.data.table.get
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.app.diagnostics.logging.lblock
import afield.app.diagnostics.logging.lprintln
import arrow.core.right
import io.github.classgraph.ClassGraph
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureNanoTime

/**
 * Startup phases: functions annotated [RunsAt] are found by a classpath scan and called by [runAll] at a named phase.
 *
 * The phase names are the constants at the top of this file ([early], [plugins], [installHandlers],
 * [newRoot], ...). [runAll] calls every function registered for a phase in priority order, passing
 * those arguments its parameters ask for. The same scan fills [labelledArgumentFunctions].
 */

/** Startup phase: install Ktor plugins. */
const val plugins = "plugins" // time to install KTor plugins
const val early = "early" // early on in the startup
const val installHandlers = "installHandlers" // install message handlers for websockets
const val templatesRegistration = "templatesRegistration" // install KTor resource templates
const val newRoot = "newRoot" // whenever a new root is made
const val macroRegistration = "macroRegistration" // whenever a new root is made
const val returnValueRegistration = "returnValueRegistration" // register handlers for return values from box execution

/** Marks a function to be called by [runAll] at phase [value], lower [priority] first. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RunsAt(val value: String, val priority: Int = 0)

/**
 * Names of the functions annotated `@LabelsArgumentsFromSource`, filled by the same scan as
 * [fieldSourceCache] and read when macros are registered (`afield.runtime.macros.registerLabelledArgumentMacros`).
 */
val labelledArgumentFunctions: MutableSet<String> = ConcurrentHashMap.newKeySet()

val fieldSourceCache: Map<String, List<Pair<Int, Method>>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val v = ConcurrentHashMap<String, MutableList<Pair<Int, Method>>>()
    ClassGraph().enableClassInfo().enableAnnotationInfo().enableMethodInfo().enableMemoryMapping()
        .addClassLoader(Thread.currentThread().contextClassLoader).acceptPackages("afield")//.disableJarScanning()
//        .scanAsync(Runloop.miscPool, Runtime.getRuntime().availableProcessors()).get()
        .scan()
        .use { scanResult ->
            for (cz in scanResult.allStandardClasses) {
                cz.methodInfo.forEach { methodInfo ->
                    methodInfo.annotationInfo.forEach { annotationInfo ->
                        if (annotationInfo.name == "afield.runtime.macros.LabelsArgumentsFromSource") {
                            labelledArgumentFunctions.add(methodInfo.name)
                        }
                        if (annotationInfo.name == "afield.runtime.lifecycle.RunsAt") {
                            val method = methodInfo.loadClassAndGetMethod()
                            val time = annotationInfo.parameterValues.get("value").value.toString()
                            val priority = annotationInfo.parameterValues.get("priority").value as Int
                            v.computeIfAbsent(time) { mutableListOf() }.add(priority to method)
                        }
                    }
                }
            }
        }
    v
}

fun runAll(time: String, vararg args: Any?) {
    val ns = measureNanoTime {
        fieldSourceCache
    }
    if (ns / 1_000_000 > 10)
        lprintln("info,di", "runAll precache time is ${ns / 1_000_000} ms")

    lblock("info,di", "runall at `$time`") {
        val units = fieldSourceCache[time]?.sortedBy { it.first }.orEmpty()
        val announce = time in startupPhases
        units.forEachIndexed { i, unit ->
            val method = unit.second
            // Tick the pinned "startup" row through each boot-time unit as it runs.
            if (announce) {
                lprintln(
                    "info,startup",
                    "$time · ${method.declaringClass.simpleName}.${method.name} (${i + 1}/${units.size})"
                )
            }
            method.invoke(null, *selectInvocationArgs(method, args))
        }
    }
}

/** Boot-time phases whose units are announced on the pinned "startup" channel (see PinnedConsole). */
private val startupPhases = setOf(early, plugins, installHandlers)

private fun selectInvocationArgs(method: Method, providedArgs: Array<out Any?>): Array<Any?> {
    val parameterTypes = method.parameterTypes
    if (parameterTypes.isEmpty()) return emptyArray()
    if (providedArgs.isEmpty()) {
        throw IllegalArgumentException(
            "No arguments provided for ${method.declaringClass.name}.${method.name}${parameterTypes.contentToString()}"
        )
    }

    val used = BooleanArray(providedArgs.size)
    val selected = arrayOfNulls<Any?>(parameterTypes.size)

    parameterTypes.forEachIndexed { index, parameterType ->
        val matchIndex = providedArgs.indices.firstOrNull { candidateIndex ->
            !used[candidateIndex] && isCompatibleArgument(parameterType, providedArgs[candidateIndex])
        } ?: throw IllegalArgumentException(
            buildString {
                append("Cannot satisfy ")
                append(method.declaringClass.name)
                append('.')
                append(method.name)
                append(" parameter ")
                append(index)
                append(" of type ")
                append(parameterType.name)
                append(" from provided args ")
                append(providedArgs.map { it?.javaClass?.name ?: "null" })
            }
        )

        used[matchIndex] = true
        selected[index] = providedArgs[matchIndex]
    }

    return selected
}

private fun isCompatibleArgument(parameterType: Class<*>, arg: Any?): Boolean {
    if (arg == null) return !parameterType.isPrimitive
    if (parameterType.isAssignableFrom(arg.javaClass)) return true

    return when (parameterType) {
        java.lang.Boolean.TYPE -> arg is Boolean
        java.lang.Byte.TYPE -> arg is Byte
        java.lang.Short.TYPE -> arg is Short
        java.lang.Integer.TYPE -> arg is Int
        java.lang.Long.TYPE -> arg is Long
        java.lang.Float.TYPE -> arg is Float
        java.lang.Double.TYPE -> arg is Double
        java.lang.Character.TYPE -> arg is Char
        else -> false
    }
}
