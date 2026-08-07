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
  # The ratchet. The numbers this probe prints were report-only for exactly one day, which meant any
  # regression in the completion backend was silent. A broken floor exits 1 (below); the marker is
  # asserted too, so a run that somehow prints the failure without exiting still fails here. Every
  # threshold, and why it is where it is, is in FLOORS — raise one when a fix moves it.
  stdoutNotContains:
    - "RATCHET FAIL"
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
 * **And it is a gate** (§3, [FLOORS]). It was report-only for one day — the right way round, since
 * inventing thresholds before measuring is how you get a gate that asserts today's bugs — but a
 * measurement nothing asserts leaves every regression in the completion backend silent. Each bucket has
 * a floor cut a few points below what was measured, plus a floor on its *sample size* (a percentage over
 * a bucket nobody sampled is not a measurement), and the hard invariants — every box lowers, no preamble
 * dropped, zero injected-region errors — are absolute. A failure names the bucket, what it fell from, and
 * the sidecar's own note on the misses. `--report` restores the exploration mode: same verdicts, exit 0.
 *
 * It lives in the SMOKE CATALOG rather than `./gradlew check` because it needs a live AA sidecar (a
 * `check` task must be deterministic and offline); `field.sh` starts a checkout-local one, and the probe
 * SKIPs cleanly where none is running.
 *
 * Knobs, all env vars (the runner passes the environment through):
 *   AFIELD_LOWERING_CORPUS        comma-separated dirs (default: starting points + templates)
 *   AFIELD_LOWERING_DOCS          max documents (default 200)
 *   AFIELD_LOWERING_CARETS        carets sampled per box (default 4)
 *   AFIELD_LOWERING_MAX_REQUESTS  cap on completion requests (default 4000 — i.e. the whole corpus)
 *   AFIELD_LOWERING_SEED          RNG seed (default 42)
 *   AFIELD_LOWERING_JSON          write the machine summary here (default build/completion-lowering/report.json)
 *   AFIELD_LOWERING_SKIP_RECALL   set to 1 to run the invariant only
 *   AFIELD_LOWERING_GATE          set to 0 (or pass `--report`) for the report-only exploration mode
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

    // ── the ratchet ─────────────────────────────────────────────────────────────────────────────────

    /**
     * One recall floor. [observed] is the number measured when the floor was last cut, so a failure can
     * say what it fell FROM; [minPercent] sits a few points below it, and [minAttempts] guards the
     * denominator (see the gate's own comment on why a bucket's sample size is a floor too).
     *
     * Every bucket is gated separately, and `blank` (typing) apart from `keep` (editing), because the
     * two carry different diagnoses: a gap between them is a parse-recovery problem, a gap in both is a
     * scope problem, and one aggregate number would hide which. Raise a floor when a fix moves its
     * number — that is what makes this a ratchet rather than a rubber stamp.
     */
    private data class Floor(val bucket: String, val minAttempts: Int, val minPercent: Int, val observed: Int)

    /**
     * The floors, all cut ~5 points below the number measured over the whole harvested corpus (1708
     * carets, ~60–530 per bucket). 5 points is chosen against the noise: the buckets are deterministic
     * given the same corpus and the same backend, so any movement at all is a real change, and the margin
     * exists for the ordering churn a candidate-set change causes near a truncation boundary — while every
     * regression this measurement has actually caught moved a bucket by 10 to 40 points.
     *
     * `minAttempts` is ~90% of the sample size observed with the same corpus; it only falls if the
     * corpus shrinks or the caret sweep stops recognising a kind of site.
     */
    private val FLOORS = listOf(
        // A bare `recv.` — the hardest and most-typed question, and the one that regresses first when
        // member/extension discovery breaks (this bucket is how "extensions by receiver" was measured).
        Floor("dot/0/blank", minAttempts = 55, minPercent = 93, observed = 98),
        Floor("dot/0/keep", minAttempts = 55, minPercent = 90, observed = 95),
        // Two characters typed: prefix filtering plus resolution. A gap to `dot/0` is a discovery problem.
        Floor("dot/2/blank", minAttempts = 55, minPercent = 93, observed = 98),
        Floor("dot/2/keep", minAttempts = 55, minPercent = 90, observed = 95),
        // Name position: scope, the index, and implicit-receiver extensions.
        Floor("name/2/blank", minAttempts = 478, minPercent = 89, observed = 94),
        Floor("name/2/keep", minAttempts = 407, minPercent = 89, observed = 94),
        // A fully-qualified path (`afield.stdlib.‸`). Its own kind because it is a different feature.
        Floor("pkg/0/blank", minAttempts = 108, minPercent = 92, observed = 97),
        Floor("pkg/0/keep", minAttempts = 108, minPercent = 92, observed = 97),
        Floor("pkg/2/blank", minAttempts = 108, minPercent = 92, observed = 97),
        Floor("pkg/2/keep", minAttempts = 108, minPercent = 92, observed = 97),
        // The headline number, gated too — not because the buckets don't cover it, but because it is what
        // gets quoted, and a change that trades one bucket off against another should have to say so.
        Floor("TOTAL", minAttempts = MIN_REQUESTS, minPercent = 90, observed = 95),
    )

    /** The sweep must have asked something like the whole corpus; a truncated sweep is not a gate. */
    private const val MIN_REQUESTS = 1600

    @JvmStatic
    fun main(args: Array<String>) {
        // Gate by default, so the catalog entry asserts; `--report` keeps the exploration mode the
        // measurement was built as (print every verdict, exit 0) for a run whose point is the residual.
        val gate = !args.contains("--report") && System.getenv("AFIELD_LOWERING_GATE")?.trim() != "0"
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
        val injectedErrors = byRegion.counts.filterKeys { it in injectedRegions }.values.sum()
        println("  INVARIANT (injected regions must be clean): " +
            "${worstBoxes.size} boxes with injected-region errors, $injectedErrors errors")
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

        // ── 3. the ratchet ──────────────────────────────────────────────────────────────────────────
        println()
        println("══ 3. RATCHET — the floors this gate holds ══")
        val broken = mutableListOf<String>()
        val held = mutableListOf<String>()
        fun hard(desc: String, ok: Boolean, detail: String) {
            if (ok) { held += desc; println("  ✓ $desc — $detail") }
            else { broken += desc; println("  ✗ RATCHET FAIL $desc — $detail") }
        }
        hard("every box lowers", lowered == cases.size, "$lowered/${cases.size} lowered, $loweringFailed failed")
        hard("no preamble dropped as unparseable", preambleDropped == 0, "$preambleDropped dropped")
        hard(
            "injected regions type-check", injectedErrors == 0,
            "$injectedErrors errors in ${worstBoxes.size} boxes" +
                worstBoxes.sortedByDescending { it.second }.firstOrNull()?.let { " (worst: ${it.first} — ${it.third})" }.orEmpty(),
        )
        if (!skipRecall) {
            hard("the sweep actually ran", requests >= MIN_REQUESTS, "$requests requests (floor $MIN_REQUESTS)")
            for (f in FLOORS) {
                val n = if (f.bucket == "TOTAL") attempts.counts.values.sum() else attempts.counts[f.bucket] ?: 0
                val h = if (f.bucket == "TOTAL") recall.counts.values.sum() else recall.counts[f.bucket] ?: 0
                val pct = if (n > 0) 100.0 * h / n else 0.0
                // A recall percentage over a bucket nobody sampled is 0/0 — vacuously fine, or vacuously
                // broken, and either way not a measurement. So the sample size is a floor too: a change
                // that stops PRODUCING `dot` targets must fail here rather than quietly stop asking.
                if (n < f.minAttempts) {
                    broken += f.bucket
                    println("  ✗ RATCHET FAIL ${f.bucket} sampled only $n carets (floor ${f.minAttempts}, " +
                        "${f.observed} when this floor was cut) — the sweep stopped asking, so its recall means nothing")
                    continue
                }
                if (pct + 1e-9 < f.minPercent) {
                    broken += f.bucket
                    val notes = (if (f.bucket == "TOTAL") missNotes else notesByBucket[f.bucket])
                        ?.top(3)?.joinToString("; ") { "${it.value}× \"${it.key}\"" } ?: "no notes recorded"
                    println("  ✗ RATCHET FAIL ${f.bucket} recall fell from ${f.observed}% to %.0f%%".format(pct) +
                        " (floor ${f.minPercent}%, $h/$n); the sidecar's own note on those misses: $notes")
                } else {
                    held += f.bucket
                    println("  ✓ ${f.bucket.padEnd(16)} %3.0f%% ≥ ${f.minPercent}%%  n=$n  (cut from ${f.observed}%%)".format(pct))
                }
            }
        } else println("  (recall floors skipped: AFIELD_LOWERING_SKIP_RECALL=1)")

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

        println()
        if (broken.isEmpty()) {
            println("RATCHET PASS: ${held.size} floors held")
        } else if (gate) {
            println("RATCHET FAIL: ${broken.size} of ${broken.size + held.size} floors broken — ${broken.joinToString(", ")}")
            println("  (each ✗ above names what it fell from and the sidecar's own note on the misses;")
            println("   a floor is raised when a fix moves its number, never lowered to make a run green)")
            println("LOWERING PROBE DONE")
            System.out.flush()
            kotlin.system.exitProcess(1)
        } else {
            println("RATCHET (report-only): ${broken.size} of ${broken.size + held.size} floors broken — " +
                broken.joinToString(", ") + "  [--report given, so exiting 0]")
        }

        println("LOWERING PROBE DONE")
    }
}
