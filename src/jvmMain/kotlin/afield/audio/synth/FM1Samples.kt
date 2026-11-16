@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The FM-1's sample bank: the eight pads its sampler tracks play, as the firmware keeps them in flash.
 *
 * A bank is a 512-byte header (`app/samples.c`: magic `FM1S`, version 1, total size, CRC-32, rate
 * 32,000 Hz, and sixteen `offset, frames, bytes, name` entries of which the first eight are pads)
 * followed by each pad's audio as SEA5 blocks, 228 bytes for every 320 frames. [FM1Sim.sample] puts
 * Field audio on a pad; this class reads and writes the bank around it.
 */

/**
 * One pad of the FM-1's sample bank.
 *
 * @param pad 0..7
 * @param note the MIDI note a sampler track plays it on
 * @param name the pad's name, up to 15 characters; the screen shows its first five
 * @param frames its length at [FM1SampleBank.RATE]; 0 for an empty pad
 */
data class FM1Pad(val pad: Int, val note: Int, val name: String, val frames: Int) {
    /** The pad's length in seconds. */
    val seconds: Double get() = frames / FM1SampleBank.RATE.toDouble()

    /** Whether nothing is loaded on this pad. */
    val empty: Boolean get() = frames == 0
}

/**
 * A sample bank as a list of eight pads, each its name, frame count and encoded SEA5 bytes.
 *
 * [parse] reads one out of flash (or a saved `.fm1s` file), [bytes] assembles one the firmware's
 * validator accepts: offsets packed in pad order, unused entries zero, CRC over the whole bank with
 * the CRC and commit words counted as zero.
 */
class FM1SampleBank(val pads: List<Pad>) {

    /**
     * One pad's encoded audio.
     *
     * @param name up to 15 printable ASCII characters
     * @param frames frames at [RATE]
     * @param data the SEA5 blocks, `ceil(frames / 320) * 228` bytes
     */
    class Pad(val name: String, val frames: Int, val data: ByteArray) {
        init {
            require(name.length <= NAME_CHARS && name.all { it.code in 32..126 }) {
                "FM1SampleBank: a pad name is at most $NAME_CHARS printable ASCII characters, not \"$name\""
            }
            require(frames in 0..MAX_FRAMES) { "FM1SampleBank: a pad holds 0..$MAX_FRAMES frames, not $frames" }
            require(data.size == encodedBytes(frames)) {
                "FM1SampleBank: $frames frames encode to ${encodedBytes(frames)} bytes, not ${data.size}"
            }
        }

        /** The same audio under another name. */
        fun named(name: String): Pad = Pad(name, frames, data)
    }

    init {
        require(pads.size == PADS) { "FM1SampleBank: a bank has $PADS pads, not ${pads.size}" }
    }

    /** The bank's size in bytes, header included. */
    val size: Int get() = HEADER + pads.sumOf { it.data.size }

    /** This bank with [pad] replaced. */
    fun with(pad: Int, value: Pad): FM1SampleBank {
        require(pad in 0 until PADS) { "FM1SampleBank: pads are 0 until $PADS, not $pad" }
        return FM1SampleBank(pads.toMutableList().also { it[pad] = value })
    }

    /** The pads as [FM1Pad]s, for listing. */
    fun describe(): List<FM1Pad> = pads.mapIndexed { i, p -> FM1Pad(i, PAD_NOTES[i], p.name, p.frames) }

