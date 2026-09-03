package afield.python

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a Python cell displays, and where it displays it.
 *
 * Four defects were fixed here on 2026-08-27, and every one of them was invisible to the test
 * suite because `afield.python` had none. They were found by driving a real cell and photographing
 * it for `public/docs/python.md`, which is not a repeatable guard: a docs capture can be
 * regenerated green by someone who never notices the output changed. Hence this file.
 *
 * **What is covered here, and how.** The output-formatting rules and the two line/size numbers are
 * pure functions, and are called directly. The remaining two live inside `_field_print` and
 * `executeFragment`, which need a live JEP interpreter and a `Box`; they are pinned by reading
 * `PythonInterface.kt` and asserting on its text. That is a weaker instrument than calling the
 * code, and it is chosen deliberately over no guard at all — it catches the regression that
 * actually happened (someone adds an output path and forgets the invariant) even though it cannot
 * catch a change in what those lines *mean*.
 *
 * **What is NOT covered, and will not be until something can start an interpreter in a test.**
 * No test here executes Python. Nothing asserts end-to-end that a bare last expression reaches the
 * front end as its value, or that a one-line print arrives tagged with the line that made it. That
 * evidence exists only as the four captures on `public/docs/python.md`, and those need a JEP
 * install (`requires="jep"` on each fence) that neither CI nor a fresh clone has. Closing this gap
 * means either a JEP-provisioned CI lane or a fake `SharedInterpreter`; both are real work and
 * neither is done.
 */
class PythonOutputContractTest {

    private val source: String by lazy {
        File("src/jvmMain/kotlin/afield/python/PythonInterface.kt").also {
            assertTrue(it.exists(), "cannot find PythonInterface.kt from ${File(".").absolutePath}")
        }.readText()
    }

    /** The body of `executeFragment`, which is where the output-slot invariant has to hold. */
    private val executeFragmentBody: String by lazy {
        val start = source.indexOf("override fun executeFragment(")
        assertTrue(start >= 0, "executeFragment has been renamed; this guard needs re-pointing")
        val end = source.indexOf("private fun hackJepThreadContext", start)
        assertTrue(end > start, "cannot find the end of executeFragment")
        source.substring(start, end)
    }

    // ---- where a result is attached -------------------------------------------------------

    @Test
    fun aFragmentsResultLandsOnItsLastLineNotPastTheEnd() {
        // The regression: a trailing newline made `split("\n")` yield a phantom final element, so
        // the result was attached one line past the document and silently dropped.
        assertEquals(3, PythonInterface.resultLineFor(0, "import math\n\nsum([1, 2])\n"))
        // Same code without the trailing newline must land in the same place.
        assertEquals(3, PythonInterface.resultLineFor(0, "import math\n\nsum([1, 2])"))
        // And several trailing blank lines are still the same statement.
        assertEquals(3, PythonInterface.resultLineFor(0, "import math\n\nsum([1, 2])\n\n\n"))
    }

    @Test
    fun aFragmentsResultLineIsOffsetByWhereTheFragmentStarts() {
        assertEquals(1, PythonInterface.resultLineFor(0, "x = 1\n"))
        assertEquals(41, PythonInterface.resultLineFor(40, "x = 1\n"))
        assertEquals(43, PythonInterface.resultLineFor(40, "x = 1\ny = 2\nx + y\n"))
    }

    @Test
    fun aSingleLineFragmentResolvesToLineOne() {
        assertEquals(1, PythonInterface.resultLineFor(0, "1 + 1"))
        assertEquals(1, PythonInterface.resultLineFor(0, "1 + 1\n"))
    }

    // ---- what output looks like -----------------------------------------------------------

    @Test
    fun multiLineOutputIsWrappedSoItKeepsItsColumns() {
        // A 2-D array or a DataFrame is only legible if it is not flattened into <br>s.
        val array = "[[ 0  1  2  3]\n [ 4  5  6  7]]"
        val formatted = PythonInterface.formatOutput(array)
        assertTrue(formatted.startsWith("<pre class=\"afield-python-output"), formatted)
        assertTrue(formatted.endsWith("</pre>"), formatted)
        assertTrue(formatted.contains("[ 4  5  6  7]"), formatted)
    }

    @Test
    fun singleLineOutputIsNotWrapped() {
        // The inline chip beside a line of code must not be a block element.
        assertEquals("python 3.12.9", PythonInterface.formatOutput("python 3.12.9"))
        // A lone trailing newline does not make output "multi-line".
        assertEquals("22.4682781862041", PythonInterface.formatOutput("22.4682781862041\n"))
    }

    @Test
    fun outputIsEscapedBecauseThePaneIsHtml() {
        assertEquals("&lt;lambda&gt; &amp; co", PythonInterface.formatOutput("<lambda> & co"))
    }

    @Test
    fun enormousOutputIsClippedHeadAndTailRatherThanShippedWhole() {
        val huge = "x".repeat(PythonInterface.MAX_OUTPUT_CHARS * 2)
        val clipped = PythonInterface.truncateOutput(huge)
        assertTrue(clipped.length < huge.length, "nothing was truncated")
        assertTrue(clipped.contains("characters truncated"), clipped.take(200))

        val many = (1..PythonInterface.MAX_OUTPUT_LINES * 2).joinToString("\n") { "line $it" }
        val clippedLines = PythonInterface.truncateOutput(many)
        assertTrue(clippedLines.contains("lines truncated"), clippedLines.take(200))
        // Head and tail are both kept: that is what makes a clipped traceback still readable.
        assertTrue(clippedLines.contains("line 1\n"), "head was dropped")
        assertTrue(clippedLines.contains("line ${PythonInterface.MAX_OUTPUT_LINES * 2}"), "tail was dropped")
    }

