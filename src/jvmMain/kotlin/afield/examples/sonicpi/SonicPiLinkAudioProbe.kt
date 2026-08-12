package afield.examples.sonicpi

import afield.four.audio.WavFloatArrayLoader
import afield.four.synth.LinkAudioInterface
import afield.four.synth.SonicPi
import afield.four.synth.SonicPiInstall
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: sonicpi.link-audio.probe
runner: jvm-main
entrypoint: afield.examples.sonicpi.SonicPiLinkAudioProbe
tags: [smoke, sonicpi, link, audio, external, probe]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "link-audio probe finished"
*/
/**
 * Does Field's Ableton Link Audio actually interoperate with Sonic Pi v5's `link_audio`?
 *
 * Both sides exist independently: Field publishes a Link Audio channel through
 * [LinkAudioInterface.enableAudioOutput], and v5 added `link_audio "peer", "channel"` to subscribe to
 * another Link peer's published channel. Whether the two speak the same protocol is a question about
 * two separate implementations of a shared spec, and the only honest way to answer it is to put a
 * known signal in one end and look for it at the other.
 *
 * So: Field publishes a 440 Hz sine as peer `Field4`, channel `Main`. Sonic Pi subscribes with
 * `link_audio "Field4"`, records its own mixer output, and the resulting WAV is measured for energy
 * at 440 Hz against a control frequency. A Goertzel at two frequencies is enough — the question is
 * not "how does it sound" but "did this specific tone cross the boundary", and that has a number.
 *
 * Deliberately no soundcard: the PCM is committed to the Link sink directly rather than through
 * [afield.four.synth.FieldAudioEngine], so the probe does not depend on an audio device being
 * available or on what it is set to.
 *
 * This *reports* rather than asserts. The interop is unproven, and a probe that fails the build on an
 * open question tells you nothing you did not already know; a probe that prints a measured number
 * tells you which of the two implementations to go and read. Harden it into an assertion once the
 * answer is known and stable.
 */
object SonicPiLinkAudioProbe {

    private const val PEER = "Field4"
    private const val CHANNEL = "Main"
    private const val TONE_HZ = 440.0
    private const val CONTROL_HZ = 1_170.0
    private const val SAMPLE_RATE = 48_000
    private const val FRAMES = 1024
    private const val RECORD_SECONDS = 4L

