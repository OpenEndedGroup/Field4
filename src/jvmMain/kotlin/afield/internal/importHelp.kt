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
    return cache[name]?.filter(::isImportableClassName)?.takeIf { it.isNotEmpty() }
}

fun isImportableClassName(name: String): Boolean {
    if ('$' in name) return false
    if (isBlacklistedKotlinCompletionName(name)) return false
    return currentFieldPreferences().importClassBlacklist.none { pattern ->
        importClassBlacklistPatternMatches(pattern, name)
    }
}

private fun importClassBlacklistPatternMatches(pattern: String, className: String): Boolean {
    val p = pattern.trim().lowercase()
    val candidate = className.lowercase()
    if (p.isBlank()) return false
    if ('*' !in p) return candidate == p
    val parts = p.split('*')
    var at = 0
    if (parts.first().isNotEmpty()) {
        if (!candidate.startsWith(parts.first())) return false
        at = parts.first().length
    }
    for (part in parts.drop(1).dropLast(1)) {
        if (part.isEmpty()) continue
        val next = candidate.indexOf(part, at)
        if (next < 0) return false
        at = next + part.length
    }
    val last = parts.last()
    return last.isEmpty() || candidate.indexOf(last, at).let { it >= 0 && candidate.endsWith(last) }
}

fun classesWithPrefix(name: String, hideDollarSignClasses : Boolean = true): List<Pair<String, String>> {
    return cache.entries.mapNotNull {
        if (it.key.startsWith(name) && (!hideDollarSignClasses || !(it.key.contains("$") || it.value.contains("$")))
            && !isBlacklistedKotlinCompletionName(it.key)) {
            it.key.removePrefix(name) to it.value
        } else null
    }.flatMap { (suffix, l) ->
        l.filter(::isImportableClassName).map {
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
