package afield.timecode

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One LTC (linear timecode) frame: the 80 bits SMPTE 12M records on an audio track once per video
 * frame. Bits 0..63 carry the timecode in BCD interleaved with 32 user bits; bits 64..79 are the
 * fixed sync word `0011 1111 1111 1101`, which tells a reader where a frame ends and which way the
 * tape is moving.
 *
 * [userBits] holds the eight 4-bit user-bit groups, group 1 in the lowest nibble.
 */
data class LtcFrame(
    val timecode: Timecode,
    val userBits: Int = 0,
    val colorFrame: Boolean = false,
) {
    /** The 80 bits in transmission order (bit 0 first). */
    fun toBits(): BooleanArray {
        val bits = BooleanArray(80)
        fun put(start: Int, width: Int, value: Int) {
            for (i in 0 until width) bits[start + i] = (value shr i) and 1 == 1
        }
        val tc = timecode
        put(0, 4, tc.frame % 10)
        put(8, 2, tc.frame / 10)
        bits[10] = tc.rate.dropFrame
        bits[11] = colorFrame
        put(16, 4, tc.seconds % 10)
        put(24, 3, tc.seconds / 10)
        put(32, 4, tc.minutes % 10)
        put(40, 3, tc.minutes / 10)
        put(48, 4, tc.hours % 10)
        put(56, 2, tc.hours / 10)
        for (g in 0 until 8) put(USER_BIT_STARTS[g], 4, (userBits ushr (4 * g)) and 0xF)
        for (i in 0 until 16) bits[64 + i] = SYNC[i]
        // Polarity correction: make the frame's count of ones even, so every frame starts at the
        // same signal level. It lives in bit 27 except at 25 fps, where it is bit 59.
        val polarityBit = if (tc.rate == TimecodeRate.FPS_25) 59 else 27
        if (bits.count { it } % 2 == 1) bits[polarityBit] = true
        return bits
    }

    companion object {
        internal val USER_BIT_STARTS = intArrayOf(4, 12, 20, 28, 36, 44, 52, 60)

        /** The sync word, bits 64..79 in transmission order. */
        val SYNC: BooleanArray = "0011111111111101".map { it == '1' }.toBooleanArray()

        /**
         * Decode 80 bits in transmission order. Returns null if the sync word is wrong or a BCD
         * field is out of range. LTC does not carry the frame rate except for the drop-frame flag,
         * so a non-drop frame is read at [rate] (or 30 if [rate] is itself drop-frame).
         */
        fun fromBits(bits: BooleanArray, rate: TimecodeRate = TimecodeRate.FPS_30): LtcFrame? {
            if (bits.size != 80) return null
            for (i in 0 until 16) if (bits[64 + i] != SYNC[i]) return null
            fun get(start: Int, width: Int): Int {
                var v = 0
                for (i in 0 until width) if (bits[start + i]) v = v or (1 shl i)
                return v
            }
            val fu = get(0, 4); val su = get(16, 4); val mu = get(32, 4); val hu = get(48, 4)
            if (fu > 9 || su > 9 || mu > 9 || hu > 9) return null
            val f = get(8, 2) * 10 + fu
            val s = get(24, 3) * 10 + su
            val m = get(40, 3) * 10 + mu
            val h = get(56, 2) * 10 + hu
            val drop = bits[10]
            val r = when {
                drop -> TimecodeRate.FPS_2997_DF
                rate.dropFrame -> TimecodeRate.FPS_30
                else -> rate
            }
            val tc = runCatching { Timecode.of(h, m, s, f, r) }.getOrNull() ?: return null
            var ub = 0
            for (g in 0 until 8) ub = ub or (get(USER_BIT_STARTS[g], 4) shl (4 * g))
            return LtcFrame(tc, ub, bits[11])
        }
    }
}

/**
 * Writes LTC as biphase-mark audio: the level flips at the start of every bit, and a 1 flips
 * again half-way through. The output is a square wave between `-amplitude` and `+amplitude`;
 * bit boundaries are placed on the nearest sample of the exact (fractional) position, so a long
 * render does not drift even at 29.97.
 */
