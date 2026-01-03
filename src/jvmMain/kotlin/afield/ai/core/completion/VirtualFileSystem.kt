package afield.ai

import afield.BoxTransferEdn
import afield.Box_transfer
import afield.SavingContext
import afield.stdlib.isTrue
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

data class VirtualFileRef(
    val uri: String,
    val fileName: String,
    val language: String
)

data class VirtualTextFile(
    val ref: VirtualFileRef,
    val content: String
)

interface VirtualFileSystem {
    val scheme: String
    fun list(root: URI): List<VirtualFileRef>
    fun read(uri: URI): VirtualTextFile?
}

class AfieldVirtualFileSystem(
    private val roots: List<Path>,
    private val properties: Set<String> = DEFAULT_PROPERTIES,
    private val languageByProperty: Map<String, String> = DEFAULT_LANGUAGE_BY_PROPERTY
) : VirtualFileSystem {
    override val scheme: String = "afield"

    override fun list(root: URI): List<VirtualFileRef> {
        val rootPath = rootPath(root) ?: return emptyList()
        val rootDir = rootPath.toFile()
        if (!rootDir.isDirectory) return emptyList()

        val ctx = SavingContext()
        val refs = mutableListOf<VirtualFileRef>()

        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { docDir ->
            val metadataEdn = File(docDir, SavingContext.METADATA_EDN)
            val metadataJson = File(docDir, SavingContext.METADATA_JSON)
            if (!metadataEdn.exists() && !metadataJson.exists()) return@forEach

            val docName = docDir.name
            if (metadataEdn.exists()) {
                val transfers = ctx.readEdnTransfers(docDir.absolutePath)
                    .filter { !it.properties["_tombstone"].isTrue() }
                addRefsFromEdn(refs, docName, transfers)
            } else {
                val transfers = SavingContext.format
                    .decodeFromString<Set<Box_transfer>>(metadataJson.readText())
                    .filter { !it.properties["_tombstone"].isTrue() }
                addRefsFromJson(refs, docName, transfers)
            }
        }

        return refs
    }

    override fun read(uri: URI): VirtualTextFile? {
        val ref = parseFileRef(uri) ?: return null
        if (!properties.contains(ref.property)) return null

        val docDir = resolveDocDir(ref.document) ?: return null
        val ctx = SavingContext()
        val metadataEdn = File(docDir, SavingContext.METADATA_EDN)
        val metadataJson = File(docDir, SavingContext.METADATA_JSON)

        if (metadataEdn.exists()) {
            val transfers = ctx.readEdnTransfers(docDir.absolutePath)
            val transfer = transfers.firstOrNull { it.id == ref.uid } ?: return null
            if (transfer.properties["_tombstone"].isTrue()) return null
            val value = transfer.properties[ref.property] ?: return null
            val resolved = ctx.resolveStoredValue(value, docDir.absolutePath) ?: return null
            val text = resolved as? String ?: return null
            return VirtualTextFile(makeRef(ref), text)
        }

        if (metadataJson.exists()) {
            val transfers = SavingContext.format
                .decodeFromString<Set<Box_transfer>>(metadataJson.readText())
            val transfer = transfers.firstOrNull { it.id == ref.uid } ?: return null
            if (transfer.properties["_tombstone"].isTrue()) return null
            val value = transfer.properties[ref.property] ?: return null
            val resolved = ctx.fromTypedPair(value, docDir.absolutePath, createBoxes = false) ?: return null
            val text = resolved as? String ?: return null
            return VirtualTextFile(makeRef(ref), text)
        }

        return null
    }

    private fun addRefsFromEdn(
        refs: MutableList<VirtualFileRef>,
        docName: String,
        transfers: List<BoxTransferEdn>
    ) {
        transfers.forEach { transfer ->
            properties.forEach { property ->
                if (!transfer.properties.containsKey(property)) return@forEach
                refs += makeRef(FileRef(docName, transfer.id, property))
            }
        }
    }

    private fun addRefsFromJson(
        refs: MutableList<VirtualFileRef>,
        docName: String,
        transfers: List<Box_transfer>
    ) {
        transfers.forEach { transfer ->
            properties.forEach { property ->
                if (!transfer.properties.containsKey(property)) return@forEach
                refs += makeRef(FileRef(docName, transfer.id, property))
            }
        }
    }

    private fun makeRef(ref: FileRef): VirtualFileRef {
        val uri = "afield://${ref.document}/${ref.uid}/${ref.property}"
        val language = languageByProperty[ref.property] ?: ref.property
        return VirtualFileRef(uri = uri, fileName = uri, language = language)
    }

    private fun resolveDocDir(docName: String): File? {
        for (root in roots) {
            val candidate = root.resolve(docName).toFile()
            if (!candidate.isDirectory) continue
            val hasMetadata = File(candidate, SavingContext.METADATA_EDN).exists() ||
                File(candidate, SavingContext.METADATA_JSON).exists()
            if (hasMetadata) return candidate
        }
        return null
    }

    private data class FileRef(val document: String, val uid: String, val property: String)

    private fun parseFileRef(uri: URI): FileRef? {
        if (!scheme.equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host
        val pathParts = uri.path?.trimStart('/')?.split("/")?.filter { it.isNotBlank() }.orEmpty()
        val parts = if (!host.isNullOrBlank()) listOf(host) + pathParts else pathParts
        if (parts.size < 3) return null
        return FileRef(parts[0], parts[1], parts[2])
    }

    private fun rootPath(uri: URI): Path? {
        val raw = uri.path?.takeIf { it.isNotBlank() } ?: uri.host?.takeIf { it.isNotBlank() }
        if (raw.isNullOrBlank()) return null
        return Paths.get(raw)
    }

    companion object {
        val DEFAULT_PROPERTIES: Set<String> = setOf(
            "code",
            "vertex",
            "fragment",
            "mesh",
            "task",
            "compute"
        )

        val DEFAULT_LANGUAGE_BY_PROPERTY: Map<String, String> = mapOf(
            "code" to "Kotlin",
            "vertex" to "GLSL (vertex)",
            "fragment" to "GLSL (fragment)",
            "mesh" to "GLSL (mesh)",
            "task" to "GLSL (task)",
            "compute" to "GLSL (compute)"
        )
    }
}

fun listVirtualFileRefs(roots: List<URI>, fileSystems: List<VirtualFileSystem>): List<VirtualFileRef> {
    val out = mutableListOf<VirtualFileRef>()
    roots.forEach { root ->
        val scheme = root.scheme ?: return@forEach
        val fs = fileSystems.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) } ?: return@forEach
        out += fs.list(root)
    }
    return out
}

fun resolveVirtualFiles(uris: List<URI>, fileSystems: List<VirtualFileSystem>): List<VirtualTextFile> {
    val out = mutableListOf<VirtualTextFile>()
    uris.forEach { uri ->
        val scheme = uri.scheme ?: return@forEach
        val fs = fileSystems.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) } ?: return@forEach
        fs.read(uri)?.let { out += it }
    }
    return out
}

fun collectVirtualFiles(
    roots: List<URI>,
    uris: List<URI>,
    fileSystems: List<VirtualFileSystem>
): List<VirtualTextFile> {
    val rootRefs = listVirtualFileRefs(roots, fileSystems)
    val rootUris = rootRefs.mapNotNull { runCatching { URI(it.uri) }.getOrNull() }
    val allUris = (uris + rootUris).distinctBy { it.toString() }
    return resolveVirtualFiles(allUris, fileSystems)
}
