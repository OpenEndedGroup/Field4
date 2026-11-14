@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import kotlin.math.roundToInt

/**
 * A DX7 voice, as the FM-1's FM tracks play it: six operators, an algorithm, a feedback, an LFO,
 * a pitch envelope and a ten-character name, held as the DX7's 155 edit-buffer parameters.
 *
 * Voices come from the simulator's library ([FM1Sim.voices]), from a DX7 bank file ([bank]), or
 * from another voice edited or blended with [lerp]; [FM1Sim.voice] sends one to a track:
 *
 * ```kotlin
 * val fm1 = FM1Sim.shared
 * val piano = fm1.voices(0)[0]
 * val bell = piano.copy().apply {
 *     algorithm = 5
 *     op(2).coarse = 7        // operator 2 at seven times the pitch
 *     feedback = 6
 *     name = "BELLPIANO"
 * }
 * fm1.voice(0, piano.lerp(bell, 0.5))
 * ```
 *
 * Every parameter has the DX7's own range (levels and rates 0..99, coarse 0..31, ...). A value set
 * past it is held at its end, because the firmware would otherwise reject the whole voice; a NaN
 * is refused.
 *
 * @param params the 155 parameters in the DX7's VCED order: operator 6 first, 21 each, then the
 *   pitch envelope, algorithm (0-based), feedback, LFO, transpose and name
 */
class Dx7Voice(params: IntArray = IntArray(155)) {
    init {
        require(params.size == 155) { "a DX7 voice has 155 parameters, not ${params.size}" }
    }

    /** The 155 parameters, VCED order, each within its range. */
    val params: IntArray = IntArray(155) { params[it].coerceIn(0, max(it)) }

    companion object {
        private val OP_MAX = intArrayOf(99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 3, 3, 7, 3, 7, 99, 1, 31, 99, 14)
        private val GLOBAL_MAX = intArrayOf(99, 99, 99, 99, 99, 99, 99, 99, 31, 7, 1, 99, 99, 99, 99, 1, 5, 7, 48)

        /** The largest value parameter [index] (VCED order) takes. */
        fun max(index: Int): Int = when {
            index < 126 -> OP_MAX[index % 21]
            index < 145 -> GLOBAL_MAX[index - 126]
            else -> 127
        }

        /** A voice from the DX7's packed 128-byte form (VMEM), as a bank stores it, from [offset]. */
        fun fromVmem(b: ByteArray, offset: Int = 0): Dx7Voice {
            require(b.size >= offset + 128) { "a packed DX7 voice is 128 bytes" }
            fun u(i: Int) = b[offset + i].toInt() and 0x7f
            val p = IntArray(155)
            for (op in 0 until 6) {
                val v = op * 17; val q = op * 21
                for (i in 0 until 11) p[q + i] = u(v + i)
                p[q + 11] = u(v + 11) and 3; p[q + 12] = (u(v + 11) shr 2) and 3
                p[q + 13] = u(v + 12) and 7; p[q + 20] = (u(v + 12) shr 3) and 15
                p[q + 14] = u(v + 13) and 3; p[q + 15] = (u(v + 13) shr 2) and 7
                p[q + 16] = u(v + 14)
                p[q + 17] = u(v + 15) and 1; p[q + 18] = (u(v + 15) shr 1) and 31
                p[q + 19] = u(v + 16)
            }
            for (i in 0 until 8) p[126 + i] = u(102 + i)
            p[134] = u(110) and 31
            p[135] = u(111) and 7; p[136] = (u(111) shr 3) and 1
            for (i in 0 until 4) p[137 + i] = u(112 + i)
            p[141] = u(116) and 1; p[142] = (u(116) shr 1) and 7; p[143] = (u(116) shr 4) and 7
            for (i in 0 until 11) p[144 + i] = u(117 + i)
            return Dx7Voice(p)
        }

        /**
         * The 32 voices of a DX7 bank: a 4,104-byte bulk-dump `.syx` (`F0 43 0n 09 20 00`, the voice
         * data, a checksum, `F7`) or its 4,096 bytes of voice data alone.
         */
        fun bank(bytes: ByteArray): List<Dx7Voice> {
            val data = when {
                bytes.size == 4096 -> 0
                bytes.size >= 4104 && (bytes[0].toInt() and 0xff) == 0xF0 && bytes[1].toInt() == 0x43 &&
                    bytes[3].toInt() == 9 -> 6
                else -> throw IllegalArgumentException(
                    "not a DX7 32-voice bank: ${bytes.size} bytes (a bulk dump is 4104, its voice data 4096)"
                )
            }
            return (0 until 32).map { fromVmem(bytes, data + it * 128) }
        }

        /** A 32-voice bulk dump of [voices], as a `.syx` file holds it. */
        fun bankSysex(voices: List<Dx7Voice>, channel: Int = 0): ByteArray {
            require(voices.size == 32) { "a DX7 bank is 32 voices, not ${voices.size}" }
            val out = ByteArray(4104)
            out[0] = 0xF0.toByte(); out[1] = 0x43; out[2] = (channel and 15).toByte(); out[3] = 9; out[4] = 0x20; out[5] = 0
            var sum = 0
            voices.forEachIndexed { i, v ->
                val vm = v.toVmem()
                for (j in 0 until 128) { out[6 + i * 128 + j] = vm[j]; sum += vm[j] }
            }
            out[4102] = ((-sum) and 0x7f).toByte(); out[4103] = 0xF7.toByte()
            return out
        }
    }

