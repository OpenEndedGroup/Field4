package afield.stdlib

import afield.four.util.CameraPose
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Tests for `keyframeBrowser.kt` — the slot-major pivot over a *collection* of saved keys.
 *
 * Every collection here is built by calling the real [KeyframeScope.saveKey], not by writing JSON
 * by hand. That matters more than it looks: the single most load-bearing behaviour in this file is
 * that [keyFilesMatching] does **not** list the numbered snapshots inside a key's `.versions`
 * directory, and a hand-written fixture would simply not have any. Only a real save produces the
 * history that the exclusion has to step over, so only a real save can prove it is stepped over.
 *
 * No GPU and no server. A `KeyframeScope` with no targets saves and loads perfectly well — the
 * targets are only consulted on capture and apply — so the whole browse path is reachable from a
 * plain unit test.
 */
class KeyframeBrowserTest {

    private fun tempDir(): Path = Files.createTempDirectory("keyframe-browser-test")

    /** Save [values] to `<dir>/<name>` through the real writer, so a real `.versions` history exists. */
    private fun save(
        dir: Path,
        name: String,
        vararg values: Pair<String, Any?>,
        note: String? = null,
        schemaVersion: Int = 0
    ): Path {
        val scope = KeyframeScope(schemaVersion)
        val path = dir.resolve(name)
        scope.saveKey(path, scope.Key(values.toMap().toMutableMap()), note)
        return path
    }

    // -----------------------------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------------------------

    /**
     * The exclusion the whole browser rests on.
     *
     * Three saves of one key leave three numbered snapshots and a manifest in `looks.json.versions`,
     * all of them valid `.json` files sitting under the search root. A naive recursive glob reports
     * five files where there is one key; this reports one, and finds the other four through
     * [KeyframeCollection.slotHistory] instead.
     */
    @Test
    fun globSkipsTheVersionsDirectoryEvenThoughItIsFullOfJson() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val path = dir.resolve("looks.json")
        repeat(3) { i -> scope.saveKey(path, scope.Key(mutableMapOf("a" to i.toFloat())), "save $i") }

