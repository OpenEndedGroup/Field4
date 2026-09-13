package afield.four.synth

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/*
FIELD_TEST
id: synth.docs.audio.provenance
runner: jvm-main
entrypoint: afield.four.synth.DocsAudioProvenance
tags: [smoke, synth, audio, wav, docs, provenance, cpu]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "docs audio provenance audited"
*/

/**
 * Every playable audio artifact the documentation publishes, and what actually made it.
 *
 * `sound.md` was written from [DirtRender]'s KDoc, which says that "every audio figure in the
 * documentation" is [patVoice]. This file exists because that sentence had never been checked, and
 * checking it is the whole job: **it is not true.** A large share of the published corpus comes from
 * [DoughDspSoundDocs], which renders DSP primitives and never calls [patVoice] at all. See
 * [Origin] for the families the sweep actually found — four of them when this object was written,
 * and the count is not repeated in this sentence, because the first version of it said
 * "three" while [Origin] already declared four. `DocsAudioProvenanceGuardTest` pins the numbers; a
 * KDoc paragraph is the wrong place to keep one.
 *
 * There is also a live failure mode here, not a hypothetical one: `56b124131` had to repair two
 * published figures that the code had stopped producing. So the measurement that matters most is
 * not "which voice" but **"does the committed asset still match what its producer makes today"** —
 * [Finding.reproducibility] — and that check applies to every asset regardless of family.
 *
 * Two independent measurements per asset:
 *
 *  * [Finding.reproducibility] — the committed `.wav` decoded off disk, correlated against a fresh
 *    render through its declared producer. ~1 means the asset is what the code makes now. This is
 *    the `56b124131` property, and it is the one a docs build can silently break.
 *  * [Finding.standInSimilarity] — against a [patVoice] render of the same pattern, where the
 *    producer is pattern-shaped. `NaN` where the concept does not apply, which is correct rather
 *    than convenient: a plate reverb has no pattern to render a stand-in of.
 *
 * Anything the sweep finds that cannot be re-derived has to be named in [UNDERIVABLE] with a
 * reason. The guard asserts that list too, so it cannot grow unnoticed.
 */
object DocsAudioProvenance {

    /** Where the pages live, relative to the repository root. */
    val DOCS_DIR: File get() = File("public/docs")

    /**
     * How a published clip was made — by *family of producer*, not by guess.
     *
     * The distinction that matters for the docs is the first one against the other two: `bd` as a
     * 62 Hz sine is a stand-in for a drum, whereas a plate reverb ringing down is not a stand-in
     * for anything. Calling both "synthesised" flattens the only difference a reader cares about.
     */
    enum class Origin(val description: String) {
        /** [patVoice]: one oscillator, one lowpass, one envelope, standing in for a drum kit. */
        STAND_IN_SYNTH("the stand-in synth voice (patVoice) — a sine and filtered noise"),

        /** Real Tidal Dirt-Samples one-shots, mixed offline. */
        DIRT_SAMPLES("real Dirt-Samples one-shots"),

        /** Real samples where the library had the token, [patVoice] per event where it did not. */
        MIXED_SAMPLES_AND_STAND_IN("real Dirt-Samples plus the stand-in synth per unmatched event"),

        /** [DoughDspSoundDocs]: DSP primitives driven by real signals. Not a stand-in for a kit. */
        DOUGH_DSP("a DoughDSP primitive run on a real signal — not a drum stand-in at all"),

        /** A Faust program compiled at runtime and rendered offline. See [SoundPathMap]. */
        FAUST("a Faust program compiled at runtime and rendered with no audio device"),

        /** Present in the tree, no producing code found. Must be in [UNDERIVABLE]. */
        UNDERIVABLE("no producing code found in the repository"),
    }

    /**
     * The renderer object named by a page's `audio-renderer=` attribute, and what it produces.
     *
     * [SoundRenderPathDocs] is absent: its figures each declare their own
     * [SoundRenderPathDocs.Voice], because it is the one producer that can emit more than one
     * family. It is resolved per figure in [originOf].
     */
    val ORIGIN_BY_RENDERER: Map<String, Origin> = mapOf(
        "afield.four.synth.PatSoundDocs" to Origin.STAND_IN_SYNTH,
        "afield.four.synth.PatMiniNotation" to Origin.STAND_IN_SYNTH,
        "afield.four.synth.DoughDspSoundDocs" to Origin.DOUGH_DSP,
    )

