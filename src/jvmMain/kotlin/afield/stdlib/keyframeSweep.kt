@file:afield.CompletionCategory("Graphics")

/**
 * Overnight exploration of a keyframe space: a directory of saved keys in, an archive of renders out.
 *
 * A [KeyframeScope] gives you one look at a time — capture it, edit it, put it back. A directory of
 * saved keys is something else: a *space*, whose interesting points are mostly the ones nobody has
 * visited. [KeyframeSweep] visits them while you sleep. Each pass draws a few keys from the pool,
 * blends them at random weights (sometimes past either end of the hull), applies the blend to the
 * live scope, makes a directory, and hands that directory to a `suspend` callback of yours which
 * runs to completion before the next pass starts. What the callback writes there is its business —
 * a still, a clip, a mesh, a CSV. What the sweep writes there is the provenance: the blended key as
 * ordinary key JSON, and a manifest saying which keys went in at which weights.
 *
 * The point of the archive is what happens to it afterwards. A night of this is a few thousand
 * directories to categorise, cluster, score or hand to a model, and the sweep's job is to make that
 * second pass possible: every render is self-describing, `index.jsonl` is one line per render for a
 * reader that does not want to walk the tree, and `renders/` is itself a valid keyframe pool — so
 * tonight's output is a legitimate input for tomorrow night.
 *
 * ## The blend is a fold, not new maths
 *
 * [KeyframeScope.interpolate] already knows how every lane type blends — numbers and vectors lerp,
 * quaternions slerp, `CameraPose` sclerp, code blends or steps. So an N-way blend here is a
 * *pairwise fold* over it rather than a second type table that would immediately drift from the
 * first: with weights sorted descending and running sums `S`, folding `t = w / S` at each step
 * yields exactly the affine weighted mean for every linear lane, and inherits the rest for free.
 *
 * Sorting descending is what makes it safe rather than merely neat. Weights sum to 1, so the running
 * sums start at the largest weight and end at 1, rising while the terms are positive and falling
 * once they go negative — the minimum is therefore `min(w_max, 1)`, which is always positive. No
 * division ever approaches zero, including under extrapolation, where some weights *are* negative.
 *
 * Three things the fold alone gets wrong, and this file handles on purpose — see [DiscreteBlend],
 * [MissingSlots] and [SweepConfig.preserveNumericKind]. All three are recorded per render rather
 * than assumed, because an archive you cannot interrogate later is a wasted night.
 *
 * ## What a night survives
 *
 * Nothing here trusts the run to finish. A render's manifest is written *before* its callback and
 * rewritten after, atomically, so a crashed pass is identifiable as one; [SweepConfig.resume] picks
 * up where the process died and re-derives each render's randomness from `seed + index`, so skipping
 * completed work cannot change what the unfinished work would have drawn. A throwing callback is
 * one failed render, not a lost night — until [SweepConfig.maxConsecutiveFailures] of them in a row
 * says the night is broken. And [SweepConfig.minFreeBytes] stops the sweep before the disk does.
 */

package afield.stdlib

import afield.logger.lprintln
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Policies
// ---------------------------------------------------------------------------------------------

/**
 * What a blend does with the lanes that have no midpoint: strings, [StackSLString] programs and
 * `SourceBackedBlock` Kotlin.
 *
 * [KeyframeScope.interpolate] *steps* these, to the right-hand value once `t >= 1`. Inside a fold
 * that is unpredictable rather than wrong: `t = w / S` can exceed 1 whenever weights have mixed
 * signs, which is exactly what extrapolation produces, so which of five programs came out would
 * depend on arithmetic nobody chose. So the choice is made explicitly instead.
 */
@Serializable
enum class DiscreteBlend {
    /**
     * One key wins every discrete lane — the *anchor*, drawn by roulette over the blend weights, so
     * a key contributing 60% of the numbers supplies the code 60% of the time. The default: it keeps
     * a blend's code coherent with itself, which matters when two lanes are a vertex and a fragment
     * shader that have to compile together.
     */
    ANCHOR,

    /**
     * Each discrete lane draws its own winner, independently, by the same roulette. Recombines code
     * across keys — a richer space, and one where a pair of lanes that must agree no longer will.
     */
    ROULETTE_PER_LANE,

    /**
     * Leave whatever the fold produced. The mode to pick when the scope has a
     * [KeyframeScope.codeBlend] configured and arity is 2, because then the fold's single
     * `interpolate` really is a code blend rather than a step, and overwriting it would throw the
     * interesting result away.
     */
    AS_INTERPOLATED
}

/**
 * What a blend does when its participants disagree about which lanes exist.
 *
 * They will disagree. A pool accumulates over weeks while the scope grows targets, and
 * `interpolate` drops any name the two sides do not share — so a naive fold silently shrinks to the
 * intersection of everything it touched, and the oldest key in the pool decides how much of the
 * look is explored. (`KeyframeCollection`'s slot pivot exists to show you this drift; this enum is
 * what to do about it.)
 */
@Serializable
enum class MissingSlots {
    /**
     * Back-fill a participant's missing lanes from `scope.capture()` — the live sketch's current
     * value. The default, because the live scope is the only authority on what the lane *means*
     * now, and because it makes an old two-lane key a usable participant in a forty-lane blend
     * instead of a lane-eater. Every fill is listed in the render manifest.
     */
    FILL_FROM_LIVE,

    /** Back-fill from the anchor key instead, keeping the blend inside the pool's own values. */
    FILL_FROM_ANCHOR,

    /** Blend only the lanes every participant has. Honest, and shrinks as the pool ages. */
    INTERSECT,

    /**
     * Never blend a partial key: pool files whose lanes do not cover every slot the scope watches
     * are excluded at load time, and [SweepReport.ineligible] says how many. Use when a missing
     * lane means a broken render rather than a stale default.
     */
    REQUIRE_ALL
}

// ---------------------------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------------------------

