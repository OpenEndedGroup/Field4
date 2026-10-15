package afield.study.build

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.data.collections.r
import afield.document.workspace.file
import afield.runtime.execution.start
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Helpers shared by the StudyBuilds feature tests. */
internal object StudyBuildTestSupport {
    fun temp(): File = Files.createTempDirectory("study-builds-").toFile()

    /** A stage that writes [file] from one input and reports [facts] computed from it. */
    fun writing(id: String, key: String, file: String = "result.txt", dependencies: List<String> = emptyList(),
        facts: (String) -> Map<String, Any?> = { emptyMap() }) =
        StudyBuildStage(id, id.replaceFirstChar { it.uppercase() }, "$id-v1", setOf(key), dependencies) { c ->
            val value = c.inputs.getValue(key)
            c.file(file).writeText(value)
            StudyBuildProduct(listOf(file), facts(value))
        }

    fun StudyBuilds.state(stage: String) = stages().first { it.id == stage }
    fun StudyBuilds.startCurrent(stage: String, request: Map<String, Any?> = emptyMap()) =
        start(stage, checkNotNull(state(stage).fingerprint) { state(stage).reason.orEmpty() }, request)

    fun StudyBuilds.done(id: String): StudyBuildRecord {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val r = job(id)
            if (r.status !in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)) return r
            Thread.sleep(5)
        }
        error("Build $id never became terminal: ${job(id)}")
    }

    fun StudyBuilds.build(stage: String, request: Map<String, Any?> = emptyMap()) = done(startCurrent(stage, request).id)

    fun CountDownLatch.arrives() = assertTrue(await(5, TimeUnit.SECONDS), "Worker did not reach the test barrier")
}
