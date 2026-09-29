package afield.stdlib

import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class FunctionStudyTest {
    @Test fun patchesAreUniqueNonOverlappingGuardedAndReturnPreviewEvidence() {
        val dir = Files.createTempDirectory("study-patch").toFile()
        var source = "val gain = 0.5\nval duration = 2.0"
        var compilations = 0
        val study = FunctionStudy(dir, { source }, { old, next -> if (source != old) false else { source = next; true } }) {
            compilations++; constant()
        }
        try {
            val first = study.edit("empty", FunctionStudy.hash(source), source, "initial").revision
            // A blank revision is the current one, as the other studies read it; models send "" for it.
            assertEquals(first, study.revision("").id)
            val originalHash = FunctionStudy.hash(source)
            assertFailsWith<IllegalArgumentException> { study.patch(first, originalHash, listOf(StudySourceEdit("val", "var")), "ambiguous") }
            assertFailsWith<IllegalArgumentException> { study.patch(first, originalHash,
                listOf(StudySourceEdit("gain = 0.5", "gain = 0.6"), StudySourceEdit("0.5", "0.7")), "overlap") }
            assertEquals(1, compilations)
            val patched = study.patch(first, originalHash, listOf(StudySourceEdit("gain = 0.5", "gain = 0.6")), "gain")
            val report = study.previewEdit(patched)
            assertContains(source, "gain = 0.6")
            assertEquals(2, compilations)
            assertEquals(1, report.toolImages.size)
            assertEquals(true, (report.facts["validation"] as Map<*, *>)["compileSuccess"])
            assertFailsWith<IllegalArgumentException> { study.patch(first, originalHash, listOf(StudySourceEdit("0.6", "0.7")), "stale") }
            assertNotNull(study.previewEdit(report, 10.0, 11.0).facts["previewError"])
            assertEquals(report.revision, study.currentId())
        } finally { dir.deleteRecursively() }
    }

    @Test fun exportHasExactEndpointVerifiedRowsAndExplicitOverwriteAndDirtyGuards() {
        val dir = Files.createTempDirectory("study-export").toFile()
        var source = "first"
        val study = FunctionStudy(dir.resolve("revisions"), { source }, { _, next -> source = next; true }) { constant() }
        try {
            val revision = study.edit("empty", FunctionStudy.hash(source), source, "initial").revision
            val outputs = listOf("a", "b", "c").map { dir.resolve("files/$it") }
            val report = study.exportCsv("current", FunctionStudy.hash(source), outputs, sampleRate = 1.25)
            assertEquals(revision, report.revision)
            assertEquals(listOf("0.0,50.0", "0.8,50.0", "1.6,50.0", "2.0,50.0"), outputs.first().readLines())
            assertEquals(3, (report.facts["files"] as List<*>).size)
            assertFailsWith<IllegalArgumentException> { study.exportCsv("current", FunctionStudy.hash(source), outputs) }
            study.exportCsv("current", FunctionStudy.hash(source), outputs, sampleRate = 1.0, scale = 2.0, header = true, overwrite = true)
            val saved = outputs.first().readText()
            assertEquals("time_seconds,signal_scaled\n0.0,1.0\n1.0,1.0\n2.0,1.0\n", saved)
            source = "manual change"
            assertEquals(source, study.inspect().facts["liveSource"])
            assertFailsWith<IllegalArgumentException> { study.exportCsv("current", FunctionStudy.hash("first"), outputs, overwrite = true) }
            assertEquals(saved, outputs.first().readText())
            assertTrue(dir.resolve("files").listFiles()!!.none { it.name.startsWith(".study-export-") })
        } finally { dir.deleteRecursively() }
    }

    private fun constant(value: Double = .5) = StudyComposition(
        channels = List(3) { Functions.fn(0.0..2.0) { value } }, duration = 2.0)

    @Test fun failedCandidateAndStaleManualSourcePreserveWorkingRevision() {
        val dir = Files.createTempDirectory("function-study-test").toFile()
        var source = "initial"
        val study = FunctionStudy(dir, { source }, { old, next ->
            if (source != old) false else { source = next; true }
        }) { code -> if (code == "broken") throw IllegalArgumentException("compile error") else constant() }
        try {
            val first = study.edit("empty", FunctionStudy.hash(source), "good", "initial").revision
            assertFailsWith<IllegalArgumentException> { study.edit(first, FunctionStudy.hash(source), "broken", "bad") }
            assertEquals(first, study.currentId()); assertEquals("good", source)
            source = "manual edit"
            assertFailsWith<IllegalArgumentException> { study.edit(first, FunctionStudy.hash("good"), "overwrite", "bad") }
            assertEquals("manual edit", source)
            assertEquals(true, study.inspect().facts["dirty"])
            assertEquals(first, study.currentId())
        } finally { dir.deleteRecursively() }
    }

    @Test fun manualChangeDuringCompilationIsNotOverwritten() {
        val dir = Files.createTempDirectory("function-study-test").toFile()
        var source = "initial"
        val study = FunctionStudy(dir, { source }, { old, next -> if (source != old) false else { source=next; true } }) {
            source = "human changed it while the compiler worked"; constant()
        }
        try {
            assertFailsWith<IllegalArgumentException> { study.edit("empty",FunctionStudy.hash("initial"),"candidate","test") }
            assertEquals("empty",study.currentId()); assertTrue(source.startsWith("human"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationAndInvalidHoldNeverActivate() {
        val dir = Files.createTempDirectory("function-study-test").toFile()
        lateinit var study: FunctionStudy
        study = FunctionStudy(dir, { "initial" }, { _, _ -> fail("Must not publish") }) {
            study.cancel(); constant()
        }
        try {
            assertFailsWith<CancellationException> { study.edit("empty",FunctionStudy.hash("initial"),"candidate","test") }
            val invalid = FunctionStudy(dir, { "initial" }, { _, _ -> fail("Must not publish") }) {
                constant().copy(events=listOf(StudyEvent("e1",0.0,.5,.5,.5)))
            }
            assertFailsWith<IllegalArgumentException> { invalid.edit("empty",FunctionStudy.hash("initial"),"bad hold","test") }
            assertEquals("empty",invalid.currentId())
        } finally { dir.deleteRecursively() }
    }

    @Test fun revisionsHaveRepeatablePixelsSamplesAndDurableSource() {
        val dir = Files.createTempDirectory("function-study-test").toFile()
        var source = "initial"
        fun session() = FunctionStudy(dir, { source }, { old, next -> if (source != old) false else { source=next; true } }) { constant(if(it=="second") .6 else .5) }
        try {
            val a=session()
            val r1=a.edit("empty",FunctionStudy.hash(source),"first","one").revision
            val image=a.observe(r1,0.0,2.0).toolImages.single().dataUrl
            val r2=a.edit(r1,FunctionStudy.hash(source),"second","two").revision
            assertEquals(image,a.observe(r1,0.0,2.0).toolImages.single().dataUrl)
            assertEquals(a.sample(r1,listOf(.2,.7)).facts,a.sample(r1,listOf(.2,.7)).facts)
            assertEquals(r2,session().resume())
            assertEquals("first",session().revision(r1).source)
            assertFailsWith<IllegalArgumentException> { a.sample(r1,listOf(Double.NaN)) }
            assertFailsWith<IllegalArgumentException> { a.observe(r1,0.0,3.0) }
        } finally { dir.deleteRecursively() }
    }

    /**
     * A revision directory written before FunctionStudy was ported onto SourceStudy (2026-09-29) — events,
     * choices and seed at the top of `<id>.json`, no `created` — still resumes, and a revision that no
     * longer passes validation is still readable: loading compiles, it does not re-check.
     */
    @Test
    fun revisionsWrittenBeforeThePortResumeAndLoadWithoutRevalidation() {
        val dir = Files.createTempDirectory("function-study-legacy").toFile()
        try {
            val source = "legacy"
            val id = "r1-" + FunctionStudy.hash(source).take(12)
            dir.resolve("$id.kt").writeText(source)
            dir.resolve("$id.json").writeText("""{"id":"$id","parent":null,"description":"old","events":[],"choices":[],"seed":0}""")
            dir.resolve("HEAD").writeText(id)
            // Would fail validation today (a narrowed domain), as a check tightened since acceptance would.
            val study = FunctionStudy(dir, { source }, { _, _ -> fail("Must not publish") }) {
                constant().copy(channels = List(3) { Functions.fn(0.0..1.0) { .5 } })
            }
            assertEquals(id, study.resume())
            assertEquals("old", study.revision().description)
            assertEquals(false, study.inspect().facts["dirty"])
            assertEquals(listOf(id), study.inspect().facts["history"])
        } finally { dir.deleteRecursively() }
    }

    @Test fun narrowedFnDomainIsRejectedInsteadOfSilentlyEvaluatedOutsideItsDomain() {
        val dir = Files.createTempDirectory("function-study-test").toFile()
        try {
            val study=FunctionStudy(dir,{ "initial" },{ _,_ -> fail("Must not publish") }) {
                constant().copy(channels=List(3) { Functions.fn(0.0..1.0) { .5 } })
            }
            assertFailsWith<IllegalArgumentException> { study.edit("empty",FunctionStudy.hash("initial"),"narrow","test") }
            assertEquals("empty",study.currentId())
        } finally { dir.deleteRecursively() }
    }
}
