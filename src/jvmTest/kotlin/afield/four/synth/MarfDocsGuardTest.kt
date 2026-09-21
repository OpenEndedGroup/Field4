package afield.four.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `marf.md`'s figures, held to the gallery that draws them.
 *
 * Seven figures, each referenced once by the page and each backed by one committed SVG. Kept by
 * hand, that is a page where a figure ends up captioned with its neighbour's claim and nobody
 * notices, because every figure is a plausible-looking set of curves. So the checks here are the
 * two that catch it: the page and the catalogue must agree on which figures exist, and each
 * committed file must be what the code draws for *that* id today.
 *
 * That pair is not enough, and the way it was not enough is on the record. It re-renders every SVG
 * and it pins figure-to-page correspondence, but it **cannot see a snippet** — so the "Banks, and
 * the readers over them" listing shipped for a day with `fields = MarfFieldClock.STAGES` dropped
 * from all three of its reads while the gallery kept it on all three lanes. The page printed code
 * that cannot produce the picture above it, and the prose under that code went on describing the
 * behaviour of the argument it had just deleted. The same omission shipped a second time in the
 * `fline` listing further down.
 *
 * Two checks are here for that, and they are the ones worth adding rather than the two corrections:
 *
 * - a figure's [MarfGallery.Figure.snippet] must appear **verbatim** in a fence on the page, and
 *   the clocks spelled in it must be exactly the clocks its lanes were built with;
 * - and page-wide, every reader constructed in a Kotlin fence with an alternation address must name
 *   a `fields`, because an alternation under the default `CYCLES` is the mistake both of those
 *   listings were.
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

    // ── the snippets, which the checks above cannot see ───────────────────────────────────

    /** Every fenced Kotlin block on the page, fence markers stripped. */
    private fun kotlinFences(text: String): List<String> =
        Regex("""(?m)^```diffstatic \{data-lang=kotlin}\n(.*?)^```$""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    /** The `MarfFieldClock.X` constants named in [snippet], as the enum. */
    private fun clocksNamedIn(snippet: String): Set<MarfFieldClock> =
        Regex("""MarfFieldClock\.([A-Z]+)""").findAll(snippet)
            .map { match ->
                MarfFieldClock.entries.firstOrNull { it.name == match.groupValues[1] }
                    ?: error("the page names MarfFieldClock.${match.groupValues[1]}, which is not a constant")
            }
            .toSet()

    /**
     * Each reader-constructing call in [fence], as its own source text.
     *
     * Paren-balanced from the call's own opening bracket, so a multi-line `marf(...)` comes back as
     * one string and the `fields` argument cannot be separated from the call it belongs to. A
     * string literal is skipped over rather than scanned, so a `")"` inside `pat("…")` cannot close
     * the call early.
     */
    private fun readerCalls(fence: String): List<String> {
        val out = mutableListOf<String>()
        for (opening in Regex("""(\bmarf|\bmarfIn|\.read)\(""").findAll(fence)) {
            var depth = 0
            var i = opening.range.last
            var inString = false
            while (i < fence.length) {
                val ch = fence[i]
                when {
                    inString && ch == '\\' -> i++
                    ch == '"' -> inString = !inString
                    inString -> Unit
                    ch == '(' -> depth++
                    ch == ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            out += fence.substring(opening.range.first, minOf(i + 1, fence.length))
        }
        return out
    }

    /** True when [call] addresses or values a reader with a mini-notation alternation. */
    private fun usesAlternation(call: String): Boolean = "pat(\"<" in call

    @Test
    fun `a figure's published snippet is on the page, verbatim`() {
        val text = page.readText()
        val fences = kotlinFences(text)
        assertTrue(fences.size >= 6, "the fence scanner found only ${fences.size} Kotlin blocks; it is broken")

        val missing = MarfGallery.FIGURES
            .filter { it.snippet != null }
            .filterNot { figure -> fences.any { it.contains(figure.snippet!!) } }
            .map { it.id }
        assertTrue(
            missing.isEmpty(),
            "these figures publish a snippet the page does not contain, character for character: " +
                "$missing. Either the page drifted or the gallery did; they are meant to be one string."
        )
    }

    @Test
    fun `a figure's snippet names exactly the clocks its lanes were built with`() {
        for (figure in MarfGallery.FIGURES) {
            val snippet = figure.snippet ?: continue
            val named = clocksNamedIn(snippet)
            // CYCLES is the default and is spelled by omission, so it is not required in the text.
            val used = figure.pageClocks - MarfFieldClock.CYCLES
            assertEquals(
                used,
                named,
                "${figure.id}: the published snippet names $named, and the lanes that drew the " +
                    "figure used ${figure.pageClocks}. A snippet that drops `fields = …` while the " +
                    "code keeps it is a page describing a picture it cannot produce — which is " +
                    "exactly what shipped on `two-readers`."
            )
        }
    }

    @Test
    fun `no listing on the page builds a reader on an alternation without naming a clock`() {
        val calls = kotlinFences(page.readText()).flatMap { readerCalls(it) }
        assertTrue(calls.size >= 8, "the call scanner found only ${calls.size} reader calls; it is broken")

        val silent = calls.filter { usesAlternation(it) && "fields" !in it }
        assertTrue(
            silent.isEmpty(),
            "these listings read an alternation address under the default CYCLES clock, where it is " +
                "latched at whatever wall-clock instant a stage happens to begin rather than " +
                "stepping one entry per stage — name a fields argument or use a bare step list:\n" +
                silent.joinToString("\n\n")
        )
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

    /**
     * Negative controls for the two snippet checks, run against the page that actually shipped.
     *
     * A guard written after the fact has to be shown to fail on the defect it was written for,
     * otherwise it is a test of nothing. So the fixtures here are the real `two-readers` listing as
     * it stood in `bc71ed216` — three reads with the `fields` argument deleted — and the real
     * `fline` listing beside it.
     */
    @Test
    fun `both snippet checks fail on the listings that actually shipped`() {
        val asShipped = """
            ```diffstatic {data-lang=kotlin}
            val b = marfBank(
                rampTo(0.1, 1.0), rampTo(0.9, 0.5), holdAt(0.4, 2.0),
                oscTo(0.7, 1.0, cycles = 3.0), curveTo(0.2, 0.25, amount = 4.0)
            )

            val a = b.read(pat("<0 1 2 1 3 4>").nums())
            val c = b.read(pat("<0 2 4 1 3>").nums().slow(3))
            val d = b.read(pat("<0 4>").nums(), speed = constant(4.0))
            ```

            ```diffstatic {data-lang=kotlin}
            val x = b.read(pat("<0 1 2 3 4>").nums())
            val y = b.read(pat("<0 2 4 1 3>").nums().slow(3))
            ```
        """.trimIndent() + "\n"

        val fences = kotlinFences(asShipped)
        assertEquals(2, fences.size, "the fixture did not parse as two fences; this control proves nothing")

        // 1. The verbatim check: the figure's real snippet is not in the page as it shipped.
        val twoReaders = MarfGallery.FIGURES.first { it.id == "two-readers" }
        assertTrue(twoReaders.snippet != null, "the two-readers figure must publish a snippet")
        assertTrue(
            fences.none { it.contains(twoReaders.snippet!!) },
            "the verbatim check would have passed on the page that shipped, so it is worthless"
        )

        // 2. The clock check: the shipped listing names no clock, and all three lanes used STAGES.
        assertEquals(setOf(MarfFieldClock.STAGES), twoReaders.pageClocks)
        assertEquals(emptySet(), clocksNamedIn(fences[0]), "the shipped listing named no clock at all")

        // 3. The page-wide check: five reader calls, and all five are the defect.
        val calls = fences.flatMap { readerCalls(it) }
        assertEquals(5, calls.size, "the call scanner found ${calls.size} calls in the fixture, not 5")
        assertEquals(
            5,
            calls.count { usesAlternation(it) && "fields" !in it },
            "every one of the five shipped calls reads an alternation with no clock named: $calls"
        )

        // And the other direction, on the corrected text rather than on a sed of the broken one:
        // the figure's own published snippet must pass every check, or the guard is just a wall.
        val fixed = readerCalls(twoReaders.snippet!!)
        assertEquals(3, fixed.size, "the corrected listing still reads the bank three times: $fixed")
        assertEquals(
            0,
            fixed.count { usesAlternation(it) && "fields" !in it },
            "the guard must accept the fix as well as reject the defect: $fixed"
        )
        assertEquals(setOf(MarfFieldClock.STAGES), clocksNamedIn(twoReaders.snippet!!))
    }
}
