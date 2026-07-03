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
    return !isBlacklistedKotlinCompletionName(name)
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

private val kotlinDefaultImportedTypeNames = setOf(
    "Any",
    "Annotation",
    "Array",
    "Boolean",
    "BooleanArray",
    "Byte",
    "ByteArray",
    "Char",
    "CharArray",
    "CharSequence",
    "Comparable",
    "Double",
    "DoubleArray",
    "Enum",
    "Float",
    "FloatArray",
    "Int",
    "IntArray",
    "Long",
    "LongArray",
    "Nothing",
    "Number",
    "Short",
    "ShortArray",
    "String",
    "Throwable",
    "Unit"
)

private val defaultImportedPackages = setOf(
    "kotlin",
    "kotlin.annotation",
    "kotlin.collections",
    "kotlin.comparisons",
    "kotlin.io",
    "kotlin.ranges",
    "kotlin.sequences",
    "kotlin.text",
    "java.lang"
)

private fun isDefaultImportedTypeName(name: String): Boolean {
    val trimmed = name.trim().removeSurrounding("`")
    if (trimmed.isBlank()) return false
    if (trimmed in kotlinDefaultImportedTypeNames) return true
    val packageName = trimmed.substringBeforeLast('.', missingDelimiterValue = "")
    return packageName in defaultImportedPackages
}

fun kotlinImportStatementFor(name: String): String? {
    if (isDefaultImportedTypeName(name)) return null

    val n = findClassesNamed(name)
    if (n == null) return null;
    if (n.size > 1) {
        lprintln("warning,import", "multiple classes called ${name} = ${cache[name]}")
        return null
    }

    if (isDefaultImportedTypeName(n[0])) return null

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
