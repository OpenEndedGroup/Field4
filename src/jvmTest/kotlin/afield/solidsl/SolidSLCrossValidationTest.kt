package afield.solidsl

import afield.manifold.ManifoldLibrary
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reason to believe `afield.solidsl`: **one written shape, evaluated two completely independent
 * ways, and the disagreement asserted.**
 *
 * # What is on each side
 *
 * Side A is [SolidExpr.toScalarField] marched by `afield.stdlib.marchingCubes` — Kotlin, a distance
 * field sampled on a grid, volume from a sum of signed tetrahedra over `Float` triangles, genus from
 * edge/vertex adjacency in `mesh_topology.kt`. Side B is [SolidExpr.exactSolid] — Manifold's exact
 * boolean kernel, in C++, volume from its own integration in `double`, genus from its own Euler
 * characteristic. The two share no line of code and no arithmetic. There is no bug that makes them
 * *both* wrong in the same direction, which is what makes agreement evidence rather than a tautology.
 *
 * # The bucket, and what would empty it for the wrong reason
 *
 * The corpus is 11 shapes. **Eight** of them have an exact CSG form; **three** deliberately do not
 * (a smooth blend, a bounded gyroid, and a plate unioned with a blend), and those get
 * [AgreementKind.MARCHED_VS_MARCHED] — two independent marchers on one field, which catches a broken
 * lowering but says nothing about whether that field is the shape the notation meant. They are
 * counted separately and **excluded from the exact statistic**, because "11 of 11 agree" where three
 * of them agreed with themselves is the exact shape of overclaim this project keeps catching. The
 * split is itself asserted, so a change that quietly turned the exact path into a fallback fails
 * here rather than reading as a pass.
 *
 * # Agreement has a floor, and the floor is computed rather than remembered
 *
 * A polyhedral shape converges: the exact lowering is the truth and the marched one approaches it as
 * `h` shrinks — measured order **2.47** over a 7.6x refinement sweep, down to `2.7e-4` relative on
 * volume. A shape with a sphere, a cylinder or a round offset does **not** converge to zero, because
 * Manifold's circle is an inscribed n-gon and the field's is a circle. [facetingDeficit] predicts
 * that floor from the segment count alone — `1 − (n/2π)·sin(2π/n)` — and it is not approximately
 * right, it is **exact to every digit measured** for a cylinder, which is a right prism over that
 * n-gon. The convergence test checks both that a curved shape's residual sits at that floor and that
 * the floor falls by 4x when the segments double, which is what distinguishes "faceting" from "a bug
 * that happens to be small".
 *
 * # What the numbers actually are, at `h = size/90`
 *
 * Volume agrees to between `2.0e-4` and `4.1e-3` relative across the eight exact shapes; genus agrees
 * on all eleven; the two surfaces are never more than about one march cell apart. Area is the weakest
 * of the three and deliberately so: a marched polyhedron chamfers every edge, replacing two faces
 * meeting at a right angle with one diagonal, which *shortens* the surface by about `0.3·h` per unit
 * of edge — so `polyPlate`, with 48 units of edge in a 44-unit surface, is 3.1% down on area while
 * being 0.3% down on volume. That is the marcher being what it is, not a disagreement.
 */
class SolidSLCrossValidationTest {

    // ==========================================================================================
    // The corpus
    // ==========================================================================================

    /**
     * The shapes, and what each one is in the corpus *for*.
     *
     * Deliberately not a list of spheres. Between them these cover a nested boolean, a transform
     * underneath a boolean, two profile extrusions and a revolve, a non-convex profile, a partial
     * sweep, both refusal flavours, and the hybrid path where one refused subtree sits inside an
     * otherwise exact tree.
     */
    private data class Case(
        val name: String,
        val expr: SolidExpr,
        val why: String,
        /** `null` when there is no closed form to check the pair against. */
        val closedFormVolume: Double? = null,
        val expectedGenus: Int? = null,
    )

