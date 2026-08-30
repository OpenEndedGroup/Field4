package drowning

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * `dust` — a GPU particle system whose forces are written in StackSL rather than in GLSL.
 *
 * This file is the layout: the bytes the CPU writes and the shaders read, and nothing else. The
 * network is in [DustOrthant], the GLSL in [DustShaders], the push-constant knobs in [DustLook], the
 * default force programs in [DustPrograms], and the box recipe in `src/personal/resources/dust/`.
 *
 * ## Three buffers
 *
 * | buffer | stride | written by | read by |
 * |---|---|---|---|
 * | state (x2) | [PARTICLE_BYTES] | once, at startup | the force shader, and every draw |
 * | links | [LINKS_BYTES] | once, at startup | the force shader, and the edge draw |
 * | history | [HISTORY_HEADER_BYTES] + `capacity * count * 16` | the history shader | the trail draw |
 *
 * **State is doubled and the force shader ping-pongs it**, which is not an optimisation — it is the
 * only correct way to do this. A particle's force depends on its *neighbours'* positions, and a
 * neighbour is another invocation in the same dispatch; reading a buffer the same dispatch is writing
 * is a race whose symptom is a network that quietly stiffens in one corner of the grid. So the
 * dispatch reads `prevState` and writes `state`, and the host swaps them each frame. Links never
 * change, so they are single and `writeOnce()` device-local.
 *
 * ## The particle record
 *
 * Split into a mutable half and an immutable half rather than one 128-byte record, because the mutable
 * half is what gets doubled: 48 bytes ping-ponged and 80 bytes shared beats 128 bytes ping-ponged.
 *
 * ```
 * DustParticle, 48 bytes           DustLinks, 80 bytes
 *   vec4  pos    xyz, w = 1/mass     vec4  rest   xyz rest position, w = stiffness scale
 *   vec4  prev   xyz, w = hash       uvec4 nbrA   neighbours 0..3
 *   vec4  aux    four free channels  uvec4 nbrB   neighbours 4,5; z = degree; w = flags
 *                                    vec4  lenA   rest lengths 0..3
 *                                    vec4  lenB   rest lengths 4,5; z = mean; w = spare
 * ```
 *
 * `prev` is the Verlet second position, so velocity is `pos - prev` and there is no velocity field to
 * keep consistent with it. `aux` is deliberately undefined: the force programs can read and write it
 * (`?gA`, `->?gA`) and nothing here touches it after the initial write.
 *
 * ## The history buffer
 *
 * One circular buffer per particle, interleaved so that a *frame* is contiguous:
 *
 * ```
 * sample[slot * count + particle]
 * ```
 *
 * The write shader is one invocation per particle all writing the same `slot`, so slot-major makes
 * that write fully coalesced. The trail draw pays for it — walking one particle's history strides by
 * `count` — but a trail vertex reads exactly one `vec4` either way, and the draw is the pass that can
 * afford to be thinned (see [DustLook.trailEveryNth]) while the write cannot.
 *
 * The header is [HISTORY_HEADER_BYTES] of `uvec4` at the front of the same buffer, so a reader needs
 * one handle rather than two, and `capacity`/`head`/`count` travel with the samples they describe.
 * The header is written by the history shader and *also* arrives at every other shader as push
 * constants — the push constants are the per-frame truth (they cannot race), the header is the record
 * (it survives into a CPU readback). Both come from the same host value; [DustHistoryHeader] reads it
 * back and [DustHistoryHeader.check] is where a disagreement would show up.
 */
object DustLayout {

    /** Neighbour slots in a [DustLinks] record. Six, so the whole link record is 80 bytes. */
    const val EDGES = 6

    /** The absent-neighbour sentinel, `0xFFFFFFFF` on the wire and `DUST_NONE` in GLSL. */
    const val NONE = -1

    /** `vec4 pos, vec4 prev, vec4 aux`. */
    const val PARTICLE_BYTES = 48

