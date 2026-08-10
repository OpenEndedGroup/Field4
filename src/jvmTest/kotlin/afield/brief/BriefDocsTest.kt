package afield.brief

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `dev_docs/brief_notation.md` held to the code.
 *
 * The page carries two kinds of load-bearing claim, and both rot in the way this repo keeps
 * rediscovering — quietly, while everything still builds.
 *
 * **The weight table is the specification.** Four words, four numbers. A reader tunes a brief against
 * that table, so if it disagrees with `BriefStrength` the page is not merely stale, it is wrong about
 * what a program means. Scraped and compared in both directions, with no count typed in.
 *
 * **The example statements are the syntax reference.** There are about thirty of them, typed by hand,
 * and until this test not one was parsed. They are compiled here against a synthetic preamble that
 * declares the elements they mention — which is shape 3 of `dev_docs/small_language_anatomy.md`'s
 * coverage-guard patterns ("execute every snippet on the page"), applied to a `dev_docs` page rather
 * than a public one because that is where this language's reference lives.
 *
 * What this cannot check is that the prose beside a statement describes what it does. Same limitation
 * `SmallLanguageAnatomyTest` states about itself, and worth restating.
 */
class BriefDocsTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    private val page: String by lazy {
        val f = File(repoRoot(), "dev_docs/brief_notation.md")
        assertTrue(f.isFile, "${f.path} is missing")
        f.readText()
    }

    @Test
    fun theWeightTableIsTheStrengthLadder() {
        // Rows of `| `word` | `1e3` | prose |`.
        // Scoped to its own section: the demo table further down is also `| `prefer` | `119.940` |`,
        // and a page-wide scrape happily reports that the ladder runs 119.94, 90, 60.06.
        val section = page.substringAfter("## Weight, which is the point").substringBefore("\n## ")
        val rows = Regex("""^\|\s*`(\w+)`\s*\|\s*`([0-9e.+-]+)`\s*\|""", RegexOption.MULTILINE)
            .findAll(section)
            .map { it.groupValues[1] to it.groupValues[2].toDouble() }
            .toList()
        assertTrue(rows.size >= 4, "the weight-table scrape looks broken — found $rows")
        val documented = rows.toMap()
        val actual = BriefStrength.entries.associate { it.word to it.weight }
        assertEquals(
            actual, documented,
            "dev_docs/brief_notation.md's weight table is the specification a reader tunes against. " +
                "It disagrees with BriefStrength, which means the page is wrong about what a program does."
        )
        // And in the order the ladder is declared, because the table is read as a ladder.
        assertEquals(
            BriefStrength.entries.map { it.word }, rows.map { it.first },
            "the table's rows must be in ascending strength order, like the enum"
        )
    }

    @Test
    fun everyExampleStatementOnThePageParses() {
        val statements = exampleStatements()
        assertTrue(
            statements.size >= 25,
            "the example scrape looks broken — found ${statements.size}: $statements"
        )
        val broken = mutableListOf<String>()
        statements.forEach { statement ->
            val diagnostics = checkBrief(PREAMBLE + "\n" + statement)
                .filter { it.severity == BriefSeverity.ERROR }
            if (diagnostics.isNotEmpty()) {
                broken += "$statement  ->  ${diagnostics.joinToString("; ") { it.format() }}"
            }
        }
        assertTrue(
            broken.isEmpty(),
            "dev_docs/brief_notation.md shows ${broken.size} statement(s) that do not parse. A syntax " +
                "reference nobody parses is a syntax reference that is wrong:\n" +
                broken.joinToString("\n") { "  $it" }
        )
    }

    @Test
    fun everyExampleStatementAlsoLowers() {
        // Parsing is not the claim; reaching a constraint is. A statement that resolves and then lowers
        // to nothing would satisfy the test above and still be a lie about the vocabulary.
        val broken = mutableListOf<String>()
        exampleStatements().forEach { statement ->
            val before = compileBrief(PREAMBLE).toGeometryModel().program.constraints.size
            val after = runCatching {
                compileBrief(PREAMBLE + "\n" + statement).toGeometryModel().program.constraints.size
            }.getOrElse {
                broken += "$statement  ->  ${it.message}"
                return@forEach
            }
            // `all loose` is the one statement that cannot raise the count: it adds a whole-cloud
            // WeakPosition, and `GeometryModelScope.build` then skips appending its automatic one
            // (`alreadyHasAllPointPrior`). The net is one constraint either way. That is correct, and it
            // is the reason this check is a count rather than a diff.
            if (after <= before && "all loose" !in statement) {
                broken += "$statement  ->  added no constraint"
            }
        }
        assertTrue(broken.isEmpty(), "these documented statements do not lower:\n" + broken.joinToString("\n") { "  $it" })
    }

    @Test
    fun theDocumentedRelationsCoverTheWholeVocabulary() {
        // The other direction: a verb with no example on the page is undocumented, whatever the tables
        // say. `at` is the one word that appears in both a declaration and a relation, so it is matched
        // by its whole phrase rather than its name.
        val shown = exampleStatements().joinToString(" ")
        val missing = (BriefVerbs.infix + BriefVerbs.prefix + BriefVerbs.functions + BriefVerbs.properties)
            .filterNot { verb -> Regex("""(^|[\s(.])${Regex.escape(verb)}([\s(.]|$)""").containsMatchIn(shown) }
            .sorted()
        assertTrue(
            missing.isEmpty(),
            "these relations have no worked example on dev_docs/brief_notation.md: $missing"
        )
    }

    /**
     * Every line of the page's two constraint blocks, split on the two-column layout the page uses.
     *
     * The page lays those blocks out in two columns for readability, which means one source line holds
     * two statements. Splitting on a run of two or more spaces before a strength word recovers them —
     * and doing that here rather than reformatting the page keeps the page readable, which is the only
     * reason it is in two columns.
     */
    private fun exampleStatements(): List<String> {
        val blocks = Regex("""```\n((?:(?:insist|prefer|whisper|demand)[^`]*?))```""")
            .findAll(page)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(blocks.size >= 2, "expected the equations and predicates blocks, found ${blocks.size}")
        return blocks
            .flatMap { it.lines() }
            .flatMap { line ->
                // A trailing parenthetical aside — "(also same-y/z/xy/xz/yz)" — is prose, not a statement.
                val cleaned = line.replace(Regex("""\s{2,}\(also[^)]*\)\s*$"""), "")
                Regex("""\s{3,}(?=(?:insist|prefer|whisper|demand)\b)""").split(cleaned)
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private companion object {
        /** Enough geometry that every documented statement has something real to talk about. */
        val PREAMBLE = """
            #brief
            point a at (0, 0, 0)
            point b at (4, 0, 0)
            point c at (4, 3, 0)
            point d at (0, 3, 0)
            point apex at (2, 1, 3)
            point west at (0, 0, 0)
            point east at (9, 0, 0)
            point post at (3, 0, 0)
            point left at (1, 1, 0)
            point right at (3, 1, 0)
            point mast0 at (5, 5, 0)
            point mast1 at (5, 5, 4)
            point rafter0 at (1, 6, 1)
            point rafter1 at (2, 7, 2)
            point w0 at (0, 9, 0)
            point w1 at (4, 9, 0)
            point e0 at (0, 12, 0)
            point e1 at (4, 12, 0)
            point s0 at (7, 0, 0)
            point s1 at (7, 4, 0)
            point s2 at (11, 4, 0)
            point s3 at (11, 0, 0)
            point m0 at (0, 5, 0)
            point m1 at (4, 5, 0)
            point m2 at (0, 5, 4)
            line spine from a to b
            line ridge from c to d
            line mast from mast0 to mast1
            line rafter from rafter0 to rafter1
            line eaves from a to c
            line westWall from w0 to w1
            line eastWall from e0 to e1
            plane floor through a b c
            plane mid through m0 m1 m2
            panel roof corners a b c d
            panel wide corners s0 s1 s2 s3
            panel small corners w0 w1 e1 e0
        """.trimIndent()
    }
}
