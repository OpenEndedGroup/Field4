package afield.docs

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An editor capture of a short program is short.
 *
 * Screenshot prep sizes the editor pane for a window capture (840–1200 CSS px) and a line note sits at
 * the pane's foot, so until 2026-09-30 an editor-only capture taken while a note showed was the whole
 * pane: lua.md's four-line `box.png` was 2440 device pixels tall, code at the top and the note at the
 * bottom with nothing between. `fitEditorPaneToContentAndNote` (Main.kt) now sizes the pane to the text,
 * its output and the note first. This holds every committed `capture="editor"` figure to a height its
 * fence can explain: [BASE] device pixels plus [PER_LINE] for each line of the fence, which is loose
 * enough for inline output (the tallest honest one, dataframe-typed-columns, is 1150 px for 18 lines)
 * and far below a pane-sized figure.
 */
class EditorCaptureHeightTest {

    private val docs = File("public/docs")

    private fun allowed(lines: Int) = BASE + PER_LINE * lines

    @Test
    fun everyEditorCaptureIsNoTallerThanItsFenceExplains() {
        val fence = Regex("""(?ms)^```diffstatic_runner \{([^\n]*)\}\n(.*?)^```""")
        val checked = ArrayList<String>()
        val tooTall = ArrayList<String>()
        docs.walkTopDown().filter { it.isFile && it.extension == "md" && "assets" !in it.path.split(File.separatorChar) }.forEach { page ->
            for (m in fence.findAll(page.readText())) {
                val attrs = m.groupValues[1]
                if ("""capture="editor"""" !in attrs) continue
                val shot = Regex("""screenshot="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: continue
                val png = File(page.parentFile, shot)
                if (!png.isFile) continue
                val height = ImageIO.read(png)?.height ?: continue
                val lines = m.groupValues[2].trim('\n').lines().size
                checked += "${png.path} ${height}px/$lines lines"
                if (height > allowed(lines)) tooTall += "${png.path}: $height device px for a $lines-line fence (allowed ${allowed(lines)})"
            }
        }
        println("editor captures checked: ${checked.size}\n  " + checked.joinToString("\n  "))
        assertTrue(checked.size >= 10, "the fence scan looks broken: found only $checked")
        assertTrue(tooTall.isEmpty(), "editor captures a whole pane tall — is a line note pushing the clip to the pane's foot?\n" + tooTall.joinToString("\n"))
    }

    /** The negative control: the figure this exists for would have failed it. */
    @Test
    fun thePaneTallFigureThatPromptedThisFails() {
        assertFalse(2440 <= allowed(4), "lua.md's box.png before the fix: 2440 px for 4 lines")
    }

    companion object {
        const val BASE = 600
        const val PER_LINE = 120
    }
}
