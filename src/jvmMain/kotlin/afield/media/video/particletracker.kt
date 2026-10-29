@file:afield.runtime.completion.CompletionCategory("Video")

package afield.media.video

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.geometry.mesh.shell
import afield.graphics.render.color
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.lifecycle.cached

import afield.audio.synth.*
import afield.runtime.execution.start
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the optional offline particle tracker (RAFT optical flow, via PyTorch) over a video and
 * caches the result as a PVTRACK1 file.
 *
 * [particleTracker] makes a [ParticleTracker] for the tools in `optional/particle-tracker`; its
 * `track` call is a suspend function meant for a box's `start { ... }` coroutine, and it returns a
 * [ParticleTrackingRun] whose `load` hands the file to `particlevideo.kt`.
 * [ParticleTrackingOptions] holds every algorithm setting, and each one is part of the cache key.
 * Invalid options fail before any process starts (`ParticleVideoTest`).
 */

/**
 * Options for bidirectional RAFT-large video particles. Width 0 retains source resolution; frame
 * limit 0 processes the whole clip. All distances and velocities use processed pixels and frames.
 *
 * [colorLimit] measures RGB RMS distance from a particle's fixed birth color. Use 1.0 to disable
 * this retirement condition for deliberately recolored or relit footage. Sampled output colors
 * still follow the current frame. [stillSpeed] and [mergeDistance] are disabled at zero.
 */
data class ParticleTrackingOptions(
    /** Resize the video to this width before tracking, keeping its aspect ratio. Zero keeps the
     * source resolution, and every distance below is in the pixels that choice produces. */
    val width: Int = 0,
    /** Resample the video to this frame rate; null tracks it at its own average rate. */
    val fps: Double? = null,
    /** Stop after this many frames. Zero runs to the end of the clip. */
    val frames: Int = 0,
    /** How many particles to keep alive. A frame that cannot be filled is left underfilled. */
    val particles: Int = 3000,
    /** Discard a trajectory that never reached this many samples. */
    val minTrack: Int = 10,
    /** How many frames in a row a condition has to hold before it retires the particle. */
    val grace: Int = 5,
    /** The closest two particles may be born, in pixels. Zero spaces them for the target count and
     * the image area, which is usually what you want. */
    val spacing: Double = 0.0,
    /** The score a match needs to move a particle. Below it the particle holds its position. */
    val confidence: Double = .25,
    /** The score a new particle needs where it is proposed, stricter than [ParticleTrackingOptions.confidence] by default. */
    val seedConfidence: Double = .45,
    /** How far the colour under a particle may drift from its birth colour before it is retired,
     * measured as an RGB root mean square. Use 1.0 to switch this off for recoloured or relit
     * footage; the colours recorded with each sample still come from the current frame. */
    val colorLimit: Double = .18,
    /** Retire a particle moving slower than this, in pixels per frame. Zero keeps them all. */
    val stillSpeed: Double = 0.0,
    /** Coalesce two particles that stay this close, in pixels. Zero never merges. */
    val mergeDistance: Double = 0.0,
    /** How many frames old both particles must be before they are allowed to merge. */
    val mergeAge: Int = 30,
    /** How much two particles' velocities may disagree and still count as one, in pixels a frame. */
    val mergeVelocity: Double = 1.5,
    /** How hard to work at filling a gap: this many batches of eight candidate positions for each
     * missing particle, every batch keeping the best-spaced position it found. */
    val seedBudget: Int = 16,
    /** Seeds the placement of new particles, so the same video and options give the same run. */
    val seed: Long = 1,
    /** Refinement steps the flow network runs on each frame pair; more of them cost more time. */
    val iterations: Int = 20,
    /** Which pretrained RAFT-large checkpoint to load, by its torchvision name. */
    val weights: String = "C_T_SKHT_V2",
    /** Where the flow network runs: `cuda`, `cuda:1`, `cpu` or `mps`. The particle bookkeeping is
     * on the CPU either way, and a different backend is a different cache entry. */
    val device: String = "cuda",
    /** Threads for the per-particle work, which runs in its own process alongside the network. */
    val threads: Int = 4,
) {
    internal fun json(): JSONObject {
        require(width == 0 || width in 32..16384)
        require(frames in 0..1_000_000 && particles in 1..1_000_000 && minTrack in 1..10000 && grace in 1..10000)
        require(iterations in 1..100 && threads in 1..256 && seed in 0..4294967295L)
        require(mergeAge in 1..1_000_000 && seedBudget in 1..10000)
        require(listOf(spacing, stillSpeed, mergeDistance, mergeVelocity).all { it.isFinite() && it >= 0 })
        require(listOf(confidence, seedConfidence, colorLimit).all { it.isFinite() && it in 0.0..1.0 })
        require(fps == null || fps.isFinite() && fps > 0 && fps <= 1000)
        require(device in listOf("cuda", "cpu", "mps") || Regex("cuda:[0-9]+").matches(device))
        require(weights.matches(Regex("[A-Z0-9_]+")))
        return JSONObject(linkedMapOf(
            "width" to width, "fps" to (fps ?: JSONObject.NULL), "frames" to frames, "particles" to particles,
            "min_track" to minTrack, "grace" to grace, "spacing" to spacing, "confidence" to confidence,
            "seed_confidence" to seedConfidence, "color_limit" to colorLimit, "still_speed" to stillSpeed,
            "merge_distance" to mergeDistance, "merge_age" to mergeAge, "merge_velocity" to mergeVelocity,
            "seed_budget" to seedBudget, "seed" to seed, "iterations" to iterations, "weights" to weights,
            "device" to device, "threads" to threads,
        ))
    }
}

