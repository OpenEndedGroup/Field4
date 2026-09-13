package afield.examples.manifold

import afield.manifold.FillRule
import afield.manifold.ManifoldLibrary
import afield.manifold.Section
import afield.manifold.Solid
import afield.manifold.toSolid
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.NormalPolicy
import afield.stdlib.polytope.PolyhedralObject
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.canonicalDiagnostics
import afield.stdlib.polytope.canonicalize
import afield.stdlib.polytope.ceresRealizationAvailable
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.relaxWithCeres
import afield.stdlib.polytope.triangulate
import afield.stdlib.weld
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cbrt
import kotlin.math.sqrt

/*
FIELD_TEST
id: manifold.polytope.contours
runner: jvm-main
entrypoint: afield.examples.manifold.PolytopeLayerContours
tags: [smoke, manifold, polytope, geometry, contours, optional]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "layer contour study passed"
*/
/**
 * Does canonicalising a Conway polytope change its printable layer contours, and by how much?
 *
 * This is the measurement behind `public/docs/chain_ceres_manifold_contours.md`. Three parts of
 * Field meet here and none of them knows the other two exist: `afield.stdlib.polytope` holds the
 * Conway operator algebra, the canonicalisation iteration and the Ceres realizer;
 * `afield.manifold` does exact geometry over Manifold's C ABI; and [Solid.slice] is the one call
 * that crosses from 3D down to 2D.
 *
 * # Why the normalisation is the whole experiment
 *
 * Canonicalisation applies a *gauge*: the tangency centroid goes to the origin and the mean
 * tangency radius is driven to one. So the canonical form and the raw per-operator realization sit
 * at different scales, and comparing their slice areas directly measures the gauge rather than the
 * shape. Every comparison below puts each form through [onTheBed] first — scaled to **enclosed
 * volume exactly 1**, translated so `z = 0` is the lowest point. That is the print-farm
 * normalisation: the same amount of material, sitting on the bed.
 *
 * Orientation is the second confound, and it is *measured* rather than assumed away.
 * [directionChange] reports the largest angle any vertex's direction from the centroid turns
 * through. Small means the two forms share a frame and one z-ladder cuts both comparably; large
 * would mean the layer comparison is reading a rotation instead of a shape change.
 *
 * # The layer stack is a validator, not just an output
 *
 * A midpoint Riemann sum over the layer areas has to return the volume the layers came from. That
 * makes slicing an independent check on everything upstream of it, computed by code that shares
 * nothing with the realizer — and it is how `kkC` gets caught below.
 *
 * Manifold is optional, and so is the Ceres wrapper; a machine without either prints a clear skip
 * and exits 0, the same contract `afield.manifold.ManifoldSmokeTest` and the Ceres smokes have.
 */
object PolytopeLayerContours {

    private var checks = 0
    private val failures = mutableListOf<String>()

    /** One horizontal cut, reduced to the four numbers a slicer cares about. */
    data class Layer(
        val z: Double,
        /** Every closed contour, holes included. */
        val contours: Int,
        /** Contours with negative signed area — the holes. */
        val holes: Int,
        /** Connected regions, via `Section.decompose()`: how many islands this layer prints as. */
        val islands: Int,
        /** Enclosed area, holes already subtracted — Manifold measures the region, not the outlines. */
        val area: Double,
    )

    /** One embedding of one topology, already gauged onto the bed. */
    private class Arm(val name: String, val polyhedron: PolyhedralObject, val solid: Solid) {
        val height: Double get() = solid.bounds.max.z.toDouble()
        lateinit var layers: List<Layer>
    }

    // ==============================================================================================
    // The chain
    // ==============================================================================================

    /** Conway notation to a Manifold solid: triangulate, weld, hand over positions and indices. */
    private fun solidOf(polyhedron: PolyhedralObject): Solid {
        val geometry = polyhedron.triangulate(normals = NormalPolicy.Flat)
        // The triangulator cuts each polygon on its own, so a corner shared by three faces arrives as
        // three vertices. A manifold is an index property: weld before handing it over.
        return geometry.mesh.weld(1e-6).toSolid()
    }

