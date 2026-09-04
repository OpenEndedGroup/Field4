package afield.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **published default value is the one in the code** — for defaults that spell a comment marker
 * inside a string literal.
 *
 * [StdlibReferenceGenerator.signatureAt] recovers a declaration's header by scanning source
 * characters, and that scan has to skip comments: a primary constructor that documents its own
 * properties otherwise publishes its KDoc inside its rendered signature. Until 2026-09-04 the scan
 * looked for comment markers without knowing where string literals were, so a default whose text
 * contained one was read as code. `keyFilesMatching`'s recursive-json glob — a double wildcard, a
 * slash, then a wildcard and an extension — opened a block comment mid-parameter-list, and
 * `reference/visualization.md` published the default as a bare double wildcard: not a truncation a
 * reader can see, but a different glob, and one that matches different files.
 *
 * These are fixture declarations rather than assertions about live source precisely because a real
 * default can be changed for unrelated reasons; the hazard is in the scanner, so the scanner is what
 * is pinned. Each case is a marker a default value can plausibly contain: a glob, a URL, a bracket,
 * a quote inside a char literal.
 *
 * **Deliberately not covered.** This does not run the generator end to end or touch the committed
 * pages; `checkCodebaseReference` regenerates and diffs them, and is what catches a page that has
 * gone stale for any other reason. Nor does it cover [StdlibReferenceGenerator.highlightSignature]
 * as a highlighter — only that it does not itself drop literal text, since it is the layer that
 * emits the entities the published page shows.
 */
class SignatureLiteralTest {

    private fun signatureOf(vararg lines: String): String =
        StdlibReferenceGenerator.signatureAt(lines.toList(), 1, "fun")

    @Test
    fun `a recursive glob default round-trips into the rendered signature`() {
        val signature = signatureOf(
            "fun keyFilesMatching(root: Path, pattern: String = \"**/*.json\"): List<Path> {",
            "    return emptyList()",
            "}",
        )
        assertEquals(
            "fun keyFilesMatching(root: Path, pattern: String = \"**/*.json\"): List<Path>",
            signature,
        )

        // The published page shows the highlighted form, with asterisks as numeric entities so the
        // markdown parser cannot read them as emphasis. The whole literal has to be in there.
        val html = StdlibReferenceGenerator.highlightSignature(signature)
        assertTrue(
            "&quot;&#42;&#42;/&#42;.json&quot;" in html,
            "the glob default did not survive highlighting: $html",
        )
    }

    @Test
    fun `a default containing a line-comment marker keeps the rest of its parameter list`() {
        assertEquals(
            "fun fetch(url: String = \"https://example.com/keys\", retries: Int = 3): String",
            signatureOf("fun fetch(url: String = \"https://example.com/keys\", retries: Int = 3): String {"),
        )
    }

    @Test
    fun `whitespace in a default is not tidied away`() {
        assertEquals(
            "fun render(prefix: String = \"( \", suffix: String = \" )\"): String",
            signatureOf("fun render(prefix: String = \"( \", suffix: String = \" )\"): String {"),
        )
    }

    @Test
    fun `a quote inside a char literal does not open a string`() {
        assertEquals(
            "fun split(separator: Char = '/', quote: Char = '\"'): List<String>",
            signatureOf("fun split(separator: Char = '/', quote: Char = '\"'): List<String> {"),
        )
    }

    @Test
    fun `an escaped quote does not close a default early`() {
        assertEquals(
            "fun quote(wrap: String = \"\\\", \"): String",
            signatureOf("fun quote(wrap: String = \"\\\", \"): String {"),
        )
    }

    @Test
    fun `comments in a header are still skipped`() {
        // The reason the scan looks for comment markers at all: a documented primary constructor.
        assertEquals(
            "class Window(width: Int = 640, height: Int = 480)",
            StdlibReferenceGenerator.signatureAt(
                listOf(
                    "class Window(",
                    "    /** Pixels, not points. */",
                    "    width: Int = 640,",
                    "    height: Int = 480, // square by default",
                    ") {",
                ),
                1,
                "class",
            ),
        )
    }
}