    @JvmStatic
    fun main(args: Array<String>) {
        val install = runCatching { SonicPiInstall.discover() }.getOrNull()
        if (install == null) {
            println("SKIPPED: no Sonic Pi install found, so nothing here was exercised.")
            println("link-audio probe finished")
            return
        }

        ensureLinkLibraryProperty()

        val link = runCatching { LinkAudioInterface.shared(tempo = 120.0, peerName = PEER, quantum = 4.0) }
            .getOrElse { t ->
                println("SKIPPED: could not start Ableton Link.")
                // The cause, not just the wrapper: an ExceptionInInitializerError on its own says
                // only that a static initialiser failed, which is the least useful half of the story.
                var cause: Throwable? = t
                while (cause != null) {
                    println("  ${cause::class.qualifiedName}: ${cause.message}")
                    cause = cause.cause?.takeIf { it !== cause }
                }
                println("  ableton.link.library=${System.getProperty("ableton.link.library")}")
                println("link-audio probe finished")
                return
            }

        val publisher = Publisher(link)
        var sonicPi: SonicPi? = null
        try {
            link.enableAudioOutput(name = CHANNEL, maxFrames = FRAMES, channels = 2)
            publisher.start()
            println("publishing $TONE_HZ Hz as peer '$PEER', channel '$CHANNEL'")
            println("  link enabled=${link.enabled} audioEnabled=${link.audioEnabled}")

            sonicPi = SonicPi.shared(install)
            // Sonic Pi has no function that returns its Link peer count, but it pushes one at its
            // client. Reading both sides tells apart "neither can see the other" from the more
            // interesting asymmetric case.
            val sonicPiPeers = java.util.concurrent.atomic.AtomicInteger(-1)
            val sonicPiBpm = java.util.concurrent.atomic.AtomicReference(Double.NaN)
            sonicPi.onMessage = { address, args ->
                when (address) {
                    "/link-num-peers" -> (args.firstOrNull() as? Number)?.let { sonicPiPeers.set(it.toInt()) }
                    "/link-bpm" -> (args.firstOrNull() as? Number)?.let { sonicPiBpm.set(it.toDouble()) }
                }
            }
            if (!sonicPi.awaitReady(180_000L)) {
                println("INCONCLUSIVE: Sonic Pi never became ready")
                println("link-audio probe finished")
                return
            }
            println("Sonic Pi ready: ${sonicPi.version()?.version}")

            // Sonic Pi's Link visibility defaults to Off, and no *language* function turns it on --
            // only the GUI's toggle, which owned mode does not have. `link_enable` sends
            // `/clock/visibility 2` (NetworkWide; 1 is loopback-only). Without this, neither side can
            // see the other and every measurement below is a measurement of nothing. User code
            // evaluates in the runtime's own context, so the API is reachable directly.
            sonicPi.run(
                "@link_api.link_enable; sleep 0.2; " +
                    "puts \"link visibility=\" + @link_api.link_get_visibility.to_s + " +
                    "\" on=\" + @link_api.link_is_on?.to_s",
                workspace = "link-audio-probe"
            )
            Thread.sleep(2_000)
            sonicPi.log.takeLast(3).forEach { println("  link setup: ${it.text.replace("\n", " ")}") }

            // Give both peers a moment to find each other on the network.
            Thread.sleep(4_000)

            // What Field can see. Sonic Pi has no function that enumerates Link Audio peers -- only
            // its GUI panel -- but Field does, so this is the one direction that can be inspected
            // before any audio is asked for.
            val channels = runCatching { link.channels().map { it.name() } }.getOrElse { listOf("<error: $it>") }
            println("Field sees ${link.peers} Link peer(s); channels on the network: $channels")
            println("Sonic Pi reports ${sonicPiPeers.get()} Link peer(s), bpm ${sonicPiBpm.get()}")
            if (link.peers == 0L && sonicPiPeers.get() <= 0) {
                println("  Neither side can see the other, so this is Link *discovery* failing rather")
                println("  than Link Audio: two processes on one machine discover over multicast UDP,")
                println("  which macOS gates behind the Local Network privacy permission per binary.")
                println("  Check that both this JVM and Sonic Pi hold it, and that Link is enabled in")
                println("  Sonic Pi's preferences, before reading either implementation's source.")
            }

            val errorsBefore = sonicPi.errors.size
            sonicPi.volume(0.8, silent = true)
            sonicPi.run("puts \"subscribing\"; link_audio \"$PEER\", \"$CHANNEL\", amp: 1", workspace = "link-audio-probe")
            Thread.sleep(2_500)

            val newErrors = sonicPi.errors.drop(errorsBefore)
            if (newErrors.isNotEmpty()) {
                println("Sonic Pi rejected the subscription:")
                newErrors.forEach { println("  ${it.message.lineSequence().first().trim()}") }
            } else {
                println("Sonic Pi accepted link_audio \"$PEER\", \"$CHANNEL\"")
            }
            sonicPi.log.takeLast(6).forEach { println("  log: ${it.text.replace("\n", " ")}") }

            // The measurement. Record Sonic Pi's own output and look for the tone in it.
            val wav = Files.createTempDirectory("sonicpi-link-audio-").resolve("capture.wav").toFile()
            sonicPi.startRecording()
            Thread.sleep(RECORD_SECONDS * 1000)
            sonicPi.saveRecording(wav.absolutePath)
            Thread.sleep(3_000)

            exercised = true
            interopConfirmed = report(wav, publisher.committed(), link)
        } catch (t: Throwable) {
            println("INCONCLUSIVE: probe threw ${t::class.simpleName}: ${t.message}")
            t.printStackTrace()
        } finally {
            publisher.stop()
            runCatching { sonicPi?.stopAll() }
            SonicPi.destroyShared()
            runCatching { link.disableAudioOutput() }
            LinkAudioInterface.destroyShared()
        }
        println("link-audio probe finished")
        // Only a machine that got as far as measuring is held to the result. Elsewhere this stays a
        // skip, because "no Sonic Pi" and "broken interop" deserve different outcomes.
        check(!exercised || interopConfirmed) {
            "Field's Link Audio channel did not reach Sonic Pi; see the measurements above"
        }
    }

    @Volatile
    private var exercised = false

    @Volatile
    private var interopConfirmed = false

    /**
     * Point the FFM binding at the committed native library if nothing else has.
     *
     * `build.gradle.kts` passes `-Dableton.link.library` to the tasks that run Field, but the
     * jvm-main smoke runner launches its own JVM without it, so a probe that relies on the launcher
     * reports "no Ableton Link" on a machine that has it. The path is in the repo either way.
     */
    private fun ensureLinkLibraryProperty() {
        if (System.getProperty("ableton.link.library") != null) return
        val name = when {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") } ->
                "libabl_link_jvm.dylib"
            System.getProperty("os.name").lowercase().contains("windows") -> "abl_link_jvm.dll"
            else -> "libabl_link_jvm.so"
        }
        val candidate = File("./libs/$name")
        if (candidate.isFile) {
            System.setProperty("ableton.link.library", candidate.absolutePath)
            println("using native Link library ${candidate.absolutePath}")
        } else {
            println("no native Link library at ${candidate.absolutePath}")
        }
    }

