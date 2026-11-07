package afield.ai.tools

import afield.ai.retrieval.RelatedContextDiscovery
import afield.ai.retrieval.RelatedContextHints
import afield.runtime.completion.KotlinDefinitionRecord
import afield.runtime.completion.KotlinSourceIndex
import afield.runtime.completion.KotlinSourceIndexDb
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections
import java.util.IdentityHashMap

/**
 * `WorkspaceSymbolSearch`: Field's Kotlin definitions ([KotlinSourceIndex]) ranked for a query, each with
 * its declaration line, KDoc and body.
 *
 * [rankWorkspaceSymbols] does the ranking and [searchWorkspaceSymbols] dresses the result for the tool. The
 * ranking is measured, not argued: `WorkspaceSymbolSearchEval` (jvmTest) replays the searches agents made in
 * real AI4 turns against a fresh index of this checkout and scores each ranker by whether it put first what
 * the agent went on to write or open. Every term in [SymbolRankingOptions] is a switch so it can be measured
 * alone; see that class for what each one was worth.
 */

/**
 * The ranking terms beyond the original lexical score, each separately switchable for the eval.
 * [LEGACY] turns them all off and reproduces the ranking as it was before 2026-11-06 exactly (the eval
 * checks that it does).
 *
 * Measured on 2026-11-06 by `WorkspaceSymbolSearchEval` (282 logged searches from six models, 114 of them
 * labelled): all of them on, MRR 0.507 → 0.564, hit@1 47.4% → 51.8%, hit@5 53.5% → 62.3%. What each was
 * worth is the drop with it switched off: [rankExactMatches] 0.048, [pathPriors] 0.033, [boxApiPrior]
 * 0.018, [nameWords] 0.008, [kdocTerms] 0.006, [receiverAware] 0.003, [demoteNonPublic] and
 * [demoteLineOnly] nothing on the mean. The last three are kept because each fixes a search that
 * failed in front of a person (in the KDoc of each), and none costs a labelled case; [kdocTerms] lowers
 * the opened-file labels (0.721 → 0.704) while raising the written ones (0.535 → 0.553). Figures in
 * `dev_docs/ai/AI4_FIRST_LOCAL_CONVERSATION.md`, "Symbol search".
 */
internal data class SymbolRankingOptions(
    /**
     * An exact name (`quat`, or `.quat` at the end of a qualified one) used to return every such
     * definition in index order, unranked: for `quat` a private formatter in `assimpBrowsing.kt` came before
     * the public `quat(angle, axis)`. They are now ordered by the same score as everything else.
     */
    val rankExactMatches: Boolean = true,
    /** `private`, `protected` and `internal` declarations rank below public ones: box code cannot call them. */
    val demoteNonPublic: Boolean = true,
    /**
     * For `Type.member` (or `Type member`), definitions on that type, its supertypes and aliases come
     * first; a top-level `member` (which `value.member` cannot reach) and the type's other members, which
     * matched only on the receiver, come after.
     */
    val receiverAware: Boolean = true,
    /** Definitions whose KDoc uses the query's words (`quat`'s KDoc for "rotate") are candidates too. */
    val kdocTerms: Boolean = true,
    /** Tests, examples, `src/personal` and the pen-learning corpora rank lower; box-facing packages higher. */
    val pathPriors: Boolean = true,
    /**
     * A name sharing camel-case words with the query is a candidate even when neither contains the other:
     * `clearOutput` finds `displayClear`, which the substring score never could.
     */
    val nameWords: Boolean = true,
    /** `Box.x` members and extensions rank a little higher: box code calls them bare, through the implicit receiver. */
    val boxApiPrior: Boolean = true,
    /**
     * A definition that matched only because the query occurs somewhere on its declaration line (a one-line
     * body that *uses* the name) ranks below names that match: `clearOutput` put `ShaderFieldProbe.deregister`
     * first because its body calls a `clearOutput` of its own.
     */
    val demoteLineOnly: Boolean = true,
) {
    companion object {
        val LEGACY = SymbolRankingOptions(
            rankExactMatches = false, demoteNonPublic = false, receiverAware = false, kdocTerms = false, pathPriors = false,
            nameWords = false, boxApiPrior = false, demoteLineOnly = false
        )
    }
}

