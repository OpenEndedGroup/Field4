package afield

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoKindForIcon(iconName: String): Int = when (iconName) {
    "property" -> 4
    "method" -> 0
    "keyword" -> 17
    "package" -> 21
    "class" -> 5
    else -> 25
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoFindLanguageForPropertyName(name: String): String {
    for (i in monacoPropertyNames.indices) {
        if (monacoPropertyNames[i] == name) return monacoPropertyLanguages[i]
    }
    return "kotlin"
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoNotEqual(list1: dynamic, list2: dynamic): Boolean {
    if (list1 == null || list2 == null) return true
    val len1 = (list1.length as? Number)?.toInt() ?: return true
    val len2 = (list2.length as? Number)?.toInt() ?: return true
    if (len1 != len2) return true
    for (i in 0 until len1) {
        if (list1[i] !== list2[i]) return true
    }
    return false
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoGetEnclosingScope(x: Int, getLine: dynamic): dynamic {
    val lines = mutableListOf<String>()
    var i = 0
    while (true) {
        val line = getLine(i) as? String ?: break
        lines.add(line)
        i++
    }

    if (x < 0 || x >= lines.size) return null

    data class Scope(
        val startLine: Int,
        val startIndex: Int,
        var endLine: Int = -1,
        var endIndex: Int = -1
    )

    val scopes = mutableListOf<Scope>()
    val stack = ArrayDeque<Scope>()
    for (lineIndex in lines.indices) {
        val line = lines[lineIndex]
        for (charIndex in line.indices) {
            when (line[charIndex]) {
                '{' -> stack.addLast(Scope(lineIndex, charIndex))
                '}' -> if (stack.isNotEmpty()) {
                    val scope = stack.removeLast()
                    scope.endLine = lineIndex
                    scope.endIndex = charIndex
                    scopes.add(scope)
                }
            }
        }
    }

    var candidate: Scope? = null
    for (scope in scopes) {
        if (x >= scope.startLine && x <= scope.endLine) {
            if (candidate == null || scope.startLine < candidate.startLine || scope.endLine > candidate.endLine) {
                candidate = scope
            }
        }
    }

    return if (candidate == null) {
        arrayOf(arrayOf(lines[x]), x, x)
    } else {
        val slice = lines.subList(candidate.startLine, candidate.endLine + 1).toTypedArray()
        arrayOf(slice, candidate.startLine, candidate.endLine + 1)
    }
}
