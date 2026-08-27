package afield.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The five lexical shapes that used to make [KotlinSourceIndex]'s brace tracking lose a function
 * body, and publish the locals inside it as public API.
 *
 * The index decides what the completion panel offers and what
 * `afield.docs.StdlibReferenceGenerator` writes onto the generated reference pages. Both ask the
 * same question of a line: *is this at file scope, or directly inside a class body?* — and both get
 * their answer from a brace depth that a hand-rolled lexer maintains. When that depth drifts low the
 * scan believes a function body has closed while it is still inside one, and a local `val` two lines
 * later is indexed, ranked in completion and published on a reference page as a symbol of that file.
 * `afield.docs.SourcePartitionGuard` counts those over-reports across the repository; each case here
 * was cut down from one it found.
 *
 * Every test is written the same way: the smallest source that shows the shape, and an assertion on
 * the *whole* set of symbols the index emits. Asserting the whole set is what makes them useful —
 * a test that only checked the local was absent would pass just as well for a tracker that had
 * stopped indexing anything at all.
 */
class KotlinSourceIndexBraceTrackingTest {

    /** Every declaration the index emits, as `kind name`, in source order. */
    private fun indexed(source: String): List<String> =
        KotlinSourceIndex.parseTopLevelDefinitions(source.trimIndent()).map { "${it.kind} ${it.name}" }

    @Test
    fun `an apostrophe inside a backtick-quoted name is not a character literal`() {
        // Kotlin test names are backtick-quoted prose, so possessives are everywhere. Read as a
        // character literal, the apostrophe swallows the `{` that opens the body — and every brace
        // after it, up to the next apostrophe anywhere in the file.
        val source = """
            class Probe {
                fun `a builder's groups become the names a pick reports`() {
                    val builder = Thing()
                    val parts = builder.groups()
                }
            }
        """
        assertEquals(
            listOf("class Probe", "fun Probe.a builder's groups become the names a pick reports"),
            indexed(source),
            "the locals in the body are not declarations of Probe",
        )
    }

    @Test
    fun `a block comment nests, so a KDoc may mention a comment marker`() {
        // Kotlin block comments nest. A KDoc that writes an inner marker in prose stays open across
        // it; ending the outer comment there hands the rest of the KDoc to the code path, where its
        // prose apostrophes and its fenced-example braces are read as source.
        val source = """
            /**
             * Merged under a content-derived key (see the `/*prefix*/` stack mark). Example:
             * ```
             * rewrite { n -> from { counter(n) } }
             * ```
             */
            fun provide(preamble: () -> Unit): Unit = erased("provide")

            val afterwards = 1
        """
        assertEquals(
            listOf("fun provide", "val afterwards"),
            indexed(source),
            "the KDoc runs to its own close, and both declarations below it are still top level",
        )
    }

    @Test
    fun `a string template may contain a string of its own`() {
        // `"${'$'}{ … "x" … }"` — skipping from the opening quote to the next quote lands *inside*
        // the template, after which its braces are counted and its text is lexed as code.
        val source = """
            fun link(line: Int?): String {
                val href = "<a${'$'}{line?.let { " data-line=\"${'$'}it\"" } ?: ""}>"
                return href
            }

            val topLevel = 1
        """
        assertEquals(
            listOf("fun link", "val topLevel"),
            indexed(source),
            "href is local to link, and the declaration after it is still top level",
        )
    }

    @Test
    fun `a raw string whose content ends in a quote closes on the last three of the run`() {
        // `""${'"'} x="1"""${'"'}` is four closing quotes: content `"`, then the close. Taking the
        // first three leaves a stray quote that opens a string nothing ever closes.
        val source = """
            fun style(px: Int): String {
                val attr = ${"\"\"\""} style="width:${'$'}{px}px"${"\"\"\""}
                return attr
            }

            val topLevel = 1
        """
        assertEquals(
            listOf("fun style", "val topLevel"),
            indexed(source),
            "the raw string ends on its own line, so the body closes and topLevel is top level",
        )
    }

    @Test
    fun `an unterminated string ends at its line, but a multi-line template does not`() {
        // Two halves of one rule. A normal string literal cannot contain a newline, so one still open
        // at the end of a line is a mis-lex and is dropped there rather than run to the end of file.
        val mislexed = """
            fun broken() {
                val quote = '
                val other = 2
            }

            val topLevel = 1
        """
        assertEquals(
            listOf("fun broken", "val topLevel"),
            indexed(mislexed),
            "a stray quote costs its own line, not the rest of the file",
        )

        // A `${'$'}{ … }` inside a string *is* allowed to span lines, and dropping it there would
        // leave the `}` that closes it to be counted as closing the function body.
        val spanning = """
            fun report(items: List<String>) {
                lprintln("count=${'$'}{
                    items.size
                } names=${'$'}{items.joinToString()}")
                val tail = 1
            }

            val topLevel = 1
        """
        assertEquals(
            listOf("fun report", "val topLevel"),
            indexed(spanning),
            "the template's closing brace does not close the function body",
        )
    }

    @Test
    fun `the shapes it already handled still work`() {
        // A characterisation guard on the rest of the lexer: comments, raw strings, character
        // literals holding braces, and escaped quotes were never the bug, and must not become one.
        val source = """
            class Holder {
                val open = '{'
                val close = '}'
                val escaped = "he said \"widget\" loudly"
                val raw = ${"\"\"\""}{ not code }${"\"\"\""}

                // } not code
                /* } not code either */
                fun member(): Int {
                    val local = 1
                    return local
                }
            }

            val topLevel = 2
        """
        assertEquals(
            listOf(
                "class Holder",
                "val Holder.open",
                "val Holder.close",
                "val Holder.escaped",
                "val Holder.raw",
                "fun Holder.member",
                "val topLevel",
            ),
            indexed(source),
        )
    }
}
