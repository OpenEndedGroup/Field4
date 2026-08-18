package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `@CommonMembers` prior needs a *qualified* receiver name, and only one of the two completion
 * backends hands one over. `AaCompletionClient` forwards `receiverQualifiedName` on every row; the K1
 * delegate forwards no receiver at all, so `CommonMemberPrior.rankOf(null, name)` returned 0 and the
 * prior contributed nothing on the K1 path.
 *
 * That path is not a harness artefact. K1 serves the first `ctrl-space` after launch — measured on
 * 2026-08-17 as two consecutive completions on one caret, `backend=K1 (AA→K1: empty: session building
 * (warming up; using K1 meanwhile))` and then `backend=AA … 1616ms (44 candidates)` — so the popup a
 * person meets first is exactly the one that had no prior.
 *
 * `FieldSuggestPresentationTest`'s rank cases cannot catch this: they replay *recorded sidecar payloads*,
 * which by construction always carry the receiver. These tests measure the resolution instead, from the
 * source text a K1 completion actually arrives with.
 */
class CompletionPriorReceiverTest {

    /** Caret marker, matching the other completion tests. */
    private val caret = "‸"

    /**
     * Every candidate the classpath scan would return, stubbed. Injected rather than scanned: the real
     * lookup walks the whole classpath and its answer depends on the personal layer and the import
     * blacklist, neither of which is in git (see `ImportResolution` / `dev_docs/HERMETIC_COMPLETION_INPUTS.md`).
     */
    private fun resolving(vararg candidates: Pair<String, List<String>>): (String) -> List<String>? {
        val index = candidates.toMap()
        return { name -> index[name] }
    }

    private val fLineOnly = resolving("FLine" to listOf("afield.graphics.FLine"))

    private fun receiverAt(
        sourceWithCaret: String,
        resolveSimpleName: (String) -> List<String>? = fLineOnly,
    ): String? {
        val position = sourceWithCaret.indexOf(caret)
        require(position >= 0) { "Missing $caret" }
        return KotlinCompletionSupport.receiverQualifiedNameAtCompletion(
            box = null,
            allCode = sourceWithCaret.replace(caret, ""),
            position = position,
            resolveSimpleName = resolveSimpleName,
        )
    }

    /** The reported case, verbatim: `val f = FLine()` and a bare `f.`, served by K1. */
    @Test
    fun `a constructor call names the receiver for a K1 dot completion`() {
        assertEquals("afield.graphics.FLine", receiverAt("val f = FLine()\nf.$caret"))
    }

    @Test
    fun `a declared type names the receiver too`() {
        assertEquals("afield.graphics.FLine", receiverAt("val f: FLine = TODO()\nf.$caret"))
    }

    /**
     * A qualified type in the source is the answer already, so the classpath is never consulted — the one
     * case where the machine-dependent lookup cannot influence the result.
     */
    @Test
    fun `a qualified declared type is used as written, without a classpath lookup`() {
        var asked = 0
        val counting: (String) -> List<String>? = { asked++; null }
        assertEquals(
            "afield.graphics.FLine",
            receiverAt("val f: afield.graphics.FLine = TODO()\nf.$caret", counting)
        )
        assertEquals(0, asked, "a qualified name needs no resolution")
    }

    /**
     * Two classes of the same name and the prior stays out of it. Attaching `FLine`'s list to some other
     * `FLine`'s members would be worse than having no prior: it would promote names the type never
     * mentioned, and it would do it silently.
     */
    @Test
    fun `a contested simple name resolves to nothing rather than to a guess`() {
        val contested = resolving("FLine" to listOf("afield.graphics.FLine", "elsewhere.FLine"))
        assertNull(receiverAt("val f = FLine()\nf.$caret", contested))
    }

    @Test
    fun `a name completion has no receiver`() {
        assertNull(receiverAt("val f = FLine()\nf$caret"))
    }

    @Test
    fun `an unknown local has no receiver`() {
        assertNull(receiverAt("q.$caret"))
    }

    /** A safe call is the same completion, so it resolves the same way. */
    @Test
    fun `a safe dot resolves like a dot`() {
        assertEquals("afield.graphics.FLine", receiverAt("val f: FLine? = null\nf?.$caret"))
    }

    /**
     * What the resolution is *for*: with it, the popup's prior applies on the K1 path; without it,
     * `rankOf(null, …)` is 0 and every row on a K1 dot panel ranks as if `FLine` had said nothing.
     */
    @Test
    fun `the prior applies to a K1 dot completion once the receiver is resolved`() {
        val receiver = receiverAt("val f = FLine()\nf.$caret")
        assertEquals(0, CommonMemberPrior.rankOf(null, "moveTo"), "the K1 path used to arrive here")
        assertTrue(
            CommonMemberPrior.rankOf(receiver, "moveTo") > 0,
            "FLine lists moveTo in @CommonMembers, so a resolved receiver has to produce a rank"
        )
        assertEquals(
            0,
            CommonMemberPrior.rankOf(receiver, "breakNext"),
            "and a member the type says nothing about still gets nothing"
        )
    }
}
