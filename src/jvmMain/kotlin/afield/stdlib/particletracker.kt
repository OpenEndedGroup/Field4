@file:afield.CompletionCategory("Video")

package afield.stdlib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Options for bidirectional RAFT-large video particles. Width 0 retains source resolution; frame
 * limit 0 processes the whole clip. All distances and velocities use processed pixels and frames.
 *
 * [colorLimit] measures RGB RMS distance from a particle's fixed birth color. Use 1.0 to disable
 * this retirement condition for deliberately recolored or relit footage. Sampled output colors
 * still follow the current frame. [stillSpeed] and [mergeDistance] are disabled at zero.
 */
data class ParticleTrackingOptions(
    val width: Int = 0, val fps: Double? = null, val frames: Int = 0,
    val particles: Int = 3000, val minTrack: Int = 10, val grace: Int = 5,
    val spacing: Double = 0.0, val confidence: Double = .25, val seedConfidence: Double = .45,
    val colorLimit: Double = .18, val stillSpeed: Double = 0.0,
    val mergeDistance: Double = 0.0, val mergeAge: Int = 30, val mergeVelocity: Double = 1.5,
    val seedBudget: Int = 16, val seed: Long = 1,
    val iterations: Int = 20, val weights: String = "C_T_SKHT_V2", val device: String = "cuda", val threads: Int = 4,
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
data class ParticleTrackingRun(val key: String, val cacheHit: Boolean, val tracksFile: Path, val manifest: Path, val log: Path) {
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
class ParticleTracker internal constructor(val tools: Path, val python: String, val cache: Path) {
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