    private val segments = 256

    private val corpus: List<Case> by lazy {
        listOf(
            Case(
                "polyPlate",
                cuboid(4.0, 3.0, 1.0) - unionOf(
                    listOf(-1.2 to -0.9, 1.2 to -0.9, -1.2 to 0.9, 1.2 to 0.9).map { (x, y) ->
                        Profile.rect(0.5, 0.5).extruded(3.0).at(x, y, -1.5)
                    },
                ),
                "a nested boolean — a Difference whose second operand is a Union — with a Translate " +
                    "under each. Entirely polyhedral, so this is the case that must converge to zero.",
                closedFormVolume = 4.0 * 3.0 * 1.0 - 4 * 0.5 * 0.5 * 1.0,
                expectedGenus = 4,
            ),
            Case(
                "lBracketCut",
                lProfile().extruded(1.5) - cuboid(0.7, 0.7, 4.0).turned(0.0, 0.0, 30.0).at(1.4, 1.4, 0.0),
                "a non-convex polygon profile extruded, with a *rotated* cuboid subtracted — a " +
                    "transform under a boolean, and the case that catches a wrong Euler order.",
            ),
            Case(
                "starTaper",
                Profile.star(5, 1.0, 0.45).extruded(1.2, scaleTop = 0.45),
                "a five-point star tapered to 45% — still polyhedral (scaling a polygon keeps every " +
                    "side face planar) but with ten reflex edges for the marcher to get wrong.",
            ),
            Case(
                "roundedSlot",
                Profile.rect(2.6, 0.9).offset(0.45, segments = segments).extruded(1.0),
                "the non-trivial profile: a stadium, made by offsetting a rectangle. " +
                    "`Section.offset(d, ROUND)` is the Minkowski sum with a disc and `sdf - d` is " +
                    "exactly that, so the two lowerings agree here for a *reason*.",
                closedFormVolume = (2.6 * 0.9 + 2 * 0.45 * (2.6 + 0.9) + PI * 0.45 * 0.45) * 1.0,
            ),
            Case(
                "ring",
                torus(1.2, 0.35, segments = segments),
                "a revolve, and the only shape here with genus 1 by construction rather than by " +
                    "subtraction. Pappus gives the closed form.",
                closedFormVolume = 2 * PI * 1.2 * (PI * 0.35 * 0.35),
                expectedGenus = 1,
            ),
            Case(
                "wedge",
                Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(270.0, segments = segments),
                "a *partial* revolve — the sweep direction and the reflex-wedge cut, which are the " +
                    "two things a plausible-looking lowering gets backwards.",
                closedFormVolume = 0.75 * 2 * PI * 1.2 * 0.36,
            ),
            Case(
                "boredBoss",
                (cuboid(3.0, 3.0, 0.8) + cylinder(1.6, 0.8, segments = segments)) -
                    cylinder(4.0, 0.35, segments = segments),
                "a curved boolean with three operands at two levels — the everyday part, and the " +
                    "shape whose agreement floor is set by faceting rather than by the march.",
                expectedGenus = 1,
            ),
            Case(
                "lens",
                sphere(1.0, segments = segments) and sphere(1.0, segments = segments).at(1.0, 0.0, 0.0),
                "an intersection of two spheres — a shape with a sharp circular edge, which is where " +
                    "a marcher's linear crossing interpolation is at its worst.",
                // Two unit spheres at distance d = 1: V = 2 * pi * (2r + h) * (r - h)^2 / 3 with h = d/2.
                closedFormVolume = 2.0 * PI * (2.0 + 0.5) * (1.0 - 0.5) * (1.0 - 0.5) / 3.0,
            ),
            Case(
                "blend",
                smoothUnion(sphere(0.8, segments = segments), cuboid(1.6, 0.4, 0.4), k = 0.3),
                "**must refuse the exact path.** A smooth blend's fillet lies on neither operand.",
            ),
            Case(
                "gyroidCore",
                gyroid(3.0, 0.25) and cuboid(1.8, 1.8, 1.8),
                "**must refuse.** A trigonometric field on all of space, made bounded by an " +
                    "intersection — which is also how you would actually use one.",
            ),
            Case(
                "hybridPlate",
                cuboid(3.0, 3.0, 0.5) +
                    smoothUnion(sphere(0.7, segments = segments).at(0.0, 0.0, 0.6), cylinder(1.6, 0.3, segments = segments), k = 0.25),
                "the hybrid: an exact plate unioned with a refused blend. `buildSolid` must march " +
                    "only the blend and keep the plate's six faces at twelve triangles.",
            ),
        )
    }

