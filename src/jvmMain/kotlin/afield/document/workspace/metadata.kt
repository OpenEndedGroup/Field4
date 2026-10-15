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

var Box.metadata by Delegation<String>(defaultValue = { "" })

fun idFromFilename(fn: String) = fn.split("_")[0]

data class BoxReference(val document: String, val id: String)

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

fun parseMetadata(s: String) = Yaml().load(s) as Map<String, Any?>


fun tagsForBox(b: Box, key: String? = null) = tagsForString(b.metadata, key)
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
