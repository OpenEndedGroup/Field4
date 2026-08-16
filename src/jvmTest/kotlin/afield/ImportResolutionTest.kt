package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
