package afield.document.workspace

import afield.app.server.lastFn
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.server.isFieldAppRouteDocumentName
import afield.audio.synth.*
import afield.document.persistence.SavingContext
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.tools.docs.format
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Where Field remembers the document you had open, keyed by workspace rather than by checkout.
 *
 * This is the store behind [lastFn], which is what a window opened without `--document` falls back
 * to. The name is kept in a file named for the SHA-256 of the workspace's canonical path, under
 * `last-documents` in the machine-wide user config directory, so two workspaces opened from one
 * checkout remember different documents and one workspace opened from two checkouts remembers the
 * same one.
 *
 * Both directions are checked against the disk rather than trusted. A remembered name comes back
 * only while a document by that name is still there, a name is written only if it resolves to
 * something inside the workspace, and a name holding a path separator or naming one of the app's own
 * routes is refused at both ends. The write goes to a temporary file beside the target and is moved
 * into place, atomically where the filesystem supports it.
 */

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