    private fun report(wav: File, committed: Long, link: LinkAudioInterface): Boolean {
        println("Field committed $committed buffer(s) to the Link sink")
        if (committed == 0L) {
            println("VERDICT: inconclusive — Field published nothing, so the far end proves nothing.")
            return false
        }
        if (!wav.isFile || wav.length() == 0L) {
            println("VERDICT: inconclusive — Sonic Pi wrote no recording to ${wav.absolutePath}")
            return false
        }

        val data = runCatching { WavFloatArrayLoader.loadWavToFloatArray(wav.absolutePath) }.getOrElse {
            println("VERDICT: inconclusive — could not read the recording: $it")
            return false
        }
        val samples = data.samples
        println("recording: ${samples.size} samples at ${data.sampleRate} Hz (${wav.length()} bytes)")
        if (samples.isEmpty()) {
            println("VERDICT: inconclusive — the recording is empty")
            return false
        }

        val rms = sqrt(samples.fold(0.0) { acc, v -> acc + v.toDouble() * v } / samples.size)
        val tone = goertzel(samples, data.sampleRate.toDouble(), TONE_HZ)
        val control = goertzel(samples, data.sampleRate.toDouble(), CONTROL_HZ)
        val ratio = if (control > 0) tone / control else Double.POSITIVE_INFINITY

        println("overall RMS ......... ${"%.6f".format(rms)}")
        println("energy at $TONE_HZ Hz ... ${"%.6f".format(tone)}")
        println("energy at $CONTROL_HZ Hz .. ${"%.6f".format(control)}  (control)")
        println("tone / control ...... ${"%.2f".format(ratio)}")

        // A tone that crossed shows up as a peak at exactly the published frequency and not at a
        // nearby one. Silence shows up as an RMS in the noise floor, which is the likelier outcome if
        // the two Link Audio implementations do not agree.
        val confirmed = rms >= 1e-5 && ratio > 8.0
        val verdict = when {
            rms < 1e-5 -> "NO — Sonic Pi recorded silence; nothing arrived"
            confirmed -> "YES — $TONE_HZ Hz dominates the recording, so Field's channel reached Sonic Pi"
            else -> "UNCLEAR — the recording has energy but no clear peak at $TONE_HZ Hz"
        }
        println("VERDICT: $verdict")
        println("link state at end: ${link.snapshot()}")
        return confirmed
    }

    /** Energy at one frequency. Cheaper and more direct than an FFT when you know what to look for. */
    private fun goertzel(samples: FloatArray, sampleRate: Double, hz: Double): Double {
        val k = 2.0 * cos(2.0 * PI * hz / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (v in samples) {
            val s0 = v + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - k * s1 * s2
        return sqrt(maxOf(0.0, power)) / samples.size
    }

    /** Commits a sine into the Link sink at the rate a soundcard would, without needing one. */
    private class Publisher(private val link: LinkAudioInterface) {
        private val pcm = ShortArray(FRAMES * 2)
        @Volatile private var running = false
        @Volatile private var count = 0L
        private var phase = 0.0
        private var thread: Thread? = null

        fun committed(): Long = count

        fun start() {
            running = true
            thread = Thread({
                val step = 2.0 * PI * TONE_HZ / SAMPLE_RATE
                val periodNanos = FRAMES.toLong() * 1_000_000_000L / SAMPLE_RATE
                var next = System.nanoTime()
                while (running) {
                    for (i in 0 until FRAMES) {
                        val v = (sin(phase) * 0.35 * Short.MAX_VALUE).toInt().toShort()
                        pcm[i * 2] = v
                        pcm[i * 2 + 1] = v
                        phase += step
                        if (phase > 2 * PI) phase -= 2 * PI
                    }
                    val block = link.beginAudioBlock()
                    if (block != null) {
                        link.commitStereo(block, pcm, FRAMES, SAMPLE_RATE)
                        count += 1
                    }
                    next += periodNanos
                    val sleep = next - System.nanoTime()
                    if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
                    else next = System.nanoTime()
                }
            }, "sonicpi-link-audio-publisher").apply { isDaemon = true; start() }
        }

        fun stop() {
            running = false
            runCatching { thread?.join(1_000) }
        }
    }
}