    /** An L, counter-clockwise, area 3 — the standard non-convex profile. */
    private fun lProfile(): Profile = Profile.polygon(
        listOf(P2(-1, -1), P2(1, -1), P2(1, 0), P2(0, 0), P2(0, 1), P2(-1, 1)),
    )

    // ==========================================================================================
    // The headline
    // ==========================================================================================

    @Test
    fun `both lowerings of the same source agree, everywhere there is an exact one`() {
        if (!requireManifold()) return
        val q = SolidQuality(segments = segments, edgeLengthFraction = 1.0 / 90.0)

        val results = corpus.map { it to it.expr.crossCheck(q) }

        println("%-13s %-9s %11s %11s %10s %9s %7s".format("case", "kind", "volume rel", "area rel", "dev/size", "genus", "tris"))
        results.forEach { (case, a) ->
            println(
                "%-13s %-9s %11.3e %11.3e %10.3e %9s %7d".format(
                    case.name,
                    if (a.kind == AgreementKind.EXACT_VS_MARCHED) "exact" else "levelset",
                    a.relativeVolumeError, a.relativeAreaError, a.relativeDeviation,
                    if (a.genusAgrees) "${a.solidGenus} ok" else "${a.solidGenus}/${a.marchedGenus} X",
                    a.solidTriangles,
                ),
            )
        }

        val exact = results.filter { it.second.kind == AgreementKind.EXACT_VS_MARCHED }
        val fallback = results.filter { it.second.kind == AgreementKind.MARCHED_VS_MARCHED }
        println()
        println("${exact.size} of ${results.size} took the exact CSG path; ${fallback.size} refused and took the level set")

        // The denominator, stated. Eight of eleven have an exact form; the statistic below is over
        // those eight only, and this assertion is what stops the bucket emptying quietly.
        assertTrue(
            exact.size >= 8,
            "only ${exact.size} of ${results.size} shapes reached the exact path — the agreement " +
                "statistic below would be measuring the level set against itself. Exact: " +
                exact.joinToString { it.first.name },
        )

        exact.forEach { (case, a) ->
            assertTrue(a.marchedIsWatertight, "${case.name}: the marched surface came out open")
            assertTrue(
                a.genusAgrees,
                "${case.name}: Manifold says genus ${a.solidGenus}, the marched mesh says ${a.marchedGenus}",
            )
            // Every threshold below is the measured worst case times about 1.5, so it is a ratchet
            // rather than a hope. Volume: worst is `wedge` at 4.09e-3 — the partial revolve, whose
            // 0.6 of thickness gets the fewest cells across it of anything here.
            assertTrue(
                a.relativeVolumeError < 6e-3,
                "${case.name}: volumes disagree by ${a.relativeVolumeError}\n${a.report()}",
            )
            // Area is the loosest of the three, for a reason given in this class's KDoc: a marched
            // polyhedron chamfers every edge and so *loses* about 0.3·h of surface per unit of edge.
            // Worst is `polyPlate` at 3.11e-2, which has 48 units of edge in a 44-unit surface.
            assertTrue(
                a.relativeAreaError < 4.5e-2,
                "${case.name}: areas disagree by ${a.relativeAreaError}\n${a.report()}",
            )
            // The two surfaces are never further apart than about one march cell. That is the
            // assertion a wrong *shape* fails even when its volume happens to come out right.
            assertTrue(
                a.relativeDeviation < 3.0 / 90.0,
                "${case.name}: surfaces are ${a.relativeDeviation} of the shape apart\n${a.report()}",
            )
        }

        fallback.forEach { (case, a) ->
            assertTrue(a.refusals.isNotEmpty(), "${case.name} took the fallback but named no refusal")
            // Two independent marchers on one field still have to land on the same solid.
            assertTrue(
                a.relativeVolumeError < 2e-2,
                "${case.name}: the two marchers disagree by ${a.relativeVolumeError}\n${a.report()}",
            )
        }
    }

