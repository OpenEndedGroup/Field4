package afield.ai.retrieval

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Path

/**
 * How well documentation search answers the questions the repository already asks of it.
 *
 * Nothing here is written for the test. Queries and answers come from two sources the authors wrote for
 * readers: the text of every cross-link between authored docs pages (`[drawing with FLines](fline_examples.md)`
 * is a query whose answer is `fline_examples.md`; the standard anchor-text method), and the `docs: why` lines
 * of the agent transcripts (`dev_docs/agents/transcripts/<name>.yaml`), each of which says what a page explains.
 * An anchor whose text points at three or more different pages ("API") is ambiguous and is left out; the
 * page a link sits on is not counted against it, since it contains the anchor text by construction.
 *
 * Begun 2026-10-22, when every vector in the index turned out to be zero and doc search had never been measured.
 */
object RetrievalEval {
    data class Case(val set: String, val query: String, val pages: Set<String>, val excludePage: String = "")

    private val link = Regex("""(?<!!)\[([^\]]{3,120})]\(([^)#\s]+\.md)(#[^)]*)?\)""")
    private val fence = Regex("""^\s*(```|~~~)""")
    private val generic = setOf("here", "this page", "this", "link", "docs", "see", "api", "reference", "below", "above")

    /** Cross-links between authored pages (not the generated reference, not assets). */
    fun anchorCases(docsRoot: File): List<Case> {
        val raw = mutableListOf<Case>()
        docsRoot.walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { file ->
            val rel = file.relativeTo(docsRoot).invariantSeparatorsPath
            if (rel.startsWith("reference/") || rel.startsWith("assets/")) return@forEach
            var inFence = false
            file.readLines().forEach { line ->
                if (fence.containsMatchIn(line)) { inFence = !inFence; return@forEach }
                if (inFence) return@forEach
                link.findAll(line).forEach { m ->
                    val text = m.groupValues[1].replace(Regex("[`*_]"), "").trim()
                    val target = File(file.parentFile, m.groupValues[2]).normalize()
                    val targetRel = runCatching { target.relativeTo(docsRoot).invariantSeparatorsPath }.getOrNull()
                    if (targetRel != null && target.isFile && targetRel != rel && !targetRel.startsWith("..") &&
                        text.lowercase() !in generic && Regex("[A-Za-z]{2,}").containsMatchIn(text)) {
                        raw += Case("anchor", text, setOf(targetRel), rel)
                    }
                }
            }
        }
        val targetsPerText = raw.groupBy { it.query.lowercase() }.mapValues { (_, cs) -> cs.flatMap { it.pages }.toSet() }
        return raw.filter { (targetsPerText[it.query.lowercase()]?.size ?: 0) < 3 }
            .distinctBy { it.query.lowercase() to it.pages }
    }

    /** `docs: [{page, why}]` in the agent transcripts: what a page explains, in its author's words. */
    fun whyCases(transcriptsDir: File): List<Case> =
        transcriptsDir.listFiles { f -> f.extension == "yaml" }.orEmpty().sortedBy { it.name }.flatMap { file ->
            val parsed = runCatching { Yaml().load<Any?>(file.readText()) as? Map<*, *> }.getOrNull().orEmpty()
            (parsed["docs"] as? List<*>).orEmpty().mapNotNull { d ->
                val m = d as? Map<*, *> ?: return@mapNotNull null
                val page = m["page"]?.toString()?.substringBefore('#') ?: return@mapNotNull null
                val why = m["why"]?.toString()?.trim()?.takeIf { it.length >= 8 } ?: return@mapNotNull null
                Case("why", why, setOf(page))
            }
        }.distinctBy { it.query to it.pages }

    data class Lesson(val title: String, val files: List<String>, val summary: String)

    /** The graphics curriculum's lessons from its generated index: the lesson table and the reverse API index. */
    fun graphicsLessons(index: File): List<Lesson> {
        val text = index.takeIf { it.isFile }?.readText() ?: return emptyList()
        val table = text.substringAfter("## Lessons", "").substringBefore("## What each lesson uses")
        val uses = HashMap<String, MutableList<String>>()
        Regex("""(?m)^- `([^`]+)` — (.+)$""").findAll(text.substringAfter("## What each lesson uses", "")).forEach { m ->
            m.groupValues[2].split(", ").forEach { lesson -> uses.getOrPut(lesson.trim()) { mutableListOf() } += m.groupValues[1] }
        }
        return table.lines().mapNotNull { row ->
            val cells = row.trim().trim('|').split('|').map { it.trim() }
            if (cells.size != 4 || cells[0] == "Lesson" || cells[0].startsWith("---")) return@mapNotNull null
            val (title, under, kotlin, shaders) = cells
            val files = Regex("`([^`]+)`").findAll("$kotlin $shaders").map { it.groupValues[1] }.toList()
            val stages = files.filter { it.endsWith(".glsl") || it.endsWith(".slang") }.map { it.substringBeforeLast("_.").substringAfterLast('_') }.distinct()
            Lesson(title, files, buildString {
                append("Graphics lesson '").append(title).append("', run under ").append(under.replace("`", "")).append(" on the Field 4 renderer.")
                if (stages.isNotEmpty()) append(" Shaders: ").append(stages.joinToString(", ")).append('.')
                uses[title]?.let { append(" Uses ").append(it.joinToString(", ")).append('.') }
            })
        }
    }

