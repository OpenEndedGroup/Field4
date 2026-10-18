package afield.io.timecode

/**
 * The SMPTE timecode value types: [TimecodeRate] (24, 25, 29.97 drop-frame, 30) and [Timecode],
 * a frame count at a rate with HH:MM:SS:FF labelling, parsing and arithmetic.
 *
 * The LTC, MTC and clock code in this package all exchange [Timecode] values.
 */

/**
 * SMPTE timecode: the HH:MM:SS:FF clock that lighting desks, video servers and DAWs share.
 *
 * A [Timecode] is a frame count at a [TimecodeRate]. Everything else (the four fields, the label,
 * seconds) is derived from that count, so arithmetic is plain integer arithmetic and drop-frame
 * labelling is handled in exactly one place ([fromFrames] / [toFrames]).
 *
 * ```
 * val t = Timecode.parse("00:00:59;29")      // 29.97 drop-frame, because of the ';'
 * println(t + 1)                             // 00:01:00;02 — ;00 and ;01 are skipped
 * ```
 */
enum class TimecodeRate(
    /** Frames counted per labelled second (24, 25 or 30). */
    val nominalFps: Int,
    /** True for 29.97 drop-frame: labels ;00 and ;01 are skipped at every minute except each tenth. */
    val dropFrame: Boolean,
    /** Real frames per second of wall time. */
    val fps: Double,
    /** The two-bit rate code MTC carries (0 = 24, 1 = 25, 2 = 29.97 DF, 3 = 30). */
    val mtcCode: Int,
) {
    FPS_24(24, false, 24.0, 0),
    FPS_25(25, false, 25.0, 1),
    FPS_2997_DF(30, true, 30000.0 / 1001.0, 2),
    FPS_30(30, false, 30.0, 3);

    /** Number of distinct frames in 24 hours, after which timecode wraps. */
    val framesPerDay: Int
        get() = if (dropFrame) 24 * 6 * FRAMES_PER_10_MIN_DF else 24 * 3600 * nominalFps

    override fun toString(): String = when (this) {
        FPS_24 -> "24"
        FPS_25 -> "25"
        FPS_2997_DF -> "29.97df"
        FPS_30 -> "30"
    }

    companion object {
        internal const val FRAMES_PER_10_MIN_DF = 17982
        internal const val FRAMES_PER_MIN_DF = 1798

        /** The rate with this MTC rate code. */
        fun fromMtcCode(code: Int): TimecodeRate = entries.first { it.mtcCode == (code and 3) }

        /** Parse "24", "25", "29.97", "29.97df", "30". */
        fun parse(text: String): TimecodeRate = when (text.trim().lowercase()) {
            "24" -> FPS_24
            "25" -> FPS_25
            "29.97", "29.97df", "2997", "df" -> FPS_2997_DF
            "30" -> FPS_30
            else -> throw IllegalArgumentException("unknown timecode rate '$text' (24, 25, 29.97df, 30)")
        }
    }
}

/**
 * A position on a SMPTE timecode clock. [frames] is the count of real frames since 00:00:00:00,
 * always in `0 until rate.framesPerDay`.
 */
data class Timecode(val frames: Int, val rate: TimecodeRate) : Comparable<Timecode> {

    init {
        require(frames in 0 until rate.framesPerDay) { "frame $frames outside one day at $rate" }
    }

    private val fields: IntArray by lazy { fieldsOf(frames, rate) }

    val hours: Int get() = fields[0]
    val minutes: Int get() = fields[1]
    val seconds: Int get() = fields[2]
    val frame: Int get() = fields[3]

    /** Wall-clock seconds since 00:00:00:00 (for 29.97 this is the real, not the labelled, time). */
    val totalSeconds: Double get() = frames / rate.fps

    /** Add [n] frames, wrapping at 24 hours. */
    operator fun plus(n: Int): Timecode = Timecode(Math.floorMod(frames + n, rate.framesPerDay), rate)

    /** Subtract [n] frames, wrapping at 24 hours. */
    operator fun minus(n: Int): Timecode = plus(-n)

    /** Frames from [other] to this one (no wrapping). */
    operator fun minus(other: Timecode): Int {
        require(other.rate == rate) { "cannot subtract $other from $this: different rates" }
        return frames - other.frames
    }

    override fun compareTo(other: Timecode): Int = frames.compareTo(other.frames)

    /** "HH:MM:SS:FF", or "HH:MM:SS;FF" for drop-frame. */
    override fun toString(): String {
        val sep = if (rate.dropFrame) ';' else ':'
        return "%02d:%02d:%02d%c%02d".format(hours, minutes, seconds, sep, frame)
    }

    companion object {
        /**
         * The timecode labelled [h]:[m]:[s]:[f]. Throws for labels that do not exist — including the
         * skipped drop-frame labels ;00 and ;01 at minutes not divisible by ten.
         */
        fun of(h: Int, m: Int, s: Int, f: Int, rate: TimecodeRate): Timecode {
            require(h in 0..23 && m in 0..59 && s in 0..59 && f in 0 until rate.nominalFps) {
                "invalid timecode %02d:%02d:%02d:%02d at $rate".format(h, m, s, f)
            }
            require(!(rate.dropFrame && s == 0 && f < 2 && m % 10 != 0)) {
                "%02d:%02d:%02d;%02d does not exist in drop-frame (;00 and ;01 are skipped)".format(h, m, s, f)
            }
            val totalMinutes = 60 * h + m
            var n = ((3600 * h + 60 * m + s) * rate.nominalFps) + f
            if (rate.dropFrame) n -= 2 * (totalMinutes - totalMinutes / 10)
            return Timecode(n, rate)
        }

        /** The frame nearest [seconds] of wall time (wrapping at 24 hours). */
        fun fromSeconds(seconds: Double, rate: TimecodeRate): Timecode =
            Timecode(Math.floorMod(Math.round(seconds * rate.fps), rate.framesPerDay.toLong()).toInt(), rate)

        /**
         * Parse "HH:MM:SS:FF" or "HH:MM:SS;FF". A ';' (or '.') before the frames means 29.97
         * drop-frame; otherwise [rate] is used (default 30).
         */
        fun parse(text: String, rate: TimecodeRate? = null): Timecode {
            val m = Regex("""^\s*(\d{1,2}):(\d{2}):(\d{2})([:;.,])(\d{2})\s*$""").matchEntire(text)
                ?: throw IllegalArgumentException("not a timecode: '$text' (want HH:MM:SS:FF or HH:MM:SS;FF)")
            val (h, mi, s, sep, f) = m.destructured
            val r = rate ?: if (sep == ";" || sep == "." || sep == ",") TimecodeRate.FPS_2997_DF else TimecodeRate.FPS_30
            return of(h.toInt(), mi.toInt(), s.toInt(), f.toInt(), r)
        }

        internal fun fieldsOf(frames: Int, rate: TimecodeRate): IntArray {
            var n = frames
            if (rate.dropFrame) {
                val d = n / TimecodeRate.FRAMES_PER_10_MIN_DF
                val r = n % TimecodeRate.FRAMES_PER_10_MIN_DF
                n += 18 * d + if (r > 1) 2 * ((r - 2) / TimecodeRate.FRAMES_PER_MIN_DF) else 0
            }
            val fps = rate.nominalFps
            return intArrayOf(n / (fps * 3600), (n / (fps * 60)) % 60, (n / fps) % 60, n % fps)
        }
    }
}
