package afield.timecode

import afield.four.synth.MidiInput

/**
 * A timecode clock a box can read: [now] answers "what timecode is it?" at any moment, from any
 * thread.
 *
 * It either generates time ([generate]: free-running from a start point) or follows a source and
 * freewheels between updates on the wall clock: decoded LTC ([followLtc], fed audio with
 * [feedLtc]) or MIDI Time Code ([followMtc]). Following anchors the clock on each received frame
 * and extrapolates at the measured [speed], so reading it at 60 Hz from a draw loop is smooth even
 * though LTC arrives at 24–30 frames a second and MTC in quarter frames.
 *
 * ```
 * val clock = TimecodeClock(TimecodeRate.FPS_25).generate(Timecode.parse("10:00:00:00", TimecodeRate.FPS_25))
 * println(clock.now())          // 10:00:00:00, then counting
 * ```
 *
 * The LTC decoder is not thread-safe: call [feedLtc] only from the one thread that owns the audio
 * buffers. [now], [seconds] and [locked] are safe anywhere.
 */
class TimecodeClock(val rate: TimecodeRate, private val nanoTime: () -> Long = System::nanoTime) {

    private class Anchor(val seconds: Double, val atNanos: Long, val speed: Double)

    @Volatile private var anchor: Anchor? = null
    @Volatile private var generating = false

    /** How long, in seconds, a followed clock keeps freewheeling after its last frame before [locked] goes false and it stops. */
    var freewheelSeconds: Double = 0.5

    /** Where the clock's time came from most recently: "generate", "ltc", "mtc" or "none". */
    @Volatile var source: String = "none"
        private set

    /** Playback speed of the source as last measured (1.0 = real time, negative = reverse). */
    val speed: Double get() = anchor?.speed ?: 0.0

    /** Start generating time from [start] at real speed. */
    fun generate(start: Timecode = Timecode(0, rate)): TimecodeClock {
        anchor = Anchor(start.totalSeconds, nanoTime(), 1.0)
        generating = true
        source = "generate"
        return this
    }

    /** Jump to [tc] keeping the current mode (a locate). */
    fun locate(tc: Timecode, speed: Double = this.speed.takeIf { it != 0.0 } ?: 1.0) {
        anchor = Anchor(tc.totalSeconds, nanoTime(), speed)
    }

    /** True while generating, or while a followed source has delivered a frame within [freewheelSeconds]. */
    val locked: Boolean
        get() {
            val a = anchor ?: return false
            return generating || (nanoTime() - a.atNanos) / 1e9 <= freewheelSeconds
        }

    /** Current time in seconds since 00:00:00:00 — the number an animation or keyframe timeline wants. */
    val seconds: Double
        get() {
            val a = anchor ?: return 0.0
            var elapsed = (nanoTime() - a.atNanos) / 1e9
            if (!generating) elapsed = elapsed.coerceAtMost(freewheelSeconds)
            return a.seconds + elapsed * a.speed
        }

    /** The current timecode (the frame containing [seconds]). */
    fun now(): Timecode = Timecode(
        Math.floorMod(kotlin.math.floor(seconds * rate.fps + 1e-6).toLong(), rate.framesPerDay.toLong()).toInt(), rate
    )

    // --- LTC ---------------------------------------------------------------------------------

    private var ltc: LtcDecoder? = null
    private var ltcSamplesFed = 0L
    private var ltcSampleRate = 48000

    /** The last LTC frame decoded, for display. */
    @Volatile var lastLtcFrame: LtcDecodedFrame? = null
        private set

    /** Follow LTC audio delivered to [feedLtc] at [sampleRate]. */
    fun followLtc(sampleRate: Int = 48000): TimecodeClock {
        ltc = LtcDecoder(sampleRate, rate)
        ltcSampleRate = sampleRate
        ltcSamplesFed = 0
        generating = false
        source = "ltc"
        return this
    }

    /**
     * Feed a block of LTC audio, as it arrives. A decoded frame anchors the clock at the moment its
     * last bit ended — which, playing forward, is the start of the next frame.
     */
    fun feedLtc(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): List<LtcDecodedFrame> {
        val decoder = ltc ?: followLtc(ltcSampleRate).ltc!!
        val now = nanoTime()
        val frames = decoder.feed(samples, offset, length)
        ltcSamplesFed += length
        frames.lastOrNull()?.let { f ->
            lastLtcFrame = f
            val behindNanos = ((ltcSamplesFed - f.sampleEnd) / ltcSampleRate * 1e9).toLong()
            val endSeconds = if (f.reverse) f.timecode.totalSeconds else (f.timecode.frames + 1) / rate.fps
            val sp = if (f.reverse) -f.speed else f.speed
            anchor = Anchor(endSeconds, now - behindNanos, sp)
            source = "ltc"
        }
        return frames
    }

    // --- MTC ---------------------------------------------------------------------------------

    /** Follow MTC from [decoder] (which you feed, or which [MidiInput.mtcDecoder] feeds). */
    fun followMtc(decoder: MtcDecoder): TimecodeClock {
        generating = false
        source = "mtc"
        decoder.onTimecode { tc -> anchor = Anchor(tc.totalSeconds, nanoTime(), if (decoder.reverse) -1.0 else 1.0) }
        return this
    }

    /** Follow MTC arriving on [input]. */
    fun followMtc(input: MidiInput): TimecodeClock = followMtc(input.mtcDecoder())

    override fun toString(): String = "TimecodeClock(${now()} @ $rate, source=$source, locked=$locked)"
}
