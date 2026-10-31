package afield.document.workspace

import afield.ai.conversation.*
import afield.ai.models.*
import afield.graphics.render.*
import afield.document.persistence.BoxTransferEdn
import afield.document.persistence.Box_transfer
import afield.document.persistence.SavingContext
import afield.data.collections.isTrue
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A read-only view of box text in saved documents as addressable files, so tools that expect files can read
 * a box's code or shaders without opening Field. [AfieldVirtualFileSystem] names each text property
 * `afield://<document>/<uid>/<property>`, lists them from a workspace directory (an `afield:` URI whose path
 * is that directory) and reads them back from `metadata.edn` or `metadata.json`, resolving sidecar files
 * and skipping tombstoned boxes.
 *
 * Only properties in [AfieldVirtualFileSystem.DEFAULT_PROPERTIES] are exposed by default: `code` and the
 * GLSL and Slang shader stages, each with a language label. [collectVirtualFiles] combines listing roots
 * and reading explicit URIs across several [VirtualFileSystem]s, dispatched by URI scheme; the AI4
 * filestore CLI in `afield.ai.knowledge` uses it to upload document text.
 */

/** One addressable file: its `afield:` [uri], a display [fileName] and a [language] label. */
data class VirtualFileRef(
    /** The address to read this file back by, which for [AfieldVirtualFileSystem] is `afield://<document>/<uid>/<property>`. */
    val uri: String,
    /** A name to show. [AfieldVirtualFileSystem] puts the whole URI here, since a box property has no filename. */
    val fileName: String,
    /**
     * What the text is written in, as a label for a reader rather than a parser: `Kotlin`, or a stage such as
     * `GLSL (fragment)` or `Slang (compute)`. Falls back to the property name for a property with no label.
     */
    val language: String
)

/** One virtual file, read whole. */
data class VirtualTextFile(
    /** Where this text came from, carried along so a consumer can attribute or re-read it. */
    val ref: VirtualFileRef,
    /** The property's text, exactly as the document stores it. */
    val content: String
)

/** A source of addressable read-only text files, chosen by the scheme of the URI being asked about. */
interface VirtualFileSystem {
    /** The URI scheme this file system answers for, matched without regard to case. */
    val scheme: String

    /** Every file under [root], which is a scheme-specific place to look rather than a single file. */
    fun list(root: URI): List<VirtualFileRef>

    /** The text at [uri], or null when it names nothing this file system can produce. */
    fun read(uri: URI): VirtualTextFile?
}

/**
 * The `afield:` file system, which reads box text straight out of saved documents on disk without a running
 * Field. The two halves take their scope from different places: `list` reads whichever workspace directory
 * the root URI names, while `read` resolves a document name against the roots this was constructed with.
 */
class AfieldVirtualFileSystem(
    private val roots: List<Path>,
    private val properties: Set<String> = DEFAULT_PROPERTIES,
    private val languageByProperty: Map<String, String> = DEFAULT_LANGUAGE_BY_PROPERTY
) : VirtualFileSystem {
    /** `afield`, the scheme of every URI this reads and every one it hands back. */
    override val scheme: String = "afield"

    /**
     * Every exposed text property of every saved document directly under [root], whose path (or host) names
     * a workspace directory. Reads `metadata.edn`, or `metadata.json` when there is no EDN, and leaves out
     * tombstoned boxes. A root that is not a directory lists nothing rather than failing, and the listing is
     * not limited to the constructor's roots, which govern [read] alone.
     */
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

    /**
     * The text of one box property, found by looking for the document name under each of the constructor's
     * roots in turn and taking the first that holds a document. Sidecar-backed properties are resolved to
     * their contents. Null throughout for anything it cannot produce: another scheme, too few path segments,
     * a property outside the exposed set, an unknown document or box, a tombstoned box, and a property whose
     * stored value is not text.
     */
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
            "vertex_fragment",
            "mesh",
            "task",
            "compute",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "vertex_s",
            "fragment_s",
            "mesh_s",
            "task_s",
            "compute_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s"
        )

        val DEFAULT_LANGUAGE_BY_PROPERTY: Map<String, String> = mapOf(
            "code" to "Kotlin",
            "vertex" to "GLSL (vertex)",
            "fragment" to "GLSL (fragment)",
            "vertex_fragment" to "Slang (vertex+fragment)",
            "mesh" to "GLSL (mesh)",
            "task" to "GLSL (task)",
            "compute" to "GLSL (compute)",
            "raygen" to "GLSL (ray generation)",
            "miss" to "GLSL (miss)",
            "closesthit" to "GLSL (closest hit)",
            "anyhit" to "GLSL (any hit)",
            "intersection" to "GLSL (intersection)",
            "callable" to "GLSL (callable)",
            "vertex_s" to "Slang (vertex)",
            "fragment_s" to "Slang (fragment)",
            "mesh_s" to "Slang (mesh)",
            "task_s" to "Slang (task)",
            "compute_s" to "Slang (compute)",
            "raygen_s" to "Slang (ray generation)",
            "miss_s" to "Slang (miss)",
            "closesthit_s" to "Slang (closest hit)",
            "anyhit_s" to "Slang (any hit)",
            "intersection_s" to "Slang (intersection)",
            "callable_s" to "Slang (callable)"
        )
    }
}

/**
 * Lists every root against whichever of [fileSystems] claims its scheme, concatenated in the order the roots
 * are given. A root with no scheme, or one no file system claims, is skipped without comment.
 */
fun listVirtualFileRefs(roots: List<URI>, fileSystems: List<VirtualFileSystem>): List<VirtualFileRef> {
    val out = mutableListOf<VirtualFileRef>()
    roots.forEach { root ->
        val scheme = root.scheme ?: return@forEach
        val fs = fileSystems.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) } ?: return@forEach
        out += fs.list(root)
    }
    return out
}

/**
 * Reads each URI through whichever of [fileSystems] claims its scheme, keeping the ones that produced text.
 * A URI that resolves to nothing is dropped, so the result may be shorter than [uris] and a caller that
 * needs to know which failed has to compare them.
 */
fun resolveVirtualFiles(uris: List<URI>, fileSystems: List<VirtualFileSystem>): List<VirtualTextFile> {
    val out = mutableListOf<VirtualTextFile>()
    uris.forEach { uri ->
        val scheme = uri.scheme ?: return@forEach
        val fs = fileSystems.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) } ?: return@forEach
        fs.read(uri)?.let { out += it }
    }
    return out
}

/**
 * Reads everything the [roots] list and everything [uris] names, as one set of files with duplicate URIs
 * collapsed. The explicit URIs come first, so an address given both ways is read once. This is the way in
 * for a caller that wants document text without caring which file system produced it.
 */
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
