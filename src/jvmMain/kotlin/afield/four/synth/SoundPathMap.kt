@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import afield.stdlib.displayAudioSamples
import afield.showSignals
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/*
FIELD_TEST
id: synth.sound.path.map
runner: jvm-main
entrypoint: afield.four.synth.SoundPathMap
tags: [smoke, synth, audio, wav, docs, provenance, faust, wasm, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "sound path map audited"
*/

/**
 * Every route Field has from code to something audible, declared — and re-derived from the code.
 *
 * The counts are the deliverable, so they live here as data rather than in a sentence somebody has
 * to remember to update: [SOURCE_COUNT] ways to make samples, [SINK_COUNT] ways to get them out of
 * the process, [INPUT_COUNT] ways to bring them in. Each is one [SoundPath] in [PATHS], carrying
 * its role, whether it can run offline, what has to be installed, the symbol it starts at, and the
 * page that documents it — or `null`, when nothing does. Running this object as a program re-counts
 * [PATHS] against all four declared totals and fails on disagreement.
 *
 * The column that decides whether two paths can be used together is [SoundPath.onSeam]. Everything
 * realtime meets at one signature, [StereoRenderable.render], which `AudioPatchSpec.Renderer`
 * repeats character for character. Two paths that both implement it compose for free; a path that
 * does not implement it composes with nothing. [SonicPi] is the only one outside it, for a reason no
 * wrapper reaches: it is a different process, and no sample ever enters this one. What the seam buys
 * is clearest in the two newest arrivals. Behind [FaustVoiceBank] are an event scheduler, a voice
 * allocator and N WebAssembly instances in one GraalVM context, and reaching the speakers, the
 * visualisers, the Link output and the box lifecycle cost it the five lines of [FaustPolyNode].
 * [PatSynthBank] is the pattern voice arriving the same way: a scheduler in front of the voice that
 * already existed, and nothing else.
 *
 * #### Why this is built like [DocsAudioProvenance] and not beside it
 *
 * [DocsAudioProvenance] guards *published assets*: it scans the docs for `audio=` fences and
 * re-renders each one. That is the right mechanism for "did a figure rot" and the wrong one for
 * "did somebody add a path", because a new render path is invisible to it until a page publishes a
 * clip from it. So this file takes the same three-step shape — declare, derive from the code, fail
 * on disagreement — and points it at a different derivation: the [MARKERS] are literals that can
 * only mean "a new way in or out of this process", and every file carrying one has to be
 * attributed to a declared [SoundPath].
 *
 * A marker declares a file-to-path map rather than a bare allowlist because a literal turns up in
 * the files that *use* a surface as well as in the one that *is* it: `WavFloatArrayWriter.encode`
 * reaches both the inline-audio sink and its callers, and they belong to different paths. [SELF] is
 * the sweep's one blind spot, and the audit turns it back into a check by requiring this file to
 * spell every marker literal, so a literal that has drifted out of the tree cannot sit here
 * matching nothing.
 *
 * #### Why the fifth marker is the seam itself
 *
 * The first four markers are all device calls or file writes, and a sample source need be neither.
 * [FaustVoiceBank] — a Faust program played as an instrument by a pattern — carried none of them, so
 * it sat in the tree undeclared for a month with [SOURCE_COUNT] reading six and the audit green,
 * which is the one failure this file exists to prevent. The fifth marker is therefore the seam
 * itself, `override fun generateStereo(`, which every [StereoNode] has to spell: a new realtime
 * stereo source cannot arrive without a hit. It reported two findings on its first run —
 * [FaustVoiceBank], and [DoughAudioSink], which is [DoughEngine] wrapped onto the seam by
 * [parkDough] and had been declared *off* it since the day this file was written.
 *
 * What it still cannot see is a source that is off the seam entirely: another [SonicPi] in another
 * process, or another whole-buffer free function like [patRenderStereo]. A mono [Signal] is far too
 * common a literal to mean anything.
 */

/**
 * The declared map, and the audit that re-derives it from the tree.
 *
 * [PATHS] and [MARKERS] are the data. Running this object as a program prints the map as a table,
 * then checks it.
 */
object SoundPathMap {

    /** The page this map is written on. Its prose is checked against [PATHS], not trusted. */
    const val PAGE = "public/docs/sound_paths.md"

    /**
     * This file, excluded from the marker sweep — and the guard's one blind spot, stated as one.
     *
     * Not an exemption for convenience: a file that *declares* `alcOpenDevice(` as a marker
     * necessarily contains `alcOpenDevice(`, so the first run of this guard reported itself as three
     * undeclared audio paths. That is a real property of any grep-based derivation and the
     * fix is to name the blind spot rather than to weaken the literals into something that could
     * match by accident.
     *
     * The blind spot is turned back into a check: [audit] requires this file to contain *every*
     * marker literal, so a marker whose spelling has drifted out of the tree cannot sit here
     * silently matching nothing.
     */
    const val SELF = "src/jvmMain/kotlin/afield/four/synth/SoundPathMap.kt"

    /** What a path does with samples. */
    enum class Role(val label: String) {
        /** Turns something that is not audio — a pattern, a graph, a program — into samples. */
        MAKES("makes samples"),

        /** Gets samples out of this process: a device, a file, a browser element, another app. */
        TAKES_OUT("takes samples out"),

        /** Brings samples in from outside: a microphone, another app, a file, a video. */
        BRINGS_IN("brings samples in"),
    }

    /** Whether a path needs a clock and a device, or runs as fast as the CPU allows. */
    enum class Timing(val label: String) {
        /** No device, no clock: renders into a buffer. Works in CI, in a docs build, in a test. */
        OFFLINE("offline"),

        /** Needs an audio device and wall-clock time. Cannot be driven by a headless docs build. */
        REALTIME("realtime"),

        /** Has both halves, usually as two different entry points. */
        BOTH("offline + realtime"),
    }

