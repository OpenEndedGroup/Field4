package afield

import java.io.File
import kotlin.random.Random

/*
FIELD_TEST
id: completion.lowering-invariant.probe
runner: jvm-main
entrypoint: afield.CompletionLoweringProbe
tags: [probe, kotlin, completion]
timeoutSec: 1200
expect:
  exitCode: 0
  stdoutContains:
    - "LOWERING PROBE DONE"
*/
/**
 * Measures the two things completion quality actually rests on, over a corpus of REAL boxes.
 *
 * The premise is that every "autocomplete offered junk" report so far has had the same cause: the
 * synthetic file the AA sidecar builds for a box (THE LOWERING — see `AaLoweredFile`) is an
 * approximation of that box's compilation unit, and when the approximation loses something the answer is
 * confidently wrong rather than absent. Both measurements here are ground-truth-based, so neither needs
 * anyone to decide what the right answer was:
 *
 *  1. **The lowering invariant.** Type-check the lowered file and attribute each error to a region.
 *     Errors in the *injected* regions (preamble at file scope, local stubs, the `fun __c` wrapper) are
 *     the lowering's own fault and should be zero. Errors in the *snippet* region are the box's code
 *     needing runtime context a headless session can't have — not a defect, but a direct prediction of
 *     where completion will be weak, and the unresolved-name histogram is the to-do list.
 *
 *  2. **Caret recall against the source itself.** Real box code that works is a labelled corpus for
 *     free: at a caret in front of `pos.add(…)`, the answer `add` is known without anyone writing a
 *     fixture. Sweep carets, ask for completion, and check whether what the author actually wrote comes
 *     back. Two modes, because they are different questions:
 *       - `blank` — everything from the caret to end of line removed: what happens while TYPING.
 *       - `keep`  — the rest of the line left in place: what happens EDITING existing code.
 *     A gap between the two is a parse-recovery problem; a gap in both is a scope problem.
 *
 * Corpus: [CompletionCorpus]'s checked-in fixtures over the bundled starting points — in-repo,
 * deterministic, derived from real agent sessions, and carrying each box's preamble AS MACRO-EXPANDED,
 * which is the context a real editing session has. That is what puts the `/*supermap*/ class
 * MappedStructure` + `fun Mesh.set(…, builder: MappedStructure.() -> Unit)` form (the one that produced
 * the `tri` → `TriOsc` bug) and every `prop<>`-declared name under measurement. Point
 * `AFIELD_LOWERING_CORPUS` at an un-harvested directory and it reads the documents raw instead, which is
 * useful for a personal workspace but reports every macro-declared name as unresolved.
 *
 * Report-only by design: it prints numbers, not a verdict. Thresholds belong in a gate once the numbers
 * are known, and inventing them before measuring is how you get a gate that asserts today's bugs.
 *
 * Knobs, all env vars (the runner passes the environment through):
 *   AFIELD_LOWERING_CORPUS        comma-separated dirs (default: starting points + templates)
 *   AFIELD_LOWERING_DOCS          max documents (default 200)
 *   AFIELD_LOWERING_CARETS        carets sampled per box (default 4)
 *   AFIELD_LOWERING_MAX_REQUESTS  cap on completion requests (default 4000 — i.e. the whole corpus)
 *   AFIELD_LOWERING_SEED          RNG seed (default 42)
 *   AFIELD_LOWERING_JSON          write the machine summary here (default build/completion-lowering/report.json)
 *   AFIELD_LOWERING_SKIP_RECALL   set to 1 to run the invariant only
 */
object CompletionLoweringProbe {

    // ── corpus model ────────────────────────────────────────────────────────────────────────────────

    // ── caret targets ───────────────────────────────────────────────────────────────────────────────

    /**
     * A place in real code where the source says what completion should offer: the identifier's start
     * offset, the identifier itself, and whether it sits after a `.`. Each site is probed under every
     * (characters-typed × mode) variant, so `dot/0` vs `dot/2` compares the SAME carets rather than two
     * independent samples — which is the whole point, since the gap between them is the diagnosis.
     */
    private data class Site(val offset: Int, val truth: String, val kind: String)

    /** One question the recall sweep will put to the sidecar: a [Site] of a box under one variant. */
    private data class Ask(
        val case: CompletionCorpus.Case,
        val site: Site,
        val typed: Int,
        val mode: String,
    )

    private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    /** Names that carry no information about scope — `it`, keywords, and the like. */
    private val UNINTERESTING = setOf(
        "it", "this", "val", "var", "fun", "if", "else", "for", "while", "return", "true", "false", "null",
        "in", "is", "as", "when", "by", "object", "class", "import", "to", "do", "try", "catch",
    )
    /** A caret right after one of these is a DECLARATION site: the author is naming something new, so
     *  there is no correct completion and counting it as a miss measures nothing. */
    private val DECLARES = Regex("""\b(val|var|fun|class|object|interface|typealias|enum\s+class)\s+$""")
    /**
     * A dotted path of all-lowercase segments ending at the caret — i.e. a package path, not a member
     * chain. `afield.stdlib.` matches; `flock.order().` and `img.sobel().` do not (a call is not a package
     * segment), and neither does `Img.` (a classifier is capitalised by convention, and Field's corpus
     * holds to it). A path whose head is a local variable named in lowercase would be misfiled here, which
     * is why the two kinds are reported side by side rather than one being dropped.
     */
    private val PACKAGE_PATH = Regex("""(?:^|[^A-Za-z0-9_.)\]])[a-z][A-Za-z0-9_]*(?:\.[a-z][A-Za-z0-9_]*)*\.\s*$""")

    /**
     * [code] with every comment and string/char literal blanked to spaces — same length, so offsets still
     * mean what they meant. Without this the sweep samples carets inside prose ("the", "and", "whether"
     * were among the most-missed "identifiers" on the first run) and measures nothing but its own naivety.
     */
    private fun sanitize(code: String): String {
        val out = code.toCharArray()
        var i = 0
        fun blank(from: Int, to: Int) { for (k in from until minOf(to, out.size)) if (out[k] != '\n') out[k] = ' ' }
        while (i < code.length) {
            when {
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    blank(i, end); i = end
                }
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    blank(i, end); i = end
                }
                code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
                    blank(i, end); i = end
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val q = code[i]
                    var j = i + 1
                    while (j < code.length && code[j] != q && code[j] != '\n') { if (code[j] == '\\') j++; j++ }
                    blank(i, minOf(j + 1, code.length)); i = minOf(j + 1, code.length)
                }
                else -> i++
            }
        }
        return String(out)
    }

    /**
     * Every place in [code] where the source itself says what completion should offer.
     *
     * DOT targets (`recv.name`) are the strong signal: the receiver is right there, so a miss is
     * unambiguous. NAME targets are sampled with two characters typed, because at an EMPTY prefix the AA
     * path deliberately withholds star-import scopes (otherwise every keystroke on a blank line would
     * enumerate the stdlib), so an empty-prefix name miss would measure a design decision, not a defect.
     */
    private fun targetsIn(rawCode: String): List<Site> {
        val code = sanitize(rawCode)
        val out = mutableListOf<Site>()
        for (m in IDENT.findAll(code)) {
            val name = m.value
            if (name in UNINTERESTING || name.length < 3) continue
            val start = m.range.first
            val before = code.substring(0, start)
            // An `import` line is a package path, not a scope question.
            if (before.substringAfterLast('\n').trimStart().startsWith("import ")) continue
            val afterDot = before.trimEnd(' ', '\t').let { it.endsWith(".") && !it.endsWith("..") }
            if (afterDot) {
                // `afield.‸stdlib.linalg.Matrix4f` is a PACKAGE PATH, not a member access, and completing
                // it is a different feature with a different answer (sub-packages, not members). Reported
                // as its own kind so it neither inflates nor deflates the member numbers: mixed in, a
                // handful of fully-qualified type references were the largest single cluster of "misses"
                // and said nothing about member completion.
                out += Site(start, name, if (PACKAGE_PATH.containsMatchIn(before)) "pkg" else "dot")
            } else {
                if (DECLARES.containsMatchIn(before)) continue
                val prev = before.lastOrNull()
                if (prev == null || prev.isWhitespace() || prev in "(,={[+-*/<>!&|") {
                    out += Site(start, name, "name")
                }
            }
        }
        return out
    }

    /** The snippet as the editor would have it at [offset]: `blank` drops the rest of the line. */
    private fun snippetFor(code: String, offset: Int, mode: String): Pair<String, Int> {
        if (mode == "keep") return code to offset
        val eol = code.indexOf('\n', offset).let { if (it < 0) code.length else it }
        return (code.substring(0, offset) + code.substring(eol)) to offset
    }

    // ── report accumulators ─────────────────────────────────────────────────────────────────────────

    private class Counter<T> {
        val counts = LinkedHashMap<T, Int>()
        fun add(k: T, n: Int = 1) { counts[k] = (counts[k] ?: 0) + n }
        fun top(n: Int) = counts.entries.sortedByDescending { it.value }.take(n)
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\t", "\\t")

    @JvmStatic
    fun main(args: Array<String>) {
        val url = (System.getProperty("afield.completion.sidecarUrl")?.trim()?.ifBlank { null }
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")?.trim()?.ifBlank { null }
            ?: File("build/completion-corpus/sidecar-url.txt").takeIf { it.exists() }?.readText()?.trim()
                ?.ifBlank { null })
        if (url == null) {
            println("SKIP: no sidecar URL (set -Dafield.completion.sidecarUrl or write build/completion-corpus/sidecar-url.txt)")
            println("LOWERING PROBE DONE")
            return
        }
        System.setProperty("afield.completion.sidecarUrl", url)
        KotlinEngineConfig.activeCompletionBackend = KotlinEngineConfig.CompletionBackend.AA

        fun env(k: String, d: String) = System.getenv(k)?.trim()?.ifBlank { null } ?: d
        val corpusDirs = env("AFIELD_LOWERING_CORPUS", "public/docs/assets/agent_transcripts,templates")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::File).filter { it.isDirectory }
        val maxDocs = env("AFIELD_LOWERING_DOCS", "200").toInt()
        val caretsPerBox = env("AFIELD_LOWERING_CARETS", "4").toInt()
        // High enough to sweep the whole corpus (~1.7k questions at 4 carets/box, ~1 minute at the
        // measured p50): a cap that BINDS turns the report into a sample, and the buckets it starves are
        // the ones with the interesting numbers. It stays a knob so an exploration run can be quick.
        val maxRequests = env("AFIELD_LOWERING_MAX_REQUESTS", "4000").toInt()
        val seed = env("AFIELD_LOWERING_SEED", "42").toLong()
        val jsonOut = File(env("AFIELD_LOWERING_JSON", "build/completion-lowering/report.json"))
        val skipRecall = env("AFIELD_LOWERING_SKIP_RECALL", "0") == "1"
        val maxExamples = env("AFIELD_LOWERING_EXAMPLES", "14").toInt()
        val receivers = listOf("afield.Box")

        println("sidecar   $url")
        println("corpus    ${corpusDirs.joinToString(", ") { it.path }}")

        // The corpus comes from the checked-in harvest ([CompletionCorpus]) whenever it is present: those
        // fixtures carry each box's preamble AS EXPANDED, which is the context a real editing session has
        // and which no static read of a document can reconstruct. Falling back to the raw documents keeps
        // the probe usable against a directory that has never been harvested (e.g. a personal workspace),
        // with the caveat that every macro-declared name will read as unresolved.
        val dirNames = corpusDirs.map { it.path }
        val (fixtures, drift) = CompletionCorpus.readFixtures(dirs = dirNames)
        drift.take(5).forEach { println("  ! fixture drift: ${it.label}: ${it.what}") }
        val cases: List<CompletionCorpus.Case>
        val docCount: Int
        if (fixtures.isNotEmpty() && drift.isEmpty()) {
            cases = fixtures.take(maxDocs * 64)
            docCount = cases.map { it.doc }.distinct().size
            println("corpus    from harvested fixtures (${CompletionCorpus.FIXTURE_DIR})")
        } else {
            println("corpus    from documents on disk — NOT harvested, so macro-declared names will be unresolved")
            val docs = CompletionCorpus.documents(dirNames).take(maxDocs)
            docCount = docs.size
            cases = docs.flatMap { doc ->
                CompletionCorpus.kotlinBoxes(doc).map { rec ->
                    CompletionCorpus.Case(doc.name, rec.id, rec.props["code"]!!, rec.props["preamble"].orEmpty())
                }
            }
        }
        val withPreamble = cases.count { it.preamble.isNotBlank() }
        println("documents $docCount   kotlin boxes ${cases.size}   with a preamble $withPreamble   " +
            "lines ${cases.sumOf { it.code.lines().size }}")

        // ── 1. the lowering invariant ───────────────────────────────────────────────────────────────
        println()
        println("══ 1. LOWERING INVARIANT — does the file completion analyses type-check? ══")
        val byRegion = Counter<String>()
        val byRegionFactory = Counter<String>()
        val unresolvedNames = Counter<String>()
        val worstBoxes = mutableListOf<Triple<String, Int, String>>()   // label, injected-region errors, sample
        var lowered = 0
        var preambleDropped = 0
        var loweringFailed = 0
        val injectedRegions = setOf("preamble", "declarations", "stubs", "wrapper")
        for (c in cases) {
            val r = AaCompletionClient.lower(c.code, c.code.length, c.preamble, receivers, replImports = introImports)
            if (r == null || !r.success) { loweringFailed++; continue }
            lowered++
            if (c.preamble.isNotBlank() && !r.preambleInjected) {
                preambleDropped++
                println("  ! preamble NOT injected (unparseable) ${c.label}")
            }
            var injected = 0
            for (e in r.errors) {
                byRegion.add(e.region)
                byRegionFactory.add("${e.region}:${e.factory}")
                if (e.region in injectedRegions) injected++
                if (e.factory.contains("UNRESOLVED")) unresolvedNames.add(e.excerpt.take(40))
            }
            if (injected > 0) {
                val sample = r.errors.first { it.region in injectedRegions }
                worstBoxes += Triple("${c.label}", injected, "${sample.region}:${sample.factory} ${sample.excerpt}")
            }
        }
        println("  lowered ${lowered}/${cases.size} boxes (failed $loweringFailed), preambles dropped as unparseable: $preambleDropped")
        println("  errors by region: " + byRegion.counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
            .ifBlank { "none" })
        println("  INVARIANT (injected regions must be clean): " +
            "${worstBoxes.size} boxes with injected-region errors, ${byRegion.counts.filterKeys { it in injectedRegions }.values.sum()} errors")
        worstBoxes.sortedByDescending { it.second }.take(12).forEach { (label, n, sample) ->
            println("    $label  $n  e.g. $sample")
        }
        println("  top error kinds:")
        byRegionFactory.top(12).forEach { println("    ${it.value.toString().padStart(5)}  ${it.key}") }
        println("  most-unresolved names in box code (what a headless session cannot see):")
        unresolvedNames.top(20).forEach { println("    ${it.value.toString().padStart(5)}  ${it.key}") }

        // ── 2. caret recall against the source ─────────────────────────────────────────────────────
        val recall = Counter<String>()
        val attempts = Counter<String>()
        val missNotes = Counter<String>()
        val notesByBucket = LinkedHashMap<String, Counter<String>>()
        val missedNames = Counter<String>()
        val missExamples = mutableListOf<String>()
        var requests = 0
        val times = mutableListOf<Long>()
        if (!skipRecall) {
            println()
            println("══ 2. CARET RECALL — is what the author wrote actually offered? ══")
            val rng = Random(seed)
            // Every question the sweep could ask, built before any of it is asked so the request cap
            // truncates EVENLY across the corpus. Asked box-by-box (as this was), a 400-request cap only
            // ever reached the first ~30 of 182 boxes — every reported miss came from `alife_*`, `dot` got
            // 15 samples, and a floor set on 15 samples measures the sample, not the engine. Round-robin
            // by caret index instead: request N of the run is caret N/182 of box N%182.
            val perCase = cases.map { c ->
                val all = targetsIn(c.code)
                val picked = all.shuffled(rng).distinctBy { it.offset }.take(caretsPerBox)
                picked.flatMap { site ->
                    // A bare `.` and two characters typed are different questions of the same caret; a
                    // name position with nothing typed is not asked at all (the AA path withholds
                    // star-import scopes there on purpose, so it would measure a decision, not a defect).
                    val typedVariants = if (site.kind == "dot" || site.kind == "pkg") listOf(0, 2) else listOf(2)
                    // A NAMED ARGUMENT that is already written is not a fair `keep`-mode target: with
                    // `cellSide = 280` still on the line, completion is right to leave `cellSide` out —
                    // you don't offer a keyword argument that has been supplied. In `blank` mode the rest
                    // of the line is gone, so the same site is a legitimate test. Counting these as misses
                    // was inflating the typing-vs-editing gap with an artifact of the sweep.
                    val suppliedNamedArg = site.kind == "name" &&
                        Regex("""^\s*=[^=]""").containsMatchIn(c.code.drop(site.offset + site.truth.length))
                    typedVariants.flatMap { t -> listOf("blank", "keep").map { t to it } }
                        .filter { (typed, mode) ->
                            typed <= site.truth.length && !(mode == "keep" && suppliedNamedArg)
                        }
                        .map { (typed, mode) -> Ask(c, Site(site.offset + typed, site.truth, site.kind), typed, mode) }
                }
            }
            val queue = ArrayList<Ask>()
            var round = 0
            while (queue.size < maxRequests) {
                val added = perCase.count { round < it.size }
                if (added == 0) break
                perCase.forEach { if (round < it.size) queue += it[round] }
                round++
            }
            for (ask in queue.take(maxRequests)) {
                val (c, t, typed, mode) = ask
                val (snippet, cursor) = snippetFor(c.code, t.offset, mode)
                if (cursor > snippet.length) continue
                val key = "${t.kind}/$typed/$mode"
                attempts.add(key)
                requests++
                val t0 = System.nanoTime()
                val outcome = AaCompletionClient.complete(
                    snippet, cursor, c.preamble, receivers, replImports = introImports,
                )
                times += (System.nanoTime() - t0) / 1_000_000
                val cands = (outcome.result as? CompletionResult.Success)?.metadata.orEmpty()
                val hit = cands.any {
                    it.text.trimEnd('(', ' ', '=') == t.truth || it.displayText.startsWith(t.truth)
                }
                if (hit) recall.add(key)
                else {
                    // A histogram says WHAT is missed; it can't be reproduced. Keep a handful of
                    // misses verbatim — the line the caret was on, and the note — so each bucket
                    // in the summary leads to something that can be pasted into a box.
                    if (missExamples.size < maxExamples) {
                        val line = c.code.take(t.offset).substringAfterLast('\n') +
                            "‸" + c.code.drop(t.offset).substringBefore('\n')
                        missExamples += "${c.label} $key .${t.truth}\n" +
                            "        line: ${line.trim().take(110)}\n" +
                            "        note: ${outcome.detail.take(110)}"
                    }
                    missedNames.add(t.truth)
                    val note = outcome.detail.substringBefore(':').take(60).let {
                        if (outcome.detail.startsWith("empty")) "empty: " + outcome.detail
                            .substringAfter("empty: ").substringBefore(':').take(40) else it
                    }
                    missNotes.add(note)
                    // Kept per bucket as well as overall, because that is what a ratchet failure has to
                    // say: "bare-dot recall fell to 71%, and the sidecar's own note on those misses is X"
                    // is a diagnosis; "recall fell" is a number to argue about.
                    notesByBucket.getOrPut(key) { Counter() }.add(note)
                }
            }
            println("  requests $requests   latency p50=${times.sorted().getOrNull(times.size / 2)}ms")
            println("  RECALL (kind/typed-chars/mode):")
            attempts.counts.keys.sorted().forEach { k ->
                val n = attempts.counts[k] ?: 0
                val h = recall.counts[k] ?: 0
                println("    ${k.padEnd(16)} ${h.toString().padStart(4)}/${n.toString().padEnd(4)} " +
                    "${if (n > 0) "%.0f%%".format(100.0 * h / n) else "-"}")
            }
            val totalA = attempts.counts.values.sum(); val totalH = recall.counts.values.sum()
            println("    ${"TOTAL".padEnd(16)} ${totalH}/${totalA} ${if (totalA > 0) "%.0f%%".format(100.0 * totalH / totalA) else "-"}")
            println("  miss reasons (sidecar's own note):")
            missNotes.top(12).forEach { println("    ${it.value.toString().padStart(5)}  ${it.key}") }
            println("  most-missed identifiers:")
            missedNames.top(20).forEach { println("    ${it.value.toString().padStart(5)}  ${it.key}") }
            println("  example misses (paste-able):")
            missExamples.forEach { println("    $it") }
        }

        runCatching {
            jsonOut.parentFile?.mkdirs()
            val sb = StringBuilder("{\n")
            sb.append("""  "documents": $docCount, "boxes": ${cases.size}, "withPreamble": $withPreamble,""").append('\n')
            sb.append("""  "lowered": $lowered, "loweringFailed": $loweringFailed,""").append('\n')
            sb.append("""  "preambleDropped": $preambleDropped,""").append('\n')
            sb.append("""  "errorsByRegion": {""")
                .append(byRegion.counts.entries.joinToString(", ") { """"${it.key}": ${it.value}""" }).append("},\n")
            sb.append("""  "errorKinds": {""")
                .append(byRegionFactory.top(40).joinToString(", ") { """"${jsonEscape(it.key)}": ${it.value}""" }).append("},\n")
            sb.append("""  "unresolvedInSnippet": {""")
                .append(unresolvedNames.top(60).joinToString(", ") { """"${jsonEscape(it.key)}": ${it.value}""" }).append("},\n")
            sb.append("""  "recall": {""")
                .append(attempts.counts.keys.sorted().joinToString(", ") {
                    """"$it": [${recall.counts[it] ?: 0}, ${attempts.counts[it]}]"""
                }).append("},\n")
            sb.append("""  "missNotes": {""")
                .append(missNotes.top(40).joinToString(", ") { """"${jsonEscape(it.key)}": ${it.value}""" }).append("},\n")
            sb.append("""  "missedNames": {""")
                .append(missedNames.top(60).joinToString(", ") { """"${jsonEscape(it.key)}": ${it.value}""" }).append("}\n")
            sb.append("}\n")
            jsonOut.writeText(sb.toString())
            println()
            println("report → ${jsonOut.path}")
        }

        println("LOWERING PROBE DONE")
    }
}
