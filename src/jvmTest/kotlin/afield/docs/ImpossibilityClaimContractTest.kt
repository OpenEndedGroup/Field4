package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract under [ImpossibilityClaimGuard]: every impossibility claim in `dev_docs` carries its
 * evidence, and the population is floored in both directions so neither the claims nor the markers can
 * quietly disappear.
 *
 * ## What is actually pinned, versus what is merely scanned
 *
 * These are different numbers and the difference has already misled a review on this project, so both
 * are printed and both are asserted:
 *
 * - **Scanned**: every `*.md` under `dev_docs`, [MIN_CORPUS_PAGES] of them, all read on every run.
 * - **Pinned**: [MIN_CLAIMS] detected claims across [MIN_CLAIM_PAGES] pages, of which
 *   [MIN_EVIDENCE_BEARING] carry a reproducer or a citation rather than a conjecture marker, and
 *   **zero** are unmarked.
 *
 * A branch that widens the scan while the pinned count stands still has not made the corpus safer, and
 * saying so is the only way that stays visible.
 */
class ImpossibilityClaimContractTest {

    private val root = CitationGuard.repoRoot()
    private val devDocs = File(root, "dev_docs")

    companion object {
        /**
         * Pages with claims, and the floor on how many each keeps.
         *
         * The floor is what stops the cheapest repair: deleting the sentence, or de-emphasising it so
         * the detector stops seeing it, instead of finding out whether it is true. Lowering an entry is
         * allowed — a limitation really does get fixed — but it is a visible edit with a reason, which
         * is the whole bargain.
         *
         * Measured 2026-08-25 on `4c235bbeb`.
         */
        val ANNOTATED_PAGES: Map<String, Int> = mapOf(
            "dev_docs/ALIFE.md" to 1,
            "dev_docs/CERES.md" to 2,
            "dev_docs/DOCS_IMPROVEMENT_AGENT.md" to 1,
            "dev_docs/DOCS_RUNNER_GPU_CAPTURES.md" to 5,
            "dev_docs/FIELDML_HOST_BRIDGE.md" to 4,
            "dev_docs/NVFLOW_BINDINGS.md" to 1,
            "dev_docs/brief_notation.md" to 2,
            "dev_docs/chart_primitive.md" to 1,
            "dev_docs/citation_contracts.md" to 1,
            "dev_docs/completion_aa_sidecar.md" to 1,
            "dev_docs/completion_rank_at_scale.md" to 1,
            "dev_docs/fline_close_nodes.md" to 1,
            "dev_docs/impossibility_claims.md" to 1,
            "dev_docs/kotlin_to_js_spike.md" to 1,
            "dev_docs/morpho_circuit_search.md" to 1,
            "dev_docs/pitch_transcription_roundtrip.md" to 2,
        )

        /** Floor on claims corpus-wide. See [ANNOTATED_PAGES] for why a floor and not an equality. */
        const val MIN_CLAIMS = 26

        /** Floor on pages carrying at least one claim. */
        const val MIN_CLAIM_PAGES = 16

        /**
         * Floor on claims backed by a **reproducer or a citation** rather than a conjecture marker.
         *
         * Without this the convention is satisfiable by marking the entire corpus `(conjecture, …)`,
         * which would be honest and useless. This is the number that says the corpus is mostly
         * *measured*.
         */
        const val MIN_EVIDENCE_BEARING = 20

        /**
         * Floor on claims carrying a runnable [ImpossibilityClaimGuard.Marker.REPRODUCER].
         *
         * Small on purpose. Most limits in this corpus are properties of a third-party API or of the
         * JVM, and the honest evidence for those is a citation. A reproducer floor above zero exists so
         * that the strongest marker is exercised by the real corpus and not only by fixtures — a
         * mechanism no page uses is a mechanism nobody has checked.
         */
        const val MIN_REPRODUCERS = 5

        /**
         * Ceiling on the escape hatch. It is a real escape hatch — the detector has a measured misfire
         * rate — but it is also the one marker that carries no evidence at all, so it is capped rather
         * than trusted.
         */
        const val MAX_WAIVERS = 2

        /** Floor on the scanned corpus, so "0 unmarked" cannot be produced by scanning nothing. */
        const val MIN_CORPUS_PAGES = 100
    }

