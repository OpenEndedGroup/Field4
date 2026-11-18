@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.drawingon

import afield.document.graph.Box
import afield.geometry.path.FLine
import afield.graphics.three.buildThreeGeometryPayload
import afield.graphics.three.splitForWire
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.inMain
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal val drawingOnJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Orthographic drawing plane. The default uses Field X/Y directly as iPad drawing points, with Y down. */
data class DrawingOnPlane(
    val origin: List<Double> = listOf(0.0, 0.0, 0.0),
    val xAxis: List<Double> = listOf(1.0, 0.0, 0.0),
    val yAxis: List<Double> = listOf(0.0, 1.0, 0.0),
    val scale: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0
) {
    internal fun json(): JsonObject {
        require(listOf(origin, xAxis, yAxis).all { it.size == 3 && it.all(Double::isFinite) })
        require(scale.isFinite() && scale > 0 && offsetX.isFinite() && offsetY.isFinite())
        val cross = listOf(xAxis[1]*yAxis[2]-xAxis[2]*yAxis[1], xAxis[2]*yAxis[0]-xAxis[0]*yAxis[2], xAxis[0]*yAxis[1]-xAxis[1]*yAxis[0])
        require(cross.sumOf { it * it } > 1e-18) { "Drawing plane axes must be independent" }
        return buildJsonObject {
            put("origin", JsonArray(origin.map(::JsonPrimitive))); put("xAxis", JsonArray(xAxis.map(::JsonPrimitive)))
            put("yAxis", JsonArray(yAxis.map(::JsonPrimitive))); put("scale", scale); put("offsetX", offsetX); put("offsetY", offsetY)
        }
    }
}

/** A committed capture event; [upload] distinguishes historical imports from a live gesture. */
data class DrawingOnEvent(val session: String, val upload: Boolean, val event: JsonObject) {
    val type: String get() = event.getValue("type").jsonPrimitive.content
    val time: Double get() = event.getValue("t").jsonPrimitive.double
    val data: JsonObject get() = event.getValue("data").jsonObject
}

internal data class DrawingOnReference(val generation: String, val message: JsonObject, val bytes: ByteArray)

/**
 * One explicitly named drawing head. [publish] uses the AVP FLine geometry/buffer wire; [onEvent]
 * receives committed Pencil, layer, view and audio events on Field's main Runloop.
 *
 * Reference geometry is retained at its latest revision across reconnects. A different box cannot
 * silently take over the same target. Re-running the owning box can replace its callback in place.
 */
class DrawingOnTarget internal constructor(val name: String, internal val owner: String, internal val ownerBox: Box? = null) {
    @Volatile private var callback: ((DrawingOnEvent) -> Unit)? = null
    @Volatile internal var reference: DrawingOnReference? = null
    internal val peers = ConcurrentHashMap.newKeySet<Channel<DrawingOnReference>>()

    fun onEvent(callback: (DrawingOnEvent) -> Unit) { this.callback = callback }

    internal fun retire() {
        callback = null; reference = null
        // Sockets own their subscriptions. Keep them open so a new owner can publish this name later.
        peers.clear()
    }

    /** Replace this head's Field reference layer, in drawing-plane coordinates. An empty list clears it. */
    fun publish(lines: Collection<FLine>, title: String = "Field · $name", plane: DrawingOnPlane = DrawingOnPlane(), samplesPerCubic: Int = 32) {
        check(DrawingOnServices.targets[name] === this) { "Drawing target '$name' has been retired" }
        val projection = plane.json()
        val generation = UUID.randomUUID().toString()
        val payload = buildThreeGeometryPayload("drawingon:$name", lines, samplesPerCubic, autoFrame = false, editable = false)
            .copy(sourceBox = owner, revision = generation)
        val split = payload.splitForWire(generation)
        val bytes = ByteArrayOutputStream().also { split.buffers?.writeTo(it) }.toByteArray()
        require(bytes.size <= 32 * 1024 * 1024) { "Drawing On reference geometry exceeds 32 MiB" }
        val message = buildJsonObject {
            put("type", "drawingon/reference"); put("target", name); put("title", title.take(200)); put("plane", projection)
            put("geometry", drawingOnJson.encodeToJsonElement(split.description))
            put("sha256", sha256(bytes))
        }
        val next = DrawingOnReference(generation, message, bytes)
        reference = next
        afield.app.server.DrawingOnPeers.attachPublisher(this)
        peers.forEach { it.trySend(next) }
    }

    /** Saved sessions bound to this target, including uploaded captures. */
    val sessions: List<String> get() = DrawingOnServices.archive?.sessions(name) ?: emptyList()

    /** Stream a recording without loading all of its audio into memory. */
    fun readCapture(session: String, consume: (JsonObject) -> Unit) {
        require(session in sessions) { "Capture is not bound to $name" }
        DrawingOnServices.archive?.read(session, consume)
    }

