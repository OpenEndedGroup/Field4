@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.study.build

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
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
import afield.runtime.execution.start
import afield.simulation.automata.history

import afield.audio.synth.*
import afield.document.properties.clear
import afield.graphics.vulkan.buffers.add
import afield.media.image.channel
import afield.media.image.close
import afield.study.source.BoxStudies
import afield.document.graph.Box
import afield.app.server.ReadOnlyDocuments
import afield.app.server.SessionInformation
import afield.document.graph.registerStopHook
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Attach durable derived stages to this box, discoverable by every AI4 chat in its document.
 *
 * Re-running replaces this named runner and revokes any unfinished publication. Stopping/deleting
 * the box closes the runner. Attempt history survives under the document's `studies` directory.
 * [readInputs] must read one immutable snapshot of the existing design authority; agents and mouse
 * controls must edit that authority, not the captured build inputs. It may run on a worker thread.
 * [gates] are inputs that exist only while their checks pass ([StudyBuildGate]). A stage may depend
 * on a stage of another runner in this document, written `dependsOn(runner, stage)`. With [exclusive],
 * the runner builds one stage at a time and refuses a start while another build runs.
 */
fun Box.studyBuilds(name: String, stages: List<StudyBuildStage>, workers: Int = 2,
    gates: List<StudyBuildGate> = emptyList(), exclusive: Boolean = false, readInputs: () -> Map<String, String>): StudyBuilds {
    require(name.matches(STUDY_BUILD_NAME)) { "Build runner name must be a simple word" }
    require((stages.flatMap { it.dependencies } + gates.flatMap { it.dependencies }).none { it.startsWith("$name/") }) {
        "A dependency names this runner '$name'; name a stage of the same runner by its ID alone"
    }
    check(!ReadOnlyDocuments.blocksWritesFrom(this)) { "This document is read-only" }
    val key = "_studyBuilds:$name"
    (properties[key] as? BoxStudyBuildBinding)?.close()
    val document = BoxStudies.documentOf(this)
    val store = StudyBuilds(File(BoxStudies.directory(this), "builds/$name"), stages, readInputs, workers, gates, exclusive) { runner ->
        studyBuildRunner(document, runner)
    }
    val binding = BoxStudyBuildBinding(this, name, store, document)
    properties[key] = binding
    binding.stopHook = registerStopHook(Runnable { binding.close() })
    return store
}

internal class BoxStudyBuildBinding(val owner: Box, val name: String, val builds: StudyBuilds,
    val document: SessionInformation? = null) : AutoCloseable {
    var stopHook: Runnable? = null
    val panels = mutableMapOf<String, StudyBuildPanel>()
    init { if (document != null) liveBindings.add(this) }
    override fun close() {
        liveBindings.remove(this)
        try { panels.values.forEach { it.close() }; panels.clear(); builds.close() } finally {
            stopHook?.run(); stopHook = null
            val key = "_studyBuilds:$name"
            if (owner.properties[key] === this) owner.properties.remove(key)
        }
    }
}

// Runners by document, readable from build workers without walking a document's boxes.
private val liveBindings: MutableSet<BoxStudyBuildBinding> = ConcurrentHashMap.newKeySet()

/** The live runner called [runner] in [document], for `dependsOn(runner, stage)`; null when there is none. */
internal fun studyBuildRunner(document: SessionInformation?, runner: String): StudyBuilds? {
    if (document == null) return null
    val found = liveBindings.filter { it.document === document && it.name == runner }
    check(found.size <= 1) { "Two boxes in this document have a build runner named '$runner'; rename one" }
    return found.singleOrNull()?.builds
}

/** Show this named runner's live stage buttons, progress and selectable diagnostics in a box output. */
fun Box.showStudyBuilds(name: String, channel: String = "builds") {
    val binding = properties["_studyBuilds:$name"] as? BoxStudyBuildBinding
        ?: kotlin.error("No build runner '$name' on this box; call studyBuilds first")
    binding.panels.getOrPut(channel) { StudyBuildPanel(binding, channel) }
}

internal fun studyBuildBindings(boxes: Collection<Box>): List<BoxStudyBuildBinding> = boxes.distinct().flatMap { box ->
    box.properties.values.filterIsInstance<BoxStudyBuildBinding>().filter { it.owner === box }
}