    private fun pages(): List<File> = ImpossibilityClaimGuard.markdownUnder(devDocs)

    // ---------------------------------------------------------------------------------------------
    // Premise
    // ---------------------------------------------------------------------------------------------

    /**
     * The premise, asserted before anything is concluded from it: there is a corpus, it is being read,
     * and the detector is switched on.
     *
     * A guard whose corpus silently became empty reports a perfect score. That is not a hypothetical
     * failure mode on this project — a docs guard passed vacuously for a night because Gradle held it
     * `UP-TO-DATE` after a Markdown-only edit, and a stale pass certified a wrong number.
     */
    @Test
    fun theCorpusIsRealAndTheDetectorIsArmed() {
        val ps = pages()
        assertTrue(
            ps.size >= MIN_CORPUS_PAGES,
            "only ${ps.size} markdown pages under dev_docs (floor $MIN_CORPUS_PAGES) — is the corpus " +
                "being read at all?",
        )
        assertTrue(ps.all { it.length() > 0 }, "empty markdown pages under dev_docs: " +
            ps.filter { it.length() == 0L }.joinToString { it.name })
        assertTrue(
            ImpossibilityClaimGuard.STRONG.isNotEmpty() &&
                ImpossibilityClaimGuard.WEAK.isNotEmpty() &&
                ImpossibilityClaimGuard.RULED_OUT.isNotEmpty(),
            "an idiom family is empty, so the detector cannot fire",
        )
    }

