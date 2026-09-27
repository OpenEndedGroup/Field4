package afield.stacksl.study

import afield.Box
import afield.stacksl.StackSLCompiler
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.SourceStudy
import afield.stdlib.StudyRejection
import afield.stdlib.StudySourceEdit
import afield.testRepoRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class StackSLStudyTest {
    private class Editor(var text: String) {
        val read = { text }
        val replace = { expected: String, next: String -> if (text != expected) false else { text = next; true } }
    }

    private fun withStudy(start: String, block: (StackSLStudy, Editor) -> Unit) {
        val dir = Files.createTempDirectory("stacksl-study").toFile()
        val editor = Editor(start)
        try {
            val study = StackSLStudy(File(dir, "revisions"), editor.read, editor.replace)
            study.checkIn("start")
            block(study, editor)
        } finally { dir.deleteRecursively() }
    }

    private fun hash(editor: Editor) = SourceStudy.hash(editor.text)

    // ---- The outline --------------------------------------------------------------------------

    @Test fun rootPrefixesKeepDefinitionsAndPositions() {
        val source = ": half >q <q 0.5 * ;\nuv half   # halve it\nfbm palette\n"
        val outline = StackSLCompiler.outline(source)
        assertEquals(listOf("uv", "half", "fbm", "palette"), outline.root.map { it.text })
        assertEquals(listOf("half"), outline.definitions.map { it.name })
        val prefix = outline.rootPrefix(source, 2)
        assertEquals(source.length, prefix.length)
        assertTrue(prefix.contains(": half >q <q 0.5 * ;"))
        assertFalse(prefix.contains("fbm"))
        assertTrue(StackSLCpu.compile(prefix).ok)
        assertEquals(source, outline.rootPrefix(source, 4))
    }

    @Test fun theCheatsheetDefinitionExampleIsRightAboutBalance() {
        // public/docs/stacksl_cheatsheet.md, "Definitions". It read `>x <x` until 2026-09-26, which
        // does not compile: `>x` is the lane-replace word, not a mark.
        assertEquals(1, StackSLStudy.check(": half  >v  <v 0.5 *  ;\nuv half").value.definitions.single().finalDepth)
        assertFailsWith<StudyRejection> { StackSLStudy.check(": half  =v  <v 0.5 *  ;\nuv half") }
        assertFalse(StackSLCpu.compile(": half >x <x 0.5 * ;\nuv half").ok)
    }

    @Test fun stagesAreLinesOrWordsWithCommentsApart() {
        val lines = StackSLStudyShader.stages("uv 5 *   # scale\n( note ) fbm\npalette")
        assertEquals(listOf("line", "line", "line"), lines.map { it.granularity })
        assertEquals(listOf("uv 5 *", "fbm", "palette"), lines.map { it.text })
        assertEquals(listOf("scale", "note", null), lines.map { it.comment })
        assertEquals(listOf(3, 4, 5), lines.map { it.wordsThrough })
        val words = StackSLStudyShader.stages("uv fbm palette")
        assertEquals(listOf("uv", "fbm", "palette"), words.map { it.text })
        assertTrue(words.all { it.granularity == "word" })
    }

    // ---- Knobs --------------------------------------------------------------------------------

    @Test fun knobsAreTheTypedNumbersLocatedToTheirDigits() {
        val knobs = StackSLStudyShader.knobs("p polar\ndup y 0.2t + >y\ncart ]wr14 palette")
        assertEquals(listOf(0.2, 14.0), knobs.map { it.value })
        assertEquals(listOf("0.2t", "]wr14"), knobs.map { it.token })
        assertEquals(listOf(2 to 7, 3 to 9), knobs.map { it.line to it.column })
        assertEquals("t amount", knobs[0].role)
        // palette's default operands are not typed, so they are not knobs.
        assertEquals(2, knobs.size)
    }

    @Test fun equalComponentsOfAVectorLiteralShareOneKnob() {
        val knobs = StackSLStudyShader.knobs("input [3,3,3,0.08] * fbm4")
        assertEquals(listOf(3.0, 0.08), knobs.map { it.value })
        assertEquals(3, knobs[0].spans.size)
        val rewritten = StackSLStudyShader.setKnobs("input [3,3,3,0.08] * fbm4", mapOf(0 to 4.5))
        assertEquals("input [4.5,4.5,4.5,0.08] * fbm4", rewritten)
    }

    @Test fun plainNumbersNameTheWordTheyFeed() {
        val knobs = StackSLStudyShader.knobs("uv fbm 0.35 0.9 ramp")
        assertEquals(listOf("ramp", "ramp"), knobs.map { it.feeds })
    }

    @Test fun settingKnobsKeepsWhatTheWordMeans() {
        val source = "p polar\ndup y 0.2t + >y\ncart ]wr14 palette"
        val rewritten = StackSLStudyShader.setKnobs(source, mapOf(0 to 0.35, 1 to 9.0))
        assertEquals("p polar\ndup y 0.35t + >y\ncart ]wr9 palette", rewritten)
        assertEquals("6.0", StackSLStudyShader.formatKnob(6.0, "5.0"))
        assertEquals("15", StackSLStudyShader.formatKnob(15.0, "14"))
        assertEquals("0.137", StackSLStudyShader.formatKnob(0.137, "0.12"))
        assertFailsWith<IllegalArgumentException> { StackSLStudyShader.setKnobs(source, mapOf(5 to 1.0)) }
        assertFailsWith<IllegalArgumentException> { StackSLStudyShader.setKnobs(source, mapOf(1 to Double.NaN)) }
    }

    // ---- The check ----------------------------------------------------------------------------

    // Cited as the reproducer by the conversation guides (guides/working.md), whose claim checker
    // looks for @Test on the line above.
    @Test
    fun theCheckRefusesWhatCannotRunAndSaysWhy() {
        val unknown = assertFailsWith<StudyRejection> { StackSLStudy.check("uv fbmm palette") }
        assertContains(unknown.message!!, "does not compile")
        assertContains(unknown.message!!, "fbmm")
        assertContains(assertFailsWith<StudyRejection> { StackSLStudy.check("uv 0.01 * sample") }.message!!, "texture")
        assertContains(assertFailsWith<StudyRejection> { StackSLStudy.check(": half =x <x 0.5 * ;\np half") }.message!!, "unbalanced")
        assertContains(assertFailsWith<StudyRejection> { StackSLStudy.check(": fbm3 uv ;\nfbm3") }.message!!, "shadows a built-in")
    }

    @Test fun theCheckReportsTimeNaNAndStructure() {
        val still = StackSLStudy.check("uv fbm palette saturate").value
        assertFalse(still.animated)
        assertEquals(0.0, still.nonFiniteFraction)
        assertEquals(2, still.rootFinalDepth)
        val moving = StackSLStudy.check("uv 5 * 0.1t + fbm palette").value
        assertTrue(moving.animated)
        val nan = StackSLStudy.check("p sqrt").value
        assertTrue(nan.nonFiniteFraction > 0.2, "p has negative components, so sqrt is NaN over much of the frame")
        val defined = StackSLStudy.check(": field >q <q 4 * fbm ;\np field palette").value
        assertEquals(listOf("field" to 1), defined.definitions.map { it.name to it.finalDepth })
    }

    // ---- Shaders ------------------------------------------------------------------------------

    @Test fun theStudyShaderIsTheGalleryAnimatedTemplate() {
        val harness = testRepoRoot().resolve("src/jvmMain/kotlin/afield/four/fabricated/StackSLGalleryHarness.kt").toFile().readText()
        val start = harness.indexOf("private fun animatedFragmentTemplate(): String = \"\"\"")
        assertTrue(start >= 0, "gallery harness no longer has animatedFragmentTemplate")
        val body = harness.substring(harness.indexOf("\"\"\"", start) + 3).substringBefore("\"\"\".trimIndent()").trimIndent()
        assertEquals(body, StackSLStudyShader.FRAGMENT)
    }

    // ---- What the editor is told -------------------------------------------------------------

    @Test fun diagnoseReportsCompilerProblemsOnTheirLines() {
        assertTrue(StackSLStudy.diagnose("uv fbm palette saturate\n").none { it.error })
        val unknown = StackSLStudy.diagnose("uv\nfbmm palette\n").first { it.error }
        assertEquals(2, unknown.line)
        assertContains(unknown.message, "fbmm")
        assertTrue(StackSLStudy.diagnose(": half =v <v 0.5 * ;\nuv half").any { it.error && "unbalanced" in it.message })
        assertTrue(StackSLStudy.diagnose("uv 0.01 * sample").any { it.error && "texture" in it.message })
    }

    @Test fun aMarkNamedAfterALaneIsExplained() {
        val problem = StackSLStudy.diagnose(": half\n  >x <x 0.5 *\n;\nuv half").first()
        assertTrue(problem.error)
        assertEquals(2, problem.line)
        assertContains(problem.message, ">x replaces the x lane")
    }

    // ---- Stages that store ---------------------------------------------------------------------

    @Test fun aStoreLineIsShownByTheValueItStored() {
        val source = "uv 2 * >q\n<q fbm\n"
        val outline = StackSLCompiler.outline(source)
        assertEquals("q", outline.storedMark(4)) // uv 2 * >q
        assertNull(outline.storedMark(3))
        assertNull(StackSLCompiler.outline("uv 0.5 >x").storedMark(3), ">x is the lane word, not a store")
        val stage = outline.stageProgram(source, 4)
        assertTrue(stage.trimEnd().endsWith("<q"))
        val shown = StackSLStudyShader.sample(stage, 0.25, 0.75, 0.0)
        assertEquals(listOf(0.5, 1.5), shown.take(2).map { (it * 1000).toInt() / 1000.0 }, "the stored uv * 2, not the input")
    }

    @Test fun traceLabelsAStoreLineWithItsMark() = withStudy("uv 2 * >q\n<q fbm\n") { study, _ ->
        val stages = study.trace(size = 24).facts["stages"] as List<*>
        assertEquals("the value stored in q", (stages[0] as Map<*, *>)["shows"])
        assertEquals("the top of the stack", (stages[1] as Map<*, *>)["shows"])
    }

    // ---- Colours -------------------------------------------------------------------------------

    @Test fun colourLiteralsAreFoundWithTheirThreeKnobs() {
        val source = "[0.92,0.84,0.68,1] >ivory\n[1,0.85,0,0] p *\n[0.5,0.5,0.2] <ivory mix\n"
        val colours = StackSLStudyShader.colourKnobs(source)
        assertEquals(1, colours.size, "[1,0.85,0,0] ends in 0, and [0.5,0.5,0.2] shares a knob between two components")
        assertEquals(listOf(0.92, 0.84, 0.68), colours.single().linear)
        assertEquals(3, colours.single().knobs.toSet().size)
        assertEquals("#f6ecd7", colours.single().hex)
    }

    @Test fun theCardGroupsKnobsByLineAndOffersColours() = withStudy("# a warm ground\n[0.92,0.84,0.68,1] >ivory\nuv fbm 0.5 * <ivory *\n") { study, _ ->
        val html = StackSLStudyViewer(study, Box("stacksl_tools_test")).html()
        assertContains(html, "line 2 · ")
        assertContains(html, "type='color'")
        assertContains(html, "value='#f6ecd7'")
    }

    @Test fun displayedRawIsExactlyWhatTheFragmentDraws() {
        for (program in listOf("uv fbm palette saturate", "p 3 * sqrt", "uv 2 * 0.5t +")) {
            val direct = StackSLCpu.renderProgram(program, StackSLStudyShader.FRAGMENT, 24, 24, pushParams = floatArrayOf(2.5f, 0f, 0f, 0f))
            val derived = StackSLStudyShader.displayed(StackSLStudyShader.renderRaw(program, 24, 2.5))
            assertContentEquals(direct.toArgb(), derived.toArgb(), program)
        }
    }

    @Test fun imagesAreEncodedTheWayTheSrgbWindowShowsThem() {
        val presented = StackSLStudyShader.presented(StackSLStudyShader.renderRaw("0.5", 4, 0.0))
        val argb = presented.toArgb()[0]
        assertEquals(188, (argb shr 16) and 0xff, "0.5 linear is sRGB 188, the grey an sRGB swapchain shows")
        assertEquals(0, (StackSLStudyShader.presented(StackSLStudyShader.renderRaw("0", 4, 0.0)).toArgb()[0] shr 8) and 0xff)
        assertContains(StackSLStudyShader.webgl2("0.5").fragment, "study_srgb(clamp(c, 0.0, 1.0))")
    }

    @Test fun theWebGlTranslationLiftsLiteralsIntoUniforms() {
        val gl = StackSLStudyShader.webgl2("uv 5.0 * 0.1t + fbm palette saturate")
        assertTrue(gl.fragment.startsWith("#version 300 es"))
        assertFalse(gl.fragment.contains("push_constant"))
        assertFalse(gl.fragment.contains("{{"))
        assertContains(gl.fragment, "uniform float u_time;")
        assertContains(gl.fragment, "vec4 stackInput = vec4(uv, gallery_hash12(uv), u_time);")
        assertTrue(gl.wires.isNotEmpty())
        gl.wires.forEach { (name, _) -> assertContains(gl.fragment, "uniform float $name;") }
        assertEquals(5.0, gl.wires.first().second)
        assertContains(gl.vertex, "gl_VertexID")
    }

    // ---- The tools ----------------------------------------------------------------------------

    @Test fun editsPatchesNumbersRestoreAndCheckInAreRevisions() = withStudy("0.15\n") { study, editor ->
        val r1 = study.currentId()
        val edit = study.edit(r1, hash(editor), "uv 5 *\nfbm\npalette\nsaturate\n", "first draft")
        assertEquals(1, edit.toolImages.size)
        assertEquals(true, (edit.facts["validation"] as Map<*, *>)["checksPassed"])
        val r2 = edit.revision
        val patch = study.patch(r2, hash(editor), listOf(StudySourceEdit("fbm\n", "0.1t + fbm\n")), "drift")
        assertContains(editor.text, "0.1t + fbm")
        val numbers = study.setNumbers(patch.revision, hash(editor), mapOf(0 to 3.0), "coarser")
        assertTrue(editor.text.startsWith("uv 3 *"))
        val restored = study.restore(numbers.revision, hash(editor), r2)
        assertEquals("uv 5 *\nfbm\npalette\nsaturate\n", editor.text)
        editor.text = "uv 7 *\nfbm\npalette\nsaturate\n"
        assertEquals(true, study.inspect().facts["dirty"])
        assertFailsWith<StudyRejection> { study.setNumbers(restored.revision, SourceStudy.hash("uv 5 *\nfbm\npalette\nsaturate\n"), mapOf(0 to 2.0), "stale") }
        val checked = study.checkIn("by hand")
        assertEquals(false, study.inspect().facts["dirty"])
        assertEquals(listOf(r1, r2, patch.revision, numbers.revision, restored.revision, checked.revision), study.revisions.history().map { it.id })
    }

    @Test fun aRefusedEditKeepsTheWorkingProgram() = withStudy("uv saturate\n") { study, editor ->
        val r1 = study.currentId()
        assertFailsWith<StudyRejection> { study.edit(r1, hash(editor), "uv fbmm", "typo") }
        assertEquals("uv saturate\n", editor.text)
        assertEquals(r1, study.currentId())
    }

    @Test fun observeMeasuresClippingNaNAndMotion() = withStudy("2\n") { study, _ ->
        val bright = study.observe(size = 32)
        @Suppress("UNCHECKED_CAST") val frame = (bright.facts["frames"] as List<StackSLFrameStats>).single()
        assertEquals(1.0, frame.clippedAbove)
        assertEquals(1.0, frame.luma)
        assertEquals(1, bright.toolImages.size)
        assertTrue(File(bright.facts["imageFile"] as String).isFile)
    }

    @Test fun observeComparesAgainstAnotherRevision() = withStudy("uv 5 * 0.2t + fbm palette saturate\n") { study, editor ->
        val r1 = study.currentId()
        val r2 = study.edit(r1, hash(editor), "uv 5 * 0.2t + fbm palette saturate 0.5 *\n", "darker").revision
        val report = study.observe(r2, listOf(0.0, 2.0), 48, r1)
        assertEquals(listOf(0.0, 2.0), report.facts["seconds"])
        assertEquals(1, (report.facts["motion"] as List<*>).size)
        assertEquals(2, (report.facts["differences"] as List<*>).size)
    }

    @Test fun traceShowsEachStageAndProbesTheStack() = withStudy("uv 2 *\nfbm\n") { study, _ ->
        val report = study.trace(size = 32, probeU = 0.25, probeV = 0.75)
        val stages = report.facts["stages"] as List<*>
        assertEquals(2, stages.size)
        assertEquals(2, (stages[0] as Map<*, *>)["depth"])
        val probe = report.facts["probe"] as Map<*, *>
        val words = probe["words"] as List<*>
        assertEquals(listOf("uv", "2", "*", "fbm"), words.map { (it as Map<*, *>)["word"] })
        @Suppress("UNCHECKED_CAST") val afterMultiply = ((words[2] as Map<*, *>)["stackTopFirst"] as List<List<Double>>).first()
        assertEquals(listOf(0.5, 1.5, 0.0, 2.0), afterMultiply)
    }

    @Test fun knobsReportWhichNumbersMatter() = withStudy("uv 5 * fbm\n0.5 0.5 0.5 1 vec4\n0.5 0.5 0.5 1 vec4\n1 1 1 1 vec4\n0 0.33 0.67 1 vec4\npalette saturate\n") { study, _ ->
        val rows = study.knobs().facts["knobs"] as List<*>
        val alpha = rows.filterIsInstance<Map<*, *>>().filter { it["at"] == "2:13" }.single()
        assertEquals(0.0, (alpha["sensitivity"] as Map<*, *>)["maxLevels"], "a palette operand's alpha does not reach the frame")
        val scale = rows.filterIsInstance<Map<*, *>>().first()
        assertTrue(((scale["sensitivity"] as Map<*, *>)["meanLevels"] as Double) > 0.5)
    }

    @Test fun sweepAndVariantsPublishNothingUntilAdopted() = withStudy("uv 5 * fbm palette saturate\n") { study, editor ->
        val r1 = study.currentId()
        val sweep = study.sweep(knob = 0, values = listOf(2.0, 5.0, 9.0), size = 32)
        assertEquals(3, (sweep.facts["values"] as List<*>).size)
        assertEquals(r1, study.currentId())
        val variants = study.tryVariants(listOf(
            StackSLStudy.Variant("warm", edits = listOf(StudySourceEdit("palette", "palette 0.8 *")), note = "dimmer"),
            StackSLStudy.Variant("broken", source = "uv fbmm"),
            StackSLStudy.Variant("rings", source = "p ]wr14 palette saturate")), size = 32)
        assertEquals(listOf("warm", "rings"), variants.facts["held"])
        assertEquals(r1, study.currentId())
        assertEquals("uv 5 * fbm palette saturate\n", editor.text)
        assertFailsWith<StudyRejection> { study.adoptVariant(r1, hash(editor), "broken") }
        val adopted = study.adoptVariant(r1, hash(editor), "rings")
        assertEquals("p ]wr14 palette saturate", editor.text)
        assertEquals(adopted.revision, study.currentId())
        assertTrue(study.heldVariants().isEmpty())
        assertFailsWith<StudyRejection> { study.tryVariants(listOf(StackSLStudy.Variant("bad label!", source = "uv"))) }
    }

    @Test fun heldVariantsSurviveARestartAgainstTheSameRevision() = withStudy("uv 5 * fbm palette saturate\n") { study, editor ->
        study.tryVariants(listOf(StackSLStudy.Variant("soft veils", source = "uv 3 * fbm palette saturate", note = "coarser")), size = 24)
        val again = StackSLStudy(study.directory, editor.read, editor.replace)
        assertEquals(study.currentId(), again.resume())
        assertEquals(listOf("soft veils"), again.heldVariants().map { it.first })
        again.adoptVariant(again.currentId(), hash(editor), "soft veils")
        val third = StackSLStudy(study.directory, editor.read, editor.replace)
        third.resume()
        assertTrue(third.heldVariants().isEmpty(), "an adopted batch is not offered again")
    }

    @Test fun compareHoldsAnEditToItsRegionAndItsStage() = withStudy("uv saturate\n") { study, editor ->
        val r1 = study.currentId()
        val r2 = study.edit(r1, hash(editor), "uv saturate\nwhite 0.5 uv x step mix\n", "right half white").revision
        assertEquals(false, study.compare(r1, r2, size = 32).facts["preservedOutsideRegion"])
        assertEquals(true, study.compare(r1, r2, size = 32, region = listOf(0.5, 0.0, 1.0, 1.0)).facts["preservedOutsideRegion"])
        val r3 = study.edit(r2, hash(editor), "uv saturate\n0.5 *\n", "recolour").revision
        assertEquals(true, study.compare(r1, r3, size = 32, mode = "stage", beforeStage = 1, afterStage = 1).facts["preservedOutsideRegion"])
        assertEquals(false, study.compare(r1, r3, size = 32, mode = "rgb").facts["preservedOutsideRegion"])
        assertEquals(listOf(false, false), study.compare(r1, r3, size = 32, mode = "stage", beforeStage = 1, afterStage = 1).facts["stageIsProgramInput"])
    }

    // The recorded tiles session compared a stage ending in `>joint` first; it held, and showed nothing,
    // because the input was all that line left on top. A store line now compares the value it stored.
    @Test fun aStoreLineComparesTheValueItStored() = withStudy("uv >q\n<q saturate\n") { study, editor ->
        val r1 = study.currentId()
        val r2 = study.edit(r1, hash(editor), "uv 0.5 * >q\n<q saturate\n", "halve").revision
        val stored = study.compare(r1, r2, size = 16, mode = "stage", beforeStage = 1, afterStage = 1).facts
        assertEquals(false, stored["preservedOutsideRegion"], "the stored value changed")
        assertEquals(listOf(false, false), stored["stageIsProgramInput"])
        assertNull(stored["vacuous"])
    }

    @Test fun aStageThatLeavesOnlyTheInputIsReportedAsProvingNothing() = withStudy("input\nuv saturate\n") { study, editor ->
        val r1 = study.currentId()
        val r2 = study.edit(r1, hash(editor), "input\nuv 0.5 * saturate\n", "halve").revision
        val facts = study.compare(r1, r2, size = 16, mode = "stage", beforeStage = 1, afterStage = 1).facts
        assertEquals(true, facts["preservedOutsideRegion"])
        assertEquals(listOf(true, true), facts["stageIsProgramInput"])
        assertNotNull(facts["vacuous"])
    }

    @Test fun exportRefusesAStaleOrUncheckedProgram() = withStudy("uv fbm palette saturate\n") { study, editor ->
        val out = File(study.directory.parentFile, "files/frame.png")
        val report = study.exportPng("current", hash(editor), out, size = 32)
        assertTrue(out.isFile)
        assertEquals(out.length().toInt(), report.facts["bytes"])
        assertFailsWith<StudyRejection> { study.exportPng("current", hash(editor), out, size = 32) }
        editor.text = "uv saturate\n"
        assertFailsWith<StudyRejection> { study.exportPng("current", SourceStudy.hash("uv fbm palette saturate\n"), out, size = 32, overwrite = true) }
    }

    @Test fun backgroundExportsEstimateHonourTheirBudgetAndFinish() = withStudy("uv 5 * fbm palette saturate\n") { study, editor ->
        val out = File(study.directory.parentFile, "files/still.png")
        val refused = assertFailsWith<StudyRejection> { study.startExport("current", hash(editor), out, "png", size = 2048, maxMinutes = 0.0001) }
        assertContains(refused.message!!, "budget")
        val started = study.startExport("current", hash(editor), out, "png", size = 24)
        assertEquals("png", started.facts["kind"])
        val deadline = System.currentTimeMillis() + 60_000
        while (study.exports().single()["status"] in setOf("queued", "running") && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("done", study.exports().single()["status"])
        assertTrue(out.isFile)
        assertEquals("Export x1 is already done", study.cancelExport("x1").facts["message"])
    }

    @Test fun knobsInsideADefinitionNameTheWordTheyFeed() {
        assertEquals(listOf("ramp", "ramp"), StackSLStudyShader.knobs(": shape 0.35 0.9 ramp ;\nuv fbm shape").map { it.feeds })
    }

    @Test fun theViewerOffersEveryKnobAndCarriesOutKeep() = withStudy("uv 5 * 0.1t + fbm palette saturate\n") { study, editor ->
        val viewer = StackSLStudyViewer(study, Box("stacksl_tools_test"))
        val html = viewer.html()
        assertEquals(study.revisions.revision().value.knobs.size, Regex("type='range'").findAll(html).count())
        assertContains(html, "data-act='keep'")
        assertContains(html, "#version 300 es")
        val r = study.currentId()
        val status = viewer.handleAction("""{"action":"keep","revision":"$r","sourceHash":"${hash(editor)}","values":{"0":7.0}}""")
        assertContains(status, "kept numbers")
        assertTrue(editor.text.startsWith("uv 7 *"))
        assertContains(viewer.handleAction("""{"action":"keep","revision":"$r","sourceHash":"stale","values":{"0":2.0}}"""), "not done")
    }

    // The recorded aurora has 95 numbers; the card used to show the first 24 and drop the rest.
    @Test fun aLongProgramsCardStillHasASliderForEveryNumber() =
        withStudy("uv\n" + (1..30).joinToString("\n") { "${it / 100.0} +" } + "\nsaturate\n") { study, _ ->
            val html = StackSLStudyViewer(study, Box("stacksl_tools_test")).html()
            assertEquals(30, study.revisions.revision().value.knobs.size)
            assertEquals(30, Regex("type='range'").findAll(html).count())
            assertContains(html, "6 more numbers")
            assertContains(html, "of 32 lines, evenly spaced")
        }
}