    /**
     * One route from code to sound, with everything a person choosing between them needs.
     *
     * [entryPoint] is a literal that must appear in [file] — a symbol, not a line number, because
     * line numbers rot inside a week and this project has a citation-drift guard for that reason.
     * The guard greps for it, so a rename fails the build instead of publishing a dead citation.
     */
    class SoundPath(
        /** Stable id, used on the page and in the guard's messages. */
        val id: String,
        val role: Role,
        val timing: Timing,
        /**
         * True when this path meets `AudioPatchSpec.Renderer.render(outL, outR, numFrames, ctx)`.
         *
         * The single most useful column on the page: a path on the seam composes with every other
         * path on the seam for free, and a path off it does not compose with anything.
         */
        val onSeam: Boolean,
        /** What has to be installed. `"nothing"` is a feature and is stated as one. */
        val needs: String,
        /** `"mono"`, `"stereo"`, or what the path actually gives you. */
        val channels: String,
        /** One sentence: what it is for. */
        val what: String,
        /** Repo-relative file that owns it. */
        val file: String,
        /** A symbol inside [file], verbatim. Checked. */
        val entryPoint: String,
        /** The docs page that documents it today, or null when nothing does. */
        val page: String?,
    ) {
        fun line(): String = buildString {
            append("%-22s".format(id))
            append(" %-18s".format(role.label))
            append(" %-19s".format(timing.label))
            append(if (onSeam) " seam " else " ---- ")
            append(" %-28s".format(needs))
            append(" %s".format(page ?: "(no page)"))
        }
    }