    internal fun deliver(session: String, upload: Boolean, events: List<JsonObject>) {
        val current = callback ?: return
        val action = {
            if (DrawingOnServices.targets[name] === this) {
                events.forEach { event ->
                    try { current(DrawingOnEvent(session, upload, event)) }
                    catch (error: Exception) { afield.app.diagnostics.logging.lprintln("error,drawingon", "callback for $name failed: $error") }
                }
            }
        }
        if (Runloop.mainThread == null) action() else inMain { action() }
    }
}

/** Publish/receive an optional native Drawing On head. Connect the iPad to `/drawingon/ws?target=name`. */
fun Box.drawingOn(target: String = "ipad"): DrawingOnTarget = DrawingOnServices.own(target, this)

object DrawingOnServices {
    internal val targets = ConcurrentHashMap<String, DrawingOnTarget>()
    @Volatile internal var archive: DrawingOnArchive? = null
    @Synchronized internal fun archiveAt(directory: File): DrawingOnArchive {
        val current = archive
        return if (current != null && current.directory.canonicalFile == directory.canonicalFile) current
        else DrawingOnArchive(directory).also { archive = it }
    }
    internal fun validTarget(name: String): String = name.also {
        require(it.isNotBlank() && it.length <= 120 && it.none(Char::isISOControl)) { "Invalid drawing target" }
    }
    internal fun own(name: String, box: Box): DrawingOnTarget {
        validTarget(name)
        val owner = box.id
        val result = targets.compute(name) { _, current ->
            require(current == null || current.ownerBox === box) { "Drawing target '$name' already belongs to another box" }
            current ?: DrawingOnTarget(name, owner, box).also { created ->
                box.runScopes.parent.own(ReleasePolicy.KEYED) {
                    if (targets.remove(name, created)) created.retire()
                }
            }
        }!!
        return result
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Append-only receiver archive. Acknowledgements are issued only after the file descriptor syncs. */
class DrawingOnArchive(val directory: File) {
    private val heads = mutableMapOf<String, Long>()
    init { require(directory.isDirectory || directory.mkdirs()) { "Cannot create capture directory" } }
    private fun id(session: String): String = UUID.fromString(session).toString()
    private fun file(session: String): File = File(directory, "${id(session)}.ndjson")

    @Synchronized fun head(session: String): Long = heads.getOrPut(id(session)) {
        val file = file(session)
        if (!file.exists()) 0L else RandomAccessFile(file, "rw").use { input ->
            var head = 0L; var valid = 0L
            while (input.filePointer < input.length()) {
                val raw = input.readLine() ?: break
                val end = input.filePointer
                input.seek(end - 1)
                val terminated = input.read() == 10
                input.seek(end)
                if (!terminated) { input.setLength(valid); break }
                val event = drawingOnJson.parseToJsonElement(String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)).jsonObject
                require(id(event.getValue("session").jsonPrimitive.content) == id(session)) { "Capture session mismatch" }
                require(event.getValue("seq").jsonPrimitive.long == head + 1) { "Corrupt capture sequence" }
                head++; valid = end
            }
            head
        }
    }

    @Synchronized fun bind(target: String, session: String) {
        DrawingOnServices.validTarget(target)
        val folder = File(directory, "bindings/${sha256(target.toByteArray())}")
        require(folder.isDirectory || folder.mkdirs())
        File(folder, id(session)).createNewFile()
    }
    @Synchronized fun sessions(target: String): List<String> = File(directory, "bindings/${sha256(target.toByteArray())}")
        .listFiles().orEmpty().filter { file(it.name).isFile }.map { it.name }.sorted()
    @Synchronized fun read(session: String, consume: (JsonObject) -> Unit) {
        file(session).useLines { lines -> lines.forEach { consume(drawingOnJson.parseToJsonElement(it).jsonObject) } }
    }

    data class Commit(val reply: JsonObject, val fresh: List<JsonObject>)
    @Synchronized fun commit(session: String, events: List<JsonObject>): Commit {
        require(events.size in 1..64) { "A capture batch contains 1 to 64 events" }
        val sessionID = id(session)
        val original = head(sessionID)
        var head = original
        val fresh = mutableListOf<JsonObject>()
        for (event in events) {
            require(id(event.getValue("session").jsonPrimitive.content) == sessionID) { "Session changed without a handshake" }
            require(event["v"]?.jsonPrimitive?.int == 1 && event["t"]?.jsonPrimitive?.double?.isFinite() == true)
            require(event["type"] is JsonPrimitive && event["data"] is JsonObject)
            val seq = event.getValue("seq").jsonPrimitive.long
            require(seq > 0)
            if (seq > head + 1) return Commit(buildJsonObject { put("replayFrom", original + 1) }, emptyList())
            if (seq <= head) continue
            fresh += event; head = seq
        }
        if (fresh.isNotEmpty()) {
            RandomAccessFile(file(sessionID), "rw").use { output ->
                val start = output.length(); output.seek(start)
                try {
                    fresh.forEach { output.write((it.toString() + "\n").toByteArray(Charsets.UTF_8)) }
                    output.fd.sync()
                } catch (error: Exception) { output.setLength(start); throw error }
            }
            heads[sessionID] = head
        }
        return Commit(buildJsonObject { put("ack", events.last().getValue("seq")); put("session", sessionID) }, fresh)
    }
}