    /**
     * The print-farm gauge: enclosed volume exactly 1, lowest point at `z = 0`, centred in x and y.
     *
     * Refuses a solid whose signed volume is not positive rather than mirroring it into looking
     * fine, because `1 / cbrt(v)` of a negative `v` is a reflection and would hide the very failure
     * this study is here to find.
     */
    private fun onTheBed(solid: Solid): Solid {
        require(solid.volume > 0.0) {
            "signed volume is ${solid.volume}; this surface is inside out or self-intersecting, " +
                "and scaling it onto the bed would silently mirror it"
        }
        val scaled = solid.scaled(1.0 / cbrt(solid.volume))
        val box = scaled.bounds
        return scaled.translated(
            -box.center.x.toDouble(),
            -box.center.y.toDouble(),
            -box.min.z.toDouble(),
        )
    }

    /** The signed area of a closed contour — positive counter-clockwise, negative for a hole. */
    private fun signedArea(contour: List<Vec2>): Double {
        var total = 0.0
        for (i in contour.indices) {
            val a = contour[i]
            val b = contour[(i + 1) % contour.size]
            total += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        return total / 2.0
    }

    private fun measure(section: Section, z: Double): Layer {
        val polygons = section.toPolygons()
        val islands = section.decompose().onEach(Section::close).size
        return Layer(
            z = z,
            contours = section.contourCount.toInt(),
            holes = polygons.count { signedArea(it) < 0.0 },
            islands = islands,
            area = section.area,
        )
    }

    /** `count` cuts at layer midpoints — where a slicer puts them, not on the layer boundaries. */
    private fun layerStack(solid: Solid, count: Int): List<Layer> {
        val low = solid.bounds.min.z.toDouble()
        val height = solid.bounds.max.z.toDouble() - low
        return (0 until count).map { i ->
            val z = low + height * (i + 0.5) / count
            solid.slice(z).use { measure(it, z) }
        }
    }

    /**
     * The largest and mean angle, over every vertex, between its direction from the centroid before
     * and after a realization change. Vertex indexing is shared, so this is a true per-vertex
     * comparison and not a shape descriptor that might coincide by accident.
     */
    private fun directionChange(before: PolyhedralObject, after: PolyhedralObject): Pair<Double, Double> {
        val a = before.realization!!.vertexCoordinates
        val b = after.realization!!.vertexCoordinates
        require(a.size == b.size) { "the realization changed the vertex count, which it may not do" }
        fun centroid(v: List<Vec3>) = Triple(
            v.sumOf { it.x.toDouble() } / v.size,
            v.sumOf { it.y.toDouble() } / v.size,
            v.sumOf { it.z.toDouble() } / v.size,
        )
        val ca = centroid(a)
        val cb = centroid(b)
        var worst = 0.0
        var total = 0.0
        for (i in a.indices) {
            val ax = a[i].x - ca.first
            val ay = a[i].y - ca.second
            val az = a[i].z - ca.third
            val bx = b[i].x - cb.first
            val by = b[i].y - cb.second
            val bz = b[i].z - cb.third
            val na = sqrt(ax * ax + ay * ay + az * az)
            val nb = sqrt(bx * bx + by * by + bz * bz)
            if (na < 1e-12 || nb < 1e-12) continue
            val cosine = ((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1.0, 1.0)
            val degrees = Math.toDegrees(acos(cosine))
            if (degrees > worst) worst = degrees
            total += degrees
        }
        return worst to total / a.size
    }

    // ==============================================================================================
    // The degenerate cuts — what a slice does when it lands exactly on something
    // ==============================================================================================

    /**
     * Where `slice` is inclusive and where it is not.
     *
     * Every expected value here was read off a first run rather than guessed, and then checked
     * against a second, independent geometry: the rule the cube implies predicts what two cubes
     * touching along one edge do, and it is right.
     */
    private fun degenerateSlices() {
        section("the cuts that land exactly on something")

        // A 2x2x2 cube centred on the origin has faces at z = -1 and z = +1.
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            cube.slice(0.0).use { exact("cut through the middle of a 2x2x2 cube", it.area, 4.0) }
            cube.slice(-1.0).use {
                exact("cut exactly on the BOTTOM face plane: the whole face comes back", it.area, 4.0)
                equal("as a single contour", it.contourCount, 1L)
            }
            cube.slice(1.0).use {
                println("    cut exactly on the top face plane: area ${it.area}, " +
                    "contours ${it.contourCount}, empty ${it.isEmpty}")
                truth("cut exactly on the TOP face plane: nothing comes back", it.isEmpty)
                exact("with zero area, though the face it missed has area 4", it.area, 0.0)
            }
            cube.slice(1.0 - 1e-9).use {
                exact("one part in a billion below the top, the face is back", it.area, 4.0)
            }
            cube.slice(1.0 + 1e-9).use { truth("and above the top there is nothing", it.isEmpty) }
            cube.slice(5.0).use { truth("as there is nowhere near the solid", it.isEmpty) }
        }

        // The same cube sitting on the bed, to show the rule is about the solid's own extent rather
        // than about the origin.
        Solid.cube(2.0, 2.0, 2.0).use { onBed ->
            onBed.slice(0.0).use { exact("a cube sitting on z=0 yields its footprint at z=0", it.area, 4.0) }
            onBed.slice(2.0).use { truth("and nothing at z equal to its own height", it.isEmpty) }
        }

        // A step: a 4x4x1 slab with a 2x2x1 block on top of it. z = 1 is the slab's top face, an
        // upward-facing plane, and also the block's bottom face.
        Solid.scope {
            val slab = Solid.cube(4.0, 4.0, 1.0)
            val block = Solid.cube(2.0, 2.0, 1.0).translated(1.0, 1.0, 1.0)
            val step = (slab + block).checked("step")
            step.slice(0.999999).use { exact("just below the step, the slab's 4x4", it.area, 16.0) }
            step.slice(1.0).use {
                println("    exactly on the step: area ${it.area}, contours ${it.contourCount}")
                exact("exactly on the step, only the block above it: 2x2", it.area, 4.0)
            }
            Unit
        }

        // A cone has one apex vertex, so a cut through it touches the solid in a single point.
        Solid.cone(2.0, 1.0, segments = 256, center = true).use { cone ->
            val apex = cone.bounds.max.z.toDouble()
            cone.slice(apex).use {
                println("    exactly through a cone's apex vertex: area ${it.area}, " +
                    "contours ${it.contourCount}, vertices ${it.vertexCount}")
                truth("a cut exactly through an apex vertex is empty", it.isEmpty)
            }
            cone.slice(apex - 0.5).use {
                // The radius falls linearly from 1 at the base over a height of 2.
                near("half a unit below it, the cone's own circle", it.area, Math.PI * 0.25 * 0.25, 2e-3)
            }
        }

        // Manifold's tetrahedron has a horizontal edge at the bottom and a horizontal edge at the
        // top: a cut on either touches the solid in a segment, which has no area.
        Solid.tetrahedron().use { tet ->
            tet.slice(tet.bounds.max.z.toDouble()).use {
                truth("a tetrahedron cut at its topmost edge is empty", it.isEmpty)
            }
            tet.slice(tet.bounds.min.z.toDouble()).use {
                truth("and at its lowest edge too — a segment has no area to return", it.isEmpty)
            }
        }

        // The independent confirmation. Two unit cubes touching along one edge at z = 0.5: the lower
        // one ends there, the upper one begins there. The rule above says exactly one of them
        // answers, and says which.
        Solid.scope {
            val lower = Solid.cube(1.0, 1.0, 1.0, center = true)
            val upper = Solid.cube(1.0, 1.0, 1.0, center = true).translated(1.0, 0.0, 1.0)
            val pair = Solid.compose(listOf(lower, upper))
            pair.slice(0.5).use {
                val centreX = (it.bounds.first.x + it.bounds.second.x) / 2.0
                println("    two cubes meeting along one edge, cut on that edge: area ${it.area}, " +
                    "contours ${it.contourCount}, centred at x = $centreX")
                exact("only one of the two squares answers", it.area, 1.0)
                near("and it is the upper cube's, at x = 1", centreX, 1.0, 1e-9)
            }
            Unit
        }
    }

    // ==============================================================================================
    // The study
    // ==============================================================================================

    private const val LAYERS = 48

    private fun describeDiagnostics(label: String, p: PolyhedralObject) {
        val d = p.canonicalDiagnostics()
        println(
            "    %-14s tangency spread %.3e   tangency centroid %.3e   planarity %.3e".format(
                label,
                d.tangencyRadiusSpread,
                d.tangencyCentroidOffset,
                d.maxFacePlanarityError,
            ),
        )
    }

    private fun arm(name: String, polyhedron: PolyhedralObject): Arm? {
        val bare = solidOf(polyhedron)
        println(
            "    %-14s Manifold status %s, genus %d, signed volume %.6f, %d triangles".format(
                name,
                bare.status,
                bare.genus,
                bare.volume,
                bare.triangleCount,
            ),
        )
        if (bare.volume <= 0.0) {
            println("    %-14s REFUSED onto the bed: a non-positive signed volume is not a printable body".format(name))
            return null
        }
        return Arm(name, polyhedron, onTheBed(bare))
    }

    private fun report(a: Arm) {
        a.layers = layerStack(a.solid, LAYERS)
        val sum = a.layers.sumOf { it.area } * a.height / LAYERS
        println(
            "    %-14s height %.6f, surface %.6f, contours %d, islands %d, holes %d".format(
                a.name,
                a.height,
                a.solid.surfaceArea,
                a.layers.sumOf { it.contours },
                a.layers.sumOf { it.islands },
                a.layers.sumOf { it.holes },
            ),
        )
        println("    %-14s midpoint Riemann sum over $LAYERS layers = %.6f (the volume is 1)".format(a.name, sum))
    }

    /**
     * How many layers before the stack agrees with the solid it came from.
     *
     * The midpoint rule integrates a smooth `A(z)` almost exactly, so a convex body converges at
     * once and the number is dull. It is not dull for a body with features finer than the layer
     * height: those are stepped over, the sum comes up short, and the rate at which the shortfall
     * closes is a direct measurement of how much of the shape is thinner than one layer. Which is
     * the question a print has to answer before it is worth starting.
     */
    private fun convergence(a: Arm, counts: List<Int> = listOf(12, 24, 48, 96, 192, 384, 768)): Double {
        val line = StringBuilder("    %-14s layers -> Riemann sum:".format(a.name))
        var finest = 0.0
        for (n in counts) {
            val stack = layerStack(a.solid, n)
            val sum = stack.sumOf { it.area } * a.height / n
            finest = sum
            line.append("  %d:%.5f".format(n, sum))
        }
        println(line)
        return finest
    }

    /**
     * Two independent readings of "does this surface pass through itself", from the slicer.
     *
     * `dev_docs/POLYTOPE_ALGEBRA.md` lists self-intersection diagnostics as an open item —
     * `CanonicalDiagnostics` measures tangency, planarity, centring and dihedral spread and none of
     * those sees a face crossing another face. Slicing does, two ways:
     *
     *  - **By fill rule.** [FillRule.POSITIVE] unions overlapping same-wound contours;
     *    `EVEN_ODD` punches their overlap out. Equal areas on every layer means the contours do
     *    not overlap; a gap is twice the overlapped area, layer by layer.
     *  - **By Boolean.** Intersecting with a box that already contains the solid is the identity
     *    on a clean body. It is not the identity on a self-intersecting one: Manifold resolves the
     *    crossings and returns the region, where `volume` integrates the surface and counts the
     *    overlap twice.
     *
     * Both are reported against the signed volume, which is 1 by construction after [onTheBed].
     */
    private fun selfIntersection(a: Arm): Double {
        var disagreeing = 0
        var positiveTotal = 0.0
        var evenOddTotal = 0.0
        val n = 192
        val low = a.solid.bounds.min.z.toDouble()
        val height = a.height - low
        for (i in 0 until n) {
            val z = low + height * (i + 0.5) / n
            val positive = a.solid.slice(z, FillRule.POSITIVE).use { it.area }
            val evenOdd = a.solid.slice(z, FillRule.EVEN_ODD).use { it.area }
            positiveTotal += positive
            evenOddTotal += evenOdd
            if (abs(positive - evenOdd) > 1e-9 * maxOf(1.0, positive)) disagreeing++
        }
        val overlap = (positiveTotal - evenOddTotal) * height / n / 2.0
        println(
            "    %-14s POSITIVE and EVEN_ODD disagree on %d of %d layers; implied overlap volume %.6f".format(
                a.name, disagreeing, n, overlap,
            ),
        )
        val span = maxOf(
            a.solid.bounds.max.x - a.solid.bounds.min.x,
            a.solid.bounds.max.y - a.solid.bounds.min.y,
            a.solid.bounds.max.z - a.solid.bounds.min.z,
        ).toDouble() * 4.0
        Solid.scope {
            val box = Solid.cube(span, span, span, center = true).translated(0.0, 0.0, a.height / 2.0)
            val resolved = a.solid and box
            println(
                "    %-14s signed volume %.6f; intersected with a box that contains it, %.6f".format(
                    a.name, a.solid.volume, resolved.volume,
                ),
            )
            Unit
        }
        return overlap
    }

    private fun compare(left: Arm, right: Arm, expression: String) {
        var worstRatio = 0.0
        var worstAt = -1
        var differingIslands = 0
        for (i in 0 until LAYERS) {
            val l = left.layers[i].area
            val r = right.layers[i].area
            val ratio = abs(r - l) / maxOf(l, r, 1e-12)
            if (ratio > worstRatio) {
                worstRatio = ratio
                worstAt = i
            }
            if (left.layers[i].islands != right.layers[i].islands) differingIslands++
        }
        println(
            "    ${left.name} vs ${right.name}: worst per-layer area disagreement %.1f%% at layer $worstAt of $LAYERS, "
                .format(worstRatio * 100.0) + "island count differs on $differingIslands layers",
        )
        println("    layer |  z(${left.name})  area  cont isl |  z(${right.name})  area  cont isl")
        for (i in listOf(0, LAYERS / 8, LAYERS / 4, LAYERS / 2, 3 * LAYERS / 4, LAYERS - 1)) {
            val l = left.layers[i]
            val r = right.layers[i]
            println(
                "    %5d | %7.4f %9.6f %4d %3d | %7.4f %9.6f %4d %3d".format(
                    i, l.z, l.area, l.contours, l.islands, r.z, r.area, r.contours, r.islands,
                ),
            )
        }
        truth("$expression: the two forms do not slice alike", worstRatio > 0.01)
    }

    /** `gC` and `gD`: canonicalisation succeeds, and the question is what it does to the layers. */
    private fun study(expression: String) {
        section("$expression — the raw realization against its canonical form, both at volume 1")

        val raw = conway(expression).polyhedron
        val canonical = raw.canonicalize().polyhedron
        println(
            "    $expression: ${raw.topology.elements(0).size} vertices, " +
                "${raw.topology.elements(1).size} edges, ${raw.topology.elements(2).size} faces",
        )
        describeDiagnostics("raw", raw)
        describeDiagnostics("canonical", canonical)
        truth(
            "$expression: canonicalisation reduced the tangency-radius spread",
            canonical.canonicalDiagnostics().tangencyRadiusSpread < raw.canonicalDiagnostics().tangencyRadiusSpread,
        )

        val (worstTurn, meanTurn) = directionChange(raw, canonical)
        println("    vertex directions turn by at most %.3f deg, mean %.3f deg".format(worstTurn, meanTurn))
        truth(
            "$expression: the two forms share a frame, so one z-ladder cuts both (worst turn under 30 deg)",
            worstTurn < 30.0,
        )

        Solid.scope {
            val left = arm("raw", raw)!!
            val right = arm("canonical", canonical)!!
            report(left)
            report(right)
            near("$expression: the raw layer stack sums back to its volume", convergence(left), 1.0, 0.005)
            near("$expression: the canonical layer stack sums back to its volume", convergence(right), 1.0, 0.005)
            println(
                "    surface at equal volume: canonical / raw = %.6f (a unit-volume sphere is 4.835976)".format(
                    right.solid.surfaceArea / left.solid.surfaceArea,
                ),
            )
            selfIntersection(left)
            selfIntersection(right)
            compare(left, right, expression)
            Unit
        }
    }

    /**
     * `kkC` — the standing counter-example, sliced.
     *
     * `dev_docs/POLYTOPE_ALGEBRA.md` records `kkC` as the input neither iteration in
     * [canonicalize] solves, and `relaxWithCeres` as the one that does. This runs all three
     * embeddings through the same slicer and asks the layers which of them is a body.
     */
    private fun theCounterExample() {
        section("kkC — the input canonicalize() does not solve, put through the slicer")

        val raw = conway("kkC").polyhedron
        println(
            "    kkC: ${raw.topology.elements(0).size} vertices, " +
                "${raw.topology.elements(1).size} edges, ${raw.topology.elements(2).size} faces",
        )
        val iterated = raw.canonicalize().polyhedron
        describeDiagnostics("raw", raw)
        describeDiagnostics("canonicalize()", iterated)
        truth(
            "kkC: canonicalize() leaves the tangency spread no better — the documented failure",
            iterated.canonicalDiagnostics().tangencyRadiusSpread >=
                raw.canonicalDiagnostics().tangencyRadiusSpread,
        )
        val (turn, _) = directionChange(raw, iterated)
        println("    and it moved vertex directions by up to %.1f deg doing it".format(turn))

        Solid.scope {
            val rawArm = arm("raw", raw)
            truth("kkC: the raw realization is a body", rawArm != null)
            rawArm?.let {
                report(it)
                near("kkC raw: its layers sum back to its volume", convergence(it), 1.0, 0.005)
                selfIntersection(it)
            }

            val iteratedArm = arm("canonicalize()", iterated)
            truth(
                "kkC: what canonicalize() returns has a non-positive signed volume, so it is not a body",
                iteratedArm == null,
            )

            if (!ceresRealizationAvailable()) {
                println()
                println("    The Ceres wrapper is not built here, so the third arm is skipped.")
                println("    ./gradlew buildCeresRedistributable, or point AFIELD_CERES_LIBRARY at a build.")
            } else {
                val solved = raw.relaxWithCeres(
                    RelaxationPlan.canonicalConditions(),
                    CeresRelaxationOptions(maxIterations = 500),
                )
                describeDiagnostics("ceres", solved.realization.polyhedron)
                println("    ${solved.summary}")
                val ceresArm = arm("ceres", solved.realization.polyhedron)
                truth("kkC: the Ceres realizer returns a body where canonicalize() did not", ceresArm != null)
                ceresArm?.let {
                    report(it)
                    // At 48 layers this one comes up roughly a tenth short, and the sweep says why:
                    // the canonical kkC has spikes finer than a 48th of its height, and a midpoint
                    // rule steps over them. It is a statement about layer height, not about Ceres.
                    val finest = convergence(it)
                    val overlap = selfIntersection(it)
                    // The sum plateaus roughly a tenth short and stays there, so this is not a
                    // layer-height artefact. The surface passes through itself, `volume`
                    // integrates the surface and counts the overlap twice, and the slicer counts
                    // the region once. See `dev_docs/POLYTOPE_ALGEBRA.md`, "Self-intersection
                    // diagnostics", which lists exactly this gap as open.
                    truth(
                        "kkC ceres: the layer stack disagrees with the signed volume by more than 1%",
                        abs(finest - 1.0) > 0.01,
                    )
                    // The closing of the argument. If the gap is overlap counted twice, then the
                    // finest Riemann sum is `1 - 2 * overlap` — and the two measurements share no
                    // code: one is an area integral under POSITIVE, the other the POSITIVE /
                    // EVEN_ODD difference. They agree to five decimal places.
                    near(
                        "kkC ceres: the volume gap is exactly twice the overlap the fill rules found",
                        finest,
                        1.0 - 2.0 * overlap,
                        1e-4,
                    )
                    truth(
                        "kkC ceres: and the tangency spread is finally small",
                        solved.realization.polyhedron.canonicalDiagnostics().tangencyRadiusSpread < 1e-4,
                    )
                    rawArm?.let { left -> compare(left, it, "kkC") }
                }
            }
            Unit
        }
    }

    // ==============================================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        println(ManifoldLibrary.describe())
        if (!ManifoldLibrary.isAvailable) {
            println()
            println("Manifold is not installed here, so there is nothing to slice.")
            println("layer contour study passed (skipped — library absent)")
            return
        }
        println("Ceres realizer available: ${ceresRealizationAvailable()}")

        degenerateSlices()
        study("gC")
        study("gD")
        theCounterExample()

        println()
        if (failures.isEmpty()) {
            println("layer contour study passed ($checks checks)")
        } else {
            println("layer contour study FAILED (${failures.size} of $checks checks)")
            failures.forEach { println("  x $it") }
            kotlin.system.exitProcess(1)
        }
    }