/**
 * Every knob a sweep has, as one serializable value — so the run's own configuration is recorded in
 * `sweep.json` beside its output and a night is reproducible from its archive rather than from
 * whatever the box said at the time.
 *
 * The three that decide what a night *looks like* are [concentration], [minArity]/[maxArity] and
 * [extrapolationProbability]. The rest decide whether it finishes.
 */
@Serializable
data class SweepConfig(
    /** Root of the run's randomness. Every render draws from `seed` and its own index, nothing else. */
    val seed: Long = 1L,

    /** How many renders to attempt. Left unbounded, [maxDurationMs] or the disk floor ends the run. */
    val count: Int = Int.MAX_VALUE,

    /** Which files under the pool directory are keys. See [everyKeyGlob], and `keyFilesMatching`. */
    val pattern: String = everyKeyGlob,

    /**
     * Fewest keys in one blend. `1` is meaningful and worth allowing: a blend of one key is that key
     * replayed exactly, which salts the archive with unmixed references to compare the blends against.
     */
    val minArity: Int = 2,

    /** Most keys in one blend, clamped to the pool size. */
    val maxArity: Int = 3,

    /**
     * The Dirichlet concentration the weights are drawn from, and the single most consequential
     * number here.
     *
     * At `1.0` the weights are uniform over the simplex. Below it they bunch into the corners, so a
     * blend is mostly one key with the others as perturbation — the setting for a night that should
     * stay recognisable. Well above it they even out, and every blend is the average of its
     * participants, which for a dense pool means a night of mush. Start at `0.6` if the point is
     * variety you can still attribute.
     */
    val concentration: Double = 1.0,

    /**
     * How often a blend is pushed outside the hull of its participants.
     *
     * An extrapolated look is the one a person would not have authored: past the end of every key
     * you saved rather than between them. It is also where a look most often falls apart, which is
     * why this is a fraction of the night rather than all of it.
     */
    val extrapolationProbability: Double = 0.15,

    /**
     * Least push, measured in hull-crossings from the anchor.
     *
     * `1.0` lands exactly on the far face of the hull — the anchor's own weight passes through zero
     * there — so `1.2` is a fifth of the way again beyond it. Below `1.0` would be inside the hull
     * and is refused, because a "gain" that sometimes extrapolated and sometimes did not would make
     * the archive's `extrapolationGain` field mean nothing.
     */
    val minExtrapolationGain: Double = 1.2,

    /** Most push. Past about `3.0` most looks fall apart, which is occasionally the point. */
    val maxExtrapolationGain: Double = 2.0,

    /** See [DiscreteBlend]. */
    val discrete: DiscreteBlend = DiscreteBlend.ANCHOR,

    /** See [MissingSlots]. */
    val missingSlots: MissingSlots = MissingSlots.FILL_FROM_LIVE,

    /**
     * Round each blended number back to the numeric type the anchor held it in.
     *
     * On by default because [KeyframeScope.interpolate] returns `Double` unless *both* sides were
     * `Float`, so an `Int` lane — an instance count, a subdivision level — comes back as a `Double`
     * that an `Int` uniform will refuse. Turn it off only to see the unrounded trajectory.
     *
     * Rounded, **not** clamped. An extrapolated blend can put an `Int` lane outside the range its
     * `intTarget` declares — 14 instances where 9 is the maximum — and the clamp stays where it
     * already lives, in the target, on apply. So the key records the point in the space that was
     * actually visited *and* still reproduces the render exactly; clamping in the key would lose the
     * first without gaining the second.
     */
    val preserveNumericKind: Boolean = true,

    /** Write the blend to the live scope before calling the callback. Off makes the sweep a pure generator. */
    val applyToScope: Boolean = true,

    /**
     * Put the scope back the way it was found when the run ends, however it ends.
     *
     * The restore is a `capture()` taken before the first render and invoked in a `finally`, so it
     * inherits `capture`'s own caveat: a key holds the *values* it read, not copies, and a target
     * registered with a bare getter can therefore hand it the live mutable vector rather than a
     * snapshot of one. The typed registrations ([KeyframeScope.vector3Target] and friends) copy in
     * both directions and are safe; a hand-rolled `target(name, get, set)` over a `Vector3f` is the
     * shape to check before trusting this.
     */
    val restoreScopeAtEnd: Boolean = true,

    /** Wall-clock budget in milliseconds; `0` for none. The "stop at 7am" knob. */
    val maxDurationMs: Long = 0,

    /**
     * Stop while this many bytes are still free on the archive's filesystem. Eight gigabytes by
     * default, which is generous on purpose: an overnight sweep of GPU stills is a *rate* of disk
     * consumption, and the thing that goes wrong is not the archive being large but the machine
     * having nothing left at 4am.
     */
    val minFreeBytes: Long = 8L shl 30,

    /** Consecutive failures that mean the night is broken rather than the render unlucky. */
    val maxConsecutiveFailures: Int = 5,

    /** Skip renders a previous process already finished. See [KeyframeSweep.run]. */
    val resume: Boolean = true,

    /** Keep the directory of a failed render, with its manifest and partial output, for forensics. */
    val keepFailedRenders: Boolean = true
) {
    init {
        require(minArity >= 1) { "minArity must be at least 1, not $minArity" }
        require(maxArity >= minArity) { "maxArity ($maxArity) must be at least minArity ($minArity)" }
        require(concentration > 0.0) { "concentration must be positive, not $concentration" }
        require(extrapolationProbability in 0.0..1.0) {
            "extrapolationProbability must be in 0..1, not $extrapolationProbability"
        }
        require(minExtrapolationGain >= 1.0) {
            "minExtrapolationGain must be at least 1.0 (1.0 is no extrapolation), not $minExtrapolationGain"
        }
        require(maxExtrapolationGain >= minExtrapolationGain) {
            "maxExtrapolationGain ($maxExtrapolationGain) must be at least minExtrapolationGain ($minExtrapolationGain)"
        }
        require(maxConsecutiveFailures >= 1) { "maxConsecutiveFailures must be at least 1" }
    }

    /** [minArity]`..`[maxArity], for reading. */
    val arity: IntRange get() = minArity..maxArity
}