/** The ranked definitions and, when the query names a receiver nothing on it answers, a sentence saying so. */
internal data class WorkspaceSymbolRanking(val definitions: List<KotlinDefinitionRecord>, val note: String = "")

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

/** `Type.member`, or `Type member`: [strict] when written with the dot. */
private data class ReceiverQuery(val receiver: String, val member: String, val strict: Boolean)

private val workspaceSearchNoiseTokens = setOf(
    "class", "interface", "object", "fun", "val", "var",
    "public", "private", "protected", "internal",
    "open", "final", "abstract", "sealed", "data", "enum",
    "annotation", "value", "inline", "suspend", "tailrec",
    "operator", "infix", "external", "override", "const",
    "lateinit", "companion", "inner"
)

/** Reads a source file's lines relative to the index root, once per search (or per eval run). */
internal class SourceLines(private val root: String) {
    private val cache = HashMap<String, List<String>>()
    fun of(path: String): List<String>? = cache.getOrPut(path) {
        val direct = Paths.get(path)
        val resolved = if (direct.isAbsolute) direct else Paths.get(root).resolve(path)
        if (!Files.exists(resolved)) emptyList()
        else runCatching { Files.readAllLines(resolved, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
    }.takeIf { it.isNotEmpty() }

    fun line(def: KotlinDefinitionRecord): String? = of(def.filePath)?.getOrNull(def.line - 1)?.trimEnd()
}

fun searchWorkspaceSymbols(
    index: KotlinSourceIndexDb?,
    rawQuery: String,
    limit: Int,
    includeRelatedContext: Boolean = false
): WorkspaceSymbolSearchResult {
    val query = rawQuery.trim()
    if (query.isEmpty()) return WorkspaceSymbolSearchResult(emptyList())
    val definitions = index?.definitions.orEmpty()
    if (index == null || definitions.isEmpty()) return WorkspaceSymbolSearchResult(emptyList())

    val lines = SourceLines(index.root)

    fun functionBody(def: KotlinDefinitionRecord, maxChars: Int = 6000): String? {
        val fileLines = lines.of(def.filePath) ?: return null
        if (def.kind == "val" || def.kind == "var") {
            val start = def.line.coerceAtMost(fileLines.size)
            val end = definitions.asSequence().filter { it.filePath == def.filePath && it.line > def.line }
                .minOfOrNull { it.line - 1 } ?: fileLines.size
            // Include property initializers/accessors, but never the next declaration's KDoc.
            return fileLines.subList((start - 1).coerceAtLeast(0), end.coerceIn(start, fileLines.size))
                .joinToString("\n").substringBefore("/**").trimEnd().take(maxChars)
        }
        if (def.kind != "fun") return null
        return KotlinSourceIndex.functionBodyText(fileLines, def.line, maxChars)
    }

    fun toMatch(def: KotlinDefinitionRecord): WorkspaceSymbolMatch {
        val location = if (!def.fileId.isNullOrBlank()) {
            "${def.filePath}:${def.line} (fileId=${def.fileId})"
        } else {
            "${def.filePath}:${def.line}"
        }
        val related = if (includeRelatedContext) RelatedContextDiscovery.forPath(def.filePath) else RelatedContextHints()
        return WorkspaceSymbolMatch(
            symbol = def.name,
            kind = def.kind,
            locationHint = location,
            filePath = def.filePath,
            fileId = def.fileId,
            line = def.line,
            definitionLine = lines.line(def),
            definitionBody = functionBody(def, maxChars = 3_000),
            relatedGuides = related.guides,
            relatedExamples = related.examples,
            kdoc = def.kdoc
        )
    }

    val ranking = rankWorkspaceSymbols(index, query, limit, lines = lines)
    val unindexed = unindexedFilesMentioning(index, query.substringAfterLast('.'))
    val warning = if (unindexed.isEmpty()) "" else "WARNING: ${unindexed.size} source file(s) saved since the symbol index was built " +
        "mention '$query' and their declarations may be missing from matches: ${unindexed.joinToString()}. " +
        "Read them with WorkspaceSearchRequest(scope=FIELD_KOTLIN_SOURCE, query=\"$query\")."
    return WorkspaceSymbolSearchResult(
        withBodyBudget(ranking.definitions.map(::toMatch)), unindexed,
        listOf(ranking.note, warning).filter { it.isNotBlank() }.joinToString(" ")
    )
}

/**
 * [index]'s definitions ranked for [rawQuery], best first, at most [limit].
 *
 * The base score is lexical: how the query (and each of its tokens) matches a definition's full name
 * (`Owner.member`, or `Receiver.name` for an extension), its simple name, and its declaration line. The
 * terms in [options] adjust it; [SymbolRankingOptions.LEGACY] leaves it as it was.
 */
internal fun rankWorkspaceSymbols(
    index: KotlinSourceIndexDb,
    rawQuery: String,
    limit: Int,
    options: SymbolRankingOptions = SymbolRankingOptions(),
    lines: SourceLines = SourceLines(index.root)
): WorkspaceSymbolRanking {
    val query = rawQuery.trim()
    val definitions = index.definitions
    if (query.isEmpty() || definitions.isEmpty()) return WorkspaceSymbolRanking(emptyList())
    val analysis = analyzeWorkspaceSymbolQuery(query)
    if (analysis.normalizedQueries.isEmpty()) return WorkspaceSymbolRanking(emptyList())
    val cap = limit.coerceIn(1, 100)
    val prepared = preparedDefinitions(definitions)
    val receiver = if (options.receiverAware) receiverQuery(query, prepared) else null
    val family = receiver?.let { prepared.families.of(it.receiver, lines) }.orEmpty()
    val focus = if (options.kdocTerms) focusWords(query, receiver) else emptyList()
    val queryWords = if (options.nameWords) focusWords(query, null, minLength = 3) else emptyList()

    fun score(def: KotlinDefinitionRecord, ordinal: Int): Int {
        val definitionLine = lines.line(def).orEmpty()
        val simpleName = def.name.substringAfterLast('.')
        val lexical = scoreWorkspaceSymbolCandidate(def.name, simpleName, definitionLine, def.kind, def.filePath, analysis)
        val kdocHits = if (focus.isEmpty()) 0 else prepared.kdocHits(ordinal, focus)
        val lineOnly = options.demoteLineOnly && lexical > 0 &&
            scoreWorkspaceSymbolCandidate(def.name, simpleName, "", def.kind, def.filePath, analysis) == 0
        val wordHits = if (queryWords.size < 2 || (lexical > 0 && !lineOnly)) 0 else nameWordHits(simpleName, queryWords)
        var score = when {
            lineOnly -> maxOf(lexical - 40, if (wordHits > 0) 50 + 20 * wordHits else 0) + 6 * kdocHits
            lexical > 0 -> lexical + 6 * kdocHits
            wordHits > 0 -> 50 + 20 * wordHits + 6 * kdocHits
            kdocHits > 0 -> 30 + 8 * kdocHits
            else -> return 0
        }
        if (receiver != null) score += receiverTerm(def, simpleName, receiver, family, kdocHits)
        if (options.demoteNonPublic && !isPublic(def, definitionLine)) score -= 45
        if (options.pathPriors) score += pathPrior(def.filePath)
        if (options.boxApiPrior && def.name.startsWith("Box.") && def.name.count { it == '.' } == 1) score += 10
        return score.coerceAtLeast(1)
    }

    fun ranked(candidates: Sequence<IndexedValue<KotlinDefinitionRecord>>): List<KotlinDefinitionRecord> {
        val best = LinkedHashMap<String, RankedWorkspaceSymbolMatch>()
        candidates.forEach { (ordinal, def) ->
            val s = score(def, ordinal)
            if (s <= 0) return@forEach
            val key = "${def.filePath}:${def.line}:${def.kind}:${def.name}"
            val existing = best[key]
            if (existing == null || s > existing.score) best[key] = RankedWorkspaceSymbolMatch(def, s, ordinal)
        }
        return best.values
            .sortedWith(compareByDescending<RankedWorkspaceSymbolMatch> { it.score }.thenBy { it.definitionIndex })
            .take(cap).map { it.definition }
    }

    // Avoid reading and scoring every source file for an exact indexed symbol.
    val exact = definitions.withIndex().filter { (_, d) -> d.name == query || d.name.endsWith(".$query") }
    val result = when {
        exact.isEmpty() -> ranked(definitions.asSequence().withIndex())
        options.rankExactMatches -> ranked(exact.asSequence())
        else -> exact.take(cap).map { it.value }
    }
    val note = if (receiver != null) receiverNote(receiver, family, prepared, lines, result) else ""
    return WorkspaceSymbolRanking(result, note)
}

/** Public, as box code sees it: the index's effective visibility, or the declaration line's own modifiers for an older index. */
private fun isPublic(def: KotlinDefinitionRecord, definitionLine: String): Boolean {
    if (def.visibility != null) return false
    val head = definitionLine.trimStart().substringBefore('(').split(Regex("\\s+"))
    return head.none { it == "private" || it == "internal" || it == "protected" }
}

private fun pathPrior(filePath: String): Int {
    val p = filePath.lowercase()
    return when {
        p.startsWith("src/personal/") -> -10
        "/jvmtest/" in p || p.startsWith("src/jvmtest/") || p.endsWith("test.kt") -> -30
        "/ai/learning/" in p -> -30
        "/examples/" in p -> -10
        p.startsWith("src/generatedlinalg/") || "/afield/math/linalg/" in p -> 12
        "/afield/geometry/" in p || "/afield/ui/output/" in p || "/afield/graphics/" in p -> 6
        else -> 0
    }
}

private fun receiverTerm(
    def: KotlinDefinitionRecord,
    simpleName: String,
    receiver: ReceiverQuery,
    family: Set<String>,
    kdocHits: Int
): Int {
    val qualifier = def.name.substringBeforeLast('.', "").substringAfterLast('.').takeIf { it.isNotEmpty() }
    val onFamily = qualifier != null && qualifier in family
    val member = receiver.member
    val nameMatches = simpleName.equals(member, ignoreCase = true)
    val nameRelated = nameMatches || symbolWords(simpleName).any { w -> symbolWords(member).any { wordsRelated(w, it) } }
    val strength = if (receiver.strict) 1.0 else 0.5
    val term = when {
        onFamily && nameMatches -> 90
        onFamily && nameRelated -> 55
        onFamily && kdocHits > 0 -> 35
        onFamily -> -70                             // only the receiver matched
        receiver.strict && def.name.substringAfterLast('.') == receiver.receiver && qualifier == null -> -30 // the type itself
        nameMatches && qualifier == null && def.kind == "fun" -> -80 // `v.member` cannot call a top-level member
        nameMatches -> -25                          // the same name on an unrelated type
        else -> 0
    }
    return (term * strength).toInt()
}

/**
 * When a `Type.member` query has no `member` on the type or its family, say so before the list, which would
 * otherwise read like an answer: the 2026-11-06 knight-tour turn asked for `Vector2f.rotate`, got private
 * top-level `rotate` helpers first, and only the KDoc of `quat` said how rotation is done (`v * quat(r)`).
 */
private fun receiverNote(
    receiver: ReceiverQuery,
    family: Set<String>,
    prepared: PreparedDefinitions,
    lines: SourceLines,
    result: List<KotlinDefinitionRecord>
): String {
    if (!receiver.strict || family.isEmpty()) return ""
    val knownType = prepared.families.isType(receiver.receiver)
    if (!knownType) return ""
    val direct = prepared.byMemberName[receiver.member.lowercase()].orEmpty().any { def ->
        def.name.substringBeforeLast('.', "").substringAfterLast('.') in family
    }
    if (direct) return ""
    val others = (family - receiver.receiver).sorted()
    val familyText = if (others.isEmpty()) "" else " (or ${others.joinToString(", ")})"
    val closest = result.take(3).joinToString(", ") { it.name }
    return "No `${receiver.member}` member or extension on ${receiver.receiver}$familyText is indexed." +
        if (closest.isEmpty()) "" else " Closest by name or documentation: $closest."
}

private fun receiverQuery(query: String, prepared: PreparedDefinitions): ReceiverQuery? {
    val cleaned = query.trim()
        .replace(Regex("^(?:(?:data|sealed|enum|annotation|abstract|open)\\s+)?(?:class|interface|object|fun|val|var)\\s+"), "")
        .substringBefore('(').trim()
    Regex("^((?:[A-Za-z_]\\w*\\.)*[A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)$").find(cleaned)?.let { m ->
        val receiver = m.groupValues[1].substringAfterLast('.')
        if (receiver.first().isUpperCase()) return ReceiverQuery(receiver, m.groupValues[2], strict = true)
    }
    Regex("^([A-Z]\\w*)\\s+([a-z_]\\w*)$").find(cleaned)?.let { m ->
        if (prepared.families.isType(m.groupValues[1])) return ReceiverQuery(m.groupValues[1], m.groupValues[2], strict = false)
    }
    return null
}

/** How many of the query's words a word of [simpleName] shares a stem with. */
private fun nameWordHits(simpleName: String, queryWords: List<String>): Int {
    val words = symbolWords(simpleName)
    return queryWords.count { q -> words.any { wordsRelated(it, q) } }
}

/** The query's words that KDoc may use: the member's for a receiver query, otherwise all of them; [minLength] letters or more. */
private fun focusWords(query: String, receiver: ReceiverQuery?, minLength: Int = 4): List<String> {
    val source = receiver?.member ?: query
    return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(source).map { it.value }
        .filter { it.lowercase() !in workspaceSearchNoiseTokens }
        .flatMap { symbolWords(it) }
        .filter { it.length >= minLength && it !in kdocStopWords }
        .distinct().toList()
}

private val kdocStopWords = setOf("with", "from", "this", "that", "into", "when", "each", "what", "have", "also", "only", "more")

/**
 * A name's words, lower-cased: its camel-case parts of three letters or more and the whole name
 * (`displayClear` → display, clear, displayclear; `FLine` → line, fline).
 */
internal fun symbolWords(name: String): List<String> {
    val parts = Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\\d+").findAll(name).map { it.value.lowercase() }.filter { it.length >= 3 }
    return (parts + name.lowercase().filter { it.isLetterOrDigit() }).distinct().toList()
}

/**
 * Two lower-case words with the same stem ([wordStem]): `rotate`/`rotation`, `line`/`lines`,
 * `translate`/`translated`, but not `display`/`displacement` or `text`/`texture`, which a shared-prefix
 * rule matched.
 */
internal fun wordsRelated(a: String, b: String): Boolean = a == b || wordStem(a) == wordStem(b)

private val stemSuffixes = listOf("ations", "ation", "ate", "ions", "ion", "ings", "ing", "ments", "ment", "ers", "er", "ed", "es", "s", "e")

/** [word] less its first suffix in [stemSuffixes] that leaves four letters or more; a crude, predictable stemmer. */
internal fun wordStem(word: String): String {
    for (suffix in stemSuffixes) {
        if (word.endsWith(suffix) && word.length - suffix.length >= 4) return word.dropLast(suffix.length)
    }
    return word
}

/** Per-index work shared across searches: KDoc words, members by simple name, and type families. */
private class PreparedDefinitions(val definitions: List<KotlinDefinitionRecord>) {
    private val kdocWords: Array<Set<String>?> = arrayOfNulls(definitions.size)
    val byMemberName: Map<String, List<KotlinDefinitionRecord>> =
        definitions.filter { '.' in it.name }.groupBy { it.name.substringAfterLast('.').lowercase() }
    val families = TypeFamilies(definitions)

    fun kdocHits(ordinal: Int, focus: List<String>): Int {
        val words = kdocWords[ordinal] ?: run {
            val kdoc = definitions[ordinal].kdoc
            val set = if (kdoc.isNullOrBlank()) emptySet()
            else Regex("[A-Za-z][A-Za-z0-9]{3,}").findAll(kdoc.take(800)).map { wordStem(it.value.lowercase()) }.toHashSet()
            kdocWords[ordinal] = set
            set
        }
        if (words.isEmpty()) return 0
        return focus.count { f -> wordStem(f) in words }
    }
}

private val preparedCache: MutableMap<List<KotlinDefinitionRecord>, PreparedDefinitions> =
    Collections.synchronizedMap(IdentityHashMap())

private fun preparedDefinitions(definitions: List<KotlinDefinitionRecord>): PreparedDefinitions = synchronized(preparedCache) {
    // One index is live at a time; keep the last couple rather than every index a long session has seen.
    preparedCache[definitions] ?: PreparedDefinitions(definitions).also {
        if (preparedCache.size >= 2) preparedCache.clear()
        preparedCache[definitions] = it
    }
}

/**
 * A type and what a value of it can also be called through: its supertypes (read from the class header),
 * the types it aliases or that alias it (`typealias Vec2 = Vector2f`), and the read-only `…c` interface of
 * the generated linear algebra (`Vector2fc` for `Vector2f`). Members and extensions declared on any of
 * these apply to a `Vector2f`, which is what a `Vector2f.rotate` query means.
 */
internal class TypeFamilies(definitions: List<KotlinDefinitionRecord>) {
    // By simple name, top-level declarations only when there are any: `Vec2` is the linear-algebra alias,
    // not also `KValue.Vec2`, whose supertype dragged `KValue` into Vector2f's family.
    private val types: Map<String, List<KotlinDefinitionRecord>> = definitions
        .filter { it.kind == "class" || it.kind == "interface" || it.kind == "object" || it.kind == "typealias" }
        .groupBy { it.name.substringAfterLast('.') }
        .mapValues { (_, defs) -> defs.filter { '.' !in it.name }.ifEmpty { defs } }
    private var aliasesTo: Map<String, Set<String>>? = null

    fun isType(name: String) = name in types

    fun of(name: String, lines: SourceLines): Set<String> {
        val out = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(name))
        while (queue.isNotEmpty() && out.size < 24) {
            val t = queue.removeFirst()
            if (!out.add(t)) continue
            types[t].orEmpty().forEach { def ->
                when (def.kind) {
                    "typealias" -> aliasTarget(def, lines)?.let(queue::add)
                    else -> queue.addAll(supertypes(def, lines))
                }
            }
            reverseAliases(lines)[t].orEmpty().forEach(queue::add)
            if (t.endsWith("c") && t.dropLast(1) in types) queue.add(t.dropLast(1))
            if ("${t}c" in types) queue.add("${t}c")
        }
        return out.filter { it in types || it == name }.toSet()
    }

    private fun reverseAliases(lines: SourceLines): Map<String, Set<String>> = aliasesTo ?: run {
        val map = HashMap<String, MutableSet<String>>()
        types.values.flatten().filter { it.kind == "typealias" }.forEach { def ->
            aliasTarget(def, lines)?.let { map.getOrPut(it) { linkedSetOf() } += def.name.substringAfterLast('.') }
        }
        aliasesTo = map
        map
    }

    private fun aliasTarget(def: KotlinDefinitionRecord, lines: SourceLines): String? =
        lines.line(def)?.substringAfter('=', "")?.trim()?.substringBefore('<')?.substringBefore('?')
            ?.substringAfterLast('.')?.trim()?.takeIf { it.isNotEmpty() && it.first().isLetter() }

    private fun supertypes(def: KotlinDefinitionRecord, lines: SourceLines): List<String> {
        val file = lines.of(def.filePath) ?: return emptyList()
        val header = StringBuilder()
        for (i in (def.line - 1) until minOf(file.size, def.line + 7)) {
            val line = file[i]
            header.append(line.substringBefore('{')).append(' ')
            if ('{' in line) break
        }
        var text = header.toString()
        // Drop the constructor's parameters and every type argument, innermost first.
        repeat(8) { text = text.replace(Regex("\\([^()]*\\)"), "").replace(Regex("<[^<>]*>"), "") }
        val simple = def.name.substringAfterLast('.')
        val afterName = text.substringAfter(simple, "")
        val colon = afterName.indexOf(':')
        if (colon < 0) return emptyList()
        return afterName.substring(colon + 1).substringBefore(" where ").split(',')
            .map { it.trim().substringBefore(' ').substringAfterLast('.').trim() }
            .filter { it.isNotEmpty() && it.first().isUpperCase() && it != "Any" }
    }
}

/**
 * `.kt` files under the index root's src/ saved after the index was built (and after their own record)
 * that contain [name] literally, at most ten. A file with no record is not enough: the index never covers
 * some trees (`src/personal/verification`, tests), and listing those on every search was noise. On 2026-10-28 a local model asked for `drawn` an hour after
 * `Shape2.drawn` was written, got eight unrelated hits, and could not tell the index was behind.
 */
internal fun unindexedFilesMentioning(index: KotlinSourceIndexDb, name: String, limit: Int = 10): List<String> {
    if (name.isBlank()) return emptyList()
    val root = Paths.get(index.root)
    val src = root.resolve("src")
    if (!Files.isDirectory(src)) return emptyList()
    val recorded = index.files.associate { root.resolve(it.path).normalize().toString() to it.modifiedEpochMs }
    return runCatching {
        Files.walk(src).use { paths ->
            paths.filter { it.toString().endsWith(".kt") && Files.isRegularFile(it) }
                .filter { path ->
                    val mtime = Files.getLastModifiedTime(path).toMillis()
                    mtime > index.updatedAtEpochMs && mtime > (recorded[path.normalize().toString()] ?: Long.MIN_VALUE)
                }
                .filter { runCatching { Files.readString(it).contains(name) }.getOrDefault(false) }
                .limit(limit.toLong())
                .map { root.relativize(it).toString() }
                .toList()
        }
    }.getOrDefault(emptyList())
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

/** All the definition bodies one search returns, together; see [withBodyBudget]. */
internal const val SYMBOL_SEARCH_BODY_BUDGET = 6_000

/**
 * Bodies in rank order until [SYMBOL_SEARCH_BODY_BUDGET]; later matches keep their signature and KDoc.
 * One search for "FLines" returned 17k characters, most of it a single unrelated function's body, to a
 * local model with a 64k-token window (2026-10-22). A body left out says how to get it.
 *
 * The ranking that orders those matches is [rankWorkspaceSymbols]. An earlier attempt left it unchanged
 * after an uncommitted measurement; the committed `WorkspaceSymbolSearchEval` (282 logged searches, 114
 * labelled) put the old ranking at MRR 0.507 and the terms in [SymbolRankingOptions] at 0.564.
 */
internal fun withBodyBudget(matches: List<WorkspaceSymbolMatch>): List<WorkspaceSymbolMatch> {
    var left = SYMBOL_SEARCH_BODY_BUDGET
    return matches.map { match ->
        val body = match.definitionBody ?: return@map match
        if (body.length <= left) { left -= body.length; match }
        else match.copy(definitionBody = "(body of ${body.length} chars omitted from this search; read it with AttachmentRequest(file=\"${match.filePath}\", find=\"${match.symbol.substringAfterLast('.')}\"))")
    }
}
