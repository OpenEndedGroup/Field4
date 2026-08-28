package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [importStatementFrom] — the rule that decides what `prop<T>(name)` writes into a box's
 * preamble, and the rule that made `checkCompletionCorpusFixtures` disagree with itself for three
 * commits running (`559348acc` removed nineteen `import afield.four.util.Window` lines, `10f3fbaf5` put
 * them back, `389e1d8ff` removed them again).
 *
 * None of the three was wrong. The rule is "emit an import only if exactly one importable class has this
 * simple name", the candidate list comes from a whole-classpath scan that includes the personal layer,
 * and the blacklist that filters it comes from `$user.dir/.field/fieldPreferencesJson`. Two people
 * running the same command against different classpaths got different fixtures.
 *
 * The candidate lists below are not invented: they are what the scan printed on 2026-08-16, from a
 * clean worktree and then from the same worktree with one `class Window` compiled into the personal
 * layer. See `dev_docs/HERMETIC_COMPLETION_INPUTS.md`.
 */
class ImportResolutionTest {

    /** Everything named `Window` on a clean checkout's classpath, verbatim from the harvest log. */
    private val cleanCheckout = listOf(
        "afield.four.util.Window",
        "java.awt.Window",
        "org.sqlite.Function\$Window",
    )

    /** The same, on a machine whose `src/personal` declares a `Window` — here `rectgame.Window`. */
    private val withPersonalLayer = cleanCheckout + "rectgame.Window"

    @Test
    fun aCleanCheckoutResolvesWindowToFieldsOwn() {
        assertEquals(
            "import afield.four.util.Window",
            importStatementFrom("Window", cleanCheckout, defaultImportClassBlacklist)
        )
    }

    /** `java.awt.Window` is only out of the way because `*awt*` is in the default blacklist... */
    @Test
    fun theAwtPatternIsWhatMakesTheCleanAnswerUnambiguous() {
        val withoutAwtPattern = defaultImportClassBlacklist.filterNot { it == "*awt*" }
        assertNull(importStatementFrom("Window", cleanCheckout, withoutAwtPattern))
    }

    /** ...and `org.sqlite.Function$Window` only because a nested name can never be imported bare. */
    @Test
    fun nestedClassesAreNeverCandidates() {
        assertTrue(cleanCheckout.any { '$' in it })
        assertNull(importStatementFrom("Window", listOf("org.sqlite.Function\$Window"), defaultImportClassBlacklist))
    }

    /**
     * The M9 divergence itself: one extra class, from code that is not in git, and nineteen preambles
     * silently lose a line. This is the assertion the three commits needed and did not have.
     */
    @Test
    fun onePersonalClassOfTheSameNameSuppressesTheImportEntirely() {
        assertEquals(
            "import afield.four.util.Window",
            importStatementFrom("Window", cleanCheckout, defaultImportClassBlacklist)
        )
        assertNull(importStatementFrom("Window", withPersonalLayer, defaultImportClassBlacklist))
    }

    /** `Renderer` survived all three commits because nothing else on the classpath is called that. */
    @Test
    fun anUncontestedNameIsUnaffected() {
        assertEquals(
            "import afield.four.Renderer",
            importStatementFrom("Renderer", listOf("afield.four.Renderer"), defaultImportClassBlacklist)
        )
    }

    @Test
    fun typesKotlinAlreadyImportsGetNoImportLine() {
        assertNull(importStatementFrom("Int", listOf("kotlin.Int"), defaultImportClassBlacklist))
        assertNull(importStatementFrom("StringBuilder", listOf("java.lang.StringBuilder"), defaultImportClassBlacklist))
    }

    @Test
    fun anUnknownNameGetsNoImportRatherThanAGuess() {
        assertNull(importStatementFrom("NoSuchTypeAnywhere", emptyList(), defaultImportClassBlacklist))
    }

    // ── the blast radius ────────────────────────────────────────────────────────────────────────────

    /**
     * `Window` was one collision that happened to be pinned by a fixture. This is how many there are.
     *
     * Every contested simple name is a type `prop<T>(name)` will declare a property of and then not
     * import, silently. Measured on a stock classpath with no personal layer, 2026-08-16:
     *
     * > 75,104 simple names; 40,031 resolvable, **11,315 contested (22.04%)**, 23,758 with nothing
     * > importable at all.
     *
     * So better than one importable type name in five already cannot be imported by `prop<T>`. That is
     * the size of the thing `Window` was one instance of.
     *
     * Floored rather than pinned: the exact number moves with every dependency bump, and "at least this
     * many" is the honest shape. The loose ceiling is there because a number that can only grow is not
     * a measurement — if this ever reads 40% the candidate filter has stopped filtering.
     */
    @Test
    fun aMeasurableShareOfEveryImportableNameIsAlreadyContested() {
        val census = ImportResolution.census(
            index = ImportResolution.index(),
            blacklist = defaultImportClassBlacklist,
        )
        println(
            "import census: ${census.simpleNames} simple names; ${census.resolvable} resolvable, " +
                "${census.ambiguous} contested (${"%.2f".format(census.ambiguousShare * 100)}%), " +
                "${census.unimportable} with nothing importable"
        )
        assertTrue(
            census.resolvable + census.ambiguous >= 10_000,
            "the scan found only ${census.resolvable + census.ambiguous} importable simple names, so " +
                "this is measuring a broken scan and not the classpath"
        )
        assertTrue(
            census.ambiguous >= 9_000,
            "expected at least 9000 contested simple names, found ${census.ambiguous} — if that dropped " +
                "legitimately, lower the floor and say why in the commit"
        )
        assertTrue(
            census.ambiguousShare < 0.40,
            "${"%.1f".format(census.ambiguousShare * 100)}% of importable names contested is not " +
                "plausible; the candidate filter has probably stopped filtering"
        )
        // A floor alone passes on junk. These are names Field itself uses; if the scan stops knowing
        // about them it has rotted, however healthy the count looks.
        for (sentinel in listOf("Window", "Renderer", "Box")) {
            assertTrue(
                ImportResolution.index()[sentinel] != null,
                "the census no longer sees `$sentinel`, so it is not scanning Field"
            )
        }
    }

