package afield.graphics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `file:line` citations rot the moment anyone edits above them, and the interior-`Close` work put
 * roughly forty of them into the sources, the tests and `dev_docs/fline_close_nodes.md`. This is
 * the guard: every cited site must still contain the thing it was cited for.
 *
 * Two tests, and the division of labour between them is the point.
 * [everyCitedSiteStillSaysWhatItWasCitedFor] is deliberately *loose* — it anchors on a token and
 * accepts it within one line either side, so reformatting inside a function does not trip it while
 * moving a function does. [theDevDocOnlyCitesSitesThisTestChecks] is *exact*: it requires every
 * `Foo.kt:NNN` in the dev doc to be one of the numbers below, so the doc and the table cannot drift
 * apart even by one line.
 *
 * When either goes red, the fix is to recompute the line, not to loosen the anchor.
 */
class CloseNodeCitationDriftTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    /** (path, line, a token the cited line — or the two lines around it — must contain). */
    private val citations = listOf(
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 188, "toOrNull"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 219, "fun endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 237, "val currentPoint"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 412, "byThickening"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 431, "removeInstruction"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 813, "toOrNull"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 1161, "plusAssign(other: FLine)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLine.kt", 1440, "'z' -> f.close()"),

        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 590, "endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 674, "CubicSegment3(endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 831, "endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1006, "byUnionWith"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1042, "byExclusiveOrWith"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1110, "endPointOf(i)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1161, "is FLine.Close"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1192, "Degenerate case"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1212, "is FLine.Close"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 1301, "endPointOf(i - 1)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2130, "result.endPointOf(i - 1)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2198, "pieces()"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2202, "}"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2747, "endPointOf(nodes.size - 2)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2831, "prevPrev = endPointOf"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 2923, "endPointOf(nodes.size - 2)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt", 4231, "endPointOf(nodes.size - 2)"),

        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 874, "constructor(f: FLine, index: Int)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 1920, "fun flineToJavaShape"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 1960, "closePath()"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2205, "SEG_CLOSE"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2269, "SEG_CLOSE"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2556, "fun intersect(a: FLine, b: FLine)"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2605, "fun intersectX"),
        Triple("src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt", 2636, "fun intersectY"),

        Triple("src/jvmMain/kotlin/afield/stdlib/StandardDrawing.kt", 279, "fun arrow"),
        Triple("src/jvmMain/kotlin/afield/stdlib/StandardDrawing.kt", 309, "fun appendHead"),
        Triple("src/jvmMain/kotlin/afield/stdlib/StandardDrawing.kt", 322, "target.close()"),

        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 341, "fun planeFigureHtml"),
        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 351, "fitInto"),
        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 370, "drawToSVG"),
        Triple("src/jvmMain/kotlin/afield/stdlib/spatial_field.kt", 463, "showPlaneGrid"),

        Triple("src/jvmMain/kotlin/afield/graphics/someSkiko.kt", 332, "is FLine.LineTo"),
        Triple("src/jvmMain/kotlin/afield/graphics/someSkiko.kt", 339, "is FLine.CubicTo"),

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

    @Test
    fun everyCitedSiteStillSaysWhatItWasCitedFor() {
        val root = repoRoot()
        val drift = mutableListOf<String>()
        for ((path, line, token) in citations) {
            val f = File(root, path)
            if (!f.isFile) {
                drift += "$path: missing file"
                continue
            }
            val lines = f.readLines()
            if (line !in 1..lines.size) {
                drift += "$path:$line is past the end of the file (${lines.size} lines)"
                continue
            }
            // the line, plus one either side, so a reformat does not count as drift
            val window = lines.subList((line - 2).coerceAtLeast(0), (line + 1).coerceAtMost(lines.size))
                .joinToString("\n")
            if (!window.contains(token)) {
                drift += "$path:$line no longer contains ${'"'}$token${'"'} — it says: ${lines[line - 1].trim()}"
            }
        }
        assertTrue(
            drift.isEmpty(),
            "${drift.size} citation(s) have drifted. Recompute the line numbers here AND in " +
                "dev_docs/fline_close_nodes.md, which quotes the same sites:\n" + drift.joinToString("\n")
        )
    }

    /**
     * And the doc's numbers must be the same numbers. Parses every `Foo.kt:NNN` out of
     * `fline_close_nodes.md` and requires it to appear in the table above — so the doc cannot cite a
     * site that nothing is checking.
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

        val known = citations.map { (p, l, _) -> p.substringAfterLast('/') to l }.toSet() + outsideThisRepo
        // `Foo.kt:123` and `Foo.kt:123-456`; a range is checked at its first line
        val pattern = Regex("""([A-Za-z_0-9]+\.kt):(\d+)(?:-\d+)?""")
        val unchecked = pattern.findAll(doc.readText())
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .distinct()
            .filter { it !in known }
            .toList()

        assertTrue(
            unchecked.isEmpty(),
            "fline_close_nodes.md cites ${unchecked.size} site(s) that CloseNodeCitationDriftTest " +
                "does not check, so they can rot unnoticed: " +
                unchecked.joinToString(", ") { "${it.first}:${it.second}" }
        )
    }
}
