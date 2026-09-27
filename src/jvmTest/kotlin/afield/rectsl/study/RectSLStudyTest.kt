package afield.rectsl.study

import afield.rectsl.compileRectSLWithSource
import afield.rectsl.rectSLKnobs
import afield.stdlib.SourceStudy
import afield.stdlib.StudyRejection
import afield.stdlib.StudySourceEdit
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The RectSL study's transactions and evidence, on the CPU reference: what a model's tools and the card
 * do to a scene, with no window. The GPU path is exercised by the `rectsl.study.gpu-agreement` smoke.
 */
class RectSLStudyTest {
    private val folders = mutableListOf<java.io.File>()

    private inner class Held(initial: String) {
        var text = initial
        val study = RectSLStudy(Files.createTempDirectory("rectsl-study").toFile().also { folders += it }, { text }, { expected, replacement ->
            if (text == expected) { text = replacement; true } else false
        })
    }

    @AfterTest
    fun removeStudies() = folders.forEach { it.deleteRecursively() }

    private val sky = """
        # asked: a sun and a moon
        ->wall 16 9 meters
        ->duration 4
        ->fps 20
        ->background 0x101830

        # the sun, crossing left to right
        "sun" rect
          0 2 ->xy
          2 ->square
          0xffcc33 solid ->fill
          '( 0=-6  4=6 ) ->x
        ;

        # the moon, still
        "moon" rect
          5 -2 ->xy
          1 ->square
          0xeeeeee solid ->fill
        ;
    """.trimIndent()

    private fun started(source: String = sky): Held = Held("").also { h ->
        h.study.edit(SourceStudy.EMPTY, SourceStudy.hash(""), source, "first draft")
    }

    @Test
    fun theFirstEditPublishesAndShowsThePreview() {
        val h = Held("")
        val report = h.study.edit(SourceStudy.EMPTY, SourceStudy.hash(""), sky, "first draft")
        assertEquals(sky, h.text)
        assertTrue(report.toolImages.isNotEmpty(), "an edit returns a preview")
        @Suppress("UNCHECKED_CAST") val scene = report.facts["scene"] as Map<String, Any?>
        assertEquals(2, scene["rects"])
        assertEquals(true, scene["animated"])
    }

    @Test
    fun aSceneThatDoesNotCompileIsRefusedWithItsLine() {
        val h = started()
        val e = assertFailsWith<StudyRejection> {
            h.study.patch(h.study.currentId(), SourceStudy.hash(h.text), listOf(StudySourceEdit("2 ->square", "2 ->sqare")), "typo")
        }
        assertTrue("line 10" in (e.message ?: ""), e.message)
        assertEquals(sky, h.text, "a refused edit changes nothing")
    }

    @Test
    fun aStaleEditIsRefused() {
        val h = started()
        val read = SourceStudy.hash(h.text)
        h.text = h.text.replace("0xeeeeee", "0xdddddd") // the person types
        assertFailsWith<StudyRejection> { h.study.patch(h.study.currentId(), read, listOf(StudySourceEdit("1 ->square", "1.5 ->square")), "grow the moon") }
        assertTrue("0xdddddd" in h.text)
    }

    @Test
    fun numbersAreSetInPlaceAndChecked() {
        val h = started()
        val moonSize = rectSLKnobs(h.text).first { it.role == "->square" && it.line == 18 }
        h.study.setNumbers(h.study.currentId(), SourceStudy.hash(h.text), mapOf(moonSize.knob to 1.5), "a bigger moon")
        assertTrue("  1.5 ->square" in h.text, h.text)
    }

    @Test
    fun inspectNamesTheLinesThatMakeRectanglesAndTheirKnobs() {
        val report = started().study.inspect()
        @Suppress("UNCHECKED_CAST") val lines = report.facts["lines"] as List<RectSLLineReport>
        assertEquals(listOf(8, 16), lines.map { it.line })
        assertEquals("the sun, crossing left to right", lines[0].comment)
        assertTrue((report.facts["frame"] as String).contains("+y is up"))
    }

    @Test
    fun theTimelineNamesEventsWithTheLineThatMadeTheRectangle() {
        val h = started(sky.replace("'( 0=-6  4=6 ) ->x", "'( 0=-6  4=12 ) ->x"))
        val report = h.study.timeline()
        @Suppress("UNCHECKED_CAST") val events = report.facts["events"] as List<String>
        assertTrue(events.any { "sun (line 8) leaves the wall" in it }, "$events")
        assertTrue(report.toolImages.isNotEmpty())
    }

    @Test
    fun traceIdentifiesEachRectangleByLine() {
        val report = started().study.trace(seconds = 2.0)
        @Suppress("UNCHECKED_CAST") val lines = report.facts["lines"] as List<Map<String, Any?>>
        assertEquals(listOf(8, 16), lines.map { it["line"] })
        @Suppress("UNCHECKED_CAST") val sun = (lines[0]["examples"] as List<Map<String, Any?>>).single()
        assertEquals(listOf(0.0, 2.0), sun["centre"], "at 2 s the sun is halfway across")
        assertEquals("#ffcc33", sun["fill"])
    }

    @Test
    fun traceBuildShowsTheSceneAfterEachLineThatMakesSomething() {
        val report = started().study.trace(seconds = 1.0, mode = "build")
        @Suppress("UNCHECKED_CAST") val stages = report.facts["stages"] as List<Map<String, Any?>>
        assertEquals(listOf(1, 2), stages.map { it["rects"] }, "$stages")
    }