    /** The renderer a fence gets when it does not name one, matching `docs_hooks.py`. */
    const val DEFAULT_RENDERER = "afield.four.synth.DoughDspSoundDocs"

    /**
     * Audio the site ships that the fence sweep cannot reach, and therefore cannot re-derive.
     *
     * An entry here is a **finding**, not an exemption granted for convenience — it is the shape
     * of the problem `56b124131` had to fix by hand, one step worse, because there is no producer
     * to compare against at all. The guard asserts this map exactly, so it cannot grow in silence.
     *
     * Found by sweeping every tracked file under `public/docs` for an audio stream, not just for
     * `audio=` fences: 98 `.mp4`/`.mov` files were probed and exactly one carries audio.
     */
    val UNDERIVABLE: Map<String, String> = mapOf(
        "assets/banner.mp4" to
            "the homepage banner loop (referenced from public/field4/assets/homepage-video.js, " +
            "not from any page fence). It carries a 49.5s AAC track, but the player sets both " +
            "the muted property and the muted attribute and exposes no controls, so the track " +
            "is inaudible by construction — published bytes rather than published sound. There " +
            "is no renderer for it: re-deriving it would mean re-performing a screen recording",
    )

    /** One `audio="…"` fence found on a page. */
    class PublishedAudio(
        /** Page path relative to [DOCS_DIR], e.g. `pattern_language.md`. */
        val page: String,
        /** Asset path as the fence wrote it, relative to the page. */
        val assetRef: String,
        val spec: String,
        val renderer: String,
    ) {
        /** The asset on disk. */
        val file: File get() = File(DOCS_DIR, assetRef)

        /** A stable identifier for reports and for the guard's expected set. */
        val id: String get() = assetRef
    }