    /**
     * The map. Seven sample sources, five ways out, four ways in.
     *
     * Ordered the way a person reads it: the things that make sound, then the things that carry it
     * away, then the things that feed it. Every `page = null` is a real gap and is named as one on
     * [PAGE] rather than omitted in silence — the most striking is `audio-patch-seam`, the one
     * signature the whole realtime stack is built on, which no published page mentions at all.
     */
    val PATHS: List<SoundPath> = listOf(

        // ── things that make samples ───────────────────────────────────────────────────────

        SoundPath(
            id = "pat-stand-in",
            role = Role.MAKES, timing = Timing.BOTH, onSeam = true,
            needs = "nothing",
            channels = "stereo",
            what = "a pattern's events through a deliberately tiny synth voice — one oscillator, " +
                "one one-pole lowpass, one decay envelope, per event. Offline in one pass through " +
                "patRenderStereo, or live through patSynth, whose PatSynthBank is a StereoNode fed " +
                "frame-stamped notes by a PatSynthFeeder off the audio thread",
            file = "src/jvmMain/kotlin/afield/four/synth/PatSoundDocs.kt",
            entryPoint = "fun patRenderStereo",
            page = "public/docs/sound.md",
        ),

        SoundPath(
            id = "dirt-samples",
            role = Role.MAKES, timing = Timing.BOTH, onSeam = true,
            needs = "the Tidal Dirt-Samples quark (~400MB, not vendored)",
            channels = "stereo",
            what = "the same pattern's events as real Tidal one-shots — offline into a buffer via " +
                "patRenderStereoDirt, or live through DirtSamplePlayer, which is a StereoNode",
            file = "src/jvmMain/kotlin/afield/four/synth/DirtRender.kt",
            entryPoint = "fun patRenderStereoDirt",
            page = "public/docs/sound.md",
        ),

        SoundPath(
            id = "signal-graph",
            role = Role.MAKES, timing = Timing.BOTH, onSeam = true,
            needs = "nothing",
            channels = "mono (Signal) or stereo (StereoRenderable)",
            what = "Field's own DSP graph: oscillators, filters, envelopes and the DoughDSP ports, " +
                "composed with Kotlin operators and rendered a block at a time",
            file = "src/jvmMain/kotlin/afield/four/synth/Signal.kt",
            entryPoint = "abstract class Signal",
            page = "public/docs/dough_dsp_sound.md",
        ),

        SoundPath(
            id = "faust",
            role = Role.MAKES, timing = Timing.BOTH, onSeam = true,
            needs = "nothing (libfaust is vendored as WebAssembly)",
            channels = "whatever the program declares; mono fans out to both",
            what = "a Faust program compiled at runtime and instantiated as a node in the same " +
                "graph — write what the signal is, not how to compute it",
            file = "src/jvmMain/kotlin/afield/four/synth/FaustDsp.kt",
            entryPoint = "class FaustNode",
            page = "public/docs/faust.md",
        ),

        SoundPath(
            id = "faust-poly",
            role = Role.MAKES, timing = Timing.BOTH, onSeam = true,
            needs = "nothing (libfaust is vendored as WebAssembly)",
            channels = "whatever the program declares; mono fans out to both",
            what = "the same Faust program played as an *instrument*: N instances of one compiled " +
                "module allocated to notes by a pattern, with the notes landing on the sample they " +
                "were scheduled for rather than on the block boundary",
            file = "src/jvmMain/kotlin/afield/four/synth/FaustPoly.kt",
            entryPoint = "class FaustVoiceBank",
            page = "public/docs/faust.md",
        ),

        SoundPath(
            id = "pure-data",
            role = Role.MAKES, timing = Timing.BOTH, onSeam = true,
            needs = "libpd, not committed — ./gradlew buildLibpdNative",
            channels = "whatever the patch's dac~ has; mono is heard in both",
            what = "a Pure Data patch, written inline in a box or opened from a .pd file, running " +
                "in a libpd instance of its own and pulled a block at a time by PdNode — with " +
                "messages to its [r] objects queued onto the rendering thread and its [s] objects " +
                "delivered back to Kotlin on the main thread",
            file = "src/jvmMain/kotlin/afield/pd/PdInstance.kt",
            entryPoint = "class PdNode",
            page = "public/docs/pure_data.md",
        ),

        SoundPath(
            id = "dough-engine",
            // onSeam was `false` here, and `sound_paths.md` said this path "could be wrapped, and has
            // not been". It is wrapped: `DoughAudioSink : StereoNode()` is in this same file and
            // `parkDough` registers `audio(sink)` with the engine. Found by the `seam-stereo-node`
            // marker on its first run, not by reading — the claim had been false since the day the
            // map was written. `DoughEngine` is still the entry point, because the wasm module is
            // what this path *is*; the wrapper is how it reaches the seam.
            role = Role.MAKES, timing = Timing.BOTH, onSeam = true,
            needs = "the dough WebAssembly build",
            channels = "stereo",
            what = "the whole dough/Strudel synthesis engine as a wasm module, driven by events and " +
                "rendered in fixed 128-frame blocks of its own, reaching the seam through " +
                "DoughAudioSink",
            file = "src/jvmMain/kotlin/afield/four/synth/Dough.kt",
            entryPoint = "class DoughEngine",
            page = null,
        ),

        SoundPath(
            id = "pat-spatial",
            // The one source in this map that is REALTIME while sitting on the seam, and the reason
            // is worth keeping next to the flag: the synthesis is a render thread's, and the audio
            // thread's whole job is a bounded copy out of a ring. `PatSpatialLaunch.advanceOffline`
            // looks like an offline half and is not one — it consumes what the render thread has
            // already produced, in real time, so a caller asking for a second of audio waits a
            // second. That is what keeps this path off the docs `audio=` fence contract; see
            // `public/docs/pat_spatial.md#why-there-is-no-clip-of-the-output`.
            role = Role.MAKES, timing = Timing.REALTIME, onSeam = true,
            needs = "the dough WebAssembly build, plus Steam Audio's libphonon, which is not " +
                "committed — ./gradlew fetchSteamAudioNatives",
            channels = "binaural stereo: one HRTF-placed mono source per `spat` tag, summed",
            what = "a pattern placed in space. Each `spat` group gets a DoughEngine of its own, " +
                "its output is taken as mono because Steam Audio spatialises a direction-less " +
                "buffer, and a render thread applies the direct and binaural effects and sums " +
                "them into a PatSpatialRing that PatSpatialBank copies out of on the audio thread",
            file = "src/jvmMain/kotlin/afield/four/synth/PatSpatial.kt",
            entryPoint = "fun patSpatial",
            page = "public/docs/pat_spatial.md",
        ),

        SoundPath(
            id = "sonic-pi",
            role = Role.MAKES, timing = Timing.REALTIME, onSeam = false,
            needs = "a local Sonic Pi v5 install",
            channels = "stereo, and never inside this process",
            what = "a Ruby program handed to Sonic Pi's own scsynth over OSC — the only path where " +
                "no sample ever enters the JVM",
            file = "src/jvmMain/kotlin/afield/four/synth/SonicPi.kt",
            entryPoint = "class SonicPi",
            page = "public/docs/sonic_pi.md",
        ),

        // ── things that take samples out ───────────────────────────────────────────────────

        SoundPath(
            id = "audio-patch-seam",
            role = Role.TAKES_OUT, timing = Timing.REALTIME, onSeam = true,
            needs = "an audio output device",
            channels = "stereo",
            what = "the seam itself, and the default way a box makes a noise: return audio(signal) " +
                "and one OpenAL-backed engine thread pulls blocks from it until the box stops",
            file = "src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt",
            entryPoint = "internal interface Renderer",
            page = null,
        ),

        SoundPath(
            id = "wav-file",
            role = Role.TAKES_OUT, timing = Timing.OFFLINE, onSeam = false,
            needs = "nothing",
            channels = "mono or stereo, 16-bit PCM",
            what = "a .wav on disk. Every audio figure in these docs is this sink, which is why " +
                "they regenerate on a machine with no sound card",
            file = "src/jvmMain/kotlin/afield/four/audio/WavFloatArrayWriter.kt",
            entryPoint = "fun writeStereo16",
            page = "public/docs/sound.md",
        ),

        SoundPath(
            id = "box-output-player",
            role = Role.TAKES_OUT, timing = Timing.OFFLINE, onSeam = false,
            needs = "nothing",
            channels = "mono",
            what = "samples encoded to a WAV in memory, base64'd into a data: URI and displayed as " +
                "an <audio> element in the box's own output pane",
            file = "src/jvmMain/kotlin/afield/stdlib/AudioVisualizer.kt",
            entryPoint = "fun renderSamplesHtml",
            page = "public/docs/visualizers.md",
        ),

        SoundPath(
            id = "spatial-openal",
            role = Role.TAKES_OUT, timing = Timing.REALTIME, onSeam = false,
            needs = "an audio output device; HRTF wants OpenAL Soft",
            channels = "positioned mono sources around a listener",
            what = "a second, independent OpenAL context for 3D positional audio with optional " +
                "HRTF and EFX reverb — the one used by the XR targets, not by boxes",
            file = "src/jvmMain/kotlin/afield/four/audio/SimpleSpatialAudio.kt",
            entryPoint = "class SimpleSpatialAudioEngine",
            page = null,
        ),

        SoundPath(
            id = "link-audio-out",
            role = Role.TAKES_OUT, timing = Timing.REALTIME, onSeam = true,
            needs = "Ableton Link Audio peers on the network",
            channels = "stereo",
            what = "the engine's own output mirrored into Link Audio so another application on the " +
                "network hears it, tempo-locked",
            file = "src/jvmMain/kotlin/afield/four/synth/AbletonLinkAudio.kt",
            entryPoint = "fun enableAudioOutput",
            page = "public/docs/sonic_pi.md",
        ),

        // ── things that bring samples in ───────────────────────────────────────────────────

        SoundPath(
            id = "device-input",
            role = Role.BRINGS_IN, timing = Timing.REALTIME, onSeam = true,
            needs = "an audio input device, and the OS microphone permission",
            channels = "mono or stereo",
            what = "OpenAL capture as a Signal, so a microphone is just another node in the graph",
            file = "src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt",
            entryPoint = "fun stereoInput()",
            page = null,
        ),

        SoundPath(
            id = "link-audio-in",
            role = Role.BRINGS_IN, timing = Timing.REALTIME, onSeam = true,
            needs = "Ableton Link Audio peers on the network",
            channels = "stereo per named channel",
            what = "another application's output as a Signal — how Sonic Pi's audio gets into a " +
                "Field graph without a virtual cable",
            file = "src/jvmMain/kotlin/afield/four/synth/AbletonLinkAudio.kt",
            entryPoint = "fun stereoInput(channelName",
            page = "public/docs/sonic_pi.md",
        ),

        SoundPath(
            id = "video-audio",
            role = Role.BRINGS_IN, timing = Timing.REALTIME, onSeam = false,
            needs = "the bundled FFmpeg",
            channels = "as the file has it, resampled to interleaved 16-bit",
            what = "a video or audio file decoded by FFmpeg and played through a third OpenAL " +
                "context, kept in step with the video texture by its own playback clock",
            file = "src/jvmMain/kotlin/afield/four/texture/video/AudioDecoder.kt",
            entryPoint = "class AudioDecoder",
            page = "dev_docs/AUDIO_OPTIONS.md",
        ),

        SoundPath(
            id = "wav-file-in",
            role = Role.BRINGS_IN, timing = Timing.OFFLINE, onSeam = false,
            needs = "nothing",
            channels = "mono or stereo",
            what = "a .wav off disk as FloatArrays — the counterpart of wav-file, and how a " +
                "recording gets into an analysis or a resynthesis",
            file = "src/jvmMain/kotlin/afield/four/audio/WavFloatArrayLoader.kt",
            entryPoint = "WavFloatArrayLoader",
            page = null,
        ),
    )

    private val byId: Map<String, SoundPath> = PATHS.associateBy { it.id }

