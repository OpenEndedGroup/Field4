package afield.ai.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownSourceTest {
    private fun withoutPositions(html: String) = html
        .replace(Regex("<span data-markdown-offsets=\"[0-9,-]*\">([^<]*)</span>"), "$1")
        .replace(Regex(" data-markdown-start=\"[0-9]+\""), "")

    @Test
    fun `source tracking preserves the shared reader HTML`() {
        val examples = listOf(
            "# Heading\n\nA **bold *phrase*** and *emphasis*, `3 * x`, < > & \\\" ' 😀.",
            "- -\n- **repeated** repeated\n   wrapped text\n\n1) first\n2. second",
            "[same](https://same.example/same) same [**label**](field:box)",
            "![**bold** alt](https://example.org/pic.png) [label](https://example.org/a**b**)",
            "![glob](**/*.png) [**label**](https://example.org/a**b**) prose *italic*",
            "![a picture](https://example.org/pic.png) ![](data:image/png;base64,AA==)",
            "inline \\(x^2\\) and \$x\$ and \$\$a+b\$\$ and `\$x\$`.",
            "| A | B |\n| :--- | ---: |\n| *same* | same |\n| | & |",
            "before\n\n```kotlin\nval x = \"<&\"\n```\n\nafter",
            "```\nno language\n```\n\n```glsl\nfloat a = 0.;"
        )
        for (source in examples) assertEquals(aiChatMarkdownHtml(source), withoutPositions(aiChatMarkdownSourceHtml(source, true)), source)
    }

    @Test fun `image paths are literal even when they contain FileManager globs`() {
        for (html in listOf(aiChatMarkdownHtml("![scan](**/*.png)"), aiChatMarkdownSourceHtml("![scan](**/*.png)", true))) {
            assertTrue("src=\"**/*.png\"" in html, html)
        }
    }

    @Test
    fun `characters retain their precise occurrence through syntax and hidden destinations`() {
        val source = "  # same\n\n[same](https://same.example/same) **same**\n  next & 😀\n\n- -\n- same\n\n| same | same |\n| --- | --- |\n| & | same |"
        val html = aiChatMarkdownSourceHtml(source, true)
        val spans = Regex("<span data-markdown-offsets=\"([0-9,]+)\">([^<]*)</span>").findAll(html).toList()
        assertTrue(spans.isNotEmpty())
        fun decode(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        for (span in spans) {
            val offsets = span.groupValues[1].split(',').map { it.toInt() }
            val visible = decode(span.groupValues[2])
            assertEquals(visible.length, offsets.size, span.value)
            visible.forEachIndexed { index, char ->
                assertEquals(char, source[offsets[index]].let { if (it == '\n') ' ' else it }, span.value)
            }
        }
        val bold = Regex("<strong><span data-markdown-offsets=\"([0-9,]+)\">").find(html)!!
        assertEquals(source.indexOf("**same**") + 2, bold.groupValues[1].substringBefore(',').toInt())
        val dash = Regex("<li><span data-markdown-offsets=\"([0-9,]+)\">-</span>").find(html)!!
        assertEquals(source.indexOf("- -") + 2, dash.groupValues[1].toInt())
    }
}
