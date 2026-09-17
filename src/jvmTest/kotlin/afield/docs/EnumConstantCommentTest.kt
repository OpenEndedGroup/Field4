package afield.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The published constants of an `enum class` are its constants** — for an enum whose constants
 * carry their own KDoc.
 *
 * [StdlibReferenceGenerator.enumConstantsAt] recovers an enum's constants by scanning source
 * characters and splitting the body on depth-zero commas. Until 2026-09-17 that scan removed `//`
 * comments and nothing else, so a KDoc block on a constant stayed in the body and its prose was
 * split on its own commas — and the leading word of each fragment was published as a constant name. `reference/languages.md` carried `Constants: which, because` and
 * `Constants: or, and, counted, when`; `reference/graphics.md` carried
 * `Constants: matching, MIX, SMOOTHSTEP`, which is the worst kind, because two of the three are
 * real and the row looks plausible.
 *
 * That is not a truncation a reader can see. It is a list of lowercase English words standing
 * exactly where an API belongs, on a page whose entire promise is that it mirrors the source.
 *
 * These are **fixture** declarations rather than assertions about live source, for the same reason
 * `SignatureLiteralTest` uses fixtures: a real enum's constants change for unrelated reasons, and
 * the hazard is in the scanner, so the scanner is what is pinned. Each case is a comment shape an
 * enum body can plausibly contain.
 *
 * **Deliberately not covered.** This does not run the generator end to end; `checkCodebaseReference`
 * regenerates and diffs the committed pages and is what catches staleness for any other reason. Nor
 * is [StdlibReferenceGenerator.stripComments] string-literal aware — see its own KDoc for why that
 * is a decision rather than an omission.
 *
 * Comment markers appear below only inside string fixtures, never in this KDoc: a literal close
 * marker inside a KDoc block ends the block early and the rest of the file parses as declarations
 * inside a comment. That cost one compile while this test was being written.
 */
class EnumConstantCommentTest {

    private fun constantsOf(vararg lines: String): List<String> =
        StdlibReferenceGenerator.enumConstantsAt(lines.toList(), 1)

    @Test
    fun `KDoc on each constant does not become a constant`() {
        val constants = constantsOf(
            "enum class AdSurrogateFamily {",
            "    /**",
            "     * The quantisers: `floor` and `ceil` (jumps at every integer), `trunc` (every integer",
            "     * except zero), and `round`/`roundEven` (jumps at the half-integers).",
            "     */",
            "    STAIRCASE,",
            "",
            "    /** `step(edge, x)`: one jump of height 1, and the partial is `+K` in x, `-K` in edge. */",
            "    STEP,",
            "",
            "    /** `sign(x)`: one jump at the origin, of height **2** — it goes from `-1` to `+1`. */",
            "    SIGN",
            "}"
        )
        assertEquals(listOf("STAIRCASE", "STEP", "SIGN"), constants)
        // The counterfactual, floored at a name rather than at a count: these are the words the
        // unfixed scanner actually published for this very enum, so a regression reproduces them.
        val prose = listOf("and", "so", "since", "because", "of", "the", "every", "one", "it", "goes")
        val leaked = constants.filter { it in prose }
        assertTrue(
            leaked.isEmpty(),
            "$leaked came out of the KDoc rather than out of the source — the comment strip is gone"
        )
        // And non-degeneracy: an enum that produced nothing would pass the assertion above for free.
        assertTrue(constants.size == 3, "expected three constants, got ${constants.size}: $constants")
    }

    @Test
    fun `a block comment opened on one line and closed on another is still one comment`() {
        assertEquals(
            listOf("BUS", "BIT"),
            constantsOf(
                "enum class Width {",
                "    /* a bus is many wires,",
                "       a bit is one, LOW, HIGH */",
                "    BUS,",
                "    BIT,",
                "}"
            ),
            "a multi-line /* … */ must not contribute LOW or HIGH"
        )
    }

    @Test
    fun `the shapes that already worked still work`() {
        assertEquals(
            listOf("HANN", "HAMMING", "BLACKMAN"),
            constantsOf("enum class WindowType { HANN, HAMMING(2), BLACKMAN, }"),
            "arguments and a trailing comma"
        )
        assertEquals(
            listOf("ADD", "SUB"),
            constantsOf(
                "enum class Op {",
                "    ADD, // addition, which is commutative",
                "    SUB; // subtraction, which is not",
                "",
                "    fun apply(a: Double, b: Double) = a",
                "}"
            ),
            "line comments, and a body after the `;`"
        )
        assertEquals(
            listOf("ONE", "TWO"),
            constantsOf(
                "enum class Inline {",
                "    ONE, /* one */ TWO",
                "}"
            ),
            "an inline block comment between two constants"
        )
        assertEquals(
            emptyList(),
            constantsOf("enum class Empty"),
            "an enum with no body at all"
        )
    }

    @Test
    fun `stripComments reports whether a block comment is still open`() {
        assertEquals("code " to false, StdlibReferenceGenerator.stripComments("code // trailing", false))
        assertEquals("code " to true, StdlibReferenceGenerator.stripComments("code /** opened", false))
        assertEquals(" closed" to false, StdlibReferenceGenerator.stripComments("still */ closed", true))
        assertEquals("" to true, StdlibReferenceGenerator.stripComments("   * a KDoc middle line", true))
        assertEquals("a  b" to false, StdlibReferenceGenerator.stripComments("a /* mid */ b", false))
        // A `//` INSIDE a block comment is not a line comment, so it must not end the strip early
        // and leave the rest of the block in the body.
        assertEquals("" to true, StdlibReferenceGenerator.stripComments(" * see // this", true))
    }
}
