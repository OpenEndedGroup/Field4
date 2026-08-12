package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus-wide citation guard: every `dev_docs` page that opts in, checked in one place.
 *
 * `SmallLanguageAnatomyTest` guards one page and does a great deal else besides (derived-count
 * ratchets, language censuses). This guards the *class* — it knows nothing about small languages, only
 * about citations — so bringing a new page under guard is one line in [GUARDED_PAGES] rather than a
 * new test class.
 *
 * ## What "opting in" means
 *
 * A page is guarded once its citations carry anchors. Two forms are understood, both resolved by
 * [CitationGuard] searching the file for the anchor rather than by counting lines:
 *
 * - ledger rows — `` | n | what it is | `path:line` | `anchor` | ``
 * - prose — `` `path:line#anchor` ``, or `` `path#anchor` `` with no number at all
 *
 * A bare `` `path:line` `` with no anchor is *not* guarded and nothing can check it. Those are counted
 * per page by [unguardedCitationsAreCountedNotIgnored] so that the size of the unchecked remainder is
 * a number in the build log instead of a thing nobody knows.
 *
 * ## Why the assertions are the shape they are
 *
 * Only properties that survive somebody inserting lines into a cited file are asserted, which rules
 * out the most tempting assertion of all: *"every advisory line number is correct"*. Enforcing that
 * would rebuild exactly the merge hazard this machinery exists to remove — one insertion above a
 * densely-cited site turns it red.
 *
 * But "unassertable" is not the same as "unenforced", and leaving it there would have swapped a guard
 * that went red for a number that goes unread. So the enforcement moves from the **count** to the
 * **magnitude**: no single citation may be more than [CitationGuard.MAX_ADVISORY_DRIFT] lines wrong.
 * How *many* numbers drift is a property of a page's citation density and says nothing about harm; how
 * *far* one is wrong is exactly how harmful it is. The argument in full is on
 * [CitationGuard.MAX_ADVISORY_DRIFT], and in `dev_docs/citation_contracts.md`.
 */
class CitationContractTest {

    private companion object {
        /**
         * The pages under corpus-wide guard, and the floor of anchored citations each must keep.
         *
         * The floor is what stops the cheapest possible repair — deleting the anchor, or the whole
         * citation — from turning a red build green. Raise one when you add citations; lowering one
         * should feel like an admission.
         */
        val GUARDED_PAGES = mapOf(
            "dev_docs/small_language_anatomy.md" to 134,
            "dev_docs/fline_fill_orientation.md" to 20,
            "dev_docs/completion_aa_sidecar.md" to 24,
            "dev_docs/FIELDML_HOST_BRIDGE.md" to 9,
            "dev_docs/STDLIB_DRAWING_FACE_PACKAGING.md" to 6,
            "dev_docs/arc_length_spacing.md" to 4,
            "dev_docs/morpho_circuit_search.md" to 4,
            "dev_docs/FLineGeometry.md" to 2,
            "dev_docs/kdoc_reference_contract.md" to 2,
            "dev_docs/ALIFE.md" to 1,
            "dev_docs/COMPLETION_ENGINE.md" to 1,
            "dev_docs/autodiff.md" to 1,
            "dev_docs/brief_notation.md" to 1,
            "dev_docs/kotlinjs_status_and_roadmap.md" to 1,
        )

        /**
         * Corpus floor on anchors that are unique in their file. Unique anchors have unbounded
         * [CitationGuard.headroom]: no amount of unrelated editing can make them resolve to the wrong
         * place. This is the number that actually measures how safe the corpus is.
         */
        const val MIN_UNIQUE_ANCHORS = 184

        /**
         * Corpus ceiling on anchors fragile enough to be confused by fewer than
         * [CitationGuard.LOW_HEADROOM] inserted lines. Zero, and it should stay zero — the whole
         * argument of this change is that this population is the one that detonates on merges, and it
         * is cheap to keep empty by lengthening an anchor.
         */
        const val MAX_FRAGILE = 0
    }

    private val root = CitationGuard.repoRoot()

    private fun pages(): List<File> = GUARDED_PAGES.keys.map { rel ->
        File(root, rel).also { assertTrue(it.isFile, "guarded page $rel is missing") }
    }

    /**
     * Every anchored citation in the corpus resolves to a real site.
     *
     * This is the only hard failure in the class, and it fires for exactly one reason: something a doc
     * points at was renamed or deleted. That is an editorial event worth a human's attention. Code
     * merely moving is not, and no longer registers here at all.
     */
    @Test
    fun everyAnchoredCitationInTheCorpusResolves() {
        val broken = pages().flatMap { p ->
            val rel = p.relativeTo(root).path
            CitationGuard.parse(p, rel).map { CitationGuard.resolve(root, it) }
        }.filterIsInstance<CitationGuard.Resolution.Broken>()

        assertTrue(
            broken.isEmpty(),
            "${broken.size} citation(s) in the guarded corpus no longer resolve:\n" +
                broken.joinToString("\n") { "  ${it.citation.describe()}: ${it.reason}" }
        )
    }

