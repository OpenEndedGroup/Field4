package afield

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

fun classesWithPrefix(name: String): List<Pair<String, String>> {
    return cache.entries.mapNotNull {
        if (it.key.startsWith(name)) {
            it.key.removePrefix(name) to it.value
        } else null
    }.flatMap { (suffix, l) ->
        l.map {
            suffix to it
        }
    }
}

fun kotlinImportStatementFor(name: String): String? {
    val n = findClassesNamed(name)
    if (n == null) return null;
    if (n.size > 1) {
        println("WARNING: multiple classes called ${name} = ${cache[name]}")
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
        println("found ${size} classes on classpath in ${time.inWholeMilliseconds / 1000.0}s")
    }
}
