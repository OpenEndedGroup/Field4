package afield.graphics

import afield.docs.CitationGuard
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `file:line` citations rot the moment anyone edits above them, and the interior-`Close` work put
 * roughly forty of them into the sources, the tests and `dev_docs/fline_close_nodes.md`. This is
 * the guard: every cited site must still contain the thing it was cited for.
 *
 * Three tests, and the division of labour between them is the point.
 * [everyCitedSiteStillSaysWhatItWasCitedFor] anchors on a token and finds it **anywhere in the file**,
 * so moving a function is a non-event and only renaming or deleting one fails.
 * [noAnchorIsTooVagueToSurviveAnEdit] keeps nearest-occurrence resolution from becoming fragile.
 * [theDevDocOnlyCitesSitesThisTestChecks] stops the doc citing a site nothing here checks.
 *
 * **Migrated 2026-08-12.** This used to accept the token within one line either side, and to require
 * the doc's numbers to equal the table's *exactly*. Both were dropped for the reason set out in
 * `dev_docs/citation_contracts.md`: a line window makes a guard reach into every file any branch
 * happens to touch, so it can never be path-disjoint from anybody, and with 52 citations across 8
 * source files this one had a wide reach. The anchor is now enforced absolutely and source motion is
 * not a failure.
 *
 * The remaining weakness, unchanged: this table lives in the *test*, not in the page it guards, so the
 * two can still disagree about what is worth citing, and `FIELD_CITATIONS_WRITE=1` cannot refresh
 * these numbers the way it refreshes a page's. Moving the table into the doc would make
 * `fline_close_nodes.md` an ordinary [afield.docs.CitationContractTest] page and is the obvious next
 * step; it is 52 rows of restructuring and was not attempted tonight.
 *
 * When one goes red, re-point the citation at what the code says now; do not weaken the anchor.
 */
class CloseNodeCitationDriftTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    /** (path, advisory line, a token that must still occur somewhere in that file). */
    private val citations = listOf(
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 188, "val toOrNull: Vec3? get() ="),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 219, "fun endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 237, "val currentPoint"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 412, "byThickening"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 431, "removeInstruction"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 813, "if (r == null) it.toOrNull?.let"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 1090, "plusAssign(other: FLine)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 1369, "'z' -> f.close()"),

        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 590, "val points = this.nodes.indices.map { Vec3(endPointOf(it)) }"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 674, "CubicSegment3(endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 831, "val p = endPointOf(it); Vec2(p.x, p.y)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1006, "byUnionWith"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1042, "byExclusiveOrWith"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1110, "endPointOf(i)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1161, "is FLine.Close"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1192, "Degenerate case"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1212, "if (node is FLine.Close) return@forEach"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1301, "endPointOf(i - 1)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2130, "result.endPointOf(i - 1)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2198, "pieces()"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2200, "it.byDashing(dashLength, gapLength, resolution)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2747, "endPointOf(nodes.size - 2)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2831, "prevPrev = endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2923, "endPointOf(nodes.size - 2)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 4231, "endPointOf(nodes.size - 2)"),

        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 874, "constructor(f: FLine, index: Int)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 1920, "fun flineToJavaShape"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 1960, "n is FLine.Close && hasCurrentPoint) p.closePath()"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2205, "SEG_CLOSE"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2269, "SEG_CLOSE"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2556, "fun intersect(a: FLine, b: FLine)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2605, "fun intersectX"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2636, "fun intersectY"),

        Triple("src/jvmMain/kotlin/afield/stdlib/StandardDrawing.kt", 396, "fun arrow"),
        Triple("src/jvmMain/kotlin/afield/stdlib/StandardDrawing.kt", 426, "fun appendHead"),
        Triple("src/jvmMain/kotlin/afield/stdlib/StandardDrawing.kt", 439, "target.close()"),

        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 341, "fun planeFigureHtml"),
        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 351, "fitInto"),
        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 370, "drawToSVG"),
        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 463, "showPlaneGrid"),

        Triple("src/jvmMain/kotlin/afield/graphics/someSkiko.kt", 339, "is FLine.LineTo"),
        Triple("src/jvmMain/kotlin/afield/graphics/someSkiko.kt", 346, "is FLine.CubicTo"),

        Triple("src/jvmMain/kotlin/afield/graphics/FLineThickening.kt", 154, "byJavaOffsetting"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineThickening.kt", 160, "flineToJavaShape_notThickened"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineThickening.kt", 384, "flineToJavaShape_notThickened"),

        Triple("src/jvmMain/kotlin/afield/four/physics/Dyn4jScene.kt", 239, "flattenClosedFLineLoops"),
        Triple("src/jvmMain/kotlin/afield/four/physics/Dyn4jScene.kt", 244, "flineToJavaShape_notThickened"),
        Triple("src/jvmMain/kotlin/afield/four/physics/Dyn4jScene.kt", 307, "flush(requireClosed = false)"),

        Triple("src/jvmTest/kotlin/afield/graphics/ClosedLineNodeAccessTest.kt", 74, "vec(0f, 100f, 0f)"),

        Triple("src/jvmMain/kotlin/afield/examples/graphics/FLineGeometryExamples.kt", 789, "insideContains"),
        Triple("src/jvmMain/kotlin/afield/examples/graphics/FLineGeometryExamples.kt", 846, "byReversing"),
        Triple("src/jvmMain/kotlin/afield/examples/graphics/FLineGeometryExamples.kt", 1419, "windingNumber"),
    )

    /** The table's rows, as [CitationGuard] citations. */
    private fun asCitations() = citations.map { (path, line, token) ->
        CitationGuard.Citation(
            page = "CloseNodeCitationDriftTest", pageLine = 0,
            path = path, advisoryLine = line, anchor = token,
            form = CitationGuard.Form.LEDGER_ROW
        )
    }

    /**
     * Every cited site still contains the thing it was cited for — resolved by [CitationGuard]
     * searching the file, not by looking within a window of the line number.
     *
     * Migrated 2026-08-12. This was the last line window in the repo, and it carried the same defect
     * as the ±2 one on `SmallLanguageAnatomyTest`: with 52 citations across 8 source files, any branch
     * touching `FLine.kt`, `FLineGeometry.kt` or `FLinesAndJavaShapes.kt` was editing an input to this
     * test, so it could not be made path-disjoint from anybody. Now a moved function is a non-event and
     * only a renamed or deleted one fails. See `dev_docs/citation_contracts.md`.
     */
    @Test
    fun everyCitedSiteStillSaysWhatItWasCitedFor() {
        val root = repoRoot()
        val broken = asCitations()
            .map { CitationGuard.resolve(root, it) }
            .filterIsInstance<CitationGuard.Resolution.Broken>()

        assertTrue(
            broken.isEmpty(),
            "${broken.size} citation(s) no longer name anything that exists. Re-point them here AND " +
                "in dev_docs/fline_close_nodes.md, which quotes the same sites:\n" +
                broken.joinToString("\n") { "  ${it.citation.path}: ${it.reason}" }
        )
    }

    /**
     * No anchor may be so vague that a couple of inserted lines would resolve it to the wrong place.
     *
     * Symbol resolution is only as good as the symbol. Migrating this class exposed how weak some of
     * these tokens were: `}` (684 occurrences in `FLineGeometry.kt`), bare `toOrNull` (7), bare
     * `endPointOf` (26). Those resolved correctly only because the advisory line happened to sit
     * nearest the right one — headroom 0 or 1, meaning a single inserted line would have silently
     * moved the citation to a different site. Silently is the bad part: the old window would at least
     * have gone red.
     *
     * Seven were lengthened until unique. This keeps the rest from creeping back.
     */
    @Test
    fun noAnchorIsTooVagueToSurviveAnEdit() {
        val root = repoRoot()
        val fragile = asCitations().mapNotNull { c ->
            val r = CitationGuard.resolve(root, c) as? CitationGuard.Resolution.Resolved
                ?: return@mapNotNull null
            if (r.headroom <= CitationGuard.LOW_HEADROOM)
                "  ${c.path.substringAfterLast('/')}:${c.advisoryLine} — `${c.anchor}` occurs " +
                    "${r.hits.size}x, headroom ${r.headroom}"
            else null
        }
        assertTrue(
            fragile.isEmpty(),
            "${fragile.size} anchor(s) would resolve elsewhere after " +
                "${CitationGuard.LOW_HEADROOM} inserted lines. Lengthen them until unique:\n" +
                fragile.joinToString("\n")
        )
    }

    /**
     * The doc may only cite sites this table knows about — so it cannot carry a citation that nothing
     * is checking.
     *
     * It used to require the doc's number to equal the table's number *exactly*, which meant the two
     * had to be edited in lockstep and made the doc a second place a moved function could go red. Now
     * a doc citation matches a table row when the file matches and the two advisory ledgers are
     * within 40 lines of each other — the identity of a citation being its file and its anchor, not
     * its line. The exactness that mattered (no unchecked citations) is kept; the
     * exactness that only created work is not. The generous 40-line pairing window below compares two
     * stale advisory ledgers to each other; it never compares either one with moving source.
     */
    @Test
    fun theDevDocOnlyCitesSitesThisTestChecks() {
        val root = repoRoot()
        val doc = File(root, "dev_docs/fline_close_nodes.md")
        assertTrue(doc.isFile, "no dev_docs/fline_close_nodes.md")

        // Citations into `src/personal`, which is gitignored and absent from this worktree
        // altogether. They cannot be pinned from here, so they are declared instead: this list is
        // the admission that CI does not verify them, and it is short on purpose.
        val outsideThisRepo = setOf(
            "flinesAndJTS.kt" to 69,    // src/personal/kotlin/euclid — the deprecated JTS shim
            "Flines.kt" to 28,          // src/personal/kotlin/sentences — how it builds its curves
            "FLineLinearizer.kt" to 91, // src/personal/kotlin/organic — the adjacent finding, not fixed
        )

        val known = citations.map { (p, l, _) -> p.substringAfterLast('/') to l } +
            outsideThisRepo.toList()
        // `Foo.kt:123` and `Foo.kt:123-456`; a range is matched at its first line
        val pattern = Regex("""([A-Za-z_0-9]+\.kt):(\d+)(?:-\d+)?""")
        val unchecked = pattern.findAll(doc.readText())
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .distinct()
            .filter { (file, line) ->
                known.none { (k, kl) ->
                    k == file && kotlin.math.abs(kl - line) <= 40
                }
            }
            .toList()

        assertTrue(
            unchecked.isEmpty(),
            "fline_close_nodes.md cites ${unchecked.size} site(s) that CloseNodeCitationDriftTest " +
                "does not check, so they can rot unnoticed: " +
                unchecked.joinToString(", ") { "${it.first}:${it.second}" }
        )
    }
}