    /** Looks a path up by id, with a message that lists the alternatives. */
    fun path(id: String): SoundPath =
        byId[id] ?: error("unknown sound path '$id'; declared: ${byId.keys.sorted()}")

    // ── the ratchets: counts, pinned near the measurement ──────────────────────────────────

    /**
     * How many distinct ways there are to make samples. **Nine**, replacing a bolded "two".
     *
     * The two the page used to name are `pat-stand-in` and `dirt-samples`; the other seven are the
     * `Signal` graph, Faust as an effect, Faust as a polyphonic instrument, a Pure Data patch, the
     * dough wasm engine, [patSpatial] and Sonic Pi. Four of the nine are pattern voices, which is the number
     * `sound.md` counts; this one counts routes, so it is always the larger.
     *
     * It went from seven to eight on 2026-09-16, and that arrival is the failure this file's
     * markers exist to catch happening a second time and being caught: `PatSpatial.kt` opens no
     * device and encodes no WAV, exactly like `FaustPoly.kt` before it, and the `seam-stereo-node`
     * marker reported it — along with `PatSpatialEngineSmoke.kt`'s control node — rather than the
     * count sitting at seven while a whole spatialised pattern voice shipped undeclared.
     *
     * Nine on 2026-09-28: `pure-data`, a libpd instance per launch, reached through `PdNode`. It was
     * declared in the change that added it, because the `seam-stereo-node` marker would not let the
     * change pass without it.
     */
    const val SOURCE_COUNT = 9

    /** How many ways samples leave this process. Five, of which three are separate OpenAL contexts. */
    const val SINK_COUNT = 5

    /** How many ways samples arrive from outside. Four. */
    const val INPUT_COUNT = 4

    /**
     * How many declared paths meet `AudioPatchSpec.Renderer.render`. Twelve of eighteen.
     *
     * A floor here is the assertion that matters most: the seam is the reason Faust cost no new
     * plumbing, and if a later change adds a path *beside* it rather than *on* it, this number
     * stops growing while [PATHS] does. That is exactly the drift worth catching.
     *
     * It went from seven to ten on 2026-09-12 for three different reasons, and only one of them is a
     * new path: `faust-poly` arrived on the seam, `dough-engine` turned out to have been on it all
     * along, and `pat-stand-in` was put on it by [patSynth], which is the scheduler a pattern needed
     * in order to be asked for a block at a time. A number that moves for three reasons is worth
     * saying so.
     *
     * Eleven since 2026-09-16, for a fourth kind of reason: [patSpatial] is the first path to reach
     * the seam without being able to render into a buffer at all. Being on the seam and being
     * offline-capable are independent, and this is the entry that proves it — what the seam asks of
     * a path is that it can fill a block when asked, not that it can do so faster than wall clock.
     *
     * Twelve since 2026-09-28, for the most ordinary reason of all: `pure-data` arrived on it.
     */
    const val SEAM_COUNT = 12

    // ── derivation from the code ───────────────────────────────────────────────────────────

    /**
     * A literal that can only mean "a new way in or out of this process", and where it may appear.
     *
     * [declaredIn] maps repo-relative file to the [SoundPath.id] responsible for it, rather than
     * being a bare allowlist, because a marker legitimately appears both in the sink that
     * implements it and in the callers that use it — `WavFloatArrayWriter.encode` is in five files
     * and only one of them is the sink.
     */
    class Marker(
        val id: String,
        /** Literal substring, grepped. Not a regex: the point is that it cannot match by accident. */
        val literal: String,
        /** What a *new* hit would mean, phrased for whoever sees the failure. */
        val meaning: String,
        /** Repo-relative directories to scan. */
        val roots: List<String>,
        val declaredIn: Map<String, String>,
    )

