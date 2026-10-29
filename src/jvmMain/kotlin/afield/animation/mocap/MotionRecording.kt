@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Portable .f4motion directory: JSON metadata/events and chunked little-endian float32 poses.
 * No native Python objects or pickle. The manifest appears only after a clean close.
 * Requested/applied/superseded commands and reset/termination diagnostics belong in [event].
 */
class MotionRecorder(val directory: Path, val skeleton: MotionSkeleton, configuration: JSONObject,
    val chunkFrames: Int = 256) : AutoCloseable {
    private val metadata = JSONObject(configuration.toString())
    private val chunks = ArrayList<String>()
    private var output: DataOutputStream? = null
    private var inChunk = 0
    private var count = 0L
    private var last: MotionFrame? = null
    private var closed = false
    private val events: java.io.BufferedWriter
    init {
        require(chunkFrames in 1..100000)
        Files.createDirectory(directory) // Existing recordings are never overwritten.
        events = Files.newBufferedWriter(directory.resolve("events.jsonl"))
        metadata.put("schema", 1).put("format", "field.motion.v1").put("skeleton", skeleton.json())
        Files.writeString(directory.resolve("incomplete.json"), metadata.toString(2))
    }
    @Synchronized fun event(event: JSONObject) {
        check(!closed)
        events.write(event.toString()); events.newLine(); events.flush()
    }
    @Synchronized fun append(frame: MotionFrame) {
        check(!closed)
        require(frame.executed.size == skeleton.size)
        last?.let { require(frame.time >= it.time && frame.index > it.index && frame.episode >= it.episode) }
        if (output == null || inChunk == chunkFrames) {
            output?.close()
            val name = "chunk-%06d.bin".format(chunks.size)
            chunks.add(name); output = DataOutputStream(Files.newOutputStream(directory.resolve(name))); inChunk = 0
        }
        val record = MotionBinary.encode(frame)
        output!!.writeInt(record.size); output!!.write(record)
        count++; inChunk++; last = frame
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { output?.close() } finally { events.close() }
        metadata.put("chunks", chunks).put("frameCount", count)
        val temp = directory.resolve("manifest.tmp")
        Files.writeString(temp, metadata.toString(2))
        Files.move(temp, directory.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE)
        Files.delete(directory.resolve("incomplete.json"))
    }
}

/** Streaming read keeps long recordings off the live buffer; [clip] is an explicit in-memory load. */
class MotionRecording(val directory: Path) {
    val metadata: JSONObject
    val skeleton: MotionSkeleton
    private val chunks: List<String>
    init {
        val path = directory.resolve("manifest.json")
        require(Files.size(path) <= 4 * 1024 * 1024) { "Manifest too large" }
        metadata = JSONObject(Files.readString(path))
        require(metadata.getInt("schema") == 1 && metadata.getString("format") == "field.motion.v1")
        skeleton = MotionSkeleton.fromJson(metadata.getJSONObject("skeleton"))
        chunks = metadata.getJSONArray("chunks").strings()
        require(chunks.size <= 100000 && chunks.toSet().size == chunks.size)
        chunks.forEachIndexed { i, name -> require(name == "chunk-%06d.bin".format(i)) }
    }
    fun frames(): Sequence<MotionFrame> = sequence {
        var count = 0L
        var last: MotionFrame? = null
        for (name in chunks) {
            val path = directory.resolve(name)
            require(!Files.isSymbolicLink(path)) { "Recording chunk must not be a symlink" }
            DataInputStream(Files.newInputStream(path).buffered()).use { input ->
                while (true) {
                    val first = input.read()
                    if (first == -1) break
                    val length = (first shl 24) or (input.readUnsignedByte() shl 16) or
                        (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                    require(length in 4..MotionBinary.MAX_BYTES) { "Invalid motion record size: $length" }
                    val bytes = ByteArray(length); input.readFully(bytes)
                    val frame = MotionBinary.decode(bytes, skeleton.size)
                    last?.let { require(frame.time >= it.time && frame.index > it.index && frame.episode >= it.episode) }
                    last = frame; count++
                    yield(frame)
                }
            }
        }
        require(count == metadata.getLong("frameCount")) { "Recording frame count mismatch" }
    }
    fun clip() = MotionClip(skeleton, frames().toList())
    fun events(): Sequence<JSONObject> = sequence {
        Files.newBufferedReader(directory.resolve("events.jsonl")).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                require(line.length <= MotionBinary.MAX_BYTES)
                yield(JSONObject(line))
            }
        }
    }
}

internal object MotionBinary {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun encode(frame: MotionFrame): ByteArray {
        val header = frame.json().removePoses().toString().toByteArray(Charsets.UTF_8)
        val values = ArrayList<Double>()
        fun pose(p: MotionPose) { values.addAll(p.root); values.addAll(p.localRotations); values.addAll(p.worldPositions) }
        pose(frame.executed); frame.intended?.let(::pose); values.addAll(frame.contacts)
        val bytes = ByteBuffer.allocate(4 + header.size + values.size * 4).order(ByteOrder.BIG_ENDIAN)
        require(bytes.capacity() <= MAX_BYTES)
        bytes.putInt(header.size).put(header).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bytes.putFloat(it.toFloat()) }
        return bytes.array()
    }
    private fun JSONObject.removePoses(): JSONObject {
        val reference = !isNull("intended")
        val referenceRotations = reference && getJSONObject("intended").getJSONArray("localRotations").length() > 0
        remove("executed"); remove("intended")
        val count = getJSONArray("contacts").length(); remove("contacts")
        return put("hasIntended", reference).put("intendedHasRotations", referenceRotations).put("contactCount", count)
    }
    fun decode(bytes: ByteArray, joints: Int): MotionFrame {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val length = buffer.int
        require(length in 2..(bytes.size - 4))
        val header = ByteArray(length); buffer.get(header)
        val json = JSONObject(String(header, Charsets.UTF_8))
        val contacts = json.getInt("contactCount")
        require(contacts in 0..1024)
        val poses = if (json.getBoolean("hasIntended")) 2 else 1
        val referenceRotations = json.optBoolean("intendedHasRotations", true)
        val floats = 3 + joints * 7 + (if (poses == 2) 3 + joints * (if (referenceRotations) 7 else 3) else 0) + contacts
        require(buffer.remaining() == floats * 4) { "Pose payload length mismatch" }
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        fun values(n: Int) = List(n) { buffer.float.toDouble() }
        fun pose(rotations: Boolean = true) = MotionPose(values(3), values(if (rotations) joints * 4 else 0), values(joints * 3))
        val executed = pose(); val intended = if (poses == 2) pose(referenceRotations) else null
        val d = json.getJSONObject("diagnostics")
        return MotionFrame(json.getDouble("time"), json.getLong("index"), json.getLong("episode"), json.getLong("revision"),
            executed, intended, values(contacts), json.getString("contactProvenance"), d.keySet().associateWith { d.getDouble(it) })
    }
}