    /**
     * The casualty list is empty exactly when there is no personal layer — and costs nothing to say so.
     *
     * On the machine this defect came from it is **not** empty: with that checkout's 829 top-level
     * class names on the classpath, `checkCompletionCorpusFixtures` reports 124 type names that a clean
     * checkout can import and that machine cannot — `Arrow`, `Cell`, `Color4`, `Float2`, `HalfEdge`,
     * `Kernel`, `LineSegment`, `GroupNode` among them. `Window` was one of the 124, and the only one
     * anybody noticed, because a fixture happened to pin it.
     *
     * That number cannot be asserted here, because it is a property of a gitignored directory that CI
     * does not have. What can be asserted is that the *reporting* of it is wired up and honest on a
     * clean checkout, which is where this runs.
     */
    @Test
    fun withNoPersonalLayerNothingIsTakenAway() {
        if (PersonalLayer.isPresent) return   // then the count is that machine's business, not CI's
        assertTrue(
            ImportResolution.personalLayerCasualties().isEmpty(),
            "no personal layer is present, so nothing can have been taken away — " +
                "found ${ImportResolution.personalLayerCasualties()}"
        )
        // ...and the two indexes are then literally the same object, which is the invariant that keeps
        // a clean checkout paying for one classpath scan rather than two.
        assertSame(
            hermeticIndex, classpathIndex,
            "with no personal layer the hermetic and machine indexes must be the same scan"
        )
    }

    // ── the fixture-side guard ──────────────────────────────────────────────────────────────────────
    // `checkCompletionCorpusFixtures` used to be able to say only "the fixture differs from the
    // harvest", which is symmetric and so cannot say which side is wrong. These cover the check that
    // can: a preamble declaring a property of a type it never brings into scope is wrong on its own.

    /** Verbatim from `tools/completion-corpus/boxes/spin_glass__sg_draw.preamble.kt` at `389e1d8ff`. */
    private fun preamble(vararg imports: String) = """
        ${imports.joinToString("\n        ")}
        var Box.sgL by Inheritance<Int>() /**/
        var Box.sgSpins by Inheritance<FrameBufferGroup>() /**/
        var Box.window by Inheritance<Window>() /**/
        val Once.window by Onceing<Window>() /**/
        var Box.renderer by Inheritance<Renderer>() /**/
    """.trimIndent()

    @Test
    fun aPreambleThatImportsWhatItDeclaresIsClean() {
        assertEquals(
            emptyList(),
            CompletionCorpus.typesDeclaredWithoutAnImport(
                preamble(
                    "import afield.four.Renderer",
                    "import afield.four.util.Window",
                    "import afield.four.buffers.FrameBufferGroup",
                )
            )
        )
    }

    /** The state `389e1d8ff` left nineteen fixtures in, caught without comparing to anything. */
    @Test
    fun aDeclarationWhoseImportWasDeletedIsCaught() {
        assertEquals(
            listOf("Window"),
            CompletionCorpus.typesDeclaredWithoutAnImport(
                preamble("import afield.four.Renderer", "import afield.four.buffers.FrameBufferGroup")
            )
        )
    }

    @Test
    fun aFullyQualifiedPropertyTypeNeedsNoImport() {
        assertEquals(
            emptyList(),
            CompletionCorpus.typesDeclaredWithoutAnImport(
                "var Box.browserControl by Inheritance<afield.four.browser.PuppetBrowser>() /**/"
            )
        )
    }

    /** `Int` needs no import, so its absence must not be reported — the guard has to know that. */
    @Test
    fun defaultImportedTypesAreNotReportedMissing() {
        assertTrue(
            "Int" !in CompletionCorpus.typesDeclaredWithoutAnImport(
                preamble("import afield.four.Renderer", "import afield.four.buffers.FrameBufferGroup")
            )
        )
    }

    /**
     * The two modes are a property of [ImportResolution], not of the caller, so that a harvest cannot
     * half-switch: [ImportResolution.blacklist] must ignore the machine's preferences in hermetic mode
     * even when those preferences have been loaded.
     */
    @Test
    fun hermeticModeUsesTheBuiltInBlacklistWhateverTheMachineSays() {
        val was = ImportResolution.hermetic
        try {
            ImportResolution.hermetic = true
            assertEquals(defaultImportClassBlacklist, ImportResolution.blacklist())
            assertTrue(ImportResolution.describe().startsWith("hermetic"))
            ImportResolution.hermetic = false
            assertTrue(ImportResolution.describe().startsWith("machine"))
        } finally {
            ImportResolution.hermetic = was
        }
    }
}