class LtcEncoder(
    val rate: TimecodeRate,
    val sampleRate: Int = 48000,
    val amplitude: Double = 0.5,
) {
    /** Samples per bit at this rate, fractional. */
    val samplesPerBit: Double get() = sampleRate / (rate.fps * 80.0)

    private var level = -1.0
    private var halfBitsWritten = 0L

    /** Encode [count] consecutive frames starting at [start]. */
    fun encode(start: Timecode, count: Int, userBits: Int = 0): FloatArray {
        require(start.rate == rate) { "start $start is not at $rate" }
        val out = ArrayList<Float>((count * 80 * samplesPerBit + 16).toInt())
        var tc = start
        repeat(count) {
            encodeFrame(LtcFrame(tc, userBits), out)
            tc += 1
        }
        return out.toFloatArray()
    }

    /** Encode one frame, appending its samples to [out]. */
    fun encodeFrame(frame: LtcFrame, out: MutableList<Float>) {
        val half = samplesPerBit / 2
        for (bit in frame.toBits()) {
            for (h in 0..1) {
                if (h == 0 || bit) level = -level
                val from = (halfBitsWritten * half).roundToLong()
                halfBitsWritten++
                val to = (halfBitsWritten * half).roundToLong()
                val v = (level * amplitude).toFloat()
                for (i in from until to) out.add(v)
            }
        }
    }

    companion object {
        /** Render [seconds] of LTC from [start] (rounded up to whole frames). */
        fun render(start: Timecode, seconds: Double, sampleRate: Int = 48000, amplitude: Double = 0.5, userBits: Int = 0): FloatArray {
            val frames = kotlin.math.ceil(seconds * start.rate.fps).toInt()
            return LtcEncoder(start.rate, sampleRate, amplitude).encode(start, frames, userBits)
        }
    }
}

/** A frame the [LtcDecoder] found, with where it sat in the input. */
data class LtcDecodedFrame(
    val frame: LtcFrame,
    /** Earliest sample of the frame in the input stream (the start of bit 0, or of bit 79 when [reverse]). */
    val sampleStart: Double,
    /** Sample at which the frame's last bit ended — the earliest moment the frame can be known. */
    val sampleEnd: Double,
    /** True if the frame was read backwards (tape or playhead running in reverse). */
    val reverse: Boolean,
    /** Measured playback speed relative to the nominal rate (1.0 = real time). */
    val speed: Double,
) {
    val timecode: Timecode get() = frame.timecode
}

/**
 * A streaming LTC reader. Push audio in with [feed] in any block size; frames come back as they
 * complete, with absolute sample positions counted from the first sample ever fed.
 *
 * It tolerates gain (it tracks the signal envelope), DC offset (a DC blocker runs first),
 * moderate noise (hysteresis on the zero crossings), speed changes (the bit period is tracked,
 * not assumed) and reverse play (the sync word is recognised in both directions).
 *
 * LTC does not say whether it is 24, 25 or 30 fps (only whether it is drop-frame), so pass [rate]
 * when you know it. With no hint the rate is inferred from the measured bit period, which is
 * only right when the source plays at close to real time.
 *
 * Feed it from the thread that owns the audio buffers; it is not thread-safe.
 */
