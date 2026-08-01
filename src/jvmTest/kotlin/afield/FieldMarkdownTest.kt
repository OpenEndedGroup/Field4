package afield

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract docs figures rely on: markdown renders the shapes that used to be hand-written HTML,
 * HTML produced by other Field APIs survives verbatim, and the two additions to the dialect
 * (`:::row`, `{role:…}`) stay out of code.
 */
class FieldMarkdownTest {

    private fun render(text: String) = FieldMarkdown.render(text)

    // ------------------------------------------------------- the shapes replaced

    @Test
    fun tablesRenderSoKeyValueFiguresNeedNoHandBuiltRows() {
        val html = render(
            """
            | binding | value |
            |---------|-------|
            | glow.radius | 9.0 |
            """
        )
        assertContains(html, "<table>")
        assertContains(html, "<th>binding</th>")
        assertContains(html, "<td>glow.radius</td>")
    }

    @Test
    fun fencedCodeEscapesSoFiguresNeedNoLocalEscHelper() {
        // The hand-rolled `fun esc(s: String) = s.replace("&", "&amp;")...` in the docs exists only
        // because <pre> needs escaping. A fence does it, and marks the language for highlighting.
        val html = render("```json\n{\"a\": \"<b> & <c>\"}\n```")
        assertContains(html, """<code class="language-json">""")
        assertContains(html, "&lt;b&gt; &amp; &lt;c&gt;")
        assertFalse("<b>" in html, "angle brackets inside a fence must not become tags")
    }

    @Test
    fun straySpecialCharactersInProseAreEscaped() {
        val html = render("a < b && c > d")
        assertContains(html, "a &lt; b &amp;&amp; c &gt; d")
    }

    @Test
    fun headingsCoverTheStepCaptionShape() {
        val html = render("#### 1 — capture()")
        assertContains(html, "<h4>1 — capture()</h4>")
    }

    // ------------------------------------------------------------ HTML passthrough

    @Test
    fun htmlFromOtherFieldApisPassesThroughVerbatim() {
        // key.h / diffHtml() / graphSvg() already return HTML. Interpolating them must not escape.
        val svg = """<svg width="10" height="10"><circle cx="5" cy="5" r="4"/></svg>"""
        assertContains(render("text\n\n$svg"), svg)
    }

    @Test
    fun htmlSurvivesInsideATableCell() {
        val svg = "<svg width='4' height='4'><rect/></svg>"
        val html = render("| a | b |\n|---|---|\n| x | $svg |")
        assertContains(html, svg)
    }

    @Test
    fun multiLineHtmlBlocksPassThrough() {
        val html = render("before\n\n<div class=\"x\">\n  <b>inner</b>\n</div>\n\nafter")
        assertContains(html, """<div class="x">""")
        assertContains(html, "<b>inner</b>")
    }

    // ------------------------------------------------------------------ indenting

    @Test
    fun textIsTrimmedSoAnIndentedRawStringIsNotReadAsACodeBlock() {
        // The failure this prevents: a """ block nested inside a with(...) { } keeps its leading
        // indentation, markdown reads four spaces as a code block, and the whole figure renders as
        // one grey slab. Silent, and the reason display() calls avoided markdown in the first place.
        val html = render(
            """
            # heading
            some text
            """
        )
        assertContains(html, "<h1>heading</h1>")
        assertFalse("<code>" in html, "indented input must not be read as a code block")
    }

    @Test
    fun aMultiLineInterpolatedBlockDoesNotDefeatTheDedent() {
        // The trap trimIndent() walks into: rows joined with "\n" land at column 0, which would drag
        // the minimum indent to zero, leave the author's four spaces in place, and render the whole
        // figure as one grey code slab. Anchoring on the first line is what prevents it.
        val rows = listOf("| a | 1 |", "| b | 2 |").joinToString("\n")
        val html = render(
            """
            | name | value |
            |------|-------|
            $rows
            """
        )
        assertContains(html, "<table>")
        assertContains(html, "<td>a</td>")
        assertContains(html, "<td>b</td>")
        assertFalse("<code>" in html, "the figure must not collapse into a code block")
    }

    @Test
    fun aFenceBodyInterpolatedAtColumnZeroSurvivesByteForByte() {
        // The documented spelling. Four-space JSON is the case that matters: every one of its lines
        // happens to start with the author's four-space margin, so a per-line dedent would silently
        // flatten the top level. Judging the fence as a whole leaves it alone.
        val json = "{\n    \"schemaVersion\": 1,\n    \"values\": {\n        \"gain\": 0.2\n    }\n}"
        val html = render(
            """
            #### on disk
            ~~~json
$json
            ~~~
            """
        )
        assertContains(html, """<code class="language-json">""")
        assertContains(html, "{\n    &quot;schemaVersion&quot;: 1,")
        assertContains(html, "        &quot;gain&quot;: 0.2")
    }

