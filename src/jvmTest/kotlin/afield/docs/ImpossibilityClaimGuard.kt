package afield.docs

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Making a documented **impossibility claim** carry its evidence, the way a documented number already
 * carries its derivation.
 *
 * ## Why this exists — the six weeks nobody could detect
 *
 * `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md` carried a section headed *"Known limitation: compute + draw in
 * ONE box"*, and it was written the way this repo writes its best work: a reproduction, a causal chain
 * naming three source files, a ruled-out alternative, and a named fix. It was also **false on the day it
 * was written**, 2026-07-11 (`36e61332d`), in both of the facts the chain rested on:
 *
 * - It said *"`executeAll` does **not** reset the `_preamble` cache, whereas `launchAll` does"*. The
 *   `execute-all` handler has cleared it since `52a1d563e`, 2025-11-03 — the first commit of this build
 *   layout, eight months earlier. See `executing.kt#remove record of previous preamble line execs`.
 * - It said the engine in use is *"the **async engine** … (`box.ki`, `Boxes.kt` `makeKI`)"*, whose defect
 *   is that it *"simply **never executes the box preamble at startup**"*. The same handler has built the
 *   **synchronous** engine since `8b5ea3a356a`, 2026-05-26 — six weeks earlier. See
 *   `executing.kt#makeKIHere(box, reason = "execute-all handler reset")`.
 *
 * And the real cause was sitting in the paragraph that dismissed it: *"A red herring we ruled out … it
 * does not fix the null-cast — tested and disproven."* What had been tested was the neighbouring
 * `var Structure.<name>` line, not the generated `class <Block> : Structure`.
 *
 * Net effect: a feature was documented as impossible for six weeks, with a citation-bearing
 * explanation, and **nothing in this repo could tell a diagnosis that was measured from one that was
 * reasoned.** `CitationGuard` would have been content: every file named really existed. A citation
 * proves a line is *there*; this whole chain was an assertion that something was *absent*.
 *
 * That is not a one-off. The same disease is on the record at least three more times: a ledger row
 * calling a `suspend` type *"unsatisfiable by a dynamic proxy"* for eleven days (it was a `Function2`
 * taking a `Continuation`); a to-do read as a finding, twice; and a claim that Plotly could not serve a
 * headless docs capture, which five shipped pages already disproved — retracted in
 * `dev_docs/chart_primitive.md`, which is worth reading as the shape of an honest correction.
 *
 * ## The convention
 *
 * An impossibility claim must carry, **inside its own claim unit** ([unitOf]), one of four markers:
 *
 * | Marker | Written as | Means |
 * |---|---|---|
 * | [Marker.REPRODUCER] | `` (reproducer: `Path.kt#anchor`) `` | a test or runnable example that *demonstrates the refusal* |
 * | [Marker.CITATION] | `` `Path.kt:120#anchor` `` | the code that makes it so — an ordinary [CitationGuard] citation |
 * | [Marker.CONJECTURE] | `(conjecture, 2026-08-25)` | reasoned, not measured, and honest about it |
 * | [Marker.NOT_A_LIMITATION] | `(not a limitation: why)` | the detector misfired; say why in the doc, not in the guard |
 *
 * Three of the four are syntax this repo already uses, deliberately: the reproducer's payload *is* a
 * `CitationGuard` symbol citation, so it inherits path resolution, anchor resolution, ambiguity
 * reporting and the nested-checkout rule for free, and the `(conjecture, YYYY-MM-DD)` spelling is the
 * one `dev_docs/DATAFRAME_PLUGIN.md` already used — the only two uses in the corpus, previously checked
 * by nothing.
 *
 * ## The one rule that is not symmetric: a citation cannot substantiate an absence
 *
 * A [Kind.RULED_OUT] claim — *"we ruled that out"*, *"a red herring"*, *"tested and disproven"* — is a
 * **measurement**, and it asserts a negative: that some mechanism is *not* the cause. Pointing at code
 * cannot establish that. Pointing at code is exactly what the six-week page did, and the code it
 * pointed at said the opposite of what the prose claimed.
 *
 * So [Kind.RULED_OUT] does **not** accept [Marker.CITATION]. It takes a reproducer, or it admits it is
 * a conjecture. That single asymmetry is the part of this guard that would have caught the original.
 *
 * ## Detection, and the honest limit of the idea
 *
 * The general form does not work, and it is worth saying so precisely rather than shipping a guard that
 * has to be suppressed everywhere. Measured on this corpus, 2026-08-25:
 *
 * - The obvious regex — `cannot|is impossible|no way to|refuses to|…` — matches **53 lines in 30 of the
 *   114 `*.md` pages under `dev_docs`**. Hand-classified, roughly **23 of those 53 are not claims at all**: `refuses
 *   to` is almost always *designed* behaviour ("the model smoke **refuses** to run unless…"), `cannot`
 *   turns up in relative clauses ("a class file that cannot be read"), in test guarantees ("so an op
 *   cannot be added without a check"), in git facts ("a commit message, which cannot be amended") and
 *   inside quoted error text.
 * - Loosening `cannot be <verb>` to a bare `cannot <verb>` takes it to **208 lines in 54 pages**. That
 *   is the collapse: at that width the guard is a tax on writing prose.
 * - And the obvious regex *misses the three lines that mattered most* — all of
 *   `DOCS_RUNNER_GPU_CAPTURES.md`'s ruled-out sentences, because "ruled out" is not an impossibility
 *   idiom. The most expensive claim in the corpus was invisible to the search that was meant to find it.
 *
 * What *is* separable is narrower and sharper, and it follows how this repo actually writes a finding:
 *
 * 1. **A strong idiom, anywhere.** `is impossible`, `there is no way to`, `nothing can be`,
 *    `no supported way` — phrases that have no innocent reading. Plus every [RULED_OUT] idiom.
 * 2. **A weak idiom inside emphasis** — a Markdown heading, a `**bold**` span, or a table row bearing
 *    ❌. `cannot` in running prose is noise; `- **A `suspend` *member* cannot be called**` is a verdict,
 *    and the emphasis is the author telling you so.
 *
 * That lands on **29 claims in 19 pages**, of which one hand-audited misfire family remains (see
 * [LIMITATION_SECTION_LABEL]) — good enough to enforce corpus-wide with a real escape hatch, which is
 * the bargain [Marker.NOT_A_LIMITATION] represents.
 *
 * Two idioms were tried and **dropped for measured imprecision**, which is the sort of thing worth
 * recording so it is not re-litigated: `never` in emphasis (23 hits, overwhelmingly *prescriptions* —
 * "**never fake a capture**", "a frame must **never** set its own scale") and `refuses` in emphasis
 * (6 hits, all of them designed refusals presented as features).
 *
 * See `dev_docs/impossibility_claims.md`.
 */
object ImpossibilityClaimGuard {

    /** What sort of absolute statement a line is making. */
    enum class Kind {
        /** An absolute limit asserted about the system: *this cannot be done*. */
        VERDICT,

        /**
         * A cause asserted **eliminated**: *we ruled that out*. Held to a higher bar than [VERDICT],
         * because it is a measurement of an absence and a citation cannot establish one.
         */
        RULED_OUT,
    }

    /** The four things a claim may carry. Ordered weakest-obligation last for readable reporting. */
    enum class Marker { REPRODUCER, CITATION, CONJECTURE, NOT_A_LIMITATION }

    // ---------------------------------------------------------------------------------------------
    // Detection
    // ---------------------------------------------------------------------------------------------

    /** Idioms with no innocent reading, so they count wherever they appear in prose. */
    val STRONG: List<Regex> = listOf(
        """\bis impossible\b""",
        """\bare impossible\b""",
        """\bis not possible\b""",
        """\bthere is (?:currently |simply |still |presently )?no way to\b""",
        """\bthere are (?:currently |simply |still )?no ways? to\b""",
        """\bno supported way\b""",
        """\bnothing can be\b""",
        """\bis unachievable\b""",
        """\bis not achievable\b""",
    ).map { Regex(it) }

    /**
     * Idioms that assert a cause has been eliminated. These are the ones the obvious impossibility
     * regex misses entirely, and they are the ones that cost six weeks.
     */
    val RULED_OUT: List<Regex> = listOf(
        """\bruled (?:that |it |them )?out\b""",
        """\bred herring\b""",
        """\btested and disproven\b""",
        """\bdisproven\b""",
        """\bis not the cause\b""",
        """\bwas not the cause\b""",
        """\bnot sufficient on its own\b""",
        """\beliminated as a cause\b""",
    ).map { Regex(it) }

    /**
     * Idioms that only count **inside emphasis** — a heading, a `**bold**` span, or a ❌ table row.
     * In running prose these are ordinary words; under emphasis they are the author's own signal that
     * a verdict is being delivered.
     */
    val WEAK: List<Regex> = listOf(
        """\bcannot\b""",
        """\bcan(?:'|’)t\b""",
        """\bno way\b""",
        """\bimpossible\b""",
        """\bnot supported\b""",
        """\bout of reach\b""",
        """\bnot possible\b""",
        """\bunreachable\b""",
    ).map { Regex(it) }

    /**
     * Constructions that look like a claim and are not, excluded before anything else runs.
     *
     * Every entry is here because a real line in this corpus needed it, and they are all the same
     * mistake: reading a page's **before-picture** as its verdict. `dev_docs/fline_fill_orientation.md`
     * writes history as *"*Was: two implementations, two conventions, and hole support impossible in one
     * of them. Unified…*"* — the sentence exists to say the problem is over.
     */
    val HISTORICAL: List<Regex> = listOf(
        """\bwas no way\b""",
        """\bwere no way\b""",
        """\bhad no way\b""",
        """\bthere was no\b""",
        """\bthere were no\b""",
        """\bno longer\b""",
        """\bused to\b""",
        """^\s*\*?was:""",
        """\bwas: """,
    ).map { Regex(it) }

    /**
     * A heading that merely *announces* a limitations section — `### What it cannot do` — rather than
     * stating a limit. Six of these exist (`CERES.md`, `PATTERN_LANGUAGE.md`, `kdoc_reference_contract.md`,
     * `server_surface_inventory.md`, `field_audio_seam_stub.md`, plus one in `public/docs`), and marking
     * a signpost is busywork: the claims are the bullets underneath, and those are detected on their own.
     *
     * Deliberately narrow. `## Redirects cannot walk the proxy off the docs site` does **not** match,
     * and should not: that heading asserts something.
     */
    val LIMITATION_SECTION_LABEL: Regex =
        Regex("""\bwhat\b[^.]*\b(?:cannot|can(?:'|’)t|is impossible|out of reach|unreachable|not supported)\b""")

    private val FENCE = Regex("""^\s*(```|~~~)""")
    private val BLOCKQUOTE = Regex("""^\s*>""")
    private val HTML_COMMENT = Regex("""^\s*<!--""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val CODE_SPAN = Regex("""`[^`]*`""")

    /**
     * HTML tags, stripped before matching.
     *
     * Not defensive coding: `public/docs/reference/polytopes.md` renders signatures as
     * `<code class="highlight"><span class="k">val</span>…`, and this repo's standing lesson is that
     * grepping a generated artifact for a source-shaped string fails **silently** because highlighting
     * splits identifiers across `<span>`s. The same hazard runs the other way — tag *attributes* are
     * prose-shaped and match prose patterns.
     */
    private val HTML_TAG = Regex("""<[^>]+>""")

    /**
     * Prose with code spans and HTML removed, so that an identifier like `unsupportedOps` or
     * `@KaK1Unsupported` cannot be read as a sentence. Replaced with a placeholder rather than deleted,
     * so a stripped span cannot fuse two words into a spurious phrase.
     */
    fun scrub(line: String): String =
        CODE_SPAN.replace(HTML_TAG.replace(line, " "), " @ ")

    /** The spans of [line] in which a [WEAK] idiom counts as a verdict. */
    fun emphasisedRegions(line: String): List<String> = buildList {
        HEADING.find(line)?.let { add(it.groupValues[1]) }
        BOLD.findAll(line).forEach { add(it.groupValues[1]) }
        if ("❌" in line) add(line)
    }

    /**
     * One detected claim.
     *
     * @param page repo-relative path of the page.
     * @param line 1-based line the idiom was found on.
     * @param unit the claim unit a marker must appear in — see [unitOf].
     * @param idiom the pattern that fired, so a failure explains *why* the line was picked up.
     */
    data class Claim(
        val page: String,
        val line: Int,
        val kind: Kind,
        val idiom: String,
        val text: String,
        val unit: IntRange,
    ) {
        fun describe(): String = "$page:$line [$kind via $idiom]"
    }

    /**
     * The block a marker has to live in: the claim's own paragraph, bullet, or table row.
     *
     * Paragraph scope rather than line scope because dev_docs prose wraps at about a hundred columns, so
     * a sentence is routinely two or three lines and a same-line rule would force markers into the
     * middle of sentences. Bullets and table rows break the run anyway, so a ten-bullet list of
     * limitations needs ten markers rather than one — which is the point. Each limit is a separate
     * claim with separate evidence, and the six-week section is exactly a case where one blanket
     * "verified" covered four independent assertions, three of which were wrong.
     */
    fun unitOf(lines: List<String>, at: Int): IntRange {
        fun startsUnit(l: String) =
            HEADING.matches(l) || Regex("""^\s*(?:[-*+]|\d+\.)\s""").containsMatchIn(l) ||
                l.trimStart().startsWith("|")
        var s = at
        while (s > 0 && !startsUnit(lines[s]) && lines[s - 1].isNotBlank()) s--
        var e = at
        while (e + 1 < lines.size && lines[e + 1].isNotBlank() && !startsUnit(lines[e + 1])) e++

        // A heading is a one-line block, and demanding a marker *inside* the heading text would put
        // citations in the table of contents. A heading's evidence lives in the paragraph it
        // introduces, so extend through the following block — one blank line, then the next run of
        // non-blank lines. `## Redirects cannot walk the proxy off the docs site` is the real case:
        // the sentence that substantiates it is the paragraph directly beneath.
        if (HEADING.matches(lines[at])) {
            var n = e + 1
            while (n < lines.size && lines[n].isBlank()) n++
            if (n < lines.size && !HEADING.matches(lines[n])) {
                while (n + 1 < lines.size && lines[n + 1].isNotBlank() && !HEADING.matches(lines[n + 1])) n++
                return s..n
            }
        }
        return s..e
    }

    /** Every claim on [page], in page order. [name] is the repo-relative path used in messages. */
    fun claims(page: File, name: String): List<Claim> {
        val lines = page.readText().split("\n")
        val out = mutableListOf<Claim>()
        var inFence = false
        lines.forEachIndexed { i, raw ->
            if (FENCE.containsMatchIn(raw)) { inFence = !inFence; return@forEachIndexed }
            if (inFence) return@forEachIndexed
            if (BLOCKQUOTE.containsMatchIn(raw)) return@forEachIndexed
            if (HTML_COMMENT.containsMatchIn(raw)) return@forEachIndexed
            val bare = scrub(raw)
            val low = bare.lowercase()
            if (HISTORICAL.any { it.containsMatchIn(low) }) return@forEachIndexed

            fun emit(kind: Kind, idiom: Regex) {
                out += Claim(name, i + 1, kind, idiom.pattern, raw.trim(), unitOf(lines, i))
            }

            RULED_OUT.firstOrNull { it.containsMatchIn(low) }?.let { return@forEachIndexed emit(Kind.RULED_OUT, it) }
            STRONG.firstOrNull { it.containsMatchIn(low) }?.let { return@forEachIndexed emit(Kind.VERDICT, it) }
            for (region in emphasisedRegions(bare)) {
                val rl = region.lowercase()
                if (HISTORICAL.any { it.containsMatchIn(rl) }) continue
                if (LIMITATION_SECTION_LABEL.containsMatchIn(rl)) continue
                val w = WEAK.firstOrNull { it.containsMatchIn(rl) } ?: continue
                emit(Kind.VERDICT, w)
                break
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Markers
    // ---------------------------------------------------------------------------------------------

    private val REPRODUCER = Regex("""\(reproducer:\s*`([^`]+)`\s*\)""")
    /**
     * `(conjecture, 2026-08-25)`, optionally with a note: `(conjecture, 2026-08-25 — not re-run)`.
     *
     * The note is allowed because the best conjecture markers in this corpus carry one — *"an absence
     * in someone else's notation grammar; nothing in this tree can be pointed at"* is worth more to a
     * reader than the date is — and because a marker that forbids explanation invites a bare date.
     */
    private val CONJECTURE = Regex("""\(conjecture,\s*(\d{4}-\d{2}-\d{2})[^)]*\)""")

    /**
     * The escape hatch, which has to carry a reason.
     *
     * A bare `(not a limitation)` would be a one-token way to switch the guard off, and the reason is
     * cheap to write and lives where the next reader is already looking. Its corpus count is
     * ceilinged by the contract test, so it cannot quietly become universal suppression.
     */
    private val NOT_A_LIMITATION = Regex("""\(not a limitation:\s*[^)]{4,}\)""")

    /** What a claim carries, and why that is or is not sufficient. */
    sealed interface Ruling {
        data class Marked(val claim: Claim, val marker: Marker, val detail: String) : Ruling
        data class Unmarked(val claim: Claim, val reason: String) : Ruling
    }

    /**
     * How to fix an [Ruling.Unmarked], written out in full.
     *
     * A guard whose message is "claim at line 41 is unmarked" makes the reader go and find the
     * convention. A sibling branch tripping this at 3am should be able to act on the failure text
     * alone, so the failure text is the documentation.
     */
    fun advice(kind: Kind): String = buildString {
        appendLine("      Add ONE of these, inside the claim's own paragraph/bullet/table row:")
        appendLine("        (reproducer: `SomeTest.kt#fun theRefusalIsObserved`)   a @Test or runnable example that demonstrates it")
        if (kind == Kind.VERDICT) {
            appendLine("        `SomeFile.kt:120#theSymbol`                            the code that makes it so")
        } else {
            appendLine("        -- a plain citation is NOT accepted for a ruled-out claim: pointing at code")
            appendLine("           cannot establish that something is absent. That is the exact hole that let")
            appendLine("           DOCS_RUNNER_GPU_CAPTURES.md carry a false diagnosis for six weeks.")
        }
        appendLine("        (conjecture, ${LocalDate.now()})                             reasoned, not measured -- and honest about it")
        appendLine("        (not a limitation: <why>)                              the detector misfired; say why")
        append("      See dev_docs/impossibility_claims.md.")
    }

    // ---------------------------------------------------------------------------------------------
    // Reproducer validity
    // ---------------------------------------------------------------------------------------------

    /**
     * True when `@Test` annotates the function declared at 1-based [declarationLine]: either on that
     * line ahead of the `fun` — `@Test fun x()`, `@Test @Timeout(5) fun x()` — or in the
     * **annotation/comment prelude** directly above it. [anchor] picks out which `fun` on the line is
     * the cited one.
     *
     * Deliberately not a fixed line window. A window of `n` lines is wrong in both directions: `@Test`
     * is often separated from its function by a multi-line KDoc, and — the failure that motivated this —
     * an ordinary helper function sitting a few lines *below* a real test inherits that test's
     * annotation and is accepted. Measured on the fixture in
     * `ImpossibilityClaimContractTest.aFakeReproducerIsRejectedAndARealOneIsAccepted`: with a 6-line
     * window, `fun notATestAtAll()` passed.
     *
     * So: walk upward through annotations, comments and KDoc only, and stop at the first line that is
     * none of those — a blank line included, because a blank line is what ends a declaration's prelude.
     * A line that *starts* with annotations but goes on to declare something, `@Test fun other() { }`,
     * is a neighbour and not a prelude: until the one-line form was accepted here, a helper directly
     * beneath a one-line test inherited its `@Test` that way.
     */
    fun hasTestAnnotation(lines: List<String>, declarationLine: Int, anchor: String = "fun "): Boolean {
        val declaration = lines[declarationLine - 1]
        val beforeFun = declaration.substring(0, declaredFunAt(declaration, anchor))
        val (sameLine, end) = preludeOf(beforeFun)
        // Code ahead of the `fun` on its own line (`fun a() { }; fun b() { }`) means the declaration
        // does not start this line, so neither that code nor the lines above are its prelude.
        if (end < beforeFun.length) return false
        if (sameLine.any(::isTestAnnotation)) return true
        var i = declarationLine - 2
        while (i >= 0) {
            val t = lines[i].trim()
            if (t.startsWith("@")) {
                val (annotations, stop) = preludeOf(t)
                val rest = t.substring(stop)
                if (rest.isNotEmpty() && !rest.startsWith("//") && !rest.startsWith("/*")) return false
                if (annotations.any(::isTestAnnotation)) return true
                i--; continue
            }
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) { i--; continue }
            return false
        }
        return false
    }

    /**
     * Where the cited `fun ` begins on [line]: the last one that starts no later than the end of
     * [anchor], so `#fun b` and a bare `#b` both land on `fun b` and not on an earlier `fun a`.
     */
    private fun declaredFunAt(line: String, anchor: String): Int {
        val at = line.indexOf(anchor)
        val found = if (at < 0) -1 else line.lastIndexOf("fun ", at + anchor.length - "fun ".length)
        return if (found >= 0) found else line.indexOf("fun ").coerceAtLeast(0)
    }

    /**
     * One annotation — `@Test`, `@Timeout(5)`, `@Tag("slow")`, `@kotlin.test.Test`, with one level of
     * nested parentheses allowed in its arguments — or one word, which [preludeOf] accepts only if it
     * is in [MODIFIERS].
     */
    private val PRELUDE_TOKEN = Regex("""@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?|\w+""")

    private val MODIFIERS = setOf(
        "public", "private", "protected", "internal", "open", "final", "abstract", "override",
        "suspend", "inline", "tailrec", "operator", "infix", "external", "expect", "actual",
    )

    /** The annotations and modifiers that [text] starts with, and the index at which they stop. */
    private fun preludeOf(text: String): Pair<List<String>, Int> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            val m = PRELUDE_TOKEN.matchAt(text, i)
            if (m == null || (!m.value.startsWith("@") && m.value !in MODIFIERS)) return tokens to i
            tokens += m.value
            i = m.range.last + 1
        }
    }

    /**
     * `@Test`, `@Test()` or a qualified `@kotlin.test.Test`, matched as the whole annotation name.
     * A prefix match would also accept `@TestOnly`, which marks code a test may *call*, not a test.
     */
    private val TEST_ANNOTATION = Regex("""@(?:[\w.]+\.)?Test""")

    private fun isTestAnnotation(token: String) = TEST_ANNOTATION.matches(token.substringBefore('('))

    /**
     * Why [spec] is not a usable reproducer, or null when it is one.
     *
     * Two things have to hold, and the second is the one that makes this more than a spell-checker:
     *
     * 1. It **resolves**, via [CitationGuard.resolve] — so the file exists, the anchor is really at the
     *    cited site, an abbreviated path is unambiguous, and a nested checkout does not silently break
     *    every reproducer at once. All of that is [CitationGuard]'s, not re-implemented here.
     * 2. It is **a test or a runnable example**, not merely a real file. A path can exist, an anchor can
     *    resolve, and the target can still be a data class in the middle of a graphics kernel — which
     *    would make "reproducer" a plausible-looking string rather than something a reader can run.
     */
    fun validateReproducer(root: File, spec: String): String? {
        val hash = spec.indexOf('#')
        if (hash < 0) return "`$spec` has no `#anchor`; write `Path.kt#fun theTest` or `Path.kt:120#fun theTest`"
        val left = spec.substring(0, hash)
        val anchor = spec.substring(hash + 1)
        if (anchor.isBlank()) return "`$spec` has an empty anchor"
        val colon = left.lastIndexOf(':')
        val path = if (colon > 0 && left.substring(colon + 1).all { it.isDigit() }) left.substring(0, colon) else left
        val advisory = if (path != left) left.substring(colon + 1).toIntOrNull() else null

        val citation = CitationGuard.Citation(
            page = "(reproducer)", pageLine = 0, path = path,
            advisoryLine = advisory, anchor = anchor, form = CitationGuard.Form.PROSE_LINE_SYMBOL,
        )
        val resolved = when (val r = CitationGuard.resolve(root, citation)) {
            is CitationGuard.Resolution.Broken -> return r.reason
            is CitationGuard.Resolution.Resolved -> r
        }
        val file = CitationGuard.locate(root, path) ?: return "cited file `$path` vanished between resolution and reading"
        val rel = file.relativeToOrSelf(root).path
        val lines = file.readLines()

        return when {
            rel.contains("/jvmTest/") && file.extension == "kt" -> {
                val declaration = lines[resolved.line - 1]
                if (!declaration.contains("fun ")) {
                    "`$spec` resolves to $rel:${resolved.line}, which is not a function declaration " +
                        "(`${declaration.trim().take(70)}`). Anchor the test function itself, e.g. `#fun myTest`."
                } else if (!hasTestAnnotation(lines, resolved.line, anchor)) {
                    "`$spec` resolves to a function at $rel:${resolved.line} with no `@Test` ahead of " +
                        "its `fun` or in the annotation prelude directly above it, so nothing runs it"
                } else null
            }

            rel.startsWith("src/jvmMain/") && file.extension == "kt" ->
                if (lines.none { it.contains("fun main(") }) {
                    "`$spec` resolves to $rel, which is under jvmMain but has no `fun main(`, " +
                        "so it is not a runnable example"
                } else null

            rel.startsWith("tools/") && file.extension == "py" ->
                if (lines.none { it.trimStart().startsWith("def ") }) {
                    "`$spec` resolves to $rel, which is under tools/ but defines nothing runnable"
                } else null

            rel.startsWith("dev_docs/agents/transcripts/") && file.extension in setOf("yaml", "yml") -> null

            else ->
                "`$spec` resolves to $rel, which is not a test, a runnable example, a tool or a " +
                    "transcript. A reproducer has to be something a reader can actually run."
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ruling
    // ---------------------------------------------------------------------------------------------

    /**
     * Judge every claim on [page].
     *
     * Citations are resolved through [CitationGuard.parse] over the whole page and then matched to
     * claim units by line, rather than re-implementing the citation regexes here. One parser, one set
     * of accepted spellings; if `CitationGuard` learns a new form, this learns it too.
     */
    fun rule(root: File, page: File, name: String): List<Ruling> {
        val lines = page.readText().split("\n")
        val citationLines = CitationGuard.parse(page, name)
            .filter { CitationGuard.resolve(root, it) is CitationGuard.Resolution.Resolved }
            .map { it.pageLine }
            .toSet()

        return claims(page, name).map { claim ->
            val body = claim.unit.mapNotNull { lines.getOrNull(it) }.joinToString("\n")

            REPRODUCER.find(body)?.let { m ->
                val spec = m.groupValues[1]
                val why = validateReproducer(root, spec)
                return@map if (why == null) {
                    Ruling.Marked(claim, Marker.REPRODUCER, spec)
                } else {
                    Ruling.Unmarked(claim, "the claimed reproducer is not usable: $why")
                }
            }
            NOT_A_LIMITATION.find(body)?.let {
                return@map Ruling.Marked(claim, Marker.NOT_A_LIMITATION, it.value)
            }
            CONJECTURE.find(body)?.let { m ->
                val date = try {
                    LocalDate.parse(m.groupValues[1])
                } catch (e: DateTimeParseException) {
                    return@map Ruling.Unmarked(claim, "`${m.value}` is not a real date")
                }
                return@map if (date.isAfter(LocalDate.now())) {
                    Ruling.Unmarked(claim, "`${m.value}` is dated in the future")
                } else {
                    Ruling.Marked(claim, Marker.CONJECTURE, date.toString())
                }
            }
            if (claim.kind == Kind.VERDICT && claim.unit.any { it + 1 in citationLines }) {
                return@map Ruling.Marked(claim, Marker.CITATION, "resolved citation in the claim unit")
            }
            val reason = if (claim.kind == Kind.RULED_OUT) {
                "a ruled-out claim is a measurement of an absence, so it needs a reproducer or an " +
                    "explicit conjecture marker; a citation is not enough"
            } else {
                "no reproducer, citation, conjecture marker or explicit waiver in the claim unit " +
                    "(lines ${claim.unit.first + 1}-${claim.unit.last + 1})"
            }
            Ruling.Unmarked(claim, reason)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Inventory
    // ---------------------------------------------------------------------------------------------

    /**
     * A countable summary. Every field is floored or ceilinged by `ImpossibilityClaimContractTest`;
     * "prints a proud number with no assertion under it" is the failure mode this repo keeps
     * rediscovering, and it is what let a nightly branch report *"scan 18 → 216 pages"* while the
     * number the guard actually pinned had gone 9 → 12.
     */
    data class Inventory(
        val claims: List<Claim>,
        val rulings: List<Ruling>,
    ) {
        val marked: List<Ruling.Marked> get() = rulings.filterIsInstance<Ruling.Marked>()
        val unmarked: List<Ruling.Unmarked> get() = rulings.filterIsInstance<Ruling.Unmarked>()
        val pages: Set<String> get() = claims.map { it.page }.toSet()
        fun byMarker(m: Marker) = marked.filter { it.marker == m }
        fun byKind(k: Kind) = claims.filter { it.kind == k }

        fun render(): String = buildString {
            appendLine("impossibility claims detected: ${claims.size} across ${pages.size} pages")
            appendLine("  verdicts:                    ${byKind(Kind.VERDICT).size}")
            appendLine("  ruled-out claims:            ${byKind(Kind.RULED_OUT).size}")
            appendLine("  marked:                      ${marked.size}")
            Marker.entries.forEach { appendLine("    ${it.name.lowercase().padEnd(27)}${byMarker(it).size}") }
            append("  UNMARKED:                    ${unmarked.size}")
        }
    }

    fun inventory(root: File, pages: List<File>): Inventory {
        val cs = pages.flatMap { claims(it, it.relativeToOrSelf(root).path) }
        val rs = pages.flatMap { rule(root, it, it.relativeToOrSelf(root).path) }
        return Inventory(cs, rs)
    }

    /** Every `*.md` under [dir], sorted, so a corpus is a directory rather than a hand-kept list. */
    fun markdownUnder(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "md" }.sortedBy { it.path }.toList()
}
