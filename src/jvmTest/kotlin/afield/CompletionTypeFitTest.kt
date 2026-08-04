package afield

import kotlin.script.experimental.api.SourceCodeCompletionVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [CompletionTypeFit] — the server's decision about what to do with a *type fit*: boost
 * it, or (at an untyped caret) show nothing else. This is the visible half of expected-type completion:
 * it is what turns `d.scheme = ‸` into a three-row panel of enum entries instead of every name in scope.
 *
 * Pure list logic, so no scripting runtime / sidecar is needed here. The candidate *generation* side
 * lives in the AA sidecar and is covered by the `expected#*` fixtures in
 * `tools/completion-corpus/name-fixtures.tsv` (via `tools/completion_diff.py`) and, live end-to-end,
 * by `CompletionAaE2EProbe`.
 */
class CompletionTypeFitTest {

    private fun row(name: String, boosted: Boolean = false) = Metadata(
        icon = "val", tail = "Scheme", text = name, displayText = name,
        contextScore = if (boosted) InfixCompletion.OPERAND_MATCH_BOOST else 0
    )

    /** Three enum entries that fit, plus unrelated names that don't — the shape of `d.scheme = ‸`. */
    private fun enumPanel() = listOf(
        row("UD", boosted = true), row("SD_BASIC", boosted = true), row("SD_COLLAPSED", boosted = true),
        row("layerGap"), row("wordFont"), row("svg"),
    )

    @Test
    fun untypedCaretWithAnExpectedTypeShowsOnlyTheFits() {
        val out = CompletionTypeFit.collapse(enumPanel(), "", expectedTypeKnown = true, infixTypeKnown = false)
        assertEquals(listOf("UD", "SD_BASIC", "SD_COLLAPSED"), out.map { it.text })
    }

    @Test
    fun oneKeystrokeRestoresEverything() {
        val all = enumPanel()
        val out = CompletionTypeFit.collapse(all, "S", expectedTypeKnown = true, infixTypeKnown = false)
        // Nothing is permanently hidden: the full list comes back, fits merely boosted.
        assertSame(all, out)
        assertEquals(3, out.count { it.contextScore > 0 })
    }

    @Test
    fun twoFitsAreEnoughForAnExpectedTypeBecauseBooleanIsTwoRows() {
        val boolPanel = listOf(row("true", boosted = true), row("false", boosted = true), row("layerGap"))
        val out = CompletionTypeFit.collapse(boolPanel, "", expectedTypeKnown = true, infixTypeKnown = false)
        assertEquals(listOf("true", "false"), out.map { it.text })
    }

    @Test
    fun aLoneFitNeverCollapsesThePanel() {
        // A bad type resolution must not be able to reduce completion to a single row.
        val panel = listOf(row("UD", boosted = true), row("layerGap"), row("wordFont"))
        assertSame(panel, CompletionTypeFit.collapse(panel, "", expectedTypeKnown = true, infixTypeKnown = false))
    }

    @Test
    fun theInfixPathKeepsItsWiderMargin() {
        // Infix operand types are inferred from indexed source text rather than resolved, so two fits
        // do NOT collapse there — three do. This is the threshold the infix path shipped with.
        val two = listOf(row("a", boosted = true), row("b", boosted = true), row("x"))
        assertSame(two, CompletionTypeFit.collapse(two, "", expectedTypeKnown = false, infixTypeKnown = true))
        val three = two + row("c", boosted = true)
        assertEquals(listOf("a", "b", "c"), CompletionTypeFit.collapse(three, "", false, infixTypeKnown = true).map { it.text })
    }

    @Test
    fun withNoTypeSignalTheListIsUntouched() {
        // The K1 fallback path: no expected-type flag, no infix site. Byte-for-byte the old behaviour,
        // even if some candidate carries a boost from elsewhere.
        val panel = enumPanel()
        assertSame(panel, CompletionTypeFit.collapse(panel, "", expectedTypeKnown = false, infixTypeKnown = false))
    }

    @Test
    fun expectedTypeKnownReadsTheSidecarFlag() {
        fun variant(fit: Boolean) = SourceCodeCompletionVariant(
            text = "BLEND", displayText = "BLEND", tail = "FabricateAlphaMode", icon = "val"
        ).apply { if (fit) extraInfo = mapOf(AaCompletionClient.EXPECTED_TYPE_MATCH to "true") }

        assertTrue(CompletionTypeFit.expectedTypeKnown(listOf(variant(false), variant(true))))
        assertFalse(CompletionTypeFit.expectedTypeKnown(listOf(variant(false))))
        assertFalse(CompletionTypeFit.expectedTypeKnown(emptyList()))
    }
}
