package afield.ui.slides

import afield.app.server.ReadOnlyDocuments
import afield.document.graph.Box
import afield.document.graph.stopTrackedJobs

/** An in-memory baseline of explicitly named local text properties; never a snapshot of runtime objects. */
class LessonCheckpoint private constructor(private val entries: List<Entry>) {
    private data class Entry(val box: Box, val property: String, val value: String)

    companion object {
        /** Capture source before experimenting. Unlisted boxes, files and properties remain outside the checkpoint. */
        fun capture(vararg boxes: Box, properties: List<String> = listOf("code")): LessonCheckpoint {
            require(boxes.isNotEmpty() && properties.isNotEmpty()) { "Name the boxes and text properties to capture" }
            val entries = boxes.distinct().flatMap { box -> properties.distinct().map { property ->
                Entry(box, property, box.getPropertyRaw(property) as? String
                    ?: error("${box.name}.$property is not a local text property"))
            } }
            return LessonCheckpoint(entries)
        }
    }

    /** Stop the captured boxes and restore their text. Run setup explicitly afterwards to recreate output. */
    fun restore() {
        check(!ReadOnlyDocuments.global) { "Cannot reset a read-only document" }
        entries.map { it.box }.distinct().filter { it.isInsideInitialized() }.forEach { box ->
            check(!box.inside.readOnly) { "Cannot reset a read-only document" }
            check(box === box.inside.root || box.inside.boxes[box.id] === box) { "A checkpoint box was removed; rebuild the baseline" }
        }
        entries.map { it.box }.distinct().forEach { it.stopTrackedJobs() }
        entries.forEach {
            it.box.setPropertyRaw(it.property, it.value, doResync = true)
            if (it.box.isInsideInitialized() && it.box.inside.writesToDisk && Box.ioSeparateProperties.containsKey(it.property))
                it.box.inside.markSidecarPending(it.box.id, it.property)
        }
    }
}
