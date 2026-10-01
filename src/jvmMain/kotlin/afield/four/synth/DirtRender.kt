@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.Box
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.sqrt

/**
 * Offline rendering of a pattern through the **real** Dirt sample library.
 *
 * The other renderer in this package, [patRenderStereo], goes through [patVoice]: a sine for `bd`,
 * filtered white noise for `sd` and `hh`. That stand-in needs no assets, so it works in CI and on
 * a clean checkout. It also sounds like bursts of white noise, because it *is* bursts of white
 * noise. It is not the voice behind most of the published audio figures either:
 * [DocsAudioProvenance] resolves each published clip to the producer that rendered it,
 * `DocsAudioProvenanceGuardTest` pins the resulting corpus by count and by family, and
 * `public/docs/sound.md` states the mix in prose. The largest family comes from
 * [DoughDspSoundDocs], which never reaches the stand-in voice at all.
 *
 * The genuine Tidal sample set was already playable, but only *live*: [playDirt] drives a
 * polyphonic [DirtSamplePlayer] through `respondAhead`, a clock and an audio device, none of which
 * exist inside a docs build or a headless box run. Here the same library, loaded by [libraryOrNull]
 * from wherever [dirtSampleRoot] finds it, is mixed straight into a buffer, so a transcript, a docs
 * capture or a `println`-and-listen box can sound like actual drums with no audio device anywhere.
 *
 * Fallback happens at two different granularities:
 *
 *  * **No library on this machine** — the whole render falls back to [patRenderStereo]. Nothing
 *    breaks, the figures still generate, and [DirtRenderResult.usedSamples] is `false`, so a
 *    caller can say which one you are hearing.
 *  * **Library present, token missing from it** — only *that event* falls back to the synth voice.
 *    A pattern of note numbers, or one naming a drum Dirt has never heard of, must not go silent
 *    just because the library loaded.
 *
 * Both are for a box, where something audible beats an error. A published clip wants the
 * opposite: `strict = true` turns either case into an exception, and the docs renderers pass
 * [DirtRender.requirePinnedLibrary], the checksummed subset `./gradlew fetchDocsDirtSamples`
 * downloads, so a clip is made from the same bytes on every machine or not made at all.
 */

/**
 * Where the Dirt sample library is, whether it loaded, and what a finished render was made of.
 *
 * Discovery is [dirtSampleRoot] and [libraryOrNull]; [DirtRenderResult] carries the bookkeeping
 * back to the caller.
 */
object DirtRender {

    /** Where the sample set lives, if it lives anywhere. Probed once; see [dirtSampleRoot]. */
    const val ENV_VAR = "FIELD_DIRT_SAMPLES"

    /**
     * The standard install locations, in probe order.
     *
     * Dirt-Samples ships as a SuperCollider *quark*, so anyone who has run Tidal already has it at
     * one of these — which is the whole reason to auto-discover rather than ask. It is ~400MB of
     * third-party audio with mixed per-folder licensing, so it is emphatically not vendored here.
     */
    private fun candidateRoots(): List<Path> = buildList {
        System.getenv(ENV_VAR)?.takeIf { it.isNotBlank() }?.let { add(Paths.get(it)) }
        val home = System.getProperty("user.home") ?: return@buildList
        add(Paths.get(home, "Library", "Application Support", "SuperCollider", "downloaded-quarks", "Dirt-Samples"))
        add(Paths.get(home, ".local", "share", "SuperCollider", "downloaded-quarks", "Dirt-Samples"))
        add(Paths.get(home, "Dirt-Samples"))
        // Last, because it holds only the few folders the docs use: a full Tidal install should
        // win for live playing. See [pinnedSampleRoot].
        runCatching { pinnedCacheDir() }.getOrNull()?.let { add(it) }
    }

    // ── the pinned subset the docs render from ──────────────────────────────────────────────

    /** The manifest `tools/fetch_dirt_samples.py` downloads from; one copy, read by both sides. */
    private const val MANIFEST_RESOURCE = "/afield/four/synth/dirt-samples-pinned.json"

    /** One pinned file: its path inside the library, its size and its SHA-256. */
    class PinnedFile(val path: String, val bytes: Long, val sha256: String)

    /** The pinned subset: which commit of tidalcycles/Dirt-Samples, which folders, which files. */
    class PinnedManifest(val commit: String, val folders: List<String>, val files: List<PinnedFile>)