// ---------------------------------------------------------------------------------------------
// The record written to disk
// ---------------------------------------------------------------------------------------------

/** Bumped when the archive layout changes in a way a reader of an older archive would notice. */
const val sweepManifestFormat: Int = 1

/** One key's part in one blend. */
@Serializable
data class SweepParent(
    /** Path relative to the pool root, `/` separated — the pool's own identity for the key. */
    val key: String,
    /** Its weight in the affine blend. Sums to 1 across a render's parents; negative under extrapolation. */
    val weight: Double,
    /** `schemaVersion` as the file carried it. More than one across a render's parents means schema drift. */
    val schemaVersion: Int,
    /** Lanes this key did not have, filled per [MissingSlots]. Empty is the healthy case. */
    val filled: List<String> = emptyList()
)

/** What a render's callback left behind, as recorded by [SweepRender.artifact]. */
@Serializable
data class SweepArtifact(
    /** Free-form: `still`, `clip`, `frames`, whatever the callback calls it. */
    val kind: String,
    /** Path relative to the render directory, or null when the artifact could not be produced. */
    val file: String? = null,
    val bytes: Long = 0,
    /** Why it is missing, when [file] is null. Never a fabricated file; always a stated gap. */
    val note: String? = null
)

/**
 * One render, completely described — the file an offline pass reads to decide what it is looking at.
 *
 * Written twice: once with `status` `running` before the callback starts, once with its terminal
 * status after. A manifest still reading `running` is a render whose process died inside it.
 */
@Serializable
data class SweepRenderManifest(
    val format: Int = sweepManifestFormat,
    val index: Int,
    val id: String,
    /** The per-render seed, derived from [SweepConfig.seed] and [index]. Redraws this render exactly. */
    val seed: Long,
    /** `running`, `ok`, `failed` or `cancelled`. */
    val status: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val durationMs: Long? = null,
    /** The keys that went in, and at what weight. */
    val parents: List<SweepParent>,
    /**
     * The parent that won the discrete lanes, and supplies numeric types under
     * `preserveNumericKind` — drawn by roulette over the final weights, so it is usually the key
     * that dominates the picture.
     */
    val anchor: String,
    /**
     * The parent this blend was pushed *away* from, for an extrapolated render — a different key
     * from [anchor], and deliberately so: the earliest version of this gave one key both jobs, and
     * produced blends wearing the palette of the look they had just fled.
     */
    val extrapolationPole: String? = null,
    /**
     * How far outside the hull this blend sits, in hull-crossings from the anchor — `1.0` is exactly
     * on the far face, `1.5` is half as far again beyond it. Null for an ordinary convex blend.
     */
    val extrapolationGain: Double? = null,
    /** Discrete lane name to the parent it came from — the provenance the fold could not be trusted with. */
    val discreteLanes: Map<String, String> = emptyMap(),
    /** Lanes present in the participants that the blend could not produce, usually a type disagreement. */
    val droppedLanes: List<String> = emptyList(),
    /** How many lanes the blended key carries. */
    val laneCount: Int = 0,
    val artifacts: List<SweepArtifact> = emptyList(),
    /** The callback's failure, first line only. Null unless `status` is `failed`. */
    val error: String? = null
)

/** The run as a whole: what it was asked to do, against what it found. Written to `sweep.json`. */
@Serializable
data class SweepRunManifest(
    val format: Int = sweepManifestFormat,
    val config: SweepConfig,
    val pool: String,
    val out: String,
    val startedAt: String,
    val finishedAt: String? = null,
    /** Pool keys eligible to be blended. */
    val poolSize: Int = 0,
    /** Pool keys excluded by [MissingSlots.REQUIRE_ALL]. */
    val ineligible: Int = 0,
    /** Lane names the live scope watched when the run started. */
    val scopeSlots: List<String> = emptyList(),
    /** `KeyframeCollection`'s per-file complaints — damaged keys the pool contains. */
    val poolProblems: List<String> = emptyList(),
    /** `KeyframeCollection.skipSummary()`: what the glob hid, and what was not a key at all. */
    val poolSkipped: String? = null,
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val stoppedBecause: String? = null
)

/** How a run ended, returned by [KeyframeSweep.run] and also the tail of `sweep.json`. */
data class SweepReport(
    val out: Path,
    /** Renders whose callback returned normally. */
    val completed: Int,
    /** Renders whose callback threw. Their directories survive unless configured otherwise. */
    val failed: Int,
    /** Renders a previous process had already finished. */
    val skipped: Int,
    /** Pool keys excluded by [MissingSlots.REQUIRE_ALL]. */
    val ineligible: Int,
    val poolSize: Int,
    val elapsedMs: Long,
    /**
     * `count`, `duration`, `disk`, `failures` or `cancelled` — why the loop stopped. Worth printing
     * in the morning: `disk` and `failures` mean the night was cut short, and the render count alone
     * does not say which.
     */
    val stoppedBecause: String
) {
    /**
     * The one line worth printing in the morning: the counts, and *why the run stopped*.
     *
     * Note that in a box `println(report)` will not reach this — Field's object inspector renders a
     * data class field by field and ignores a hand-written `toString`. Interpolate it (`"$report"`).
     */
    override fun toString() =
        "sweep: $completed ok, $failed failed, $skipped skipped, stopped on $stoppedBecause " +
            "(${elapsedMs / 1000}s, pool of $poolSize) -> $out"
}

// ---------------------------------------------------------------------------------------------
// One render, as the callback sees it
// ---------------------------------------------------------------------------------------------