class LtcDecoder(
    val sampleRate: Int = 48000,
    val rate: TimecodeRate? = null,
) {
    // DC blocker and envelope.
    private var x1 = 0.0
    private var y1 = 0.0
    private val dcR = 1.0 - 2.0 * Math.PI * 20.0 / sampleRate
    private var env = 0.0
    private val envDecay = Math.exp(-1.0 / (0.05 * sampleRate))
    private var level = 0
    private var prevY = 0.0
    private var lastCross = 0.0
    private var lastTransition = Double.NaN
    private var position = 0L

    // Interval classification.
    private var period = Double.NaN // full bit period, in samples
    private val warmup = ArrayList<DoubleArray>()
    private var pendingShortStart = Double.NaN

    // Bit window.
    private val bits = BooleanArray(80)
    private val bitStarts = DoubleArray(80)
    private var bitCount = 0
    private var lastEnd = Double.NaN

    private val found = ArrayList<LtcDecodedFrame>()

    /** Frames decoded so far, across all calls. */
    var framesDecoded: Int = 0
        private set

    /** Feed [length] samples from [samples] at [offset]. Returns the frames completed during this block. */
    fun feed(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): List<LtcDecodedFrame> {
        found.clear()
        for (i in offset until offset + length) {
            val x = samples[i].toDouble()
            val y = x - x1 + dcR * y1
            x1 = x; y1 = y
            val a = abs(y)
            env = if (a > env) a else env * envDecay
            if (prevY <= 0.0 && y > 0.0 || prevY >= 0.0 && y < 0.0) {
                val t = if (y != prevY) prevY / (prevY - y) else 0.0
                lastCross = position - 1 + t
            }
            prevY = y
            val thr = 0.3 * env
            if (env > 1e-4) {
                if (level <= 0 && y > thr) { level = 1; transition(lastCross) }
                else if (level >= 0 && y < -thr) { level = -1; transition(lastCross) }
            }
            position++
        }
        return found.toList()
    }

    /** Decode a whole buffer at once. */
    fun decodeAll(samples: FloatArray): List<LtcDecodedFrame> = feed(samples)

    private fun transition(at: Double) {
        val prev = lastTransition
        lastTransition = at
        if (prev.isNaN()) return
        val d = at - prev
        if (period.isNaN()) {
            warmup.add(doubleArrayOf(prev, d))
            // Learn the bit period from the two interval clusters (half bits and whole bits). A
            // run of zeros shows only whole bits, so wait until both have appeared — the sync word
            // guarantees that within one frame.
            if (warmup.size >= 32) {
                val sorted = warmup.map { it[1] }.sorted()
                val lo = sorted[sorted.size / 20]
                val hi = sorted[sorted.size * 19 / 20]
                if (hi < 1.5 * lo && warmup.size < 400) return
                period = hi
                val replay = warmup.toList()
                warmup.clear()
                for (w in replay) interval(w[0], w[1])
            }
            return
        }
        interval(prev, d)
    }

    private fun interval(start: Double, d: Double) {
        if (d > 1.6 * period) {
            // A gap (dropout, stop): lose sync and re-learn the period.
            pendingShortStart = Double.NaN
            bitCount = 0
            if (d > 4 * period) { period = Double.NaN; warmup.clear() }
            return
        }
        if (d < 0.75 * period) {
            period = 0.98 * period + 0.02 * (2 * d)
            if (pendingShortStart.isNaN()) pendingShortStart = start
            else { pushBit(true, pendingShortStart, start + d); pendingShortStart = Double.NaN }
        } else {
            period = 0.98 * period + 0.02 * d
            pendingShortStart = Double.NaN
            pushBit(false, start, start + d)
        }
    }

    private fun pushBit(bit: Boolean, start: Double, end: Double) {
        if (bitCount < 80) {
            bits[bitCount] = bit; bitStarts[bitCount] = start; bitCount++
        } else {
            System.arraycopy(bits, 1, bits, 0, 79)
            System.arraycopy(bitStarts, 1, bitStarts, 0, 79)
            bits[79] = bit; bitStarts[79] = start
        }
        if (bitCount < 80) return
        val forward = (0 until 16).all { bits[64 + it] == LtcFrame.SYNC[it] }
        val backward = !forward && (0 until 16).all { bits[it] == LtcFrame.SYNC[15 - it] }
        if (!forward && !backward) return
        // Two checks against a false sync from noise or a dropout: the polarity bit makes every
        // genuine frame's count of ones even, and a genuine frame's 80 bits are evenly spaced.
        if (bits.count { it } % 2 != 0) return
        val span = (end - bitStarts[0]) / 80.0
        if (span < 0.8 * period || span > 1.25 * period) return
        val ordered = if (forward) bits.copyOf() else BooleanArray(80) { bits[79 - it] }
        val fps = sampleRate / (80.0 * period)
        val hint = rate ?: inferRate(fps)
        val frame = LtcFrame.fromBits(ordered, hint) ?: return
        val speed = fps / frame.timecode.rate.fps
        found.add(LtcDecodedFrame(frame, bitStarts[0], end, backward, speed))
        framesDecoded++
        lastEnd = end
        bitCount = 0
    }

    private fun inferRate(fps: Double): TimecodeRate =
        listOf(TimecodeRate.FPS_24, TimecodeRate.FPS_25, TimecodeRate.FPS_30).minBy { abs(it.fps - fps) }
}

/** Mono 16-bit PCM `.wav` writing and reading, enough for LTC files. */
object TimecodeWav {
    fun write(file: File, samples: FloatArray, sampleRate: Int = 48000) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data.capacity())
        file.parentFile?.mkdirs()
        val out = ByteArrayOutputStream()
        out.write(header.array()); out.write(data.array())
        file.writeBytes(out.toByteArray())
    }

    /** Read a mono 16-bit PCM file written by [write] (or any canonical 44-byte-header mono wav). Returns samples and rate. */
    fun read(file: File): Pair<FloatArray, Int> {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bytes.getInt(24)
        val n = bytes.getInt(40) / 2
        return FloatArray(n) { bytes.getShort(44 + 2 * it) / 32767f } to rate
    }
}
