@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.midi.rtp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Wire formats for RTP-MIDI (RFC 6295) and the AppleMIDI session protocol that macOS, iOS,
 * rtpMIDI on Windows and most network-MIDI hardware use around it.
 *
 * A MIDI message here is its raw bytes, status byte first: `[0x90, 60, 100]`, or a whole SysEx
 * `[0xF0, ..., 0xF7]`. [RtpMidiSession] turns them into `javax.sound.midi` messages.
 */
object RtpMidiCodec {
    const val PAYLOAD_TYPE = 0x61
    const val PROTOCOL_VERSION = 2

    /** Data bytes that follow [status], or -1 for SysEx (runs to F7) and undefined bytes. */
    fun dataLength(status: Int): Int = when (status and 0xF0) {
        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
        0xC0, 0xD0 -> 1
        0xF0 -> when (status) {
            0xF1, 0xF3 -> 1
            0xF2 -> 2
            0xF6, in 0xF8..0xFF -> 0
            else -> -1
        }
        else -> -1
    }

    // ---- MIDI command section (RFC 6295 §3) ----

    /** One command and its delta time, in RTP timestamp units, from the previous one (or the header timestamp). */
    data class Command(val delta: Int, val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Command && other.delta == delta && other.bytes.contentEquals(bytes)
        override fun hashCode() = 31 * delta + bytes.contentHashCode()
        override fun toString() = "Command(delta=$delta, ${bytes.joinToString(" ") { "%02X".format(it) }})"
    }

    /**
     * Encodes [commands] as a MIDI command section. The first delta is written only when non-zero
     * (the Z flag). With [runningStatus], a channel message that repeats the previous status omits
     * it. No journal is written.
     */
    fun encodeCommands(commands: List<Command>, runningStatus: Boolean = true): ByteArray {
        val list = ByteArrayOutputStream()
        var last = -1
        val z = commands.isNotEmpty() && commands[0].delta != 0
        commands.forEachIndexed { i, c ->
            if (i > 0 || z) writeDelta(list, c.delta)
            val status = c.bytes[0].toInt() and 0xFF
            if (runningStatus && status < 0xF0 && status == last) list.write(c.bytes, 1, c.bytes.size - 1)
            else list.write(c.bytes, 0, c.bytes.size)
            last = when {
                status < 0xF0 -> status
                status >= 0xF8 -> last                    // real-time does not touch running status
                else -> -1                                // system common and SysEx cancel it
            }
        }
        val body = list.toByteArray()
        require(body.size <= 0x0FFF) { "command section too long (${body.size} bytes)" }
        val out = ByteArrayOutputStream()
        val flags = (if (z) 0x20 else 0)
        if (body.size > 15) {
            out.write(0x80 or flags or (body.size shr 8)); out.write(body.size and 0xFF)
        } else out.write(flags or body.size)
        out.write(body)
        return out.toByteArray()
    }

    /** The result of [decodeCommands]: the commands, whether a journal followed, and SysEx carried across packets. */
    data class Decoded(val commands: List<Command>, val journalPresent: Boolean, val sysexPending: ByteArray?)

    /**
     * Decodes a MIDI command section starting at [offset] of [data]. A journal (J flag) is skipped,
     * since the header's length says where the command list ends. [sysexPending] is an unfinished
     * segmented SysEx from the previous packet of the same stream.
     */
    fun decodeCommands(data: ByteArray, offset: Int, length: Int = data.size - offset, sysexPending: ByteArray? = null): Decoded {
        var p = offset
        val end = offset + length
        val h = data[p++].toInt() and 0xFF
        val long = h and 0x80 != 0
        val j = h and 0x40 != 0
        val z = h and 0x20 != 0
        val phantom = h and 0x10 != 0
        var len = h and 0x0F
        if (long) len = (len shl 8) or (data[p++].toInt() and 0xFF)
        val listEnd = p + len
        require(listEnd <= end) { "command list length $len overruns packet" }
        val out = ArrayList<Command>()
        var running = -1
        var pending = sysexPending
        var first = true
        // P: the first command's status was omitted and is the running status from before; we keep
        // none across packets, so such a command is dropped up to the next status byte.
        if (phantom) running = -1
        while (p < listEnd) {
            var delta = 0
            if (!first || z) {
                var n = 0
                while (true) {
                    val b = data[p++].toInt() and 0xFF
                    delta = (delta shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0 || ++n == 4) break
                }
                if (p >= listEnd) break
            }
            first = false
            var b = data[p].toInt() and 0xFF
            if (b and 0x80 == 0) {
                if (running < 0) { while (p < listEnd && data[p].toInt() and 0x80 == 0) p++; continue }
                val n = dataLength(running)
                out += Command(delta, byteArrayOf(running.toByte()) + data.copyOfRange(p, minOf(p + n, listEnd)))
                p += n
                continue
            }
            when {
                b == 0xF0 || (b == 0xF7 && pending != null) -> {
                    // SysEx, whole (F0 .. F7) or a segment (F0 .. F0, F7 .. F0, F7 .. F7, F7 .. F4 cancel).
                    val start = p++
                    while (p < listEnd) {
                        val c = data[p].toInt() and 0xFF
                        if (c == 0xF7 || c == 0xF0 || c == 0xF4) break
                        p++
                    }
                    if (p >= listEnd) { pending = null; break }
                    val term = data[p++].toInt() and 0xFF
                    val body = data.copyOfRange(start + 1, p - 1)
                    val head = if (b == 0xF0) byteArrayOf(0xF0.toByte()) else (pending ?: byteArrayOf(0xF0.toByte()))
                    when (term) {
                        0xF7 -> { out += Command(delta, head + body + byteArrayOf(0xF7.toByte())); pending = null }
                        0xF0 -> pending = head + body
                        else -> pending = null                     // F4: cancelled
                    }
                    running = -1
                }
                else -> {
                    val n = dataLength(b)
                    if (n < 0) { p++; continue }
                    out += Command(delta, data.copyOfRange(p, minOf(p + 1 + n, listEnd)))
                    p += 1 + n
                    if (b < 0xF0) running = b else if (b < 0xF8) running = -1
                }
            }
        }
        return Decoded(out, j, pending)
    }

