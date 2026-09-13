package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus-wide guard on **arithmetic between two published facts**.
 *
 * [CitationContractTest] proves an anchor still resolves. [ProseNumeralContractTest] proves a
 * *population size* stated in prose still matches a scrape of the code. This proves the third
 * thing, which neither of them reads: that a number a page computes **from numbers it has already
 * stated** is the number that arithmetic gives. See [DerivedArithmeticGuard] for the marker, the
 * grammar, the tolerance rule and the three defects that motivated it.
 *
 * ## The population this selects, said as a number
 *
 * Every tracked-shape `.md` file under `dev_docs/` and `public/docs/` —
 * [DerivedArithmeticGuard.CORPUS_ROOTS] — which is **641 files** measured 2026-09-13, of which 20
 * are the generated reference. Both trees are already declared `citationGates` inputs
 * (`publishedDocs` and `guardedDevDocs`), so no markdown-only edit can leave this task UP-TO-DATE,
 * and [GateInputDeclarationTest] holds that property rather than this KDoc.
 *
 * That is deliberately **wider than [ProseNumeralContractTest]'s 34 pages**, and the reason is
 * structural rather than ambition. A `count:` marker names a key that must exist in a Kotlin-side
 * registry, so that class can only scan pages it has a registry entry for. A `derive:` marker
 * carries its own arithmetic, so there is nothing to register and no reason to restrict the corpus.
 * The population is stated here because this repo has now recorded four guards that selected less
 * than their author believed — a `_GUARDED_PAGE` regex matching only `dev_docs/` that left all ten
 * `public/docs` pages invisible, and a `_reference_input` predicate covering 2 of 16 source roots —
 * and in every case the direction of the error was "less".
 *
 * ## How this refuses to pass vacuously
 *
 * 1. **Its own premise is asserted first.** [theCorpusThisScansIsTheOneItClaims] fails if the
 *    corpus collapses, so a moved directory or a broken walk fails loudly instead of turning every
 *    assertion below into a green tick over zero files.
 * 2. **The teeth are shown to bite.** [theAgreementCheckFailsOnAWrongDerivation] replays the real
 *    2026-09-12 reciprocal and asserts the check reports it. Last night's judge caught this
 *    package citing an assertion as a guard's teeth when an earlier `strict=True` made the call
 *    raise before that line could ever run; naming an assertion as the teeth now means
 *    demonstrating it can fail.
 * 3. **Floors, not pins.** [MIN_MARKERS] and [MIN_MARKED_PAGES] are floors under the measurement,
 *    because a sibling branch merging changes the corpus underneath this class and four times in
 *    seven nights a published count broke on merge because somebody wrote `assertEquals`.
 * 4. **A census of the remainder.** [theMarkedPopulationIsFlooredAndTheRemainderIsCounted] prints
 *    how many lines in the corpus *look like* a derivation and carry no marker, so nobody can read
 *    this class as saying more than it does.
 */
class DerivedArithmeticContractTest {