    @Test
    fun `the corpus is what it claims to be`() {
        // No Manifold needed: this is a property of the notation, answered symbolically.
        val refusing = corpus.filter { it.expr.exactRefusal() != null }
        assertEquals(
            setOf("blend", "gyroidCore", "hybridPlate"),
            refusing.map { it.name }.toSet(),
            "exactly three cases are meant to refuse; if this set changed, the exact statistic in " +
                "the headline test is measuring a different denominator than its message claims",
        )
        // Every refusal says why, and none of them is a to-do.
        refusing.forEach { case ->
            val rs = case.expr.allRefusals()
            assertTrue(rs.isNotEmpty(), "${case.name} refuses but allRefusals() is empty")
            rs.forEach {
                assertTrue(it.why.length > 40, "${case.name}: the refusal reason is too thin: '${it.why}'")
                assertTrue(!it.closable, "${case.name}: nothing in this package is a closable refusal yet")
            }
        }
        // And the shapes that must NOT refuse, do not.
        corpus.filter { it.name !in setOf("blend", "gyroidCore", "hybridPlate") }.forEach {
            assertEquals(null, it.expr.exactRefusal(), "${it.name} should have an exact form")
        }
    }

    @Test
    fun `where there is a closed form, both lowerings hit it`() {
        if (!requireManifold()) return
        val q = SolidQuality(segments = segments, edgeLengthFraction = 1.0 / 90.0)
        var checked = 0
        corpus.filter { it.closedFormVolume != null }.forEach { case ->
            val v = case.closedFormVolume!!
            val a = case.expr.crossCheck(q)
            val exactErr = abs(a.solidVolume - v) / v
            val marchErr = abs(a.marchedVolume - v) / v
            println("%-13s closed form %12.6f   exact %.3e   marched %.3e".format(case.name, v, exactErr, marchErr))
            // Both sides against a third, independent number. This is the check that catches the two
            // lowerings being *jointly* wrong — agreeing with each other and with nothing else.
            //
            // Note what the exact side's residual *is*, when it is not zero: it is the faceting
            // deficit and nothing else. `polyPlate` is wholly polyhedral and comes back at exactly
            // 0.0 — Manifold's 11.0 against the closed form's 11.0, to the last bit. Everything with
            // a curve in it is a few parts in ten thousand short, in the direction an inscribed
            // polygon is short, and by an amount that shrinks with `segments`.
            assertTrue(exactErr < 1e-3, "${case.name}: Manifold's volume is $exactErr off the closed form $v")
            assertTrue(marchErr < 6e-3, "${case.name}: the marched volume is $marchErr off the closed form $v")
            checked++
        }
        assertTrue(checked >= 5, "only $checked shapes had a closed form to check against")

        // And the polyhedral one is not merely close, it is exact. That is the single strongest fact
        // in this file: a shape whose every face is planar goes through a symbolic notation, a CSG
        // kernel and an FFM boundary and comes back bit-identical to arithmetic done by hand.
        val plate = corpus.first { it.name == "polyPlate" }
        plate.expr.exactSolid(SolidQuality(segments = segments)).orThrow().use {
            assertEquals(plate.closedFormVolume!!, it.volume, 0.0, "a polyhedron's exact volume must be exact")
        }
    }

