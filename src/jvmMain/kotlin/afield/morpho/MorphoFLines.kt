package afield.morpho

import afield.Rect
import afield.graphics.FLine
import afield.Colormaps
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drawing a MorphoHDL circuit with Field's FLines.
 *
 * This is the half of the bridge morpho does not supply and Field does. Upstream renders with
 * SwissGL and a Canvas 2D fallback (`viewer.js`, `layout_renderer.js`, `bloom.js` — about 1,900
 * lines), none of which is vendored. Field already draws graphs, so a circuit becomes one FLine
 * per wire and one point-set per gate, and everything downstream — SVG export, the plane grid,
 * animation, the docs capture pipeline — comes along for free.
 *
 * ## Colour carries the simulation
 *
 * By default wires are coloured by **signal arrival time**, which is what makes the picture worth
 * looking at rather than a hairball: the inputs are one end of the ramp, the critical path is the
 * other, and the difference between a ripple adder and a Brent–Kung is visible as a gradient
 * rather than inferable from a gate count.
 */

/**
 * Compile a MorphoHDL design, ready to grow.
 *
 * A shorthand for [MorphoCircuit.compile] that takes the signature as varargs, so a box reads
 * `morphoCircuit("brent_kung_adder", 32, 32, 1)` rather than spelling out an `intArrayOf`.
 *
 * [source] defaults to morpho's own standard library, which already defines every design named on
 * this page.
 *
 * ```kotlin
 * import afield.morpho.*
 *
 * val c = morphoCircuit("brent_kung_adder", 32, 32, 1)
 * c.growFully(relaxSteps = 250)
 * showPlaneGrid(c.summary() to c.flines(), window = morphoPlaneWindow(c.planeBounds()))
 * ```
 */
fun morphoCircuit(
    design: String,
    vararg signature: Int,
    source: String = MorphoRuntime.standardLibrary,
    optimize: Boolean = true,
    seed: Int = 1337,
): MorphoCircuit = MorphoCircuit.compile(source, design, signature, optimize, seed)

/** Flattens a 3D layout position to the plane a drawing lives in. */
fun interface MorphoProjector {
    fun project(x: Float, y: Float, z: Float): Pair<Double, Double>
}

/** The three fixed views worth having names. */
enum class MorphoProjection : MorphoProjector {
    /** Drop z. Directly comparable with morpho's own 2D renderer, which pins z to zero anyway. */
    XY,

    /** Isometric. The default, because the layout is genuinely 3D and XY throws that away. */
    ISOMETRIC,

    /** Look down the y axis — the view in which a "flat" layout would be a horizontal line. */
    XZ,
    ;

    override fun project(x: Float, y: Float, z: Float): Pair<Double, Double> = when (this) {
        XY -> x.toDouble() to y.toDouble()
        XZ -> x.toDouble() to z.toDouble()
        ISOMETRIC -> morphoOrbit(0.7853982).project(x, y, z)
    }
}

/**
 * A view from an arbitrary angle around the vertical axis.
 *
 * Turning [azimuth] over a launched box's frames orbits the camera, which is the only honest way to
 * show in a still medium that the layout is a solid: a projection of a plane collapses to a line
 * once per half turn, and a projection of a solid never does.
 *
 * @param azimuth radians around y.
 * @param elevation how far the camera is lifted; 0 looks along the horizon.
 */
fun morphoOrbit(azimuth: Double, elevation: Double = 0.5235988): MorphoProjector =
    MorphoProjector { x, y, z ->
        val ca = cos(azimuth)
        val sa = sin(azimuth)
        val rx = x * ca - z * sa
        val rz = x * sa + z * ca
        rx to y + rz * sin(elevation)
    }

/**
 * Projected bounds of a laid-out circuit as `[xMin, yMin, xMax, yMax]`, square and padded.
 *
 * Two figures drawn without a shared window are drawn at two different scales, and a comparison
 * between them is worthless — the same trap [afield.stdlib.dynamicGraphWindow] exists to avoid.
 * Pass the result through [morphoPlaneWindow] and hand that to
 * [afield.stdlib.showPlaneGrid] for every panel.
 *
 * For an animation, union successive calls with [morphoWindowUnion] so the frame only ever grows:
 * a window recomputed per frame around a circuit that is still expanding jitters on every step.
 */