    private companion object {

        /**
         * Floor on the markdown pages the corpus walk finds.
         *
         * Measured 2026-09-13: **641** tracked `.md` under `dev_docs/` and `public/docs/`. Floored
         * at 550 rather than pinned, because the docs corpus grows and shrinks on every branch and
         * a page added must never need a second edit here.
         *
         * It is not decoration. Without it, a renamed directory or a `walkTopDown` that stopped
         * entering would make every assertion in this class pass over an empty list — which is the
         * precise failure `dev_docs/guard_population_hygiene.md` exists to describe, and which this
         * repo has now found in four separate guards.
         */
        const val MIN_PAGES_SCANNED = 550

        /**
         * Floor on the `derive:` markers in the corpus.
         *
         * Measured 2026-09-13, the day the mechanism landed: **19** markers across 9 pages. The
         * founding nine came with the mechanism; the other ten came out of the sweep described in
         * `dev_docs/derived_arithmetic_sweep.md`, which read 65 published derivations by hand and
         * pinned the ones whose operands were all on the page.
         *
         * Floored at 14 on the [ProseNumeralContractTest.MIN_MARKED_NUMERALS] convention — two
         * below the measurement, so adding a marker never needs a second edit here and dropping
         * the founding population is a decision somebody makes on purpose.
         *
         * Ratchet it when the corpus grows. That KDoc records what happens otherwise: a floor of 6
         * under a population of 13 permitted deleting seven markers and staying green, because a
         * floor is only a floor relative to the population it floors.
         */
        const val MIN_MARKERS = 14

        /**
         * Floor on the *pages* carrying at least one marker. Measured 2026-09-13: **9**.
         *
         * The marker count alone cannot see the shape of a loss: nineteen markers on one page and
         * nineteen spread over nine pages are the same number and not the same coverage, and the
         * cheapest way to shed markers is to stop guarding a page rather than to thin a page out.
         */
        const val MIN_MARKED_PAGES = 7

        /**
         * The census predicate: a line that *looks like* it states a derived quantity.
         *
         * This is the sweep predicate that found the founding instances, kept in the source so the
         * unchecked remainder is a number in the build log rather than a thing nobody knows. It is
         * **line-scoped**, and that is its honest limit — a real derivation often spans a sentence
         * or two, as `dev_docs/profiling_stack.md`'s did, so this under-selects. It is a
         * measurement and is labelled as one; nothing is asserted about its size.
         */
        val DERIVATION_CONNECTIVE = Regex(
            """so (?:about|roughly|around)|that is (?:about|roughly|around)|works out|""" +
                """which gives|amounts to|comes to|per (?:frame|second|core|call|span|client)|""" +
                """apiece|times a second|events a second|about [0-9]|roughly [0-9]|around [0-9]""",
            RegexOption.IGNORE_CASE,
        )

        /** Two or more numerals on one line — the narrowing that took the sweep from 585 to 381. */
        val NUMERAL = Regex("""[0-9][0-9.,_]*""")
    }

    private val root = CitationGuard.repoRoot()

    private fun pages(): List<File> = DerivedArithmeticGuard.corpusPages(root)

    private fun relative(f: File): String = f.relativeTo(root).invariantSeparatorsPath

    private fun scans(): List<DerivedArithmeticGuard.PageScan> {
        val out = mutableListOf<DerivedArithmeticGuard.PageScan>()
        for (f in pages()) out.add(DerivedArithmeticGuard.scan(f, relative(f)))
        return out
    }

    private fun allMarked(): List<DerivedArithmeticGuard.Marked> {
        val out = mutableListOf<DerivedArithmeticGuard.Marked>()
        for (s in scans()) out.addAll(s.marked)
        return out
    }

    // -------------------------------------------------------------------------------------------
    // The premise, asserted before anything is built on it
    // -------------------------------------------------------------------------------------------

    /**
     * **The premise.** The corpus exists, both roots contribute, and it is the size this class
     * claims.
     *
     * Deliberately first. An environment change — a renamed tree, a walk that stops entering, a
     * repo root resolved to the wrong place — must fail here with the numbers printed, rather than
     * silently turning the four assertions below into green ticks over an empty list.
     */
    @Test
    fun theCorpusThisScansIsTheOneItClaims() {
        val found = pages()
        val dev = found.count { relative(it).startsWith("dev_docs/") }
        val published = found.count { relative(it).startsWith("public/docs/") }
        println("--- derived-arithmetic corpus ---")
        println("  repo root      : ${root.path}")
        println("  dev_docs       : $dev page(s)")
        println("  public/docs    : $published page(s)")
        println("  total scanned  : ${found.size} page(s) (floor $MIN_PAGES_SCANNED)")

        assertTrue(
            found.size >= MIN_PAGES_SCANNED,
            "the corpus walk found only ${found.size} markdown page(s) under " +
                "${DerivedArithmeticGuard.CORPUS_ROOTS}, below the floor of $MIN_PAGES_SCANNED. " +
                "Suspect the walk, not the corpus: every assertion in this class ranges over this " +
                "list, so a walk that finds nothing makes them all pass over nothing."
        )
        assertTrue(dev > 0, "no dev_docs page was found at all — the walk or the root is wrong")
        assertTrue(
            published > 0,
            "no public/docs page was found at all. This is the specific shape of the error this " +
                "repo keeps making: a predicate that matched only dev_docs left all ten published " +
                "pages invisible to a guard whose author believed it covered both."
        )
    }

