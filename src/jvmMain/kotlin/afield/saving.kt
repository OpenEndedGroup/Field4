@file:OptIn(ExperimentalSerializationApi::class)

package afield

import afield.logger.lprintln
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.io.FileNotFoundException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class Box_transfer(val id: String, val properties: Map<String, Pair<String, JsonElement>>)

data class BoxTransferEdn(val id: String, val properties: Map<String, Any?>)

private data class BoxRef(val id: String)
private data class FileRef(val path: String)
private data class FileInline(val path: String, val inline: String)

class SavingContext {

    fun exportEdnPackage(boxes: Set<Box>, directory: String): String {
        val transfers = toTransferInline(boxes, directory)
        val payloadBoxes = transfers.map { transfer ->
            linkedMapOf<String, Any?>(
                "id" to transfer.id,
                "properties" to transfer.properties
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "version" to 2,
            "boxes" to payloadBoxes,
            "persistentProperties" to Box.ioPersistentProperties.toList()
        )
        return Edn.write(payload, ednCustom)
    }

    fun listTemplates(dir: String): List<String> {
        val directory = File(dir)
        if (!directory.exists()) return emptyList()
        
        val files = directory.listFiles() ?: return emptyList()
        val result = mutableListOf<String>()
        
        // Add subdirectories containing metadata files
        files.filter { file ->
            file.isDirectory && (File(file, METADATA_EDN).exists() || File(file, METADATA_JSON).exists())
        }.mapTo(result) { it.name }
        
        // Add standalone metadata.edn files (but not the main metadata.edn)
        files.filter { file ->
            file.isFile && file.name.endsWith(".edn") && file.name != METADATA_EDN
        }.mapTo(result) { it.nameWithoutExtension }
        
        return result
    }

    @OptIn(ExperimentalUuidApi::class)
    fun cloneInto(dir: String): Set<Box> {
        val seen = mutableMapOf<String, String>("root" to "root")
        return load(dir, { id ->
            val next = seen.computeIfAbsent(id) { Uuid.random().toString() }
            next
        }, upgradeOnLoad = false)
    }

    var altered = false

    fun toTransfer(boxes: Set<Box>, directory: String): List<BoxTransferEdn> {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        return boxes.map { box ->
            val properties = LinkedHashMap<String, Any?>()
            box.properties.forEach { (name, value) ->
                if (!patterns.any { name.matches(it) }) return@forEach
                if (value == null) return@forEach

                if (Box.ioSeparateProperties.containsKey(name)) {
                    val extension = Box.ioSeparateProperties[name]!!
                    val contents = value as? String ?: value.toString()
                    properties[name] = toFile(box.id, name, extension, directory, contents)
                } else {
                    val normalized = normalizeValue(value)
                    if (normalized != null) {
                        properties[name] = normalized
                    }
                }
            }
            BoxTransferEdn(box.id, properties)
        }
    }

    private fun toTransferInline(boxes: Set<Box>, directory: String): List<BoxTransferEdn> {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        return boxes.map { box ->
            val properties = LinkedHashMap<String, Any?>()
            box.properties.forEach { (name, value) ->
                if (!patterns.any { name.matches(it) }) return@forEach
                if (value == null) return@forEach

                if (Box.ioSeparateProperties.containsKey(name)) {
                    val extension = Box.ioSeparateProperties[name]!!
                    val contents = value as? String ?: value.toString()
                    properties[name] = FileInline(filenameFor(box.id, name, extension), contents)
                } else {
                    val normalized = normalizeInlineValue(value, directory)
                    if (normalized != null) {
                        properties[name] = normalized
                    }
                }
            }
            BoxTransferEdn(box.id, properties)
        }
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

    private fun toFile(
        id: String,
        name: String,
        extension: String,
        directory: String,
        contents: String
    ): FileInline {
        val path = filenameFor(id, name, extension)
        val cd = checkDifference(File(directory, path).path, contents)
        altered = altered || cd
        return FileInline(path, contents)
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null -> null
        is Rect -> value
        is String -> value
        is Number -> value
        is Boolean -> value
        is Box -> BoxRef(value.id)
        is BoxRef -> value
        is FileRef -> value
        is FileInline -> value
        is List<*> -> value.map { normalizeValue(it) }
        is Set<*> -> value.mapTo(LinkedHashSet()) { normalizeValue(it) }
        is Map<*, *> -> {
            val mapped = LinkedHashMap<Any?, Any?>()
            for ((k, v) in value) {
                mapped[normalizeValue(k)] = normalizeValue(v)
            }
            mapped
        }
        else -> {
            lprintln(" unhandled class $value ${value.javaClass}")
            null
        }
    }

    private fun normalizeInlineValue(value: Any?, directory: String): Any? = when (value) {
        null -> null
        is Rect -> value
        is String -> value
        is Number -> value
        is Boolean -> value
        is Box -> BoxRef(value.id)
        is BoxRef -> value
        is FileRef -> {
            val file = File(directory, value.path)
            val inline = if (file.exists()) file.readText() else ""
            FileInline(value.path, inline)
        }
        is FileInline -> value
        is List<*> -> value.map { normalizeInlineValue(it, directory) }
        is Set<*> -> value.mapTo(LinkedHashSet()) { normalizeInlineValue(it, directory) }
        is Map<*, *> -> {
            val mapped = LinkedHashMap<Any?, Any?>()
            for ((k, v) in value) {
                mapped[normalizeInlineValue(k, directory)] = normalizeInlineValue(v, directory)
            }
            mapped
        }
        else -> {
            lprintln(" unhandled class $value ${value.javaClass}")
            null
        }
    }

    fun save(fn: String, boxes: Set<Box>, message: String? = null) {
        altered = false
        val root = ensureDirectory(fn)
        ensureRepository(fn)

        val transfers = toTransfer(boxes, root)
        val payloadBoxes = transfers.map { transfer ->
            linkedMapOf<String, Any?>(
                "id" to transfer.id,
                "properties" to transfer.properties
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "version" to 2,
            "boxes" to payloadBoxes,
            "persistentProperties" to Box.ioPersistentProperties.toList()
        )
        val ednText = Edn.write(payload, ednCustom)
        val cd = checkDifference(File(root, METADATA_EDN).path, ednText)
        altered = altered || cd

        if (altered) {
            commit(fn, message)
            notify("saved file at <<timeago:${System.currentTimeMillis()}>>", "*saved")
            
            // Mark all saved sidecar files as internal saves
            try {
                thisSessionInfo.get()?.fileWatchManager?.let { watchManager ->
                    File(root).listFiles()?.forEach { file ->
                        if (file.isFile && file.name.contains("_") && 
                            Box.ioSeparateProperties.values.contains(file.extension)) {
                            watchManager.markInternalSave(file)
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently ignore if session info is not available
            }
        }
    }

    /**
     * Save boxes as a standalone template file (metadata.edn without subdirectory)
     */
    fun saveAsTemplate(templateName: String, boxes: Set<Box>, templatesDir: String) {
        val ednText = exportEdnPackage(boxes, templatesDir)
        val templateFile = File(templatesDir, "$templateName.edn")
        templateFile.writeText(ednText)
    }


    fun load(
        fn: String,
        filterId: (String) -> String = { it },
        upgradeOnLoad: Boolean = true
    ): Set<Box> {
        val file = File(fn)
        
        // Check if fn points to a standalone .edn file
        if (file.isFile && file.name.endsWith(".edn")) {
            val ednText = file.readText()
            val directory = file.absoluteFile.parentFile?.absolutePath ?: file.absolutePath
            return loadFromEdnText(ednText, directory, filterId)
        }
        
        // Otherwise, treat as directory
        val root = ensureDirectory(fn)
        val ednFile = File(root, METADATA_EDN)
        if (ednFile.exists()) {
            return loadEdn(root, filterId)
        }

        val jsonFile = File(root, METADATA_JSON)
        if (!jsonFile.exists()) {
            throw FileNotFoundException("missing $METADATA_EDN or $METADATA_JSON in $root")
        }

        val boxes = loadJson(root, filterId)
        if (upgradeOnLoad) {
            try {
                save(root, boxes, "upgrade to edn")
            } catch (ex: Exception) {
                lprintln(" error upgrading $root to EDN: ${ex.message}")
            }
        }
        return boxes
    }

    fun resolveStoredValue(value: Any?, directory: String): Any? {
        return resolveValue(value, directory, { it }, createBoxes = false)
    }

    /**
     * Merges persistent properties from a document with the current global set.
     * This ensures that all properties that were previously saved in a document
     * continue to be saved, even if they're not in the current global set.
     *
     * @param rootValue The root EDN value from metadata.edn (should be a Map)
     */
    private fun mergePersistentProperties(rootValue: Map<*, *>) {
        val savedProps = rootValue["persistentProperties"]
        if (savedProps != null) {
            val propsList = when (savedProps) {
                is List<*> -> savedProps.mapNotNull { it?.toString() }
                is Set<*> -> savedProps.mapNotNull { it?.toString() }
                else -> emptyList()
            }
            // Merge the saved properties with the current global set
            // Note: This modifies global state, which is intentional to ensure
            // all loaded documents' properties are preserved across saves
            Box.ioPersistentProperties.addAll(propsList)
        }
    }

    fun readEdnTransfers(directory: String): List<BoxTransferEdn> {
        val rootValue = Edn.read(File(directory, METADATA_EDN).readText(), ednCustom)
        val boxesValue = when (rootValue) {
            is Map<*, *> -> {
                // Merge persistent properties from the document with the current global set
                mergePersistentProperties(rootValue)
                rootValue["boxes"]
            }
            is List<*> -> rootValue
            is Set<*> -> rootValue.toList()
            else -> throw IllegalArgumentException("expected EDN map or list in ${File(directory, METADATA_EDN).path}")
        }
        val boxList = when (boxesValue) {
            null -> emptyList()
            is List<*> -> boxesValue
            is Set<*> -> boxesValue.toList()
            else -> throw IllegalArgumentException("expected boxes list in ${File(directory, METADATA_EDN).path}")
        }

        return boxList.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString() ?: return@mapNotNull null
            val propsRaw = map["properties"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val props = LinkedHashMap<String, Any?>()
            for ((k, v) in propsRaw) {
                val key = k?.toString() ?: continue
                props[key] = v
            }
            BoxTransferEdn(id, props)
        }
    }

    fun readEdnTransfersFromString(ednText: String): List<BoxTransferEdn> {
        val rootValue = Edn.read(ednText, ednCustom)
        val boxesValue = when (rootValue) {
            is Map<*, *> -> {
                // Merge persistent properties from the document with the current global set
                mergePersistentProperties(rootValue)
                rootValue["boxes"]
            }
            is List<*> -> rootValue
            is Set<*> -> rootValue.toList()
            else -> throw IllegalArgumentException("expected EDN map or list in provided text")
        }
        val boxList = when (boxesValue) {
            null -> emptyList()
            is List<*> -> boxesValue
            is Set<*> -> boxesValue.toList()
            else -> throw IllegalArgumentException("expected boxes list in provided text")
        }

        return boxList.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString() ?: return@mapNotNull null
            val propsRaw = map["properties"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val props = LinkedHashMap<String, Any?>()
            for ((k, v) in propsRaw) {
                val key = k?.toString() ?: continue
                props[key] = v
            }
            BoxTransferEdn(id, props)
        }
    }

    private fun resolveValue(
        value: Any?,
        directory: String,
        filterId: (String) -> String,
        createBoxes: Boolean
    ): Any? = when (value) {
        null -> null
        is FileRef -> File(directory, value.path).readText()
        is FileInline -> {
            val file = File(directory, value.path)
            if (file.exists()) file.readText() else value.inline
        }
        is BoxRef -> {
            if (!createBoxes) {
                value
            } else {
                val sourceId = value.id
                val id = filterId(sourceId)
                val v = box(id, createIfMissing = true)
                if (id != sourceId) {
                    v.setPropertyRaw("source_id", sourceId)
                }
                v
            }
        }
        is List<*> -> value.map { resolveValue(it, directory, filterId, createBoxes) }.toMutableList()
        is Set<*> -> value.mapTo(LinkedHashSet()) { resolveValue(it, directory, filterId, createBoxes) }
        is Map<*, *> -> {
            val mapped = LinkedHashMap<Any?, Any?>()
            for ((k, v) in value) {
                mapped[resolveValue(k, directory, filterId, createBoxes)] =
                    resolveValue(v, directory, filterId, createBoxes)
            }
            mapped
        }
        is EdnValue -> {
            lprintln(" can't decode $value")
            null
        }
        else -> value
    }

    private fun loadEdn(root: String, filterId: (String) -> String): Set<Box> {
        val transfers = readEdnTransfers(root)
        return instantiateTransfers(transfers, root, filterId, createBoxes = true)
    }

    private fun instantiateTransfers(
        transfers: List<BoxTransferEdn>,
        root: String,
        filterId: (String) -> String,
        createBoxes: Boolean
    ): Set<Box> {
        return transfers.map { transfer ->
            val id = filterId(transfer.id)
            val b = box(id, createIfMissing = createBoxes)
            transfer.properties.forEach { (name, value) ->
                val v = resolveValue(value, root, filterId, createBoxes = createBoxes)
                if (v != null) {
                    b.setPropertyRaw(name, v)
                }
            }
            if (id != transfer.id) {
                b.setPropertyRaw("source_id", transfer.id)
            }
            b
        }.toSet()
    }

    fun loadFromEdnText(
        ednText: String,
        directory: String,
        filterId: (String) -> String = { it }
    ): Set<Box> {
        val transfers = readEdnTransfersFromString(ednText)
        return instantiateTransfers(transfers, directory, filterId, createBoxes = true)
    }

    private fun loadJson(root: String, filterId: (String) -> String): Set<Box> {
        val transfers = format.decodeFromString<Set<Box_transfer>>(File(root, METADATA_JSON).readText())
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
        filterId: (String) -> String = { it },
        createBoxes: Boolean = true
    ): Any? = when (element.first) {
        "__file__" -> File(directory, format.decodeFromJsonElement<String>(element.second)).readText()
        "Rect" -> format.decodeFromJsonElement<Rect>(element.second) as Any
        "String" -> format.decodeFromJsonElement<String>(element.second) as Any
        "Int" -> format.decodeFromJsonElement<Int>(element.second) as Any
        "Number" -> format.decodeFromJsonElement<Number>(element.second) as Any
        "Boolean" -> format.decodeFromJsonElement<Boolean>(element.second) as Any
        "Box" -> {
            val sourceId = format.decodeFromJsonElement<String>(element.second)
            if (!createBoxes) {
                BoxRef(sourceId)
            } else {
                val id = filterId(sourceId)
                val v = box(id, createIfMissing = true)
                if (id != sourceId) {
                    v.setPropertyRaw("source_id", sourceId)
                }
                v
            }
        }
        "List" -> format.decodeFromJsonElement<List<Pair<String, JsonElement>>>(element.second).map {
            fromTypedPair(it, directory, filterId, createBoxes)
        }.toMutableList() as Any
        "Set" -> format.decodeFromJsonElement<List<Pair<String, JsonElement>>>(element.second).map {
            fromTypedPair(it, directory, filterId, createBoxes)
        }.toMutableSet() as Any
        else -> {
            lprintln(" can't decode $element")
            null
        }
    }

    companion object {
        val format = Json {
            prettyPrint = true
        }

        const val METADATA_EDN = "metadata.edn"
        const val METADATA_JSON = "metadata.json"

        private val ednCustom = Edn.CustomTypes().apply {
            registerEdn(Rect::class, "afield/rect", { rect ->
                EdnValue.VectorVal(
                    listOf(
                        EdnValue.Number(rect.x.toString()),
                        EdnValue.Number(rect.y.toString()),
                        EdnValue.Number(rect.w.toString()),
                        EdnValue.Number(rect.h.toString())
                    )
                )
            }, { value ->
                val vec = value as? EdnValue.VectorVal
                    ?: throw IllegalArgumentException("expected vector for Rect, got $value")
                val items = vec.values
                if (items.size != 4) {
                    throw IllegalArgumentException("expected 4 values for Rect, got ${items.size}")
                }
                Rect(
                    ednValueToDouble(items[0]),
                    ednValueToDouble(items[1]),
                    ednValueToDouble(items[2]),
                    ednValueToDouble(items[3])
                )
            })

            registerStringPayload(BoxRef::class, "afield/box", { it.id }, { BoxRef(it) })
            registerEdn(FileInline::class, "afield/file-inline", { file ->
                EdnValue.MapVal(
                    linkedMapOf(
                        EdnValue.Str("path") to EdnValue.Str(file.path),
                        EdnValue.Str("inline") to EdnValue.Str(file.inline)
                    )
                )
            }, { value ->
                val map = value as? EdnValue.MapVal
                    ?: throw IllegalArgumentException("expected map for FileInline, got $value")
                val pathValue = map.values[EdnValue.Str("path")] as? EdnValue.Str
                val inlineValue = map.values[EdnValue.Str("inline")] as? EdnValue.Str
                if (pathValue == null || inlineValue == null) {
                    throw IllegalArgumentException("missing path/inline for FileInline: $value")
                }
                FileInline(pathValue.value, inlineValue.value)
            })
            registerStringPayload(FileRef::class, "afield/file", { it.path }, { FileRef(it) })
        }

        fun filenameFor(id: String, name: String, extension: String): String {
            return id + "_" + name + "_." + extension
        }

        private fun ednValueToDouble(value: EdnValue): Double = when (value) {
            is EdnValue.Number -> value.token.toDouble()
            is EdnValue.Str -> value.value.toDouble()
            else -> throw IllegalArgumentException("expected number, got $value")
        }
    }

    fun ensureDirectory(fn: String): String {
        File(fn).mkdirs()
        return fn
    }
}


/**
 * Safely saves text to a file, creating backups of previous versions.
 * 
 * Changed from "backup" to "backups" directory to match the requirement
 * that previous metadata.edn files should be stored in backups/ directory.
 * 
 * @param text The text content to save
 * @return true if the file was modified, false if content was unchanged
 */
fun File.safeSave(text: String): Boolean {
    val file = this
    if (file.exists()) {
        // Read current file content
        val currentContent = file.readText()
        if (currentContent != text) {
            // Only create the backup directory when a backup is needed
            val backupDir = File(file.parentFile, "backups")
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