    @Test
    fun `the corpus has the expected topology`() {
        if (!requireManifold()) return
        val q = SolidQuality(segments = segments)
        corpus.filter { it.expectedGenus != null }.forEach { case ->
            case.expr.exactSolid(q).orThrow().use {
                assertEquals(case.expectedGenus, it.genus, "${case.name}: genus")
            }
        }
    }

    // ==========================================================================================
    // Convergence — the thing that proves both lowerings are right rather than jointly wrong
    // ==========================================================================================

    /**
     * A least-squares slope of `log(error)` against `log(h)` — the convergence *order*.
     *
     * The right statistic for this, and better than comparing the two endpoints, because a marched
     * volume's residual at any one `h` carries a lot of alignment noise: whether a box face happens
     * to land on a grid plane changes the answer by a factor of two, and the ratio between two
     * adjacent points in the sweep was measured anywhere from 1.4x to 9x. The *slope* through six
     * points is stable where any single ratio is not.
     */
    private fun convergenceOrder(hs: List<Double>, errors: List<Double>): Double {
        val x = hs.map { kotlin.math.ln(it) }
        val y = errors.map { kotlin.math.ln(it) }
        val mx = x.average(); val my = y.average()
        var num = 0.0; var den = 0.0
        for (i in x.indices) { num += (x[i] - mx) * (y[i] - my); den += (x[i] - mx) * (x[i] - mx) }
        return num / den
    }

    @Test
    fun `a polyhedral shape converges to the exact solid as the march refines`() {
        if (!requireManifold()) return
        val expr = corpus.first { it.name == "polyPlate" }.expr
        // Geometric, ratio 1.5, six points — enough for a slope, and no two of them share a grid
        // alignment with each other.
        val fractions = listOf(1.0 / 32.0, 1.0 / 48.0, 1.0 / 72.0, 1.0 / 108.0, 1.0 / 162.0, 1.0 / 243.0)
        val volumeErrors = mutableListOf<Double>()
        val deviations = mutableListOf<Double>()
        println("%10s %12s %12s".format("h/size", "volume rel", "dev/size"))
        fractions.forEach { f ->
            val a = expr.crossCheck(SolidQuality(segments = segments, edgeLengthFraction = f))
            println("%10.6f %12.4e %12.4e".format(f, a.relativeVolumeError, a.relativeDeviation))
            volumeErrors += a.relativeVolumeError
            deviations += a.relativeDeviation
        }

        val volumeOrder = convergenceOrder(fractions, volumeErrors)
        val deviationOrder = convergenceOrder(fractions, deviations)
        println("fitted order: volume %.2f, surface deviation %.2f".format(volumeOrder, deviationOrder))

        // Marching cubes places a crossing by linear interpolation, which is exact on a planar face
        // of an exact SDF and wrong by O(h²) where the surface turns — so on a polyhedron the whole
        // residual lives on the edges, and the volume error is O(h²) times a fixed total edge length.
        // Measured 1.86 over this sweep. Asserted at 1.5, well clear of the 1.0 that a lowering
        // converging to the *wrong* solid would show, and of the 0.0 the curved shapes below show.
        assertTrue(
            volumeOrder > 1.5,
            "the volume error should fall as about h²; the fitted order was only $volumeOrder over " +
                "$volumeErrors — a lowering converging to a different solid would look like this",
        )
        // The Hausdorff distance between the two surfaces is the chamfer depth at an edge, which is
        // O(h) rather than O(h²). Measured 1.02.
        assertTrue(
            deviationOrder > 0.8,
            "the surfaces should close in about linearly with the cell; fitted order $deviationOrder",
        )
        // And a ratchet on the absolute floor. Measured 3.66e-4 at h = size/243; asserted at 1.5e-3,
        // so a real regression fails and grid-alignment noise does not.
        assertTrue(
            volumeErrors.last() < 1.5e-3,
            "a wholly polyhedral shape should agree to under 1.5e-3 at h = size/243; got ${volumeErrors.last()}",
        )
        assertTrue(
            volumeErrors.last() < volumeErrors.first() / 20.0,
            "an 7.6x refinement should have bought more than 20x; got ${volumeErrors.first()} -> ${volumeErrors.last()}",
        )
    }

