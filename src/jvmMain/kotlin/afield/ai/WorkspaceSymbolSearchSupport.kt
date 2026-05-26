package afield.ai

import afield.ai.core.WorkspaceSymbolMatch
import afield.ai.core.WorkspaceSymbolSearchResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

private data class RankedWorkspaceSymbolMatch(
    val definition: KotlinDefinitionRecord,
    val score: Int,
    val definitionIndex: Int
)

private data class WorkspaceQueryAnalysis(
    val normalizedQueries: List<String>,
    val significantTokens: List<String>,
    val preferredKinds: Set<String>
)

private val workspaceSearchNoiseTokens = setOf(
    "class", "interface", "object", "fun", "val", "var",
    "public", "private", "protected", "internal",
    "open", "final", "abstract", "sealed", "data", "enum",
    "annotation", "value", "inline", "suspend", "tailrec",
    "operator", "infix", "external", "override", "const",
    "lateinit", "companion", "inner"
)

fun searchWorkspaceSymbols(
    index: KotlinSourceIndexDb?,
    rawQuery: String,
    limit: Int
): WorkspaceSymbolSearchResult {
    val query = rawQuery.trim()
    if (query.isEmpty()) return WorkspaceSymbolSearchResult(emptyList())

    val definitions = index?.definitions.orEmpty()
    if (definitions.isEmpty()) return WorkspaceSymbolSearchResult(emptyList())

    val analysis = analyzeWorkspaceSymbolQuery(query)
    if (analysis.normalizedQueries.isEmpty()) return WorkspaceSymbolSearchResult(emptyList())

    val fileCache = HashMap<String, List<String>>()
    fun fileLines(path: String): List<String>? {
        return fileCache.getOrPut(path) {
            val direct = Paths.get(path)
            val resolved = if (Files.exists(direct)) direct else Paths.get(System.getProperty("user.dir")).resolve(path)
            if (!Files.exists(resolved)) return@getOrPut emptyList()
            runCatching { Files.readAllLines(resolved, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
        }.takeIf { it.isNotEmpty() }
    }

    fun definitionLineText(def: KotlinDefinitionRecord): String? {
        val lines = fileLines(def.filePath) ?: return null
        val idx = def.line - 1
        return lines.getOrNull(idx)?.trimEnd()
    }

    fun functionBody(def: KotlinDefinitionRecord, maxChars: Int = 6000): String? {
        if (def.kind != "fun") return null
        val lines = fileLines(def.filePath) ?: return null
        val startIdx = (def.line - 1).coerceIn(0, lines.lastIndex)
        var depth = 0
        var found = false
        val sb = StringBuilder()
        var chars = 0
        for (i in startIdx..lines.lastIndex) {
            val line = lines[i]
            var j = 0
            while (j < line.length) {
                val ch = line[j]
                if (!found) {
                    if (ch == '{') {
                        found = true
                        depth = 1
                        sb.append(ch)
                    }
                } else {
                    sb.append(ch)
                    if (ch == '{') depth++
                    if (ch == '}') {
                        depth--
                        if (depth == 0) return sb.toString().trimEnd()
                    }
                }
                if (found) {
                    chars++
                    if (chars >= maxChars) return sb.toString().trimEnd()
                }
                j++
            }
            if (found) {
                sb.append('\n')
                chars++
                if (chars >= maxChars) return sb.toString().trimEnd()
            }
        }
        return if (found) sb.toString().trimEnd() else null
    }

    fun toMatch(def: KotlinDefinitionRecord): WorkspaceSymbolMatch {
        val location = if (!def.fileId.isNullOrBlank()) {
            "${def.filePath}:${def.line} (fileId=${def.fileId})"
        } else {
            "${def.filePath}:${def.line}"
        }
        val related = RelatedContextDiscovery.forPath(def.filePath)
        return WorkspaceSymbolMatch(
            symbol = def.name,
            kind = def.kind,
            locationHint = location,
            filePath = def.filePath,
            fileId = def.fileId,
            line = def.line,
            definitionLine = definitionLineText(def),
            definitionBody = functionBody(def),
            relatedGuides = related.guides,
            relatedExamples = related.examples
        )
    }

    val ranked = LinkedHashMap<String, RankedWorkspaceSymbolMatch>()
    definitions.forEachIndexed { definitionIndex, def ->
        val definitionLine = definitionLineText(def).orEmpty()
        val fullName = def.name
        val simpleName = fullName.substringAfterLast('.')
        val score = scoreWorkspaceSymbolCandidate(
            fullName = fullName,
            simpleName = simpleName,
            definitionLine = definitionLine,
            kind = def.kind,
            filePath = def.filePath,
            analysis = analysis
        )
        if (score <= 0) return@forEachIndexed

        val key = "${def.filePath}:${def.line}:${def.kind}:${def.name}"
        val rankedMatch = RankedWorkspaceSymbolMatch(
            definition = def,
            score = score,
            definitionIndex = definitionIndex
        )
        val existing = ranked[key]
        if (existing == null || rankedMatch.score > existing.score) {
            ranked[key] = rankedMatch
        }
    }

    val matches = ranked.values
        .sortedWith(compareByDescending<RankedWorkspaceSymbolMatch> { it.score }.thenBy { it.definitionIndex })
        .take(limit.coerceAtLeast(1))
        .map { toMatch(it.definition) }

    return WorkspaceSymbolSearchResult(matches)
}

private fun analyzeWorkspaceSymbolQuery(rawQuery: String): WorkspaceQueryAnalysis {
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
        val cleaned = candidate
            ?.trim()
            ?.trim('"', '\'', '`')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        if (cleaned.isNotEmpty() && (allowShort || cleaned.length >= 2)) {
            variants += cleaned
        }
    }

    add(rawQuery, allowShort = true)
    add(
        rawQuery.replace(
            Regex(
                "^(?:data\\s+class|sealed\\s+class|annotation\\s+class|enum\\s+class|class|interface|object|fun|val|var)\\s+",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
    )

    extractOperatorAliases(rawQuery).forEach(::add)

    Regex("`[^`]+`|[A-Za-z_][A-Za-z0-9_]*").findAll(rawQuery).forEach { match ->
        val token = match.value.trim('`')
        if (token.length >= 2 && token.lowercase() !in workspaceSearchNoiseTokens) {
            add(token)
            significantTokens += token.lowercase()
        }
    }

    rawQuery.split(Regex("[\\./:]+"))
        .map { it.trim('`', '"', '\'', ' ') }
        .filter { it.length >= 2 && it.lowercase() !in workspaceSearchNoiseTokens }
        .forEach {
            add(it)
            significantTokens += it.lowercase()
        }

    return WorkspaceQueryAnalysis(
        normalizedQueries = variants.toList(),
        significantTokens = significantTokens.toList(),
        preferredKinds = preferredKinds
    )
}

private fun extractOperatorAliases(query: String): List<String> {
    val aliases = mutableListOf<String>()
    if ("+=" in query) aliases += "plusAssign"
    if ("-=" in query) aliases += "minusAssign"
    if ("*=" in query) aliases += "timesAssign"
    if ("/=" in query) aliases += "divAssign"
    if ("%=" in query) aliases += "remAssign"
    if ("==" in query) aliases += "equals"
    return aliases
}

private fun scoreWorkspaceSymbolCandidate(
    fullName: String,
    simpleName: String,
    definitionLine: String,
    kind: String,
    filePath: String,
    analysis: WorkspaceQueryAnalysis
): Int {
    val full = fullName.lowercase()
    val simple = simpleName.lowercase()
    val line = definitionLine.lowercase()
    val kindLower = kind.lowercase()
    val pathLower = filePath.lowercase()

    val bestQueryScore = analysis.normalizedQueries.maxOfOrNull { query ->
        val q = query.lowercase()
        if (q.isBlank()) {
            0
        } else {
            when {
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