fun MorphoCircuit.planeBounds(
    projection: MorphoProjector = MorphoProjection.ISOMETRIC,
    padFraction: Double = 0.08,
): DoubleArray {
    val pos = layout.positions()
    val n = layout.particleCount
    var xMin = Double.POSITIVE_INFINITY
    var yMin = Double.POSITIVE_INFINITY
    var xMax = Double.NEGATIVE_INFINITY
    var yMax = Double.NEGATIVE_INFINITY
    for (p in 0 until n) {
        val (x, y) = projection.project(pos[p * 3], pos[p * 3 + 1], pos[p * 3 + 2])
        if (!x.isFinite() || !y.isFinite()) continue
        if (x < xMin) xMin = x
        if (x > xMax) xMax = x
        if (y < yMin) yMin = y
        if (y > yMax) yMax = y
    }
    if (!xMin.isFinite()) return doubleArrayOf(-1.0, -1.0, 1.0, 1.0)
    val cx = 0.5 * (xMin + xMax)
    val cy = 0.5 * (yMin + yMax)
    // Square, so a panel never stretches the circuit on one axis.
    val half = maxOf(1e-9, 0.5 * maxOf(xMax - xMin, yMax - yMin)) * (1.0 + padFraction)
    return doubleArrayOf(cx - half, cy - half, cx + half, cy + half)
}

/** The smallest window containing both, for a frame that grows with the circuit but never shrinks. */
fun morphoWindowUnion(a: DoubleArray?, b: DoubleArray): DoubleArray {
    if (a == null) return b
    return doubleArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
}

/**
 * The window for panels drawn with `fit = true`, which normalise themselves into the unit square.
 *
 * Slightly larger than the unit square so a port dot on the boundary is not clipped in half.
 */
fun morphoUnitWindow(): Rect = Rect(-1.08, -1.08, 2.16, 2.16)

/**
 * A [planeBounds] result as the [Rect] [afield.stdlib.showPlaneGrid] wants.
 *
 * y is negated to match [morphoFLines], which negates it exactly once when it emits geometry.
 */
fun morphoPlaneWindow(window: DoubleArray): Rect {
    require(window.size == 4) { "morphoPlaneWindow: window must be [xMin, yMin, xMax, yMax]" }
    require(window[2] > window[0] && window[3] > window[1]) {
        "morphoPlaneWindow: window must be non-degenerate, got ${window.toList()}"
    }
    return Rect(window[0], -window[3], window[2] - window[0], window[3] - window[1])
}

/**
 * One FLine per wire, plus one point-set per cell type.
 *
 * @param colormap sampled by normalised arrival time; `"magma"` matches the rest of Field's graph
 *   documentation.
 * @param byDepth colour wires by signal arrival time. With this off every wire is drawn at the
 *   top of the ramp, which is what you want when the picture is about topology alone.
 * @param minIntensity how dark the earliest wires get. Zero makes the input side vanish into the
 *   background, which reads as a hole rather than as an input.
 * @param gateSize dot radius for LUT cells, in the units [FLine] uses for points.
 * @param portSize dot radius for the design's input and output ports, drawn larger because they
 *   are the handles you reason about.
 */
fun MorphoCircuit.flines(
    projection: MorphoProjector = MorphoProjection.ISOMETRIC,
    colormap: String = "magma",
    byDepth: Boolean = true,
    minIntensity: Double = 0.15,
    thinWire: Double = 0.7,
    thickWire: Double = 1.6,
    gateSize: Double = 0.45,
    portSize: Double = 1.1,
    fit: Boolean = false,
): List<FLine> = morphoFLines(
    netlist = netlist,
    timing = timing,
    positions = layout.positions(),
    cellIndices = layout.cellIndices(),
    projection = projection,
    colormap = colormap,
    byDepth = byDepth,
    minIntensity = minIntensity,
    thinWire = thinWire,
    thickWire = thickWire,
    gateSize = gateSize,
    portSize = portSize,
    fit = fit,
)

/**
 * The same drawing from raw parts, for callers holding a netlist and positions from somewhere
 * other than [MorphoCircuit] — a recorded layout, say, or Field's own graph systems standing in
 * for the WASM kernel.
 */
