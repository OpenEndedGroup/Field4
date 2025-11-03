@file:OptIn(ExperimentalSerializationApi::class)

package afield

import afield.logger.lprintln
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class Box_transfer(val id: String, val properties: Map<String, Pair<String, JsonElement>>)

class SavingContext {

    fun listTemplates(dir: String): List<String> {
        val d = File(dir).listFiles { file ->
            file.isDirectory && File(file, "/metadata.json").exists()
        }
        if (d == null) return emptyList<String>()
        return d.map { it.name }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun cloneInto(dir: String): Set<Box> {
        val seen = mutableMapOf<String, String>("root" to "root")
        return load(dir) { id ->
            val next = seen.computeIfAbsent(id) { Uuid.random().toString() }
            next
        }
    }

    var altered = false
    fun toTransfer(boxes: Set<Box>, directory: String): Set<Box_transfer> {

        return boxes.map { box ->

            val r = Box.ioPersistentProperties.map { it.toRegex() }

            val properties = box.properties.filter { (name, _) ->
                r.any { name.matches(it) }
            }

            val p = properties.mapNotNull { (it, v) ->

                if (Box.ioSeparateProperties.containsKey(it)) {
                    if (v == null) null
                    else it to ("__file__" to toFile(
                        box.id,
                        it,
                        Box.ioSeparateProperties[it]!!,
                        directory,
                        v as String
                    ))
                } else if (v == null) null
                else toTypedPair(v, it)
            }.toMap()

            Box_transfer(box.id, p)
        }.toSet()
    }

    fun checkDifference(p: String, contents: String): Boolean {
        if (!File(p).exists()) {
            File(p).safeSave(contents)
            return true
        } else {
            try {
                return File(p).safeSave(contents)
            } catch (ex: Exception) {
                File(p).writeText(contents)
                return true
            }
        }
    }

    fun toFile(id: String, name: String, extension: String, directory: String, contents: String): JsonElement {
        val path = filenameFor(id, name, extension)
        val fn = format.encodeToJsonElement(path)

        val cd = checkDifference(directory + "/" + path, contents)
        altered = altered || cd

        return fn
    }


    private fun toTypedPair(
        v: Any, name: String = ""
    ): Pair<String, Pair<String, JsonElement>>? = when (v) {
        is Rect -> name to ("Rect" to format.encodeToJsonElement(v))
        is String -> name to ("String" to format.encodeToJsonElement(v))
        is Number -> name to ("Number" to format.encodeToJsonElement(v))
        is Box -> name to ("Box" to format.encodeToJsonElement(v.id))
        is Boolean -> name to ("Boolean" to format.encodeToJsonElement(v))
        is List<*> -> name to ("List" to format.encodeToJsonElement(v.map { vs ->
            if (vs != null) {
                toTypedPair(vs)?.second
            } else null
        }))

        is Set<*> -> name to ("Set" to format.encodeToJsonElement(v.map { vs ->
            if (vs != null) {
                toTypedPair(vs)?.second
            } else null
        }))

        else -> {
            lprintln(" unhandled class $v ${v.javaClass}")
            null
        }
    }


    companion object {
        val format = Json {
            prettyPrint = true
        }

        fun filenameFor(id: String, name: String, extension: String): String {
            return id + "_" + name + "_." + extension
        }

    }

    fun ensureDirectory(fn: String): String {
        File(fn).mkdirs()
        return fn
    }

    fun save(fn: String, boxes: Set<Box>, message: String? = null) {
        val root = ensureDirectory(fn)
        val git = ensureRepository(fn)
        val cd = checkDifference(root + "/metadata.json", format.encodeToString(toTransfer(boxes, root)))
        altered = altered || cd
        if (altered) {
            commit(fn, message)
        }
    }

    fun load(fn: String, filterId: (String) -> String = { it }): Set<Box> {
        val root = ensureDirectory(fn)
        val transfers = format.decodeFromString<Set<Box_transfer>>(File(root, "metadata.json").readText())
        return transfers.map {
            val id = filterId(it.id)
            val b = box(id, createIfMissing = true)
            it.properties.forEach { (name, element) ->

                val v = fromTypedPair(element, root, filterId)

                if (v != null) {
                    b.setPropertyRaw(name, v)
                }
            }

            if (id != it.id) {
                b.setPropertyRaw("source_id", it.id)
            }

            b
        }.toSet()
    }

    fun fromTypedPair(
        element: Pair<String, JsonElement>,
        directory: String,
        filterId: (String) -> String = { it }
    ): Any? = when (element.first) {
        "__file__" -> File(directory + "/" + format.decodeFromJsonElement<String>(element.second)).readText()
        "Rect" -> format.decodeFromJsonElement<Rect>(element.second) as Any
        "String" -> format.decodeFromJsonElement<String>(element.second) as Any
        "Int" -> format.decodeFromJsonElement<Int>(element.second) as Any
        "Number" -> format.decodeFromJsonElement<Number>(element.second) as Any
        "Boolean" -> format.decodeFromJsonElement<Boolean>(element.second) as Any
        "Box" -> {
            val source_id = format.decodeFromJsonElement<String>(element.second)
            val id = filterId(source_id)
            var v = box(id, createIfMissing = true)
            if (id != source_id) {
                v.setPropertyRaw("source_id", source_id)
            }
            v
        }

        "List" -> format.decodeFromJsonElement<List<Pair<String, JsonElement>>>(element.second).map {
            fromTypedPair(it, directory, filterId)
        }.toMutableList() as Any

        "Set" -> format.decodeFromJsonElement<List<Pair<String, JsonElement>>>(element.second).map {
            fromTypedPair(it, directory, filterId)
        }.toMutableSet() as Any

        else -> {
            lprintln(" can't decode $element")
            null
        }
    }
}


fun File.safeSave(text: String): Boolean {
    val file = this
    if (file.exists()) {
        // Read current file content
        val currentContent = file.readText()
        if (currentContent != text) {
            // Only create the backup directory when a backup is needed
            val backupDir = File(file.parentFile, "backup")
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            // Determine backup file name inside the backup directory
            val baseName = file.nameWithoutExtension
            val extension = file.extension
            var backupIndex = 0
            var backupFile: File
            do {
                val indexStr = String.format("%05d", backupIndex)
                val backupFileName = if (extension.isNotEmpty())
                    "${baseName}_backup_$indexStr.$extension"
                else
                    "${baseName}_backup_$indexStr"
                backupFile = File(backupDir, backupFileName)
                backupIndex++
            } while (backupFile.exists())
            // Rename the current file to the backup file name in the backup directory
            file.renameTo(backupFile)
        } else {
            // If the content is the same, do nothing.
            return false
        }
    }
    // Write the new text to the file
    file.writeText(text)
    return true
}
