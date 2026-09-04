package afield.stdlib

import afield.four.buffers.Structure
import afield.four.util.CameraPose
import afield.keyframeBrowserIframe
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * A vec4 is two plots and a magnitude, whatever it turns out to mean.
     *
     * The summary is `|v|` rather than a component range, because that is the one scalar a vec4 has
     * regardless of interpretation — and it is the band that can be brushed.
     */
    @Test
    fun aVec4BecomesTwoPlotsAndAMagnitude() {
        val dir = tempDir()
        save(dir, "a.json", "v" to Vector4f(1f, 0f, 0f, 0f))
        save(dir, "b.json", "v" to Vector4f(0f, 0f, 0f, 2f))

        val slot = KeyframeCollection.load(dir, "**/*.json").slot("v")
        val plot = slot.display as SlotDisplay.Vec4Plot
        assertEquals(2, plot.xy.size)
        assertEquals(2, plot.zw.size)
        assertEquals(4, plot.bands.size, "the raw component bands stay, for the axis numbers")
        assertEquals(1.0, plot.length.lo)
        assertEquals(2.0, plot.length.hi)
        assertEquals("|v| 1 – 2", slot.summaryText)
    }

    /**
     * Each component keeps its own band, scaled to itself.
     *
     * This is what the four brushable strips read from, and it is why a vec4 cannot just be the two
     * plots: a vec4 very often packs four unrelated numbers for a shader, so "keys whose w is under
     * -30" has to be answerable without the other three components being involved. Per-component
     * scaling is the load-bearing part — a `w` running -44..-8 must span its own strip rather than
     * being squashed by an `x` that never leaves zero.
     */
    @Test
    fun everyVec4ComponentKeepsItsOwnBandForBrushing() {
        val dir = tempDir()
        save(dir, "a.json", "plane" to Vector4f(0f, 1f, 0f, -12.5f))
        save(dir, "b.json", "plane" to Vector4f(0f, 1f, 0f, -44f))
        save(dir, "c.json", "plane" to Vector4f(0f, 1f, 0f, -8f))

        val plot = KeyframeCollection.load(dir, "**/*.json").slot("plane").display as SlotDisplay.Vec4Plot
        assertEquals(listOf("x", "y", "z", "w"), plot.bands.map { it.label })

        val w = plot.bands[3]
        assertEquals(-44.0, w.lo)
        assertEquals(-8.0, w.hi)
        // a=-12.5 sits ((-12.5) - (-44)) / 36 = 0.875 along its own band, not squashed by x or y
        assertEquals(listOf(0.875, 0.0, 1.0), w.marks)

        // x never moves, so its band is degenerate — the page reads that as "not brushable" rather
        // than offering a filter that could only ever select everything.
        assertEquals(plot.bands[0].lo, plot.bands[0].hi)
        assertEquals(listOf(0.5, 0.5, 0.5), plot.bands[0].marks)
    }

    /**
     * The colour guess survives only where it costs nothing: inside the unit cube the points carry
     * their own RGB, so an RGBA tint reads as colour *within* the plots rather than replacing them.
     */
    @Test
    fun aUnitVec4CarriesItsColourOnThePoints() {
        val dir = tempDir()
        save(dir, "a.json", "tint" to Vector4f(1f, 0f, 0f, 1f))
        save(dir, "b.json", "tint" to Vector4f(0f, 0.5f, 1f, 1f))

        val plot = KeyframeCollection.load(dir, "**/*.json").slot("tint").display as SlotDisplay.Vec4Plot
        assertEquals(listOf("#ff0000", "#0080ff"), plot.colors)
    }

    /** One component out of `0..1` anywhere and the colour guess is off — for every file, not just that one. */
    @Test
    fun aVec4LeavingTheUnitCubeCarriesNoColourAtAll() {
        val dir = tempDir()
        save(dir, "a.json", "v" to Vector4f(1f, 0f, 0f, 1f))
        save(dir, "b.json", "v" to Vector4f(0f, 0f, 40f, 1f))

        val plot = KeyframeCollection.load(dir, "**/*.json").slot("v").display as SlotDisplay.Vec4Plot
        assertNull(plot.colors, "a vec4 reaching 40 is a position or a plane, not an RGBA")
    }

    /**
     * Both axes of a plot share one extent.
     *
     * Normalising each axis alone would spread a component that never moves across the full width,
     * which reads as variation that is not in the data. Here x runs 0..4 and y is pinned at 1, so
     * the points must land at x = 0 and 1 with y flat — not at two different heights.
     */
    @Test
    fun aVec4PlotNormalisesBothAxesTogether() {
        val dir = tempDir()
        save(dir, "a.json", "v" to Vector4f(0f, 1f, 0f, 0f))
        save(dir, "b.json", "v" to Vector4f(4f, 1f, 0f, 0f))

        val plot = KeyframeCollection.load(dir, "**/*.json").slot("v").display as SlotDisplay.Vec4Plot
        assertEquals(listOf(0.0, 0.25), plot.xy[0])
        assertEquals(listOf(1.0, 0.25), plot.xy[1])
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
    // Timestamps
    // -----------------------------------------------------------------------------------------

    /**
     * The cutovers, each checked on both sides, against a fixed `now` and a fixed zone so this does
     * not drift with the wall clock or the machine's locale.
     *
     * `now` is a Tuesday afternoon, chosen so "yesterday" and the weekday form are distinguishable
     * from each other and from the same-year date form.
     */
    @Test
    fun friendlyTimeReadsAsAPersonWouldSayIt() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.Instant.parse("2026-09-01T15:00:00Z")   // Tuesday
        fun at(iso: String) = friendlyTime(iso, now, zone)

        assertEquals("just now", at("2026-09-01T14:59:30Z"), "30s")
        assertEquals("a minute ago", at("2026-09-01T14:58:40Z"), "80s")
        assertEquals("5 minutes ago", at("2026-09-01T14:55:00Z"))
        assertEquals("59 minutes ago", at("2026-09-01T14:01:00Z"), "still minutes at the boundary")
        assertEquals("an hour ago", at("2026-09-01T13:30:00Z"))
        assertEquals("4 hours ago", at("2026-09-01T11:00:00Z"))
        assertEquals("yesterday 09:15", at("2026-08-31T09:15:00Z"))
        assertEquals("Sat 22:05", at("2026-08-29T22:05:00Z"), "inside the week, so the weekday")
        assertEquals("12 Aug 08:00", at("2026-08-12T08:00:00Z"), "past a week, but this year")
        assertEquals("3 Mar 2025", at("2025-03-03T08:00:00Z"), "another year drops the clock")
    }

    /** Clock skew reads as "just now", not as a negative duration. */
    @Test
    fun aTimestampFromTheFutureDoesNotReadAsNegative() {
        val now = java.time.Instant.parse("2026-09-01T15:00:00Z")
        assertEquals("just now", friendlyTime("2026-09-01T15:10:00Z", now, java.time.ZoneId.of("UTC")))
    }

    /** An unparseable timestamp is handed back untouched rather than throwing inside a render. */
    @Test
    fun anUnparseableTimestampIsPassedThrough() {
        assertEquals("not a date", friendlyTime("not a date", java.time.Instant.now()))
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
     * A file that will not decode must not take the collection with it. Losing an afternoon to a
     * stray `package.json` in the looks directory is exactly the failure this prevents.
     *
     * This test used to assert that both intruders landed in `problems`, and that assertion is now
     * inverted on purpose. Neither of these files is *damage*: one is a JSON object that never
     * mentioned `values`, the other is not JSON at all, and neither has a `.versions` directory
     * beside it, so Field did not write either. They are somebody else's files sharing a folder, and
     * a red row for them is a permanent false alarm — `.DS_Store` produced one in every key
     * directory macOS had ever opened. They are still reported, still named, and still one line
     * each; they are reported as *ignored*.
     */
    @Test
    fun aFileThatWillNotDecodeIsNamedAndTheRestStillLoad() {
        val dir = tempDir()
        save(dir, "good.json", "r" to 1f)
        dir.resolve("notakey.json").writeText("""{"name":"something else entirely"}""")
        dir.resolve("broken.json").writeText("{ this is not json")

        val c = KeyframeCollection.load(dir, "**/*.json")
        assertEquals(listOf("good.json"), c.files.map { it.relative })

        assertEquals(emptyList(), c.problems, "neither intruder is damage, so neither is red")
        assertEquals(2, c.ignoredCount, "both are still named: ${c.skipped}")
        assertEquals(
            listOf("broken.json", "notakey.json"),
            c.skipped.filter { it.kind == SkipKind.NotAKey }.map { it.relative }.sorted()
        )
        assertContains(c.skipped.single { it.relative == "broken.json" }.detail, "not JSON")
        assertContains(c.skipped.single { it.relative == "notakey.json" }.detail, "values")
        assertEquals("2 files ignored", c.skipSummary())

        assertEquals(1.0, (c.slot("r").display as SlotDisplay.Range).band.lo)
    }

    /**
     * The same two intruders, and a *third* file that really is damage, in one directory — because
     * the whole value of the split is that the two coexist and read differently.
     *
     * The damaged one is a key saved through the real writer and then truncated, so its `.versions`
     * directory is intact beside it. That directory is the ownership proof: without it a truncated
     * key is just malformed JSON, indistinguishable from the `broken.json` above, and the file that
     * most needs a red row would be filed as junk.
     */
    @Test
    fun damageAndJunkInOneDirectoryReadDifferently() {
        val dir = tempDir()
        save(dir, "good.json", "r" to 1f)
        val truncated = save(dir, "half.json", "r" to 2f)
        truncated.writeText(Files.readString(truncated).take(9))
        dir.resolve("broken.json").writeText("{ this is not json")

        val c = KeyframeCollection.load(dir, "**/*.json")

        assertEquals(listOf("good.json"), c.files.map { it.relative })
        assertEquals(1, c.problems.size, "exactly one red row: ${c.problems}")
        assertContains(c.problems.single(), "half.json")
        assertContains(c.problems.single(), "save history is intact")
        assertEquals(1, c.ignoredCount)
        assertEquals(
            listOf("broken.json"),
            c.skipped.filter { it.kind == SkipKind.NotAKey }.map { it.relative }
        )
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

    // ---- the panel's own size ---------------------------------------------------------------

    @Test
    fun theBrowserIframeDeclaresItsWidthInPixels() {
        val html = keyframeBrowserIframe("token", 900, 460)
        // The output area measures the panel from these two declarations with a `parseFloat`-based
        // parser, and a percentage is meaningless to it: `parseFloat("100%")` is 100, not 0, so the
        // frame claims a hundred pixels and loses to the header's floor. `width:100%` is how the
        // browser came up 192
        // pixels wide inside a 200-pixel panel with two thirds of it off the right edge.
        for (axis in listOf("width", "height")) {
            val declared = Regex("""[;"]$axis:\s*([^;"]+)""").find(html)?.groupValues?.get(1)?.trim()
            assertTrue(declared != null, "$axis is not declared in $html")
            assertTrue(declared.endsWith("px"), "$axis must be in px, not '$declared'")
            assertTrue(
                declared.removeSuffix("px").toDoubleOrNull()?.let { it > 0 } == true,
                "$axis '$declared' does not parse to a positive number"
            )
        }
        // The iframe opts out of `iframe_autosize.js`, which is the other way a frame could report a
        // size. If that ever changes, the pixel width above stops being the only source.
        assertContains(html, "data-afield-autosize=\"off\"")
    }

    // -----------------------------------------------------------------------------------------
    // Key discovery — the human's own directory
    // -----------------------------------------------------------------------------------------

    /**
     * The fixture that the rest of this file did not have, and whose absence is why none of it
     * caught the bug it is named for.
     *
     * Every other collection here is named `looks.json`, `top.json`, `nested.json` — eight
     * characters and up, every one of them. A fixture like that cannot fail the way a real directory
     * failed: `?` matches nothing in it, so `?` looks harmless, and a page written from such a
     * fixture once told a person their working pattern was fine. This one is the real thing, copied
     * name for name from a live session's `files/` directory: six keys called `a b c d d2 d3`, one
     * and two characters, with `d`/`d2`/`d3` sharing a prefix — plus the `.DS_Store` that macOS
     * leaves in any folder a Finder window has opened.
     *
     * A claim true of a fixture is not true of the reader unless the fixture is shaped like the
     * reader's disk.
     */
    private fun humansKeyDirectory(): Path {
        val dir = tempDir()
        save(dir, "a", "glow@radius" to 1f)
        save(dir, "b", "glow@radius" to 2f)
        save(dir, "c", "glow@radius" to 3f)
        save(dir, "d", "glow@radius" to 4f)
        save(dir, "d2", "glow@radius" to 5f)
        save(dir, "d3", "glow@radius" to 6f)
        // Not written through `save`, and deliberately not JSON: this is junk, not a key, and it has
        // no `.versions` directory beside it.
        dir.resolve(".DS_Store").writeText("Bud1 junk")
        return dir
    }

    /**
     * The regression. The default pattern must find every key in a directory of keys saved the
     * ordinary way.
     *
     * `KeyframeScope.saveKey` writes the path it is handed and appends nothing, so
     * `capture().save("d3".path)` produces a file called `d3`. The old default asked for a `.json`
     * suffix and therefore matched **zero** of these six, and the browser said "No key files
     * matched" over a full directory. The floor is asserted beside the number because a six that
     * silently becomes a four is the entire failure being pinned here.
     */
    @Test
    fun theDefaultPatternFindsEveryKeySavedTheOrdinaryWay() {
        val dir = humansKeyDirectory()

        val names = keyFilesMatching(dir).map { it.name }
        assertEquals(listOf("a", "b", "c", "d", "d2", "d3"), names.filter { it != ".DS_Store" })

        val c = KeyframeCollection.load(dir)
        assertEquals(6, c.files.size, "all six keys must be rows")
        assertTrue(c.files.size >= 6, "floor: fewer than six means the default narrowed again")
        assertEquals(
            listOf("a", "b", "c", "d", "d2", "d3"),
            c.files.map { it.relative },
            "and in directory order, so the chips read the way the folder does"
        )
    }

    /**
     * The old default, run against the same directory, so the fix is measured rather than asserted.
     *
     * This is the number the person actually saw. Nothing about the *files* changed between this test
     * and the one above it — only the glob.
     */
    @Test
    fun theOldJsonOnlyDefaultFoundNoneOfThem() {
        val dir = humansKeyDirectory()
        assertEquals(
            emptyList(),
            keyFilesMatching(dir, "**/*.json"),
            "a suffix-demanding glob matches no extension-less key, and the .versions snapshots that " +
                "do carry .json are excluded, so this can only ever be empty"
        )
    }

    /**
     * Step zero, pinned: Java's glob supports brace alternation, so the expressiveness was never
     * missing.
     *
     * `?` is exactly one character and `??` exactly two, so no single-length pattern can span
     * `a b c d` and `d2 d3` — which is how trying `?` and then `??` traded four keys for two and
     * found six neither time. `{?,??}` spans both, today, with no code change. This test is here so
     * that a later "let us add a comma syntax" is answered by a failing assumption rather than by an
     * argument.
     */
    @Test
    fun braceAlternationAlreadySpansOneAndTwoCharacterKeyNames() {
        val dir = humansKeyDirectory()
        fun found(pattern: String) = keyFilesMatching(dir, pattern).map { it.name }

        assertEquals(listOf("a", "b", "c", "d"), found("?"), "the first pattern tried")
        assertEquals(listOf("d2", "d3"), found("??"), "the second, and the last act of that evening")
        assertEquals(listOf("a", "b", "c", "d", "d2", "d3"), found("{?,??}"), "both, in one glob")
        assertTrue(found("{?,??}").size >= 6, "floor: the alternation must span both lengths")
    }

    /**
     * `.DS_Store` is not a problem. It is one line of notice and no colour at all.
     *
     * This is the row that used to be indistinguishable from a corrupt key — same list, same red —
     * in a directory where nothing was wrong. A permanent false alarm teaches you to stop reading the
     * alarm.
     */
    @Test
    fun osJunkIsIgnoredRatherThanReportedAsAProblem() {
        val c = KeyframeCollection.load(humansKeyDirectory())

        assertEquals(emptyList(), c.problems, "nothing here is damaged, so nothing may be red")
        assertEquals(1, c.ignoredCount)
        assertEquals(0, c.hiddenCount)
        assertEquals(
            listOf(".DS_Store"),
            c.skipped.filter { it.kind == SkipKind.NotAKey }.map { it.relative }
        )
        assertEquals("1 file ignored", c.skipSummary())
    }

    /**
     * A truncated key *is* a problem, and what tells it apart from junk is its history.
     *
     * A half-written key is not valid JSON, so a content-only ladder would call it "not JSON, not a
     * key" and file real damage as an OS turd — the important case, backwards. Its `.versions`
     * directory is still beside it and still holds every earlier save, which both proves Field wrote
     * the file and is exactly what the person now needs.
     */
    @Test
    fun aTruncatedKeyIsDamageBecauseItsHistoryStillNamesItAsOurs() {
        val dir = humansKeyDirectory()
        val d3 = dir.resolve("d3")
        d3.writeText(Files.readString(d3).take(12))  // a write that stopped halfway

        val c = KeyframeCollection.load(dir)

        assertEquals(5, c.files.size, "the other five keys still browse")
        assertEquals(1, c.problems.size, "and the broken one is the only red row: ${c.problems}")
        assertContains(c.problems.single(), "d3")
        assertEquals(
            listOf(SkipKind.Damaged),
            c.skipped.filter { it.relative == "d3" }.map { it.kind }
        )
        // The junk is still junk. Damage and junk coexisting is the whole point of the split.
        assertEquals(1, c.ignoredCount)
    }

    /** The ladder, a rung at a time, with no filesystem in sight. */
    @Test
    fun theClassifierLadderRungByRung() {
        // Ownership wins over content: anything with a history beside it is ours, however unreadable.
        assertEquals(SkipKind.Damaged, classifyNonKey("d3", "{ trunca", hasVersionsDir = true).first)
        assertEquals(SkipKind.Damaged, classifyNonKey("d3", null, hasVersionsDir = true).first)
        // Without a history, the file speaks for itself.
        assertEquals(SkipKind.NotAKey, classifyNonKey(".DS_Store", "Bud1", hasVersionsDir = false).first)
        assertEquals(SkipKind.NotAKey, classifyNonKey("README", "hello", hasVersionsDir = false).first)
        assertEquals(SkipKind.NotAKey, classifyNonKey("nums.json", "[1,2,3]", hasVersionsDir = false).first)
        assertEquals(SkipKind.NotAKey, classifyNonKey("other.json", "{\"a\":1}", hasVersionsDir = false).first)
        // Presents as a key and is not decodable as one: damage, history or no history.
        assertEquals(SkipKind.Damaged, classifyNonKey("k.json", "{\"values\":7}", hasVersionsDir = false).first)
    }

    /**
     * Narrowing says what it hid. This is the message that was missing on the evening of the
     * `?`-then-`??` fumble.
     */
    @Test
    fun aNarrowedPatternReportsTheKeysItHid() {
        val dir = humansKeyDirectory()

        val two = KeyframeCollection.load(dir, "??")
        assertEquals(2, two.files.size)
        assertEquals(4, two.hiddenCount, "a b c d are keys that '??' cannot match")
        assertTrue(two.hiddenCount >= 4, "floor: silence here is the original bug")
        assertEquals(
            listOf("a", "b", "c", "d"),
            two.skipped.filter { it.kind == SkipKind.Hidden }.map { it.relative },
            "named, not just counted, because a count you cannot act on is half a message"
        )
        assertContains(two.skipSummary()!!, "4 more keys here are hidden by this pattern")
        assertEquals(emptyList(), two.problems, "narrowing is not damage")

        val four = KeyframeCollection.load(dir, "?")
        assertEquals(4, four.files.size)
        assertEquals(2, four.hiddenCount, "d2 and d3 are keys that '?' cannot match")

        // And the glob that spans both hides no *key*. It still steps over the `.DS_Store`, which is
        // reported as ignored rather than hidden: a narrowing that excludes junk has excluded
        // nothing you wanted, and saying "1 more key is hidden" about an OS turd would be a lie.
        val all = KeyframeCollection.load(dir, "{?,??}")
        assertEquals(6, all.files.size)
        assertEquals(0, all.hiddenCount, "the alternation hides no key")
        assertEquals("1 file ignored", all.skipSummary())
        assertEquals(emptyList(), all.problems)
    }

    /**
     * Row ids are dense over the rows, not over the matched paths.
     *
     * They used to be the index into the matched-path list, so every skipped file left a hole and the
     * browser's chips numbered 1, 2, 4. Under a default that matches everything there is more junk to
     * step over, so the holes would have gone from an oddity to the normal case — and `.DS_Store`
     * sorts first, which would have made the *first* chip read 2.
     */
    @Test
    fun rowIdsAreDenseEvenWhenJunkSortsFirst() {
        val c = KeyframeCollection.load(humansKeyDirectory())
        assertEquals((0 until 6).toList(), c.files.map { it.id })
        assertEquals(0, c.files.first().id, "the first chip must read 1, not 2")
        // And `file(id)` still agrees with the ids it handed out.
        c.files.forEach { assertEquals(it.relative, c.file(it.id)?.relative) }
    }

    /**
     * `keyFilesMatching`'s contract for its other callers: an *explicit* pattern reaches the glob
     * untouched, and the `.versions` exclusion holds whatever the pattern is.
     *
     * Only the default moved. Pinned because a default and a parameter are easy to conflate, and a
     * change to the second would break callers that already say what they want.
     */
    @Test
    fun anExplicitPatternIsUnchangedByTheNewDefault() {
        val dir = tempDir()
        dir.resolve("looks").createDirectories()
        save(dir, "top.json", "a" to 1f)
        save(dir.resolve("looks"), "nested.json", "a" to 2f)
        dir.resolve("notes.txt").writeText("not a key")

        // Explicit and suffix-bearing: exactly the two keys, no notes.txt, no snapshots.
        assertEquals(
            listOf("looks/nested.json", "top.json"),
            keyFilesMatching(dir, "*.json").map { dir.relativize(it).toString() }.sorted()
        )
        // The wide default sees the third file too, and calls it ignored rather than a problem.
        val c = KeyframeCollection.load(dir)
        assertEquals(2, c.files.size)
        assertEquals(listOf("notes.txt"), c.skipped.map { it.relative })
        assertEquals(emptyList(), c.problems)
        // The exclusion is not a property of the pattern. Two saved keys, and no snapshot is a row.
        assertTrue(
            keyFilesMatching(dir, everyKeyGlob).none { it.toString().contains(".versions") },
            "the .versions exclusion must survive the widest possible glob"
        )
    }


    // -----------------------------------------------------------------------------------------
    // A saved slot the scope no longer watches
    // -----------------------------------------------------------------------------------------

    /**
     * The disagreement that was silent in only one direction.
     *
     * `scopeSlots` has always drawn the "you have never saved this" row: a slot the scope watches
     * that no file holds. Its mirror image — a slot a *file* holds that the scope no longer watches
     * — rendered as an ordinary row, with its real saved range, and `KeyframeScope.loadKey` is
     * explicit that those names "simply have nowhere to go on apply". So a rename half-done looked
     * like a working slot and applied to nothing.
     */
    @Test
    fun aSavedSlotTheScopeNoLongerWatchesIsMarkedOrphaned() {
        val dir = tempDir()
        // Two slots saved. The scope has since been renamed and watches only one of them, plus a
        // third it has never saved.
        save(dir, "a", "glow@radius" to 1f, "glow@blur" to 4f)
        save(dir, "b", "glow@radius" to 2f, "glow@blur" to 5f)

        val glow = Structure()
        glow.bind("radius") { 3f }
        glow.bind("feather") { 0.5f }
        val scope = KeyframeScope()
        scope.target("glow@radius", glow)
        scope.target("glow@feather", glow)

        val c = KeyframeCollection.load(dir, everyKeyGlob, scope)

        assertTrue(c.scopeAttached)
        assertEquals(listOf("glow@blur"), c.orphanedSlots, "saved, and now wired to nothing")

        val blur = c.slot("glow@blur")
        assertTrue(blur.orphaned, "the row must say the value applies to nowhere")
        assertEquals(2, blur.presentIn, "and it is still a real, fully-present slot: ${blur.summaryText}")

        // The mirror image still works, and is NOT reported as orphaned - nothing was saved to orphan.
        val feather = c.slot("glow@feather")
        assertTrue(feather.inScope)
        assertEquals(0, feather.presentIn)
        assertFalse(feather.orphaned, "'never saved' and 'saved but unwired' are different rows")

        // And a live slot is neither.
        val radius = c.slot("glow@radius")
        assertTrue(radius.inScope)
        assertFalse(radius.orphaned)
    }

    /**
     * With no scope attached, nothing is orphaned — because nothing can be known.
     *
     * This is the whole reason [KeyframeCollection.scopeAttached] exists as a flag separate from
     * `scopeSlots.isEmpty()`. A read-only browser has no opinion about which slots are live, and a
     * scope that is attached and watches nothing has a very strong one; both leave `scopeSlots`
     * empty. Without the flag the marker either stays silent in the case that matters or cries wolf
     * on every row of every read-only browse.
     */
    @Test
    fun aReadOnlyBrowseNeverClaimsASlotIsDisconnected() {
        val dir = tempDir()
        save(dir, "a", "glow@radius" to 1f)

        val readOnly = KeyframeCollection.load(dir, everyKeyGlob, scope = null)
        assertFalse(readOnly.scopeAttached)
        assertEquals(emptyList(), readOnly.orphanedSlots, "with no scope there is nothing to disagree with")
        assertFalse(readOnly.slot("glow@radius").orphaned)

        // An attached scope watching nothing is the opposite answer from the same empty scopeSlots.
        val empty = KeyframeCollection.load(dir, everyKeyGlob, scope = KeyframeScope())
        assertTrue(empty.scopeAttached)
        assertEquals(emptySet(), empty.scopeSlots, "the two cases are indistinguishable by scopeSlots alone")
        assertEquals(listOf("glow@radius"), empty.orphanedSlots, "and must still be told apart")
        assertTrue(empty.slot("glow@radius").orphaned)
    }

    @Test
    fun aNonPositiveWidthIsStillTheFillMyColumnEscapeHatch() {
        assertContains(keyframeBrowserIframe("token", 0, 460), "width:100%")
        assertContains(keyframeBrowserIframe("token", -1, 460), "width:100%")
    }
}
