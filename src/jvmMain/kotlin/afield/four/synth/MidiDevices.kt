@file:afield.CompletionCategory("Audio")
package afield.four.synth

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

/**
 * Live MIDI device I/O, and offline rendering of a [MidiClip] to a WAV through the JDK's own
 * software synthesizer.
 *
 * [MidiDevices] enumerates what the JVM can see and opens a [MidiOutput] or [MidiInput] on it;
 * [MidiClip.playTo] sends a clip to a device by name fragment. [MidiClip.renderWav] and
 * [renderToWav] take the offline route instead, which needs no hardware at all.
 *
 * Everything here has to survive a machine with no MIDI hardware, so every entry point either
 * returns null or an empty list, or throws a message that names what was missing —
 * [midiRenderUnavailableReason] is that check on its own, for asking before committing to a
 * render. Nothing fakes a device.
 */

/**
 * One MIDI device the JVM can see.
 *
 * [canReceive] and [canTransmit] are the useful distinction, and they are not symmetric with the
 * names: a device Field can *send* to reports receivers, and a device Field can *listen* to reports
 * transmitters. macOS surfaces its IAC bus as two separate `MidiDevice.Info` entries with the
 * *same* name, one of each direction, so name alone does not identify a device.
 */
data class MidiDeviceHandle(
    val name: String,
    val vendor: String,
    val description: String,
    val version: String,
    /** True if Field can send messages to this device (it exposes receivers). */
    val canReceive: Boolean,
    /** True if Field can read messages from this device (it exposes transmitters). */
    val canTransmit: Boolean,
    /** The JDK implementation class, e.g. `SoftSynthesizer`, `MidiOutDevice`, `RealTimeSequencer`. */
    val implementation: String,
    internal val info: MidiDevice.Info
) {
    /** True for the JDK's bundled software synthesizer and sequencer, rather than real hardware. */
    val isSoftware: Boolean
        get() = implementation == "SoftSynthesizer" || implementation == "RealTimeSequencer"

    override fun toString(): String = buildString {
        append(name)
        append(" [")
        append(if (canReceive) "out" else "")
        append(if (canReceive && canTransmit) "/" else "")
        append(if (canTransmit) "in" else "")
        append("] ")
        append(vendor)
        append(" (")
        append(implementation)
        append(")")
    }
}

/** Enumeration and opening of live MIDI devices. */
object MidiDevices {

    /**
     * Every device `MidiSystem` reports, in its own order.
     *
     * A device whose info cannot be opened for inspection is skipped rather than thrown, because
     * one broken driver should not hide the rest of the list.
     */
    fun list(): List<MidiDeviceHandle> = MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
        try {
            val device = MidiSystem.getMidiDevice(info)
            MidiDeviceHandle(
                name = info.name,
                vendor = info.vendor,
                description = info.description,
                version = info.version,
                // maxReceivers/-Transmitters return -1 for "unlimited", 0 for "none".
                canReceive = device.maxReceivers != 0,
                canTransmit = device.maxTransmitters != 0,
                implementation = device.javaClass.simpleName,
                info = info
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Devices Field can send to. */
    fun outputs(): List<MidiDeviceHandle> = list().filter { it.canReceive }

    /** Devices Field can listen to. */
    fun inputs(): List<MidiDeviceHandle> = list().filter { it.canTransmit }

    /**
     * Devices that are real ports rather than the JDK's built-in synthesizer and sequencer.
     *
     * On this machine at the time of writing that is macOS's IAC "Bus 1", in both directions. On a
     * machine with nothing plugged in it is empty, which is the case every device test here has to
     * skip on.
     */
    fun hardware(): List<MidiDeviceHandle> = list().filterNot { it.isSoftware }

    /** A one-line-per-device report, for a box output or a test's skip reason. */
    fun describe(): String {
        val all = list()
        if (all.isEmpty()) return "no MIDI devices at all"
        return all.joinToString("\n") { "  $it" }
    }

    /** The first output whose name contains [nameFragment], case-insensitively. */
    fun findOutput(nameFragment: String): MidiDeviceHandle? =
        outputs().firstOrNull { it.name.contains(nameFragment, ignoreCase = true) }

    /** The first input whose name contains [nameFragment], case-insensitively. */
    fun findInput(nameFragment: String): MidiDeviceHandle? =
        inputs().firstOrNull { it.name.contains(nameFragment, ignoreCase = true) }

    /** Open [handle] for output. The caller owns the result and must close it. */
    fun openOutput(handle: MidiDeviceHandle): MidiOutput {
        require(handle.canReceive) { "${handle.name} has no receivers, so it cannot be opened for output" }
        val device = MidiSystem.getMidiDevice(handle.info)
        device.open()
        return MidiOutput(handle, device, device.receiver)
    }

    /** Open [handle] for input. The caller owns the result and must close it — but see [MidiInput]. */
    fun openInput(handle: MidiDeviceHandle): MidiInput {
        require(handle.canTransmit) { "${handle.name} has no transmitters, so it cannot be opened for input" }
        val device = MidiSystem.getMidiDevice(handle.info)
        device.open()
        return MidiInput(handle, device)
    }
}

/** An open MIDI output. Send notes with [noteOn]/[noteOff], or a whole clip with [play]. */
class MidiOutput internal constructor(
    val handle: MidiDeviceHandle,
    private val device: MidiDevice,
    private val receiver: Receiver
) : AutoCloseable {

    fun noteOn(pitch: Int, velocity: Int = 100, channel: Int = 0) {
        receiver.send(ShortMessage(ShortMessage.NOTE_ON, channel.coerceIn(0, 15), pitch.coerceIn(0, 127), velocity.coerceIn(0, 127)), -1)
    }

    fun noteOff(pitch: Int, channel: Int = 0) {
        receiver.send(ShortMessage(ShortMessage.NOTE_OFF, channel.coerceIn(0, 15), pitch.coerceIn(0, 127), 0), -1)
    }

    fun controlChange(controller: Int, value: Int, channel: Int = 0) {
        receiver.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel.coerceIn(0, 15), controller.coerceIn(0, 127), value.coerceIn(0, 127)), -1)
    }