    /** `vec4 rest, uvec4 nbrA, uvec4 nbrB, vec4 lenA, vec4 lenB`. */
    const val LINKS_BYTES = 80

    /** `uvec4 header[2]` at the front of the history buffer. */
    const val HISTORY_HEADER_BYTES = 32

    /** `vec4` per particle per slot. */
    const val HISTORY_SAMPLE_BYTES = 16

    /** `"DUST"`, in `header[1].z`. A reader that does not see this is looking at the wrong buffer. */
    const val HISTORY_MAGIC = 0x44555354

    /** Bumped whenever the meaning of a header word changes; `header[1].w`. */
    const val HISTORY_VERSION = 1

    /** `vec4`s to allocate for [count] particles of state — `defaultBuffer { size = ....vec4s }`. */
    fun stateVec4s(count: Int): Int = count * (PARTICLE_BYTES / 16)

    /** `vec4`s to allocate for [count] particles of links. */
    fun linksVec4s(count: Int): Int = count * (LINKS_BYTES / 16)

    /** `vec4`s to allocate for a [capacity]-deep history of [count] particles, header included. */
    fun historyVec4s(count: Int, capacity: Int): Int =
        HISTORY_HEADER_BYTES / 16 + count * capacity

    /** Device bytes across both state buffers, the links, and the history. */
    fun deviceBytes(count: Int, historyCapacity: Int): Long =
        2L * count * PARTICLE_BYTES + count.toLong() * LINKS_BYTES +
            historyVec4s(count, historyCapacity).toLong() * 16
}

/**
 * A cloud of particles and the network over it: everything the GPU needs, laid out for [writeState]
 * and [writeLinks] and nothing more.
 *
 * Positions are stored flat (`3 * count` floats, xyz) rather than as objects, because the only two
 * things that ever happen to them are "build a neighbour network out of them" and "copy them into a
 * `vec4` buffer", and both want the flat form. The initial position *is* the rest position — a field
 * that starts at rest and is then pushed around by noise, which is the useful starting state for the
 * rest-tether force to mean anything.
 *
 * Build one with [random] and hand it to [DustOrthant.build] for the links, or use [randomWithLinks]
 * to do both.
 */