    /**
     * The convention has to be written down somewhere a person can read, and the guard's own failure
     * text points at that page. If the page stops describing a marker the guard still accepts, the two
     * have diverged and the failure message is a dead end.
     */
    @Test
    fun theConventionIsDocumentedAndTheDocumentDescribesEveryMarker() {
        val page = File(root, "dev_docs/impossibility_claims.md")
        assertTrue(page.isFile, "dev_docs/impossibility_claims.md is missing; the guard's advice points at it")
        val text = page.readText()
        listOf("(reproducer:", "(conjecture,", "(not a limitation:").forEach {
            assertTrue(it in text, "dev_docs/impossibility_claims.md never mentions the `$it` marker")
        }
        assertTrue(
            "DOCS_RUNNER_GPU_CAPTURES" in text,
            "the page drops the story that motivates the convention, which is the argument for it",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The corpus
    // ---------------------------------------------------------------------------------------------

    /** The load-bearing assertion: nothing in `dev_docs` asserts an impossibility without evidence. */
    @Test
    fun everyImpossibilityClaimInDevDocsCarriesItsEvidence() {
        val inv = ImpossibilityClaimGuard.inventory(root, pages())
        println("--- dev_docs impossibility-claim inventory ---")
        println(inv.render())
        println("scanned: ${pages().size} markdown pages under dev_docs")

        if (inv.unmarked.isNotEmpty()) {
            val report = inv.unmarked.joinToString("\n\n") { u ->
                "  ${u.claim.describe()}\n    ${u.claim.text.take(160)}\n    -> ${u.reason}\n" +
                    ImpossibilityClaimGuard.advice(u.claim.kind)
            }
            error(
                "${inv.unmarked.size} impossibility claim(s) in dev_docs carry no evidence:\n\n$report",
            )
        }
    }

    /**
     * The inventory, floored and ceilinged in every direction it can move.
     *
     * Both numbers the brief for this work asked for are printed together on purpose: the corpus
     * *scanned* and the claims *pinned*. A recent branch reported a scan going 18 → 216 pages and
     * recommended a merge while the marker coverage it actually pinned had gone 9 → 12. Both statements
     * were true and only one of them mattered.
     */
    @Test
    fun theClaimPopulationIsFlooredInBothDirections() {
        val inv = ImpossibilityClaimGuard.inventory(root, pages())
        val evidenceBearing = inv.byMarker(ImpossibilityClaimGuard.Marker.REPRODUCER).size +
            inv.byMarker(ImpossibilityClaimGuard.Marker.CITATION).size
        val waivers = inv.byMarker(ImpossibilityClaimGuard.Marker.NOT_A_LIMITATION).size
        val reproducers = inv.byMarker(ImpossibilityClaimGuard.Marker.REPRODUCER).size

        println(
            "pinned: ${inv.claims.size} claims / ${inv.pages.size} pages / $evidenceBearing evidence-bearing " +
                "/ $reproducers reproducers / $waivers waivers"
        )

        assertTrue(
            inv.claims.size >= MIN_CLAIMS,
            "only ${inv.claims.size} impossibility claims detected (floor $MIN_CLAIMS). A claim was " +
                "deleted or de-emphasised rather than answered, or the detector regressed. Lower the " +
                "floor deliberately if a limitation really was fixed.",
        )
        assertTrue(
            inv.pages.size >= MIN_CLAIM_PAGES,
            "only ${inv.pages.size} pages carry a claim (floor $MIN_CLAIM_PAGES)",
        )
        assertTrue(
            evidenceBearing >= MIN_EVIDENCE_BEARING,
            "only $evidenceBearing claims carry a reproducer or a citation (floor $MIN_EVIDENCE_BEARING). " +
                "The corpus must not drift toward being all conjecture.",
        )
        assertTrue(
            reproducers >= MIN_REPRODUCERS,
            "only $reproducers claims carry a runnable reproducer (floor $MIN_REPRODUCERS)",
        )
        assertTrue(
            waivers <= MAX_WAIVERS,
            "$waivers claims are waived with `(not a limitation: …)` (ceiling $MAX_WAIVERS). The escape " +
                "hatch must not become universal suppression: " +
                inv.byMarker(ImpossibilityClaimGuard.Marker.NOT_A_LIMITATION)
                    .joinToString { it.claim.describe() },
        )
    }

    /**
     * The ledger, read in **both** directions.
     *
     * Forward: a page listed here must still carry at least its floor. Backward: a page listed here
     * must still carry *some* claim — otherwise a page whose claims were all deleted leaves a stale
     * ledger row that nothing reports, and the ledger degrades into a list of pages that used to
     * matter. An empty list must fail, not pass.
     */
    @Test
    fun theAnnotatedPageLedgerIsNeitherShortNorStale() {
        val inv = ImpossibilityClaimGuard.inventory(root, pages())
        val perPage = inv.claims.groupingBy { it.page }.eachCount()

        // Printed so that refreshing ANNOTATED_PAGES after a deliberate change is a copy rather than a
        // hand-count. A ledger nobody can cheaply regenerate is a ledger that drifts.
        println("--- claims per page (ledger source) ---")
        perPage.entries.sortedBy { it.key }.forEach { println("            \"" + it.key + "\" to " + it.value + ",") }

        val shortfalls = ANNOTATED_PAGES.mapNotNull { (page, floor) ->
            val n = perPage[page] ?: 0
            if (n < floor) "  $page has $n claims, floor is $floor" else null
        }
        assertTrue(shortfalls.isEmpty(), "annotated pages lost claims:\n" + shortfalls.joinToString("\n"))

        val stale = ANNOTATED_PAGES.keys.filter { !File(root, it).isFile }
        assertTrue(stale.isEmpty(), "ledger names pages that no longer exist: $stale")

        assertTrue(
            ANNOTATED_PAGES.isNotEmpty() && perPage.isNotEmpty(),
            "the ledger or the detected population is empty, which would make every check above vacuous",
        )
        assertEquals(
            emptySet(),
            ANNOTATED_PAGES.keys - perPage.keys,
            "ledger rows whose page no longer carries any claim at all — delete the row, or find out why",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Counterfactual: the guard is observed to bite
    // ---------------------------------------------------------------------------------------------

    /**
     * The detector fires on every shape it claims to recognise, and stays quiet on the shapes it
     * measured as noise.
     *
     * This is the assertion that stops a regex refactor from silently neutering the guard: without it,
     * deleting a pattern makes the corpus check *pass harder*.
     */
    @Test
    fun theDetectorRecognisesEveryClaimShapeAndNoneOfTheMeasuredNoise() {
        val tmp = createTempDir("impossibility-shapes")
        try {
            val claims = File(tmp, "claims.md")
            claims.writeText(
                """
                |Single-pass is impossible on this compiler.
                |
                |There is no way to hold a parameter block constant.
                |
                |Nothing can be held constant here.
                |
                |- **The bridge cannot narrow for you.** An erased generic reads back as a fresh variable.
                |
                |## Redirects cannot walk the proxy off the docs site
                |
                || A video of the GPU window | — | ❌ not supported; the surface is stills-only |
                |
                |**A red herring we ruled out:** the four insert builders emit an identical declaration.
                |
                |That fix was tested and disproven.
                |""".trimMargin()
            )
            val found = ImpossibilityClaimGuard.claims(claims, "claims.md")
            assertEquals(
                8, found.size,
                "expected one claim per planted shape, got:\n" + found.joinToString("\n") { it.describe() },
            )
            assertEquals(
                2, found.count { it.kind == ImpossibilityClaimGuard.Kind.RULED_OUT },
                "the ruled-out family is the one the obvious regex misses; it must be recognised",
            )

            val noise = File(tmp, "noise.md")
            noise.writeText(
                """
                |The model smoke refuses to run unless the environment variable is set.
                |
                |A class file that cannot be read fingerprints as `nanoTime` instead.
                |
                |The test asserts that too, so an op cannot be added without a check.
                |
                |There was no way to see a level before this landed.
                |
                |*Was: two conventions, and hole support impossible in one of them. Unified since.*
                |
                |It has to live in `afield.stdlib` or a box cannot reach it — no longer true.
                |
                |### What it cannot do
                |
                |> Such a configuration is not supported.
                |
                |```
                |null cannot be cast to non-null type <root>.<box>__preamble__2
                |```
                |
                |Identifiers are not prose: `unsupportedOps` and `@KaK1Unsupported` are names.
                |""".trimMargin()
            )
            val quiet = ImpossibilityClaimGuard.claims(noise, "noise.md")
            assertEquals(
                emptyList(),
                quiet.map { it.describe() to it.text.take(60) },
                "the detector fired on prose measured to be noise",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * A **fake** reproducer path is rejected, and a real one is accepted — four ways of being fake,
     * each with its own message.
     *
     * "The path exists" is not the bar. A reproducer is a promise that a future reader can re-run the
     * refusal, and the cheapest way to break that promise is a plausible-looking string.
     */
    @Test
    fun aFakeReproducerIsRejectedAndARealOneIsAccepted() {
        val tmp = createTempDir("impossibility-repro")
        try {
            File(tmp, "settings.gradle.kts").writeText("")
            val test = File(tmp, "src/jvmTest/kotlin/afield/Fixture.kt")
            test.parentFile.mkdirs()
            test.writeText(
                """
                |package afield
                |class Fixture {
                |    @Test
                |    fun theRefusalIsObserved() { }
                |
                |    fun notATestAtAll() { }
                |}
                |""".trimMargin()
            )
            val notRunnable = File(tmp, "src/jvmMain/kotlin/afield/Plain.kt")
            notRunnable.parentFile.mkdirs()
            notRunnable.writeText("package afield\ndata class Plain(val x: Int)\n")

            fun why(spec: String) = ImpossibilityClaimGuard.validateReproducer(tmp, spec)

            assertEquals(
                null, why("src/jvmTest/kotlin/afield/Fixture.kt#fun theRefusalIsObserved"),
                "a real @Test reproducer must be accepted",
            )

            val missing = why("src/jvmTest/kotlin/afield/NoSuchFixture.kt#fun whatever")
            assertTrue(
                missing != null && "does not exist" in missing,
                "a reproducer naming a file that is not there must say so: $missing",
            )

            val noAnchor = why("src/jvmTest/kotlin/afield/Fixture.kt#fun neverWritten")
            assertTrue(
                noAnchor != null && "occurs nowhere" in noAnchor,
                "a reproducer whose anchor is absent must say so: $noAnchor",
            )

            val notATest = why("src/jvmTest/kotlin/afield/Fixture.kt#fun notATestAtAll")
            assertTrue(
                notATest != null && "@Test" in notATest,
                "a function with no @Test above it runs nothing, and must be rejected as such: $notATest",
            )

            val notRunnableWhy = why("src/jvmMain/kotlin/afield/Plain.kt#data class Plain")
            assertTrue(
                notRunnableWhy != null && "fun main(" in notRunnableWhy,
                "a real file that is not a test or an example must be rejected: $notRunnableWhy",
            )

            val noHash = why("src/jvmTest/kotlin/afield/Fixture.kt")
            assertTrue(
                noHash != null && "#anchor" in noHash,
                "a reproducer with no anchor must be rejected: $noHash",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * The counterfactual, floored at a number rather than at "differs at all".
     *
     * Planting five unmarked claims of five different shapes beside the real corpus must produce at
     * least five findings; the real corpus on its own must produce none. Both halves are needed: a
     * "the results differ" assertion passes when a broken detector finds nothing in either, and a
     * blank-versus-blank comparison passes at zero differences.
     */
    @Test
    fun plantingUnmarkedClaimsBesideTheRealCorpusIsDetected() {
        val real = ImpossibilityClaimGuard.inventory(root, pages())
        assertEquals(
            0, real.unmarked.size,
            "the baseline half of the counterfactual must be clean, or the comparison proves nothing",
        )
        assertTrue(
            real.claims.isNotEmpty(),
            "the baseline found no claims at all, so a clean baseline is a blank artefact, not a pass",
        )

        val tmp = createTempDir("impossibility-counterfactual")
        try {
            val planted = File(tmp, "planted.md")
            planted.writeText(
                """
                |Reading a probe buffer back is impossible on this path.
                |
                |There is no way to name a strategy from a rewrite rule.
                |
                |- **The sidecar cannot enumerate a binary package by prefix.** So the popup is empty.
                |
                |**A red herring we ruled out:** the duplicate declaration was not the cause.
                |
                || A video of the window | — | ❌ not supported |
                |""".trimMargin()
            )
            val withPlant = ImpossibilityClaimGuard.inventory(root, pages() + planted)
            val newlyUnmarked = withPlant.unmarked.size - real.unmarked.size
            assertTrue(
                newlyUnmarked >= 5,
                "planting 5 unmarked claims produced only $newlyUnmarked new findings — the guard does " +
                    "not bite. Findings were:\n" + withPlant.unmarked.joinToString("\n") { it.claim.describe() },
            )
            assertTrue(
                withPlant.unmarked.any { it.claim.kind == ImpossibilityClaimGuard.Kind.RULED_OUT },
                "the planted ruled-out claim was not caught",
            )
            assertTrue(
                withPlant.unmarked.all { it.reason.isNotBlank() },
                "an unmarked finding with no reason is not actionable",
            )
            ImpossibilityClaimGuard.Kind.entries.forEach { kind ->
                assertTrue(
                    ImpossibilityClaimGuard.advice(kind).contains("(reproducer:"),
                    "the advice for $kind does not tell the author what to write",
                )
            }
            // The advice for a ruled-out claim must not *offer* the citation form. It does mention
            // citations, to explain why they are refused, so match the offered form rather than the
            // word: the earlier spelling of this assertion looked for "citation" before a substring
            // and passed on the explanation instead of the offer, which is a tautology.
            val verdictAdvice = ImpossibilityClaimGuard.advice(ImpossibilityClaimGuard.Kind.VERDICT)
            val ruledOutAdvice = ImpossibilityClaimGuard.advice(ImpossibilityClaimGuard.Kind.RULED_OUT)
            assertTrue(
                "`SomeFile.kt:120#theSymbol`" in verdictAdvice,
                "the verdict advice must offer the citation form",
            )
            assertTrue(
                "`SomeFile.kt:120#theSymbol`" !in ruledOutAdvice,
                "the ruled-out advice must not offer a plain citation as a way out:\n$ruledOutAdvice",
            )
            assertTrue(
                "cannot establish that something is absent" in ruledOutAdvice,
                "and it must say why, or the refusal reads as arbitrary",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * A citation satisfies a verdict and does **not** satisfy a ruled-out claim.
     *
     * This asymmetry is the single rule that would have caught the original failure, so it is pinned
     * directly rather than inferred from the corpus happening to be green.
     */
    @Test
    fun aCitationCannotSubstantiateARuledOutClaim() {
        val tmp = createTempDir("impossibility-asymmetry")
        try {
            File(tmp, "settings.gradle.kts").writeText("")
            val src = File(tmp, "src/jvmMain/kotlin/afield/Thing.kt")
            src.parentFile.mkdirs()
            src.writeText("package afield\nval theAnchorLine = 1\nfun main() {}\n")

            val verdict = File(tmp, "verdict.md")
            verdict.writeText(
                "Reading it back is impossible on this path, see `src/jvmMain/kotlin/afield/Thing.kt#theAnchorLine`.\n"
            )
            val vr = ImpossibilityClaimGuard.rule(tmp, verdict, "verdict.md")
            assertEquals(1, vr.size, "expected one claim in the verdict fixture")
            assertTrue(
                vr.single() is ImpossibilityClaimGuard.Ruling.Marked,
                "a verdict with a resolving citation must be accepted: ${vr.single()}",
            )

            val ruledOut = File(tmp, "ruled.md")
            ruledOut.writeText(
                "**A red herring we ruled out:** see `src/jvmMain/kotlin/afield/Thing.kt#theAnchorLine`.\n"
            )
            val rr = ImpossibilityClaimGuard.rule(tmp, ruledOut, "ruled.md")
            assertEquals(1, rr.size, "expected one claim in the ruled-out fixture")
            val unmarked = rr.single()
            assertTrue(
                unmarked is ImpossibilityClaimGuard.Ruling.Unmarked,
                "a ruled-out claim must not be satisfiable by a citation: $unmarked",
            )
            assertTrue(
                "absence" in unmarked.reason,
                "the refusal must explain itself: ${unmarked.reason}",
            )

            val withRepro = File(tmp, "ruled_ok.md")
            withRepro.writeText(
                "**A red herring we ruled out:** the declaration was not the cause. (conjecture, 2026-08-25)\n"
            )
            assertTrue(
                ImpossibilityClaimGuard.rule(tmp, withRepro, "ruled_ok.md")
                    .single() is ImpossibilityClaimGuard.Ruling.Marked,
                "an explicitly-conjectural ruled-out claim must be accepted",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * The claim unit is the paragraph, the bullet or the table row — not the whole section.
     *
     * Pinned because the generous reading is the tempting one, and it is what the six-week section did:
     * one "what we verified" paragraph covering four independent assertions, three of them wrong.
     */
    @Test
    fun aMarkerDoesNotReachOutOfItsOwnBulletOrParagraph() {
        val tmp = createTempDir("impossibility-scope")
        try {
            val page = File(tmp, "scope.md")
            page.writeText(
                """
                |- **The first thing cannot be done.** (conjecture, 2026-08-25)
                |- **The second thing cannot be done either.**
                |
                |Reading it back is impossible.
                |
                |(conjecture, 2026-08-25)
                |""".trimMargin()
            )
            val rulings = ImpossibilityClaimGuard.rule(tmp, page, "scope.md")
            assertEquals(3, rulings.size, "expected three claims: two bullets and one paragraph")
            assertTrue(
                rulings[0] is ImpossibilityClaimGuard.Ruling.Marked,
                "a bullet carrying its own marker is marked",
            )
            assertTrue(
                rulings[1] is ImpossibilityClaimGuard.Ruling.Unmarked,
                "the next bullet must not inherit the previous bullet's marker",
            )
            assertTrue(
                rulings[2] is ImpossibilityClaimGuard.Ruling.Unmarked,
                "a marker in a separate paragraph must not reach the claim",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
