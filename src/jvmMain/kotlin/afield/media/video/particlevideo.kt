@file:afield.runtime.completion.CompletionCategory("Video")

package afield.media.video

import afield.data.collections.invoke
import afield.data.collections.s
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.files
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.simulation.agents.step
import afield.simulation.automata.step
import afield.simulation.automata.trajectory

import afield.animation.keyframes.h
import afield.audio.synth.*
import afield.data.collections.get
import afield.document.workspace.file
import afield.document.workspace.path
import afield.graphics.render.color
import afield.graphics.render.lineTo
import afield.graphics.render.moveTo
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.graphics.vulkan.buffers.add
import afield.lang.stacksl.h
import afield.math.autodiff.maxOf
import afield.math.autodiff.minOf
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.i
import afield.math.linalg.vec
import afield.math.linalg.x
import afield.media.image.channel
import afield.media.image.open
import afield.runtime.execution.start
import afield.ui.output.h
import afield.geometry.path.FLine
import afield.math.linalg.times
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.floor

/**
 * Reads particle-tracking results (PVTRACK1 files) as scrubbable frames and per-identity tracks,
 * with no Python or Torch at load time.
 *
 * [loadParticleVideo] memory-maps a finalized file (a gzip-compressed copy is inflated into memory
 * instead) and validates every lifecycle before returning a [ParticleVideo]. [ParticleVideo.frame] decodes one recorded frame;
 * [ParticleVideo.at] scrubs by time and interpolates only between samples of the same identity.
 * Each [ParticleSample] carries position, velocity, sampled RGB and [ParticleFlags]; each
 * [ParticleTrack] records one trajectory and why it was retired.
 *
 * The files come from `particletracker.kt` and are drawn by `particlevideoview.kt`. Ordering,
 * interpolation, rejection of broken files and the compressed round trip are covered by
 * `ParticleVideoTest`.
 */

/** Loads a finalized PVTRACK1 file (or a gzip-compressed copy), without Python or Torch. */
fun loadParticleVideo(path: String): ParticleVideo = loadParticleVideo(File(path))

/** Loads and validates complete particle lifecycles, indexing samples by frame and identity. */
fun loadParticleVideo(file: File): ParticleVideo = ParticleVideo.read(file)

/**
 * Sample and retirement flags. Several reasons can apply to the same sample or retirement, and the
 * four conditions a sample can carry retire the particle once they have held for the run's grace
 * frames, which is why they appear in [ParticleTrack.endFlags] as well.
 */
object ParticleFlags {
    /** The first sample of a trajectory. Its velocity is zero, there being no earlier position. */
    const val BORN = 1
    /** The flow estimate for this frame scored too low to use, so the particle held its position
     * with zero velocity rather than jumping to a bad match. */
    const val UNCERTAIN = 2
    /** An older particle is nearer than the birth spacing. Of a crowded pair only the younger one
     * carries this, so the flags count encounters rather than particles. */
    const val CROWDED = 4
    /** The colour under the particle has drifted further from its birth colour than the run allows. */
    const val COLOR = 8
    /** The particle is moving slower than the still-speed threshold, which is off unless set. */
    const val STILL = 16
    /** A retirement: the particle reached the edge of the image. */
    const val LOST = 32
    /** A retirement into a nearby identity that has lived at least as long, which `mergedInto`
     * names; the younger of the two is always the one that goes. */
    const val MERGED = 64
    /** A retirement at the end of the clip, which says nothing about how the tracking went. */
    const val EOF = 128
}

/** One identity at one processed video frame. Position and velocity use pixels, with y down. */
data class ParticleSample(
    /** The identity this sample belongs to, stable for as long as the particle lives. */
    val id: Long,
    /** The processed frame it was taken on, counted from zero. */
    val frame: Int,
    /** The [ParticleFlags] bits set for this sample. */
    val flags: Int,
    /** Position across the processed image, in pixels. */
    val x: Float,
    /** Position down the processed image, in pixels, measured from the top. */
    val y: Float,
    /** Horizontal displacement since the previous frame, in pixels. */
    val vx: Float,
    /** Vertical displacement since the previous frame, in pixels. */
    val vy: Float,
    /** Red under the particle on this frame, 0 to 1, as the video stores it rather than linearised. */
    val red: Float,
    /** Green under the particle on this frame, 0 to 1. */
    val green: Float,
    /** Blue under the particle on this frame, 0 to 1. */
    val blue: Float,
    /** How much the estimator trusts this match, 0 to 1: a heuristic built from
     * [ParticleSample.forwardBackwardError], [ParticleSample.residual] and [ParticleSample.texture] rather than a calibrated probability. Below
     * the run's threshold the sample is marked uncertain. */
    val confidence: Float,
    /** How far tracking forward and then back again misses the starting point, in pixels. */
    val forwardBackwardError: Float,
    /** Brightness difference between the two matched windows, as a root mean square over 0 to 1. */
    val residual: Float,
    /** How much structure the window had to match on, as the weaker gradient direction's strength
     * per pixel; flat regions score near zero, and the neural tracker records -1 for all of them. */
    val texture: Float,
    /** Distance from the particle's birth colour, by the same measure the colour limit uses. */
    val colorDelta: Float,
) {
    /** Fresh Field vector; mutating it does not modify the mapped track data. */
    val position: Vec3 get() = vec(x, y, 0)
    /** Displacement per processed frame, not pixels per second. Birth and uncertain samples have zero velocity. */
    val velocity: Vec3 get() = vec(vx, vy, 0)
    /** Whether this sample held its position instead of following a flow estimate. */
    val uncertain: Boolean get() = flags and ParticleFlags.UNCERTAIN != 0
}