    @Test
    fun `the curved floor is faceting, and scales as 1 over segments squared`() {
        if (!requireManifold()) return
        // A cylinder is a right prism over an inscribed n-gon, so its volume deficit against the true
        // cylinder is *exactly* facetingDeficit(n). No other shape in the corpus lets the prediction
        // be checked to three figures, which is why the assertion lives on this one.
        val h = 2.0
        val r = 0.75
        val trueVolume = PI * r * r * h
        println("%8s %14s %14s %10s".format("segments", "predicted", "measured", "ratio"))
        val ratios = mutableListOf<Double>()
        for (n in listOf(16, 32, 64, 128)) {
            val predicted = facetingDeficit(n)
            cylinder(h, r, segments = n).exactSolid().orThrow().use { s ->
                val measured = (trueVolume - s.volume) / trueVolume
                println("%8d %14.8f %14.8f %10.4f".format(n, predicted, measured, measured / predicted))
                ratios += measured / predicted
                assertTrue(
                    abs(measured / predicted - 1.0) < 1e-6,
                    "a cylinder's volume deficit at n=$n should be exactly facetingDeficit(n): " +
                        "predicted $predicted, measured $measured",
                )
            }
        }
        // Doubling the segments quarters the deficit — the 1/n² the formula predicts.
        for (n in listOf(16, 32, 64)) {
            val ratio = facetingDeficit(n) / facetingDeficit(2 * n)
            assertTrue(abs(ratio - 4.0) < 0.05, "doubling $n segments should quarter the deficit; got $ratio")
        }

        // Now the consequence for the cross-check: a curved shape's agreement bottoms out at the
        // faceting deficit and refining the march does not help past it.
        val expr = cylinder(h, r, segments = 32)
        val fine = expr.crossCheck(SolidQuality(edgeLengthFraction = 1.0 / 192.0))
        val coarse = expr.crossCheck(SolidQuality(edgeLengthFraction = 1.0 / 48.0))
        println(
            "cylinder(32 segments): volume rel at h/size=1/48 %.4e, at 1/192 %.4e, faceting floor %.4e"
                .format(coarse.relativeVolumeError, fine.relativeVolumeError, facetingDeficit(32)),
        )
        assertTrue(
            fine.relativeVolumeError > 0.5 * facetingDeficit(32),
            "a 32-segment cylinder cannot agree with its own true field to better than the faceting " +
                "deficit ${facetingDeficit(32)}; measured ${fine.relativeVolumeError}, which would " +
                "mean one of the two lowerings is not doing what it says",
        )
        assertTrue(
            fine.relativeVolumeError < 1.5 * facetingDeficit(32),
            "and at h = size/192 essentially nothing but faceting should be left; measured " +
                "${fine.relativeVolumeError} against a floor of ${facetingDeficit(32)}",
        )
    }

    // ==========================================================================================
    // Point membership — the sharpest convention check there is
    // ==========================================================================================