    @Test
    fun outputThatFitsIsLeftExactlyAlone() {
        val small = "a\nb\nc"
        assertEquals(small, PythonInterface.truncateOutput(small))
    }

    // ---- the embedded-figure iframe --------------------------------------------------------

    @Test
    fun theEmbedIframeDeclaresBothAxesInPixels() {
        val style = PythonInterface.EMBED_IFRAME_STYLE
        // outputareas2.js reads these with parseFloat and treats an unparseable value as 0, which
        // is how "width:100%" collapsed every embedded figure into a ~480px column.
        for (axis in listOf("width", "height")) {
            val declared = Regex("""\b$axis:\s*([^;]+);""").find(style)?.groupValues?.get(1)?.trim()
            assertTrue(declared != null, "$axis is not declared in $style")
            assertTrue(declared.endsWith("px"), "$axis must be in px, not '$declared'")
            val parsed = declared.removeSuffix("px").toDoubleOrNull()
            assertTrue(parsed != null && parsed > 0, "$axis '$declared' does not parse to a positive number")
        }
        assertFalse(style.contains("%"), "a percentage in $style parses as zero in outputareas2.js")
    }

    @Test
    fun theEmbeddedPythonUsesTheOneIframeStyleDefinition() {
        // If the Python literal drifts back to a hand-written string the test above stops guarding
        // anything, so pin the interpolation itself.
        assertTrue(
            source.contains("_FIELD_IFRAME_STYLE = \"\${EMBED_IFRAME_STYLE}\""),
            "the embedded Python no longer interpolates EMBED_IFRAME_STYLE",
        )
    }

    // ---- source-level guards over the two paths a unit test cannot reach --------------------

    @Test
    fun everyOutputPathInExecuteFragmentClaimsTheOutputSlot() {
        // `finally` writes "ok" over the cell's output unless something already claimed it by
        // setting hasHadOutput. A bare last expression and "[task launched]" both used to publish
        // without claiming, so both were overwritten by "ok".
        val body = executeFragmentBody
        val unclaimed = mutableListOf<String>()
        Regex("""inside\.output\.proxy\(""").findAll(body).forEach { match ->
            // Measured in *code* lines, not characters: the explanatory comments beside these
            // calls are long enough to push the claim outside any reasonable character window,
            // and a wide character window would instead be satisfied by an unrelated assignment.
            val claimed = body.substring(match.range.first)
                .lineSequence()
                .filterNot { it.trim().startsWith("//") }
                .drop(1)
                .takeWhile { !it.contains("inside.output.proxy(") }   // stop at the next output path
                .take(4)                                             // …and stay inside this branch
                .any { it.contains("hasHadOutput = true") }
            if (!claimed) {
                unclaimed += "line ${body.take(match.range.first).count { it == '\n' } + 1} of executeFragment: " +
                    body.substring(match.range.first).lineSequence().first().trim()
            }
        }
        assertTrue(
            unclaimed.isEmpty(),
            "these output paths in executeFragment publish without setting hasHadOutput, so the " +
                "finally block will overwrite them with \"ok\":\n" + unclaimed.joinToString("\n"),
        )
        // The guard is worthless if it finds nothing to check.
        assertTrue(
            Regex("""inside\.output\.proxy\(""").findAll(body).count() >= 3,
            "expected at least three output paths in executeFragment",
        )
    }

    @Test
    fun printIsPublishedAgainstTheLineThatMadeIt() {
        // `print` used to publish at line -1, which the output area resolves to the END of the
        // fragment: a one-line print appeared beside the cell's last line instead of its own, and
        // two of them collided on that same line.
        assertTrue(
            source.contains("box.getOutput().getProxy()(PythonInterface.formatOutput(text), False, _field_source_line(PythonInterface))"),
            "_field_print no longer resolves its own source line",
        )
        assertFalse(
            source.contains("box.getOutput().getProxy()(PythonInterface.formatOutput(text), False, -1)"),
            "_field_print has gone back to publishing at line -1",
        )
    }

    @Test
    fun theSourceLineWalkerMatchesTheFilenameTheFragmentIsCompiledUnder() {
        // _field_source_line finds the cell's frame by filename prefix, so it only works while
        // _exec_with_return2 keeps building that same prefix.
        assertTrue(
            source.contains("""filename = f"<repl:{source_id or 'cell'}>""""),
            "the fragment filename has changed; _field_source_line's prefix match must follow it",
        )
        assertTrue(
            source.contains("""frame.f_code.co_filename.startswith("<repl:")"""),
            "_field_source_line no longer matches the fragment filename prefix",
        )
        assertTrue(
            source.contains("return PythonInterface.thisLineOffset + frame.f_lineno") ||
                source.contains("return _PythonInterface.thisLineOffset + frame.f_lineno"),
            "_field_source_line no longer offsets the frame line by the fragment's start line",
        )
    }
}