fun morphoFLines(
    netlist: MorphoNetlist,
    timing: MorphoTiming?,
    positions: FloatArray,
    cellIndices: IntArray,
    projection: MorphoProjector = MorphoProjection.ISOMETRIC,
    colormap: String = "magma",
    byDepth: Boolean = true,
    minIntensity: Double = 0.15,
    thinWire: Double = 0.7,
    thickWire: Double = 1.6,
    gateSize: Double = 0.45,
    portSize: Double = 1.1,
    fit: Boolean = false,
): List<FLine> {
    require(minIntensity in 0.0..1.0) { "morphoFLines: minIntensity must be in 0..1, found $minIntensity" }
    require(thinWire > 0.0 && thickWire > 0.0) { "morphoFLines: wire widths must be > 0" }
    require(positions.size >= cellIndices.size * 3) {
        "morphoFLines: ${cellIndices.size} particles need ${cellIndices.size * 3} coordinates, got ${positions.size}"
    }

    // Cell index -> plane position. Cells with no particle (inactive) are simply absent.
    val plane = HashMap<Int, Pair<Double, Double>>(cellIndices.size * 2)
    for (p in cellIndices.indices) {
        val cell = cellIndices[p]
        // y is negated exactly once, here, to match Field's screen-space convention for figures.
        val (x, y) = projection.project(positions[p * 3], positions[p * 3 + 1], positions[p * 3 + 2])
        plane[cell] = x to -y
    }

    // `showPlaneGrid` frames every panel with ONE window, so comparing four circuits of four
    // different sizes in one grid means normalising each into the same unit square here — there is
    // nowhere downstream to do it per panel. Off by default: rescaling silently destroys the one
    // thing a shared window is for, which is making two panels commensurable.
    if (fit && plane.isNotEmpty()) {
        var xLo = Double.POSITIVE_INFINITY; var xHi = Double.NEGATIVE_INFINITY
        var yLo = Double.POSITIVE_INFINITY; var yHi = Double.NEGATIVE_INFINITY
        plane.values.forEach { (x, y) ->
            if (x < xLo) xLo = x; if (x > xHi) xHi = x
            if (y < yLo) yLo = y; if (y > yHi) yHi = y
        }
        val cx = 0.5 * (xLo + xHi)
        val cy = 0.5 * (yLo + yHi)
        val half = 0.5 * maxOf(xHi - xLo, yHi - yLo)
        if (half > 1e-9) plane.replaceAll { _, v -> (v.first - cx) / half to (v.second - cy) / half }
    }

    val maxTime = if (byDepth && timing != null) {
        netlist.activeCells.maxOfOrNull { timing.last(it) }?.takeIf { it > 0f } ?: 1f
    } else 1f

    val out = ArrayList<FLine>()

    val edges = netlist.edges()
    var i = 0
    while (i < edges.size) {
        val src = edges[i]
        val dst = edges[i + 1]
        i += 3
        val a = plane[src] ?: continue
        val b = plane[dst] ?: continue

        // A wire's colour is when its *driver* settles, so a wire reads as "this value is known by
        // now" rather than as a property of whatever happens to consume it.
        val t = if (byDepth && timing != null) {
            (timing.last(src) / maxTime).coerceIn(0f, 1f).toDouble()
        } else 1.0
        val ramp = minIntensity + (1.0 - minIntensity) * t
        val c = Colormaps.sample(colormap, ramp)

        out.add(
            fline {
                moveTo(a.first, a.second)
                lineTo(b.first, b.second)
                this.color = vec(c.r, c.g, c.b, 1.0)
                this.thickness = thinWire + (thickWire - thinWire) * t
                this.stroked = true
                this.filled = false
            }
        )
    }

    fun points(cells: List<Int>, size: Double, colour: Vec4) {
        if (cells.isEmpty() || size <= 0.0) return
        out.add(
            fline {
                cells.forEach { c -> plane[c]?.let { (x, y) -> moveTo(x, y) } }
                this.color = colour
                this.pointed = true
                this.pointSize = size
                this.stroked = false
                this.filled = false
            }
        )
    }

    val gates = netlist.activeCells.filter { netlist.isLut(it) }
    val inputs = netlist.inputCells.filter { netlist.isActive(it) }
    val outputs = netlist.outputCells.filter { netlist.isActive(it) }

    val top = Colormaps.sample(colormap, 1.0)
    points(gates, gateSize, vec(top.r, top.g, top.b, 0.9))
    // Ports are drawn off the colormap so they never read as "just another gate at some depth".
    points(inputs, portSize, vec(0.35, 0.85, 1.0, 0.95))
    points(outputs, portSize, vec(1.0, 0.55, 0.25, 0.95))

    return out
}