    // -------------------------------------------------------------------------------------------
    // The marker, and the arithmetic
    // -------------------------------------------------------------------------------------------

    /**
     * A marker must abut a numeral.
     *
     * The likeliest authoring slip by a distance — the comment written after the noun rather than
     * after the number — and if it were merely ignored the result would be a page that looks
     * guarded carrying a number that is not.
     */
    @Test
    fun everyMarkerIsAttachedToANumber() {
        val dangling = mutableListOf<DerivedArithmeticGuard.Dangling>()
        for (s in scans()) dangling.addAll(s.dangling)
        assertTrue(
            dangling.isEmpty(),
            "${dangling.size} derive marker(s) claim no number. The marker must sit immediately " +
                "after the digits it derives, with at most emphasis characters and one space " +
                "between. A number spelled as a word cannot be derived — a derived quantity " +
                "spelled \"two and a half\" has discarded the precision the agreement rule uses:\n" +
                dangling.joinToString("\n") { "  ${it.page}:${it.pageLine} derive: ${it.body} — ${it.text}" }
        )
    }

    /**
     * Every marker's arithmetic must be arithmetic this can evaluate.
     *
     * **This is the fail-closed half of the fail-open design.** The scan never aborts on a bad
     * marker — one typo cannot stop the other 640 pages being checked, which is the lesson
     * `tools/discover_field_tests.py` paid twelve hours of dead smoke runner for and which
     * `d6023c83b` fixed by failing open per annotation. But a malformed marker is still a
     * *failure*, reported with its path, because a marker that is quietly skipped is a published
     * number that looks guarded and is not.
     */
    @Test
    fun everyMarkerCarriesArithmeticThisCanEvaluate() {
        val bad = mutableListOf<String>()
        for (m in allMarked()) {
            val v = DerivedArithmeticGuard.evaluate(m)
            if (v is DerivedArithmeticGuard.Verdict.Unparseable) bad.add("  ${m.describe()} — ${v.reason}")
        }
        assertTrue(
            bad.isEmpty(),
            "${bad.size} derive marker(s) carry arithmetic this cannot evaluate, so the number " +
                "beside them is not checked:\n" + bad.joinToString("\n") +
                "\nThe grammar is +, -, *, / parentheses and decimal literals. It is small on " +
                "purpose: the marker's only advantage over a Kotlin-side derivation is that a " +
                "reader can audit it inside the sentence."
        )
    }

    /**
     * **The teeth.** A published number must equal its own arithmetic.
     *
     * This is the assertion the 2026-09-12 `frame_budget.md` sentence would have failed, and
     * [theAgreementCheckFailsOnAWrongDerivation] demonstrates that it can fail rather than
     * asserting it in prose.
     */
    @Test
    fun everyDerivedNumberAgreesWithItsOwnArithmetic() {
        val wrong = mutableListOf<String>()
        for (m in allMarked()) {
            val v = DerivedArithmeticGuard.evaluate(m)
            if (v is DerivedArithmeticGuard.Verdict.Disagrees) wrong.add("  ${m.describe()} — ${v.why}")
        }
        assertTrue(
            wrong.isEmpty(),
            "${wrong.size} number(s) published in prose disagree with the arithmetic the page " +
                "itself claims for them:\n" + wrong.joinToString("\n") +
                "\nCheck the direction of the ratio before checking the operands — every recorded " +
                "instance of this defect had correct, cited operands and published the reciprocal."
        )
    }