class DustField(
    /** How many particles. */
    val count: Int,
    /** `3 * count` floats, xyz per particle — both the initial and the rest position. */
    val position: FloatArray,
    /** `1/mass` per particle. Zero pins a particle: no force moves it. */
    val invMass: FloatArray,
    /** A stable per-particle random in `0..1`, reachable in a force program as `?gHash`. */
    val hash: FloatArray,
    /** Per-particle multiplier on the rest-tether and edge stiffness, `1` unless something set it. */
    val stiffness: FloatArray
) {
    init {
        require(count > 0) { "a dust field needs at least one particle" }
        require(position.size == count * 3) { "position must be 3 floats per particle, got ${position.size} for $count" }
        require(invMass.size == count) { "invMass must be one float per particle" }
        require(hash.size == count) { "hash must be one float per particle" }
        require(stiffness.size == count) { "stiffness must be one float per particle" }
    }

    /** The axis-aligned bounds of the cloud, as `(minX, minY, minZ, maxX, maxY, maxZ)`. */
    fun bounds(): FloatArray {
        val b = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        )
        for (i in 0 until count) for (a in 0 until 3) {
            val v = position[i * 3 + a]
            if (v < b[a]) b[a] = v
            if (v > b[3 + a]) b[3 + a] = v
        }
        return b
    }

    /** Distance between particles [i] and [j]. */
    fun distance(i: Int, j: Int): Float {
        val dx = position[i * 3] - position[j * 3]
        val dy = position[i * 3 + 1] - position[j * 3 + 1]
        val dz = position[i * 3 + 2] - position[j * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Pack the mutable half into `pos / prev / aux`, ready for one of the two state buffers.
     *
     * `prev` is set to `pos`, so the field starts at rest — a Verlet integrator reads zero velocity
     * from that and the first frame's motion comes entirely from the force programs. Write *both*
     * state buffers with this: the ping-pong reads one before it has written it once.
     *
     * @param aux the initial value of the four free channels
     */
    fun writeState(into: ByteBuffer, aux: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)) {
        require(aux.size == 4) { "aux is four channels" }
        val b = into.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            b.putFloat(position[i * 3]); b.putFloat(position[i * 3 + 1]); b.putFloat(position[i * 3 + 2])
            b.putFloat(invMass[i])
            b.putFloat(position[i * 3]); b.putFloat(position[i * 3 + 1]); b.putFloat(position[i * 3 + 2])
            b.putFloat(hash[i])
            aux.forEach { b.putFloat(it) }
        }
    }

    /**
     * Pack the immutable half — rest position and the network — for the links buffer.
     *
     * Absent neighbour slots get [DustLayout.NONE] and a rest length of zero, and the shader tests the
     * index rather than the length, so a zero-length edge can never turn into a division by zero the
     * way it would if absence were encoded in the length alone.
     */
    fun writeLinks(into: ByteBuffer, links: DustLinks, flags: IntArray? = null) {
        require(links.count == count) { "links describe ${links.count} particles, field has $count" }
        require(flags == null || flags.size == count) { "flags must be one per particle" }
        val slots = DustLayout.EDGES
        val b = into.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            b.putFloat(position[i * 3]); b.putFloat(position[i * 3 + 1]); b.putFloat(position[i * 3 + 2])
            b.putFloat(stiffness[i])
            for (s in 0 until 4) b.putInt(links.neighbour[i * slots + s])
            b.putInt(links.neighbour[i * slots + 4])
            b.putInt(links.neighbour[i * slots + 5])
            b.putInt(links.degree[i])
            b.putInt(flags?.get(i) ?: 0)
            for (s in 0 until 4) b.putFloat(links.restLength[i * slots + s])
            b.putFloat(links.restLength[i * slots + 4])
            b.putFloat(links.restLength[i * slots + 5])
            b.putFloat(links.meanLength(i))
            b.putFloat(0f)
        }
    }

    /** One line for a box's output channel. */
    fun describe(): String {
        val b = bounds()
        return "dust: $count particles in " +
            "[%.2f..%.2f] x [%.2f..%.2f] x [%.2f..%.2f]".format(b[0], b[3], b[1], b[4], b[2], b[5]) +
            ", ${"%.2f".format(count * DustLayout.PARTICLE_BYTES / 1e6)} MB of state"
    }

    companion object {

        /**
         * A uniform random cube of particles, centred on the origin.
         *
         * Uniform and not, say, Poisson-disc on purpose: a uniform sample has a wide spread of nearest
         * neighbour distances, which is exactly what makes an orthant network *look* like a network
         * rather than like a lattice. The [DustOrthant] rest lengths inherit that spread, so the
         * relaxed field has structure in it before any force program has been written.
         *
         * @param extent half-extent of the cube, per axis
         * @param seed fixed by default, because a network you cannot reproduce is a network you cannot
         *   debug — pass `System.nanoTime()` for a different cloud each run
         */
        fun random(
            count: Int,
            extent: Float = 1f,
            seed: Long = 0x64757374L,
            pinFraction: Float = 0f
        ): DustField {
            val rng = java.util.Random(seed)
            val position = FloatArray(count * 3)
            val invMass = FloatArray(count)
            val hash = FloatArray(count)
            val stiffness = FloatArray(count) { 1f }
            for (i in 0 until count) {
                position[i * 3] = (rng.nextFloat() * 2f - 1f) * extent
                position[i * 3 + 1] = (rng.nextFloat() * 2f - 1f) * extent
                position[i * 3 + 2] = (rng.nextFloat() * 2f - 1f) * extent
                hash[i] = rng.nextFloat()
                invMass[i] = if (pinFraction > 0f && rng.nextFloat() < pinFraction) 0f else 1f
            }
            return DustField(count, position, invMass, hash, stiffness)
        }

        /** [random] plus its orthant network, the one call a box needs. */
        fun randomWithLinks(
            count: Int,
            extent: Float = 1f,
            seed: Long = 0x64757374L,
            pinFraction: Float = 0f,
            settings: DustOrthantSettings = DustOrthantSettings()
        ): Pair<DustField, DustLinks> {
            val field = random(count, extent, seed, pinFraction)
            return field to DustOrthant.build(field, settings)
        }
    }
}

