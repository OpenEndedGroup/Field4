@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * The small pictures line notes ([LineNotes]) draw for languages whose values are not images: a
 * stack, drawn as cells with its top uppermost, and a relation, drawn as a table. Drawn in Java2D on
 * an off-screen image, so they need no window or GPU.
 */
object LineNotePictures {
    private val background = Color(0x1c, 0x1c, 0x21)
    private val cell = Color(0x2a, 0x2a, 0x32)
    private val edge = Color(0x4a, 0x4a, 0x55)
    private val ink = Color(0xdd, 0xdd, 0xe2)
    private val muted = Color(0x88, 0x88, 0x92)
    private val accent = Color(0x5c, 0xe1, 0xff)
    private val font = Font(Font.MONOSPACED, Font.PLAIN, 11)

    /** How many cells of a stack, or rows of a table, a picture shows before it says how many more there are. */
    const val SHOWN = 8

    private fun draw(width: Int, height: Int, paint: (Graphics2D) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = background
            g.fillRect(0, 0, width, height)
            g.font = font
            paint(g)
        } finally {
            g.dispose()
        }
        return afield.util.PngEncoder.encode(image.getRGB(0, 0, width, height, null, 0, width), width, height)
    }

    private fun fit(g: Graphics2D, text: String, width: Int): String {
        val metrics = g.fontMetrics
        if (metrics.stringWidth(text) <= width) return text
        var cut = text
        while (cut.isNotEmpty() && metrics.stringWidth("$cut…") > width) cut = cut.dropLast(1)
        return "$cut…"
    }

    /**
     * A stack of [values], bottom first as a stack is usually listed; the top is drawn uppermost and
     * outlined. An empty stack is drawn as an empty tray.
     */
    fun stack(values: List<String>): ByteArray {
        val shown = values.takeLast(SHOWN).asReversed()
        val hidden = values.size - shown.size
        val rowHeight = 18
        val width = 168
        val rows = maxOf(1, shown.size) + (if (hidden > 0) 1 else 0)
        val height = 8 + rows * rowHeight + 8
        return draw(width, height) { g ->
            val baseline = g.fontMetrics.ascent + (rowHeight - g.fontMetrics.height) / 2
            if (shown.isEmpty()) {
                g.color = muted
                g.drawString("empty stack", 10, 8 + baseline)
            }
            shown.forEachIndexed { i, value ->
                val y = 8 + i * rowHeight
                g.color = cell
                g.fillRect(8, y, width - 16, rowHeight - 2)
                g.color = if (i == 0) accent else edge
                g.stroke = BasicStroke(1f)
                g.drawRect(8, y, width - 17, rowHeight - 3)
                g.color = muted
                g.drawString(if (i == 0) "top" else "${values.size - i}", 12, y + baseline)
                g.color = ink
                g.drawString(fit(g, value, width - 58), 44, y + baseline)
            }
            if (hidden > 0) {
                g.color = muted
                g.drawString("… $hidden more below", 12, 8 + shown.size * rowHeight + baseline)
            }
        }
    }

    /** A table of [rows] under [columns], showing at most [SHOWN] rows and saying how many of [total] it left out. */
    fun table(columns: List<String>, rows: List<List<String>>, total: Int = rows.size): ByteArray {
        val shown = rows.take(SHOWN)
        val rowHeight = 16
        val cols = maxOf(1, columns.size)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().also { it.font = font }
        val widths = (0 until cols).map { c ->
            val cells = listOf(columns.getOrElse(c) { "" }) + shown.map { it.getOrElse(c) { "" } }
            (cells.maxOf { probe.fontMetrics.stringWidth(it.take(24)) } + 14).coerceIn(28, 160)
        }
        probe.dispose()
        val width = 16 + widths.sum()
        val footer = total > shown.size
        val height = 8 + rowHeight * (1 + maxOf(1, shown.size) + if (footer) 1 else 0) + 6
        return draw(maxOf(width, 120), height) { g ->
            val baseline = g.fontMetrics.ascent + (rowHeight - g.fontMetrics.height) / 2
            var x = 8
            columns.forEachIndexed { c, name ->
                g.color = accent
                g.drawString(fit(g, name, widths[c] - 10), x + 4, 8 + baseline)
                x += widths[c]
            }
            g.color = edge
            g.drawLine(8, 8 + rowHeight - 1, width - 8, 8 + rowHeight - 1)
            if (shown.isEmpty()) {
                g.color = muted
                g.drawString(if (columns.isEmpty()) "no" else "no rows", 12, 8 + rowHeight + baseline)
            }
            shown.forEachIndexed { r, row ->
                val y = 8 + rowHeight * (r + 1)
                if (r % 2 == 1) {
                    g.color = cell
                    g.fillRect(8, y, width - 16, rowHeight)
                }
                var cx = 8
                (0 until cols).forEach { c ->
                    g.color = if (c == 0) accent.darker() else ink
                    g.drawString(fit(g, row.getOrElse(c) { "" }, widths[c] - 10), cx + 4, y + baseline)
                    cx += widths[c]
                }
            }
            if (footer) {
                g.color = muted
                g.drawString("… ${total - shown.size} more", 12, 8 + rowHeight * (1 + shown.size) + baseline)
            }
        }
    }
}
