@file:afield.CompletionCategory("Workspace")

package afield.graphics

import afield.Box
import afield.ReadOnlyDocuments
import afield.Runloop
import afield.registerStopHook
import afield.thisSessionInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Give a custom canvas the same persistent annotation inbox as a Three.js viewer.
 *
 * [name] identifies this canvas on the box; [revision] identifies its authored geometry. The canvas
 * supplies a selection, saved view and optional actual context/crop PNGs in the ordinary geometry
 * review format. It owns hit testing and semantic feature identities. AI4's geometry review tools
 * discover this inbox and can read, reply and resolve notes, including their historical pictures.
 * [outputBox] identifies the box whose output can be captured (defaults to this source box).
 * Re-running replaces the binding, while notes remain on the box. Stop/delete unregisters it.
 */
fun Box.geometryReviewSurface(name: String, revision: String, outputBox: Box = this): GeometryReviewSurface =
    bindGeometryReviewSurface(this, name, revision, outputBox) { action ->
        val invoke = {
            val previous = thisSessionInfo.get()
            thisSessionInfo.set(inside)
            try { action() } finally { thisSessionInfo.set(previous) }
        }
        if (Runloop.isMainThread) invoke()
        else runBlocking { withContext(Runloop.runLoop.main) { invoke() } }
    }

/** A custom canvas's review binding; writes dispatch to its Field document's owner thread. */
class GeometryReviewSurface internal constructor(
    internal val owner: Box, val name: String, val uid: String, internal val outputBox: Box,
    private val onOwner: (() -> JsonObject) -> JsonObject,
) : AutoCloseable {
    internal val reviewInbox = ThreeReviewInbox(uid).also { it.owner = owner }
    private val closed = AtomicBoolean(false)
    internal var stopHook: Runnable? = null

    /** Invalidate the current selection when the authored geometry changes; retain saved notes. */
    fun updateRevision(revision: String) {
        check(!closed.get()) { "Review surface is closed" }
        require(revision.isNotBlank() && revision.length <= 256) { "Supply a bounded geometry revision" }
        reviewInbox.update(revision)
    }

    /** Read notes without acknowledging them or changing the human selection. */
    fun reviews(includeResolved: Boolean = false): JsonObject = onOwner {
        requireLive(); reviewInbox.snapshot(includeResolved)
    }

    /** Receive a revision-labelled selection or save a note with its optional context/crop evidence. */
    fun receive(record: JsonObject, saveNote: Boolean = true): JsonObject = onOwner {
        requireLive()
        check(!ReadOnlyDocuments.blocksWritesFrom(owner)) { "This document is read-only" }
        reviewInbox.receive(record, saveNote)
    }

    /** Append a reply or explicitly resolve/reopen, with the current revision and note version. */
    fun updateNote(request: JsonObject): JsonObject = onOwner {
        requireLive()
        check(!ReadOnlyDocuments.blocksWritesFrom(owner)) { "This document is read-only" }
        reviewInbox.updateNote(request)
    }

    private fun requireLive() {
        check(!closed.get() && (owner.inside.boxes[owner.id] === owner || owner.inside.root === owner)) {
            "The review surface's source box is no longer live"
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        customGeometryReviewSurfaces.remove(uid, this)
        val key = "_geometryReviewSurface:$name"
        if (owner.properties[key] === this) owner.properties.remove(key)
        stopHook?.run(); stopHook = null
    }
}

private val customGeometryReviewSurfaces = ConcurrentHashMap<String, GeometryReviewSurface>()

internal fun bindGeometryReviewSurface(owner: Box, name: String, revision: String, outputBox: Box = owner,
    onOwner: (() -> JsonObject) -> JsonObject): GeometryReviewSurface {
    require(name.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Review surface name must be a simple word" }
    require(revision.isNotBlank() && revision.length <= 256) { "Supply a bounded geometry revision" }
    check(!ReadOnlyDocuments.blocksWritesFrom(owner)) { "This document is read-only" }
    require(outputBox.inside === owner.inside) { "The canvas output box must belong to the same document" }
    val key = "_geometryReviewSurface:$name"
    val identity = "${File(owner.inside.filename).canonicalPath}\u0000${owner.id}\u0000$name"
    val uid = "review-" + UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))
    (owner.properties[key] as? GeometryReviewSurface)?.close()
    val binding = GeometryReviewSurface(owner, name, uid, outputBox, onOwner)
    binding.updateRevision(revision)
    check(customGeometryReviewSurfaces.putIfAbsent(uid, binding) == null) { "This review surface is already bound in another live session" }
    owner.properties[key] = binding
    binding.stopHook = owner.registerStopHook(Runnable { binding.close() })
    return binding
}

internal fun geometryReviewSurfacesOwnedBy(boxes: Collection<Box>): List<GeometryReviewSurface> =
    customGeometryReviewSurfaces.values.filter { view -> boxes.any { it === view.owner } }.sortedBy { it.uid }

internal fun customGeometryReviewInbox(uid: String): ThreeReviewInbox? = customGeometryReviewSurfaces[uid]
    ?.takeIf { it.owner.inside.boxes[it.owner.id] === it.owner || it.owner.inside.root === it.owner }?.reviewInbox