    @Test
    fun aHandWrittenFenceBodyIsDedentedAsAWhole() {
        // Every line carries the margin, so it is the author's indentation and comes off.
        val html = render(
            """
            ~~~
            {
              "a": 1
            }
            ~~~
            """
        )
        assertContains(html, "{\n  &quot;a&quot;: 1\n}")
    }

    @Test
    fun interpolatedHtmlThatCarriesItsOwnIndentationStillRenders() {
        // keyframeKeyHtml and friends leak a four-space margin: their trimIndent() is defeated by
        // their own interpolated rows. Harmless to every other caller, and it would silently show
        // this figure's HTML as a code block instead of rendering it.
        val leaky = "    <div class=\"leaky\">\n      <b>inner</b>\n    </div>"
        val html = render(
            """
            #### 1 — capture()
            $leaky
            """
        )
        assertContains(html, """<div class="leaky">""")
        assertContains(html, "<b>inner</b>")
        assertFalse("&lt;div" in html, "the HTML must render, not be shown as code")
    }

    @Test
    fun deliberatelyFencedHtmlIsStillShownRatherThanRendered() {
        val html = render("~~~html\n    <div>shown</div>\n~~~")
        assertContains(html, "&lt;div&gt;shown&lt;/div&gt;")
    }

    @Test
    fun tildeFencesWorkSoAFigureCanShowCodeInsideABacktickDocsFence() {
        // Docs runner fences are ```-delimited, so a figure's own code block has to be ~~~.
        val html = render("~~~kotlin\nval x = 1\n~~~")
        assertContains(html, """<code class="language-kotlin">""")
        assertContains(html, "val x = 1")
    }

    // --------------------------------------------------------------- mdEscape

    @Test
    fun mdEscapeKeepsComputedValuesOutOfMarkdownsHands() {
        // A StackSL program reads "saw 110 *" and a path can hold anything; unescaped, a pair of
        // asterisks becomes emphasis and a pipe silently splits the table row it lands in.
        val html = render("| voice | ${mdEscape("saw 110 * fold * bar")} |\n|---|---|\n| a | b |")
        assertContains(html, "saw 110 * fold * bar")
        assertFalse("<em>" in html, "asterisks in a value must not become emphasis")
    }

    @Test
    fun mdEscapeKeepsAPipeInsideItsCell() {
        val html = render("| k | v |\n|---|---|\n| x | ${mdEscape("a|b")} |")
        assertContains(html, "a|b")
        assertEquals(2, Regex("<td>").findAll(html).count(), "the pipe must not open a third cell")
    }

    @Test
    fun mdEscapeShowsTagLikeValuesRatherThanRunningThem() {
        val html = render(mdEscape("<b>not bold</b>"))
        assertContains(html, "&lt;b&gt;not bold&lt;/b&gt;")
    }

    @Test
    fun mdEscapeFoldsNewlinesSoARowCannotEndEarly() {
        assertEquals("a b", mdEscape("a\nb"))
        assertEquals("a b", mdEscape("a\r\nb"))
    }

    @Test
    fun mdEscapeRendersNullTheWayInterpolationDoes() {
        assertEquals("null", mdEscape(null))
    }

    // ----------------------------------------------------------------- :::row

    @Test
    fun rowSplitsColumnsOnARuleLine() {
        val html = render(
            """
            :::row
            #### before
            one
            ---
            #### after
            two
            :::end
            """
        )
        assertContains(html, """<div class="field-md-row">""")
        assertEquals(2, Regex("""<div class="field-md-col">""").findAll(html).count())
        assertTrue(
            html.indexOf("before") < html.indexOf("after"),
            "columns keep source order"
        )
    }

    @Test
    fun aBareColonCloserStillWorksOutsideTheDocsPipeline() {
        // `:::end` is the documented spelling because public/docs_hooks.py deletes a bare `:::`
        // from the code it runs. Everywhere else the obvious spelling should still do the obvious
        // thing, so both close the row.
        val html = render(":::row\na\n---\nb\n:::\n\nafter")
        assertEquals(2, Regex("""<div class="field-md-col">""").findAll(html).count())
        assertTrue(
            html.indexOf("field-md-row") < html.indexOf("after"),
            "content after the closer must not be swallowed into the last column: $html"
        )
    }

    @Test
    fun contentAfterARowIsASiblingNotAThirdColumn() {
        // The failure this pins: with the row left open, "outro" lands inside the last column, so
        // a figure's trailing prose silently renders half-width under the right-hand panel.
        val html = render(":::row\na\n---\nb\n:::end\n\n#### outro\n\ntail")
        val rowEnd = html.indexOf("</div></div>")
        assertTrue(rowEnd > 0, "row should close with nested divs: $html")
        assertTrue(html.indexOf("outro") > rowEnd, "outro must come after the row closes: $html")
    }