    /**
     * The covered population, floored, and the remainder counted.
     *
     * The number to watch is not the floor but the ratio printed here. A guard over nine numerals
     * in a corpus of 641 pages is not a guarded corpus, it is nine guarded sentences, and saying so
     * in the build log is the only thing that stops the mechanism being read as more than it is.
     */
    @Test
    fun theMarkedPopulationIsFlooredAndTheRemainderIsCounted() {
        val marked = allMarked()
        val markedPages = marked.map { it.page }.toSortedSet()

        var candidates = 0
        val candidatePages = sortedSetOf<String>()
        for (f in pages()) {
            val rel = relative(f)
            if (rel.startsWith("public/docs/reference/")) continue
            for (line in DerivedCountGuard.stripCode(f.readText())) {
                if (!DERIVATION_CONNECTIVE.containsMatchIn(line)) continue
                if (NUMERAL.findAll(line).count() < 2) continue
                candidates++
                candidatePages.add(rel)
            }
        }

        println("--- derived arithmetic: what is checked, and what is not ---")
        println("  markers          : ${marked.size} on ${markedPages.size} page(s) " +
            "(floors $MIN_MARKERS / $MIN_MARKED_PAGES)")
        markedPages.forEach { p -> println("    $p — ${marked.count { it.page == p }}") }
        println("  census, unmarked : $candidates line(s) on ${candidatePages.size} page(s) look " +
            "like a derivation and carry no marker")

        assertTrue(
            marked.size >= MIN_MARKERS,
            "${marked.size} derive marker(s) in the corpus, below the floor of $MIN_MARKERS. " +
                "Deleting the markers must not be a route to a green build — that is how this " +
                "class would become six green ticks over nothing."
        )
        assertTrue(
            markedPages.size >= MIN_MARKED_PAGES,
            "${markedPages.size} page(s) carry a derive marker, below the floor of " +
                "$MIN_MARKED_PAGES: $markedPages. The cheapest way to shed markers is to stop " +
                "guarding a page, so the page count is floored separately from the marker count."
        )
        assertTrue(
            candidates > 0,
            "the census predicate matched no line in the whole corpus. A survey returning zero " +
                "deserves as much suspicion as one returning everything — suspect " +
                "DERIVATION_CONNECTIVE, not the corpus."
        )
    }

    // -------------------------------------------------------------------------------------------
    // The mechanism's own tests
    // -------------------------------------------------------------------------------------------

    /**
     * **Proof that the teeth can bite**, by replaying the real defect.
     *
     * `public/docs/frame_budget.md` published "roughly 0.6 chances per frame" for a 10 ms sampler
     * at 60 fps. Both operands were right and both were in the sentence; the published number was
     * `10/16.67`, the reciprocal of the answer. `b84ce6d25` fixed the prose, so the instance is
     * gone from the corpus and cannot be the thing that keeps this mechanism honest. This is that
     * instance, dated and fenced, in the manner
     * `dev_docs/characterization_tests.md` prescribes.
     */
    @Test
    fun theAgreementCheckFailsOnAWrongDerivation() {
        val reciprocal = marker("0.6", "16.67/10")
        val verdict = DerivedArithmeticGuard.evaluate(reciprocal)
        assertTrue(
            verdict is DerivedArithmeticGuard.Verdict.Disagrees,
            "the 2026-09-12 reciprocal — 0.6 published for 16.67/10 — must be reported as a " +
                "disagreement, or this class's headline assertion has no teeth. Got: $verdict"
        )
        assertTrue(
            "1.667" in verdict.why,
            "the failure message must name the value the arithmetic gives, or a red build teaches " +
                "nothing: ${verdict.why}"
        )

        // And the corrected sentence passes, so this is a check and not a refusal of all ratios.
        assertTrue(
            DerivedArithmeticGuard.evaluate(marker("1.7", "16.7/10"))
                is DerivedArithmeticGuard.Verdict.Agrees,
            "the corrected form — 1.7 published for 16.7/10 — must pass. 1.67 rounds to 1.7 at the " +
                "one decimal place the page publishes, which is the whole default tolerance rule."
        )
    }

