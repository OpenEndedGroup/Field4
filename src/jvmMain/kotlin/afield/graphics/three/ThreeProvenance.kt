package afield.graphics.three

import afield.document.graph.Box
import afield.runtime.kotlin.fieldSourcePropertyFromFileName

/** Where in a box's source a part of a `three { … }` scene was added; see [afield.app.protocol.GeometrySourceLine]. */
typealias ThreeSourceLine = afield.app.protocol.GeometrySourceLine

private val assignedName = Regex("""^\s*(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")

/**
 * The first frame of [owner]'s own compiled source on the current stack. Box snippets are compiled
 * with a file name built from the box id and the property (`fieldSourceName`), and their line numbers
 * are already the editor's lines (the error marker reads them the same way), so no table lookup is
 * needed past the file name. `null` outside a box's code: a library helper building a scene.
 */
internal fun threeCallSite(owner: Box?): ThreeSourceLine? {
    val id = owner?.id ?: return null
    val frame = StackWalker.getInstance().walk { frames ->
        frames.filter { f -> f.lineNumber > 0 && f.fileName?.contains(id) == true }.findFirst().orElse(null)
    } ?: return null
    val property = fieldSourcePropertyFromFileName(frame.fileName) ?: "code"
    val text = (owner.properties[property] as? String)?.lineSequence()?.drop(frame.lineNumber - 1)?.firstOrNull()
    return ThreeSourceLine(id, property, frame.lineNumber, text?.let { assignedName.find(it)?.groupValues?.get(1) })
}