    private fun set(index: Int, value: Int) { params[index] = value.coerceIn(0, max(index)) }

    /** One operator's parameters, 1..6 as the DX7 numbers them. */
    inner class Operator internal constructor(val number: Int) {
        private val base = (6 - number) * 21
        private operator fun get(i: Int) = params[base + i]
        private fun put(i: Int, v: Int) = set(base + i, v)

        /** Envelope rate [i] (0..3), 0..99. */
        fun rate(i: Int): Int = this[i]
        /** Set envelope rate [i] (0..3). */
        fun rate(i: Int, value: Int) = put(i, value)
        /** Envelope level [i] (0..3), 0..99. */
        fun egLevel(i: Int): Int = this[4 + i]
        /** Set envelope level [i] (0..3). */
        fun egLevel(i: Int, value: Int) = put(4 + i, value)

        /** Output level, 0..99: how loud a carrier is, how bright a modulator makes its carrier. */
        var level: Int get() = this[16]; set(v) = put(16, v)
        /** Frequency coarse, 0..31: the ratio to the note (0 means a half). */
        var coarse: Int get() = this[18]; set(v) = put(18, v)
        /** Frequency fine, 0..99. */
        var fine: Int get() = this[19]; set(v) = put(19, v)
        /** Detune, -7..+7. */
        var detune: Int get() = this[20] - 7; set(v) = put(20, v + 7)
        /** Fixed frequency instead of a ratio. */
        var fixed: Boolean get() = this[17] == 1; set(v) = put(17, if (v) 1 else 0)
        /** Key-velocity sensitivity, 0..7. */
        var velocity: Int get() = this[15]; set(v) = put(15, v)
        /** Amplitude-modulation sensitivity, 0..3. */
        var ampMod: Int get() = this[14]; set(v) = put(14, v)

        override fun toString(): String = "op$number(level=$level coarse=$coarse fine=$fine detune=$detune)"
    }

    /** Operator [n], 1..6. */
    fun op(n: Int): Operator {
        require(n in 1..6) { "a DX7 operator is 1..6, not $n" }
        return Operator(n)
    }

    /** The algorithm, 1..32 as the DX7 panel numbers them. */
    var algorithm: Int get() = params[134] + 1; set(v) = set(134, v - 1)
    /** Operator feedback, 0..7. */
    var feedback: Int get() = params[135]; set(v) = set(135, v)
    /** LFO speed, 0..99. */
    var lfoSpeed: Int get() = params[137]; set(v) = set(137, v)
    /** LFO pitch-modulation depth, 0..99. */
    var lfoPitchDepth: Int get() = params[139]; set(v) = set(139, v)
    /** LFO amplitude-modulation depth, 0..99. */
    var lfoAmpDepth: Int get() = params[140]; set(v) = set(140, v)
    /** LFO wave, 0..5: triangle, saw down, saw up, square, sine, sample and hold. */
    var lfoWave: Int get() = params[142]; set(v) = set(142, v)
    /** Transpose in semitones from C3, -24..+24. */
    var transpose: Int get() = params[144] - 24; set(v) = set(144, v + 24)

