@file:afield.CompletionCategory("Audio")
package afield.four.synth

import kotlin.math.sqrt

/**
 * Band energy from audio that is already playing: a bank of band-pass filters, and the RMS of what
 * each one passes.
 *
 * [BandSplit] is the bank and [logBands] builds one with log-spaced centres. Both read an
 * [AudioSnapshot], a copy of what the audio path last produced, so a measurement never renders a
 * synth graph a second time. What comes back is one `Double` per band in the order the centres were
 * given, which is the shape a frame's worth of code can hand to a shader or a particle force.
 *
 *     val launch = patSynth(stack(pat("bd*4"), pat("hh*16")), cps = 2.0)
 *     val split = logBands(4, lowHz = 70.0, highHz = 8000.0, sampleRate = launch.bank.sampleRate)
 *     val perFrame = launch.bank.sampleRate / 60
 *     frames {
 *         while (frame()) {
 *             launch.advanceOffline(perFrame)
 *             val energy = split.measure(launch.tap.snapshot())
 *             // energy[0] is the lowest band, and hears the kick; energy[3] is the highest, and
 *             // hears the hats
 *         }
 *     }
 *
 * Something has to be pulling the bank, or the tap stays empty and every band reads zero. Above the
 * box pulls it, which measures without sounding and does not depend on there being an output
 * device; returning the launch as the box's last expression hands it to the audio engine instead,
 * which pulls it when there is a device to play it through. Doing both advances the voices twice.
 *
 * Per band the reading is the RMS of the filtered window's second half, the first half being the
 * filters' settling. The filters keep their state between calls, so successive overlapping
 * snapshots read as a smoothed series rather than as independent measurements.
 */

/**
 * How loud each band is, measured by pushing a window of samples through Field's own [Biquad]s.
 *
 * The thing this exists to make easy is the step between "audio is playing" and "something on screen
 * moves in time with it". [AudioSnapshot] gives a window on the recent past, published from the
 * audio render path; this turns that window into a handful of numbers a frame's worth of code can
 * act on. The file overview above has the worked loop.
 *
 * # The filters belong to the analysis, not to the graph
 *
 * `afield.four.synth`'s nodes are audio-thread-owned: a [Signal] caches per [SynthContext] and per
 * cycle, but many of them also carry mutable DSP state past that cache, so rendering one graph from
 * two threads can corrupt it. The band-passes here are constructed by and reachable only from this
 * object, and their input is a buffer this object owns, so measuring costs the audio graph nothing
 * and there is no second renderer of anything the engine is rendering.
 *
 * # What the number is
 *
 * Per band, the RMS of the filtered window over its second half. The first half is the filter's
 * settling, and dropping it is what keeps a measurement from depending on where in the ring the
 * window happened to start.
 *
 * The filters keep their state between calls, so successive overlapping snapshots read as a smoothed
 * series rather than as independent measurements. At a default 4096-sample window and a 60 Hz frame
 * rate that overlap is most of the window, which is the behaviour you want for driving a display and
 * the wrong one for a spectrum table.
 */
class BandSplit(
    /** Band centre frequencies in Hz, low to high. */
    val centresHz: DoubleArray,
    /** Filter Q. Around 1.5 gives bands that overlap a little rather than leaving gaps. */
    val q: Double = 1.6,
    /**
     * Sample rate the filters are built for, in Hz. It has to be the rate the measured audio was
     * produced at, since it sets both the filter coefficients and the Nyquist bound every centre in
     * [centresHz] is checked against.
     */
    val sampleRate: Int = 48_000,
    /** Longest window [measure] will accept. */
    val capacity: Int = 8192,
) {
    init {
        require(centresHz.isNotEmpty()) { "BandSplit needs at least one band" }
        require(centresHz.all { it > 0.0 && it < sampleRate / 2.0 }) {
            "every centre must be in (0, ${sampleRate / 2}) Hz, got ${centresHz.toList()}"
        }
        require(capacity in 64..(1 shl 20)) { "capacity must be in 64..1048576, got $capacity" }
    }

    /** A [Signal] over a buffer this object refills, so the filter chain is built once. */
    private class Window : Signal() {
        var buffer: FloatArray = FloatArray(0)
        var length: Int = 0
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            for (i in 0 until numFrames) output[i] = if (i < length) buffer[i] else 0f
        }
    }

    /** How many bands there are: [centresHz]'s length, and the length of every [measure] result. */
    val bands: Int get() = centresHz.size

    private val windows = Array(bands) { Window() }
    private val filters = Array(bands) { b ->
        Biquad(
            type = BiquadType.BPF,
            cutoff = Param(centresHz[b].toFloat()),
            q = Param(q.toFloat()),
        ).also { it.input = windows[b] }
    }
    private val context = SynthContext(sampleRate.toFloat(), capacity)
    private val scratch = FloatArray(capacity)
    private var cycle = 0L

    /** Per-band RMS of [snapshot]'s left channel, or of its mono sum when it is stereo. */
    fun measure(snapshot: AudioSnapshot): DoubleArray {
        val right = snapshot.right
        if (right == null) return measure(snapshot.left, snapshot.left.size)
        val n = snapshot.frameCount
        val mono = FloatArray(n) { 0.5f * (snapshot.left[it] + right[it]) }
        return measure(mono, n)
    }

    /** Per-band RMS of the first [length] samples of [samples]. */
    fun measure(samples: FloatArray, length: Int = samples.size): DoubleArray {
        val n = length.coerceIn(0, minOf(samples.size, capacity))
        val out = DoubleArray(bands)
        if (n == 0) return out
        // One cycle id for the whole measurement, bumped once per call, so each band's filter runs
        // exactly once over the window rather than replaying a cache from the previous call.
        context.cycleId = ++cycle
        val from = n / 2
        for (b in 0 until bands) {
            windows[b].buffer = samples
            windows[b].length = n
            filters[b].render(scratch, n, context)
            var sum = 0.0
            for (i in from until n) {
                val v = scratch[i].toDouble()
                sum += v * v
            }
            out[b] = sqrt(sum / (n - from).coerceAtLeast(1))
        }
        return out
    }

    /** One line naming the band count, the centre frequencies and the Q. */
    override fun toString(): String = synthPrettyObject(
        this, "bands" to bands, "centresHz" to centresHz.toList(), "q" to q,
    )
}

/**
 * [BandSplit] centres spread logarithmically over [lowHz] to [highHz].
 *
 * Equal spacing in Hz puts almost every band above the top of a kick drum, which is why this is the
 * convenience and a linear one is not.
 */
fun logBands(
    count: Int,
    lowHz: Double = 60.0,
    highHz: Double = 9000.0,
    q: Double = 1.6,
    sampleRate: Int = 48_000,
): BandSplit {
    require(count >= 1) { "count must be >= 1, got $count" }
    require(lowHz > 0.0 && highHz > lowHz) { "need 0 < lowHz < highHz, got $lowHz and $highHz" }
    val ratio = highHz / lowHz
    val centres = DoubleArray(count) {
        if (count == 1) sqrt(lowHz * highHz) else lowHz * Math.pow(ratio, it.toDouble() / (count - 1))
    }
    return BandSplit(centres, q = q, sampleRate = sampleRate)
}