    /** The manifest off the classpath. */
    val pinnedManifest: PinnedManifest by lazy {
        val text = DirtRender::class.java.getResourceAsStream(MANIFEST_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("$MANIFEST_RESOURCE is missing from the classpath")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        PinnedManifest(
            commit = root.getValue("commit").jsonPrimitive.content,
            folders = root.getValue("folders").jsonArray.map { it.jsonPrimitive.content },
            files = root.getValue("files").jsonArray.map {
                val o = it.jsonObject
                PinnedFile(
                    o.getValue("path").jsonPrimitive.content,
                    o.getValue("bytes").jsonPrimitive.long,
                    o.getValue("sha256").jsonPrimitive.content,
                )
            },
        )
    }

    /**
     * Where `tools/fetch_dirt_samples.py` puts the pinned subset, whether or not it is there.
     *
     * The per-user native download cache of `tools/native_download_cache.py`, computed the same
     * way: `AFIELD_NATIVE_DOWNLOAD_CACHE` first, then the platform's cache convention.
     */
    fun pinnedCacheDir(): Path {
        val override = System.getenv("AFIELD_NATIVE_DOWNLOAD_CACHE")?.trim().orEmpty()
        val home = System.getProperty("user.home") ?: "."
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val cache = when {
            override.isNotEmpty() -> Paths.get(override.replaceFirst(Regex("^~"), home))
            os.contains("windows") ->
                Paths.get(System.getenv("LOCALAPPDATA") ?: Paths.get(home, "AppData", "Local").toString(), "Field4", "Cache")
            os.contains("mac") -> Paths.get(home, "Library", "Caches", "Field4")
            else -> Paths.get(System.getenv("XDG_CACHE_HOME") ?: Paths.get(home, ".cache").toString(), "field4")
        }
        return cache.resolve("native-downloads").resolve("dirt-samples").resolve(pinnedManifest.commit)
    }

    private val verifiedRoots = java.util.concurrent.ConcurrentHashMap<Path, List<String>>()

    /** What is wrong with the pinned subset at [root]: missing files and wrong digests. Empty when it is whole. */
    fun pinnedProblems(root: Path = pinnedCacheDir()): List<String> = verifiedRoots.computeIfAbsent(root.toAbsolutePath().normalize()) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        pinnedManifest.files.mapNotNull { file ->
            val path = root.resolve(file.path)
            when {
                !Files.isRegularFile(path) -> "missing ${file.path}"
                Files.size(path) != file.bytes -> "wrong size ${file.path}"
                digest.digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) } != file.sha256 ->
                    "wrong digest ${file.path}"
                else -> null
            }
        }
    }

    /**
     * The pinned, checksummed Dirt-Samples subset, or null when it is not on this machine intact.
     *
     * Verified once per process: every file in the manifest present at its pinned SHA-256.
     */
    fun pinnedSampleRoot(): Path? = pinnedCacheDir().takeIf { it.isDirectory() && pinnedProblems(it).isEmpty() }

    /** How to get the pinned subset; quoted by every failure that needs it. */
    const val PINNED_REMEDY = "run `./gradlew fetchDocsDirtSamples` (or `python3 tools/fetch_dirt_samples.py`), " +
        "which downloads the pinned, checksummed Dirt-Samples folders into the shared per-user cache"

    /**
     * The library a published clip is rendered from: the pinned subset, and nothing else.
     *
     * Not [libraryOrNull], which takes whatever Tidal install it finds first. Two machines with two
     * different installs would publish two different clips, and the provenance audit compares
     * bytes. Throws, naming the remedy, when the subset is missing or any file is wrong.
     */
    fun requirePinnedLibrary(): DirtSampleLibrary {
        val dir = pinnedCacheDir()
        val problems = if (dir.isDirectory()) pinnedProblems(dir) else listOf("no directory at $dir")
        check(problems.isEmpty()) {
            "the pinned Dirt-Samples subset (tidalcycles/Dirt-Samples@${pinnedManifest.commit.take(12)}: " +
                "${pinnedManifest.folders.joinToString(" ")}) is not usable at $dir — " +
                "${problems.size} problem(s), first: ${problems.first()}. To fix it, $PINNED_REMEDY. " +
                "There is no synth fallback for a published clip."
        }
        return DirtSampleLibrary.shared(dir, preload = false)
    }

    /**
     * The Dirt sample root on this machine, or null.
     *
     * Not cached: the env var is the documented override, and a caller that sets it and then
     * renders in the same session should get what they asked for.
     */
    fun dirtSampleRoot(): Path? = candidateRoots().firstOrNull { it.isDirectory() && Files.exists(it) }

    /** The shared library for [dirtSampleRoot], or null when there is nothing to load. */
    fun libraryOrNull(preload: Boolean = false): DirtSampleLibrary? =
        dirtSampleRoot()?.let { runCatching { DirtSampleLibrary.shared(it, preload = preload) }.getOrNull() }

    /** A rendered buffer, plus what it was actually made of. */
    class DirtRenderResult(
        val audio: PatSoundDocs.Stereo,
        /** False when no library was found and the whole thing came from [patVoice]. */
        val usedSamples: Boolean,
        /** Distinct tokens played from real samples. */
        val matched: Set<String>,
        /** Distinct tokens the library had nothing for, which fell back to the synth voice. */
        val unmatched: Set<String>,
    ) {
        /** One line fit to print in a box, so a demo can say which voice you are hearing. */
        fun describe(): String = when {
            !usedSamples -> "synth voice (no Dirt-Samples found; set ${ENV_VAR} to use real samples)"
            unmatched.isEmpty() -> "Dirt samples: ${matched.sorted().joinToString(" ")}"
            else -> "Dirt samples: ${matched.sorted().joinToString(" ")}" +
                " — synth voice for ${unmatched.sorted().joinToString(" ")}"
        }
    }
}