    /**
     * Every `audio="…"` fence on every page under [DOCS_DIR], including `reference/`.
     *
     * Mirrors `parse_runner_options` in `public/docs_hooks.py` closely enough to find the same
     * fences: the attribute block of a `*runner {…}` header, with `audio-spec` under any of the
     * spellings that file accepts.
     */
    fun scan(docsDir: File = DOCS_DIR): List<PublishedAudio> {
        val header = Regex("""```+[a-z_]*runner \{([^}\n]*)\}""")
        return docsDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.path }
            .flatMap { page ->
                val relative = page.relativeTo(docsDir).path
                header.findAll(page.readText()).mapNotNull { match ->
                    val attrs = match.groupValues[1]
                    fun attr(vararg names: String): String? = names.firstNotNullOfOrNull {
                        Regex("""\b$it="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                    }
                    val audio = attr("audio") ?: return@mapNotNull null
                    PublishedAudio(
                        page = relative,
                        assetRef = normalise(relative, audio),
                        spec = attr("audio-spec", "audio_spec", "audioSpec", "sound", "scene").orEmpty(),
                        renderer = attr("audio-renderer", "audio_renderer", "audioRenderer") ?: DEFAULT_RENDERER,
                    )
                }
            }
            .toList()
    }

    /** Resolves an asset reference written relative to its page into a docs-root-relative path. */
    private fun normalise(page: String, ref: String): String {
        val dir = File(page).parent ?: return ref
        return File(dir, ref).path.replace("\\", "/")
    }

    /** The family a fence's audio should belong to, or null when nothing declares one. */
    fun originOf(published: PublishedAudio): Origin? = when (published.renderer) {
        "afield.four.synth.SoundRenderPathDocs" ->
            when (SoundRenderPathDocs.figure(published.spec).voice) {
                SoundRenderPathDocs.Voice.STAND_IN -> Origin.STAND_IN_SYNTH
                SoundRenderPathDocs.Voice.DIRT_SAMPLES -> Origin.DIRT_SAMPLES
                SoundRenderPathDocs.Voice.MIXED -> Origin.MIXED_SAMPLES_AND_STAND_IN
            }

        // Also per figure, and for the same reason: the whole point of that page's comparison is
        // that one buffer is rendered through several *different* paths, so the renderer object
        // cannot name a single family. It reads the family off the figure's declared SoundPath.
        //
        // Two of the three are honestly *chains* — the stand-in synth's output, then a filter from
        // another path — and are classified by the processing rather than by the source, because
        // the processing is what the figure is about. The `standInSimilarity` column below says how
        // much of the stand-in survived, which is the number that keeps that classification honest.
        "afield.four.synth.SoundPathMap" ->
            when (SoundPathMap.comparison(published.spec).pathId) {
                "pat-stand-in" -> Origin.STAND_IN_SYNTH
                "signal-graph" -> Origin.DOUGH_DSP
                "faust" -> Origin.FAUST
                else -> null
            }

        else -> ORIGIN_BY_RENDERER[published.renderer]
    }

    // ── measurement ────────────────────────────────────────────────────────────────────────

    /** What the audit measured for one published asset. */
    class Finding(
        val published: PublishedAudio,
        val origin: Origin?,
        /** Correlation between the committed asset and a fresh render through its producer. */
        val reproducibility: Double,
        /** Correlation against a [patVoice] render of the same pattern, or `NaN` when N/A. */
        val standInSimilarity: Double,
        val frames: Int,
        val channels: Int,
        val peak: Double,
        val rms: Double,
        /** Non-null when the asset could not be re-rendered at all, with the reason. */
        val failure: String? = null,
    ) {
        val reproduces: Boolean get() = reproducibility >= REPRODUCIBILITY_FLOOR

        fun line(): String = buildString {
            append("%-46s".format(published.id))
            append(" %-26s".format(origin?.name ?: "UNKNOWN"))
            append(" R=%s".format(if (reproducibility.isNaN()) "  n/a " else "%.4f".format(reproducibility)))
            append(" S=%s".format(if (standInSimilarity.isNaN()) "  n/a " else "%.4f".format(standInSimilarity)))
            append(" %dch %.2fs peak=%.3f rms=%.4f".format(channels, frames / PatSoundDocs.SR.toDouble(), peak, rms))
            if (failure != null) append("  !! $failure")
        }
    }

    /**
     * Re-renders [published] through its declared producer and measures it against the committed
     * asset.
     *
     * The producer is called reflectively via its `renderWav(String, String)` — the same contract
     * `docs_hooks.py` invokes over the backdoor — so this audit exercises the real production path
     * rather than a re-implementation of it.
     */
    fun audit(published: PublishedAudio, scratch: File): Finding {
        val origin = originOf(published)
        val committedFile = published.file
        if (!committedFile.isFile) {
            return Finding(published, origin, Double.NaN, Double.NaN, 0, 0, 0.0, 0.0, "asset missing on disk")
        }
        val committed = readWav(committedFile)
        val peak = PatSoundDocs.peak(committed.mono)
        val rms = PatSoundDocs.rms(committed.mono)
        val channels = channelsOf(committedFile)

        val fresh = runCatching { renderThrough(published, scratch) }
        if (fresh.isFailure) {
            return Finding(
                published, origin, Double.NaN, Double.NaN, committed.frames, channels, peak, rms,
                "could not re-render: ${fresh.exceptionOrNull()?.message?.take(160)}",
            )
        }

        val reproducibility = SoundRenderPathDocs.waveformSimilarity(committed, fresh.getOrThrow())
        val standIn = runCatching {
            when (published.renderer) {
                "afield.four.synth.SoundRenderPathDocs" ->
                    SoundRenderPathDocs.waveformSimilarity(
                        committed, SoundRenderPathDocs.standInReference(published.spec),
                    )

                // The comparison figures share one stand-in render by construction — it is the
                // *input* to all three — so this column reads as "how much of the dry beat is left".
                "afield.four.synth.SoundPathMap" ->
                    SoundRenderPathDocs.waveformSimilarity(committed, SoundPathMap.dryBeat())

                else -> Double.NaN
            }
        }.getOrDefault(Double.NaN)

        return Finding(published, origin, reproducibility, standIn, committed.frames, channels, peak, rms)
    }

    /** Calls `<renderer>.renderWav(spec, tmp)` reflectively and reads the result back. */
    private fun renderThrough(published: PublishedAudio, scratch: File): PatSoundDocs.Stereo {
        val instance = Class.forName(published.renderer).getDeclaredField("INSTANCE").get(null)
        val method = instance.javaClass.getMethod("renderWav", String::class.java, String::class.java)
        val out = File(scratch, "audit-${published.id.replace('/', '-')}")
        out.parentFile?.mkdirs()
        method.invoke(instance, published.spec, out.absolutePath)
        check(out.isFile) { "${published.renderer}.renderWav wrote no file" }
        return readWav(out)
    }

    /** Declared channel count of a WAV, before it is folded for measurement. */
    fun channelsOf(file: File): Int = AudioSystem.getAudioInputStream(file).use { it.format.channels }

    /**
     * Reads a 16-bit PCM WAV into the renderer's own shape.
     *
     * Mono is duplicated into both channels rather than rejected: [DoughDspSoundDocs] writes mono
     * and [PatSoundDocs] writes stereo, and the audit has to compare like with like across both.
     */
    fun readWav(file: File): PatSoundDocs.Stereo {
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            require(format.encoding == AudioFormat.Encoding.PCM_SIGNED) { "$file: not PCM_SIGNED" }
            require(format.sampleSizeInBits == 16) { "$file: ${format.sampleSizeInBits}-bit, expected 16" }
            val channels = format.channels
            require(channels in 1..2) { "$file: $channels channels" }

            val bytes = stream.readAllBytes()
            val frames = bytes.size / (2 * channels)
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            val big = format.isBigEndian
            for (i in 0 until frames) {
                val base = i * 2 * channels
                left[i] = pcm16(bytes, base, big)
                right[i] = if (channels == 2) pcm16(bytes, base + 2, big) else left[i]
            }
            return PatSoundDocs.Stereo(left, right)
        }
    }

    private fun pcm16(bytes: ByteArray, at: Int, bigEndian: Boolean): Float {
        val lo = bytes[if (bigEndian) at + 1 else at].toInt() and 0xFF
        val hi = bytes[if (bigEndian) at else at + 1].toInt()
        return (((hi shl 8) or lo).toShort()).toFloat() / 32768f
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    /** Audits the whole corpus and prints one line per asset. Writes no committed assets. */
    @JvmStatic
    fun main(args: Array<String>) {
        val scratch = File(System.getProperty("java.io.tmpdir"), "docs-audio-provenance").apply { mkdirs() }
        val published = scan()
        println("swept ${DOCS_DIR.path}: ${published.size} published audio fence(s)")
        println()

        val findings = published.map { audit(it, scratch) }
        findings.forEach { println("  " + it.line()) }
        println()

        findings.groupBy { it.origin }.toSortedMap(compareBy { it?.ordinal ?: 99 }).forEach { (origin, group) ->
            println("  ${group.size} x ${origin?.name ?: "UNKNOWN"} — ${origin?.description ?: "nothing declares a producer"}")
        }
        println()
        UNDERIVABLE.forEach { (id, reason) -> println("  UNDERIVABLE $id — $reason") }
        println()

        val broken = findings.filter { it.failure != null || !it.reproduces }
        broken.forEach { println("  PROBLEM ${it.line()}") }
        check(broken.isEmpty()) {
            "${broken.size} published audio asset(s) no longer match what their producer renders"
        }
        check(findings.none { it.origin == null }) {
            "unclassified audio: ${findings.filter { it.origin == null }.map { it.published.id }}"
        }

        println("docs audio provenance audited: ${findings.size} asset(s), ${UNDERIVABLE.size} underivable")
    }

    /**
     * A committed asset must still be what its producer renders.
     *
     * This is the `56b124131` guard generalised to the whole corpus. Measured across all fifteen
     * published clips: the lowest reproducibility is 1.0000 and so is the highest — every one is
     * bit-stable through peak normalisation and 16-bit quantisation, which is what a deterministic
     * offline renderer should do. 0.98 leaves room for a floating-point drift that changes nothing
     * audible, and none at all for a figure the code has stopped producing.
     */
    const val REPRODUCIBILITY_FLOOR = 0.98
}
