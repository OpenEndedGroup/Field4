package afield.solidsl

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.bounds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Manifold actually does for the handful of conventions `afield.solidsl` has to match exactly — rotation order, twist direction, the sense of a
 * partial revolve, where an extrusion sits in z.
 *
 * These are the places where a lowering can be plausibly, silently wrong: a mirrored solid still has
 * the right volume and the right genus and renders perfectly. So they are determined by running the
 * library rather than by reading its header, and the answers are then pinned by real assertions —
 * here for the revolve axis, and in [SolidSLCrossValidationTest] for the rest.
 *
 * This is also where a defect in code outside this package was found and is documented: see
 * `Section revolved turns about Z, and the KDoc that says Y is wrong`.
 */
class SolidSLConventionProbe {

    @Test
    fun `report what Manifold's conventions actually are`() {
        if (!ManifoldLibrary.isAvailable) {
            println("SolidSLConventionProbe: skipping — ${ManifoldLibrary.describe()}")
            return
        }
        println("Manifold: ${ManifoldLibrary.describe()}")
        println("default circular segments for r=1: ${Solid.circularSegments(1.0)}")

        // ---- 1. does the field lowering agree with Manifold about which points are inside? -------
        // This one check subsumes every convention question below: a mirrored, transposed or
        // counter-rotated lowering keeps the same volume and genus and fails this immediately.
        val cases = listOf<Pair<String, SolidExpr>>(
            "cuboid" to cuboid(2.0, 1.0, 0.5),
            "sphere" to sphere(1.0, segments = 128),
            "cylinder Z" to cylinder(2.0, 0.6, segments = 128),
            "cone Z" to cone(2.0, 0.8, segments = 128),
            "frustum Z" to cylinder(2.0, 0.8, 0.3, segments = 128),
            "rotate 30,0,0" to cuboid(2.0, 1.0, 0.5).turned(30.0, 0.0, 0.0),
            "rotate 0,40,0" to cuboid(2.0, 1.0, 0.5).turned(0.0, 40.0, 0.0),
            "rotate 0,0,50" to cuboid(2.0, 1.0, 0.5).turned(0.0, 0.0, 50.0),
            "rotate 30,40,50" to cuboid(2.0, 1.0, 0.5).turned(30.0, 40.0, 50.0),
            "mirror x" to cuboid(2.0, 1.0, 0.5).at(0.7, 0.0, 0.0).mirrored(1.0, 0.0, 0.0),
            "mirror 1,1,0" to cuboid(2.0, 1.0, 0.5).at(0.7, 0.3, 0.0).mirrored(1.0, 1.0, 0.0),
            "scale 2,1,0.5" to sphere(1.0, segments = 128).sized(2.0, 1.0, 0.5),
            "extrude rect" to Profile.rect(2.0, 1.0).extruded(1.5),
            "extrude L" to lProfile().extruded(1.5),
            "extrude star" to Profile.star(5, 1.0, 0.45).extruded(0.8),
            "extrude offset rect" to Profile.rect(2.0, 1.0).offset(0.35, segments = 256).extruded(1.0),
            "extrude twist +90" to Profile.rect(2.0, 0.6).extruded(2.0, twistDegrees = 90.0, slices = 64),
            "extrude taper 0.4" to Profile.rect(2.0, 1.0).extruded(2.0, scaleTop = 0.4, slices = 32),
            "revolve 360" to torus(1.0, 0.35, segments = 128),
            "revolve 90" to Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(90.0, segments = 128),
            "revolve 270" to Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(270.0, segments = 128),
        )

        println()
        println("%-22s %8s %8s  %s".format("case", "inside%", "agree%", "note"))
        for ((name, expr) in cases) {
            val r = runCatching { membershipCheck(expr) }
            if (r.isFailure) {
                println("%-22s %8s %8s  %s".format(name, "-", "-", "threw ${r.exceptionOrNull()}"))
                continue
            }
            val m = r.getOrThrow()
            println(
                "%-22s %7.1f%% %7.2f%%  %s".format(
                    name, 100.0 * m.insideFraction, 100.0 * m.agreementFraction,
                    if (m.agreementFraction > 0.999) "" else "MISMATCH (${m.disagreements} of ${m.tested})",
                ),
            )
        }
    }