    /** The grammar: what it accepts, and that everything else is a named failure rather than a throw. */
    @Test
    fun theEvaluatorAcceptsItsGrammarAndRefusesEverythingElse() {
        assertEquals(1000.0 / 60.0, DerivedArithmeticGuard.Expr.eval("1000/60"))
        assertEquals(360.0, DerivedArithmeticGuard.Expr.eval("6*60"))
        assertEquals(11500.0, DerivedArithmeticGuard.Expr.eval("23 * 500"))
        assertEquals(1500.0, DerivedArithmeticGuard.Expr.eval("10*5*30"))
        assertEquals(11.0, DerivedArithmeticGuard.Expr.eval("(1+10)"))
        assertEquals(-5.0, DerivedArithmeticGuard.Expr.eval("-5"))
        assertEquals(5.0, DerivedArithmeticGuard.Expr.eval("10-5"))
        assertEquals(1500.0, DerivedArithmeticGuard.Expr.eval("1,500"))
        assertEquals(8.2944, DerivedArithmeticGuard.Expr.eval("1920*1080*4/1e6")!!, 1e-9)
        // Precedence, because getting this wrong would make every marker in the corpus meaningless
        // in exactly the direction nobody would notice.
        assertEquals(7.0, DerivedArithmeticGuard.Expr.eval("1+2*3"))
        assertEquals(9.0, DerivedArithmeticGuard.Expr.eval("(1+2)*3"))

        // Every refusal is a null, never an exception. A guard that throws on a bad marker is a
        // guard that a bad marker can turn into an error whose message is a stack trace.
        for (bad in listOf(
            "", "   ", "1/0", "1+", "*2", "(1+2", "1+2)", "round(1)", "hz*ms", "2^8", "1 2", "1..2",
        )) {
            assertEquals(null, DerivedArithmeticGuard.Expr.eval(bad), "`$bad` must not evaluate")
        }
    }

    /**
     * The marker terminator against an expression that ends in a subtraction.
     *
     * `<!--derive: 10-5-->` is ambiguous to a greedy reader and is not ambiguous to the regex, for
     * the reason [DerivedArithmeticGuard.MARKER]'s KDoc gives. Worth a test rather than a comment:
     * if this were wrong, every subtraction in the corpus would parse as something else and the
     * failure would be a value mismatch rather than a parse error, which is the confusing direction.
     */
    @Test
    fun aSubtractionDoesNotConfuseTheMarkerTerminator() {
        val scan = DerivedArithmeticGuard.scanText(
            "A budget of 5<!--derive: 10-5--> milliseconds is what is left.\n",
            "synthetic.md",
        )
        assertEquals(1, scan.marked.size, "one marker expected, got ${scan.marked}")
        assertEquals("10-5", scan.marked[0].expression)
        assertTrue(
            DerivedArithmeticGuard.evaluate(scan.marked[0]) is DerivedArithmeticGuard.Verdict.Agrees
        )
        assertTrue(scan.dangling.isEmpty(), "no dangling marker expected, got ${scan.dangling}")
    }

