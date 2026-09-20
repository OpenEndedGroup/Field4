package afield.four.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `marf.md`'s figures, held to the gallery that draws them.
 *
 * Six figures, each referenced once by the page and each backed by one committed SVG. Kept by hand,
 * that is a page where a figure ends up captioned with its neighbour's claim and nobody notices,
 * because every figure is a plausible-looking set of curves. So the checks here are the two that
 * catch it: the page and the catalogue must agree on which figures exist, and each committed file
 * must be what the code draws for *that* id today.
 *
 * The same property [DocsAudioProvenance] applies to audio, applied to line art.
 */
class MarfDocsGuardTest {

    private val page = File("public/docs/marf.md")
    private val assetDir = File("public/docs/assets/marf")

    private fun assetName(id: String) = "marf-$id.svg"

    @Test
    fun `every figure on the page has an asset behind it`() {
        assertTrue(assetDir.isDirectory, "${assetDir.path} is missing — run the synth.marf.gallery capture")
        val missing = MarfGallery.FIGURES.map { assetName(it.id) }.filterNot { File(assetDir, it).isFile }
        assertTrue(
            missing.isEmpty(),
            "these published figures have no asset behind them: ${missing.joinToString(", ")}"
        )
    }

    @Test
    fun `nothing stale is left in the asset directory`() {
        assertTrue(assetDir.isDirectory, "${assetDir.path} is missing")
        // Not filtered to the `marf-` prefix first: this directory exists for these figures and
        // nothing else, so a scratch preview file left behind is an orphan too.
        val expected = MarfGallery.FIGURES.map { assetName(it.id) }.toSet()
        val orphans = (assetDir.listFiles() ?: emptyArray())
            .filterNot { it.isDirectory }
            .map { it.name }
            .filterNot { it in expected || it == ".DS_Store" }
        assertTrue(
            orphans.isEmpty(),
            "these files belong to no figure — a renamed id, a deleted figure or a scratch file " +
                "left them in a published asset directory: ${orphans.sorted().joinToString(", ")}"
        )
    }

    @Test
    fun `every committed figure matches a fresh render of its own id`() {
        val stale = MarfGallery.FIGURES.mapNotNull { figure ->
            val file = File(assetDir, assetName(figure.id))
            if (!file.isFile) return@mapNotNull null
            if (file.readText() == MarfGallery.render(figure).svg) null else figure.id
        }
        assertTrue(
            stale.isEmpty(),
            "these committed figures are not what the code draws today: ${stale.joinToString(", ")}. " +
                "Regenerate with the synth.marf.gallery capture rather than editing the SVG."
        )
    }

    @Test
    fun `the page references every figure, and references no other asset`() {
        assertTrue(page.isFile, "${page.path} is missing; this test runs from the repository root")
        val text = page.readText()

        val unreferenced = MarfGallery.FIGURES.filterNot { text.contains("assets/marf/${assetName(it.id)}") }
        assertTrue(
            unreferenced.isEmpty(),
            "the gallery draws these figures and the page never shows them: " +
                "${unreferenced.map { it.id }}. Either reference them or delete them."
        )

        val referenced = Regex("""assets/marf/([A-Za-z0-9_.-]+)""").findAll(text).map { it.groupValues[1] }.toSet()
        val known = MarfGallery.FIGURES.map { assetName(it.id) }.toSet()
        assertEquals(
            emptySet(),
            referenced - known,
            "the page references assets no figure produces, so they will render as broken images"
        )
    }

    @Test
    fun `the catalogue is well formed`() {
        val ids = MarfGallery.FIGURES.map { it.id }
        assertEquals(ids.distinct(), ids, "two figures share an id, so one would overwrite the other")
        for (figure in MarfGallery.FIGURES) {
            assertTrue(figure.id.matches(Regex("[a-z0-9-]+")), "${figure.id}: ids are lowercase and hyphenated")
            assertTrue(figure.title.isNotBlank(), "${figure.id}: no title")
            assertTrue(figure.subtitle.isNotBlank(), "${figure.id}: no subtitle")
            assertTrue(figure.cycles in 1..64, "${figure.id}: ${figure.cycles} cycles is not a plottable window")
            assertTrue(figure.lanes.isNotEmpty(), "${figure.id}: no lanes")
            val keys = figure.lanes.map { it.key }
            assertEquals(keys.distinct(), keys, "${figure.id}: two lanes share a key, so one's measurements are lost")
        }
    }

    /**
     * Every figure still passes its own checks, without writing anything.
     *
     * `synth.marf.gallery` runs this too, but it runs through the backdoor and is not part of
     * `jvmTest`. A figure whose claim has stopped being true should fail in the ordinary test run
     * as well, because that is the one anybody runs.
     */
    @Test
    fun `every figure passes the gallery's own assertions`() {
        for (figure in MarfGallery.FIGURES) {
            MarfGallery.checkFigure(figure, MarfGallery.render(figure))
        }
    }

    /**
     * Negative controls. A guard assembled out of string search can fail in two directions, so both
     * are made to fail here: a tampered SVG must not compare equal, and the page-reference check
     * must not pass on a page that mentions nothing.
     */
    @Test
    fun `the comparison rejects a tampered figure and an empty page`() {
        val figure = MarfGallery.FIGURES.first()
        val real = MarfGallery.render(figure).svg
        assertTrue(real.contains("<polyline"), "a figure with no polyline is not plotting anything")

        val tampered = real.replaceFirst("<polyline", "<polyline data-tampered=\"1\"")
        assertTrue(tampered != real, "the fixture edit did not apply; this control proves nothing")

        val markerless = "# a page\n\nno figures here\n"
        assertTrue(
            !markerless.contains("assets/marf/${assetName(figure.id)}"),
            "the reference check is too loose to be meaningful"
        )
    }
}