    /**
     * Tutorial search, from the transcripts' `docs` links: the title a docs page's own author gave it is a query,
     * and the tutorials that link to that page answer it. The summaries indexed for tutorials leave these links out.
     */
    fun tutorialCases(checkout: Path): List<Case> {
        val docsRoot = checkout.resolve("public/docs").toFile()
        val byPage = HashMap<String, MutableSet<String>>()
        checkout.resolve("dev_docs/agents/transcripts").toFile().listFiles { f -> f.extension == "yaml" }.orEmpty().forEach { file ->
            if (!checkout.resolve("public/docs/assets/agent_transcripts/${file.nameWithoutExtension}").toFile().isDirectory) return@forEach
            val parsed = runCatching { Yaml().load<Any?>(file.readText()) as? Map<*, *> }.getOrNull().orEmpty()
            (parsed["docs"] as? List<*>).orEmpty().forEach { d ->
                val page = (d as? Map<*, *>)?.get("page")?.toString()?.substringBefore('#') ?: return@forEach
                byPage.getOrPut(page) { mutableSetOf() } += file.nameWithoutExtension
            }
        }
        return byPage.mapNotNull { (page, tutorials) ->
            val title = File(docsRoot, page).takeIf { it.isFile }?.useLines { lines -> lines.firstOrNull { it.startsWith("# ") } }
                ?.removePrefix("# ")?.replace(Regex("""\s*\{[^}]*}\s*$"""), "")?.trim() ?: return@mapNotNull null
            Case("tutorial", title, tutorials)
        }.sortedBy { it.query }
    }

    fun cases(checkout: Path): List<Case> =
        anchorCases(checkout.resolve("public/docs").toFile()) + whyCases(checkout.resolve("dev_docs/agents/transcripts").toFile())

    data class Score(val set: String, val n: Int, val hit1: Int, val hit5: Int, val mrr: Double) {
        override fun toString() = "%-7s n=%4d  hit@1=%5.1f%%  hit@5=%5.1f%%  MRR@10=%.3f".format(set, n, 100.0 * hit1 / n, 100.0 * hit5 / n, mrr)
    }

    /**
     * Rank of the first result on an answering page, counting pages once and skipping [Case.excludePage].
     * [search] returns result paths relative to the checkout (`public/docs/...`), best first.
     */
    fun rank(case: Case, results: List<String>): Int? {
        val pages = results.map { it.removePrefix("public/docs/") }.filter { it != case.excludePage }.distinct()
        return pages.indexOfFirst { it in case.pages }.takeIf { it >= 0 }?.plus(1)
    }

    fun score(cases: List<Case>, search: (String) -> List<String>): Pair<List<Score>, List<Pair<Case, Int?>>> {
        val ranked = cases.map { it to rank(it, search(it.query)) }
        val scores = ranked.groupBy { it.first.set }.map { (set, rows) ->
            Score(set, rows.size, rows.count { it.second == 1 }, rows.count { (it.second ?: 99) <= 5 },
                rows.sumOf { r -> r.second?.takeIf { it <= 10 }?.let { 1.0 / it } ?: 0.0 } / rows.size)
        }
        return scores to ranked
    }
}

/**
 * Score documentation search on [RetrievalEval.cases]. Run from a checkout with an isolated index:
 * `-Dafield.semanticSearch.cacheRoot=<dir with config.json + models>` and
 * `-Dafield.semanticSearch.indexPath=<dir>`; `-Dafield.semanticSearch.corpora=documentation` keeps the build to
 * minutes. Arguments: `rebuild` to (re)build first; then fusion modes to compare (default: hybrid lexical dense).
 * Writes per-case ranks to `build/retrieval-eval/<mode>.tsv`.
 */
fun main(args: Array<String>) {
    val checkout = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath()
    if ("rebuild" in args) {
        val t0 = System.currentTimeMillis()
        val built = SemanticRetrievalService.rebuild()
        println("rebuild: ${built.success} ${built.message} (${(System.currentTimeMillis() - t0) / 1000}s)")
        if (!built.success) return
    }
    val cases = RetrievalEval.cases(checkout)
    println("${cases.size} cases: " + cases.groupingBy { it.set }.eachCount())
    val modes = args.filter { it != "rebuild" }.ifEmpty { listOf("hybrid", "lexical", "dense") }
    val out = checkout.resolve("build/retrieval-eval").toFile().apply { mkdirs() }
    for (mode in modes) {
        System.setProperty("afield.semanticSearch.fusion", mode)
        val (scores, ranked) = RetrievalEval.score(cases) { q ->
            SemanticRetrievalService.query(SemanticRetrievalQueryRequest(q, limit = 20, corpora = listOf("documentation"), rebuildIfEmpty = false))
                .results.map { it.path }
        }
        val (tutorialScores, tutorialRanked) = RetrievalEval.score(RetrievalEval.tutorialCases(checkout)) { q ->
            SemanticRetrievalService.query(SemanticRetrievalQueryRequest(q, limit = 40, corpora = listOf("tutorials"), rebuildIfEmpty = false))
                .results.map { it.documentName }
        }
        println("== $mode")
        (scores + tutorialScores).forEach { println("   $it") }
        java.io.File(out, "$mode-tutorials.tsv").writeText(tutorialRanked.joinToString("\n") { (c, r) -> "${r ?: "-"}\t${c.query}\t${c.pages.joinToString(",")}" })
        java.io.File(out, "$mode.tsv").writeText(ranked.joinToString("\n") { (c, r) -> "${c.set}\t${r ?: "-"}\t${c.query.replace('\t', ' ').replace('\n', ' ')}\t${c.pages.joinToString(",")}" })
    }
}