    fun programChange(program: Int, channel: Int = 0) {
        receiver.send(ShortMessage(ShortMessage.PROGRAM_CHANGE, channel.coerceIn(0, 15), program.coerceIn(0, 127), 0), -1)
    }

    /** Send an all-notes-off on every channel. Worth doing before closing, or a note hangs. */
    fun allNotesOff() {
        for (channel in 0..15) {
            receiver.send(ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 123, 0), -1)
        }
    }

    /**
     * Play [clip] to this output in real time, blocking until it finishes.
     *
     * This is a plain sleep-and-send loop rather than a `Sequencer`, because a `Sequencer` needs to
     * be wired to a device and torn down, and for a short clip the added machinery buys nothing.
     * It is therefore not sample-accurate and not suitable as a musical clock — [MidiClip.play] is
     * for auditioning, and anything that needs real timing should drive a `Sequencer` directly.
     */
    fun play(clip: MidiClip) {
        data class Ev(val seconds: Double, val on: Boolean, val note: MidiNote)

        val events = clip.tracks.flatMap { it.notes }
            .flatMap { listOf(Ev(clip.beatsToSeconds(it.startBeats), true, it), Ev(clip.beatsToSeconds(it.endBeats), false, it)) }
            .sortedBy { it.seconds }

        val startedAt = System.nanoTime()
        for (event in events) {
            val targetNanos = startedAt + (event.seconds * 1_000_000_000L).toLong()
            val waitNanos = targetNanos - System.nanoTime()
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
            if (event.on) noteOn(event.note.pitch, event.note.velocity, event.note.channel)
            else noteOff(event.note.pitch, event.note.channel)
        }
    }

    override fun close() {
        try {
            allNotesOff()
        } catch (_: Throwable) {
            // Closing matters more than a clean release.
        }
        runCatching { receiver.close() }
        runCatching { device.close() }
    }
}

/**
 * An open MIDI input, usable as a Field control source. Poll [lastValue] for a controller, or
 * install a callback with [onMessage].
 *
 * **Operational hazard, measured rather than assumed.** Opening a CoreMIDI input device spawns a
 * *non-daemon* thread named `Java Sound MidiInDevice Thread`, and that thread survives both
 * `Transmitter.close()` and `MidiDevice.close()`. A JVM that has ever opened a MIDI input will
 * therefore not exit naturally — I confirmed this by listing non-daemon threads after closing both,
 * and the process still had to be killed after a 20-second wait. An output-only device closes
 * cleanly and the JVM exits normally.
 *
 * The consequence for this codebase: a `jvm-main` smoke test must not open a MIDI input, because
 * smoke tests are required to exit naturally. [MidiDeviceSmokeTest] enumerates and opens an
 * *output* only, and the loopback check that does open an input is a `jvmTest` (where Gradle's test
 * JVM is torn down for us) guarded to skip when no hardware port exists.
 */
