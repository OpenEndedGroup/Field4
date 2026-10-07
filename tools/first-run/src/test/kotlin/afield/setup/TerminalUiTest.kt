package afield.setup

import afield.asciiWelcome
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalUiTest {
    private val escapes = Regex("\u001b\\[[0-9;]*m")

    @Test fun pipesNoColorAndDumbTerminalsNeverReceiveAnsi() {
        val modern = mapOf("COLORTERM" to "truecolor", "TERM" to "xterm-256color")
        assertEquals(TerminalColor.NONE, terminalColor(modern, false, false))
        assertEquals(TerminalColor.NONE, terminalColor(modern + ("NO_COLOR" to ""), true, false))
        assertEquals(TerminalColor.NONE, terminalColor(modern + ("TERM" to "dumb"), true, false))
        assertEquals(TerminalColor.NONE, terminalColor(modern + ("CLICOLOR" to "0"), true, false))
        assertFalse(TerminalUi(TerminalColor.NONE, 80).wordmark().contains('\u001b'))
    }

    @Test fun windowsColorRequiresAnAnsiCapableHost() {
        assertEquals(TerminalColor.NONE, terminalColor(emptyMap(), true, true))
        assertEquals(TerminalColor.TRUE_COLOR, terminalColor(mapOf("WT_SESSION" to "session"), true, true))
        assertEquals(TerminalColor.ANSI, terminalColor(mapOf("ANSICON" to "enabled"), true, true))
    }

    @Test fun wideTerminalUsesServersExactArtworkAndEveryColoredLineResets() {
        val ui = TerminalUi(TerminalColor.TRUE_COLOR, 140)
        val expected = asciiWelcome.trimIndent().lines().map(String::trimEnd).filterNot(String::isBlank)
        assertEquals(expected, ui.logoLines())
        val lines = ui.wordmark().lines()
        assertTrue(lines.all { it.endsWith("\u001b[0m") })
        assertEquals(expected, lines.map { escapes.replace(it, "").trimEnd() }.let { rendered ->
            val addedIndent = (140 - expected.maxOf(String::length)) / 2
            rendered.map { it.drop(addedIndent) }
        })
    }

    @Test fun narrowWordmarksFitWithoutTruncatingAnyGlyphs() {
        for (width in listOf(24, 40, 80, 120, 140)) {
            val ui = TerminalUi(TerminalColor.ANSI, width)
            assertTrue(ui.wordmark().lines().all { escapes.replace(it, "").length <= width })
        }
        assertEquals(listOf("F I E L D"), TerminalUi(TerminalColor.NONE, 24).logoLines())
    }

    @Test fun paragraphsWrapAndCommandsKeepTheirArgumentQuoting() {
        val previous = System.out
        val buffer = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(buffer))
            val ui = TerminalUi(TerminalColor.NONE, 40)
            ui.paragraph("Use your browser and leave the optional libraries for a later visit.")
            ui.command(listOf("./gradlew", "--project-dir", "/path with spaces"))
        } finally { System.setOut(previous) }
        val lines = buffer.toString().trimEnd().lines()
        assertTrue(lines.dropLast(1).all { it.length <= 40 })
        assertTrue(lines.last().endsWith("'/path with spaces'"))
        assertFalse(buffer.toString().contains('\u001b'))
    }
}
