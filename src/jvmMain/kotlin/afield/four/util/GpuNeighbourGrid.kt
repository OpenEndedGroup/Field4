package afield.four.util

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.buffer
import afield.four.device.CommandBuffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.ComputeModulePointer
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.fromContext
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.ComputeTarget
import afield.stdlib.linalg.Rectanglef
import afield.stdlib.linalg.Vector2f
import kotlin.math.ceil

/**
 * A uniform spatial grid over a buffer of particles, built on the GPU every frame, so a compute
 * shader can ask "who is near me" without looking at everybody.
 *
 * This is what [GpuRadixSort] is for. The construction is the standard one and it is worth
 * stating because every step of it exists to avoid something a fragment shader cannot do:
 *
 * 1. **hash** — one invocation per particle writes `key = cell index`, `value = particle index`.
 * 2. **sort** — [GpuRadixSort] sorts the key/value pairs, so every cell's members become a
 *    *contiguous run* in the value array. This is the step that turns a scatter problem into a
 *    gather problem, and it is why a neighbour query needs a sort at all.
 * 3. **ranges** — one invocation per sorted entry compares its key with its neighbours' and
 *    writes the start and end of each run. No atomics: an entry can see whether it is the first
 *    or last of its run by looking left and right, so the only writer of `cellStart[c]` is the
 *    single entry that begins run `c`.
 * 4. **read** — a consumer walks the 3x3 block of cells around its own and iterates each run.
 *
 * ### Row-major cells, not Morton
 *
 * `mortonPointQuadtree` in `FabricatePassLibrary` sorts by Morton code, because a *hierarchy*
 * needs a node's children to be contiguous. A flat grid does not: it needs a *cell's* members to
 * be contiguous, and the row-major index does that just as well while making the nine cell
 * indices of a 3x3 neighbourhood plain arithmetic instead of a bit interleave. Morton would buy
 * better cache locality between adjacent cells and cost clarity; measure before reaching for it.
 *
 * ### The radius is the cell size
 *
 * The 3x3 block around a particle's cell covers everything within one cell width, so
 * [cellsPerSide] must be chosen so that one cell is at least the interaction radius. Make the
 * cells smaller than the radius and the query silently misses neighbours — the shader still
 * runs, the flock still flocks, and it is simply wrong. `GpuNeighbourGridSmoke` asserts the
 * neighbour *sets* against brute force for exactly this reason: a force comparison would let a
 * few missing neighbours hide inside a tolerance, and a set comparison cannot.
 *
 * Particles outside `[domainMin, domainMin + domainSize)` are clamped into the edge cells rather
 * than dropped, so they stay findable; they just stop being spatially separated out there.
 */
