package afield.logger

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Values are colored relative to the *previous* line the same channel emitted, so each test uses
 * its own channel name to avoid cross-test history leakage (and cleans up after itself).
 */
class StatsHeatmapTest {

    private val usedChannels = mutableListOf<String>()

    private fun channel(name: String): String {
        usedChannels += name
        return name
    }

    @AfterTest
    fun resetHistory() {
        usedChannels.forEach { StatsHeatmap.reset(it) }
    }

    @Test
    fun `first sighting of a channel is grey since there is no history to compare against`() {
        val ch = channel("first")
        val fragments = StatsHeatmap.fragments(ch, "foo=21.3ms")
        // key+sep, value+unit
        assertEquals(listOf("foo=", "21.3ms"), fragments.map { it.text })
        assertEquals(TextColorsAccess.gray, fragments[1].style)
    }

    @Test
    fun `parses key=value, key value, and key colon value forms in one line`() {
        val ch = channel("mixed-forms")
        val fragments = StatsHeatmap.fragments(ch, "foo=21.3ms bar 33.4s gux:102.2%")
        // The key+separator (unstyled) and the value+unit (heatmap-styled) are separate fragments.
        assertEquals(
            listOf("foo=", "21.3ms", " ", "bar ", "33.4s", " ", "gux:", "102.2%"),
            fragments.map { it.text }
        )
    }

    @Test
    fun `identical value renders grey, not the increasing or decreasing color`() {
        val ch = channel("unchanged")
        StatsHeatmap.fragments(ch, "fps=60.0")
        val second = StatsHeatmap.fragments(ch, "fps=60.0")
        assertEquals(TextColorsAccess.gray, second.last().style)
    }

    @Test
    fun `an increasing value and a decreasing value get different styles`() {
        val risingChannel = channel("direction-up")
        StatsHeatmap.fragments(risingChannel, "load=50.0")
        val increased = StatsHeatmap.fragments(risingChannel, "load=75.0")

        val fallingChannel = channel("direction-down")
        StatsHeatmap.fragments(fallingChannel, "load=50.0")
        val decreased = StatsHeatmap.fragments(fallingChannel, "load=25.0")

        assertNotEquals(increased.last().style, decreased.last().style)
        assertNotEquals(TextColorsAccess.gray, increased.last().style)
        assertNotEquals(TextColorsAccess.gray, decreased.last().style)
    }

    @Test
    fun `unrelated keys on the same channel are tracked independently`() {
        val ch = channel("independent-keys")
        StatsHeatmap.fragments(ch, "foo=10.0 bar=10.0")
        val second = StatsHeatmap.fragments(ch, "foo=20.0 bar=10.0")
        val foo = second[1] // fragments: "foo=", "20.0", " ", "bar=", "10.0"
        val bar = second[4]
        assertNotEquals(TextColorsAccess.gray, foo.style, "foo rose, should not be grey")
        assertEquals(TextColorsAccess.gray, bar.style, "bar unchanged, should be grey")
    }

    @Test
    fun `reset drops history so the next line looks like a first sighting again`() {
        val ch = channel("reset-me")
        StatsHeatmap.fragments(ch, "n=1")
        StatsHeatmap.reset(ch)
        // Without reset, jumping from 1 to 999 would saturate the "increasing" end of the scale;
        // with history cleared it should read as a fresh first sighting (grey) instead.
        val fragments = StatsHeatmap.fragments(ch, "n=999")
        assertEquals(TextColorsAccess.gray, fragments.last().style)
    }
}

/** Exposes the exact grey used by [StatsHeatmap] for equality checks in tests. */
private object TextColorsAccess {
    val gray = com.github.ajalt.mordant.rendering.TextColors.gray
}