/**
 * The handle a sweep's callback is given: where to write, what it is rendering, and how it got here.
 *
 * [dir] already exists and is yours. [key] is the blend — already applied to the live scope, unless
 * [SweepConfig.applyToScope] was turned off — and [manifest] is the provenance that will be written
 * beside whatever you leave in the directory.
 */
class SweepRender internal constructor(
    /** Zero-based position in the run. Stable across a [SweepConfig.resume]. */
    val index: Int,
    /** `000123-4f2a9c`, the directory's name: ordered by index, and distinct per seed. */
    val id: String,
    /** The render's own directory, created before the callback is called. */
    val dir: Path,
    /** The blended key. Also written to `key.json` in [dir] before the callback runs. */
    val key: KeyframeScope.Key,
    /** This render's seed — for a callback that wants its own reproducible randomness. */
    val seed: Long,
    manifest: SweepRenderManifest
) {
    /** The provenance record, as it will be written. [artifact] is the one part a callback adds. */
    var manifest: SweepRenderManifest = manifest
        internal set

    /** A path inside [dir]. Creates nothing; the callback writes it. */
    fun file(name: String): Path = dir.resolve(name)

    /**
     * Record something the callback produced, so the offline pass can find it without guessing file
     * names.
     *
     * Call it with a null [file] and a [note] when the artifact *could not* be produced — no ffmpeg,
     * an empty readback. An archive that says "no clip, ffmpeg absent" is usable; one that quietly
     * has no clip is a mystery in the morning, and one with a fabricated clip is worse than both.
     */
    fun artifact(kind: String, file: Path? = null, note: String? = null) {
        val relative = file?.let { runCatching { dir.relativize(it).toString() }.getOrNull() ?: it.name }
        val bytes = file?.let { runCatching { Files.size(it) }.getOrDefault(0L) } ?: 0L
        manifest = manifest.copy(artifacts = manifest.artifacts + SweepArtifact(kind, relative, bytes, note))
    }
}

// ---------------------------------------------------------------------------------------------
// The sweep
// ---------------------------------------------------------------------------------------------

/**
 * A random walk of a keyframe space, with a render at every stop.
 *
 * ```
 * val sweep = KeyframeSweep(scope, "looks".path, "archive".path, SweepConfig(seed = 7, count = 500))
 * frames {
 *     val report = sweep.run { render ->
 *         renderer.awaitFrames(2)
 *         render.captureStill(renderer, rtt)
 *     }
 *     println("$report")
 * }
 * ```
 *
 * `run` is the whole API. It is a `suspend` function that returns when the run is over, so a box
 * hosts it inside a `frames { }` — which puts it on the Runloop, lets a callback wait for real
 * frames, and makes the stop key cancel it cleanly.
 *
 * `frames` rather than the `start` you would reach for first: `start` has both a `Frames`-receiver
 * overload and a receiverless one, and a bare `{ … }` satisfies both, so `start { … }` is an
 * **overload resolution ambiguity** — everywhere, not only in a box. It goes unnoticed because
 * nothing in the repository calls it that way: every existing call site passes a callable reference
 * (`start(renderer::run)`), which picks an overload. `frames` has one overload and no such question.
 *
 * And the report is interpolated rather than passed bare, because `println(anything)` in a box goes
 * through Field's object inspector, which renders a data class field by field and ignores its
 * `toString`.
 *
 * The pool is read once, at the start: adding keys to the directory mid-run does not extend the
 * space being explored, which is deliberate — a run's `sweep.json` describes a fixed pool, and a
 * pool that changed underneath would make the archive's provenance unverifiable.
 */