    /** The ten-character name. */
    var name: String
        get() = String(CharArray(10) { params[145 + it].toChar() }).trimEnd()
        set(v) { for (i in 0 until 10) set(145 + i, v.getOrElse(i) { ' ' }.code.coerceIn(32, 126)) }

    /** An independent copy. */
    fun copy(): Dx7Voice = Dx7Voice(params)

    /**
     * Every parameter [t] of the way from this voice to [other], rounded: 0 is this voice, 1 is
     * [other], and past either end carries on in the same direction until each parameter reaches
     * the end of its range. Switches (algorithm, waveforms, fixed frequency) move at the halfway
     * point, which is how a DX7 user would morph between two patches by hand.
     */
    fun lerp(other: Dx7Voice, t: Double): Dx7Voice {
        require(!t.isNaN()) { "lerp amount is NaN" }
        val switches = setOf(134, 136, 141, 142) + (0 until 6).flatMap { listOf(it * 21 + 11, it * 21 + 12, it * 21 + 17) }
        val out = IntArray(155) { i ->
            when {
                i >= 145 || i in switches -> if (t < 0.5) params[i] else other.params[i]
                params[i] == other.params[i] -> params[i]
                else -> {
                    val v = params[i] + (other.params[i] - params[i]) * t
                    if (v.isInfinite()) (if (v > 0) max(i) else 0) else v.roundToInt()
                }
            }
        }
        return Dx7Voice(out)
    }

    /** The packed 128-byte form a DX7 bank stores. */
    fun toVmem(): ByteArray {
        val p = params
        val b = ByteArray(128)
        for (op in 0 until 6) {
            val v = op * 17; val q = op * 21
            for (i in 0 until 11) b[v + i] = p[q + i].toByte()
            b[v + 11] = (p[q + 11] or (p[q + 12] shl 2)).toByte()
            b[v + 12] = (p[q + 13] or (p[q + 20] shl 3)).toByte()
            b[v + 13] = (p[q + 14] or (p[q + 15] shl 2)).toByte()
            b[v + 14] = p[q + 16].toByte()
            b[v + 15] = (p[q + 17] or (p[q + 18] shl 1)).toByte()
            b[v + 16] = p[q + 19].toByte()
        }
        for (i in 0 until 8) b[102 + i] = p[126 + i].toByte()
        b[110] = p[134].toByte()
        b[111] = (p[135] or (p[136] shl 3)).toByte()
        for (i in 0 until 4) b[112 + i] = p[137 + i].toByte()
        b[116] = (p[141] or (p[142] shl 1) or (p[143] shl 4)).toByte()
        for (i in 0 until 11) b[117 + i] = p[144 + i].toByte()
        return b
    }

    /** The DX7 single-voice SysEx for this voice on MIDI [channel] (0..15): 163 bytes. */
    fun toSysex(channel: Int = 0): ByteArray {
        require(channel in 0..15) { "a MIDI channel is 0..15, not $channel" }
        val out = ByteArray(163)
        out[0] = 0xF0.toByte(); out[1] = 0x43; out[2] = channel.toByte(); out[3] = 0; out[4] = 1; out[5] = 0x1B
        var sum = 0
        for (i in 0 until 155) { out[6 + i] = params[i].toByte(); sum += params[i] }
        out[161] = ((-sum) and 0x7f).toByte(); out[162] = 0xF7.toByte()
        return out
    }

    override fun equals(other: Any?): Boolean = other is Dx7Voice && other.params.contentEquals(params)
    override fun hashCode(): Int = params.contentHashCode()
    override fun toString(): String = "Dx7Voice(\"$name\" algorithm=$algorithm feedback=$feedback)"
}