    /** The bank as the firmware stores it; fails if it would not fit beside the voice library. */
    fun bytes(): ByteArray {
        check(size <= LIMIT) {
            "FM1SampleBank: the pads take ${size - HEADER} bytes (${"%.1f".format(totalSeconds())} s); the bank holds " +
                "${LIMIT - HEADER} (${"%.1f".format(MAX_SECONDS)} s at 32 kHz) across all eight"
        }
        val out = ByteArray(size)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, MAGIC); b.putInt(4, 1); b.putInt(8, size); b.putInt(20, RATE)
        var next = HEADER
        for (i in 0 until ENTRIES) {
            val at = 32 + i * ENTRY
            val p = pads.getOrNull(i)
            b.putInt(at, next)
            if (p != null) {
                b.putInt(at + 4, p.frames); b.putInt(at + 8, p.data.size)
                p.name.toByteArray(Charsets.US_ASCII).copyInto(out, at + 12)
                p.data.copyInto(out, next)
                next += p.data.size
            }
        }
        b.putInt(12, crc(out))
        return out
    }

    private fun totalSeconds() = pads.sumOf { it.frames } / RATE.toDouble()

    companion object {
        /** The bank's sample rate; pads play at this rate on any track. */
        const val RATE: Int = 32_000

        /** Pads in a bank. */
        const val PADS: Int = 8

        /** The MIDI note each pad plays on, in pad order: BD, SD, RIM, LT, MT, HT, CH, OH. */
        val PAD_NOTES: IntArray = intArrayOf(36, 38, 37, 41, 47, 48, 42, 46)

        /** Characters in a pad name. */
        const val NAME_CHARS: Int = 15

        /** Bytes of header in front of the audio. */
        const val HEADER: Int = 512

        /** Frames per SEA5 block. */
        const val CHUNK_FRAMES: Int = 320

        /** Bytes per SEA5 block. */
        const val CHUNK_BYTES: Int = 228

        /**
         * The most bytes a bank may take, header included: the sample region stops short of the five
         * sectors holding the DX7 voice library and the tuning tables (`GROOVE_SAMPLE_LIMIT`).
         */
        const val LIMIT: Int = 466_944 - 5 * 4096

        /** The most frames one pad can hold (the firmware's `SAMPLE_DRUM_MAX_FRAMES`). */
        const val MAX_FRAMES: Int = (466_944 - HEADER) / CHUNK_BYTES * CHUNK_FRAMES

        /** Seconds of audio the eight pads share, about 19.5. */
        val MAX_SECONDS: Double = (LIMIT - HEADER) / CHUNK_BYTES * CHUNK_FRAMES / RATE.toDouble()

        private const val MAGIC = 0x53314d46 // "FM1S"
        private const val ENTRIES = 16
        private const val ENTRY = 28

        /** Bytes [frames] encode to. */
        fun encodedBytes(frames: Int): Int = (frames + CHUNK_FRAMES - 1) / CHUNK_FRAMES * CHUNK_BYTES

        /** An empty pad. */
        val EMPTY: Pad = Pad("", 0, ByteArray(0))

        /** The firmware's checksum: CRC-32 of the bank with bytes 12..19 (CRC and commit) as zero. */
        fun crc(bank: ByteArray): Int {
            val c = CRC32()
            c.update(bank, 0, 12)
            c.update(ByteArray(8))
            c.update(bank, 20, bank.size - 20)
            return c.value.toInt()
        }

        /**
         * Read a bank, checking it the way `app/samples.c` does; throws with the first thing wrong.
         * Trailing bytes past the bank's own size (the rest of a flash region) are ignored.
         */
        fun parse(bytes: ByteArray): FM1SampleBank {
            require(bytes.size >= HEADER) { "FM1SampleBank: ${bytes.size} bytes is shorter than a header" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.getInt(0) == MAGIC) { "FM1SampleBank: not a sample bank (no FM1S magic)" }
            require(b.getInt(4) == 1 && b.getInt(20) == RATE) { "FM1SampleBank: unknown version or rate" }
            val size = b.getInt(8)
            require(size in HEADER..bytes.size) { "FM1SampleBank: header says $size bytes, have ${bytes.size}" }
            val bank = bytes.copyOf(size)
            require(crc(bank) == b.getInt(12)) { "FM1SampleBank: CRC does not match" }
            val pads = (0 until PADS).map { i ->
                val at = 32 + i * ENTRY
                val offset = b.getInt(at); val frames = b.getInt(at + 4); val n = b.getInt(at + 8)
                require(offset in HEADER..size && n >= 0 && offset + n <= size) { "FM1SampleBank: pad $i lies outside the bank" }
                val name = String(bank, at + 12, 16, Charsets.US_ASCII).substringBefore('\u0000')
                Pad(name, frames, bank.copyOfRange(offset, offset + n))
            }
            return FM1SampleBank(pads)
        }

        /**
         * [audio] at [sampleRate], as mono 16-bit samples at [RATE], ready for the firmware's encoder.
         *
         * Downsampling goes through a windowed-sinc low-pass at the lower Nyquist, so a 44.1 or
         * 48 kHz source does not fold its top octave back down. Values past ±1 are clipped, the way
         * the device would clip them; a NaN becomes silence rather than a click.
         */
        fun toPcm(audio: FloatArray, sampleRate: Double): ShortArray {
            require(sampleRate.isFinite() && sampleRate > 0.0) { "FM1SampleBank: sample rate must be finite and > 0, was $sampleRate" }
            val ratio = RATE / sampleRate
            val frames = ceil(audio.size * ratio).toInt()
            require(frames <= MAX_FRAMES) {
                "FM1SampleBank: ${"%.2f".format(frames / RATE.toDouble())} s is longer than a pad holds " +
                    "(${"%.2f".format(MAX_FRAMES / RATE.toDouble())} s)"
            }
            fun at(i: Int): Float = audio.getOrNull(i)?.takeIf { !it.isNaN() } ?: 0f
            val out = ShortArray(frames)
            if (abs(ratio - 1.0) < 1e-9) {
                for (i in 0 until frames) out[i] = pcm16(at(i))
                return out
            }
            val cutoff = minOf(1.0, ratio)
            val half = 16
            val width = ceil(half / cutoff).toInt()
            for (i in 0 until frames) {
                val t = i / ratio
                val centre = floor(t).toInt()
                var acc = 0.0; var norm = 0.0
                for (j in centre - width + 1..centre + width) {
                    val x = (t - j) * cutoff
                    val w = if (abs(x) >= half) 0.0 else 0.5 + 0.5 * cos(PI * x / half)
                    val k = w * if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    acc += k * at(j); norm += k
                }
                out[i] = pcm16((acc / norm).toFloat())
            }
            return out
        }

        private fun pcm16(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
    }
}
