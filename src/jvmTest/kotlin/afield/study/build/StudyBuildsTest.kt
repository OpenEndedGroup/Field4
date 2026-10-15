package afield.study.build

import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.clear
import afield.document.properties.compareTo
import afield.document.properties.saved
import afield.graphics.render.color
import afield.graphics.render.rangeTo
import afield.graphics.shader.vertex
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.times
import afield.math.linalg.v2
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.data.collections.get
import afield.data.collections.r
import afield.document.workspace.file
import afield.document.workspace.path
import afield.graphics.shader.mesh
import afield.math.linalg.set
import afield.media.image.close
import afield.runtime.execution.start
import afield.simulation.automata.history
import afield.study.source.SourceStudy
import afield.study.source.StudyAdmission
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class StudyBuildsTest {
    private fun temp() = Files.createTempDirectory("study-builds-").toFile()
    private fun stage(id: String = "mesh", version: String = "v1", keys: Set<String> = setOf("shape"), deps: List<String> = emptyList(),
        work: (StudyBuildContext) -> StudyBuildProduct = { c ->
            c.progress(0.5, "Writing the captured shape")
            c.file("result.txt").writeText(c.inputs["shape"].orEmpty())
            StudyBuildProduct(listOf("result.txt"), mapOf("checked" to true))
        }) = StudyBuildStage(id, id, version, keys, deps, build = work)

    private fun StudyBuilds.startCurrent(stage: String = "mesh") = start(stage, stages().first { it.id == stage }.fingerprint!!)
    private fun StudyBuilds.done(id: String): StudyBuildRecord {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val r = job(id)
            if (r.status !in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)) return r
            Thread.sleep(5)
        }
        error("Build $id never became terminal: ${job(id)}")
    }
    private fun CountDownLatch.arrives() = assertTrue(await(5, TimeUnit.SECONDS), "Worker did not reach the test barrier")

    @Test fun optionalStageExplainsItsInputsWithoutBypassingReadinessOrChangingBuildIdentity() {
        val inputs = AtomicReference<Map<String, String>>(emptyMap())
        val help = StudyBuildInputHelp("Idle", "No candidate is being tested. Ask the agent to prepare a recipe change.")
        fun definition(withHelp: Boolean) = StudyBuildStage("trial", "Test candidate", "v1", setOf("candidate"),
            inputHelp = help.takeIf { withHelp }) { context ->
                context.file("result.txt").writeText(context.inputs.getValue("candidate"))
                StudyBuildProduct(listOf("result.txt"))
            }
        StudyBuilds(temp(), listOf(definition(true)), inputs::get).use { builds ->
            val idle = builds.stages().single()
            assertEquals(help, idle.inputHelp)
            assertEquals("Missing input: candidate", idle.reason)
            assertNull(idle.fingerprint)
            assertFailsWith<IllegalStateException> { builds.start("trial", "invented") }
            assertTrue(builds.history().isEmpty())
            inputs.set(mapOf("candidate" to "source-v1"))
            val ready = builds.stages().single()
            assertNull(ready.inputHelp)
            assertNotNull(ready.fingerprint)
            StudyBuilds(temp(), listOf(definition(false)), inputs::get).use { plain ->
                assertEquals(plain.stages().single().fingerprint, ready.fingerprint)
            }
            assertEquals(StudyBuildStatus.SUCCEEDED, builds.done(builds.startCurrent("trial").id).status)
            inputs.set(emptyMap())
            assertNull(builds.stages().single().current)
            assertEquals(help, builds.stages().single().inputHelp)
        }
    }

    @Test fun resultFeedbackUsesRetainedFactsWithoutChangingCurrencyOrBuildIdentity() {
        val root = temp()
        val input = AtomicReference("a")
        val notes = { r: StudyBuildRecord -> listOf(StudyBuildNotice("${r.facts["unresolved"]} items remain. Inspect the report.")) }
        fun definition(feedback: (StudyBuildRecord) -> List<StudyBuildNotice>) =
            StudyBuildStage("mesh", "Analysis", "v1", setOf("shape"), resultNotes = feedback) { c ->
                c.file("report").writeText("Two unresolved items")
                StudyBuildProduct(listOf("report"), mapOf("unresolved" to 2))
            }
        val id: String
        val fingerprint: String
        StudyBuilds(root, listOf(definition { emptyList() }), { mapOf("shape" to input.get()) }).use { builds ->
            fingerprint = builds.stages().single().fingerprint!!
            id = builds.startCurrent().id
            assertEquals(StudyBuildStatus.SUCCEEDED, builds.done(id).status)
        }
        StudyBuilds(root, listOf(definition(notes)), { mapOf("shape" to input.get()) }).use { builds ->
            val current = builds.stages().single()
            assertEquals(fingerprint, current.fingerprint)
            assertEquals(id, current.current?.id)
            assertEquals(notes(current.current!!), current.resultNotes)
            assertEquals(current.resultNotes, builds.resultNotes(id))
            input.set("b")
            assertNull(builds.stages().single().current)
            assertTrue(builds.stages().single().resultNotes.isEmpty())
            assertContains(builds.resultNotes(id).single().message, "2 items")
        }
        StudyBuilds(root, listOf(definition { error("broken formatter") }), { mapOf("shape" to "a") }).use { builds ->
            assertEquals(id, builds.stages().single().current?.id)
            assertContains(builds.stages().single().resultNotes.single().message, "feedback is unavailable")
            assertEquals(1, builds.history().size)
        }
    }

    @Test fun aCurrentResultFromAnotherRunnerIsRequiredBeforeDownstreamWorkCanStart() {
        val source = AtomicReference("first")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        StudyBuilds(temp(), listOf(stage { c ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            c.file("result.txt").writeText(c.inputs.getValue("shape"))
            StudyBuildProduct(listOf("result.txt"))
        }), { mapOf("shape" to source.get()) }).use { upstream ->
            StudyBuilds(temp(), listOf(stage(keys = setOf("checked result"))), {
                upstream.stages().single().current?.let { mapOf("checked result" to it.id) } ?: emptyMap()
            }).use { downstream ->
                fun assertWaiting() {
                    val state = downstream.stages().single()
                    assertNull(state.fingerprint)
                    assertNull(state.current)
                    assertEquals("Missing input: checked result", state.reason)
                    assertFailsWith<IllegalStateException> { downstream.start("mesh", "obsolete") }
                }
                assertWaiting()
                val attempt = upstream.startCurrent(); entered.arrives()
                assertWaiting()
                assertTrue(downstream.history().isEmpty())
                release.countDown()
                val checked = upstream.done(attempt.id)
                assertNotNull(downstream.stages().single().fingerprint)
                val built = downstream.done(downstream.startCurrent().id)
                assertEquals(checked.id, built.inputs["checked result"])
                source.set("changed")
                assertWaiting()
                assertEquals(1, downstream.history().size)
            }
        }
    }

    @Test fun sourceStudyEditsInvalidateOnlyTheDependentStages() {
        val source = AtomicReference("10")
        val study = SourceStudy(temp(), "txt", source::get, source::compareAndSet,
            admit = { text, _ -> StudyAdmission(text.toInt()) })
        study.edit("empty", SourceStudy.hash("10"), "10", "Initial dimensions")
        val independent = AtomicReference("red")
        StudyBuilds(temp(), listOf(stage(), stage("derived", deps = listOf("mesh")), stage("palette", keys = setOf("color"))),
            { mapOf("shape" to study.liveSource(), "color" to independent.get()) }).use { builds ->
            assertNull(builds.stages().first { it.id == "derived" }.fingerprint)
            assertFailsWith<IllegalStateException> { builds.start("derived", "anything") }
            val mesh = builds.done(builds.startCurrent().id)
            val derived = builds.done(builds.startCurrent("derived").id)
            val palette = builds.done(builds.startCurrent("palette").id)
            assertEquals(mesh.id, derived.dependencies.getValue("mesh").jobId)
            assertTrue(builds.stages().all { it.current != null })
            assertEquals("10", builds.currentFile("mesh", "result.txt").readText())
            study.edit(study.currentId(), SourceStudy.hash(source.get()), "12", "A mouse or conversational dimension edit")
            assertNull(builds.stages().first { it.id == "mesh" }.current)
            assertNull(builds.stages().first { it.id == "derived" }.current)
            assertFailsWith<IllegalStateException> { builds.currentFile("mesh", "result.txt") }
            assertEquals(palette.id, builds.stages().first { it.id == "palette" }.current?.id)
            assertEquals("10", File(builds.directory, "attempts/${mesh.id}/result.txt").readText())
            assertEquals("10", builds.job(mesh.id).inputs["shape"])
            val rebuilt = builds.done(builds.startCurrent().id)
            assertEquals("12", rebuilt.inputs["shape"])
            assertNull(builds.stages().first { it.id == "derived" }.current)
        }
    }

    @Test fun aLateWorkerCannotPublishOverANewerInput() {
        val source = AtomicReference("before")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        StudyBuilds(temp(), listOf(stage { c ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            assertEquals("before", c.inputs["shape"])
            c.file("result.txt").writeText(c.inputs.getValue("shape")); StudyBuildProduct(listOf("result.txt"))
        }), { mapOf("shape" to source.get()) }).use { builds ->
            val job = builds.startCurrent(); entered.arrives()
            source.set("after"); release.countDown()
            val late = builds.done(job.id)
            assertEquals(StudyBuildStatus.OBSOLETE, late.status)
            assertEquals(1, late.artifacts.size)
            assertNull(builds.stages().single().current)
        }
    }

    @Test fun staleAndDuplicateStartRequestsChangeNothing() {
        val source = AtomicReference("before")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        StudyBuilds(temp(), listOf(stage { c ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            c.file("result.txt").writeText("ok"); StudyBuildProduct(listOf("result.txt"))
        }), { mapOf("shape" to source.get()) }).use { builds ->
            val old = builds.stages().single().fingerprint!!
            source.set("after")
            assertFailsWith<IllegalStateException> { builds.start("mesh", old) }
            assertTrue(builds.history().isEmpty())
            val job = builds.startCurrent(); entered.arrives()
            assertFailsWith<IllegalStateException> { builds.startCurrent() }
            assertEquals(1, builds.history().size)
            release.countDown(); assertEquals(StudyBuildStatus.SUCCEEDED, builds.done(job.id).status)
        }
    }

    @Test fun failureHasUsefulDetailsAndKeepsThePreviousMatchingResult() {
        var fail = false
        StudyBuilds(temp(), listOf(stage { c ->
            if (fail) throw StudyBuildFailure(StudyBuildProblem("invalid-profile", "The profile intersects itself.",
                listOf("Move vertex b clear of edge a-c."), mapOf("vertex" to "b", "distance" to 0.0)))
            c.file("result.txt").writeText("ok"); StudyBuildProduct(listOf("result.txt"))
        }), { mapOf("shape" to "same") }).use { builds ->
            val good = builds.done(builds.startCurrent().id); fail = true
            val bad = builds.done(builds.startCurrent().id)
            assertEquals(StudyBuildStatus.FAILED, bad.status)
            assertEquals("invalid-profile", bad.problem?.code)
            assertEquals("b", bad.problem?.details?.get("vertex"))
            assertEquals(1, bad.problem?.suggestions?.size)
            assertTrue(bad.artifacts.isEmpty())
            assertEquals(good.id, builds.stages().single().current?.id)
            assertEquals(bad.id, builds.stages().single().latest?.id)
        }
    }

    @Test fun aCurrentFailureExplainsTheFailedCheckUntilItsInputsActuallyChange() {
        val source = AtomicReference("initial")
        var fail = true
        StudyBuilds(temp(), listOf(stage { c ->
            if (fail) throw StudyBuildFailure(StudyBuildProblem("loose-component", "Cutting separated material from the panel."))
            c.file("result.txt").writeText("ok"); StudyBuildProduct(listOf("result.txt"))
        }), { mapOf("shape" to source.get()) }).use { builds ->
            assertEquals("Not built yet", builds.stages().single().reason)
            builds.done(builds.startCurrent().id)
            assertEquals("Cutting separated material from the panel.", builds.stages().single().reason)
            source.set("changed")
            assertEquals("Inputs or recipe changed; rebuild this stage.", builds.stages().single().reason)
            fail = false
            builds.done(builds.startCurrent().id)
            assertNull(builds.stages().single().reason)
            source.set("changed again"); fail = true
            builds.done(builds.startCurrent().id)
            assertNull(builds.stages().single().current)
            assertEquals("Cutting separated material from the panel.", builds.stages().single().reason)
        }
    }

    @Test fun cancellationDoesNotPretendAnUncooperativeOperationHasStopped() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        StudyBuilds(temp(), listOf(stage { c ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            c.file("result.txt").writeText("late"); StudyBuildProduct(listOf("result.txt"))
        }), { mapOf("shape" to "same") }).use { builds ->
            val job = builds.startCurrent(); entered.arrives()
            val cancellation = builds.cancel(job.id)
            assertTrue(cancellation.cancelRequested)
            assertEquals(StudyBuildStatus.RUNNING, cancellation.status)
            assertFailsWith<IllegalStateException> { builds.startCurrent() }
            release.countDown()
            assertEquals(StudyBuildStatus.CANCELLED, builds.done(job.id).status)
            assertNull(builds.stages().single().current)
            assertEquals("Build cancelled; no current result was published.", builds.stages().single().reason)
        }
    }

    @Test fun queuedCancellationDoesNotRunTheRecipe() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var secondRan = false
        StudyBuilds(temp(), listOf(stage { c ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            c.file("a").writeText("a"); StudyBuildProduct(listOf("a"))
        }, stage("other") { c -> secondRan = true; c.file("b").writeText("b"); StudyBuildProduct(listOf("b")) }),
            { mapOf("shape" to "same") }, workers = 1).use { builds ->
            val first = builds.startCurrent(); entered.arrives()
            val second = builds.startCurrent("other")
            assertEquals(StudyBuildStatus.QUEUED, builds.cancel(second.id).status)
            release.countDown(); builds.done(first.id)
            assertEquals(StudyBuildStatus.CANCELLED, builds.done(second.id).status)
            assertFalse(secondRan)
        }
    }

    @Test fun historyAndArtifactsSurviveReopenButRecipeChangesInvalidateCurrency() {
        val root = temp()
        val original = StudyBuilds(root, listOf(stage()), { mapOf("shape" to "saved") }).use { it.done(it.startCurrent().id) }
        StudyBuilds(root, listOf(stage()), { mapOf("shape" to "saved") }).use { reopened ->
            assertEquals(original, reopened.job(original.id))
            assertEquals(original.id, reopened.stages().single().current?.id)
            assertTrue(File(root, "attempts/${original.id}/input.json").isFile)
        }
        StudyBuilds(root, listOf(stage(version = "v2")), { mapOf("shape" to "saved") }).use { changed ->
            assertNull(changed.stages().single().current)
            assertEquals(original.id, changed.history().single().id)
        }
    }

    @Test fun reopeningAnInterruptedRunnerRequiresAnExplicitRetry() {
        val root = temp(); val entered = CountDownLatch(1)
        val definition = stage { c -> entered.countDown(); Thread.sleep(10_000); c.file("a").writeText("a"); StudyBuildProduct(listOf("a")) }
        val builds = StudyBuilds(root, listOf(definition), { mapOf("shape" to "saved") })
        val job = builds.startCurrent(); entered.arrives(); builds.close()
        StudyBuilds(root, listOf(stage()), { mapOf("shape" to "saved") }).use { reopened ->
            assertEquals(StudyBuildStatus.INTERRUPTED, reopened.job(job.id).status)
            assertNull(reopened.stages().single().current)
            assertEquals("Build interrupted; inspect the attempt before retrying.", reopened.stages().single().reason)
            assertEquals(1, reopened.history().size)
            assertEquals(StudyBuildStatus.SUCCEEDED, reopened.done(reopened.startCurrent().id).status)
        }
    }

    @Test fun aManifestLeftRunningByACrashIsNotReportedAsStillRunning() {
        val root = temp()
        val original = StudyBuilds(root, listOf(stage()), { mapOf("shape" to "saved") }).use { it.done(it.startCurrent().id) }
        val manifest = File(root, "builds.json")
        manifest.writeText(manifest.readText().replace("\"SUCCEEDED\"", "\"RUNNING\""))
        StudyBuilds(root, listOf(stage()), { mapOf("shape" to "saved") }).use { reopened ->
            assertEquals(StudyBuildStatus.INTERRUPTED, reopened.job(original.id).status)
            assertEquals("interrupted", reopened.job(original.id).problem?.code)
            assertNull(reopened.stages().single().current)
        }
    }

    @Test fun corruptArtifactsInvalidateTheirDependantsAndDependencyReadsVerifyHashes() {
        StudyBuilds(temp(), listOf(stage(), stage("derived", deps = listOf("mesh")) { c ->
            c.file("copy").writeText(c.dependencyFile("mesh", "result.txt").readText()); StudyBuildProduct(listOf("copy"))
        }), { mapOf("shape" to "1234") }).use { builds ->
            val a = builds.done(builds.startCurrent().id)
            val b = builds.done(builds.startCurrent("derived").id)
            assertEquals(StudyBuildStatus.SUCCEEDED, b.status)
            File(builds.directory, "attempts/${a.id}/result.txt").writeText("4321")
            assertNull(builds.stages().first { it.id == "mesh" }.current)
            assertNull(builds.stages().first { it.id == "derived" }.fingerprint)
            assertFailsWith<IllegalStateException> { StudyBuildContext(builds, b).dependencyFile("mesh", "result.txt") }
        }
    }

    @Test fun twoOwnersCannotWriteOneManifestAndPathsCannotOverwriteFrozenInputs() {
        val root = temp()
        StudyBuilds(root, listOf(stage()), { mapOf("shape" to "same") }).use { builds ->
            assertFailsWith<IllegalStateException> { StudyBuilds(root, listOf(stage()), { emptyMap() }) }
            val job = builds.done(builds.startCurrent().id)
            for (path in listOf("../other", "/tmp/elsewhere", "input.json", "./input.json", "sub/../input.json")) {
                assertFailsWith<IllegalArgumentException>(path) { builds.artifactFile(job.id, path) }
            }
            val outside = File(temp(), "outside")
            Files.createSymbolicLink(File(builds.directory, "attempts/${job.id}/escape").toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> { builds.artifactFile(job.id, "escape") }
        }
    }

    @Test fun nativeOrAssertionErrorsBecomeTerminalFailures() {
        StudyBuilds(temp(), listOf(stage { throw AssertionError("native invariant failed") }), { mapOf("shape" to "same") }).use { builds ->
            val job = builds.done(builds.startCurrent().id)
            assertEquals(StudyBuildStatus.FAILED, job.status)
            assertEquals("native invariant failed", job.problem?.message)
        }
    }

    @Test fun definitionsRejectCyclesDuplicatesAndMissingDependenciesBeforeStartingWorkers() {
        assertFailsWith<IllegalArgumentException> { StudyBuilds(temp(), listOf(stage(), stage()), { emptyMap() }) }
        assertFailsWith<IllegalArgumentException> { StudyBuilds(temp(), listOf(stage(deps = listOf("absent"))), { emptyMap() }) }
        assertFailsWith<IllegalArgumentException> { StudyBuilds(temp(), listOf(stage("a", deps = listOf("b")), stage("b", deps = listOf("a"))), { emptyMap() }) }
        StudyBuilds(temp(), listOf(stage()), { emptyMap() }).use { builds ->
            assertNull(builds.stages().single().fingerprint)
            assertContains(builds.stages().single().reason!!, "shape")
        }
    }
}