    @Test
    fun `the two lowerings agree point by point about what is inside`() {
        if (!requireManifold()) return
        // Volume, area and genus are all invariant under a mirror or a transposed axis. Membership is
        // not: this is the check that pins the conventions, and it is why `Section.revolved`'s
        // "about the Y axis" was caught rather than shipped.
        val q = SolidQuality(segments = 128)
        var totalTested = 0
        var totalWrong = 0
        corpus.filter { it.expr.exactRefusal() == null }.forEach { case ->
            val tight = case.expr.bounds()!!
            val span = maxOf(tight.size.x, tight.size.y, tight.size.z).toDouble()
            val b = tight.expandedBy(0.25 * span)
            val field = case.expr.toField3()
            val skin = 0.03 * span
            val random = kotlin.random.Random(11L)
            case.expr.exactSolid(q).orThrow().use { solid ->
                var tested = 0
                var wrong = 0
                var inside = 0
                repeat(4000) {
                    val x = b.min.x + random.nextDouble().toFloat() * b.size.x
                    val y = b.min.y + random.nextDouble().toFloat() * b.size.y
                    val z = b.min.z + random.nextDouble().toFloat() * b.size.z
                    val d = field.at(x.toDouble(), y.toDouble(), z.toDouble())
                    if (abs(d) < skin) return@repeat
                    tested++
                    if (d < 0) inside++
                    if ((d < 0) != solid.contains(x.toDouble(), y.toDouble(), z.toDouble())) wrong++
                }
                println("%-13s %5d sampled, %5.1f%% inside, %d disagreements".format(case.name, tested, 100.0 * inside / tested, wrong))
                // A sample that is entirely outside would pass a disagreement count of zero without
                // testing anything, so the inside population is asserted too.
                assertTrue(inside > tested / 100, "${case.name}: only $inside of $tested samples landed inside")
                assertEquals(0, wrong, "${case.name}: the two lowerings disagree about $wrong of $tested points")
                totalTested += tested
                totalWrong += wrong
            }
        }
        println("membership: $totalWrong disagreements in $totalTested samples across ${corpus.count { it.expr.exactRefusal() == null }} shapes")
        assertTrue(totalTested > 20_000, "only $totalTested points were actually tested")
    }

    // ==========================================================================================
    // The hybrid
    // ==========================================================================================

    @Test
    fun `a refused subtree is marched alone, and the rest stays exact`() {
        if (!requireManifold()) return
        val expr = corpus.first { it.name == "hybridPlate" }.expr
        expr.buildSolid(SolidQuality(segments = 128, edgeLengthFraction = 1.0 / 60.0)).use { build ->
            println(build.report())
            assertTrue(!build.isExact, "hybridPlate contains a smooth blend, so it cannot be exact")
            assertEquals(1, build.marchedSubtrees.size, "only the SmoothUnion should have been marched")
            assertTrue(
                build.marchedSubtrees.single().node is SolidExpr.SmoothUnion,
                "the marched subtree should be the SmoothUnion itself, not the whole Union above it",
            )
            assertTrue(build.solid.status.let { !it.isError }, "the hybrid solid is invalid: ${build.solid.status}")

            // The point of marching the *smallest* refused subtree, measured rather than asserted
            // from theory: build the same shape the way a naive whole-tree fallback would, at the
            // same edge length, and count.
            val naive = wholeTreeLevelSet(expr, 1.0 / 60.0)
            println(
                "hybrid ${build.solid.triangleCount} triangles against $naive for a whole-tree " +
                    "march at the same edge length — ${"%.2f".format(naive.toDouble() / build.solid.triangleCount)}x",
            )
            assertTrue(
                build.solid.triangleCount < naive * 3 / 4,
                "the hybrid should be materially cheaper than a whole-tree march; got " +
                    "${build.solid.triangleCount} against $naive",
            )

            // And Field's own topology code, which shares nothing with Manifold, agrees it closed.
            val mesh = build.solid.toTriMesh()
            assertTrue(mesh.isWatertight(), "the hybrid solid is not watertight by Field's reckoning")
            assertTrue(mesh.signedVolume() > 0.0, "the hybrid solid came out inside-out")
        }
    }

