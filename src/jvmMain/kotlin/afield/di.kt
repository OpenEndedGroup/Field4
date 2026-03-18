package afield

import afield.logger.lblock
import afield.logger.lprintln
import arrow.core.right
import io.github.classgraph.ClassGraph
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureNanoTime

const val plugins = "plugins" // time to install KTor plugins
const val early = "early" // early on in the startup
const val installHandlers = "installHandlers" // install message handlers for websockets
const val templatesRegistration = "templatesRegistration" // install KTor resource templates
const val newRoot = "newRoot" // whenever a new root is made
const val macroRegistration = "macroRegistration" // whenever a new root is made
const val returnValueRegistration = "returnValueRegistration" // register handlers for return values from box execution

/**
 * this is the world's smallest DI framework
 */

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RunsAt(val value: String, val priority : Int = 0)

val fieldSourceCache: Map<String, List<Pair<Int, Method>>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val v = ConcurrentHashMap<String, MutableList<Pair<Int, Method>>>()
    ClassGraph().enableClassInfo().enableAnnotationInfo().enableMethodInfo().enableMemoryMapping().addClassLoader(Thread.currentThread().contextClassLoader).acceptPackages("afield")//.disableJarScanning()
//        .scanAsync(Runloop.miscPool, Runtime.getRuntime().availableProcessors()).get()
        .scan()
        .use { scanResult ->
            for (cz in scanResult.allStandardClasses) {
               cz.methodInfo.forEach { methodInfo ->
                   methodInfo.annotationInfo.forEach { annotationInfo ->
                       if (annotationInfo.name == "afield.RunsAt") {
                           val method = methodInfo.loadClassAndGetMethod()
                           val time = annotationInfo.parameterValues.get("value").value.toString()
                           val priority = annotationInfo.parameterValues.get("priority").value as Int
                           v.computeIfAbsent(time) { mutableListOf()}.add(priority to method)
                       }
                   }
               }
            }
        }
    v
}

fun runAll(time : String, vararg args : Any?) {
    val ns = measureNanoTime {
        fieldSourceCache
    }
    if (ns/1_000_000 > 10)
        lprintln("info,di", "runAll precache time is ${ns/1_000_000} ms")

    lblock("info,di", "runall at `$time`") {
        fieldSourceCache[time]?.sortedBy { it.first }?.forEach {
            lblock("debug,di", "method ${it.second.name} (from ${it.second.declaringClass.name})") {
                it.second.invoke(null, *selectInvocationArgs(it.second, args))
            }
        }
    }
}

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
