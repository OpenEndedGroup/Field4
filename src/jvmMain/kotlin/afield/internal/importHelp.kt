package afield

import afield.logger.lprintln
import io.github.classgraph.ClassGraph
import kotlin.concurrent.thread
import kotlin.time.measureTime

val cache: Map<String, List<String>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val v = mutableMapOf<String, MutableList<String>>()
    ClassGraph().enableClassInfo().enableSystemJarsAndModules().enableMemoryMapping()
        .scan().use { scanResult ->                // Start the scan
            for (cz in scanResult.allStandardClasses) {
                v.computeIfAbsent(cz.simpleName) { mutableListOf() }.add(cz.name)
            }
        }
    v
}

fun findClassesNamed(name: String): List<String>? {
    return cache[name]
}

fun classesWithPrefix(name: String, hideDollarSignClasses : Boolean = true): List<Pair<String, String>> {
    return cache.entries.mapNotNull {
        if (it.key.startsWith(name) && (!hideDollarSignClasses || !(it.key.contains("$") || it.value.contains("$")))
            && !isBlacklistedKotlinCompletionName(it.key)) {
            it.key.removePrefix(name) to it.value
        } else null
    }.flatMap { (suffix, l) ->
        l.filter { !it.contains("$") && !isBlacklistedKotlinCompletionName(it) }.map {
            suffix to it
        }
    }
}

fun kotlinImportStatementFor(name: String): String? {
    val n = findClassesNamed(name)
    if (n == null) return null;
    if (n.size > 1) {
        lprintln("warning,import", "multiple classes called ${name} = ${cache[name]}")
        return null
    }

    return "import ${n[0].replace("$", ".")}"
}

fun initializeClassCache() {
    thread {
        var size = 0
        var time = measureTime {
            size = cache.size
        }
        lprintln("info,import", "found ${size} classes on classpath in ${time.inWholeMilliseconds / 1000.0}s")
    }
}
