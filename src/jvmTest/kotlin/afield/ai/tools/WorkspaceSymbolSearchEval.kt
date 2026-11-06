package afield.ai.tools

import afield.runtime.completion.KotlinDefinitionRecord
import afield.runtime.completion.KotlinSourceIndex
import afield.runtime.completion.KotlinSourceIndexDb
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * How well `WorkspaceSymbolSearch` ranks, measured on searches agents really made.
 *
 * **Cases** come from `src/jvmTest/resources/ai4/workspace_symbol_search_cases.jsonl`, which
 * `tools/ai4_symbol_search_cases.py` extracts from the AI4 turn logs (`~/.afield/logs/ai4_tool_io/`): each
 * logged `WorkspaceSymbolSearch` with what the agent did in the next twelve tool calls of that conversation
 * (running on into the next turn when the turn ended first): the identifiers it newly wrote in box code and
 * the Kotlin files it opened.
 *
 * **The index** is rebuilt from this checkout's `src/jvmMain/kotlin` and `src/generatedLinalg/kotlin` with
 * the same parser Field uses ([KotlinSourceIndex.scanDefinitions]), never the machine's
 * `kotlin_source_index.json`, which may be days old or another checkout's. `src/personal` is left out on
 * purpose: it is untracked, so no worktree has it, and including it would make the number depend on whose
 * machine ran it. Cases whose only answers lived there show up as unresolved.
 *
 * **A label** is a definition the agent's next actions point at, and that relates to the query:
 *
 * - *Written*: a name the agent newly wrote in box code, resolved by Kotlin's scope rules as far as names
 *   allow. `quat(...)` written bare resolves to a top-level `quat`, to `Box.quat` (the implicit receiver),
 *   or to a type `quat`; `v.rotated` written after a dot resolves only to a member or extension `T.rotated`
 *   where `T` is in the family ([TypeFamilies]) of a type the written code or the query names, or, failing
 *   that, to every `*.rotated` when they all share one owner. Only public definitions count (box code
 *   cannot call the others).
 * - *Opened*: a definition in a Kotlin file the agent read next whose name relates to the query, or which
 *   the read's `find` string names.
 *
 * and, either way, it must relate to the query ([relatesToQuery]): a word of its simple name, or of its
 * KDoc, shares a stem with a word of the query (the member's words for `Type.member`). That keeps
 * `vec` and `println` from labelling every search before a box was written, and is how `quat` (whose KDoc
 * is about rotation) labels the `Vector2f.rotate` search that the next turn answered with `v * quat(r)`.
 *
 * **Scores** are hit@1, hit@5 and MRR@20 of the first label in a ranker's top twenty, over labelled cases,
 * overall, by label source and by model; unlabelled cases are counted by why.
 *
 * Limitations, honestly:
 *
 * - *Presentation bias.* Agents wrote and opened what the ranker of the day showed them, so labels lean
 *   toward the baseline: a better answer it never surfaced cannot be a label. Gains here are a lower bound;
 *   a ranker that differs from the baseline can only lose credit for surfacing something the agent never
 *   saw. Read the per-case diff, not only the means.
 * - *Names, not types.* Without a compiler, `x.size` cannot be resolved to `x`'s type; the type-family
 *   rule is a stand-in that can both miss (an inferred type never named) and over-credit (two named types
 *   that both declare the member).
 * - *Relatedness is lexical.* The stem rule is itself a crude ranker; a label whose name and KDoc share
 *   no word with the query (the agent found a different idea entirely) is dropped as unrelated.
 * - *Known names.* A name the agent wrote or read earlier in the conversation never labels a later search,
 *   but box code attached to the first turn's prompt is not in the logs, so its names can read as new.
 * - *Drift.* The logged queries ran against older sources than the ones indexed here; a definition
 *   renamed since is unresolved, not a miss.
 * - *Small.* About 280 searches, a third to a half of them labelled, dominated by one model; differences of a
 *   point or two in MRR are noise.
 */
internal object WorkspaceSymbolSearchEval {
    /** `quat` written bare, or `.rotated` written after a dot. */
    data class Written(val name: String, val dotted: Boolean)
    data class Opened(val file: String = "", val find: String = "")
    data class Case(
        val timestamp: String = "",
        val model: String = "",
        val provider: String = "",
        val conversation: String = "",
        val round: Int? = null,
        val query: String = "",
        val limit: Int = 6,
        val written: List<String> = emptyList(),
        val typesNamed: List<String> = emptyList(),
        val opened: List<Opened> = emptyList(),
        val callsInLaterTurns: Int = 0,
        val loggedTop: List<String> = emptyList(),
    ) {
        val writes: List<Written> get() = written.map { Written(it.removePrefix("."), it.startsWith(".")) }
    }

    enum class Source { WRITTEN, OPENED }
    data class Label(val definition: KotlinDefinitionRecord, val source: Source)
    data class Labelled(val case: Case, val labels: List<Label>, val unlabelledBecause: String = "")

    fun interface Ranker { fun rank(query: String): List<KotlinDefinitionRecord> }

    data class Score(val n: Int, val hit1: Double, val hit5: Double, val mrr: Double) {
        override fun toString() = if (n == 0) "n=0" else
            String.format(Locale.ROOT, "n=%3d  hit@1 %5.1f%%  hit@5 %5.1f%%  MRR %.3f", n, 100 * hit1, 100 * hit5, mrr)
    }

    const val DEPTH = 20
    val FIXTURE: Path = Path.of("src/jvmTest/resources/ai4/workspace_symbol_search_cases.jsonl")
    val REPORT: Path = Path.of("build/reports/ai4/workspace_symbol_search_eval.txt")

    private val mapper = ObjectMapper().registerKotlinModule()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun loadCases(path: Path = FIXTURE): List<Case> =
        Files.readAllLines(path).filter { it.isNotBlank() }.map { mapper.readValue<Case>(it) }

    /** A fresh index of [root]'s own Kotlin sources, with the parser Field uses. */
    fun buildIndex(root: Path): KotlinSourceIndexDb {
        val abs = root.toAbsolutePath().normalize()
        val definitions = KotlinSourceIndex.scanDefinitions(
            listOf(abs.resolve("src/jvmMain/kotlin"), abs.resolve("src/generatedLinalg/kotlin")), abs
        )
        return KotlinSourceIndexDb(root = abs.toString(), apiKeyHash = null, updatedAtEpochMs = System.currentTimeMillis(),
            files = emptyList(), definitions = definitions)
    }

    private fun qualifierOf(def: KotlinDefinitionRecord) = def.name.substringBeforeLast('.', "").substringAfterLast('.')
    private fun simpleOf(def: KotlinDefinitionRecord) = def.name.substringAfterLast('.')

    /** The query's words a label must share a stem with: the member's for `Type.member`, else all. */
    fun queryWords(query: String): List<String> {
        val cleaned = query.substringBefore('(').trim()
        val member = Regex("^(?:[A-Za-z_]\\w*\\.)*([A-Z]\\w*)\\.([A-Za-z_]\\w*)$").find(cleaned)?.groupValues?.get(2)
        val source = member ?: cleaned
        val noise = setOf("class", "interface", "object", "fun", "val", "var", "data", "enum", "private", "public")
        return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(source).map { it.value }
            .filter { it.lowercase() !in noise }.flatMap { symbolWords(it) }.distinct().toList()
    }

    /**
     * The label rule's own word relation, deliberately not the ranker's [wordsRelated]: equal, or both four
     * letters or more and sharing their first five (all of the shorter one if it has fewer). Tuning the
     * ranker must not move the labels it is scored against.
     */
    fun labelWordsRelated(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = minOf(a.length, b.length)
        if (shorter < 4) return false
        return a.regionMatches(0, b, 0, minOf(5, shorter))
    }

    fun relatesToQuery(def: KotlinDefinitionRecord, words: List<String>): Boolean {
        val name = symbolWords(simpleOf(def))
        if (name.any { n -> words.any { labelWordsRelated(n, it) } }) return true
        val kdoc = def.kdoc?.take(800)?.let { k -> Regex("[A-Za-z][A-Za-z0-9]{3,}").findAll(k).map { it.value.lowercase() }.toSet() }.orEmpty()
        return words.filter { it.length >= 4 }.any { w -> kdoc.any { labelWordsRelated(it, w) } }
    }

    class Labeller(private val index: KotlinSourceIndexDb) {
        private val lines = SourceLines(index.root)
        private val families = TypeFamilies(index.definitions)
        private val public = index.definitions.filter { it.visibility == null }
        private val topLevel = public.filter { '.' !in it.name }.groupBy { it.name }
        private val types = public.filter { it.kind in setOf("class", "interface", "object", "typealias") }.groupBy { simpleOf(it) }
        private val members = public.filter { '.' in it.name }.groupBy { simpleOf(it) }
        private val byFile = index.definitions.groupBy { it.filePath }

        fun label(case: Case): Labelled {
            val words = queryWords(case.query)
            val queryTypes = Regex("[A-Z][A-Za-z0-9_]*").findAll(case.query).map { it.value }.toList()
            val familyOf = (case.typesNamed + queryTypes + "Box").flatMap { families.of(it, lines) }.toSet()
            val resolved = linkedSetOf<KotlinDefinitionRecord>()
            case.writes.forEach { w ->
                if (w.dotted) {
                    val candidates = members[w.name].orEmpty()
                    val onNamed = candidates.filter { qualifierOf(it) in familyOf }
                    resolved += onNamed.ifEmpty { candidates.takeIf { c -> c.map(::qualifierOf).distinct().size == 1 }.orEmpty() }
                } else {
                    resolved += topLevel[w.name].orEmpty()
                    resolved += members[w.name].orEmpty().filter { qualifierOf(it) == "Box" || qualifierOf(it) in families.of("Box", lines) }
                    resolved += types[w.name].orEmpty().filter { '.' !in it.name }
                }
            }
            val labels = mutableListOf<Label>()
            resolved.filter { relatesToQuery(it, words) }.forEach { labels += Label(it, Source.WRITTEN) }
            var openedIndexed = false
            case.opened.forEach { o ->
                val defs = byFile[o.file].orEmpty()
                if (defs.isNotEmpty()) openedIndexed = true
                val findNames = Regex("[A-Za-z_]\\w*").findAll(o.find).map { it.value }.toSet()
                defs.filter { d ->
                    symbolWords(simpleOf(d)).any { n -> words.any { labelWordsRelated(n, it) } } ||
                        (simpleOf(d) in findNames && relatesToQuery(d, words))
                }.forEach { d -> if (labels.none { it.definition == d }) labels += Label(d, Source.OPENED) }
            }
            val why = when {
                labels.isNotEmpty() -> ""
                case.written.isEmpty() && case.opened.isEmpty() -> "nothing new written or opened"
                case.opened.any { it.file.startsWith("src/personal/") } && resolved.isEmpty() -> "answer in src/personal (not indexed)"
                resolved.isEmpty() && !openedIndexed -> "written names not Field definitions"
                else -> "nothing written or opened relates to the query"
            }
            return Labelled(case, labels, why)
        }
    }

    /** 1-based rank of the first label in [ranked], or null. */
    fun rankOf(labels: List<Label>, ranked: List<KotlinDefinitionRecord>): Int? {
        val keys = labels.map { key(it.definition) }.toSet()
        return ranked.indexOfFirst { key(it) in keys }.takeIf { it >= 0 }?.plus(1)
    }

    private fun key(d: KotlinDefinitionRecord) = "${d.filePath}:${d.line}:${d.name}"

    fun score(ranks: List<Int?>): Score {
        if (ranks.isEmpty()) return Score(0, 0.0, 0.0, 0.0)
        val n = ranks.size.toDouble()
        return Score(ranks.size, ranks.count { it == 1 } / n, ranks.count { it != null && it <= 5 } / n,
            ranks.sumOf { r -> if (r == null) 0.0 else 1.0 / r } / n)
    }

    data class Run(val name: String, val ranks: List<Int?>, val tops: List<List<KotlinDefinitionRecord>>)

    fun run(name: String, labelled: List<Labelled>, ranker: Ranker): Run {
        val tops = labelled.map { ranker.rank(it.case.query).take(DEPTH) }
        return Run(name, labelled.zip(tops).map { (l, top) -> rankOf(l.labels, top) }, tops)
    }

    /** The table: each run overall, by label source and by model; then the per-case diff of [diff] (before, after). */
    fun report(all: List<Labelled>, runs: List<Run>, vararg diffs: Pair<String, String>): String = buildString {
        val labelled = all.filter { it.labels.isNotEmpty() }
        appendLine("WorkspaceSymbolSearch eval: ${all.size} logged searches, ${labelled.size} labelled " +
            "(${labelled.count { l -> l.labels.any { it.source == Source.WRITTEN } }} by code written, " +
            "${labelled.count { l -> l.labels.any { it.source == Source.OPENED } }} by a file opened).")
        all.filter { it.labels.isEmpty() }.groupingBy { it.unlabelledBecause }.eachCount().entries.sortedByDescending { it.value }
            .forEach { (why, n) -> appendLine("  unlabelled, $why: $n") }
        appendLine()
        val labelledIdx = all.indices.filter { all[it].labels.isNotEmpty() }
        fun slice(run: Run, pick: (Labelled) -> Boolean) = score(labelledIdx.filter { pick(all[it]) }.map { run.ranks[it] })
        val width = runs.maxOf { it.name.length }
        appendLine("All labelled")
        runs.forEach { appendLine("  ${it.name.padEnd(width)}  ${slice(it) { true }}") }
        appendLine("Labelled by code written")
        runs.forEach { r -> appendLine("  ${r.name.padEnd(width)}  ${slice(r) { l -> l.labels.any { it.source == Source.WRITTEN } }}") }
        appendLine("Labelled by a file opened")
        runs.forEach { r -> appendLine("  ${r.name.padEnd(width)}  ${slice(r) { l -> l.labels.any { it.source == Source.OPENED } }}") }
        labelled.map { it.case.model }.distinct().sorted().forEach { model ->
            appendLine("Model $model")
            runs.forEach { r -> appendLine("  ${r.name.padEnd(width)}  ${slice(r) { it.case.model == model }}") }
        }
        for (diff in diffs) {
            val before = runs.first { it.name == diff.first }
            val after = runs.first { it.name == diff.second }
            appendLine()
            appendLine("Per-case diff, ${diff.first} -> ${diff.second} (rank of the first label; - = not in top $DEPTH):")
            val changed = labelledIdx.filter { before.ranks[it] != after.ranks[it] }
                .sortedBy { i -> (1.0 / (after.ranks[i] ?: 1000)) - (1.0 / (before.ranks[i] ?: 1000)) }
            changed.forEach { i ->
                val l = all[i]
                fun r(x: Int?) = x?.toString() ?: "-"
                val label = l.labels.first().definition.name
                appendLine("  ${r(before.ranks[i]).padStart(2)} -> ${r(after.ranks[i]).padEnd(2)}  ${l.case.model.padEnd(12)} " +
                    "\"${l.case.query}\"  label ${label}${if (l.labels.size > 1) " (+${l.labels.size - 1})" else ""}; " +
                    "now first: ${after.tops[i].firstOrNull()?.name ?: "-"}")
            }
            appendLine("  ${changed.size} changed, ${changed.count { (after.ranks[it] ?: 1000) < (before.ranks[it] ?: 1000) }} better.")
        }
    }

    /** Legacy, the shipped ranker, and the shipped ranker with each term switched off. */
    fun standardRuns(index: KotlinSourceIndexDb, labelled: List<Labelled>): List<Run> {
        val lines = SourceLines(index.root)
        fun ranker(options: SymbolRankingOptions) = Ranker { q -> rankWorkspaceSymbols(index, q, DEPTH, options, lines).definitions }
        val shipped = SymbolRankingOptions()
        return listOf(
            run("legacy", labelled) { q -> LegacyWorkspaceSymbolRanker.rank(index.definitions, q, DEPTH, lines::line) },
            run("current", labelled, ranker(shipped)),
            run("- rankExactMatches", labelled, ranker(shipped.copy(rankExactMatches = false))),
            run("- demoteNonPublic", labelled, ranker(shipped.copy(demoteNonPublic = false))),
            run("- receiverAware", labelled, ranker(shipped.copy(receiverAware = false))),
            run("- kdocTerms", labelled, ranker(shipped.copy(kdocTerms = false))),
            run("- pathPriors", labelled, ranker(shipped.copy(pathPriors = false))),
            run("- nameWords", labelled, ranker(shipped.copy(nameWords = false))),
            run("- boxApiPrior", labelled, ranker(shipped.copy(boxApiPrior = false))),
            run("- demoteLineOnly", labelled, ranker(shipped.copy(demoteLineOnly = false))),
        )
    }
}
