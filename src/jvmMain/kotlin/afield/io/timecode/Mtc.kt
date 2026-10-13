package afield.io.timecode

import afield.audio.synth.MidiInput
import afield.audio.synth.MidiOutput
import javax.sound.midi.MidiMessage
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage

/**
 * MIDI Time Code: SMPTE timecode carried over MIDI.
 *
 * While running, a sender emits eight quarter-frame messages (`F1 0n`) per two frames, each
 * carrying one nibble of the time; a receiver needs all eight to know the time, so a decoded time
 * is two frames old by the time it is complete ([MtcDecoder] adds them back). When the time jumps
 * (locate, stop), a sender emits one full-frame SysEx `F0 7F 7F 01 01 hh mm ss ff F7` instead.
 */
object Mtc {
    /** The eight quarter-frame data bytes (piece << 4 | nibble) describing [tc]. */
    fun quarterFrames(tc: Timecode): IntArray {
        val values = intArrayOf(
            tc.frame and 0xF, tc.frame shr 4,
            tc.seconds and 0xF, tc.seconds shr 4,
            tc.minutes and 0xF, tc.minutes shr 4,
            tc.hours and 0xF, (tc.hours shr 4) or (tc.rate.mtcCode shl 1),
        )
        return IntArray(8) { (it shl 4) or (values[it] and 0xF) }
    }

    /** The full-frame SysEx bytes for [tc], `device` 0x7F meaning "all devices". */
    fun fullFrame(tc: Timecode, device: Int = 0x7F): ByteArray = byteArrayOf(
        0xF0.toByte(), 0x7F, device.toByte(), 0x01, 0x01,
        ((tc.rate.mtcCode shl 5) or tc.hours).toByte(), tc.minutes.toByte(), tc.seconds.toByte(), tc.frame.toByte(),
        0xF7.toByte(),
    )

    /** Parse a full-frame SysEx (with or without the leading F0). Returns null if it is not one. */
    fun parseFullFrame(bytes: ByteArray): Timecode? {
        val b = if (bytes.isNotEmpty() && bytes[0] == 0xF0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        if (b.size < 8 || b[0] != 0x7F.toByte() || b[2] != 0x01.toByte() || b[3] != 0x01.toByte()) return null
        val hh = b[4].toInt() and 0x7F
        val rate = TimecodeRate.fromMtcCode(hh shr 5)
        return runCatching { Timecode.of(hh and 0x1F, b[5].toInt(), b[6].toInt(), b[7].toInt(), rate) }.getOrNull()
    }

    /** [quarterFrames] as Java Sound messages. */
    fun quarterFrameMessages(tc: Timecode): List<ShortMessage> =
        quarterFrames(tc).map { ShortMessage(ShortMessage.MIDI_TIME_CODE, it, 0) }

    /** [fullFrame] as a Java Sound message. */
    fun fullFrameMessage(tc: Timecode): SysexMessage {
        val bytes = fullFrame(tc)
        return SysexMessage(bytes, bytes.size)
    }
}

/**
 * Reassembles MTC into timecode. Hand it every incoming MIDI message with [accept] (or [acceptBytes]);
 * it ignores anything that is not MTC. [current] is the most recent time and [onTimecode] fires for
 * every full frame and for every completed quarter-frame cycle (forward or backward).
 */
class MtcDecoder {
    private val nibbles = IntArray(8)
    private var lastPiece = -1
    private var seen = 0
    private var direction = 0

    /** The latest time decoded, or null before the first. */
    @Volatile
    var current: Timecode? = null
        private set

    /** True if the latest quarter frames ran backwards. */
    @Volatile
    var reverse: Boolean = false
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Timecode) -> Unit>()

    /** Called with each decoded time, on whichever thread delivered the MIDI. */
    fun onTimecode(block: (Timecode) -> Unit) { listeners += block }

    fun accept(message: MidiMessage) = acceptBytes(message.message.copyOf(message.length))

    /** Accept raw MIDI bytes: `F1 dd` or a full-frame SysEx. */
    fun acceptBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        when (bytes[0].toInt() and 0xFF) {
            0xF1 -> if (bytes.size >= 2) quarter(bytes[1].toInt() and 0x7F)
            0xF0 -> Mtc.parseFullFrame(bytes)?.let { seen = 0; lastPiece = -1; publish(it) }
        }
    }

    /** Accept one quarter-frame data byte (piece << 4 | nibble). */
    fun quarter(data: Int) {
        val piece = (data shr 4) and 7
        val dir = when (piece) {
            (lastPiece + 1) and 7 -> 1
            (lastPiece + 7) and 7 -> -1
            else -> 0
        }
        // A piece out of sequence starts a new run; a run only completes after eight in a row.
        if (dir == 0 || (direction != 0 && dir != direction)) seen = 0
        direction = dir
        lastPiece = piece
        nibbles[piece] = data and 0xF
        seen++
        val complete = (direction == 1 && piece == 7) || (direction == -1 && piece == 0)
        if (complete && seen >= 8) {
            val rate = TimecodeRate.fromMtcCode(nibbles[7] shr 1)
            val f = nibbles[0] or (nibbles[1] shl 4)
            val s = nibbles[2] or (nibbles[3] shl 4)
            val m = nibbles[4] or (nibbles[5] shl 4)
            val h = nibbles[6] or ((nibbles[7] and 1) shl 4)
            val tc = runCatching { Timecode.of(h, m, s, f, rate) }.getOrNull() ?: return
            reverse = direction == -1
            // The cycle described the frame at which it started; it is now two frames later.
            publish(if (reverse) tc - 2 else tc + 2)
        }
    }

    private fun publish(tc: Timecode) {
        current = tc
        listeners.forEach { runCatching { it(tc) } }
    }
}

/** Send [tc] as a full-frame SysEx (a locate). */
fun MidiOutput.sendMtcFullFrame(tc: Timecode) = send(Mtc.fullFrameMessage(tc))

/** Send the eight quarter frames for [tc]. Call once every two frames while running. */
fun MidiOutput.sendMtcQuarterFrames(tc: Timecode) = Mtc.quarterFrameMessages(tc).forEach { send(it) }

/** Decode MTC arriving on this input. */
fun MidiInput.mtcDecoder(): MtcDecoder {
    val decoder = MtcDecoder()
    onMessage { message, _ -> decoder.accept(message) }
    return decoder
}