    /**
     * **Ground truth for the axis `Section.revolved` turns about — asserted, not printed.**
     *
     * `Section.revolved`'s KDoc (`Section.kt:191`) says "Revolve about the Y axis". It is wrong;
     * Manifold revolves about **Z**, the profile's `x` becoming the radius in the XY plane and the
     * profile's `y` becoming `z`. This settles it three independent ways so the finding can be acted
     * on without re-deriving it.
     *
     * The profile is deliberately **asymmetric in y** — a right triangle at `x ∈ [1.0, 1.6]`,
     * `y ∈ [0, 0.8]`. A symmetric one (the square the existing tests use) cannot discriminate at all,
     * because a symmetric torus looks the same under either reading.
     *
     * 1. **Bounds.** One axis of the result must carry the profile's asymmetric `[0, 0.8]` and the
     *    other two the swept `[-1.6, 1.6]`. Whichever axis holds `[0, 0.8]` *is* the axis. There is
     *    nothing to interpret.
     * 2. **Membership.** A point at radius 1.3 just above the profile's base is inside under one
     *    reading and outside under the other.
     * 3. **Pappus.** `2πRA` is axis-independent, so it confirms the solid is the right *shape* and
     *    that only the axis was ever in question.
     *
     * # It is the KDoc that is wrong, not the binding
     *
     * `manifoldc.h:205` declares
     * `manifold_revolve(void* mem, ManifoldPolygons* cs, int circular_segments, double revolve_degrees)`
     * — **there is no axis parameter**, and `Section.revolved` passes exactly those three arguments.
     * The wrapper has no axis to get wrong. The axis is Manifold's own convention and the prose
     * misreports it.
     *
     * # Why it survived the test suite
     *
     * Because almost nothing in it discriminates. The torus checks are genus (1 either way), Pappus
     * volume (axis-independent), and an equatorial slice contour count — and a torus about Y sliced
     * at `z = 0` gives *two separate squares*, which is also two contours. The one committed
     * assertion that does discriminate is the projected-shadow area, and the second half of this test
     * shows by how much.
     */
    @Test
    fun `Section revolved turns about Z, and the KDoc that says Y is wrong`() {
        if (!ManifoldLibrary.isAvailable) return
        // Lifted clear of the y = 0 plane on purpose. With its base on the axis plane, the two probe
        // points below land exactly ON a face of the solid under one of the two readings, and
        // `contains` at a boundary is a coin toss rather than an answer.
        val triangle = Profile.polygon(listOf(P2(1.0, 0.2), P2(1.6, 0.2), P2(1.6, 1.0)))
        triangle.revolved(segments = 256).exactSolid().orThrow().use {
            val b = it.bounds
            println("asymmetric profile revolved -> bounds $b, volume ${"%.5f".format(it.volume)}")

            // 1. The asymmetric extent lands on Z; X and Y are the swept, symmetric ones.
            assertEquals(0.2f, b.min.z, 1e-4f, "the profile's y = 0.2 edge must become z = 0.2")
            assertEquals(1.0f, b.max.z, 1e-4f, "and its y = 1.0 apex must become z = 1.0")
            assertEquals(-1.6f, b.min.x, 1e-3f, "x is swept, so it is symmetric about the origin")
            assertEquals(-1.6f, b.min.y, 1e-3f, "and so is y — which is therefore NOT the axis")
            assertTrue(
                b.max.y > 1.5f,
                "under the documented 'Y axis' reading y would carry the asymmetric [0.2, 1.0] " +
                    "extent; it is ${b.min.y}..${b.max.y}",
            )

            // 2. The same discriminator pointwise, as a matched pair: these two points are each
            //    other with y and z swapped. One is well inside under the Z reading and well outside
            //    under the Y reading; the other is exactly the reverse. Neither is near a face.
            assertTrue(
                it.contains(1.45, 0.0, 0.6),
                "radius 1.45 at z = 0.6 is inside a Z-axial solid — the section there runs 1.3..1.6",
            )
            assertTrue(
                !it.contains(1.45, 0.6, 0.0),
                "and its y/z mirror is outside, where the documented Y-axis reading would put it inside",
            )

            // 3. Pappus, axis-independent, confirming the shape rather than the axis.
            val area = 0.5 * 0.6 * 0.8
            val centroidRadius = (1.0 + 1.6 + 1.6) / 3.0
            assertEquals(2 * Math.PI * centroidRadius * area, it.volume, 3e-3, "Pappus: V = 2 pi R A")
        }

        // Why the existing suite could not catch it. `ManifoldTest.kt:640` asserts that a revolved
        // 0.6-square at R = 1.5 projects to an annulus of area pi(1.8^2 - 1.2^2) = 5.6549. That is
        // the one committed assertion that discriminates: under the documented Y-axis reading the
        // solid would be a ring standing in the XZ plane and its shadow on XY would be a 3.6 x 0.6
        // band of area 2.16. It passes — so the *code* has been telling the truth all along and only
        // the prose has not.
        val underZ = Math.PI * (1.8 * 1.8 - 1.2 * 1.2)
        val underY = 3.6 * 0.6
        println("projected shadow area: %.4f if revolved about Z, %.4f if about Y".format(underZ, underY))
        Profile.rect(0.6, 0.6).translated(1.5, 0.0).revolved(segments = 256).exactSolid().orThrow().use { torus ->
            torus.project().use { shadow ->
                println("  Manifold says ${"%.4f".format(shadow.area)}, ${shadow.contourCount} contours")
                assertEquals(underZ, shadow.area, 1e-2, "the shadow is an annulus, so the axis is Z")
                assertTrue(
                    abs(shadow.area - underY) > 1.0,
                    "and nowhere near the band a Y-axial revolve would cast",
                )
            }
        }

        // The likely origin of the error, and a hazard in its own right: Field's *own* lathe uses the
        // other axis. `afield.stdlib.revolveMesh` is documented as and implemented as a sweep about
        // Y — `vertex(rad * cu, profile[j].y, rad * su)`. The two lathes in this codebase disagree by
        // ninety degrees, so a profile ported between them comes out silently rotated. That deserves
        // a sentence in both KDocs, not only a correction in one.
        val lathe = afield.stdlib.revolveMesh(
            listOf(afield.stdlib.Vec2(1.0f, 0.0f), afield.stdlib.Vec2(1.6f, 0.0f), afield.stdlib.Vec2(1.6f, 0.8f)),
            256,
        )
        val lb = lathe.bounds()
        println("afield.stdlib.revolveMesh, same profile -> bounds $lb  (asymmetric extent on Y)")
        assertEquals(0.0f, lb.min.y, 1e-4f, "revolveMesh keeps the profile's y as y — it sweeps about Y")
        assertEquals(0.8f, lb.max.y, 1e-4f)
    }

