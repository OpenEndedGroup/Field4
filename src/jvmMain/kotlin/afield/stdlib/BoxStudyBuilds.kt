@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.ReadOnlyDocuments
import afield.registerStopHook
import java.io.File

/**
 * Attach durable derived stages to this box, discoverable by every AI4 chat in its document.
 *
 * Re-running replaces this named runner and revokes any unfinished publication. Stopping/deleting
 * the box closes the runner. Attempt history survives under the document's `studies` directory.
 * [readInputs] must read one immutable snapshot of the existing design authority; agents and mouse
 * controls must edit that authority, not the captured build inputs. It may run on a worker thread.
 */
fun Box.studyBuilds(name: String, stages: List<StudyBuildStage>, workers: Int = 2,
    readInputs: () -> Map<String, String>): StudyBuilds {
    require(name.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Build runner name must be a simple word" }
    check(!ReadOnlyDocuments.blocksWritesFrom(this)) { "This document is read-only" }
    val key = "_studyBuilds:$name"
    (properties[key] as? BoxStudyBuildBinding)?.close()
    val store = StudyBuilds(File(BoxStudies.directory(this), "builds/$name"), stages, readInputs, workers)
    val binding = BoxStudyBuildBinding(this, name, store)
    properties[key] = binding
    binding.stopHook = registerStopHook(Runnable { binding.close() })
    return store
}

internal class BoxStudyBuildBinding(val owner: Box, val name: String, val builds: StudyBuilds) : AutoCloseable {
    var stopHook: Runnable? = null
    val panels = mutableMapOf<String, StudyBuildPanel>()
    override fun close() {
        try { panels.values.forEach { it.close() }; panels.clear(); builds.close() } finally {
            stopHook?.run(); stopHook = null
            val key = "_studyBuilds:$name"
            if (owner.properties[key] === this) owner.properties.remove(key)
        }
    }
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