    // ---- the tiny assertion vocabulary, the same shape as ManifoldSmokeTest's --------------------

    private fun section(title: String) {
        println()
        println("  $title")
    }

    private fun exact(what: String, actual: Double, expected: Double) {
        checks++
        if (abs(actual - expected) > 1e-12 * maxOf(1.0, abs(expected))) {
            failures += "$what: expected exactly $expected, got $actual"
            println("    x $what: $actual != $expected")
        } else {
            println("    . $what = $actual")
        }
    }

    private fun near(what: String, actual: Double, expected: Double, tolerance: Double) {
        checks++
        val scale = maxOf(1.0, abs(expected))
        if (abs(actual - expected) > tolerance * scale) {
            failures += "$what: expected $expected +/- ${tolerance * scale}, got $actual"
            println("    x $what: $actual vs $expected")
        } else {
            println("    . $what = ${"%.6f".format(actual)}")
        }
    }

    private fun <T> equal(what: String, actual: T, expected: T) {
        checks++
        if (actual != expected) {
            failures += "$what: expected $expected, got $actual"
            println("    x $what: $actual != $expected")
        } else {
            println("    . $what = $actual")
        }
    }

    private fun truth(what: String, condition: Boolean) {
        checks++
        if (!condition) {
            failures += what
            println("    x $what")
        } else {
            println("    . $what")
        }
    }
}