    @Test
    fun compareSaysWhichRectanglesChangedAndWhereTheFrameDid() {
        val h = started()
        val before = h.study.currentId()
        h.study.patch(before, SourceStudy.hash(h.text), listOf(StudySourceEdit("5 -2 ->xy", "5 -3 ->xy")), "lower the moon")
        val report = h.study.compare(before, seconds = listOf(0.0))
        assertEquals(false, report.facts["preservedOutsideRegion"])
        @Suppress("UNCHECKED_CAST") val rects = report.facts["rectangles"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val changed = rects["changed"] as List<Map<String, Any?>>
        assertEquals(16, changed.single()["line"])
        assertEquals(listOf("position"), changed.single()["what"])
        assertEquals(1, rects["unchanged"])
        val allowed = h.study.compare(before, seconds = listOf(0.0), region = listOf(3.5, -4.5, 6.5, -0.5))
        assertEquals(true, allowed.facts["preservedOutsideRegion"], "the moon moved only inside the region")
    }

    @Test
    fun variantsAreHeldUnpublishedAndAdopted() {
        val h = started()
        val report = h.study.tryVariants(listOf(
            RectSLStudy.Variant("red", edits = listOf(StudySourceEdit("0xffcc33", "0xff4422")), note = "a red sun"),
            RectSLStudy.Variant("broken", edits = listOf(StudySourceEdit("->fill\n  '(", "->fil\n  '(")))))
        assertEquals(sky, h.text, "trying variants publishes nothing")
        assertEquals(listOf("red"), report.facts["held"])
        h.study.adoptVariant(h.study.currentId(), SourceStudy.hash(h.text), "red")
        assertTrue("0xff4422" in h.text)
    }

    @Test
    fun aRectangleThatIsNeverOnTheWallIsWarnedAbout() {
        val lost = sky.replace("5 -2 ->xy", "30 -2 ->xy")
        val check = RectSLStudy.check(lost).value
        assertTrue(check.warnings.any { "never visible" in it && "moon (line 16)" in it }, "${check.warnings}")
    }

    @Test
    fun overlapIsCountedBetweenObjectsNotWithABackdropOrWithinOne() {
        // A sky covering the wall, a lantern of four overlapping parts, and tiny stars behind it:
        // composition, not crowding.
        val composed = """
            ->wall 16 9 meters
            ->duration 2
            "sky" rect 16 9 ->wh 0x101830 solid ->fill ;
            "star" rect 0.02 ->square 0xffffff solid ->fill
              6 '( fc 0.4 * ->x ) array ;
            "lantern" group
              "glow" rect 1.4 1.8 ->wh 0xff8830 solid ->fill 0.1 ->alpha ;
              "paper" rect 1 1.4 ->wh 0xffb040 solid ->fill ;
              "fold" rect 0.4 1.2 ->wh 0xffd080 solid ->fill ;
              "flame" rect 0 -0.5 ->xy 0.2 0.2 ->wh 0xffffff solid ->fill ;
            end
        """.trimIndent()
        assertTrue(RectSLStudy.check(composed).value.warnings.none { "overlap" in it }, "${RectSLStudy.check(composed).value.warnings}")
        // Five separate squares piled on one spot are crowding.
        val piled = "->wall 16 9 meters\n->duration 2\n" + (1..5).joinToString("\n") { "\"pile-$it\" rect 0 0 ->xy 2 ->square 0xffffff solid ->fill ;" }
        assertTrue(RectSLStudy.check(piled).value.warnings.any { "Heavy overlap" in it }, "${RectSLStudy.check(piled).value.warnings}")
    }

    @Test
    fun aSceneWithoutMotionIsStillAndObservedOnce() {
        val still = sky.replace("  '( 0=-6  4=6 ) ->x\n", "")
        val h = started(still)
        assertEquals(false, h.study.revisions.revision().value.animated)
        assertEquals(listOf(1.0), h.study.observe(seconds = listOf(1.0, 2.0)).facts["seconds"], "a still scene is drawn once")
    }

    @Test
    fun exportsAStillAndTheWholeClip() {
        val h = started(sky.replace("->duration 4\n->fps 20", "->duration 1\n->fps 12"))
        val folder = Files.createTempDirectory("rectsl-export").toFile().also { folders += it }
        val hash = SourceStudy.hash(h.text)
        h.study.startExport("current", hash, java.io.File(folder, "sky.png"), "png", size = 160, seconds = 0.5)
        val clip = h.study.ffmpeg()?.let { h.study.startExport("current", hash, java.io.File(folder, "sky.mp4"), "mp4", size = 160); java.io.File(folder, "sky.mp4") }
        val deadline = System.currentTimeMillis() + 60_000
        while (h.study.exports().any { it["status"] in setOf("queued", "running") } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(h.study.exports().all { it["status"] == "done" }, "${h.study.exports()}")
        assertTrue(java.io.File(folder, "sky.png").length() > 100)
        clip?.let { assertTrue(it.length() > 1000, "the clip has frames") }
        assertEquals("12/12", h.study.exports().firstOrNull { it["kind"] == "mp4" }?.get("frames") ?: "12/12", "one loop at 12 fps is 12 frames")
    }

    @Test
    fun everySnippetInTheWorkingGuideCompiles() {
        val guide = RectSLStudyGuides.working
        val fences = Regex("```rectsl\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(guide).map { it.groupValues[1] }.toList()
        assertTrue(fences.size >= 2)
        fences.forEach { fence ->
            val body = fence.lines().filter { it.trim() != "..." }.joinToString("\n")
            val source = if ("->wall" in body) body else "->wall 16 9 meters\n->duration 6\n$body"
            val compiled = compileRectSLWithSource(source)
            assertTrue(compiled.ok, "guide snippet does not compile: ${compiled.problems.joinToString { it.summary }}\n$source")
        }
        assertNotNull(RectSLStudyGuides.words.lines().firstOrNull { it.startsWith("| `->xy`") }, "the words guide has the geometry table")
    }
}