    /**
     * Every marker, with the file-to-path attribution measured on the day each one was added.
     *
     * The judgement a hit calls for is always the same one: a file that is a *node of* an
     * already-declared path is declared against that path, and a file that is a new *route from code
     * to sound* gets a new [SoundPath]. `Sampling.kt`'s granulators are the first kind — they are
     * nodes in the `Signal` graph and compose through it. `FaustPoly.kt` is the second, and getting
     * that call wrong once already cost this map a whole sample source; see this object's KDoc.
     */
    val MARKERS: List<Marker> = listOf(
        Marker(
            id = "openal-output",
            literal = "alcOpenDevice(",
            meaning = "a new, independent OpenAL output context — a fourth realtime audio surface " +
                "in one process. Three is already more than anybody expected",
            roots = listOf("src/jvmMain/kotlin"),
            declaredIn = mapOf(
                "src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt" to "audio-patch-seam",
                "src/jvmMain/kotlin/afield/four/audio/SimpleSpatialAudio.kt" to "spatial-openal",
                "src/jvmMain/kotlin/afield/four/texture/video/AudioPlayer.kt" to "video-audio",
            ),
        ),
        Marker(
            id = "openal-capture",
            literal = "alcCaptureOpenDevice(",
            meaning = "a new audio *capture* surface",
            roots = listOf("src/jvmMain/kotlin"),
            declaredIn = mapOf(
                "src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt" to "device-input",
            ),
        ),
        Marker(
            id = "docs-audio-producer",
            literal = "fun renderWav(name: String, outPath: String)",
            meaning = "a new offline producer on the docs `audio=` fence contract. " +
                "DocsAudioProvenance.ORIGIN_BY_RENDERER needs an entry too, or the corpus audit " +
                "will call its clips UNKNOWN",
            roots = listOf("src/jvmMain/kotlin"),
            declaredIn = mapOf(
                "src/jvmMain/kotlin/afield/four/synth/PatSoundDocs.kt" to "pat-stand-in",
                "src/jvmMain/kotlin/afield/four/synth/PatMiniNotation.kt" to "pat-stand-in",
                "src/jvmMain/kotlin/afield/four/synth/CaSoundDocs.kt" to "pat-stand-in",
                "src/jvmMain/kotlin/afield/four/synth/LambdaSoundDocs.kt" to "pat-stand-in",
                "src/jvmMain/kotlin/afield/four/synth/PatSynthDocs.kt" to "pat-stand-in",
                "src/jvmMain/kotlin/afield/four/synth/DrawnScoreDocs.kt" to "pat-stand-in",
                "src/jvmMain/kotlin/afield/four/synth/DoughDspSoundDocs.kt" to "signal-graph",
                "src/jvmMain/kotlin/afield/four/synth/FnSoundDocs.kt" to "signal-graph",
                "src/jvmMain/kotlin/afield/four/synth/SoundRenderPathDocs.kt" to "dirt-samples",
                "src/jvmMain/kotlin/afield/four/synth/FaustPolySoundDocs.kt" to "faust-poly",
                // The only producer here that cannot render the path it belongs to. It publishes
                // the *mono source* pat-spatial hands Steam Audio, because the spatialisation is
                // realtime-only and its binary is not committed; see PatSpatialSoundDocs' KDoc.
                "src/jvmMain/kotlin/afield/four/synth/PatSpatialSoundDocs.kt" to "pat-spatial",
            ),
        ),
        Marker(
            id = "inline-audio-player",
            literal = "WavFloatArrayWriter.encode",
            meaning = "another place that turns samples into a base64 data: URI for a box output. " +
                "Prefer AudioVisualizer.renderSamplesHtml over a fourth hand-rolled <audio> tag",
            roots = listOf("src/jvmMain/kotlin"),
            declaredIn = mapOf(
                "src/jvmMain/kotlin/afield/stdlib/AudioVisualizer.kt" to "box-output-player",
                "src/jvmMain/kotlin/afield/visualization/ValueVisualizer.kt" to "box-output-player",
                "src/jvmMain/kotlin/afield/four/audio/WavFloatArrayWriter.kt" to "wav-file",
                "src/jvmMain/kotlin/afield/four/synth/DirtRender.kt" to "dirt-samples",
                "src/jvmMain/kotlin/afield/four/synth/PatSoundDocs.kt" to "pat-stand-in",
                "src/jvmMain/kotlin/afield/four/synth/FaustPattern.kt" to "faust",
            ),
        ),
        Marker(
            id = "seam-stereo-node",
            literal = "override fun generateStereo(",
            meaning = "a new node on the seam. This is the marker the other four did not have: a " +
                "sample source that opens no device and encodes no WAV carries none of them, which " +
                "is how a whole polyphonic Faust instrument sat in the tree undeclared for a month. " +
                "Every StereoNode has to spell this literal, so a new realtime stereo source cannot " +
                "arrive without a hit — and a hit is only sometimes a new path",
            roots = listOf("src/jvmMain/kotlin"),
            declaredIn = mapOf(
                // Nodes of the graph, not routes of their own: the seam's own base class and its
                // combinators, the granulators, the stereo scope tap, and the DoughDSP ports.
                "src/jvmMain/kotlin/afield/four/synth/Signal.kt" to "signal-graph",
                "src/jvmMain/kotlin/afield/four/synth/Sampling.kt" to "signal-graph",
                "src/jvmMain/kotlin/afield/four/synth/Visualizers.kt" to "signal-graph",
                "src/jvmMain/kotlin/afield/four/synth/DoughDsp.kt" to "signal-graph",
                // One file per path, for the four sources that reach the seam through a node.
                "src/jvmMain/kotlin/afield/four/synth/DirtSamples.kt" to "dirt-samples",
                "src/jvmMain/kotlin/afield/four/synth/Dough.kt" to "dough-engine",
                "src/jvmMain/kotlin/afield/four/synth/FaustDsp.kt" to "faust",
                "src/jvmMain/kotlin/afield/four/synth/FaustPoly.kt" to "faust-poly",
                "src/jvmMain/kotlin/afield/four/synth/PatSynth.kt" to "pat-stand-in",
                // PdNode, the audio thread's end of a libpd instance.
                "src/jvmMain/kotlin/afield/pd/PdInstance.kt" to "pure-data",
                // PatSpatialBank and PatSpatialLaunch, the audio thread's end of the spatial ring.
                "src/jvmMain/kotlin/afield/four/synth/PatSpatial.kt" to "pat-spatial",
                // Not a node of the path: the control patch that smoke registers *beside* the
                // launch, so that "the engine pulled nothing" can be told apart from "the engine
                // pulled nothing from this launch". Declared anyway, because the sweep cannot tell
                // a test's scaffolding from a shipped node and should not be taught to guess — a
                // file under jvmMain that spells the seam is a file somebody has to account for.
                "src/jvmMain/kotlin/afield/four/synth/PatSpatialEngineSmoke.kt" to "pat-spatial",
            ),
        ),
    )

    /**
     * Repo-relative paths of every file under a marker's [Marker.roots] that carries its literal.
     *
     * [SELF] is excluded; see its KDoc for why that is a stated blind spot rather than an exemption.
     */
    fun filesCarrying(marker: Marker, repoRoot: File = File(".")): Set<String> =
        marker.roots.flatMap { root ->
            File(repoRoot, root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(repoRoot).path.replace('\\', '/') }
                .filter { it != SELF }
                .filter { File(repoRoot, it).readText().contains(marker.literal) }
                .toList()
        }.toSet()

    /**
     * Marker literals that [SELF] does not spell the way it says it does.
     *
     * The other half of the [SELF] blind spot: this file is not swept, so nothing else would notice
     * a marker whose literal had been mistyped into one that can never match.
     */
    fun selfCheck(repoRoot: File = File(".")): List<String> {
        val self = File(repoRoot, SELF)
        if (!self.isFile) return listOf("SELF ($SELF) does not exist, so the marker sweep excludes nothing")
        val text = self.readText()
        return MARKERS.filterNot { text.contains(it.literal) }
            .map { "marker '${it.id}' declares the literal `${it.literal}`, which does not appear in $SELF" }
    }

    /**
     * Every disagreement between [MARKERS] and the tree, phrased so the fix is obvious.
     *
     * Both directions, because both are real failures: an undeclared hit is a path nobody wrote
     * down, and a declared file that no longer carries its marker is a citation that has rotted.
     */
    fun markerDisagreements(repoRoot: File = File(".")): List<String> = buildList {
        MARKERS.forEach { marker ->
            val found = filesCarrying(marker, repoRoot)
            val declared = marker.declaredIn.keys
            (found - declared).sorted().forEach { file ->
                add(
                    "UNDECLARED ${marker.id}: $file contains `${marker.literal}`. That means " +
                        "${marker.meaning}. Add it to SoundPathMap.MARKERS[\"${marker.id}\"].declaredIn " +
                        "pointing at the SoundPath it belongs to, add the SoundPath to PATHS if it is " +
                        "new, and name its entryPoint on $PAGE."
                )
            }
            (declared - found).sorted().forEach { file ->
                add(
                    "STALE ${marker.id}: $file is declared as carrying `${marker.literal}` and no " +
                        "longer does. Either the path moved — update declaredIn and the SoundPath's " +
                        "file — or it is gone, and PATHS plus $PAGE should stop claiming it exists."
                )
            }
        }
        MARKERS.forEach { marker ->
            marker.declaredIn.forEach { (file, id) ->
                if (id !in byId) add("marker ${marker.id} attributes $file to unknown path id '$id'")
            }
        }
    }