class GpuNeighbourGrid(
    /** How many particles the grid is sized for. */
    val capacity: Int,
    /** Cells along each axis. `cellsPerSide * cellsPerSide` cells in total. */
    val cellsPerSide: Int,
    /** Lower corner of the gridded region, in whatever coordinates the positions are in. */
    var domainMin: Vector2f,
    /** Side length of the gridded region, in the same coordinates. */
    var domainSize: Float,
    val renderer: Renderer = fromContext(),
    /** Distinguishes this grid's buffers and modules from another's. */
    val name: String = "neighbourGrid",
) {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
        require(cellsPerSide in 2..4096) { "cellsPerSide of $cellsPerSide is outside 2..4096" }
        require(domainSize > 0f) { "domainSize must be positive, got $domainSize" }
    }

    /** Total cells. */
    val cellCount: Int = cellsPerSide * cellsPerSide

    /** The width of one cell — the largest interaction radius a 3x3 query covers. */
    val cellSize: Float get() = domainSize / cellsPerSide

    /** Cell index per particle, sorted after [install] runs. */
    val keys: FrameBufferGroup = uintBuffer("${name}Keys", capacity)

    /** Particle index per entry, carried along by the sort — the gather order. */
    val values: FrameBufferGroup = uintBuffer("${name}Values", capacity)

    /** First entry of each cell's run in [values]. */
    val cellStart: FrameBufferGroup = uintBuffer("${name}CellStart", cellCount)

    /** One past the last entry of each cell's run. Equal to [cellStart] for an empty cell. */
    val cellEnd: FrameBufferGroup = uintBuffer("${name}CellEnd", cellCount)

    private val sorter = GpuRadixSort(renderer)

    private var hashModule: ComputeModulePointer? = null
    private var clearModule: ComputeModulePointer? = null
    private var rangeModule: ComputeModulePointer? = null

    /**
     * A uint buffer shaped the way [GpuRadixSort] expects one.
     *
     * The recipe is copied verbatim from the `6. radix sort` lesson in `canonical_graphics`, and
     * the temptation to "improve" it with `streamReads()` is a trap that cost an afternoon.
     * `streamReads()` is the right access pattern for a buffer the CPU reads back — that is the
     * standing rule in `field_probes.md` — but it changes `accessPattern` and adds `HOST_CACHED`,
     * and `BufferParameters.checkCompatibility` compares `accessPattern` exactly. A sort handed an
     * incompatible binding resolves a **different allocation**, sorts that perfectly, and leaves
     * the buffer you passed in untouched. No error, no warning: the keys simply come back exactly
     * as the hash wrote them, which reads as "the sort never ran".
     */
    private fun uintBuffer(bufferName: String, count: Int): FrameBufferGroup =
        buffer(name = bufferName) {
            size = count * 4L
            storage()
            addressable()
            hosted()
            dest()
        }.toBuffer(renderer)

    /**
     * Installs the four stages into [into], in order, under [name].
     *
     * All four go in as a single `others` entry rather than as separate compute modules, and that
     * is not tidiness: `ComputeTarget.render` runs every module before any `other`, so a sort
     * registered as an `other` would run *after* the hash and the range passes that have to
     * bracket it. Sequencing them by hand inside one `Target` is the only way to get the order
     * the algorithm requires, and it is also where the barriers go.
     *
     * [positions] is read as a flat float array: particle `i`'s x is at
     * `i * strideFloats + offsetFloats`. That is deliberately general — the grid does not need to
     * know what else is in your particle struct.
     */
    fun install(
        into: ComputeTarget,
        positions: FrameBufferGroup,
        count: Int,
        strideFloats: Int,
        offsetFloats: Int = 0,
    ) = install(into.others, positions, count, strideFloats, offsetFloats)

    /** As [install], into any target's `others` map — a [afield.four.targets.RenderTarget]'s included. */
    fun install(
        into: MutableMap<String, Target>,
        positions: FrameBufferGroup,
        count: Int,
        strideFloats: Int,
        offsetFloats: Int = 0,
    ) {
        require(count in 1..capacity) { "count $count is outside 1..$capacity" }
        require(strideFloats >= 2) { "a 2D position needs a stride of at least 2, got $strideFloats" }

        val hash = hashModule ?: buildModule("$name-hash", HASH_SHADER).also { hashModule = it }
        val clear = clearModule ?: buildModule("$name-clear", CLEAR_SHADER).also { clearModule = it }
        val ranges = rangeModule ?: buildModule("$name-ranges", RANGE_SHADER).also { rangeModule = it }

        ComputeMesh(renderer, hash).apply {
            x = groups(count); y = 1; z = 1
            push.bind("positions") { positions }
            push.bind("keys") { this@GpuNeighbourGrid.keys }
            push.bind("values") { this@GpuNeighbourGrid.values }
            push.bind("domainMin") { domainMin }
            push.bind("invCellSize") { cellsPerSide / domainSize }
            push.bind("cells") { cellsPerSide }
            push.bind("stride") { strideFloats }
            push.bind("offset") { offsetFloats }
            push.bind("count") { count }
            hash.current.meshes["$name-hash"] = this
        }
        ComputeMesh(renderer, clear).apply {
            x = groups(cellCount); y = 1; z = 1
            push.bind("cellStart") { this@GpuNeighbourGrid.cellStart }
            push.bind("cellEnd") { this@GpuNeighbourGrid.cellEnd }
            push.bind("count") { cellCount }
            clear.current.meshes["$name-clear"] = this
        }
        ComputeMesh(renderer, ranges).apply {
            x = groups(count); y = 1; z = 1
            push.bind("keys") { this@GpuNeighbourGrid.keys }
            push.bind("cellStart") { this@GpuNeighbourGrid.cellStart }
            push.bind("cellEnd") { this@GpuNeighbourGrid.cellEnd }
            push.bind("count") { count }
            ranges.current.meshes["$name-ranges"] = this
        }

        val bounds = Rectanglef(0f, 0f, 0f, 0f)
        into[name] = Target { command: CommandBuffer, _: Int ->
            hash.render(command, bounds)
            clear.render(command, bounds)
            barrier(command)
            // The sort puts its own barrier in front of the keys and values it is about to read.
            sorter.sort(command, keys, count, values)
            barrier(command)
            ranges.render(command, bounds)
            barrier(command)
        }
    }

    /** Takes the grid back out of [into]. */
    fun removeFrom(into: ComputeTarget) = removeFrom(into.others)

    /** Takes the grid back out of any target's `others` map. */
    fun removeFrom(into: MutableMap<String, Target>) {
        into.remove(name)
    }

    fun cleanup() = sorter.cleanup()

    private fun barrier(command: CommandBuffer) {
        computeMemoryBarrier(command, keys.get().buffer, keys.get().requestedSize)
        computeMemoryBarrier(command, values.get().buffer, values.get().requestedSize)
        computeMemoryBarrier(command, cellStart.get().buffer, cellStart.get().requestedSize)
        computeMemoryBarrier(command, cellEnd.get().buffer, cellEnd.get().requestedSize)
    }

    private fun groups(items: Int) = ceil(items / LOCAL_SIZE.toDouble()).toInt().coerceAtLeast(1)

    private fun buildModule(moduleName: String, source: String): ComputeModulePointer =
        ComputeModule(parameters(renderer) { name = moduleName; compute = source }).pointer()

    /**
     * GLSL declaring this grid's buffer-reference types and the two index helpers a consumer
     * needs, plus a worked comment showing the 3x3 walk.
     *
     * Deliberately *not* a `forEachNeighbour` macro. GLSL has no closures, so the only ways to
     * hide the loop are a `#define` with a body argument or a callback by name, and both make the
     * one piece of the shader an author most wants to read and modify — the inner loop over
     * neighbours — the piece they cannot see. The loop is six lines; they are better off with it.
     *
     * Place immediately after `#version`: this carries `#extension` directives.
     */
    fun glsl(prefix: String = "grid"): String = """
        #extension GL_EXT_buffer_reference : require

        layout(std430, buffer_reference) buffer ${prefix}Uints { uint v[]; };

        const int ${prefix}Cells = $cellsPerSide;

        ivec2 ${prefix}CellOf(vec2 p, vec2 domainMin, float invCellSize) {
            return clamp(ivec2(floor((p - domainMin) * invCellSize)), ivec2(0), ivec2(${prefix}Cells - 1));
        }

        uint ${prefix}CellIndex(ivec2 c) {
            return uint(c.y) * uint(${prefix}Cells) + uint(c.x);
        }

        // The walk, for reference -- write it out in your own shader rather than hiding it:
        //
        //   ivec2 home = ${prefix}CellOf(myPosition, pc.domainMin, pc.invCellSize);
        //   for (int dy = -1; dy <= 1; ++dy)
        //   for (int dx = -1; dx <= 1; ++dx) {
        //       ivec2 c = home + ivec2(dx, dy);
        //       if (c.x < 0 || c.y < 0 || c.x >= ${prefix}Cells || c.y >= ${prefix}Cells) continue;
        //       uint ci = ${prefix}CellIndex(c);
        //       for (uint k = pc.cellStart.v[ci]; k < pc.cellEnd.v[ci]; ++k) {
        //           uint j = pc.values.v[k];   // a neighbouring particle's index
        //       }
        //   }
    """.trimIndent()

    companion object {
        private const val LOCAL_SIZE = 64

        private val HASH_SHADER = """
            #version 450
            #extension GL_EXT_buffer_reference : require
            layout(local_size_x = $LOCAL_SIZE) in;

            layout(std430, buffer_reference) buffer Floats { float v[]; };
            layout(std430, buffer_reference) buffer Uints { uint v[]; };

            layout(push_constant) uniform Push {
                Floats positions;
                Uints keys;
                Uints values;
                vec2 domainMin;
                float invCellSize;
                uint cells;
                uint stride;
                uint offset;
                uint count;
            } pc;

            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= pc.count) return;
                uint base = i * pc.stride + pc.offset;
                vec2 p = vec2(pc.positions.v[base], pc.positions.v[base + 1u]);
                ivec2 c = clamp(
                    ivec2(floor((p - pc.domainMin) * pc.invCellSize)),
                    ivec2(0), ivec2(int(pc.cells) - 1)
                );
                pc.keys.v[i] = uint(c.y) * pc.cells + uint(c.x);
                pc.values.v[i] = i;
            }
        """.trimIndent()

        private val CLEAR_SHADER = """
            #version 450
            #extension GL_EXT_buffer_reference : require
            layout(local_size_x = $LOCAL_SIZE) in;

            layout(std430, buffer_reference) buffer Uints { uint v[]; };

            layout(push_constant) uniform Push {
                Uints cellStart;
                Uints cellEnd;
                uint count;
            } pc;

            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= pc.count) return;
                // start == end means an empty cell, so zeroing both is a valid empty range and
                // needs no separate "unoccupied" sentinel.
                pc.cellStart.v[i] = 0u;
                pc.cellEnd.v[i] = 0u;
            }
        """.trimIndent()

        private val RANGE_SHADER = """
            #version 450
            #extension GL_EXT_buffer_reference : require
            layout(local_size_x = $LOCAL_SIZE) in;

            layout(std430, buffer_reference) buffer Uints { uint v[]; };

            layout(push_constant) uniform Push {
                Uints keys;
                Uints cellStart;
                Uints cellEnd;
                uint count;
            } pc;

            void main() {
                uint j = gl_GlobalInvocationID.x;
                if (j >= pc.count) return;
                uint k = pc.keys.v[j];
                // Look left and right. Exactly one entry begins each run and exactly one ends it,
                // so each cell's start and end have a single writer and no atomic is needed.
                if (j == 0u || pc.keys.v[j - 1u] != k) pc.cellStart.v[k] = j;
                if (j + 1u == pc.count || pc.keys.v[j + 1u] != k) pc.cellEnd.v[k] = j + 1u;
            }
        """.trimIndent()
    }
}