/**
 * The history buffer's header, read back off the GPU.
 *
 * Everything here is written by the history compute shader from its push constants, so reading it back
 * is how you find out whether that shader ran at all — a buffer that was allocated but never dispatched
 * reads as all zeroes, and [magic] is what tells the two apart.
 *
 * Needs the history buffer to have been allocated `debugReads()` or host-visible; a `zeroInit()`
 * device-local buffer has nothing to read.
 */
data class DustHistoryHeader(
    /** Frames of history per particle. */
    val capacity: Int,
    /** The slot the *next* frame will be written into; the newest sample is at `head - 1`. */
    val head: Int,
    /** Frames written so far, saturating at [capacity]. Below it, the buffer is partly empty. */
    val count: Int,
    /** Particles the history covers. */
    val particles: Int,
    /** The global frame counter at the last write. */
    val frame: Int,
    /** `vec4`s per particle per slot. 1 today; the field exists so a wider sample can be detected. */
    val stride: Int,
    /** [DustLayout.HISTORY_MAGIC] if the history shader has ever run. */
    val magic: Int,
    /** [DustLayout.HISTORY_VERSION]. */
    val version: Int
) {
    /** True when the shader has run and the header is self-consistent. */
    val valid: Boolean
        get() = magic == DustLayout.HISTORY_MAGIC && version == DustLayout.HISTORY_VERSION &&
            capacity > 0 && particles > 0 && stride == 1 && head in 0 until capacity && count in 0..capacity

    /** Why [valid] is false, or `null`. The message names the value, for a box's output channel. */
    fun check(): String? = when {
        magic == 0 && capacity == 0 -> "the history shader has never run (header is all zero)"
        magic != DustLayout.HISTORY_MAGIC -> "magic is 0x%08x, expected 0x%08x".format(magic, DustLayout.HISTORY_MAGIC)
        version != DustLayout.HISTORY_VERSION -> "version is $version, this reader knows ${DustLayout.HISTORY_VERSION}"
        capacity <= 0 -> "capacity is $capacity"
        particles <= 0 -> "particles is $particles"
        stride != 1 -> "stride is $stride, this reader knows 1"
        head !in 0 until capacity -> "head $head is outside 0..${capacity - 1}"
        count !in 0..capacity -> "count $count is outside 0..$capacity"
        else -> null
    }

    /** Slot holding the sample written [age] frames ago, or -1 when the history is not that deep. */
    fun slotForAge(age: Int): Int =
        if (age < 0 || age >= count) -1 else ((head - 1 - age) % capacity + capacity) % capacity

    override fun toString(): String =
        "history: $count/$capacity frames of $particles particles, head $head, at frame $frame" +
            (check()?.let { " — INVALID: $it" } ?: "")

    companion object {
        /** Decode the first [DustLayout.HISTORY_HEADER_BYTES] of a history readback. */
        fun read(from: ByteBuffer): DustHistoryHeader {
            val b = from.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            fun w(i: Int) = b.getInt(b.position() + i * 4)
            return DustHistoryHeader(
                capacity = w(0), head = w(1), count = w(2), particles = w(3),
                frame = w(4), stride = w(5), magic = w(6), version = w(7)
            )
        }
    }
}
