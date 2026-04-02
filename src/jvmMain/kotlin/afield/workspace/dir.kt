package afield

import afield.logger.lprintln
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.max
import kotlin.math.min

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

@Serializable
data class WorkspaceSearchMatch(
    val boxName: String,
    val property: String,
    val line: Int,
    val snippet: String
)

@Serializable
data class WorkspaceSearchResult(
    val filename: String,
    val numBoxes: Int,
    val lastModifiedEpochMs: Long,
    val lastModifiedAgoMs: Long,
    val matches: List<WorkspaceSearchMatch>
)

@Serializable
data class WorkspaceSearchResponse(
    val query: String,
    val documents: List<WorkspaceSearchResult>,
    val totalDocuments: Int,
    val totalMatches: Int,
    val truncated: Boolean
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

private data class SearchOptions(
    val maxDocuments: Int = 50,
    val maxMatchesPerDocument: Int = 5
)

private val workspaceSearchProperties = linkedSetOf(
    "name",
    "code",
    "fragment",
    "vertex",
    "compute",
    "task",
    "mesh",
    "fragment_s",
    "vertex_s",
    "compute_s",
    "task_s",
    "mesh_s",
    "customization",
    "remote_js",
    "metadata"
)

fun searchWorkspace(
    base: String,
    rawQuery: String,
    maxDocuments: Int = 50,
    maxMatchesPerDocument: Int = 5
): WorkspaceSearchResponse {
    val query = rawQuery.trim()
    if (query.isBlank()) {
        return WorkspaceSearchResponse(
            query = "",
            documents = emptyList(),
            totalDocuments = 0,
            totalMatches = 0,
            truncated = false
        )
    }

    val baseDir = File(base)
    if (!baseDir.exists() || !baseDir.isDirectory) {
        return WorkspaceSearchResponse(
            query = query,
            documents = emptyList(),
            totalDocuments = 0,
            totalMatches = 0,
            truncated = false
        )
    }

    val options = SearchOptions(
        maxDocuments = maxDocuments.coerceIn(1, 200),
        maxMatchesPerDocument = maxMatchesPerDocument.coerceIn(1, 20)
    )
    val now = System.currentTimeMillis()
    val searchTerm = query.lowercase()
    val savingContext = SavingContext()
    val results = mutableListOf<WorkspaceSearchResult>()
    var totalMatches = 0
    var truncated = false

    baseDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending {
        max(
            File(it, SavingContext.METADATA_EDN).lastModified(),
            File(it, SavingContext.METADATA_JSON).lastModified()
        )
    }?.forEach { dir ->
        if (results.size >= options.maxDocuments) {
            truncated = true
            return@forEach
        }

        val metadataEdnFile = File(dir, SavingContext.METADATA_EDN)
        val metadataJsonFile = File(dir, SavingContext.METADATA_JSON)
        val metadataFile = when {
            metadataEdnFile.exists() -> metadataEdnFile
            metadataJsonFile.exists() -> metadataJsonFile
            else -> null
        } ?: return@forEach

        try {
            val matches = if (metadataFile.name == SavingContext.METADATA_EDN) {
                searchEdnDocument(savingContext, dir.absolutePath, searchTerm, options)
            } else {
                searchJsonDocument(savingContext, metadataJsonFile, dir.absolutePath, searchTerm, options)
            }

            val filenameMatches = buildMatchesForText(
                boxName = dir.name,
                property = "document",
                text = dir.name,
                query = searchTerm,
                maxMatches = options.maxMatchesPerDocument
            )

            val combined = (filenameMatches + matches).take(options.maxMatchesPerDocument)
            if (combined.isEmpty()) return@forEach

            totalMatches += combined.size
            val modifiedAt = metadataFile.lastModified()
            results += WorkspaceSearchResult(
                filename = dir.name,
                numBoxes = countBoxes(savingContext, dir, metadataFile),
                lastModifiedEpochMs = modifiedAt,
                lastModifiedAgoMs = now - modifiedAt,
                matches = combined
            )
        } catch (e: Exception) {
            lprintln("warning,workspace-search", "Skipping ${dir.name}: ${e.message}")
        }
    }

    return WorkspaceSearchResponse(
        query = query,
        documents = results,
        totalDocuments = results.size,
        totalMatches = totalMatches,
        truncated = truncated
    )
}

private fun countBoxes(savingContext: SavingContext, dir: File, metadataFile: File): Int {
    return if (metadataFile.name == SavingContext.METADATA_EDN) {
        savingContext.readEdnTransfers(dir.absolutePath).count { !it.properties["_tombstone"].isTrue() }
    } else {
        val metadataContent = metadataFile.readText()
        SavingContext.format.decodeFromString<Set<Box_transfer>>(metadataContent)
            .count { !it.properties["_tombstone"].isTrue() }
    }
}

private fun searchEdnDocument(
    savingContext: SavingContext,
    directory: String,
    query: String,
    options: SearchOptions
): List<WorkspaceSearchMatch> {
    val matches = mutableListOf<WorkspaceSearchMatch>()
    savingContext.readEdnTransfers(directory)
        .filter { !it.properties["_tombstone"].isTrue() }
        .forEach { transfer ->
            val boxName = savingContext.resolveStoredValue(transfer.properties["name"], directory)?.toString().orEmpty()
            collectPropertyMatches(
                propertyProvider = { property ->
                    savingContext.resolveStoredValue(transfer.properties[property], directory)?.toString()
                },
                boxName = boxName,
                query = query,
                matches = matches,
                maxMatches = options.maxMatchesPerDocument
            )
            if (matches.size >= options.maxMatchesPerDocument) return matches
        }
    return matches
}

private fun searchJsonDocument(
    savingContext: SavingContext,
    metadataJsonFile: File,
    directory: String,
    query: String,
    options: SearchOptions
): List<WorkspaceSearchMatch> {
    val matches = mutableListOf<WorkspaceSearchMatch>()
    val metadataContent = metadataJsonFile.readText()
    SavingContext.format.decodeFromString<Set<Box_transfer>>(metadataContent)
        .filter { !it.properties["_tombstone"].isTrue() }
        .forEach { transfer ->
            val boxName = try {
                savingContext.fromTypedPair(transfer.properties["name"]!!, directory)?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
            collectPropertyMatches(
                propertyProvider = { property ->
                    transfer.properties[property]?.let {
                        savingContext.fromTypedPair(it, directory, createBoxes = false)?.toString()
                    }
                },
                boxName = boxName,
                query = query,
                matches = matches,
                maxMatches = options.maxMatchesPerDocument
            )
            if (matches.size >= options.maxMatchesPerDocument) return matches
        }
    return matches
}

private fun collectPropertyMatches(
    propertyProvider: (String) -> String?,
    boxName: String,
    query: String,
    matches: MutableList<WorkspaceSearchMatch>,
    maxMatches: Int
) {
    workspaceSearchProperties.forEach { property ->
        if (matches.size >= maxMatches) return
        val text = propertyProvider(property) ?: return@forEach
        val remaining = maxMatches - matches.size
        matches += buildMatchesForText(boxName, property, text, query, remaining)
    }
}

private fun buildMatchesForText(
    boxName: String,
    property: String,
    text: String,
    query: String,
    maxMatches: Int
): List<WorkspaceSearchMatch> {
    if (text.isBlank() || maxMatches <= 0) return emptyList()

    val lines = text.split('\n')
    val results = mutableListOf<WorkspaceSearchMatch>()

    lines.forEachIndexed { index, rawLine ->
        if (results.size >= maxMatches) return@forEachIndexed
        val line = rawLine.trim()
        if (!line.lowercase().contains(query)) return@forEachIndexed

        results += WorkspaceSearchMatch(
            boxName = boxName.ifBlank { "(unnamed box)" },
            property = property,
            line = index + 1,
            snippet = trimSnippet(rawLine, query)
        )
    }

    return results
}

private fun trimSnippet(line: String, query: String, maxLength: Int = 180): String {
    val trimmedLine = line.trim()
    if (trimmedLine.isBlank()) return ""

    val lowered = trimmedLine.lowercase()
    val startIndex = lowered.indexOf(query)
    if (startIndex < 0 || trimmedLine.length <= maxLength) return trimmedLine

    val context = maxLength / 2
    val sliceStart = max(0, startIndex - context)
    val sliceEnd = min(trimmedLine.length, sliceStart + maxLength)
    val prefix = if (sliceStart > 0) "…" else ""
    val suffix = if (sliceEnd < trimmedLine.length) "…" else ""
    return prefix + trimmedLine.substring(sliceStart, sliceEnd).trim() + suffix
}