class KeyframeSweep(
    /** The live scope. Supplies the target set, the blend maths, and (by default) the fill values. */
    val scope: KeyframeScope,
    /** Directory of saved keys to draw from. Read once, non-destructively. */
    val pool: Path,
    /** Archive root. Created if absent; added to if it already holds a run. */
    val out: Path,
    val config: SweepConfig = SweepConfig(),
    /**
     * Called after every terminal render, completed or failed, with the manifest as written. The
     * place to put progress reporting — a `display` table, a line in a log, a push to a dashboard.
     */
    val onRender: (SweepRenderManifest) -> Unit = {}
) {
    private val compactJson = Json { encodeDefaults = true; classDiscriminator = "type" }
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true; classDiscriminator = "type" }

    /** `out/renders`, one directory per render. Itself a valid keyframe pool, via the `key.json` in each. */
    val rendersDir: Path get() = out.resolve("renders")

    /** `out/index.jsonl`: one compact [SweepRenderManifest] per line, for a reader that will not walk the tree. */
    val indexFile: Path get() = out.resolve("index.jsonl")

    /** `out/sweep.json`: the run's own configuration and pool census. */
    val runManifestFile: Path get() = out.resolve("sweep.json")

    /**
     * Run the sweep, calling [body] once per render and waiting for it each time.
     *
     * [body] runs to completion: nothing here cancels it, times it out, or runs the next render
     * beside it. A callback that throws fails that one render — recorded, directory kept — and the
     * run continues until [SweepConfig.maxConsecutiveFailures] in a row says otherwise.
     *
     * Stops on whichever comes first of [SweepConfig.count], [SweepConfig.maxDurationMs], the
     * [SweepConfig.minFreeBytes] disk floor, the consecutive-failure limit, or cancellation.
     * Cancellation (the stop key) marks the in-flight render `cancelled`, restores the scope and
     * rethrows, so the archive is consistent even when the run was not finished.
     *
     * With [SweepConfig.resume] on, an index whose directory already holds a terminal manifest is
     * skipped. A directory left `running` by a dead process is *not* skipped: it is deleted and
     * redone, since its callback got some way through writing files nobody can vouch for.
     */
    suspend fun run(body: suspend (SweepRender) -> Unit): SweepReport {
        val startedAtNs = System.nanoTime()
        val startedAt = Instant.now()
        out.createDirectories()
        rendersDir.createDirectories()

        val live = scope.capture()
        val scopeSlots = live.values.keys.sorted()
        val loaded = loadPool(scopeSlots)

        var runManifest = SweepRunManifest(
            config = config,
            pool = pool.toString(),
            out = out.toString(),
            startedAt = startedAt.toString(),
            poolSize = loaded.keys.size,
            ineligible = loaded.ineligible,
            scopeSlots = scopeSlots,
            poolProblems = loaded.problems,
            poolSkipped = loaded.skipSummary
        )
        writeAtomic(runManifestFile, prettyJson.encodeToString(runManifest))

        require(loaded.keys.isNotEmpty()) {
            "keyframe sweep: no usable keys in $pool (pattern '${config.pattern}')" +
                loaded.problems.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = " — ") { it }.orEmpty()
        }

        var completed = 0
        var failed = 0
        var skipped = 0
        var consecutiveFailures = 0
        var stoppedBecause = "count"

        try {
            var index = 0
            while (index < config.count) {
                val budget = budgetStop(startedAtNs)
                if (budget != null) {
                    stoppedBecause = budget
                    break
                }

                val renderSeed = seedFor(index)
                val id = renderId(index, renderSeed)
                val dir = rendersDir.resolve(id)

                if (config.resume && isFinished(dir)) {
                    skipped++
                    index++
                    continue
                }
                if (dir.exists()) deleteTree(dir)

                val draw = draw(index, renderSeed, loaded.keys)
                val blended = blend(draw, live)
                dir.createDirectories()

                var manifest = SweepRenderManifest(
                    index = index,
                    id = id,
                    seed = renderSeed,
                    status = "running",
                    startedAt = Instant.now().toString(),
                    parents = blended.parents,
                    anchor = draw.anchorKey,
                    extrapolationPole = draw.poleKey,
                    extrapolationGain = draw.gain,
                    discreteLanes = blended.discreteLanes,
                    droppedLanes = blended.dropped,
                    laneCount = blended.key.values.size
                )
                writeAtomic(dir.resolve("manifest.json"), prettyJson.encodeToString(manifest))
                Files.writeString(dir.resolve("key.json"), scope.encodeKeyJson(blended.key))

                if (config.applyToScope) blended.key()

                val render = SweepRender(index, id, dir, blended.key, renderSeed, manifest)
                val renderStartNs = System.nanoTime()
                var failure: Throwable? = null
                try {
                    body(render)
                } catch (cancellation: CancellationException) {
                    withContext(NonCancellable) {
                        finish(render, "cancelled", renderStartNs, null)
                    }
                    stoppedBecause = "cancelled"
                    throw cancellation
                } catch (t: Throwable) {
                    failure = t
                }

                manifest = finish(render, if (failure == null) "ok" else "failed", renderStartNs, failure)
                onRender(manifest)

                if (failure == null) {
                    completed++
                    consecutiveFailures = 0
                } else {
                    failed++
                    consecutiveFailures++
                    lprintln(
                        "warning,keyframe",
                        "keyframe sweep render $id failed: ${failure.message ?: failure::class.simpleName}"
                    )
                    if (!config.keepFailedRenders) deleteTree(dir)
                    if (consecutiveFailures >= config.maxConsecutiveFailures) {
                        stoppedBecause = "failures"
                        index++
                        break
                    }
                }
                index++
            }
        } finally {
            withContext(NonCancellable) {
                if (config.restoreScopeAtEnd) runCatching { live() }
                runManifest = runManifest.copy(
                    finishedAt = Instant.now().toString(),
                    completed = completed,
                    failed = failed,
                    skipped = skipped,
                    stoppedBecause = stoppedBecause
                )
                runCatching { writeAtomic(runManifestFile, prettyJson.encodeToString(runManifest)) }
            }
        }

        return SweepReport(
            out = out,
            completed = completed,
            failed = failed,
            skipped = skipped,
            ineligible = loaded.ineligible,
            poolSize = loaded.keys.size,
            elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
            stoppedBecause = stoppedBecause
        )
    }

    /**
     * Rebuild `index.jsonl` from the `manifest.json` files under `renders/`, and answer how many
     * lines it wrote.
     *
     * The index is a projection, not the record: it is appended to as renders finish, so a process
     * killed between a manifest write and its append leaves it one line short, and two runs over one
     * archive interleave rather than merge. The manifests are authoritative; this makes the index
     * agree with them again. Cheap enough to call before an offline pass, and the honest thing to do
     * after a crash.
     */
    fun reindex(): Int {
        if (!rendersDir.isDirectory()) return 0
        val lines = rendersDir.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
            .mapNotNull { readManifest(it) }
            .map { compactJson.encodeToString(it) }
        writeAtomic(indexFile, lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
        return lines.size
    }

    // ---- pool ---------------------------------------------------------------------------------

    private class LoadedPool(
        val keys: List<PoolKey>,
        val ineligible: Int,
        val problems: List<String>,
        val skipSummary: String?
    )

    /** One pool entry: where it came from, and what it holds. Re-read per use — see [PoolKey.load]. */
    private inner class PoolKey(val path: Path, val relative: String, val schemaVersion: Int, val lanes: Set<String>) {
        /**
         * A fresh [KeyframeScope.Key] from disk, every time.
         *
         * Deliberately uncached. A `Key`'s values are the live objects a target gets bound to, and a
         * `Vector3f` is mutable, so a cached participant could end up aliased into the running
         * sketch by a blend that happened to pass its value through. Re-parsing a few kilobytes of
         * JSON costs nothing beside a render.
         */
        fun load(): KeyframeScope.Key = scope.loadKey(path)
    }

    private fun loadPool(scopeSlots: List<String>): LoadedPool {
        val collection = KeyframeCollection.load(pool, config.pattern, scope)
        val all = collection.files.map {
            PoolKey(it.path, it.relative, it.schemaVersion, it.values.keys)
        }
        val eligible =
            if (config.missingSlots == MissingSlots.REQUIRE_ALL) all.filter { it.lanes.containsAll(scopeSlots) }
            else all
        return LoadedPool(
            keys = eligible,
            ineligible = all.size - eligible.size,
            problems = collection.problems,
            skipSummary = collection.skipSummary()
        )
    }

    // ---- the draw -----------------------------------------------------------------------------

    private inner class Draw(
        val seed: Long,
        val participants: List<PoolKey>,
        val weights: DoubleArray,
        val anchor: Int,
        val pole: Int,
        val gain: Double?
    ) {
        val anchorKey: String get() = participants[anchor].relative
        val poleKey: String? get() = pole.takeIf { it >= 0 }?.let { participants[it].relative }
    }

    /**
     * One render's randomness, and all of it: arity, participants, weights, anchor, extrapolation.
     *
     * Drawn from a [Random] seeded by this index alone, which is what makes `resume` sound — the
     * draw for render 4000 does not depend on whether renders 0..3999 happened in this process.
     */
    private fun draw(index: Int, seed: Long, keys: List<PoolKey>): Draw {
        val rng = Random(seed)
        val hi = min(config.maxArity, keys.size)
        val lo = min(config.minArity, hi)
        val arity = if (hi <= lo) lo else lo + rng.nextInt(hi - lo + 1)

        // Sample without replacement: a partial Fisher-Yates over indices, so the cost is the arity
        // rather than the pool size.
        val pickable = IntArray(keys.size) { it }
        val participants = ArrayList<PoolKey>(arity)
        for (i in 0 until arity) {
            val j = i + rng.nextInt(keys.size - i)
            val tmp = pickable[i]; pickable[i] = pickable[j]; pickable[j] = tmp
            participants += keys[pickable[i]]
        }

        val weights = dirichlet(rng, arity, config.concentration)

        // The *pole* is the key a blend is pushed away from, which is a different job from owning the
        // discrete lanes — and giving one key both made an extrapolated blend wear the palette of the
        // key it had just fled. So the pole is drawn here, the anchor below, from the weights as they
        // finally are.
        var pole = -1
        var gain: Double? = null
        if (arity > 1 && rng.nextDouble() < config.extrapolationProbability) {
            val candidate = roulette(rng, weights)
            val g = config.minExtrapolationGain +
                rng.nextDouble() * (config.maxExtrapolationGain - config.minExtrapolationGain)
            val away = 1.0 - weights[candidate]
            // A blend already dominated by one key has nowhere to be pushed *from*: the further the
            // anchor's weight is from the rest, the larger the push needed to leave the hull at all,
            // and past about 0.9 that push is a blast rather than an exploration. Such a render
            // stays convex, and the null gain in its manifest says so.
            if (away >= 0.1) {
                // Push along the ray from the pole through the convex point, measured in
                // *hull-crossings*: scale the other weights to sum to `g` and give the pole `1 - g`.
                // The weights still sum to 1, so this is the same affine blend with the hull lifted —
                // and the pole's weight passes through exactly zero at `g == 1`, which is the far
                // face. So `g` reads directly: 1.0 is the boundary, 1.5 is half as far again beyond
                // it, and anything above 1 is genuinely outside.
                val scale = g / away
                for (i in weights.indices) weights[i] *= scale
                weights[candidate] = 1.0 - g
                pole = candidate
                gain = g
            }
        }

        // The anchor: whose code, strings and numeric types this blend takes. Drawn over the final
        // weights by magnitude, so the key that actually dominates the picture usually owns them.
        val anchor = roulette(rng, weights)
        return Draw(seed, participants, weights, anchor, pole, gain)
    }

    // ---- the blend ----------------------------------------------------------------------------

    private class Blended(
        val key: KeyframeScope.Key,
        val parents: List<SweepParent>,
        val discreteLanes: Map<String, String>,
        val dropped: List<String>
    )

    private fun blend(draw: Draw, live: KeyframeScope.Key): Blended {
        val loadedKeys = draw.participants.map { it.load() }
        val anchorKey = loadedKeys[draw.anchor]

        // Which lanes are in play, and each participant's shortfall against that.
        val lanes: Set<String> = when (config.missingSlots) {
            MissingSlots.INTERSECT ->
                loadedKeys.map { it.values.keys.toSet() }.reduce { a, b -> a intersect b }
            else -> loadedKeys.flatMapTo(linkedSetOf()) { it.values.keys }
        }
        val filled = loadedKeys.map { key -> lanes.filter { it !in key.values }.sorted() }
        loadedKeys.forEachIndexed { i, key ->
            val source = if (config.missingSlots == MissingSlots.FILL_FROM_ANCHOR) anchorKey else live
            filled[i].forEach { lane -> key.values[lane] = source.values[lane] }
            // Lanes outside the set in play (an INTERSECT leftover) would otherwise survive the fold
            // in whichever participant has them and be blended against nothing.
            key.values.keys.retainAll(lanes)
        }

        // Fold in descending weight order: the running sums then start at the largest weight and
        // end at 1, so no divisor ever approaches zero even with negative weights in the mix.
        val order = draw.weights.indices.sortedByDescending { draw.weights[it] }
        var accumulated = loadedKeys[order.first()].copy()
        var runningSum = draw.weights[order.first()]
        for (position in 1 until order.size) {
            val i = order[position]
            runningSum += draw.weights[i]
            accumulated = scope.interpolate(accumulated, loadedKeys[i], draw.weights[i] / runningSum)
        }

        // The three things the fold cannot be trusted with.
        val discreteLanes = resolveDiscreteLanes(accumulated, draw, loadedKeys, anchorKey)
        if (config.preserveNumericKind) restoreNumericKinds(accumulated, anchorKey)
        val dropped = lanes.filter { it !in accumulated.values }.sorted()

        val parents = draw.participants.mapIndexed { i, p ->
            SweepParent(p.relative, draw.weights[i], p.schemaVersion, filled[i])
        }
        return Blended(accumulated, parents, discreteLanes, dropped)
    }

    /**
     * Give every discrete lane an owner, per [DiscreteBlend], and say which parent that was.
     *
     * The lane *set* comes from the anchor rather than from the fold's output, because a lane the
     * fold dropped is exactly the lane most in need of being restored from a single key.
     */
    private fun resolveDiscreteLanes(
        into: KeyframeScope.Key,
        draw: Draw,
        loadedKeys: List<KeyframeScope.Key>,
        anchorKey: KeyframeScope.Key
    ): Map<String, String> {
        if (config.discrete == DiscreteBlend.AS_INTERPOLATED) return emptyMap()
        val provenance = linkedMapOf<String, String>()
        // A second stream, seeded off the render's own seed, so per-lane draws do not perturb the
        // participant/weight draw that a re-run has to reproduce exactly.
        val laneRng = Random(draw.seed xor 0x5D_EE_CE_66L)
        anchorKey.values.forEach { (lane, value) ->
            if (!isDiscrete(value)) return@forEach
            val owner =
                if (config.discrete == DiscreteBlend.ROULETTE_PER_LANE) roulette(laneRng, draw.weights)
                else draw.anchor
            val source = loadedKeys[owner].values[lane] ?: value
            into.values[lane] = source
            provenance[lane] = draw.participants[owner].relative
        }
        return provenance
    }

    /**
     * Put each numeric lane back into the type the anchor held it in.
     *
     * `interpolate` widens: two `Int`s blend to a `Double`, because half way between 3 and 4 is not
     * an `Int`. That is right for the blend and wrong for the target, which is an `Int` uniform that
     * will refuse a `Double`. Rounding here rather than in `interpolate` keeps the widening visible
     * to anything that wants it.
     */
    private fun restoreNumericKinds(into: KeyframeScope.Key, anchorKey: KeyframeScope.Key) {
        into.values.entries.forEach { entry ->
            val blendedValue = entry.value
            val anchorValue = anchorKey.values[entry.key]
            if (blendedValue !is Number || anchorValue !is Number) return@forEach
            entry.setValue(
                when (anchorValue) {
                    is Byte -> blendedValue.toDouble().roundToInt().coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
                    is Short -> blendedValue.toDouble().roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    is Int -> blendedValue.toDouble().roundToInt()
                    is Long -> blendedValue.toDouble().roundToLong()
                    is Float -> blendedValue.toFloat()
                    else -> blendedValue.toDouble()
                }
            )
        }
    }

    private fun isDiscrete(value: Any?): Boolean =
        value is String || value is StackSLString || value is SourceBackedBlock<*>

    // ---- budget, identity and bookkeeping ------------------------------------------------------

    /** Why the loop should stop before starting another render, or null to carry on. */
    private fun budgetStop(startedAtNs: Long): String? {
        if (config.maxDurationMs > 0 && (System.nanoTime() - startedAtNs) / 1_000_000L >= config.maxDurationMs) {
            return "duration"
        }
        if (config.minFreeBytes > 0) {
            val usable = runCatching { Files.getFileStore(out).usableSpace }.getOrNull()
            if (usable != null && usable < config.minFreeBytes) {
                lprintln(
                    "warning,keyframe",
                    "keyframe sweep stopping: ${usable / (1L shl 20)}MB free on ${out}, " +
                        "floor is ${config.minFreeBytes / (1L shl 20)}MB"
                )
                return "disk"
            }
        }
        return null
    }

    /** This index's seed. A pure function of the run seed and the index — nothing accumulates. */
    private fun seedFor(index: Int): Long = config.seed * 6_364_136_223_846_793_005L + index * 1_442_695_040_888_963_407L + 1L

    private fun renderId(index: Int, seed: Long): String =
        "%06d-%06x".format(index, (seed ushr 16) and 0xFF_FF_FFL)

    private fun isFinished(dir: Path): Boolean =
        readManifest(dir)?.status?.let { it != "running" } ?: false

    private fun readManifest(dir: Path): SweepRenderManifest? {
        val file = dir.resolve("manifest.json")
        if (!file.exists()) return null
        return runCatching { compactJson.decodeFromString<SweepRenderManifest>(Files.readString(file)) }.getOrNull()
    }

    /** Terminal manifest write plus the index append, as one step so the two cannot disagree by much. */
    private fun finish(
        render: SweepRender,
        status: String,
        startedAtNs: Long,
        failure: Throwable?
    ): SweepRenderManifest {
        val manifest = render.manifest.copy(
            status = status,
            finishedAt = Instant.now().toString(),
            durationMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
            error = failure?.let { it.message?.lineSequence()?.firstOrNull() ?: it::class.simpleName }
        )
        render.manifest = manifest
        runCatching { writeAtomic(render.dir.resolve("manifest.json"), prettyJson.encodeToString(manifest)) }
        runCatching {
            Files.writeString(
                indexFile,
                compactJson.encodeToString(manifest) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
        return manifest
    }

    /**
     * Write via a sibling temporary and an atomic move, so a reader never sees half a manifest.
     *
     * A sweep's manifests are written while something else may be reading them — an analysis pass
     * over last night's archive, or the next morning's browser — and a torn JSON file reads as a
     * damaged render rather than as a busy one.
     */
    private fun writeAtomic(path: Path, text: String) {
        path.parent?.createDirectories()
        val tmp = path.resolveSibling("${path.name}.tmp")
        Files.writeString(tmp, text)
        runCatching { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .onFailure { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun deleteTree(dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sampling
// ---------------------------------------------------------------------------------------------

/**
 * `n` weights summing to 1, drawn from a symmetric Dirichlet with concentration [concentration].
 *
 * The distribution matters more than it sounds. Drawing weights by normalising `n` uniforms — the
 * obvious thing — concentrates hard on the centre of the simplex as `n` grows, so every blend of
 * five keys is nearly their average and a night of it is a night of mush. A Dirichlet is the
 * distribution that is actually uniform over the simplex at `α = 1`, and whose corner bias is a knob
 * rather than an accident.
 *
 * Built from Gamma(α, 1) draws via Marsaglia–Tsang, with the standard `α < 1` boost. Deterministic
 * in [rng], so a run's weights are reproducible from its seed.
 */
fun dirichlet(rng: Random, n: Int, concentration: Double = 1.0): DoubleArray {
    require(n >= 1) { "dirichlet needs at least one component, not $n" }
    require(concentration > 0.0) { "dirichlet concentration must be positive, not $concentration" }
    val out = DoubleArray(n) { gamma(rng, concentration) }
    val total = out.sum()
    // Every draw underflowing to zero is astronomically unlikely and catastrophic rather than
    // wrong-looking, so it falls back to equal weights instead of producing NaNs.
    if (total <= 0.0 || !total.isFinite()) return DoubleArray(n) { 1.0 / n }
    for (i in out.indices) out[i] /= total
    return out
}

/** One Gamma(shape, 1) draw — Marsaglia–Tsang for `shape >= 1`, boosted below it. */
private fun gamma(rng: Random, shape: Double): Double {
    if (shape < 1.0) {
        val u = rng.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        return gamma(rng, shape + 1.0) * u.pow(1.0 / shape)
    }
    val d = shape - 1.0 / 3.0
    val c = 1.0 / sqrt(9.0 * d)
    while (true) {
        var x: Double
        var v: Double
        do {
            x = gaussian(rng)
            v = 1.0 + c * x
        } while (v <= 0.0)
        v = v * v * v
        val u = rng.nextDouble()
        if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
        if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
    }
}

/** Box–Muller, one of the pair. [Random] has no `nextGaussian`. */
private fun gaussian(rng: Random): Double {
    val u1 = rng.nextDouble().coerceAtLeast(Double.MIN_VALUE)
    val u2 = rng.nextDouble()
    return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}

/**
 * An index of [weights], drawn proportionally to weight — the anchor draw.
 *
 * Absolute values, because extrapolation makes weights negative and a key pulling a blend hard
 * *away* from itself is still a key with a large say in the result.
 */
internal fun roulette(rng: Random, weights: DoubleArray): Int {
    val total = weights.sumOf { abs(it) }
    if (total <= 0.0 || !total.isFinite()) return 0
    var mark = rng.nextDouble() * total
    for (i in weights.indices) {
        mark -= abs(weights[i])
        if (mark <= 0.0) return i
    }
    return weights.size - 1
}

/**
 * Every render in an archive, newest last — the offline pass's way in.
 *
 * Reads `index.jsonl` when it is there and falls back to walking the `manifest.json` files, so it
 * works on an archive whose index a crash left short. Damaged lines are skipped rather than fatal:
 * one unreadable render out of four thousand should not stop the categorisation.
 */
fun readSweepArchive(out: Path): List<SweepRenderManifest> {
    val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    val index = out.resolve("index.jsonl")
    if (index.exists()) {
        val rows = Files.readAllLines(index)
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<SweepRenderManifest>(it) }.getOrNull() }
        if (rows.isNotEmpty()) return rows.sortedBy { it.index }
    }
    val renders = out.resolve("renders")
    if (!renders.isDirectory()) return emptyList()
    return renders.listDirectoryEntries().filter { it.isDirectory() }
        .mapNotNull { dir ->
            val file = dir.resolve("manifest.json")
            if (!file.exists()) null
            else runCatching { json.decodeFromString<SweepRenderManifest>(Files.readString(file)) }.getOrNull()
        }
        .sortedBy { it.index }
}

/**
 * A readout of what a night actually did: how many renders, how they ended, how wide the blends
 * ranged, and which pool keys got used.
 *
 * Written for the morning, when the question is "is this archive worth analysing" and the answer
 * lives in the failure count and the key histogram rather than in any one render.
 */
fun sweepArchiveSummary(out: Path): String {
    val rows = readSweepArchive(out)
    if (rows.isEmpty()) return "{muted:No renders in $out.}"
    val byStatus = rows.groupingBy { it.status }.eachCount().entries.sortedByDescending { it.value }
    val extrapolated = rows.count { it.extrapolationGain != null }
    val durations = rows.mapNotNull { it.durationMs }.sorted()
    val median = durations.getOrNull(durations.size / 2) ?: 0L
    val usage = rows.flatMap { m -> m.parents.map { it.key } }.groupingBy { it }.eachCount()
    val widest = rows.flatMap { m -> m.parents.map { abs(it.weight) } }.maxOrNull() ?: 0.0
    return buildString {
        append("${rows.size} renders — ")
        append(byStatus.joinToString(", ") { "${it.value} ${it.key}" })
        append("\n$extrapolated extrapolated, widest single weight ${"%.2f".format(widest)}")
        append("\nmedian render ${median}ms, ${usage.size} of the pool's keys used")
        val unusedNote = usage.entries.sortedBy { it.value }.firstOrNull()
        if (unusedNote != null) append("\nleast used key: ${unusedNote.key} (${unusedNote.value})")
        val dropped = rows.flatMap { it.droppedLanes }.distinct()
        if (dropped.isNotEmpty()) append("\ndropped lanes: ${dropped.sorted().joinToString(", ")}")
        val fills = rows.flatMap { m -> m.parents.flatMap { it.filled } }.distinct()
        if (fills.isNotEmpty()) append("\nback-filled lanes: ${fills.sorted().take(8).joinToString(", ")}")
    }
}

/** The one number an overnight run is judged on: completed renders per hour. */
fun SweepReport.rendersPerHour(): Double =
    if (elapsedMs <= 0) 0.0 else completed * 3_600_000.0 / elapsedMs
