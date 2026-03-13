package afield

import afield.logger.lprintln
import kotlinx.serialization.Serializable
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

@Serializable
data class WelcomeEntry(
    val filename: String,
    val numBoxes: Int,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val lastModifiedAgoMs: Long
)

@Serializable
data class WelcomeDocsResponse(
    val documents: List<WelcomeEntry>,
    val lastOpened: String,
    val defaultDocument: String
)

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
                    .filter { !it.properties["_tombstone"].isTrue() }
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
                    .filter { !it.properties["_tombstone"].isTrue() }
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

fun parseRecentDocuments(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

fun updateRecentDocuments(raw: String?, documentName: String, maxEntries: Int = 200): String {
    val clean = documentName.trim()
    if (clean.isBlank()) return raw ?: ""

    val updated = mutableListOf(clean)
    parseRecentDocuments(raw).forEach {
        if (it != clean && updated.size < maxEntries) updated += it
    }
    return updated.take(maxEntries).joinToString("\n")
}

fun welcomeDir(base: String, recents: List<String>): List<WelcomeEntry> {
    val baseDir = File(base)
    if (!baseDir.exists() || !baseDir.isDirectory) {
        return emptyList()
    }

    val recencyOrder = recents.withIndex().associate { it.value to it.index }
    val entries = mutableListOf<WelcomeEntry>()
    val now = System.currentTimeMillis()
    val s = SavingContext()

    baseDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        val metadataEdnFile = File(dir, SavingContext.METADATA_EDN)
        val metadataJsonFile = File(dir, SavingContext.METADATA_JSON)
        val metadataFile = when {
            metadataEdnFile.exists() -> metadataEdnFile
            metadataJsonFile.exists() -> metadataJsonFile
            else -> null
        } ?: return@forEach

        try {
            val numBoxes = if (metadataFile.name == SavingContext.METADATA_EDN) {
                s.readEdnTransfers(dir.absolutePath).count { !it.properties["_tombstone"].isTrue() }
            } else {
                val metadataContent = metadataJsonFile.readText()
                SavingContext.format.decodeFromString<Set<Box_transfer>>(metadataContent)
                    .count { !it.properties["_tombstone"].isTrue() }
            }

            val modifiedAt = metadataFile.lastModified()
            entries += WelcomeEntry(
                filename = dir.name,
                numBoxes = numBoxes,
                sizeBytes = metadataFile.length(),
                lastModifiedEpochMs = modifiedAt,
                lastModifiedAgoMs = now - modifiedAt
            )
        } catch (e: Exception) {
            lprintln("warning,welcome", "Skipping ${dir.name}: ${e.message}")
        }
    }

    return entries.sortedWith(
        compareBy<WelcomeEntry> { recencyOrder[it.filename] ?: Int.MAX_VALUE }
            .thenByDescending { it.lastModifiedEpochMs }
    )
}
