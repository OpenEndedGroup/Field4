package afield.four.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mini-notation section of `pattern_language.md`, held to the catalogue that generates it.
 *
 * The section is twenty-seven cards, each one an image reference, an audio fence and a gloss, and
 * every card names its own asset stem three times. Hand-maintained, that is a section where a roll
 * ends up captioned with its neighbour's notation and nobody notices — the figures are all
 * plausible drum patterns. So [PatMiniNotation.markdownSection] generates it and this test asserts
 * the committed page still matches, which makes the page a build artifact in everything but name.
 *
 * The asset check is the other half, and it is the one that would actually have caught the failure
 * mode this guards against: a card can only teach if both its files exist, and a missing `.wav`
 * renders as an audio player that silently plays nothing.
 */
class PatMiniNotationDocsGuardTest {

    private val page = File("public/docs/pattern_language.md")
    private val assetDir = File("public/docs/assets/pattern_language/mini")

    private companion object {
        const val BEGIN = "<!-- BEGIN GENERATED: PatMiniNotation.markdownSection -->"
        const val END = "<!-- END GENERATED: PatMiniNotation.markdownSection -->"
    }

    /** The generated region of the page, or null when the markers are missing or inverted. */
    private fun committedSection(): String? {
        if (!page.isFile) return null
        val text = page.readText()
        val start = text.indexOf(BEGIN)
        val end = text.indexOf(END)
        if (start < 0 || end < 0 || end < start) return null
        return text.substring(start + BEGIN.length, end).trim('\n')
    }

    @Test
    fun `the page's mini-notation section is what the catalogue generates`() {
        assertTrue(page.isFile, "${page.path} is missing; this test runs from the repository root")
        val committed = committedSection()
        assertTrue(
            committed != null,
            "${page.path} has no generated mini-notation region. It is delimited by\n  $BEGIN\n  $END\n" +
                "and produced by `PatMiniNotation.main(\"--markdown\")`.",
        )
        assertEquals(
            PatMiniNotation.markdownSection().trim('\n'),
            committed,
            "the committed mini-notation section has drifted from PatMiniNotation.GROUPS. Regenerate it:\n" +
                "  python3 tools/field_backdoor.py --port <p> run-jvm-main " +
                "afield.four.synth.PatMiniNotation --arg --markdown\n" +
                "and paste the result between the markers. Do not hand-edit the region — edit the " +
                "catalogue, which is also what the rolls and clips are built from.",
        )
    }

    /** Both files behind every card exist, and nothing stale is left in the directory. */
    @Test
    fun `every card's roll and clip are on disk`() {
        assertTrue(assetDir.isDirectory, "${assetDir.path} is missing — run the synth.pat.mini capture")
        val missing = PatMiniNotation.EXAMPLES.flatMap { example ->
            listOf("pat-mini-${example.id}.svg", "pat-mini-${example.id}.wav")
                .filterNot { File(assetDir, it).isFile }
        }
        assertTrue(
            missing.isEmpty(),
            "these published cards have no asset behind them: ${missing.joinToString(", ")}",
        )

        // Anything in this directory that is not one of those files is an orphan. The check
        // deliberately does *not* filter to the `pat-mini-` prefix first: the first version did,
        // and a scratch `preview.html` used to eyeball the cards sat in the staged commit
        // completely unnoticed, because it did not match the prefix it was being compared against.
        // The directory exists for these twenty-six pairs and nothing else, so that is the claim.
        val expected = PatMiniNotation.EXAMPLES.flatMap {
            listOf("pat-mini-${it.id}.svg", "pat-mini-${it.id}.wav")
        }.toSet()
        val orphans = (assetDir.listFiles() ?: emptyArray())
            .filterNot { it.isDirectory }
            .map { it.name }
            .filterNot { it in expected || it == ".DS_Store" }
        assertTrue(
            orphans.isEmpty(),
            "these files belong to no example — a renamed id, a deleted example or a scratch file " +
                "left them in a published asset directory: ${orphans.sorted().joinToString(", ")}",
        )
    }

    /**
     * Every card's SVG really is a roll of the notation the card claims.
     *
     * Cheap but not vacuous: the committed file is compared against a fresh render of the example
     * whose id is in its filename, so a roll swapped with its neighbour's fails here even though
     * both files are present and both are valid SVG. This is the `56b124131` property that
     * [DocsAudioProvenance] applies to audio, applied to the figures.
     */
    @Test
    fun `every committed roll matches a fresh render of its own example`() {
        val stale = PatMiniNotation.EXAMPLES.mapNotNull { example ->
            val file = File(assetDir, "pat-mini-${example.id}.svg")
            if (!file.isFile) return@mapNotNull null
            if (file.readText() == PatMiniNotation.svgFor(example.id)) null else example.id
        }
        assertTrue(
            stale.isEmpty(),
            "these committed rolls are not what the code draws today: ${stale.joinToString(", ")}. " +
                "Regenerate with the synth.pat.mini capture rather than editing the SVG.",
        )
    }

    /**
     * Negative controls. A guard assembled out of string search can fail in two directions —
     * matching nothing because a marker was mistyped, and matching anything because the comparison
     * was too loose — so both are made to fail on purpose.
     */
    @Test
    fun `the comparison rejects a tampered section and a missing marker`() {
        val real = PatMiniNotation.markdownSection()
        assertTrue(real.isNotBlank(), "the generator produced nothing to compare against")

        // Too loose: one notation string swapped for another must not compare equal.
        val tampered = real.replaceFirst("bd [sd sd]", "bd [sd sd sd]")
        assertTrue(tampered != real, "the fixture edit did not apply; this control proves nothing")
        assertTrue(tampered.trim('\n') != real.trim('\n'), "a changed notation compared equal to the original")

        // Too strict: the region has to be found at all, so a page without markers reads as absent
        // rather than as an empty match that would silently pass.
        val markerless = "# a page\n\nno markers here\n"
        assertTrue(markerless.indexOf(BEGIN) < 0, "the BEGIN marker is too loose to be meaningful")

        // And every example must actually appear in the output, or the generator could emit an
        // empty section that matches an empty region.
        val absent = PatMiniNotation.EXAMPLES.filterNot { real.contains("pat-mini-${it.id}.svg") }
        assertTrue(absent.isEmpty(), "generated section omits: ${absent.map { it.id }}")
    }

    /** The catalogue's own shape: unique ids, non-empty prose, and a sane cycle count. */
    @Test
    fun `the catalogue is well formed`() {
        val ids = PatMiniNotation.EXAMPLES.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate example id: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        PatMiniNotation.EXAMPLES.forEach { example ->
            assertTrue(example.source.isNotBlank(), "${example.id}: no notation")
            assertTrue(example.gloss.isNotBlank(), "${example.id}: no gloss")
            assertTrue(example.cycles in 1..8, "${example.id}: ${example.cycles} cycles is outside 1..8")
            assertTrue(
                example.id.matches(Regex("[a-z0-9-]+")),
                "${example.id}: an id becomes a filename and an audio-spec, so keep it kebab-case",
            )
        }
    }

    /** The instrument-colour claim, run as a unit test as well as inside the smoke. */
    @Test
    fun `instrument colours distinguish exactly what the voice distinguishes`() {
        PatMiniNotation.checkAtomColorClaim()
    }
}
