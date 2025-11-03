package afield

import afield.logger.lprintln
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
class Entry {
    var filename: String = ""
    var numBoxes: Int = -1
    var lastModified: Long = -1
    var boxes: MutableList<Pair<String, String>> = mutableListOf()
    var metadata: MutableList<Pair<String, List<String>>> = mutableListOf()
}

fun registerTemplate(dir: String) {

    val format = Json {
        prettyPrint = true
    }


    templates["dir_json"] = { template, everything ->
        val entries = simpleDir(dir)

        format.encodeToString(entries)
    }

    runAll(templatesRegistration)
}

fun simpleDir(base: String): List<Entry> {
    val baseDir = File(base)
    if (!baseDir.exists() || !baseDir.isDirectory) {
        return emptyList()
    }

    val entries = mutableListOf<Entry>()

    var s = SavingContext()

    baseDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        val metadataFile = File(dir, "metadata.json")
        if (metadataFile.exists()) {
            try {
                val entry = Entry()
                entry.filename = dir.name

                // Read metadata.json to count boxes
                val metadataContent = metadataFile.readText()
                val transfers = SavingContext.Companion.format.decodeFromString<Set<Box_transfer>>(metadataContent).filter { !it.properties.contains("_tombstone") }
                entry.numBoxes = transfers.size

                transfers.forEach { transfer ->
                    var contents = ""
                    var metadata = ""

                    try {
                        var name = s.fromTypedPair(transfer.properties.get("name")!!, dir.absolutePath).toString()
                        transfer.properties.get("code")?.let {
                            contents = s.fromTypedPair(it, dir.absolutePath)?.toString() ?: ""
                        }
                        transfer.properties.get("metadata")?.let {
                            metadata = s.fromTypedPair(it, dir.absolutePath)?.toString() ?: ""
                        }

                        if (contents.isNotBlank()) {
                            val c = (tagsForString(metadata, "tags") ?: emptyList())
                            entry.boxes.add(name to contents)
                            entry.metadata.add(name to c)
                        }
                    } catch (e: NullPointerException) {
                        // we supress this for now
                        println("${entry.filename} failed to get name or code for ${transfer}")
                    }
                }

                // Get last modified time
                entry.lastModified = System.currentTimeMillis() - metadataFile.lastModified()

                entries.add(entry)
            } catch (e: Exception) {
                // Skip directories with invalid metadata
                lprintln("Error processing directory ${dir.name}: ${e.message}")
            }
        }
    }

    return entries
}