    private fun writeDelta(out: ByteArrayOutputStream, delta: Int) {
        val d = delta.coerceIn(0, 0x0FFFFFFF)
        var shift = 21
        while (shift > 0 && (d shr shift) == 0) shift -= 7
        while (shift > 0) { out.write(0x80 or ((d shr shift) and 0x7F)); shift -= 7 }
        out.write(d and 0x7F)
    }

    // ---- RTP packet ----

    data class RtpPacket(val sequence: Int, val timestamp: Long, val ssrc: Int, val commands: List<Command>, val journal: Boolean, val sysexPending: ByteArray? = null)

    fun encodeRtp(sequence: Int, timestamp: Long, ssrc: Int, commands: List<Command>, runningStatus: Boolean = true): ByteArray {
        val section = encodeCommands(commands, runningStatus)
        return ByteBuffer.allocate(12 + section.size).apply {
            put(0x80.toByte()); put(PAYLOAD_TYPE.toByte()); putShort(sequence.toShort())
            putInt(timestamp.toInt()); putInt(ssrc); put(section)
        }.array()
    }

    fun isRtpMidi(data: ByteArray, length: Int) =
        length >= 13 && (data[0].toInt() and 0xC0) == 0x80 && (data[1].toInt() and 0x7F) == PAYLOAD_TYPE

    fun decodeRtp(data: ByteArray, length: Int, sysexPending: ByteArray? = null): RtpPacket {
        val bb = ByteBuffer.wrap(data, 0, length)
        val b0 = bb.get().toInt() and 0xFF
        bb.get()
        val seq = bb.short.toInt() and 0xFFFF
        val ts = bb.int.toLong() and 0xFFFFFFFFL
        val ssrc = bb.int
        val csrc = b0 and 0x0F
        val start = 12 + 4 * csrc
        val d = decodeCommands(data, start, length - start, sysexPending)
        return RtpPacket(seq, ts, ssrc, d.commands, d.journalPresent, d.sysexPending)
    }

    // ---- AppleMIDI session packets ----

    sealed class Session {
        /** IN, OK, NO; [name] is absent from NO on some peers. */
        data class Invitation(val command: String, val token: Int, val ssrc: Int, val name: String?) : Session()
        data class Bye(val token: Int, val ssrc: Int) : Session()
        /** CK: [count] 0, 1, 2 and the three timestamps in 100 µs units. */
        data class Sync(val ssrc: Int, val count: Int, val ts1: Long, val ts2: Long, val ts3: Long) : Session()
        /** RS: the peer has received everything up to [sequence]. */
        data class Feedback(val ssrc: Int, val sequence: Int) : Session()
    }

    fun isSession(data: ByteArray, length: Int) = length >= 4 && data[0] == 0xFF.toByte() && data[1] == 0xFF.toByte()

    fun encode(s: Session): ByteArray = when (s) {
        is Session.Invitation -> {
            val name = s.name?.toByteArray(Charsets.UTF_8)
            ByteBuffer.allocate(16 + (name?.size?.plus(1) ?: 0)).apply {
                putShort(-1); put(s.command.toByteArray(Charsets.US_ASCII)); putInt(PROTOCOL_VERSION); putInt(s.token); putInt(s.ssrc)
                if (name != null) { put(name); put(0) }
            }.array()
        }
        is Session.Bye -> ByteBuffer.allocate(16).apply {
            putShort(-1); put("BY".toByteArray()); putInt(PROTOCOL_VERSION); putInt(s.token); putInt(s.ssrc)
        }.array()
        is Session.Sync -> ByteBuffer.allocate(36).apply {
            putShort(-1); put("CK".toByteArray()); putInt(s.ssrc); put(s.count.toByte()); put(0); putShort(0)
            putLong(s.ts1); putLong(s.ts2); putLong(s.ts3)
        }.array()
        is Session.Feedback -> ByteBuffer.allocate(12).apply {
            putShort(-1); put("RS".toByteArray()); putInt(s.ssrc); putInt(s.sequence shl 16)
        }.array()
    }

    /** Decodes a session packet, or null for one this implementation does not know. */
    fun decodeSession(data: ByteArray, length: Int): Session? {
        if (!isSession(data, length)) return null
        val bb = ByteBuffer.wrap(data, 0, length)
        bb.position(2)
        val cmd = String(byteArrayOf(bb.get(), bb.get()), Charsets.US_ASCII)
        return when (cmd) {
            "IN", "OK", "NO" -> {
                if (length < 16) return null
                bb.int
                val token = bb.int; val ssrc = bb.int
                var name: String? = null
                if (length > 16) {
                    var e = 16
                    while (e < length && data[e] != 0.toByte()) e++
                    name = String(data, 16, e - 16, Charsets.UTF_8)
                }
                Session.Invitation(cmd, token, ssrc, name)
            }
            "BY" -> if (length < 16) null else { bb.int; Session.Bye(bb.int, bb.int) }
            "CK" -> if (length < 36) null else {
                val ssrc = bb.int; val count = bb.get().toInt() and 0xFF; bb.get(); bb.short
                Session.Sync(ssrc, count, bb.long, bb.long, bb.long)
            }
            "RS" -> if (length < 12) null else Session.Feedback(bb.int, (bb.int ushr 16) and 0xFFFF)
            else -> null
        }
    }
}