/** A finalized cached tracking result. The binary can be copied and loaded without the launcher. */
data class ParticleTrackingRun(
    /** The content key this run is filed under: the video, every option, the tools and the
     * environment they ran in. Two calls that agree on all of it are one run. */
    val key: String,
    /** Whether a finished run was already on disk, so no inference happened on this call. */
    val cacheHit: Boolean,
    /** The PVTRACK1 file, which [ParticleTrackingRun.load] reads and which can be copied anywhere. */
    val tracksFile: Path,
    /** The receipt beside it: what was asked for, what came out, and checksums of both. */
    val manifest: Path,
    /** Everything the tracker printed while producing it. */
    val log: Path
) {
    /** Memory-map and validate the recorded tracks; this does not run inference. */
    fun load(): ParticleVideo = loadParticleVideo(tracksFile.toFile())
}

/**
 * Creates an optional PyTorch/C++ launcher. Nothing is installed, compiled or downloaded until
 * [ParticleTracker.track] is called. Prepare the environment in `optional/particle-tracker` first,
 * or point [python] at an existing matched Torch/Torchvision environment.
 */
fun particleTracker(
    tools: Path = Path.of(System.getProperty("afield.particleTracker.tools")
        ?: System.getenv("FIELD_PARTICLE_TRACKER_TOOLS") ?: "optional/particle-tracker"),
    python: String = System.getProperty("afield.particleTracker.python")
        ?: System.getenv("FIELD_PARTICLE_TRACKER_PYTHON")
        ?: tools.resolve(".venv/bin/python").toAbsolutePath().toString(),
    cache: Path = Path.of(System.getProperty("afield.particleTracker.cache")
        ?: System.getenv("FIELD_PARTICLE_TRACKER_CACHE") ?: Path.of(System.getProperty("user.home"), ".afield/particle-tracker").toString()),
): ParticleTracker = ParticleTracker(tools, python, cache)

/**
 * Cached offline video processing, intended to be called inside a box's `start { ... }` coroutine.
 * The suspend call owns the Python process: cancellation terminates the wrapper and its inference
 * descendants. Successful runs publish a checksum-validated receipt atomically; failures never
 * become cache hits. Locks share concurrent identical requests across boxes and worktrees.
 *
 * Cache keys include video content, every algorithm option, tool source, compiler, model weights,
 * Torch/Torchvision versions and FFmpeg. An explicit CPU or MPS backend uses a different key.
 */
class ParticleTracker internal constructor(
    /** The `optional/particle-tracker` directory holding the wrapper, the engine and its tests. */
    val tools: Path,
    /** The interpreter to run it with, which needs a matched Torch and Torchvision. */
    val python: String,
    /** Where finished runs are kept, by default under `~/.afield/particle-tracker`. */
    val cache: Path
) {
    /**
     * Tracks a local video, or returns the identical cached run. [offline] refuses a missing
     * pretrained checkpoint without downloading it. [force] recomputes this key while preserving
     * an existing successful result if inference fails. Logs and provenance are retained in the cache.
     */
    suspend fun track(input: String, options: ParticleTrackingOptions = ParticleTrackingOptions(),
                      offline: Boolean = false, force: Boolean = false): ParticleTrackingRun =
        track(Path.of(input), options, offline, force)

    /** Path overload of [track]. No shell expansion or string-command interpolation is used. */
    suspend fun track(input: Path, options: ParticleTrackingOptions = ParticleTrackingOptions(),
                      offline: Boolean = false, force: Boolean = false): ParticleTrackingRun = withContext(Dispatchers.IO) {
        val arguments = options.json().toString()
        val script = tools.toAbsolutePath().resolve("cached_track.py")
        require(Files.isRegularFile(script)) { "Particle tracker tools not found at $script; set FIELD_PARTICLE_TRACKER_TOOLS" }
        require(Files.isRegularFile(input)) { "Video not found: $input" }
        Files.createDirectories(cache)
        val launch = Files.createTempDirectory(cache, ".launch-")
        val stdout = launch.resolve("stdout.json"); val stderr = launch.resolve("stderr.log")
        var process: Process? = null
        try {
            val command = mutableListOf(python, "-u", script.toString(), "--input", input.toAbsolutePath().toString(),
                "--cache", cache.toAbsolutePath().toString(), "--options", arguments)
            if (offline) command += "--offline"
            if (force) command += "--force"
            process = ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
            while (process.isAlive) delay(50)
            check(process.exitValue() == 0) { "Particle tracker failed (${process.exitValue()}): ${Files.readString(stderr).takeLast(6000)}" }
            val result = JSONObject(Files.readString(stdout))
            ParticleTrackingRun(result.getString("key"), result.getBoolean("cache_hit"), Path.of(result.getString("tracks")),
                Path.of(result.getString("manifest")), Path.of(result.getString("log")))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.takeIf { it.isAlive }?.let { p ->
                    // The wrapper's TERM handler cleans up its independently grouped inference child.
                    val descendants = p.descendants().toList()
                    p.destroy()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly().waitFor()
                    descendants.filter { it.isAlive }.forEach { it.destroy() }
                    descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
                }
                Files.deleteIfExists(stdout); Files.deleteIfExists(stderr); Files.deleteIfExists(launch)
            }
        }
    }
}
