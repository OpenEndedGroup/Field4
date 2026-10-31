package afield.document.workspace

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.audio.synth.*
import afield.document.graph.Box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import afield.document.properties.Delegation
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Per-box YAML metadata and the tags read from it. A box's `metadata` property holds YAML text, saved
 * as the `<uid>_metadata_.yaml` sidecar; [tagsForBox] reads one key of it as a list of strings, and
 * [tagDatabase] scans every document folder under a workspace root for those sidecars and collects
 * each box's `tags`.
 *
 * [tagsForBox] returns null when the YAML is not a map or lacks the key; [tagDatabase] skips a sidecar
 * whose YAML is not a map. A YAML syntax error is not caught in either and propagates.
 */

/** The box's YAML metadata text; empty by default. */
var Box.metadata by Delegation<String>(defaultValue = { "" })

/** The box uid a sidecar filename begins with, taken as everything before its first underscore. */
fun idFromFilename(fn: String) = fn.split("_")[0]

/** One box in one document, as [tagDatabase] names the boxes it found. */
data class BoxReference(
    /** The absolute path of the document folder, not the document's name. */
    val document: String,
    /** The box uid, read off the sidecar's filename by [idFromFilename]. */
    val id: String
)

/**
 * Every tagged box under a workspace root, as a box and the tags its `metadata` sidecar gives it. Reads each
 * document folder's `<uid>_metadata_.yaml` files and takes their `tags` key, accepting a single value as a
 * one-element list. A sidecar whose YAML is not a map, or which has no `tags`, is left out rather than
 * reported; a YAML syntax error propagates, and so does a [root] that is not a directory.
 */
fun tagDatabase(root: String): List<Pair<BoxReference, List<String>>> {
    return File(root).listFiles {
        it.isDirectory
    }.flatMap { parent ->
        parent.listFiles().filter { f ->
            f.name.contains("_metadata_.yaml")
        }.mapNotNull {
            try {
                val id = idFromFilename(it.name)
                val t = (Yaml().load(it.readText()) as Map<String, Any?>)["tags"]
                val t2 = if (t is Collection<*>) t.map { it.toString() } else listOf(t.toString())
                BoxReference(parent.canonicalFile.absolutePath, id) to t2
            } catch (e: ClassCastException) {
                null
            } catch (e: NullPointerException) {
                null
            }
        }
    }
}

/**
 * A box's YAML metadata as a map. Nothing is caught here: empty text loads as null and fails the cast, and
 * YAML that is a list or a scalar does the same, so a caller that does not know the text is a map should
 * wrap this.
 */
fun parseMetadata(s: String) = Yaml().load(s) as Map<String, Any?>


/**
 * [tagsForString] over a box's own `metadata` property. The [key] has no useful default: left out, the
 * lookup asks for a null key and the result is null for all but the rare YAML that has one.
 */
fun tagsForBox(b: Box, key: String? = null) = tagsForString(b.metadata, key)

/**
 * One key of a YAML map as a list of strings, with a single value read as a one-element list and everything
 * stringified. Null when the text is not a map, when the key is absent, and when its value is null, so a
 * caller cannot tell those apart; callers in this repo pass `"tags"` and treat null as no tags.
 */
fun tagsForString(s: String, key: String? = null): List<String>? {

    try {
        val a = Yaml().load(s) as Map<String, Any?>
        val v = a[key]!!
        return if (v is Collection<*>) return v.toList().map { it.toString() }
        else listOf(v.toString())
    } catch (e: ClassCastException) {
        return null
    } catch (e: NullPointerException) {
        return null
    }
}