/** A committed trajectory, including its retirement reason and optional surviving merge identity. */
data class ParticleTrack(
    /** The identity, which no later particle reuses. */
    val id: Long,
    /** The frame the particle was born on. */
    val firstFrame: Int,
    /** The frame of its last recorded sample. */
    val lastFrame: Int,
    /** How many samples the file holds for it, never fewer than the run's minimum track length. */
    val samples: Int,
    /** The frame its retirement was recorded on, which is [ParticleTrack.lastFrame] or a later frame. */
    val endFrame: Int,
    /** Why it was retired, as [ParticleFlags] bits. */
    val endFlags: Int,
    /** The identity it was merged into, or null if it ended some other way. */
    val mergedInto: Long?,
)

/** Ways to color the same geometry: source RGB, deterministic identity, or heuristic confidence. */
enum class ParticleColor { SAMPLED, IDENTITY, CONFIDENCE }

/** A frame of sampled particles. Retired and newly born identities can both appear at a transition. */
data class ParticleFrame(
    /** The recorded frame this came from, and the earlier of the two when samples are interpolated. */
    val index: Int,
    /** Movie time in seconds: the scrubbed time for an interpolated frame, [ParticleFrame.index] over the frame
     * rate for a recorded one. */
    val seconds: Double,
    /** The particles present, in the order the file records them. */
    val samples: List<ParticleSample>
) {
    /** Point geometry for node-color-aware drawing, including Vulkan. Use [ParticleVideo.image] for 2D output. */
    fun points(scale: Double = 1.0, pointSize: Double = 2.0, color: ParticleColor = ParticleColor.SAMPLED): FLine {
        require(scale.isFinite() && scale > 0 && pointSize.isFinite() && pointSize > 0)
        return FLine().also { line ->
            line.stroked = false
            line.pointed = true
            line.pointSize = pointSize
            samples.forEach { sample ->
                line.moveTo(sample.position * scale)
                line.here.color = particleColor(sample, color)
            }
        }
    }
}

internal fun particleColor(sample: ParticleSample, mode: ParticleColor): Vec4 = when (mode) {
    ParticleColor.SAMPLED -> vec(sample.red, sample.green, sample.blue, 1)
    ParticleColor.IDENTITY -> vec(
        ((sample.id * 53) % 160 + 95) / 255.0,
        ((sample.id * 97) % 160 + 95) / 255.0,
        ((sample.id * 139) % 160 + 95) / 255.0, 1.0,
    )
    ParticleColor.CONFIDENCE -> vec(1.0 - sample.confidence, sample.confidence.toDouble(), .2, 1.0)
}

/**
 * Read-only, memory-mapped video tracks with frame and identity indexes. No neural dependencies.
 *
 * The tracker buffers probation samples, so records need not be globally sorted by frame. This
 * reader indexes that ordering rather than assuming a frame-sorted file. Samples are decoded on
 * demand; the mapped binary and integer indexes remain resident, not millions of sample objects.
 * Files up to 2 GiB and one million frames are supported by this version. Save larger work in clips.
 *
 * Coordinates are processed pixels with y down. Colors are sampled RGB in 0..1. Confidence is a
 * consistency heuristic, not a calibrated probability; neural samples use texture = -1.
 */