    @Test
    fun `which axis does Section revolve actually turn about`() {
        if (!ManifoldLibrary.isAvailable) return
        val profile = Profile.rect(0.6, 0.6).translated(1.2, 0.0)
        profile.exactSection().use { s ->
            println("section bounds ${s.bounds}, area ${s.area}")
            for (deg in listOf(360.0, 270.0, 90.0)) {
                s.revolved(128, deg).use { solid ->
                    println(
                        "revolved $deg -> bounds ${solid.bounds}, volume ${"%.5f".format(solid.volume)}, " +
                            "genus ${solid.genus}",
                    )
                    // Where is the material, angularly? Probe a ring of points at the tube radius.
                    val hits = (0 until 12).joinToString(" ") { i ->
                        val a = Math.toRadians(i * 30.0)
                        val inX = solid.contains(1.2 * kotlin.math.cos(a), 0.0, 1.2 * kotlin.math.sin(a))
                        val inZ = solid.contains(1.2 * kotlin.math.cos(a), 1.2 * kotlin.math.sin(a), 0.0)
                        "${i * 30}:${if (inX) "X" else "-"}${if (inZ) "Z" else "-"}"
                    }
                    println("  ring(cos,0,sin)=X  ring(cos,sin,0)=Z   $hits")
                }
            }
        }
    }

    /** An L, in the notation, counter-clockwise. Area 3. */
    private fun lProfile(): Profile = Profile.polygon(
        listOf(P2(0, 0), P2(2, 0), P2(2, 1), P2(1, 1), P2(1, 2), P2(0, 2)),
    )

    private data class Membership(
        val tested: Int,
        val disagreements: Int,
        val insideFraction: Double,
    ) {
        val agreementFraction: Double get() = if (tested == 0) 0.0 else 1.0 - disagreements.toDouble() / tested
    }

    /**
     * Sample the shape's bound and ask both lowerings, point by point, "is this inside?".
     *
     * Points within a band of the surface are skipped: a faceted Manifold primitive genuinely differs
     * from the true curve there, and the question being asked is about *conventions*, not accuracy.
     */
    private fun membershipCheck(expr: SolidExpr, samples: Int = 3000, seed: Long = 7L): Membership {
        val q = SolidQuality(segments = 128)
        // A quarter of the longest axis of headroom, so the sample box contains a decent population
        // of *outside* points. A tight bound on a solid extrusion is 100% inside, and a check where
        // every sample is inside cannot see a shape that is too big.
        val tight = expr.bounds()!!
        val b = tight.expandedBy(0.25 * maxOf(tight.size.x, tight.size.y, tight.size.z).toDouble())
        val field = expr.toField3()
        val size = b.size
        val skin = 0.03 * maxOf(size.x, size.y, size.z)
        val random = kotlin.random.Random(seed)
        return expr.exactSolid(q).orThrow().use { solid ->
            var tested = 0
            var wrong = 0
            var inside = 0
            repeat(samples) {
                val x = b.min.x + random.nextDouble().toFloat() * size.x
                val y = b.min.y + random.nextDouble().toFloat() * size.y
                val z = b.min.z + random.nextDouble().toFloat() * size.z
                val d = field.at(x.toDouble(), y.toDouble(), z.toDouble())
                if (abs(d) < skin) return@repeat
                tested++
                if (d < 0) inside++
                if ((d < 0) != solid.contains(x.toDouble(), y.toDouble(), z.toDouble())) wrong++
            }
            Membership(tested, wrong, if (tested == 0) 0.0 else inside.toDouble() / tested)
        }
    }
}