    @Test
    fun proseBeforeAndAfterARowKeepsItsOrder() {
        val html = render("intro\n\n:::row\na\n---\nb\n:::end\n\noutro")
        val intro = html.indexOf("intro")
        val row = html.indexOf("field-md-row")
        val outro = html.indexOf("outro")
        assertTrue(intro in 0 until row && row < outro, "got: $html")
    }

    @Test
    fun anUnterminatedRowStillRenders() {
        // A missing ::: should cost a layout, not the whole figure.
        val html = render(":::row\na\n---\nb")
        assertContains(html, """<div class="field-md-row">""")
        assertContains(html, "b")
    }

    @Test
    fun aRowShownAsExampleCodeIsNotInterpreted() {
        val html = render("```\n:::row\nleft\n---\nright\n:::\n```")
        assertFalse("field-md-row" in html, "a fenced :::row is example text, not a layout")
        assertContains(html, ":::row")
    }

    @Test
    fun aRuleOutsideARowIsStillAHorizontalRule() {
        assertContains(render("a\n\n---\n\nb"), "<hr")
    }

    // ------------------------------------------------------------- {role:text}

    @Test
    fun roleSpansRenderWithoutAHexLiteral() {
        assertContains(render("dropped: {bad:gain, taps}"), """<span class="field-md-bad">gain, taps</span>""")
        FieldMarkdown.ROLES.forEach { role ->
            assertContains(render("{$role:x}"), """<span class="field-md-$role">x</span>""")
        }
    }

    @Test
    fun roleSpansNestAndStillRunMarkdownInside() {
        val html = render("{muted:outer {accent:**inner**} tail}")
        assertContains(html, """<span class="field-md-muted">""")
        assertContains(html, """<span class="field-md-accent">""")
        assertContains(html, "<strong>inner</strong>")
    }

    @Test
    fun aRoleSpanMayWrapAcrossLines() {
        // Annotations under a chart are paragraphs, not one physical line. Requiring the closing
        // brace on the opening line would fail silently and leave the braces on screen.
        val html = render("{muted:first line\nsecond line\nthird line}")
        assertContains(html, """<span class="field-md-muted">""")
        assertContains(html, "third line</span>")
        assertFalse("{muted:" in html, "the marker must not survive into the output")
    }

    @Test
    fun aRoleSpanDoesNotReachAcrossAFencedBlock() {
        // The fence breaks the prose run, so an unclosed role before it cannot swallow code.
        val html = render("{muted:dangling\n\n~~~\ncode }\n~~~\n\ntail")
        assertContains(html, "code }")
        assertFalse("field-md-muted" in html, "an unclosed role must not consume the fence")
    }

    @Test
    fun rolesAreLeftAloneInCode() {
        assertFalse("field-md-warn" in render("```\n{warn:literal}\n```"), "fenced code is verbatim")
        assertFalse("field-md-warn" in render("`{warn:literal}`"), "an inline code span is verbatim")
    }

    @Test
    fun unknownRolesAndBareBracesAreLeftAlone() {
        assertContains(render("a {notARole:b} c"), "{notARole:b}")
        assertContains(render("val f = { it.x }"), "{ it.x }")
    }

    @Test
    fun anUnbalancedRoleBraceIsLeftAsText() {
        assertContains(render("{bad:unterminated"), "{bad:unterminated")
    }

    @Test
    fun anEscapedBraceIsALiteralBrace() {
        val html = render("""\{accent:not a span}""")
        assertFalse("field-md-accent" in html)
        assertContains(html, "{accent:not a span}")
    }

    // ------------------------------------------------------------------- card

    @Test
    fun theCardCarriesTheStylesheetClassAndPinsWidthOnRequest() {
        assertContains(FieldMarkdown.card("x"), """<div class="field-md">""")
        assertContains(FieldMarkdown.card("x", width = 820), """style="width:820px;max-width:820px"""")
    }

    @Test
    fun theBodyWrapperIsStripped() {
        assertFalse("<body>" in FieldMarkdown.card("x"), "the <body> the generator emits must not ship")
    }

    @Test
    fun emptyTextIsAnEmptyCardRatherThanAFailure() {
        assertEquals("""<div class="field-md"></div>""", FieldMarkdown.card(""))
    }

    // --------------------------------------------------------------- Box.markdown

    @Test
    fun boxMarkdownDisplaysTheCardOnTheNamedChannelAndOpensIt() {
        val box = Box("markdown-test")
        val seen = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, content -> seen += channel to content }

        box.markdown("keys", "### hello", width = 820)

        assertEquals(1, seen.size)
        assertEquals("keys", seen[0].first)
        val html = seen[0].second as String
        assertContains(html, """<div class="field-md" style="width:820px;max-width:820px">""")
        assertContains(html, "<h3>hello</h3>")
    }

    @Test
    fun theSingleArgumentFormUsesTheDefaultChannel() {
        val box = Box("markdown-test")
        val seen = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, content -> seen += channel to content }

        box.markdown("### hello")

        assertEquals("output", seen.single().first)
    }
}
