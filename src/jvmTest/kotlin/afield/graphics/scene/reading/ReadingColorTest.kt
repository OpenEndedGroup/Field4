package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.Vector4f
import kotlin.test.*

class ReadingColorTest {
    private val font = Typefaces.named("Serif", 100)
    private val atlas = SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
    private val red = Vector4f(.6f, 0f, 0f, .8f)
    private val teal = Vector4f(0f, .45f, .56f, .8f)

    private fun close(a: Vector4f, b: Vector4f) =
        assertTrue(listOf(a.x - b.x, a.y - b.y, a.z - b.z, a.w - b.w).all { kotlin.math.abs(it) < 1e-5f }, "$a != $b")

    @Test
    fun aTintFlaresThenSettlesAndTheLatestStartedCueGoverns() {
        val graph = readingDiagram { dot("d", point("p", 0, 0, pinned = true).anchor) }
        val d = graph.dot("d")
        val score = diagramScore(graph, 20.0) {
            tint(d, red, at = 2.0, seconds = 2.0) // flare: 1 -> 0
            tint(d, teal, at = 10.0, seconds = 1.0, from = 0f, to = 1f) // stage colour, then held
            tint(d, teal, at = 6.0, seconds = 1.0, from = 1f, to = 1f) // added out of order on purpose
        }
        val white = Vector4f(1f, 1f, 1f, .8f)
        assertNull(score.pose(d, 1.0).tint) // before any cue, nothing
        close(Vector4f(.6f, 0f, 0f, .8f), score.pose(d, 2.0).tinted(white))
        close(white, score.pose(d, 4.5).tinted(white)) // settled back, alpha kept
        close(Vector4f(0f, .45f, .56f, .8f), score.pose(d, 7.0).tinted(white)) // held
        close(white, score.pose(d, 10.0).tinted(white)) // the later cue starts at 0
        close(Vector4f(0f, .45f, .56f, .8f), score.pose(d, 15.0).tinted(white))
    }

    @Test
    fun colouredPartsReplaceRgbMultiplyAlphaAndTakeTheRunsTint() {
        val graph = readingDiagram {
            text("run", "f (x)", point("p", 0, 0, pinned = true)) {
                color = Vector4f(1f, 1f, 1f, .5f)
                characters(2, 3).color(red)
                characters(4, 5).color(red)
            }
        }
        val run = graph.text("run")
        val key = readingPreparationKey(run, font)
        assertEquals(2, key.colors.size)
        assertNotEquals(key, readingPreparationKey(readingDiagram { text("run", "f (x)", point("p", 0, 0, pinned = true)) }.text("run"), font))
        val layout = prepareReadingLayout(run, key)
        val plain = ReadingText(run.text, 1f, layout.toFLine(), atlas, layout)
        val brackets = plain.copy(color = red)
        val shape = plain.copy(renderParts = listOf(plain, brackets))
        val player = ReadingDiagramPlayer(graph, font, mapOf("run" to shape), diagramScore(graph, 10.0) {
            tint(run, teal, at = 0.0, seconds = 4.0)
        }, mapOf(run to shape)).apply { gravity = 0f; wind = 0f; playback.playing = false }
        player.update(0.0)
        assertEquals(2, player.labels.size)
        close(Vector4f(0f, .45f, .56f, .5f), player.labels[0].color) // run colour, fully tinted
        close(Vector4f(0f, .45f, .56f, .4f), player.labels[1].color) // span alpha .8 x run alpha .5
        player.playback.seek(4.0)
        player.update(0.0)
        close(Vector4f(1f, 1f, 1f, .5f), player.labels[0].color)
        close(Vector4f(.6f, 0f, 0f, .4f), player.labels[1].color)
    }
}

class ReadingSaturationTest {
    @Test
    fun saturationFlaresEachColourTowardItsOwnHueThenSettles() {
        val graph = readingDiagram { dot("d", point("p", 0, 0, pinned = true).anchor) }
        val d = graph.dot("d")
        val score = diagramScore(graph, 10.0) { saturate(d, at = 1.0, seconds = 2.0, from = 3f) }
        val warm = Vector4f(250f / 255, 249f / 255, 211f / 255, .8f)
        val cool = Vector4f(220f / 255, 235f / 255, 197f / 255, .8f)
        val w = score.pose(d, 1.0).tinted(warm)
        val c = score.pose(d, 1.0).tinted(cool)
        assertTrue(w.z < warm.z - .1f && w.x >= warm.x) // warm off-white flares yellow
        assertTrue(c.y > c.x && c.x < cool.x - .05f) // cool off-white flares green
        assertEquals(.8f, w.w)
        val settled = score.pose(d, 3.5).tinted(warm)
        assertEquals(warm.z, settled.z, 1e-5f)
        assertNull(score.pose(d, .5).tint)
        assertEquals(0f, score.pose(d, .5).saturation)
    }
}

class ReadingPaletteTest {
    private val palette = DiagramPalette.hex(
        "990000", "ac2900", "ca5b09", "dd872a", "e6b058", "ebd189", "f2e9b4", "faf9d3", "ffffe0",
        "f4f9d7", "dcebc5", "b4d8b2", "7ebea0", "419e95", "00738f", "003b86", "220080",
    )

    @Test
    fun stopsSitEvenlyOnTheRampAndWhiteIsNeutral() {
        assertEquals(0f, palette.coordinate(palette.stops.first()), 1e-5f)
        assertEquals(1f, palette.coordinate(palette.stops[8]), 1e-5f)
        assertEquals(2f, palette.coordinate(palette.stops.last()), 1e-5f)
        assertEquals(1f, palette.coordinate(Vector4f(1f)), .02f) // white lands on the cream middle
        for (u in listOf(.1f, .7f, 1.3f, 1.9f)) assertEquals(u, palette.coordinate(palette.at(u)), 1e-3f)
        assertFailsWith<IllegalArgumentException> { DiagramPalette.hex("000000", "ffffff") }
    }

    @Test
    fun withAPaletteSaturationPushesWarmAndCoolTowardTheirOwnEnds() {
        val graph = readingDiagram { dot("d", point("p", 0, 0, pinned = true).anchor) }
        val d = graph.dot("d")
        val score = diagramScore(graph, 10.0) { saturate(d, at = 1.0, seconds = 2.0, from = 2f) }
        val pose = score.pose(d, 1.0)
        val warm = pose.tinted(palette, palette.stops[7].apply { w = .8f }) // u = .875
        val cool = pose.tinted(palette, palette.stops[10].apply { w = .8f }) // u = 1.25
        assertEquals(.625f, palette.coordinate(warm), 1e-3f)
        assertEquals(1.75f, palette.coordinate(cool), 1e-3f)
        assertEquals(.8f, warm.w)
        val settled = score.pose(d, 4.0)
        assertEquals(1.25f, palette.coordinate(settled.tinted(palette, palette.stops[10])), 1e-3f)
    }
}
