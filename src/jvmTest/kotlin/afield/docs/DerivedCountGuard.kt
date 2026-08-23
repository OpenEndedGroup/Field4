package afield.docs

import java.io.File

/**
 * Pinning a **number written in prose** to something computed from the code.
 *
 * ## Why this exists
 *
 * [CitationGuard] proves that an anchor still exists at a cited site. It says nothing about the
 * sentence wrapped around that anchor, and `dev_docs/citation_contracts.md` says so in as many words
 * under *"What none of this checks — that the prose is true"*. On 2026-08-22 that gap produced a
 * clean example. `dev_docs/DATAFRAME_PLUGIN.md` argued the blast radius of a change to
 * `ValuePrinterRegistry` from *"in this repository there are exactly four registrations, all of them
 * DataFrame's"*. There are 54 in compiled source. The sentence carried a citation that resolved
 * perfectly, on a page that passed `citationGates` 307 of 307, and the arithmetic beside the citation
 * was wrong by an order of magnitude.
 *
 * The conclusion happened to survive — but for a reason the page never gave, which means the page was
 * right by luck. That is the failure class this guards: **a derived number, hand-kept.**
 *
 * ## The prior art this generalises
 *
 * `SmallLanguageAnatomyTest` has done exactly this for one page since 2026-08-12, five times over, as
 * five hand-written `assertTrue("**$n** registered main languages" in page)` calls. That works, and it
 * does not scale: the phrase lives in a Kotlin string, the page cannot say which of its numbers are
 * checked, and nothing counts the ones that are not. This is the same idea with the phrase moved into
 * the page, where the author can see it.
 *
 * ## The marker
 *
 * A numeral is claimed by an HTML comment sitting immediately after it:
 *
 * ```markdown
 * … the tree carries **50** class-keyed registrations …
 * ```
 *
 * (with `<!--count:valuePrinterClassKeys-->` between the `50` and the space — written out in
 * `dev_docs/citation_contracts.md`, and not repeated literally here, because a marker inside this
 * KDoc is a marker inside a `.kt` file and this guard only reads Markdown.)
 *
 * Three properties, each chosen against a specific alternative:
 *
 * - **Invisible when rendered.** An HTML comment disappears in every Markdown renderer, so the
 *   published sentence reads exactly as it did. The alternative — a visible sigil like `[^count]` —
 *   taxes the prose for the guard's benefit, and this repo already accepts light *invisible*
 *   provenance markup rather than visible bookkeeping.
 * - **Adjacent, not line-scoped.** The marker must abut its numeral: only `*`, `` ` ``, `_` and at
 *   most one space may sit between. A "nearest numeral on the line" rule is ambiguous the first time
 *   a sentence carries two numbers, and this repo's sentences carry two numbers constantly.
 * - **Never a line number.** Markers inside inline code spans and fenced blocks are ignored outright,
 *   so `` `Foo.kt:412` `` can never be pinned. That is deliberate and load-bearing:
 *   [CitationContractTest]'s KDoc refuses to assert advisory line numbers because 36% of commits
 *   touching cited files insert more than the old ceiling, and a mechanism that let a doc pin a line
 *   number would rebuild that merge hazard under a new name. Every derivable number this accepts is a
 *   *population size*, which no unrelated insertion can move.
 *
 * Word numerals are accepted alongside digits ([WORD_NUMERALS]) because the sentence that motivated
 * all of this said "four", and a mechanism that forces prose to write "4" to be checkable has made
 * the prose worse in order to check it.
 *
 * ## The census
 *
 * A guard over three numerals on a page of forty is not a guarded page, it is a guarded sentence. So
 * [census] counts the numerals that carry **no** marker, per page, in the spirit of
 * [CitationContractTest.unguardedCitationsAreCountedNotIgnored]: the size of the unchecked remainder
 * is a number in the build log rather than a thing nobody knows. It is a measurement and is labelled
 * as one; the assertion is on the *covered* population, which is what stops the mechanism going
 * vacuous.
 */
object DerivedCountGuard {

