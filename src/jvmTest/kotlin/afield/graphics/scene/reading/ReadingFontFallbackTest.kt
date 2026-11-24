package afield.graphics.scene.reading

import afield.graphics.text.*
import kotlin.test.*

/**
 * Glyph borrowing, with fonts from this checkout so the result does not depend on what is
 * installed: the Monaco icon font has no letters at all, and Iosevka-Light has letters but no 𝓝.
 */
class ReadingFontFallbackTest {
    private val icons = Typefaces.fromFile("src/jsMain/resources/monaco/min/vs/base/browser/ui/codicons/codicon/codicon.ttf", 100)
    private val iosevka = Typefaces.fromFile("src/commonMain/resources/Iosevka-Light.ttf", 40)

    private fun run(text: String, chain: List<Typeface>?) = readingDiagram {
        text("run", text, point("p", 0, 0, pinned = true)) { font = icons; fallbacks = chain }
    }.text("run")

    @Test
    fun missingGlyphsAreBorrowedAtTheRunsSizeAcrossLines() {
        val run = run("ab\n\ncd", listOf(iosevka))
        val key = readingPreparationKey(run, icons)
        // One merged range per line of borrowed letters, each at the run's size, not the chain's.
        assertEquals(listOf(0 to 2, 4 to 6), key.ranges.map { it.start to it.end })
        assertTrue(key.ranges.all { it.face.familyName == iosevka.familyName && it.face.size == 100.0 })
        val layout = prepareReadingLayout(run, key)
        assertEquals(3, layout.lines.size)
        assertEquals(listOf(0, 1, 4, 5), layout.glyphs.map { it.charIndex })
        assertEquals(listOf(0, 0, 2, 2), layout.glyphs.map { it.lineIndex })
        assertEquals(2 * icons.lineHeight, layout.lines[2].baselineY, 1e-9)
        assertEquals(layout.lines[0].advanceWidth, layoutText("ab", iosevka.atSize(100)).advanceWidth, 1e-6)
    }

    @Test
    fun aGlyphNoFaceHasFailsNamingTheCharacterAndTheFacesTried() {
        val e = assertFailsWith<IllegalArgumentException> {
            readingPreparationKey(run("open 𝓝 x", listOf(iosevka)), icons)
        }
        assertContains(e.message!!, "U+1D4DD")
        assertContains(e.message!!, "at 5")
        assertContains(e.message!!, iosevka.familyName)
        // An empty chain turns borrowing off: the first letter already fails.
        val off = assertFailsWith<IllegalArgumentException> { readingPreparationKey(run("a", emptyList()), icons) }
        assertContains(off.message!!, "U+0061")
    }

    @Test
    fun aRunItsFaceCanSetIsUntouched() {
        val run = readingDiagram {
            text("run", "plain\ntext", point("p", 0, 0, pinned = true)) { font = iosevka; fallbacks = emptyList() }
        }.text("run")
        assertTrue(readingPreparationKey(run, iosevka).ranges.isEmpty())
    }
}