        val everyJsonOnDisk = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.count() }
        assertEquals(
            5, everyJsonOnDisk,
            "expected the key, three numbered snapshots and a manifest — the premise of this test"
        )

        val matched = keyFilesMatching(dir, "**/*.json")
        assertEquals(listOf(path), matched, "the .versions snapshots must not be listed as keys")
    }

    /** A bare `*.json` finds keys in subdirectories, the way FileManager's alternate pattern does. */
    @Test
    fun aBarePatternStillSearchesSubdirectories() {
        val dir = tempDir()
        dir.resolve("looks").createDirectories()
        save(dir, "top.json", "a" to 1f)
        save(dir.resolve("looks"), "nested.json", "a" to 2f)

        assertEquals(
            listOf("looks/nested.json", "top.json"),
            keyFilesMatching(dir, "*.json").map { dir.relativize(it).toString() }.sorted()
        )
    }

    /** A pattern that names a directory is honoured literally rather than widened. */
    @Test
    fun aDirectoryQualifiedPatternDoesNotEscapeThatDirectory() {
        val dir = tempDir()
        dir.resolve("looks").createDirectories()
        save(dir, "top.json", "a" to 1f)
        save(dir.resolve("looks"), "nested.json", "a" to 2f)

        assertEquals(
            listOf("looks/nested.json"),
            keyFilesMatching(dir, "looks/*.json").map { dir.relativize(it).toString() }
        )
    }

    // -----------------------------------------------------------------------------------------
    // Coverage and drift
    // -----------------------------------------------------------------------------------------

    /**
     * The browser's most useful column, and the one a file-major listing cannot show at all: a slot
     * present in some files and not others, with the schema versions that bracket the change.
     */
    @Test
    fun aSlotPresentInSomeFilesReportsPartialCoverage() {
        val dir = tempDir()
        save(dir, "a.json", "glow@radius" to 2f, schemaVersion = 0)
        save(dir, "b.json", "glow@radius" to 5f, schemaVersion = 0)
        save(dir, "c.json", "glow@radius" to 9f, "blur@radius" to 12f, schemaVersion = 1)

        val c = KeyframeCollection.load(dir, "**/*.json")
        assertEquals(3, c.files.size)
        assertEquals(listOf(0, 1), c.schemaVersions, "two schema versions is the drift signal")

        val glow = c.slot("glow@radius")
        assertEquals(3, glow.presentIn)
        assertEquals(3, glow.total)

        val blur = c.slot("blur@radius")
        assertEquals(1, blur.presentIn)
        assertEquals(3, blur.total)
    }

    /**
     * A name the live scope watches that no file holds. This is the one fact a collection cannot
     * derive from itself, and the reason `Box.keyframes` takes a scope at all.
     */
    @Test
    fun aScopeOnlySlotIsListedAsAbsentAndFlagged() {
        val dir = tempDir()
        save(dir, "a.json", "glow@radius" to 2f)

        val scope = KeyframeScope()
        scope.floatTarget("glow@radius", { 2f }, {})
        scope.floatTarget("glow@falloff", { 0.5f }, {})

        val c = KeyframeCollection.load(dir, "**/*.json", scope)
        assertContains(c.slotNames, "glow@falloff", "a scope-only name still gets a row")

        val falloff = c.slot("glow@falloff")
        assertEquals("absent", falloff.kind)
        assertEquals(0, falloff.presentIn)
        assertTrue(falloff.inScope)
        val note = falloff.display as SlotDisplay.Note
        assertEquals("warn", note.severity)
        assertContains(note.text, "in no key on disk")
    }

    /** Without a scope there is nothing to compare against, so an unsaved name simply is not known. */
    @Test
    fun withoutAScopeThereAreNoScopeOnlySlots() {
        val dir = tempDir()
        save(dir, "a.json", "glow@radius" to 2f)
        val c = KeyframeCollection.load(dir, "**/*.json")
        assertEquals(listOf("glow@radius"), c.slotNames)
    }

    // -----------------------------------------------------------------------------------------
    // Ranges, per type
    // -----------------------------------------------------------------------------------------

    /** A numeric slot: the band spans the extremes and every value lands at its own fraction. */
    @Test
    fun aNumericSlotBecomesABandWithOneMarkPerFile() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 2f)
        save(dir, "b.json", "r" to 6f)
        save(dir, "c.json", "r" to 10f)

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("r")
        assertEquals("num", slot.kind)
        assertEquals("float", slot.typeLabel)
        val range = slot.display as SlotDisplay.Range
        assertEquals(2.0, range.band.lo)
        assertEquals(10.0, range.band.hi)
        assertEquals(listOf(0.0, 0.5, 1.0), range.band.marks)
        assertEquals("2 – 10", slot.summaryText)
    }

    /**
     * A slot that never moved. Dividing by a zero span would put every mark at NaN and draw nothing;
     * they go to the middle instead, and the summary says one number rather than a range of one.
     */
    @Test
    fun aFrozenNumericSlotDoesNotDivideByZero() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 4f)
        save(dir, "b.json", "r" to 4f)

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("r")
        val range = slot.display as SlotDisplay.Range
        assertEquals(listOf(0.5, 0.5), range.band.marks)
        assertEquals("4", slot.summaryText)
        assertTrue(range.band.marks.none { it.isNaN() })
    }

    /** An `Int` slot and a `Float` slot under one name is a real hazard on apply, so the label says both. */
    @Test
    fun theNumericTypeLabelNamesEveryNumKindPresent() {
        val dir = tempDir()
        save(dir, "a.json", "n" to 1)
        save(dir, "b.json", "n" to 2f)

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("n")
        assertEquals("num", slot.kind, "both are Num, so this is not a type change")
        assertEquals("float/int", slot.typeLabel)
    }

    /** A vec3 gets one band per component, each scaled independently. */
    @Test
    fun aVectorSlotBecomesOneBandPerComponent() {
        val dir = tempDir()
        save(dir, "a.json", "p" to Vector3f(0f, 5f, 1f))
        save(dir, "b.json", "p" to Vector3f(10f, 5f, 3f))

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("p")
        val comps = slot.display as SlotDisplay.Components
        assertEquals(listOf("x", "y", "z"), comps.bands.map { it.label })
        assertEquals(0.0 to 10.0, comps.bands[0].lo to comps.bands[0].hi)
        assertEquals(5.0 to 5.0, comps.bands[1].lo to comps.bands[1].hi)
        assertEquals("x 0 – 10", slot.summaryText, "the widest component is the one worth naming")
    }

    /** A vec4 inside the unit cube reads as colour — and keeps its bands, so the guess hides nothing. */
    @Test
    fun aUnitVec4IsShownAsSwatchesAsWellAsBands() {
        val dir = tempDir()
        save(dir, "a.json", "tint" to Vector4f(1f, 0f, 0f, 1f))
        save(dir, "b.json", "tint" to Vector4f(0f, 0.5f, 1f, 1f))

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("tint")
        val sw = slot.display as SlotDisplay.Swatches
        assertEquals(listOf("#ff0000", "#0080ff"), sw.colors)
        assertEquals(4, sw.bands.size, "the component bands ride along with the swatches")
        assertEquals("2 swatches", slot.summaryText)
    }

    /** A vec4 with a component outside 0..1 is not a colour, and is not drawn as one. */
    @Test
    fun aVec4OutsideTheUnitCubeIsNotTreatedAsColour() {
        val dir = tempDir()
        save(dir, "a.json", "v" to Vector4f(1f, 0f, 0f, 1f))
        save(dir, "b.json", "v" to Vector4f(0f, 0f, 40f, 1f))

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("v")
        assertTrue(
            slot.display is SlotDisplay.Components,
            "a vec4 reaching 40 is a position or a plane, not an RGBA — got ${slot.display}"
        )
    }

    /**
     * A camera slot. The arc is checked against an independently computed angle rather than against
     * a second call to the code under test, and the plan-view scatter is checked for the joint
     * normalisation that keeps a one-axis move from being stretched into a full-width line.
     */
    @Test
    fun aCameraSlotReportsItsArcAndItsPlanViewScatter() {
        val dir = tempDir()
        val straight = CameraPose(Quaternionf(), Vector3f(0f, 0f, 0f), 5f)
        // A quarter turn about y, built from its own axis-angle rather than from anything under test.
        val quarter = Quaternionf(0f, kotlin.math.sin(Math.PI.toFloat() / 4f), 0f, kotlin.math.cos(Math.PI.toFloat() / 4f))
        val turned = CameraPose(quarter, Vector3f(4f, 0f, 0f), 5f)

        save(dir, "a.json", "camera" to straight)
        save(dir, "b.json", "camera" to turned)

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("camera")
        assertEquals("cameraPose", slot.kind)
        assertEquals("pose", slot.typeLabel)
        val poses = slot.display as SlotDisplay.Poses
        assertTrue(
            abs(poses.arcDegrees - 90.0) < 0.5,
            "a quarter turn should read as ~90°, got ${poses.arcDegrees}"
        )
        // x runs 0..4 and z is 0 throughout; normalising both over the same 0..4 extent puts the two
        // points at x = 0 and x = 1 with z pinned at 0, rather than stretching a flat z into a range.
        assertEquals(listOf(0.0, 0.0), poses.points[0])
        assertEquals(listOf(1.0, 0.0), poses.points[1])
        assertEquals(4, poses.bands.size, "x, y, z and the focus distance")
        assertEquals("focus", poses.bands[3].label)
    }

    /**
     * The split that is not cosmetic: code steps, so it has variants rather than a range. Grouping is
     * by source text and the most common variant comes first.
     */
    @Test
    fun aCodeSlotBecomesCountedVariantsRatherThanARange() {
        val dir = tempDir()
        save(dir, "a.json", "field" to stackSLString("x y +"))
        save(dir, "b.json", "field" to stackSLString("x y +"))
        save(dir, "c.json", "field" to stackSLString("x y *"))

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("field")
        assertEquals("stacksl", slot.kind)
        val variants = (slot.display as SlotDisplay.Variants).variants
        assertEquals(2, variants.size)
        assertEquals("x y +", variants[0].text)
        assertEquals(2, variants[0].count)
        assertEquals(listOf(0, 1), variants[0].fileIds)
        assertEquals(1, variants[1].count)
        assertEquals("2 variants", slot.summaryText)
    }

    /** One distinct value across every file reads as one value, not as "1 variants". */
    @Test
    fun aCodeSlotThatNeverChangedSaysSo() {
        val dir = tempDir()
        save(dir, "a.json", "label" to "warm")
        save(dir, "b.json", "label" to "warm")

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("label")
        assertEquals("1 value", slot.summaryText)
    }

    /**
     * One name holding two different types is a rename half-done or a bug, not a range. Picking one
     * type and summarising it would hide the other, so nothing is summarised and the row says why.
     */
    @Test
    fun aSlotThatChangedTypeBetweenSavesIsReportedRatherThanSummarised() {
        val dir = tempDir()
        save(dir, "a.json", "x" to 3f)
        save(dir, "b.json", "x" to Vector3f(1f, 2f, 3f))

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("x")
        assertEquals("mixed", slot.kind)
        assertEquals("mixed (num, vec3)", slot.typeLabel)
        assertEquals("2 types", slot.summaryText)
        val note = slot.display as SlotDisplay.Note
        assertEquals("warn", note.severity)
        assertContains(note.text, "changed type between saves")
    }

    // -----------------------------------------------------------------------------------------
    // History
    // -----------------------------------------------------------------------------------------

    /**
     * The trajectory view. A version the slot is absent from contributes a null rather than being
     * dropped, so the index of an entry stays the index of the save — otherwise a rail of five ticks
     * would silently describe seven saves.
     */
    @Test
    fun slotHistoryWalksTheNumberedSnapshotsAndKeepsGapsAsNulls() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val path = dir.resolve("looks.json")
        scope.saveKey(path, scope.Key(mutableMapOf("r" to 1f)), "first")
        scope.saveKey(path, scope.Key(mutableMapOf("other" to 1f)), "r missing here")
        scope.saveKey(path, scope.Key(mutableMapOf("r" to 3f)), "third")

        val c = KeyframeCollection.load(dir, "**/*.json")
        val history = c.slotHistory(0, "r")
        assertEquals(3, history.size)
        assertEquals(listOf(0, 1, 2), history.map { it.first.index })
        assertEquals(1.0, (history[0].second as KValue.Num).value)
        assertNull(history[1].second, "the middle save has no `r`, and that gap is the point")
        assertEquals(3.0, (history[2].second as KValue.Num).value)
        assertEquals("r missing here", history[1].first.note)
    }

    /** The collection's headline timestamp and note come from the newest save across every file. */
    @Test
    fun theCollectionReportsTheNewestSaveAcrossEveryFile() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 1f, note = "older")
        Thread.sleep(5)
        save(dir, "b.json", "r" to 2f, note = "newer")

        val c = KeyframeCollection.load(dir, "**/*.json")
        assertEquals("newer", c.latest?.note)
        assertEquals(2, c.versionCount)
    }

    // -----------------------------------------------------------------------------------------
    // Failure
    // -----------------------------------------------------------------------------------------

    /**
     * One unreadable file must not take the collection with it. Losing an afternoon to a stray
     * `package.json` in the looks directory is exactly the failure this prevents.
     */
    @Test
    fun anUnreadableFileIsReportedAndTheRestStillLoad() {
        val dir = tempDir()
        save(dir, "good.json", "r" to 1f)
        dir.resolve("notakey.json").writeText("""{"name":"something else entirely"}""")
        dir.resolve("broken.json").writeText("{ this is not json")

        val c = KeyframeCollection.load(dir, "**/*.json")
        assertEquals(listOf("good.json"), c.files.map { it.relative })
        assertEquals(2, c.problems.size, "both bad files are named: ${c.problems}")
        assertTrue(c.problems.any { it.startsWith("broken.json") })
        assertTrue(c.problems.any { it.startsWith("notakey.json") })
        assertEquals(1.0, (c.slot("r").display as SlotDisplay.Range).band.lo)
    }

    /** An empty or missing directory browses as an empty collection rather than throwing. */
    @Test
    fun aMissingDirectoryIsAnEmptyCollection() {
        val c = KeyframeCollection.load(tempDir().resolve("nothing-here"), "**/*.json")
        assertEquals(0, c.files.size)
        assertEquals(0, c.slotNames.size)
        assertTrue(c.problems.isEmpty(), "an absent directory is ordinary, not a fault")
    }

    /**
     * The load path reads what is on disk, not what a scope would default in.
     *
     * `KeyframeScope.loadKey` fills `missingKey` defaults, which is right before an apply and wrong
     * here: it would report a slot as present in a file that does not contain it, quietly turning the
     * coverage column — the browser's most useful diagnostic — into a column of `n/n`.
     */
    @Test
    fun loadingDoesNotApplyMissingKeyDefaults() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 1f)

        val scope = KeyframeScope()
        scope.missingKey("legacy", 7f)
        scope.floatTarget("r", { 1f }, {})
        scope.floatTarget("legacy", { 7f }, {})

        // The scope would default `legacy` in on loadKey; prove it does, so the next line means something.
        assertEquals(7f, scope.loadKey(dir.resolve("a.json")).values["legacy"])

        val c = KeyframeCollection.load(dir, "**/*.json", scope)
        assertEquals(
            0, c.slot("legacy").presentIn,
            "the file does not contain `legacy`, and the browser must say so"
        )
    }
}