    /**
     * Number words a marked numeral may be spelled with, lower-cased.
     *
     * Deliberately small — nought to twenty and the tens, which is the range English prose actually
     * spells out. Anything larger reads as a digit anyway ("fifty registrations" is a style nobody
     * writes in a technical note), and a long table here would only be a place for a typo to hide.
     * An unrecognised word is a *failure* naming this list, never a silent skip.
     */
    val WORD_NUMERALS: Map<String, Int> = buildMap {
        listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty",
        ).forEachIndexed { i, w -> put(w, i) }
        put("thirty", 30); put("forty", 40); put("fifty", 50); put("sixty", 60)
        put("seventy", 70); put("eighty", 80); put("ninety", 90); put("hundred", 100)
        // British spelling of zero, which turns up in prose about counts more often than "zero".
        put("nought", 0)
    }

    /** `<!--count:key-->`, wherever it appears. Keys are identifiers so a typo is a parse failure. */
    private val MARKER = Regex("""<!--\s*count:([A-Za-z][A-Za-z0-9_]*)\s*-->""")

    /**
     * A marker with a numeral properly abutting it.
     *
     * The `[*`_]{0,3}` allows the numeral's own closing emphasis (`**50**`) and the optional single
     * space allows `50 <!--…-->` for an author who finds the tight form unreadable. More slack than
     * that and the rule stops being "adjacent".
     *
     * The word alternative is built from [WORD_NUMERALS] rather than being `[A-Za-z]+`, and the
     * difference matters: with `[A-Za-z]+`, a marker written after the *noun* —
     * `50 registrations<!--count:k-->` — would parse as a numeral literal of "registrations" and be
     * reported as a value mismatch. It is not a value mismatch, it is a misplaced marker, and
     * [Dangling] exists to say so. Longest-first so `nineteen` is not eaten as `nine`, and `\b` in
     * front so `twentyfour` cannot match on its tail.
     */
    private val ATTACHED: Regex by lazy {
        val words = WORD_NUMERALS.keys.sortedByDescending { it.length }.joinToString("|")
        Regex(
            """\b((?:$words)-(?:$words)|[0-9][0-9,_]*|$words)([*`_]{0,3})[ ]?""" +
                """<!--\s*count:([A-Za-z][A-Za-z0-9_]*)\s*-->""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** A numeral in running prose: digits, with optional grouping and an optional `%` or ordinal. */
    private val NUMERAL = Regex("""\b[0-9][0-9,_]*(?:\.[0-9]+)?(?:%|st|nd|rd|th)?""")

    /** ISO dates, which this corpus writes constantly ("Measured 2026-08-17") and never derives. */
    private val ISO_DATE = Regex("""\b\d{4}-\d{2}-\d{2}\b""")

    /** A bare year in the 20th/21st century, for the same reason. */
    private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")

    /** A markdown link target — `](path/to.md#frag)` — whose digits are addresses, not counts. */
    private val LINK_TARGET = Regex("""]\([^)]*\)""")

    /** An ordered-list marker at the head of a line: numbering, not a claim. */
    private val LIST_MARKER = Regex("""^\s*\d+[.)]\s""")

    /** A heading's explicit anchor, `{ #some-id-2 }`, which zensical pages carry. */
    private val HEADING_ANCHOR = Regex("""\{\s*#[^}]*}""")

    /** One numeral on a page that carries a marker, with the key it claims. */
    data class Marked(
        val page: String,
        val pageLine: Int,
        val key: String,
        /** Exactly as written — `50`, `**four**`'s `four`, `5,000`. */
        val literal: String,
        /** Parsed value, or null when [literal] is a word this does not know. */
        val value: Int?,
    ) {
        fun describe(): String = "$page:$pageLine `$literal` <!--count:$key-->"
    }

    /**
     * A marker that claims nothing, because no numeral abuts it.
     *
     * Reported rather than ignored: a marker written after the wrong token is the single most likely
     * authoring mistake, and the failure mode if it were ignored is a numeral silently unguarded on a
     * page that looks guarded.
     */
    data class Dangling(val page: String, val pageLine: Int, val key: String, val text: String)

    /** A numeral with no marker — the unchecked remainder, counted and printed, never asserted. */
    data class Unmarked(val page: String, val pageLine: Int, val literal: String, val context: String)

    /** Everything [scan] found on one page. */
    data class PageScan(
        val page: String,
        val marked: List<Marked>,
        val dangling: List<Dangling>,
        val unmarked: List<Unmarked>,
    )

    /**
     * Blank out fenced code blocks and inline code spans, preserving line and column positions.
     *
     * Position-preserving on purpose: the marker rule is about adjacency, so a stripper that changed
     * offsets would make "the numeral immediately before this marker" mean something different in a
     * line containing a code span. Replacing with spaces keeps every index where it was.
     *
     * Fences are recognised on ``` and ~~~ at the start of a (possibly indented) line, which is what
     * this corpus uses. A fence marker line is itself blanked, so an info string like ```kotlin
     * cannot contribute a numeral either.
     */
    fun stripCode(text: String): List<String> {
        var inFence = false
        return text.lines().map { line ->
            val fence = Regex("""^\s*(```|~~~)""").containsMatchIn(line)
            if (fence) {
                inFence = !inFence
                return@map " ".repeat(line.length)
            }
            if (inFence) return@map " ".repeat(line.length)
            blankInlineCode(line)
        }
    }

    /**
     * Blank the inside *and* the backticks of every inline code span on one line.
     *
     * Handles the multi-backtick form (`` ` `` inside `` `` ` `` ``) the way CommonMark does: a run of
     * n backticks closes on the next run of exactly n. An unclosed run is left alone rather than
     * swallowing the rest of the line, because a lone backtick in prose is a typo, not a code span,
     * and eating the remainder of the sentence would hide numerals from the census.
     */
    private fun blankInlineCode(line: String): String {
        val out = line.toCharArray()
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') { i++; continue }
            var run = 0
            while (i + run < line.length && line[i + run] == '`') run++
            val fence = "`".repeat(run)
            // Search for a closing run of exactly the same length.
            var j = i + run
            var close = -1
            while (j < line.length) {
                if (line[j] == '`') {
                    var r = 0
                    while (j + r < line.length && line[j + r] == '`') r++
                    if (r == run) { close = j; break }
                    j += r
                } else j++
            }
            if (close < 0) { i += run; continue }
            for (k in i until close + fence.length) out[k] = ' '
            i = close + run
        }
        return String(out)
    }

    /** Every marker, dangling marker and unmarked numeral on [pageFile]. */
    fun scan(pageFile: File, pageName: String = pageFile.name): PageScan {
        val lines = stripCode(pageFile.readText())
        val marked = mutableListOf<Marked>()
        val dangling = mutableListOf<Dangling>()
        val unmarked = mutableListOf<Unmarked>()

        lines.forEachIndexed { idx, rawLine ->
            val n = idx + 1
            val attachedEnds = mutableSetOf<Int>()
            val claimedRanges = mutableListOf<IntRange>()

            ATTACHED.findAll(rawLine).forEach { m ->
                attachedEnds += m.range.last
                claimedRanges += m.groups[1]!!.range
                val literal = m.groupValues[1]
                marked += Marked(pageName, n, m.groupValues[3], literal, parseNumeral(literal))
            }
            MARKER.findAll(rawLine).forEach { m ->
                if (m.range.last !in attachedEnds) {
                    dangling += Dangling(pageName, n, m.groupValues[1], rawLine.trim())
                }
            }

            // The census runs on a further-reduced line: markers, dates, link targets and heading
            // anchors removed, table rows dropped entirely. Each exclusion is a category whose digits
            // are addresses or labels rather than claims about the code.
            if (rawLine.trimStart().startsWith("|")) return@forEachIndexed
            var census = rawLine
            listOf(MARKER, ISO_DATE, LINK_TARGET, HEADING_ANCHOR, LIST_MARKER, YEAR).forEach { rx ->
                census = rx.replace(census) { " ".repeat(it.value.length) }
            }
            claimedRanges.forEach { r ->
                census = census.replaceRange(r, " ".repeat(r.last - r.first + 1))
            }
            NUMERAL.findAll(census).forEach { m ->
                unmarked += Unmarked(pageName, n, m.value, rawLine.trim().take(110))
            }
        }
        return PageScan(pageName, marked, dangling, unmarked)
    }

    /**
     * `50` / `5,000` / `four` / `thirty-two` → 50 / 5000 / 4 / 32; null for anything else.
     *
     * The hyphenated form is composed rather than tabulated. `dev_docs/autodiff.md` — the page whose
     * op count drifted twice, in opposite directions, and which says in as many words that nothing
     * generates it — spells its count "thirty-two", and eighty table entries so that one sentence
     * could keep its house style would have been a worse trade than eight lines of arithmetic.
     */
    fun parseNumeral(literal: String): Int? {
        val cleaned = literal.replace(",", "").replace("_", "")
        cleaned.toIntOrNull()?.let { return it }
        val lower = cleaned.lowercase()
        WORD_NUMERALS[lower]?.let { return it }
        if ('-' !in lower) return null
        val (tens, units) = lower.split('-', limit = 2)
        val t = WORD_NUMERALS[tens] ?: return null
        val u = WORD_NUMERALS[units] ?: return null
        // Only `tens-unit` composes: "twenty-one" is 21, "one-twenty" and "twenty-thirty" are not
        // numbers anybody writes and must not silently become 21 and 50.
        if (t < 20 || t % 10 != 0 || u !in 1..9) return null
        return t + u
    }

    /**
     * The markdown section containing [line]: the nearest heading at or above it, down to the next
     * heading of the same or higher level.
     *
     * Section scoping is what makes [codeSpanWords] mean anything. Measured 2026-08-23: with the
     * whole page in scope, deleting `dev_docs/autodiff.md`'s entire *angle family* table row left the
     * members check **green**, because `atan`, `atan2` and `hypot` also appear in the reverse-mode op
     * vocabulary sixty lines higher up, in a paragraph about a different vocabulary entirely. A check
     * that a real deletion cannot fail is not a check. The list has to be *next to* the count.
     */
    fun sectionBounds(pageFile: File, line: Int): IntRange {
        val lines = pageFile.readLines()
        val heading = Regex("""^(#{1,6})\s""")
        var start = 0
        var level = 1
        for (i in (line - 1).coerceIn(0, lines.size - 1) downTo 0) {
            val m = heading.find(lines[i]) ?: continue
            start = i
            level = m.groupValues[1].length
            break
        }
        var end = lines.size - 1
        for (i in start + 1 until lines.size) {
            val m = heading.find(lines[i]) ?: continue
            if (m.groupValues[1].length <= level) { end = i - 1; break }
        }
        return start..end
    }

    /**
     * Every whole word that appears inside a code span or fenced block on [pageFile], optionally
     * restricted to the 0-based line range [within].
     *
     * This is how a derivation asks *"is every member of my population actually named beside the
     * number?"* — the check `SmallLanguageAnatomyTest` writes by hand as `` "`$it`" in page ``.
     * Restricted to code because the alternative is unusable: the ops on `dev_docs/autodiff.md` are
     * called `add`, `over`, `abs` and `shift`, and a whole-page word search would find every one of
     * them in ordinary English prose and report a table with rows missing as complete.
     *
     * It remains the weakest check in the set, and honestly so: within its section, a name in *any*
     * code span counts as named — a citation anchor would do. It catches the case that matters (an op
     * exists in the code and appears nowhere near the count that claims to cover it).
     */
    fun codeSpanWords(pageFile: File, within: IntRange? = null): Set<String> {
        val original = pageFile.readLines()
        val stripped = stripCode(pageFile.readText())
        val range = within ?: original.indices
        val code = buildString {
            for (i in range) {
                if (i !in original.indices) continue
                val o = original[i]
                val b = stripped.getOrElse(i) { o }
                // Whatever stripCode blanked is exactly the code; recover it by difference, character
                // by character, which is why stripCode is position-preserving.
                o.forEachIndexed { j, c -> append(if (b.getOrNull(j) == c) ' ' else c) }
                append(' ')
            }
        }
        return Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(code).map { it.value }.toSet()
    }

    // ---------------------------------------------------------------------------------------------
    // Derivations
    // ---------------------------------------------------------------------------------------------

    /**
     * A number a page is allowed to state, and the population it must equal the size of.
     *
     * The population is a **set of strings**, not an `Int`, and that is the whole design. An `Int`
     * derivation that silently stops matching after a refactor presents as a green tick over zero,
     * which this repo has now rediscovered often enough to have a name for. A set can be checked
     * three further ways at no cost: against [floor], against [landmarks], and — where two derivations
     * partition one population — against each other. `ServerSurfaceLedgerTest` reached the same
     * conclusion for tables; this is that conclusion for sentences.
     *
     * @param key the identifier a marker names.
     * @param page the guarded page that must state it. One page, deliberately: a number stated on two
     *   pages should be derived twice under two keys, so that deleting one page's sentence is still a
     *   failure rather than being covered by the other's.
     * @param what a noun phrase for the failure message — "class-keyed value-printer registrations".
     * @param floor the smallest size that is not evidence of a broken scrape.
     * @param landmarks members that must be present. A shrunken population *with* its landmarks is a
     *   deletion and wants a doc edit; a shrunken population *without* them is a broken derivation and
     *   wants a code fix. The messages have to say which, or a red build teaches nothing.
     * @param derive repo root → the population.
     * @param membersNamedOnPage when true, every member must also appear inside a code span on
     *   [page] ([codeSpanWords]). This is the *"and the table beside the number lists them"* half,
     *   and it exists because `dev_docs/autodiff.md` records the count and the table drifting apart
     *   **twice, in opposite directions**: a count-only guard would have caught neither.
     */
    data class Derivation(
        val key: String,
        val page: String,
        val what: String,
        val floor: Int,
        val landmarks: Set<String>,
        val derive: (File) -> Set<String>,
        val membersNamedOnPage: Boolean = false,
    )

    /** Kotlin sources under [root], excluding foreign checkouts, for a derivation that scrapes code. */
    fun kotlinSourcesUnder(root: File, vararg relative: String): List<File> =
        relative.flatMap { rel ->
            File(root, rel).walkTopDown()
                .onEnter { !CitationGuard.isForeignCheckout(root, it) && it.name !in setOf("build", ".git") }
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }.sortedBy { it.path }
}
