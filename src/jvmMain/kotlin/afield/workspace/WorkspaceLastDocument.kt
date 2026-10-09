package afield

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Remembers a local document per canonical workspace, independently of the launching checkout. */
internal class WorkspaceLastDocument(workspace: File, stateDirectory: File) {
    private val workspace = workspace.canonicalFile
    private val key = MessageDigest.getInstance("SHA-256")
        .digest(this.workspace.path.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val stateFile = File(stateDirectory, "$key.txt")

    // The old checkout-wide lastFn has no workspace identity, so it cannot be migrated safely.
    fun read(): String? {
        val name = stateFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim() ?: return null
        val document = localDocument(name) ?: return null
        return name.takeIf { SavingContext.documentExistsOnDisk(document.path) }
    }

    fun remember(name: String?) {
        val clean = name?.trim() ?: return
        if (localDocument(clean) == null) return
        stateFile.parentFile.mkdirs()
        val temporary = Files.createTempFile(stateFile.parentFile.toPath(), key, ".tmp")
        try {
            Files.writeString(temporary, clean, Charsets.UTF_8)
            try {
                Files.move(temporary, stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun localDocument(name: String): File? {
        if (name.isBlank() || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it == '\n' || it == '\r' } ||
            isFieldAppRouteDocumentName(name)
        ) return null
        return File(workspace, name)
    }
}
