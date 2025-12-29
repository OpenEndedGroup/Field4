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

    val s = SavingContext()

    baseDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        val metadataEdnFile = File(dir, SavingContext.METADATA_EDN)
        val metadataJsonFile = File(dir, SavingContext.METADATA_JSON)
        val hasEdn = metadataEdnFile.exists()
        val hasJson = metadataJsonFile.exists()
        if (!hasEdn && !hasJson) return@forEach

        try {
            val entry = Entry()
            entry.filename = dir.name

            if (hasEdn) {
                val transfers = s.readEdnTransfers(dir.absolutePath)
                    .filter { !it.properties.containsKey("_tombstone") }
                entry.numBoxes = transfers.size

                transfers.forEach { transfer ->
                    var contents = ""
                    var metadata = ""
                    val name =
                        s.resolveStoredValue(transfer.properties["name"], dir.absolutePath)?.toString() ?: ""
                    transfer.properties["code"]?.let {
                        contents = s.resolveStoredValue(it, dir.absolutePath)?.toString() ?: ""
                    }
                    transfer.properties["metadata"]?.let {
                        metadata = s.resolveStoredValue(it, dir.absolutePath)?.toString() ?: ""
                    }

                    if (contents.isNotBlank()) {
                        val c = (tagsForString(metadata, "tags") ?: emptyList())
                        entry.boxes.add(name to contents)
                        entry.metadata.add(name to c)
                    }
                }

                entry.lastModified = System.currentTimeMillis() - metadataEdnFile.lastModified()
            } else {
                val metadataContent = metadataJsonFile.readText()
                val transfers = SavingContext.format.decodeFromString<Set<Box_transfer>>(metadataContent)
                    .filter { !it.properties.contains("_tombstone") }
                entry.numBoxes = transfers.size

                transfers.forEach { transfer ->
                    var contents = ""
                    var metadata = ""

                    try {
                        val name =
                            s.fromTypedPair(transfer.properties.get("name")!!, dir.absolutePath).toString()
                        transfer.properties.get("code")?.let {
                            contents = s.fromTypedPair(it, dir.absolutePath, createBoxes = false)?.toString() ?: ""
                        }
                        transfer.properties.get("metadata")?.let {
                            metadata = s.fromTypedPair(it, dir.absolutePath, createBoxes = false)?.toString() ?: ""
                        }

                        if (contents.isNotBlank()) {
                            val c = (tagsForString(metadata, "tags") ?: emptyList())
                            entry.boxes.add(name to contents)
                            entry.metadata.add(name to c)
                        }
                    } catch (e: NullPointerException) {
                        // we supress this for now
                        lprintln("warning,dir", "${entry.filename} failed to get name or code for ${transfer}")
                    }
                }

                entry.lastModified = System.currentTimeMillis() - metadataJsonFile.lastModified()
            }

            entries.add(entry)
        } catch (e: Exception) {
            // Skip directories with invalid metadata
            lprintln("error,dir", "Error processing directory ${dir.name}: ${e.message}")
        }
    }

    return entries
}
