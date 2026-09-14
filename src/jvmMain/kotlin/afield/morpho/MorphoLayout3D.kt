package afield.morpho

import org.graalvm.polyglot.Value
import java.io.Closeable
import java.nio.file.Files

/**
 * Placement for a MorphoHDL circuit: every active cell gets a 3D position, from a force-directed
 * layout running on morpho's own Barnes–Hut kernel.
 *
 * One of the two things Field does with a [MorphoNetlist] snapshot, the other being [MorphoSim];
 * `MorphoFLines.kt` turns the positions that come out of here into `FLine`s. Layout is stepped
 * alongside growth rather than run to convergence at the end, and a cell born in a growth step is
 * seeded at its parent's position, so a circuit that is still expanding is drawable on every frame.
 *
 * The parameters are upstream's defaults, and the only behavioural difference from morpho's own
 * demo is that Field leaves the z axis free.
 */

/**
 * Force-directed 3D layout for a growing MorphoHDL circuit, computed by morpho's own
 * Barnes–Hut kernel (`graphs_engine/main.wasm`) on GraalWasm.
 *
 * The kernel is 3D throughout — stride-3 positions, an octree, and angular-momentum compensation
 * via a full inertia tensor — but upstream's `force_layout.js` pins `pos[p*3+2] = 0` and clears
 * z-velocity every frame, so morpho's own demo only ever sees it flat. Field does not pin z, and
 * the circuits come out as solids: a fully grown 32-bit Brent–Kung adder occupies a z extent
 * about two thirds of its x extent.
 *
 * ## Using it
 *
 * [sync] after each growth step, then [relax] a few iterations per frame. New cells are seeded at
 * their parent's position, so a subtree unfolds from where it came from rather than materialising
 * somewhere random — which is what makes growth legible.
 */
class MorphoLayout3D(
    private val session: MorphoSession,
    seed: Int = 1337,
    maxPoints: Int = 65_536,
) : Closeable {

    /**
     * Barnes–Hut and integration parameters. Defaults are upstream's, from `force_layout.js`'s
     * constructor, so a Field layout and a morpho layout of the same circuit are comparable.
     */
    data class Params(
        val repulsionForce: Double = 0.05,
        val wireForce: Double = 1.0,
        val velocityDecay: Double = 0.05,
        /** Barnes–Hut opening angle. Larger is faster and coarser. */
        val theta: Double = 0.5,
        val leafSize: Int = 64,
        val maxLevel: Int = 10,
        val linkDistance: Double = 2.0,
        val maxDist: Double = 80.0,
        val maxSpeed: Double = 1.0,
        val gravity: Double = 0.1,
    )

    private val handle: Value
    private var closed = false

    init {
        MorphoRuntime.withJs {
            val bytes = Files.readAllBytes(MorphoRuntime.root.resolve("morpho/graphs_engine/main.wasm"))
            MorphoRuntime.callLayout("initWasm", bytes, maxPoints)
        }
        handle = MorphoRuntime.callLayout("createLayout", seed)
    }

    /** Number of particles, i.e. active cells, after re-mapping onto the current graph. */
    var particleCount: Int = 0
        private set

    /** Re-map particles onto the circuit's currently active cells. Call after each growth step. */
    fun sync(): Int {
        check(!closed) { "layout has been closed" }
        particleCount = MorphoRuntime.callLayout("sync", handle, session.state).asInt()
        return particleCount
    }

    /** Advance the simulation by [steps] Barnes–Hut iterations. */
    fun relax(steps: Int = 1, params: Params = Params()): Int {
        check(!closed) { "layout has been closed" }
        if (particleCount == 0) return 0
        val opts = MorphoRuntime.callLayout(
            "options",
            params.repulsionForce, params.wireForce, params.velocityDecay, params.theta,
            params.leafSize, params.maxLevel, params.linkDistance, params.maxDist,
            params.maxSpeed, params.gravity,
        )
        return MorphoRuntime.callLayout("relax", handle, session.state, steps, opts).asInt()
    }

    /** Particle positions, stride 3 (x, y, z), in particle order. */
    fun positions(): FloatArray =
        MorphoRuntime.callLayout("positions", handle).`as`(FloatArray::class.java)

    /** Cell index for each particle, joining [positions] back to a [MorphoNetlist]. */
    fun cellIndices(): IntArray =
        MorphoRuntime.callLayout("cellIndices", handle).`as`(IntArray::class.java)

    /** `[minX, minY, minZ, maxX, maxY, maxZ]`. */
    fun bounds(): FloatArray =
        MorphoRuntime.callLayout("bounds", handle).`as`(FloatArray::class.java)

    /**
     * How far the layout actually used the third dimension, as z extent over the larger of the x
     * and y extents. Zero would mean it had collapsed to a plane, as upstream's forces it to.
     */
    fun flatness(): Double {
        val b = bounds()
        if (!b[0].isFinite()) return 0.0
        val x = (b[3] - b[0]).toDouble()
        val y = (b[4] - b[1]).toDouble()
        val z = (b[5] - b[2]).toDouble()
        val wide = maxOf(x, y)
        return if (wide <= 0.0) 0.0 else z / wide
    }

    override fun close() {
        closed = true
    }
}