    /**
     * Tolerances, and the shape of the fail-open decision.
     *
     * The second half is the one worth having: a page carrying **one** unparseable marker must
     * still have its other markers evaluated. Fail-closed-tree-wide would have been the easier
     * code and is the recorded mistake.
     */
    @Test
    fun tolerancesAreHonouredAndOneBadMarkerDoesNotBlindTheRest() {
        assertTrue(
            DerivedArithmeticGuard.evaluate(marker("360", "361.4 ~2"))
                is DerivedArithmeticGuard.Verdict.Agrees,
            "an absolute tolerance must widen the default rounding rule"
        )
        assertTrue(
            DerivedArithmeticGuard.evaluate(marker("360", "361.4"))
                is DerivedArithmeticGuard.Verdict.Disagrees,
            "and without the tolerance the same pair must fail, or `~` buys nothing"
        )
        assertTrue(
            DerivedArithmeticGuard.evaluate(marker("360", "361.4 ~5%"))
                is DerivedArithmeticGuard.Verdict.Agrees,
            "a relative tolerance must widen it too"
        )
        assertTrue(
            DerivedArithmeticGuard.evaluate(marker("360", "500 ~5%"))
                is DerivedArithmeticGuard.Verdict.Disagrees,
            "a relative tolerance must not be unbounded"
        )
        assertTrue(
            DerivedArithmeticGuard.evaluate(marker("360", "361.4 ~lots"))
                is DerivedArithmeticGuard.Verdict.Unparseable,
            "an unreadable tolerance must be a named failure, not a silently honoured one"
        )

        val page = DerivedArithmeticGuard.scanText(
            """
            |A good one: 100<!--derive: 1000/10--> a second.
            |A broken one: 42<!--derive: round(3)--> of them.
            |Another good one: 360<!--derive: 6*60--> a second.
            """.trimMargin(),
            "synthetic.md",
        )
        assertEquals(3, page.marked.size, "all three markers must be found: ${page.marked}")
        val verdicts = page.marked.map { DerivedArithmeticGuard.evaluate(it) }
        assertEquals(
            2,
            verdicts.count { it is DerivedArithmeticGuard.Verdict.Agrees },
            "the two sound markers must still be evaluated around the broken one — failing closed " +
                "tree-wide on a bad annotation is what left the smoke runner dead for twelve " +
                "hours before d6023c83b. Got: $verdicts"
        )
        assertEquals(
            1,
            verdicts.count { it is DerivedArithmeticGuard.Verdict.Unparseable },
            "and the broken one must be reported rather than skipped. Got: $verdicts"
        )
    }

    /** A marker inside a code span or a fence can never pin a line number. */
    @Test
    fun aMarkerInsideCodeIsNotAMarker() {
        val inline = DerivedArithmeticGuard.scanText(
            "The call site is `Renderer.kt:92<!--derive: 46*2-->` on the render thread.\n",
            "synthetic.md",
        )
        assertTrue(
            inline.marked.isEmpty() && inline.dangling.isEmpty(),
            "a marker inside an inline code span must be invisible, or this mechanism rebuilds " +
                "the line-number merge hazard CitationContractTest refuses: ${inline.marked}"
        )
        val fenced = DerivedArithmeticGuard.scanText(
            "```kotlin\nval n = 92<!--derive: 46*2-->\n```\n",
            "synthetic.md",
        )
        assertTrue(
            fenced.marked.isEmpty() && fenced.dangling.isEmpty(),
            "a marker inside a fenced block must be invisible: ${fenced.marked}"
        )
    }

    /** A marker written after the noun rather than the number is reported, not ignored. */
    @Test
    fun aMisplacedMarkerIsReportedRatherThanIgnored() {
        val scan = DerivedArithmeticGuard.scanText(
            "It fires 100 times<!--derive: 1000/10--> a second.\n",
            "synthetic.md",
        )
        assertTrue(scan.marked.isEmpty(), "nothing should be treated as marked: ${scan.marked}")
        assertEquals(1, scan.dangling.size, "the misplaced marker must be reported: ${scan.dangling}")
    }

    /** A [DerivedArithmeticGuard.Marked] built by hand, for the mechanism's own tests. */
    private fun marker(literal: String, body: String): DerivedArithmeticGuard.Marked {
        val scan = DerivedArithmeticGuard.scanText("x $literal<!--derive: $body--> y\n", "synthetic.md")
        assertEquals(
            1,
            scan.marked.size,
            "the fixture `$literal` / `$body` did not produce exactly one marker: $scan"
        )
        return scan.marked[0]
    }
}
