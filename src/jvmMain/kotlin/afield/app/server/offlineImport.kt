package afield.app.server

import afield.document.persistence.SavingContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks a document zip, as the docs pages ship them, into [targetDir], for
 * `/api/import-offline-doc`: `metadata.edn` together with its sidecars and every folder beside it
 * (saved chats, study revisions, recorded conversations), which an `ednText` import leaves behind.
 *
 * A zip whose entries all sit in one top-level folder (`function_conversation_example/...`, the
 * layout a download unzips to) has that folder removed, so the document lands in [targetDir]
 * itself. An entry that would resolve outside [targetDir] fails the import. Returns the number of
 * files written; fails when the zip holds no `metadata.edn`.
 */
fun unpackDocumentZip(zip: ByteArray, targetDir: File): Int {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(zip)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            if (!entry.isDirectory) entries += entry.name.replace('\\', '/') to input.readBytes()
        }
    }
    val roots = entries.map { it.first.substringBefore('/', "") }.toSet()
    val prefix = roots.singleOrNull()?.takeIf { it.isNotEmpty() }?.let { "$it/" } ?: ""
    val files = entries.map { (name, data) -> name.removePrefix(prefix) to data }
    require(files.any { it.first == SavingContext.METADATA_EDN }) { "the zip has no ${SavingContext.METADATA_EDN}" }

    val root = targetDir.canonicalFile
    for ((name, _) in files) {
        val target = File(root, name).canonicalFile
        require(target.toPath().startsWith(root.toPath()) && target != root) { "zip entry outside the document: $name" }
    }
    for ((name, data) in files) {
        val target = File(root, name)
        target.parentFile.mkdirs()
        target.writeBytes(data)
    }
    return files.size
}