    /**
     * Declared files or symbols that are not there any more.
     *
     * The citation ratchet: [SoundPath.entryPoint] is grepped out of [SoundPath.file], so a rename
     * fails the build rather than leaving a dead symbol on a published page.
     */
    fun brokenCitations(repoRoot: File = File(".")): List<String> = buildList {
        PATHS.forEach { p ->
            val f = File(repoRoot, p.file)
            if (!f.isFile) {
                add("path '${p.id}': ${p.file} does not exist")
            } else if (!f.readText().contains(p.entryPoint)) {
                add("path '${p.id}': ${p.file} no longer contains `${p.entryPoint}`")
            }
            p.page?.let { page ->
                if (!File(repoRoot, page).isFile) add("path '${p.id}': page $page does not exist")
            }
        }
    }

    /**
     * Paths the map claims but [PAGE] does not actually name.
     *
     * Closes the loop the other way round: the guard already stops the code drifting away from the
     * page, and this stops the page drifting away from the code. A path is "named" when the page
     * carries both its file and its entry-point symbol verbatim, which is a low bar on purpose —
     * it catches an omitted row and a renamed symbol without dictating how the prose reads.
     */
    fun pageOmissions(repoRoot: File = File(".")): List<String> {
        val page = File(repoRoot, PAGE)
        if (!page.isFile) return listOf("$PAGE does not exist, so nothing documents the map")
        val text = page.readText()
        return PATHS.mapNotNull { p ->
            when {
                !text.contains(p.file) -> "path '${p.id}': $PAGE never mentions ${p.file}"
                !text.contains(p.entryPoint) -> "path '${p.id}': $PAGE never names `${p.entryPoint}`"
                else -> null
            }
        }
    }

    // ── the comparison: one input, three sample-makers ─────────────────────────────────────

    /** The beat every figure here starts from — the same one `sound.md` uses, so they line up. */
    private fun theBeat(): PatPattern<String> = pat("bd ~ sd ~, hh*8") with gain(0.9)

    private const val CYCLES = 4
    private const val CPS = 2.0

    /** Cutoff every path in the comparison is asked for, in Hz. */
    const val COMPARISON_CUTOFF_HZ = 900.0

    /**
     * The dry beat: [patRenderStereo] with the voice's filter wide open.
     *
     * The *shared input* for the comparison. Every figure below is this exact buffer, so a
     * difference between two of them is a difference between the two paths and nothing else.
     */
    fun dryBeat(): PatSoundDocs.Stereo = patRenderStereo(theBeat(), CYCLES, CPS)