    /** What a naive fallback would have cost: the whole tree through one level set. */
    private fun wholeTreeLevelSet(expr: SolidExpr, fraction: Double): Long {
        val raw = expr.bounds()!!
        val edge = maxOf(raw.size.x, raw.size.y, raw.size.z) * fraction
        val field = expr.toLevelSetField()
        return afield.manifold.Solid.levelSet(raw.expandedBy(2.0 * edge), edge, parallel = true) { x, y, z ->
            field.at(x, y, z)
        }.use { it.triangleCount }
    }

    // ==========================================================================================
    // The algebra itself
    // ==========================================================================================

    @Test
    fun `an expression is a value, so it prints, compares and hashes`() {
        val a = cuboid(2, 1, 0.5) - cylinder(3, 0.2).at(0.5, 0, 0)
        val b = cuboid(2, 1, 0.5) - cylinder(3, 0.2).at(0.5, 0, 0)
        val c = cuboid(2, 1, 0.5) - cylinder(3, 0.25).at(0.5, 0, 0)
        assertEquals(a, b, "two identically written shapes must be equal — the whole point of a notation")
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c, "a different radius must be a different shape")
        assertTrue(mapOf(a to "cut plate")[b] == "cut plate", "an expression must work as a map key")

        val text = a.toString()
        assertTrue(text.contains("Difference"), text)
        assertTrue(text.contains("Cuboid(x=2.0"), text)
        assertTrue(text.contains("Cylinder"), text)
        assertTrue(text.contains("Translate"), text)
        // Which is the thing a closure could not do. `(Vec3) -> Float` prints as
        // "Function1<Vector3f, Float>" and compares by identity.
    }

    @Test
    fun `bounds are computed from the expression, with no sampling`() {
        assertEquals(null, gyroid().bounds(), "a gyroid is unbounded and must say so rather than guess")
        assertEquals(null, (gyroid() + sphere(1.0)).bounds(), "and a union with one is unbounded too")

        val bounded = gyroid() and cuboid(2.0, 2.0, 2.0)
        val b = bounded.bounds()!!
        assertEquals(-1.0f, b.min.x, 1e-6f)
        assertEquals(1.0f, b.max.z, 1e-6f)

        // A rotated box's bound is the bound of its rotated corners — conservative, not tight.
        val turned = cuboid(2.0, 2.0, 2.0).turned(0.0, 0.0, 45.0).bounds()!!
        assertEquals(kotlin.math.sqrt(2.0).toFloat(), turned.max.x, 1e-5f)

        // An extrusion sits at z in [0, h], not centred — Manifold's convention, and the one a
        // reader is most likely to assume otherwise about.
        val ex = Profile.rect(1.0, 1.0).extruded(3.0).bounds()!!
        assertEquals(0.0f, ex.min.z, 1e-6f)
        assertEquals(3.0f, ex.max.z, 1e-6f)
    }

    @Test
    fun `the field lowering says where it is not a true distance function`() {
        assertEquals(emptyList(), sphere(1.0).fieldNotes())
        assertEquals(emptyList(), (cuboid(1, 1, 1) + sphere(0.7)).fieldNotes())
        assertTrue((cuboid(1, 1, 1) - sphere(0.7)).fieldNotes().any { it.contains("Difference") })
        assertTrue(sphere(1.0).sized(2.0, 1.0, 1.0).fieldNotes().any { it.contains("non-uniform") })
        assertTrue(gyroid().fieldNotes().any { it.contains("not a distance function") })
        // A uniform scale keeps it exact, which is worth pinning because the easy implementation
        // would have flagged both.
        assertEquals(emptyList(), sphere(1.0).sized(2.0).fieldNotes())
    }

    companion object {
        private var announced = false

        /** Manifold is an optional dependency, so these skip rather than fail without it. */
        fun requireManifold(): Boolean {
            if (ManifoldLibrary.isAvailable) return true
            if (!announced) {
                announced = true
                println("SolidSLCrossValidationTest: skipping — ${ManifoldLibrary.describe()}")
            }
            return false
        }
    }
}