class MidiInput internal constructor(
    val handle: MidiDeviceHandle,
    private val device: MidiDevice
) : AutoCloseable {

    private val transmitter = device.transmitter
    private val listeners = CopyOnWriteArrayList<(MidiMessage, Long) -> Unit>()

    /** Latest value seen per (channel, controller), 0..127. */
    val controllers: MutableMap<Pair<Int, Int>, Int> = java.util.concurrent.ConcurrentHashMap()

    /** Latest note-on seen per channel, as (pitch, velocity). */
    val lastNoteOn: MutableMap<Int, Pair<Int, Int>> = java.util.concurrent.ConcurrentHashMap()

    /** Count of messages seen since opening. Useful for asserting that a device really spoke. */
    @Volatile
    var messageCount: Long = 0L
        private set

    init {
        transmitter.receiver = object : Receiver {
            override fun send(message: MidiMessage, timeStamp: Long) {
                messageCount++
                if (message is ShortMessage) {
                    when (message.command) {
                        ShortMessage.CONTROL_CHANGE ->
                            controllers[message.channel to message.data1] = message.data2
                        ShortMessage.NOTE_ON ->
                            if (message.data2 > 0) lastNoteOn[message.channel] = message.data1 to message.data2
                    }
                }
                listeners.forEach { runCatching { it(message, timeStamp) } }
            }

            override fun close() {}
        }
    }

    /**
     * The current value of [controller] on [channel], normalised to 0..1 — the shape a knob on a
     * controller driving a box property wants. Returns [default] until the controller has moved
     * once, because MIDI controllers are stateless and report nothing until touched.
     */
    fun knob(controller: Int, channel: Int = 0, default: Double = 0.0): Double {
        val raw = controllers[channel to controller] ?: return default
        return raw / 127.0
    }

    /** Raw 0..127 value of [controller] on [channel], or null if untouched. */
    fun raw(controller: Int, channel: Int = 0): Int? = controllers[channel to controller]

    /** Add a message callback. Called on the Java Sound MIDI thread, so keep it short. */
    fun onMessage(block: (MidiMessage, Long) -> Unit) {
        listeners += block
    }

    override fun close() {
        listeners.clear()
        runCatching { transmitter.close() }
        runCatching { device.close() }
        // The `Java Sound MidiInDevice Thread` outlives this call. See the class doc.
    }
}

/** Result of [renderToWav]. */
data class MidiRenderResult(
    val file: File,
    val seconds: Double,
    val sampleRate: Int,
    /** Peak absolute sample amplitude, 0.0..1.0. A silent render is 0.0. */
    val peakAmplitude: Double,
    /** Root-mean-square amplitude, 0.0..1.0. */
    val rmsAmplitude: Double,
    val soundbankName: String?
) {
    val isSilent: Boolean get() = peakAmplitude <= 0.0
}

/**
 * Whether this JVM can render MIDI to audio offline, and if not, why not.
 *
 * Returns null when rendering is available, or a human-readable reason when it is not. Callers
 * should treat a non-null result as a *skip* reason, not a failure: an offline render needs
 * `com.sun.media.sound.AudioSynthesizer`, which is the JDK's internal interface behind
 * `MidiSystem.getSynthesizer()`. `java.desktop` does not export that package, so it is reachable
 * only when the JVM was started with
 * `--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED`.
 *
 * This is checked at run time rather than assumed, because whether the flag is present depends on
 * how the JVM was launched, and Field's `requiredRunJvmArgs()` does not currently pass it.
 */
fun midiRenderUnavailableReason(): String? {
    val synthClass = try {
        Class.forName("com.sun.media.sound.AudioSynthesizer")
    } catch (t: Throwable) {
        return "com.sun.media.sound.AudioSynthesizer is not present in this JVM (${t.javaClass.simpleName})"
    }
    val synth = try {
        MidiSystem.getSynthesizer()
    } catch (t: Throwable) {
        return "MidiSystem.getSynthesizer() failed: ${t.message ?: t.javaClass.simpleName}"
    }
    if (!synthClass.isInstance(synth)) {
        return "the default synthesizer is ${synth.javaClass.name}, which is not an AudioSynthesizer"
    }
    return try {
        // Resolving the method is not enough — the access check happens on invoke, and that is the
        // check that actually fails without the --add-exports, with an IllegalAccessException.
        val probe = synthClass.getMethod("openStream", AudioFormat::class.java, Map::class.java)
        val stream = probe.invoke(synth, WAV_FORMAT, null) as AudioInputStream
        stream.close()
        synth.close()
        null
    } catch (t: Throwable) {
        val cause = t.cause ?: t
        "openStream is not accessible: ${cause.javaClass.simpleName}. " +
            "Relaunch with --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED"
    }
}