    /** Each guarded page keeps at least as many anchored citations as it is on record as having. */
    @Test
    fun everyGuardedPageKeepsItsCitations() {
        val shortfalls = GUARDED_PAGES.mapNotNull { (rel, floor) ->
            val n = CitationGuard.parse(File(root, rel), rel).size
            if (n < floor) "  $rel has $n anchored citations, floor is $floor" else null
        }
        assertTrue(
            shortfalls.isEmpty(),
            "a guarded page lost citations — anchors deleted rather than repaired?\n" +
                shortfalls.joinToString("\n")
        )
    }

    /**
     * The corpus inventory, printed and floored.
     *
     * The printed block is the machine-readable record of how fragile the corpus is on any given
     * build; the assertions under it are the part that makes the number mean something.
     */
    @Test
    fun theCorpusInventoryIsReportedAndFloored() {
        val inv = CitationGuard.inventory(root, pages())
        println("--- dev_docs citation inventory (${GUARDED_PAGES.size} guarded pages) ---")
        println(inv.render())

        // The same inventory, machine-readable, for questions this class did not anticipate —
        // "which guarded pages cite Server.kt?" chief among them, since that is the query that
        // turns a merge collision from a discovery into a prediction.
        val json = File(root, "build/reports/citations/inventory.json")
        CitationGuard.writeInventoryJson(root, pages(), json)
        println("machine-readable inventory: ${json.relativeTo(root).path}")
        assertTrue(json.isFile && json.length() > 0, "inventory JSON was not written")

        assertEquals(0, inv.broken.size, "unresolvable citations in the corpus")
        assertEquals(
            inv.total, inv.resolved,
            "every parsed citation must resolve by symbol; ${inv.total - inv.resolved} did not"
        )
        assertTrue(
            inv.uniqueAnchors >= MIN_UNIQUE_ANCHORS,
            "only ${inv.uniqueAnchors} of ${inv.total} corpus anchors are unique in their file " +
                "(floor $MIN_UNIQUE_ANCHORS). Lengthen anchors rather than lowering this."
        )
        assertTrue(
            inv.lowHeadroom.size <= MAX_FRAGILE,
            "${inv.lowHeadroom.size} citation(s) sit below ${CitationGuard.LOW_HEADROOM} lines of " +
                "headroom and will misresolve on the next sizeable insertion:\n" +
                inv.lowHeadroom.joinToString("\n") {
                    "  ${it.citation.describe()} — ${it.hits.size} occurrences, headroom ${it.headroom}"
                }
        )
        // The assertion under the advisory-drift number. Demoting line numbers to advisory swapped a
        // guard that went red for a number that could go unread; this is the part that stops it.
        // Magnitude, not count — see CitationGuard.MAX_ADVISORY_DRIFT for the argument.
        assertTrue(
            inv.worstAdvisoryDrift <= CitationGuard.MAX_ADVISORY_DRIFT,
            "a dev_doc line number is ${inv.worstAdvisoryDrift} lines wrong, over the ceiling of " +
                "${CitationGuard.MAX_ADVISORY_DRIFT} — far enough that a reader following it lands " +
                "somewhere else entirely. Worst: " +
                "${inv.worstAdvisoryDriftAt?.citation?.describe()} → actually at " +
                ":${inv.worstAdvisoryDriftAt?.line}. Fix every drifted number at once with\n" +
                "  FIELD_CITATIONS_WRITE=1 ./gradlew --no-daemon jvmTest --tests '*CitationContract*'"
        )
    }

    /**
     * Count — do not check, and do not quietly ignore — the bare `path:line` citations that carry no
     * anchor.
     *
     * There are hundreds of these across `dev_docs`, and they are unverifiable by construction. The
     * honest thing is to say how many there are on the pages that are otherwise guarded, so that a
     * page cannot look protected while most of its citations are not. No floor and no ceiling: this is
     * a measurement, and it is labelled as one.
     */
    @Test
    fun unguardedCitationsAreCountedNotIgnored() {
        println("--- unguarded (anchorless) citations on guarded pages ---")
        var total = 0
        GUARDED_PAGES.keys.sorted().forEach { rel ->
            val n = CitationGuard.unguarded(File(root, rel)).size
            total += n
            println("  %-45s %d".format(rel, n))
        }
        println("  total: $total")
        assertTrue(total >= 0, "unreachable; the count is a measurement, not a contract")
    }

    /**
     * Not a test: the mechanical refresh of advisory line numbers, gated behind an environment
     * variable so an ordinary build can never rewrite tracked files.
     *
     * ```
     * FIELD_CITATIONS_WRITE=1 ./gradlew --no-daemon jvmTest --tests '*CitationContract*'
     * ```
     *
     * Advisory drift is not a build failure by design; this is how it gets cleaned up without one.
     */
    @Test
    fun refreshAdvisoryLineNumbersWhenAsked() {
        if (System.getenv("FIELD_CITATIONS_WRITE") != "1") {
            val drift = CitationGuard.inventory(root, pages()).advisoryDrifted
            println(
                "advisory line numbers drifted: ${drift.size} " +
                    "(set FIELD_CITATIONS_WRITE=1 to rewrite them)"
            )
            drift.take(30).forEach {
                println("  ${it.citation.describe()} → actually at :${it.line}")
            }
            return
        }
        var changed = 0
        GUARDED_PAGES.keys.forEach { rel ->
            changed += CitationGuard.refreshAdvisoryLines(root, File(root, rel), rel)
        }
        println("refreshed $changed advisory line number(s); review the diff before committing")
    }
}
