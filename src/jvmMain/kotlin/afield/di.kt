package afield

import afield.logger.lblock
import afield.logger.lprint
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

/**
 * this is the world's smallest DI framework
 */

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RunsAt(val value: String, val priority : Int = 0)

val fieldSourceCache: Map<String, List<Pair<Int, Method>>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val v = ConcurrentHashMap<String, MutableList<Pair<Int, Method>>>()
    ClassGraph().enableClassInfo().enableAnnotationInfo().enableMethodInfo().enableMemoryMapping().addClassLoader(Thread.currentThread().contextClassLoader).acceptPackages("afield").disableJarScanning()
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
        lprintln(" runAll precache time is ${ns/1_000_000} ms")

    lblock("runall at `$time`") {
        fieldSourceCache[time]?.sortedBy { it.first }?.forEach {
            lblock("method ${it.second.name} (from ${it.second.declaringClass.name})") {
                it.second.invoke(null, *args)
            }
        }
    }
}