private val WAV_FORMAT = AudioFormat(44100f, 16, 2, true, false)

/**
 * Render [clip] to a WAV at [file] using the JDK's Gervill software synthesizer, offline and faster
 * than real time. Returns the measured result, or throws with a stated reason if rendering is
 * unavailable — check [midiRenderUnavailableReason] first if you want to skip instead.
 *
 * The returned [MidiRenderResult] carries the measured peak and RMS amplitude of what was actually
 * written, because "the file exists" and "the file has audio in it" are different claims and file
 * size cannot distinguish them: a WAV of pure silence is exactly as large as a loud one.
 */
fun renderToWav(
    clip: MidiClip,
    file: File,
    tailSeconds: Double = 1.0,
    program: Int? = null
): MidiRenderResult {
    midiRenderUnavailableReason()?.let { error("cannot render MIDI to audio: $it") }

    val synthClass = Class.forName("com.sun.media.sound.AudioSynthesizer")
    val synth: Synthesizer = MidiSystem.getSynthesizer()
    val openStream = synthClass.getMethod("openStream", AudioFormat::class.java, Map::class.java)
    val stream = openStream.invoke(synth, WAV_FORMAT, null) as AudioInputStream
    val soundbank = runCatching { synth.defaultSoundbank?.name }.getOrNull()

    try {
        val receiver = synth.receiver
        // Load the sequence into the synth's own event queue with real timestamps, in microseconds.
        // Sending with timestamp -1 would fire everything immediately and the render would be a
        // single chord.
        clip.tracks.forEach { track ->
            val channel = track.notes.firstOrNull()?.channel ?: 0
            (program ?: track.program)?.let {
                receiver.send(ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, it.coerceIn(0, 127), 0), 0L)
            }
            track.notes.forEach { note ->
                val onMicros = (clip.beatsToSeconds(note.startBeats) * 1_000_000L).toLong()
                val offMicros = (clip.beatsToSeconds(note.endBeats) * 1_000_000L).toLong()
                receiver.send(
                    ShortMessage(ShortMessage.NOTE_ON, note.channel, note.pitch, note.velocity),
                    onMicros
                )
                receiver.send(ShortMessage(ShortMessage.NOTE_OFF, note.channel, note.pitch, 0), offMicros)
            }
        }

        val seconds = clip.durationSeconds + tailSeconds
        val frames = (seconds * WAV_FORMAT.sampleRate).toLong()
        val byteCount = frames * WAV_FORMAT.frameSize

        file.absoluteFile.parentFile?.mkdirs()
        val bytes = ByteArray(byteCount.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = stream.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }

        AudioSystem.write(
            AudioInputStream(bytes.inputStream(), WAV_FORMAT, (read / WAV_FORMAT.frameSize).toLong()),
            AudioFileFormat.Type.WAVE,
            file
        )

        var peak = 0
        var sumSquares = 0.0
        var samples = 0L
        var i = 0
        while (i + 1 < read) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            peak = maxOf(peak, abs(sample))
            sumSquares += sample.toDouble() * sample
            samples++
            i += 2
        }

        return MidiRenderResult(
            file = file,
            seconds = read.toDouble() / WAV_FORMAT.frameSize / WAV_FORMAT.sampleRate,
            sampleRate = WAV_FORMAT.sampleRate.toInt(),
            peakAmplitude = peak / 32768.0,
            rmsAmplitude = if (samples == 0L) 0.0 else Math.sqrt(sumSquares / samples) / 32768.0,
            soundbankName = soundbank
        )
    } finally {
        runCatching { stream.close() }
        runCatching { synth.close() }
    }
}

/** Render this clip to a WAV. See [renderToWav]. */
fun MidiClip.renderWav(file: File, tailSeconds: Double = 1.0, program: Int? = null): MidiRenderResult =
    renderToWav(this, file, tailSeconds, program)

/** Play this clip to a named MIDI output, opening and closing it around the play. */
fun MidiClip.playTo(nameFragment: String) {
    val handle = MidiDevices.findOutput(nameFragment)
        ?: error("no MIDI output matching \"$nameFragment\". Available:\n${MidiDevices.describe()}")
    MidiDevices.openOutput(handle).use { it.play(this) }
}

/** This clip as a `javax.sound.midi.Sequence`, for callers that want to drive a `Sequencer`. */
fun MidiClip.asSequence(): Sequence = toSequence()