    /** A [Signal] that plays a fixed buffer once and then silence. Lets a graph filter a recording. */
    private class BufferSignal(private val samples: FloatArray) : Signal() {
        private var at = 0
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            for (i in 0 until numFrames) {
                output[i] = if (at < samples.size) samples[at] else 0f
                at++
            }
        }
    }

    /** [dryBeat] lowpassed by the `Signal` graph's own [ChamberlinLowpass]. */
    fun throughSignalGraph(dry: PatSoundDocs.Stereo, cutoffHz: Double = COMPARISON_CUTOFF_HZ): PatSoundDocs.Stereo {
        fun one(channel: FloatArray): FloatArray {
            val filter = ChamberlinLowpass(cutoff = Param(cutoffHz.toFloat()))
            val seconds = channel.size.toFloat() / PatSoundDocs.SR
            return doughRenderMono(BufferSignal(channel).through(filter), seconds, PatSoundDocs.SR)
                .copyOf(channel.size)
        }
        return PatSoundDocs.Stereo(one(dry.left), one(dry.right))
    }

    /** The Faust source the comparison compiles. A first-order lowpass on a stereo input, nothing else. */
    fun faustLowpassSource(cutoffHz: Double = COMPARISON_CUTOFF_HZ): String =
        """
        import("stdfaust.lib");
        process = fi.lowpass(1, $cutoffHz), fi.lowpass(1, $cutoffHz);
        """.trimIndent()

    /**
     * [dryBeat] lowpassed by a Faust program, compiled and run in this process with no audio device.
     *
     * The point of the figure: a Faust effect is a two-in two-out [FaustDsp], driven by exactly the
     * signature [ChamberlinLowpass] is driven by, on exactly the same input samples. Nothing about
     * getting Faust into the graph is Faust-specific.
     */
    fun throughFaust(dry: PatSoundDocs.Stereo, cutoffHz: Double = COMPARISON_CUTOFF_HZ): PatSoundDocs.Stereo {
        val program = FaustEngine.compile("soundPathMapLowpass", faustLowpassSource(cutoffHz))
        require(program.inputs == 2) { "the comparison needs a 2-in effect, got ${program.inputs}" }
        val dsp = FaustDsp(program, PatSoundDocs.SR)
        try {
            val n = dry.frames
            val outL = FloatArray(n)
            val outR = FloatArray(n)
            dsp.render(outL, outR, n, dry.left, dry.right)
            return PatSoundDocs.Stereo(outL, outR)
        } finally {
            runCatching { dsp.close() }
        }
    }

    /** [dryBeat] lowpassed inside [patVoice] itself, per event, by the voice's own one-pole. */
    fun throughStandInVoice(cutoffHz: Double = COMPARISON_CUTOFF_HZ): PatSoundDocs.Stereo =
        patRenderStereo(theBeat() with cutoff(cutoffHz), CYCLES, CPS)

    /**
     * Proof that the seam is one seam: the same gain, computed by the graph and by Faust.
     *
     * A lowpass cannot be compared exactly — a Chamberlin state-variable filter and a first-order
     * Butterworth are different filters and *should* differ. A gain can. Returns the worst
     * per-sample disagreement between `Signal * 0.5` and Faust's `*(0.5)` over the same input,
     * which is the strongest available evidence that the two paths are being driven identically
     * rather than merely similarly.
     */
    fun seamAgreementError(dry: PatSoundDocs.Stereo): Float {
        val n = min(dry.frames, PatSoundDocs.SR)   // one second is plenty and keeps the smoke quick
        val input = dry.left.copyOf(n)

        val graph = doughRenderMono(BufferSignal(input) * 0.5f, n.toFloat() / PatSoundDocs.SR, PatSoundDocs.SR)
            .copyOf(n)

        val program = FaustEngine.compile("soundPathMapGain", "process = _,_ : *(0.5), *(0.5);")
        val dsp = FaustDsp(program, PatSoundDocs.SR)
        val faustL = FloatArray(n)
        val faustR = FloatArray(n)
        try {
            dsp.render(faustL, faustR, n, input, input)
        } finally {
            runCatching { dsp.close() }
        }

        var worst = 0f
        for (i in 0 until n) worst = max(worst, abs(graph[i] - faustL[i]))
        return worst
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    /**
     * One published figure of the comparison, and which declared [SoundPath] made it.
     *
     * Named `"same-input-…"` on purpose: the shared prefix is the claim. A figure whose name does
     * not start with it is not part of the comparison and does not belong on the page.
     */
    class ComparisonFigure(
        val name: String,
        val pathId: String,
        /** The caption sentence the page must carry verbatim beside the fence. */
        val pageLabel: String,
        val render: () -> PatSoundDocs.Stereo,
    )

    /** The three renders of one buffer, in the order the page presents them. */
    val COMPARISON: List<ComparisonFigure> = listOf(
        ComparisonFigure(
            name = "same-input-dry",
            pathId = "pat-stand-in",
            pageLabel = "**Path: `pat-stand-in`, unfiltered.** This exact buffer is the input to both figures below.",
            render = { dryBeat() },
        ),
        ComparisonFigure(
            name = "same-input-signal-graph",
            pathId = "signal-graph",
            pageLabel = "**Path: `signal-graph`.** The buffer above, through `ChamberlinLowpass` at 900 Hz.",
            render = { throughSignalGraph(dryBeat()) },
        ),
        ComparisonFigure(
            name = "same-input-faust",
            pathId = "faust",
            pageLabel = "**Path: `faust`.** The same buffer, through `fi.lowpass(1, 900)`, compiled at runtime.",
            render = { throughFaust(dryBeat()) },
        ),
    )

    private val comparisonByName = COMPARISON.associateBy { it.name }

    /** Looks a comparison figure up by name. */
    fun comparison(name: String): ComparisonFigure =
        comparisonByName[name]
            ?: error("unknown comparison figure '$name'; known: ${comparisonByName.keys.sorted()}")

    /**
     * Offline-renders comparison figure [name] to a stereo 16-bit WAV at [outPath].
     *
     * The `renderWav(name, path)` contract the docs runner's `audio="…"` fence drives over the
     * backdoor, identical to [SoundRenderPathDocs.renderWav], so `DocsAudioProvenance` can
     * re-derive these clips exactly like the others.
     */
    fun renderWav(name: String, outPath: String) {
        val audio = comparison(name).render()
        require(audio.frames > 0) { "figure '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }

    /**
     * Spectral centroid of a buffer, in Hz — the number that says "this got darker".
     *
     * A real magnitude-weighted mean frequency out of [afield.stdlib.fft], **not**
     * [PatSoundDocs.brightnessOf]. That helper's own KDoc calls itself "first-difference energy over
     * total energy — a brightness proxy", which is unitless; the first draft of this file printed it
     * as Hz and got `centroid=2Hz` for a beat full of 6.2 kHz hats. Worth recording, because the
     * number looked plausible enough at four significant figures to publish.
     *
     * Zero-padded to the next power of two so the whole buffer is measured rather than a prefix.
     */
    fun brightnessHz(samples: FloatArray, sampleRate: Int = PatSoundDocs.SR): Double {
        var n = 1
        while (n < samples.size) n = n shl 1
        return afield.stdlib.fft(samples.copyOf(n), sampleRate.toDouble()).centroid()
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    /**
     * Prints the map, checks it against the tree, and renders the comparison.
     *
     * Writes NO committed assets; pass an output dir as `args[0]` to regenerate the published ones.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("SOUND_PATH_MAP_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "sound-path-map").path
        )
        outDir.mkdirs()

        try {
            audit()
            renderComparison(outDir)
        } finally {
            // The Faust engine holds a Truffle context alive; a jvm-main smoke must exit naturally.
            FaustEngine.shutdown()
        }

        println("sound path map audited: ${PATHS.size} path(s), ${MARKERS.size} marker(s), ${COMPARISON.size} figure(s)")
    }

    private fun audit() {
        println("the map — ${PATHS.size} declared paths")
        println()
        Role.values().forEach { role ->
            val group = PATHS.filter { it.role == role }
            println("  ${group.size} ${role.label}:")
            group.forEach { println("    " + it.line()) }
            println()
        }

        val onSeam = PATHS.count { it.onSeam }
        println("  $onSeam of ${PATHS.size} paths meet AudioPatchSpec.Renderer.render(outL, outR, numFrames, ctx)")
        val undocumented = PATHS.filter { it.page == null }
        println("  ${undocumented.size} path(s) no published page mentions: ${undocumented.map { it.id }}")
        println()

        check(PATHS.count { it.role == Role.MAKES } == SOURCE_COUNT) {
            "SOURCE_COUNT says $SOURCE_COUNT, PATHS has ${PATHS.count { it.role == Role.MAKES }}. " +
                "This number is quoted on $PAGE in bold, so move it deliberately."
        }
        check(PATHS.count { it.role == Role.TAKES_OUT } == SINK_COUNT) {
            "SINK_COUNT says $SINK_COUNT, PATHS has ${PATHS.count { it.role == Role.TAKES_OUT }}"
        }
        check(PATHS.count { it.role == Role.BRINGS_IN } == INPUT_COUNT) {
            "INPUT_COUNT says $INPUT_COUNT, PATHS has ${PATHS.count { it.role == Role.BRINGS_IN }}"
        }
        check(onSeam == SEAM_COUNT) {
            "SEAM_COUNT says $SEAM_COUNT, PATHS has $onSeam. If a new path went in beside the seam " +
                "rather than on it, that is the finding, not a number to edit."
        }

        val problems = markerDisagreements() + brokenCitations() + pageOmissions() + selfCheck()
        problems.forEach { println("  PROBLEM $it") }
        check(problems.isEmpty()) { "${problems.size} sound-path-map problem(s):\n" + problems.joinToString("\n") }
        println("  markers agree with the tree, every citation resolves, every path is on the page")
        println()
    }

    private fun renderComparison(outDir: File) {
        println("the comparison — one buffer, ${COMPARISON.size} paths")
        val dry = dryBeat()
        val rendered = COMPARISON.associate { figure ->
            val audio = figure.render()
            check(audio.frames == dry.frames) {
                "figure '${figure.name}': ${audio.frames} frames against the dry buffer's ${dry.frames}. " +
                    "The whole comparison is that these are the same input."
            }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "figure '${figure.name}': non-finite samples"
            }
            val peak = PatSoundDocs.peak(audio.mono)
            check(peak > 0.02) { "figure '${figure.name}': effectively silent (peak=$peak)" }
            check(peak <= 4.0) { "figure '${figure.name}': runaway level (peak=$peak)" }
            println(
                "  %-24s peak=%.3f rms=%.4f centroid=%.0fHz".format(
                    figure.name, peak, PatSoundDocs.rms(audio.mono), brightnessHz(audio.mono)
                )
            )
            figure.name to audio
        }
        println()

        val dryBright = brightnessHz(rendered.getValue("same-input-dry").mono)
        val graphBright = brightnessHz(rendered.getValue("same-input-signal-graph").mono)
        val faustBright = brightnessHz(rendered.getValue("same-input-faust").mono)

        // Both filters must actually be filters. Measured against the dry buffer rather than
        // against a remembered number, so a tempo or pattern change does not invalidate it.
        check(graphBright < dryBright * BRIGHTNESS_DROP_CEILING) {
            "signal-graph did not darken the buffer: ${graphBright}Hz against dry ${dryBright}Hz"
        }
        check(faustBright < dryBright * BRIGHTNESS_DROP_CEILING) {
            "faust did not darken the buffer: ${faustBright}Hz against dry ${dryBright}Hz"
        }

        // And they must be *lowpasses of the same thing*, not two unrelated noises.
        val agreement = SoundRenderPathDocs.waveformSimilarity(
            rendered.getValue("same-input-signal-graph"),
            rendered.getValue("same-input-faust"),
        )
        println("  signal-graph vs faust waveform similarity: %.4f".format(agreement))
        check(agreement > CROSS_PATH_SIMILARITY_FLOOR) {
            "signal-graph and faust disagree completely (similarity $agreement). They are different " +
                "filters, so they should not be identical — but they are lowpasses of one buffer, so " +
                "$agreement means one of the two paths has stopped filtering that buffer at all."
        }

        val seamError = seamAgreementError(dry)
        println("  the same gain through both paths: worst per-sample error %.3e".format(seamError))
        check(seamError < SEAM_AGREEMENT_CEILING) {
            "`Signal * 0.5` and Faust's `*(0.5)` differ by $seamError on identical input. The seam " +
                "claim on $PAGE is that these two paths are driven the same way; this says they are not."
        }
        println()

        rendered.forEach { (name, audio) ->
            // The committed spelling, so `SOUND_PATH_MAP_DIR=public/docs/assets/sound_path_map`
            // regenerates the published figures in place and nothing has to be renamed by hand.
            val file = File(outDir, "$name.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
            println("  wrote ${file.absolutePath} (${file.length()} bytes)")
        }
        println()
    }

    // ── floors, each next to the number it was measured from ───────────────────────────────

    /**
     * A 900 Hz lowpass must take the spectral centroid down to a fraction of the dry buffer's.
     *
     * Re-measure with `run_field_tests.py --id synth.sound.path.map`, which prints all four
     * centroids. The beat is a quarter sixteenth-note hats at 6.2 kHz, so the dry centroid is high
     * and both filters move it a long way; 0.6 leaves room to change the cutoff or the pattern
     * without leaving room for a filter that has stopped filtering.
     */
    const val BRIGHTNESS_DROP_CEILING = 0.6

    /**
     * Two different lowpasses of one buffer still correlate strongly.
     *
     * Loose. A Chamberlin state-variable filter and a first-order Butterworth are not
     * the same filter and the figure would be dishonest if they matched; what this catches is one
     * of the two paths producing something unrelated to the input.
     */
    const val CROSS_PATH_SIMILARITY_FLOOR = 0.5

    /**
     * `Signal * 0.5` and Faust `*(0.5)` on identical input must agree to float precision.
     *
     * This is the seam claim reduced to a number. It is a multiplication by an exactly
     * representable constant on both sides, so the right expectation is bit-equality; the ceiling
     * allows a single rounding step and nothing more.
     */
    const val SEAM_AGREEMENT_CEILING = 1e-6f
}

// ── the loadable box ───────────────────────────────────────────────────────────────────


/**
 * One line for a box: the whole comparison, playable and plotted.
 *
 * Three players and a brightness plot, so the difference between the paths can be heard *and*
 * seen without leaving the box. Routed through `displayAudioSamples`, which is the
 * `box-output-player` path from the map — so running this exercises four of the eighteen declared
 * paths at once (`pat-stand-in`, `signal-graph`, `faust`, `box-output-player`).
 *
 * The Faust compile costs 13–22s the first time in a process and is nearly free afterwards, so
 * the first run of this box is slow and the second is not. That is Truffle warming up, not
 * anything of Field's.
 */
fun afield.Box.showSoundPathComparison(cutoffHz: Double = SoundPathMap.COMPARISON_CUTOFF_HZ) {
    val dry = SoundPathMap.dryBeat()
    val graph = SoundPathMap.throughSignalGraph(dry, cutoffHz)
    val faust = SoundPathMap.throughFaust(dry, cutoffHz)

    val renders = listOf(
        "pat-stand-in — the shared input, unfiltered" to dry,
        "signal-graph — ChamberlinLowpass at ${cutoffHz.toInt()} Hz (2-pole)" to graph,
        "faust — fi.lowpass(1, ${cutoffHz.toInt()}) (1-pole)" to faust,
    )
    renders.forEachIndexed { i, (label, audio) ->
        displayAudioSamples(audio.mono, PatSoundDocs.SR, channel = "path$i", label = label)
        println(
            "%-52s centroid=%6.0f Hz  rms=%.4f".format(
                label, SoundPathMap.brightnessHz(audio.mono), PatSoundDocs.rms(audio.mono)
            )
        )
    }

    println("signal-graph vs faust waveform similarity: %.4f".format(
        SoundRenderPathDocs.waveformSimilarity(graph, faust)
    ))
    println("the same gain through both paths, worst per-sample error: %.3e".format(
        SoundPathMap.seamAgreementError(dry)
    ))
    println("(0 means bit-equal — AudioPatchSpec.Renderer is one seam, not two similar ones)")

    showSignals(
        *renders.map { (label, audio) ->
            label.substringBefore(" —") to doughShortTimeBrightness(audio.mono, 40.0, 200, PatSoundDocs.SR)
        }.toTypedArray(),
        channel = "plot",
        title = "one buffer, three paths — short-time brightness",
        xTitle = "window", yTitle = "brightness (unitless proxy)",
    )
}
