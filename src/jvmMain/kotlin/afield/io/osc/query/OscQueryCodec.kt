package afield.io.osc.query

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes and decodes the OSC 1.0 messages that OSCQuery sends in WebSocket binary frames.
 *
 * [OscQueryCodec] works on byte arrays with no socket, which is what the WebSocket side of
 * [OscQueryServer] and [OscQueryRemote] needs; UDP traffic goes through JavaOSC instead.
 */

/**
 * The OSC 1.0 message encoding OSCQuery puts in WebSocket binary frames: an address, a type-tag
 * string and big-endian arguments, each padded to four bytes. Handles `i f s d h T F N`, which is
 * every type an OSCQuery parameter here can have; a bundle or an unknown tag decodes to `null`.
 *
 * UDP input goes through JavaOSC (`afield.io.osc.openOscPortIn`); this codec exists because the
 * WebSocket side needs raw bytes in and out, without a socket.
 */
object OscQueryCodec {

    data class Packet(val address: String, val arguments: List<Any?>)

    /** The OSC type tag for [value]: `i` Int, `h` Long, `f` Float, `d` Double, `s` String, `T`/`F`, `N`. */
    fun tagOf(value: Any?): Char = when (value) {
        null -> 'N'
        is Int, is Short, is Byte -> 'i'
        is Long -> 'h'
        is Float -> 'f'
        is Double -> 'd'
        is Boolean -> if (value) 'T' else 'F'
        is CharSequence, is Char -> 's'
        else -> throw IllegalArgumentException("OSCQuery cannot encode ${value::class.simpleName}")
    }

    fun encode(address: String, arguments: List<Any?>): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, address)
        writeString(out, "," + arguments.joinToString("") { tagOf(it).toString() })
        val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        for (a in arguments) {
            b.clear()
            when (a) {
                is Int, is Short, is Byte -> { b.putInt((a as Number).toInt()); out.write(b.array(), 0, 4) }
                is Long -> { b.putLong(a); out.write(b.array(), 0, 8) }
                is Float -> { b.putFloat(a); out.write(b.array(), 0, 4) }
                is Double -> { b.putDouble(a); out.write(b.array(), 0, 8) }
                is CharSequence, is Char -> writeString(out, a.toString())
                else -> {} // T F N carry no bytes
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Packet? = try {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val address = readString(b)
        if (!address.startsWith("/")) null
        else {
            val tags = if (b.hasRemaining()) readString(b) else ","
            val args = ArrayList<Any?>()
            var ok = true
            for (t in tags.drop(1)) {
                when (t) {
                    'i' -> args += b.int
                    'h' -> args += b.long
                    'f' -> args += b.float
                    'd' -> args += b.double
                    's', 'S' -> args += readString(b)
                    'T' -> args += true
                    'F' -> args += false
                    'N' -> args += null
                    else -> { ok = false; break }
                }
            }
            if (ok) Packet(address, args) else null
        }
    } catch (_: Exception) { null }

    /** Every message in [bytes]: one, or the contents of a `#bundle` (nested bundles included). */
    fun decodeAll(bytes: ByteArray): List<Packet> {
        if (bytes.size >= 16 && String(bytes, 0, 7, Charsets.US_ASCII) == "#bundle") {
            val out = ArrayList<Packet>()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            b.position(16) // "#bundle\0" + timetag
            while (b.remaining() >= 4) {
                val size = b.int
                if (size <= 0 || size > b.remaining()) break
                val element = ByteArray(size).also { b.get(it) }
                out += decodeAll(element)
            }
            return out
        }
        return listOfNotNull(decode(bytes))
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.write(bytes)
        val pad = 4 - (bytes.size % 4)
        repeat(pad) { out.write(0) }
    }

    private fun readString(b: ByteBuffer): String {
        val start = b.position()
        var end = start
        while (b.get(end) != 0.toByte()) end++
        val s = String(b.array(), start, end - start, Charsets.UTF_8)
        val len = end - start
        b.position(start + len + (4 - len % 4))
        return s
    }
}