/**
 * Schedules [pattern] over [cycles] cycles at [cps] and mixes it from real Dirt samples.
 *
 * Samples are one-shots: a hit plays to its natural end rather than being gated by the length of
 * the arc that triggered it, which is what SuperDirt does and the reason a snare sounds like a
 * snare instead of a click. Only events with an onset trigger — a fragment carried over from a
 * previous arc is a continuation, and re-triggering it would flam.
 *
 * Honours the same `gain`, `pan` and `speed` annotations as [patRenderStereo], so the parameter
 * algebra applies unchanged.
 *
 * With [strict], a missing [library] or a token it has no sample for is an exception rather than
 * the synth voice; that is the mode every published clip uses.
 */
fun patRenderStereoDirt(
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    library: DirtSampleLibrary? = DirtRender.libraryOrNull(),
    voice: PatVoiceSpec = patVoice(),
    sampleRate: Int = PatSoundDocs.SR,
    strict: Boolean = false,
): DirtRender.DirtRenderResult {
    require(cycles > 0) { "cycles must be > 0" }
    require(cps > 0.0) { "cps must be > 0" }
    check(!strict || library != null) {
        "a strict Dirt render needs a sample library and none was found; ${DirtRender.PINNED_REMEDY}, " +
            "or set ${DirtRender.ENV_VAR} to a Dirt-Samples directory"
    }

    if (library == null) {
        return DirtRender.DirtRenderResult(
            audio = patRenderStereo(pattern, cycles, cps, voice, sampleRate),
            usedSamples = false,
            matched = emptySet(),
            unmatched = emptySet(),
        )
    }

    val secondsPerCycle = 1.0 / cps
    val frames = (cycles * secondsPerCycle * sampleRate).toInt()
    val left = FloatArray(frames)
    val right = FloatArray(frames)

    val matched = linkedSetOf<String>()
    val unmatched = linkedSetOf<String>()
    val leftovers = mutableListOf<PatEvent<String>>()

    val events = pattern.events(PatArc(PatQ.ZERO, PatQ.of(cycles)))
        .filter { it.hasOnset }
        .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    for (event in events) {
        val token = event.value.trim()
        val sample = runCatching { library.sample(token) }.getOrNull()
        if (sample == null) {
            check(!strict) {
                "a strict Dirt render met '$token', which ${library.root} has no sample for " +
                    "(it has ${library.families.keys.sorted().joinToString(" ")})"
            }
            unmatched += token
            leftovers += event
            continue
        }
        matched += token

        val startFrame = (event.arc.start.toDouble() * secondsPerCycle * sampleRate).toInt()
        if (startFrame >= frames) continue

        val gain = (event.annotations["gain"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 4.0)
        val pan = (event.annotations["pan"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0)
        val speed = (event.annotations["speed"]?.toDoubleOrNull() ?: 1.0).let {
            if (it == 0.0) 1.0 else it
        }
        val leftGain = (gain * sqrt(1.0 - pan)).toFloat()
        val rightGain = (gain * sqrt(pan)).toFloat()

        // One step through the source per output frame, accounting for both a sample recorded at a
        // different rate and an explicit `speed`.
        val step = (sample.sampleRate / sampleRate) * speed
        var position = 0.0
        var frame = startFrame
        while (frame < frames && position < sample.lengthSamples - 1) {
            left[frame] += sample.readLeft(position) * leftGain
            right[frame] += sample.readRight(position) * rightGain
            position += step
            frame++
        }
    }

    // Whatever the library had no sample for still gets a voice, mixed on top.
    if (leftovers.isNotEmpty()) {
        val rest = patRenderStereo(
            PatPattern { arc -> leftovers.filter { it.arc.start >= arc.start && it.arc.start < arc.end } },
            cycles, cps, voice, sampleRate,
        )
        for (i in 0 until minOf(frames, rest.left.size)) {
            left[i] += rest.left[i]
            right[i] += rest.right[i]
        }
    }

    return DirtRender.DirtRenderResult(
        audio = PatSoundDocs.Stereo(left, right),
        usedSamples = true,
        matched = matched,
        unmatched = unmatched,
    )
}

/**
 * Renders [pattern] through the real Dirt samples and puts an audio player in this box's output.
 *
 * The sample-backed twin of [showPatAudio]: same call shape, same annotations, but it sounds like
 * the drum machine the token names instead of a stand-in for one. Falls back to the synth voice
 * when the library is absent, and returns the result so a box can print [DirtRender.DirtRenderResult.describe]
 * and say which one played.
 */
fun Box.showDirtAudio(
    pattern: PatPattern<String>,
    cycles: Int = 4,
    cps: Double = 2.0,
    library: DirtSampleLibrary? = DirtRender.libraryOrNull(),
    voice: PatVoiceSpec = patVoice(),
    channel: String = "main",
): DirtRender.DirtRenderResult {
    val result = patRenderStereoDirt(pattern, cycles = cycles, cps = cps, library = library, voice = voice)
    display(
        channel,
        afield.four.audio.WavFloatArrayWriter.encodeStereo16(
            result.audio.left, result.audio.right, PatSoundDocs.SR, normalizePeak = 0.9f
        )
    )
    return result
}