class ParticleVideo private constructor(
    /** The mapped file, as an absolute path. */
    val file: File,
    /** Width of the processed image, which is the frame these coordinates are in. */
    val width: Int,
    /** Height of the processed image. */
    val height: Int,
    /** Processed frames per second, which is what turns a frame index into movie time. */
    val fps: Double,
    /** How many frames were processed, whatever the source clip's own length. */
    val frameCount: Int,
    /** The seed the run was given, recorded so the same particles can be laid down again. */
    val seed: Long,
    /** Samples and retirements in the file together, which is not a count of particles. */
    val recordCount: Long,
    private val data: ByteBuffer,
    private val byFrame: Array<IntArray>,
    private val byTrack: Map<Long, IntArray>,
    /** Every committed trajectory, in the order the particles were retired. */
    val tracks: List<ParticleTrack>,
) {
    /** Playback duration including the last frame's display interval. */
    val durationSeconds: Double get() = frameCount / fps
    /** Time of the final recorded sample, for inclusive scrub controls. */
    val lastSampleSeconds: Double get() = (frameCount - 1) / fps

    /** Decodes just one frame. Empty frames remain valid. */
    fun frame(index: Int): ParticleFrame {
        require(index in 0 until frameCount) { "Frame $index is outside 0 until $frameCount" }
        return ParticleFrame(index, index / fps, byFrame[index].map(::sample))
    }

    /**
     * Scrubs recorded movie time, clamped to its sample range. Interpolation joins only matching
     * identities in adjacent frames; births and retirements never crossfade into another identity.
     * RGB, position and velocity interpolate; status/confidence diagnostics belong to the earlier frame.
     */
    fun at(seconds: Double, interpolate: Boolean = true): ParticleFrame {
        require(seconds.isFinite()) { "Movie time must be finite" }
        val t = (seconds * fps).coerceIn(0.0, (frameCount - 1).toDouble())
        val index = floor(t).toInt()
        val first = frame(index)
        val alpha = (t - index).toFloat()
        if (!interpolate || alpha == 0f || index == frameCount - 1) return first
        val next = frame(index + 1).samples.associateBy { it.id }
        fun mix(a: Float, b: Float) = a + (b - a) * alpha
        return first.copy(seconds = t / fps, samples = first.samples.map { a ->
            val b = next[a.id] ?: return@map a
            a.copy(x = mix(a.x, b.x), y = mix(a.y, b.y), vx = mix(a.vx, b.vx), vy = mix(a.vy, b.vy),
                red = mix(a.red, b.red), green = mix(a.green, b.green), blue = mix(a.blue, b.blue))
        })
    }

    /** A single recorded trajectory in time order; an unknown identity returns an empty list. */
    fun trajectory(id: Long, fromFrame: Int = 0, throughFrame: Int = frameCount - 1): List<ParticleSample> {
        val offsets = byTrack[id] ?: return emptyList()
        val first = data.getLong(offsets.first() + 16).toInt()
        val from = maxOf(0, fromFrame - first)
        val to = minOf(offsets.lastIndex, throughFrame - first)
        return if (from > to) emptyList() else (from..to).map { sample(offsets[it]) }
    }

    /**
     * Trails ending at [frame], one path per currently visible identity, in processed coordinates.
     * [stride] selects stable identities by ID modulo, useful for drawing dense runs without flicker.
     */
    fun trails(frame: Int, length: Int = 12, stride: Int = 1, scale: Double = 1.0,
               color: ParticleColor = ParticleColor.IDENTITY): List<FLine> {
        require(length > 0 && stride > 0 && scale.isFinite() && scale > 0)
        return this.frame(frame).samples.filter { it.id % stride == 0L }.map { current ->
            val offsets = byTrack.getValue(current.id)
            val start = maxOf(0, frame - length + 1)
            // Consecutive per-track frames make the sample range directly addressable.
            val first = data.getLong(offsets.first() + 16).toInt()
            val from = maxOf(0, start - first)
            val to = minOf(offsets.lastIndex, frame - first)
            FLine().also { line ->
                for (i in from..to) {
                    val p = sample(offsets[i]).position * scale
                    if (i == from) line.moveTo(p) else line.lineTo(p)
                }
                line.color = particleColor(current, color)
                line.thickness = .6
            }
        }
    }

    private fun sample(offset: Int): ParticleSample = ParticleSample(
        id = data.getLong(offset + 8), frame = data.getLong(offset + 16).toInt(), flags = data.getInt(offset + 4),
        x = data.getFloat(offset + 32), y = data.getFloat(offset + 36),
        vx = data.getFloat(offset + 40), vy = data.getFloat(offset + 44),
        red = data.getFloat(offset + 48), green = data.getFloat(offset + 52), blue = data.getFloat(offset + 56),
        confidence = data.getFloat(offset + 60), forwardBackwardError = data.getFloat(offset + 64),
        residual = data.getFloat(offset + 68), texture = data.getFloat(offset + 72), colorDelta = data.getFloat(offset + 76),
    )

    companion object {
        internal fun read(file: File): ParticleVideo {
            if (file.extension == "gz") {
                val out = ByteArrayOutputStream()
                GZIPInputStream(file.inputStream().buffered()).use { stream ->
                    val block = ByteArray(65536)
                    while (true) {
                        val n = stream.read(block)
                        if (n < 0) break
                        require(out.size().toLong() + n <= 256L * 1024 * 1024) { "Compressed track files must inflate to at most 256 MiB; use an uncompressed file for larger runs" }
                        out.write(block, 0, n)
                    }
                }
                val bytes = out.toByteArray()
                return parse(file, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), bytes.size.toLong())
            }
            return FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            require(size in 64..Int.MAX_VALUE.toLong()) { "Expected a PVTRACK1 file between 64 bytes and 2 GiB" }
            val b = channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN)
                parse(file, b, size)
            }
        }

        private fun parse(file: File, b: ByteBuffer, size: Long): ParticleVideo {
            require(size >= 64) { "Truncated particle header" }
            val magic = ByteArray(8).also { b.get(it) }
            require(magic.contentEquals("PVTRACK1".toByteArray()) && b.getInt(8) == 1 && b.getInt(12) == 64 && b.getInt(16) == 80) {
                "Unsupported particle video format"
            }
            val w = b.getInt(20); val h = b.getInt(24); val fps = b.getDouble(32)
            val count = b.getLong(40); val records = b.getLong(48)
            require(b.getLong(56) == 1L) { "Particle video was not finalized" }
            require(w in 32..16384 && h in 32..16384 && fps.isFinite() && fps > 0 && fps <= 1000 && count in 2..1_000_000) {
                "Invalid particle video dimensions, FPS or frame count"
            }
            require(records >= 0 && records <= (size - 64) / 80 && size == 64 + records * 80) { "Truncated or trailing particle records" }
            val frames = Array(count.toInt()) { IntOffsets() }
            val builders = LinkedHashMap<Long, TrackBuilder>()
            val active = HashMap<Long, TrackBuilder>()
            val finished = ArrayList<ParticleTrack>()
            repeat(records.toInt()) { i ->
                val offset = 64 + i * 80
                val kind = b.getInt(offset); val flags = b.getInt(offset + 4)
                val id = b.getLong(offset + 8); val frame = b.getLong(offset + 16); val related = b.getLong(offset + 24)
                require(kind in 1..2 && flags and 255 == flags && id > 0 && frame in 0 until count) { "Invalid particle record identity, flags or time" }
                for (field in 0 until 12) require(b.getFloat(offset + 32 + field * 4).isFinite()) { "Nonfinite particle sample" }
                require((48..60 step 4).all { b.getFloat(offset + it) in 0f..1f }) { "Invalid particle color or confidence" }
                if (kind == 1) {
                    require(related == 0L) { "A sample cannot have merge ancestry" }
                    val track = active[id] ?: run {
                        require(flags and ParticleFlags.BORN != 0 && id !in builders) { "A trajectory must start with BORN and a new identity" }
                        TrackBuilder(id, frame.toInt()).also { builders[id] = it; active[id] = it }
                    }
                    require(frame.toInt() == track.last + 1) { "Nonconsecutive samples for particle $id" }
                    require(track.offsets.size == 0 || flags and ParticleFlags.BORN == 0) { "Repeated BORN flag" }
                    track.last = frame.toInt(); track.offsets.add(offset); frames[frame.toInt()].add(offset)
                } else {
                    val track = active.remove(id) ?: throw IllegalArgumentException("Retirement without an active identity")
                    require(frame >= track.last && flags and ParticleFlags.BORN == 0) { "Retirement precedes its trajectory or carries BORN" }
                    require(if (flags and ParticleFlags.MERGED != 0) related != id && related in active else related == 0L) { "Invalid merge ancestry" }
                    finished += ParticleTrack(id, track.first, track.last, track.offsets.size, frame.toInt(), flags, related.takeIf { it != 0L })
                }
            }
            require(active.isEmpty()) { "Unterminated particle trajectories" }
            return ParticleVideo(file.absoluteFile, w, h, fps, count.toInt(), Integer.toUnsignedLong(b.getInt(28)), records,
                b, frames.map { it.toArray() }.toTypedArray(), builders.mapValues { it.value.offsets.toArray() }, finished.toList())
        }
    }
}

private class TrackBuilder(val id: Long, val first: Int) {
    var last = first - 1
    val offsets = IntOffsets()
}

private class IntOffsets {
    private var values = IntArray(16)
    var size: Int = 0
        private set
    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }
    fun toArray(): IntArray = values.copyOf(size)
}
