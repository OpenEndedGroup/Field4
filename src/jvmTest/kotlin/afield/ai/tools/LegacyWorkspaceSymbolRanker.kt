package afield.ai.tools

import afield.runtime.completion.KotlinDefinitionRecord

/**
 * The `WorkspaceSymbolSearch` ranking as it was on 2026-11-06, frozen so [WorkspaceSymbolSearchEval] can
 * keep measuring the current ranker against it.
 *
 * Two branches: if any definition's name is the query or ends in `.query`, those come back in index
 * order, unranked; otherwise every definition is scored on its name and its declaration line (plus small
 * kind and path terms) and sorted. Do not "fix" this copy; it is the baseline.
 */
internal object LegacyWorkspaceSymbolRanker {
    private data class Analysis(val normalizedQueries: List<String>, val significantTokens: List<String>, val preferredKinds: Set<String>)

    private val noise = setOf(
        "class", "interface", "object", "fun", "val", "var", "public", "private", "protected", "internal",
        "open", "final", "abstract", "sealed", "data", "enum", "annotation", "value", "inline", "suspend", "tailrec",
        "operator", "infix", "external", "override", "const", "lateinit", "companion", "inner"
    )

    fun rank(definitions: List<KotlinDefinitionRecord>, rawQuery: String, limit: Int, line: (KotlinDefinitionRecord) -> String?): List<KotlinDefinitionRecord> {
        val query = rawQuery.trim()
        if (query.isEmpty() || definitions.isEmpty()) return emptyList()
        val analysis = analyze(query)
        if (analysis.normalizedQueries.isEmpty()) return emptyList()
        val exact = definitions.filter { it.name == query || it.name.endsWith(".$query") }
        if (exact.isNotEmpty()) return exact.take(limit.coerceIn(1, 100))
        data class Ranked(val def: KotlinDefinitionRecord, val score: Int, val index: Int)
        val ranked = LinkedHashMap<String, Ranked>()
        definitions.forEachIndexed { index, def ->
            val score = score(def.name, def.name.substringAfterLast('.'), line(def).orEmpty(), def.kind, def.filePath, analysis)
            if (score <= 0) return@forEachIndexed
            val key = "${def.filePath}:${def.line}:${def.kind}:${def.name}"
            val existing = ranked[key]
            if (existing == null || score > existing.score) ranked[key] = Ranked(def, score, index)
        }
        return ranked.values.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.index })
            .take(limit.coerceAtLeast(1)).map { it.def }
    }

    private fun analyze(rawQuery: String): Analysis {
        val preferredKinds = linkedSetOf<String>()
        val loweredRaw = rawQuery.lowercase()
        if (Regex("\\bclass\\b").containsMatchIn(loweredRaw)) preferredKinds += "class"
        if (Regex("\\binterface\\b").containsMatchIn(loweredRaw)) preferredKinds += "interface"
        if (Regex("\\bobject\\b").containsMatchIn(loweredRaw)) preferredKinds += "object"
        if (Regex("\\bfun\\b").containsMatchIn(loweredRaw) || loweredRaw.contains("operator")) preferredKinds += "fun"
        if (Regex("\\bval\\b").containsMatchIn(loweredRaw)) preferredKinds += "val"
        if (Regex("\\bvar\\b").containsMatchIn(loweredRaw)) preferredKinds += "var"
        val variants = LinkedHashSet<String>()
        val significantTokens = LinkedHashSet<String>()
        fun add(candidate: String?, allowShort: Boolean = false) {
            val cleaned = candidate?.trim()?.trim('"', '\'', '`')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (cleaned.isNotEmpty() && (allowShort || cleaned.length >= 2)) variants += cleaned
        }
        add(rawQuery, allowShort = true)
        add(rawQuery.replace(Regex("^(?:data\\s+class|sealed\\s+class|annotation\\s+class|enum\\s+class|class|interface|object|fun|val|var)\\s+", RegexOption.IGNORE_CASE), ""))
        val aliases = mutableListOf<String>()
        if ("+=" in rawQuery) aliases += "plusAssign"
        if ("-=" in rawQuery) aliases += "minusAssign"
        if ("*=" in rawQuery) aliases += "timesAssign"
        if ("/=" in rawQuery) aliases += "divAssign"
        if ("%=" in rawQuery) aliases += "remAssign"
        if ("==" in rawQuery) aliases += "equals"
        aliases.forEach(::add)
        Regex("`[^`]+`|[A-Za-z_][A-Za-z0-9_]*").findAll(rawQuery).forEach { match ->
            val token = match.value.trim('`')
            if (token.length >= 2 && token.lowercase() !in noise) { add(token); significantTokens += token.lowercase() }
        }
        rawQuery.split(Regex("[\\./:]+")).map { it.trim('`', '"', '\'', ' ') }
            .filter { it.length >= 2 && it.lowercase() !in noise }
            .forEach { add(it); significantTokens += it.lowercase() }
        return Analysis(variants.toList(), significantTokens.toList(), preferredKinds)
    }

    private fun score(fullName: String, simpleName: String, definitionLine: String, kind: String, filePath: String, analysis: Analysis): Int {
        val full = fullName.lowercase()
        val simple = simpleName.lowercase()
        val line = definitionLine.lowercase()
        val kindLower = kind.lowercase()
        val pathLower = filePath.lowercase()
        val bestQueryScore = analysis.normalizedQueries.maxOfOrNull { query ->
            val q = query.lowercase()
            if (q.isBlank()) 0 else when {
                full == q -> 150
                simple == q -> 145
                full.endsWith(".$q") -> 140
                full.startsWith("$q.") -> 135
                full.startsWith(q) -> 125
                simple.startsWith(q) -> 120
                full.contains(".$q.") -> 115
                full.contains(".$q") -> 110
                simple.contains(q) -> 100
                full.contains(q) -> 90
                line.contains(q) -> 60
                else -> 0
            }
        } ?: 0
        if (bestQueryScore <= 0) return 0
        val tokenScore = analysis.significantTokens.sumOf { token ->
            when {
                full == token -> 22
                simple == token -> 22
                full.endsWith(".$token") -> 20
                simple.startsWith(token) -> 16
                simple.contains(token) -> 12
                full.contains(token) -> 10
                line.contains(token) -> 6
                else -> 0
            }
        }.coerceAtMost(90)
        val kindScore = when {
            analysis.preferredKinds.isEmpty() -> 0
            kindLower in analysis.preferredKinds -> 35
            else -> -20
        }
        val pathScore = when {
            pathLower.startsWith("src/jvmmain/kotlin/afield/four/") -> 18
            pathLower.startsWith("src/jvmmain/kotlin/afield/stdlib/") -> 14
            pathLower.startsWith("src/jvmmain/kotlin/") -> 10
            pathLower.startsWith("src/commonmain/kotlin/") -> 8
            "/examples/" in pathLower -> -12
            pathLower.startsWith("src/personal/") -> -35
            else -> 0
        }
        return bestQueryScore + tokenScore + kindScore + pathScore
    }
}
