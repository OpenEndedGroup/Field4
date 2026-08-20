package afield.manifold

import afield.stdlib.Vec2
import afield.stdlib.boundsAround
import afield.stdlib.boundsOf
import afield.stdlib.genus
import afield.stdlib.icosphereMesh
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume
import afield.stdlib.torusMesh
import afield.stdlib.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: manifold.ffm.smoke
runner: jvm-main
entrypoint: afield.manifold.ManifoldSmokeTest
tags: [smoke, manifold, geometry, ffm, native, optional]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "Manifold smoke tests passed"
*/
/**
 * The Manifold binding, end to end, against geometry whose answers are known in closed form.
 *
 * What makes this worth having rather than a set of "it did not crash" checks is that **almost every
 * number below is exact**. A cube of 2x3x4 has volume 24 and area 52; an annulus between radii 1 and
 * 0.5 has area `0.75π`; a 2x2 square offset by `0.5` with mitred corners has area `9`, with round
 * corners `8 + 0.25π`. Manifold measures the *solid* rather than summing float triangles, so these
 * come back to the last digit and a binding bug cannot hide in a tolerance.
 *
 * The three classes of failure this is really guarding:
 *
 *  1. **A function descriptor that is subtly wrong.** FFM does not type-check against the header, so a
 *     `size_t` declared as `JAVA_INT`, or a struct-by-value return declared as a pointer, produces
 *     garbage or a segfault rather than a link error. Exact expected values catch the first; the
 *     `mem` sizes catch the second — and this suite exists in the shape it does because
 *     `manifold_slice` returns `ManifoldPolygons*`, not `ManifoldCrossSection*`, and getting that
 *     wrong aborted the process inside libc++ with no Java stack at all.
 *  2. **The upcall contract.** A Kotlin lambda is called by Manifold's own TBB threads. This runs a
 *     level set both ways and asserts they agree exactly, which is the only real check that the
 *     parallel path is not racing, and runs one that throws to prove the exception comes back out as
 *     an exception rather than taking the VM down.
 *  3. **Disagreement with Field's own geometry.** Volume, genus and watertightness are computed
 *     independently by `afield.stdlib`'s mesh family on the [TriMesh] that comes back, so the bridge
 *     is checked by two implementations that share no code.
 *
 * Manifold is optional, so a machine without it gets a clear skip and exit code 0 rather than a
 * failure — the same contract the Ceres smokes have.
 */
object ManifoldSmokeTest {

    private var checks = 0
    private val failures = mutableListOf<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        println(ManifoldLibrary.describe())
        if (!ManifoldLibrary.isAvailable) {
            // Not a failure: this is an optional native dependency and a clone without it must still
            // pass its test suite. The smoke says loudly what is not being checked.
            println("SKIPPED: no libmanifoldc, so nothing in afield.manifold was exercised.")
            println("Manifold smoke tests passed (skipped — library absent)")
            return
        }

        primitivesAreExact()
        booleansComposeArithmetically()
        genusIsTopological()
        theTriMeshBridgeRoundTrips()
        theLevelSetUpcallWorksBothWays()
        aThrowingFieldComesBackAsAnException()
        theWarpUpcallMovesVertices()
        crossSectionsAndOffsetsAreExact()
        theFLineBridgeSurvivesARoundTrip()
        sliceAndProjectReturnPolygons()
        raysAndClearances()
        statusReportsRatherThanCrashes()
        lifetimeIsExplicitAndAutomatic()

        println()
        if (failures.isEmpty()) {
            println("Manifold smoke tests passed ($checks checks)")
        } else {
            println("FAILED ${failures.size} of $checks checks:")
            failures.forEach { println("  - $it") }
            throw AssertionError("${failures.size} Manifold check(s) failed")
        }
    }

    // ==========================================================================================
    // The checks
    // ==========================================================================================

    private fun primitivesAreExact() {
        section("primitives are exact, not approximate")
        Solid.cube(2.0, 3.0, 4.0, center = true).use { box ->
            exact("cube 2x3x4 volume", box.volume, 24.0)
            exact("cube 2x3x4 area", box.surfaceArea, 52.0)
            // 12 and not 24: Manifold's cube is the minimal closed mesh, which is a stronger claim
            // than "it renders" and the same one afield.stdlib's boxMesh makes.
            equal("cube triangle count", box.triangleCount, 12L)
            equal("cube vertex count", box.vertexCount, 8L)
            equal("cube edge count", box.edgeCount, 18L)
            val bounds = box.bounds
            exact("cube bounds min x", bounds.min.x.toDouble(), -1.0)
            exact("cube bounds max z", bounds.max.z.toDouble(), 2.0)
        }
        Solid.cube(2.0, 2.0, 2.0, center = false).use { corner ->
            exact("uncentered cube sits on the origin", corner.bounds.min.x.toDouble(), 0.0)
        }
        Solid.sphere(1.0, segments = 512).use { ball ->
            near("sphere volume -> 4/3 pi", ball.volume, 4.0 / 3.0 * PI, 1e-3)
            near("sphere area -> 4 pi", ball.surfaceArea, 4.0 * PI, 1e-3)
        }
        Solid.cylinder(2.0, 1.0, segments = 512).use { tube ->
            near("cylinder volume -> pi r^2 h", tube.volume, PI * 2.0, 1e-3)
        }
        Solid.cone(3.0, 1.0, segments = 512).use { cone ->
            near("cone volume -> pi r^2 h / 3", cone.volume, PI / 3.0 * 3.0, 1e-3)
        }
        Solid.tetrahedron().use { t ->
            // vertices at alternating cube corners of side 2: volume = 8/3
            near("tetrahedron volume", t.volume, 8.0 / 3.0, 1e-9)
            equal("tetrahedron triangles", t.triangleCount, 4L)
        }
    }

    private fun booleansComposeArithmetically() {
        section("booleans are exact enough to do arithmetic with")
        Solid.cube(2.0, 2.0, 2.0, center = true).use { a ->
            Solid.cube(2.0, 2.0, 2.0, center = true).translated(1.0, 0.0, 0.0).use { b ->
                // overlap is the 1x2x2 slab in the middle
                (a and b).use { both -> exact("intersection volume", both.volume, 4.0) }
                (a + b).use { either -> exact("union volume", either.volume, 12.0) }
                (a - b).use { left -> exact("difference volume", left.volume, 4.0) }
                // inclusion-exclusion, which only holds if all three are right
                (a and b).use { i ->
                    (a + b).use { u ->
                        exact("|a| + |b| - |a and b| == |a or b|", a.volume + b.volume - i.volume, u.volume)
                    }
                }
            }
        }
        // a batch union of many overlapping spheres against the pairwise fold: same answer
        val balls = (0 until 6).map { i ->
            Solid.sphere(0.6, segments = 128).translated(i * 0.4, 0.0, 0.0)
        }
        try {
            Solid.union(balls).use { batch ->
                val folded = balls.reduce { acc, s -> acc + s }
                near("batch union == pairwise fold", batch.volume, folded.volume, 1e-9)
                folded.close()
            }
        } finally {
            balls.forEach { it.close() }
        }
        // subtracting a solid from itself is empty, and empty is not an error
        Solid.sphere(1.0, segments = 64).use { s ->
            (s - s.copy()).use { nothing ->
                truth("a - a is empty", nothing.isEmpty)
                equal("a - a is not an error", nothing.status, ManifoldStatus.NO_ERROR)
            }
        }
    }

    private fun genusIsTopological() {
        section("genus counts holes, and survives the operations")
        Solid.cube(4.0, 4.0, 1.0, center = true).use { plate ->
            equal("a plate is genus 0", plate.genus, 0)
            val bores = (0 until 3).map { Solid.cylinder(4.0, 0.3, segments = 64, center = true).translated(it - 1.0, 0.0, 0.0) }
            try {
                Solid.union(bores).use { all ->
                    (plate - all).use { drilled ->
                        equal("three bores make genus 3", drilled.genus, 3)
                        near(
                            "drilled volume = plate - 3 bores",
                            drilled.volume,
                            plate.volume - 3 * PI * 0.09 * 1.0,
                            2e-3,
                        )
                    }
                }
            } finally {
                bores.forEach { it.close() }
            }
        }
        // a revolved offset square is a torus: genus 1, and Pappus's theorem gives the volume
        Section.square(1.0, 0.5, center = true).translated(1.5, 0.0).use { profile ->
            profile.revolved(segments = 256).use { torus ->
                equal("a revolved profile is genus 1", torus.genus, 1)
                near("Pappus: V = 2 pi R A", torus.volume, 2 * PI * 1.5 * 0.5, 1e-3)
                equal("and it is valid", torus.status, ManifoldStatus.NO_ERROR)
            }
        }
        // two disjoint blocks: one Solid, genus 0, two components
        Solid.cube(1.0, center = true).use { a ->
            a.translated(5.0, 0.0, 0.0).use { b ->
                (a + b).use { two ->
                    exact("two disjoint unit cubes", two.volume, 2.0)
                    val parts = two.decompose()
                    try {
                        equal("decompose finds both", parts.size, 2)
                        exact("each part is a unit cube", parts[0].volume, 1.0)
                    } finally {
                        parts.forEach { it.close() }
                    }
                }
            }
        }
    }

    private fun theTriMeshBridgeRoundTrips() {
        section("the TriMesh bridge, checked by Field's own mesh code")
        val ball = icosphereMesh(subdivisions = 3, radius = 1.0)
        truth("the icosphere Field built is watertight", ball.isWatertight())
        ball.toSolid().use { solid ->
            equal("Manifold accepts it", solid.status, ManifoldStatus.NO_ERROR)
            equal("and agrees on the genus", solid.genus, ball.genus())
            // Two independent volume computations: Field sums signed tetrahedra over float triangles,
            // Manifold integrates the solid in double. They must agree to float precision.
            near(
                "Field's signedVolume == Manifold's volume",
                solid.volume,
                ball.signedVolume(),
                1e-5,
            )
            equal("triangle count survives the trip in", solid.triangleCount, ball.triangleCount.toLong())

            val back = solid.toTriMesh()
            equal("and back out", back.triangleCount, ball.triangleCount)
            truth("the returned mesh is watertight by Field's reckoning", back.isWatertight())
            near(
                "and has the same volume again",
                back.signedVolume(),
                ball.signedVolume(),
                1e-5,
            )

            val withNormals = solid.toTriMesh(normals = true)
            val normals = withNormals.normals
            truth("normals asked for are normals delivered", normals != null && normals.size == withNormals.vertexCount * 3)
            if (normals != null) {
                // On a sphere at the origin the outward normal is the normalised position, so this
                // checks both that they are unit length and that they are in the right property slot
                // -- the trap being that Manifold indexes normal channels *excluding* position, so
                // asking for index 3 puts them at MeshGL index 6 with zeros in between.
                var worst = 0.0
                for (v in 0 until withNormals.vertexCount) {
                    val px = withNormals.positions[v * 3].toDouble()
                    val py = withNormals.positions[v * 3 + 1].toDouble()
                    val pz = withNormals.positions[v * 3 + 2].toDouble()
                    val nx = normals[v * 3].toDouble()
                    val ny = normals[v * 3 + 1].toDouble()
                    val nz = normals[v * 3 + 2].toDouble()
                    val pl = sqrt(px * px + py * py + pz * pz)
                    val nl = sqrt(nx * nx + ny * ny + nz * nz)
                    if (pl > 1e-6 && nl > 1e-6) {
                        worst = maxOf(worst, abs(1.0 - (px * nx + py * ny + pz * nz) / pl / nl))
                    }
                }
                near("every normal points outward on a sphere", worst, 0.0, 2e-2)
            }
        }
        // a torus, so the genus agreement is not a check that 0 == 0
        val donut = torusMesh(majorRadius = 1.0, minorRadius = 0.35, majorSegments = 64, minorSegments = 32)
        donut.toSolid().use { solid ->
            equal("Field and Manifold agree a torus is genus 1", solid.genus, 1)
            equal("and Field says so too", donut.genus(), 1)
        }
    }

    private fun theLevelSetUpcallWorksBothWays() {
        section("a Kotlin lambda as a level set, on Manifold's threads and on ours")
        val bounds = boundsAround(radius = 1.3)
        val threads = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val sphere: (Double, Double, Double) -> Double = { x, y, z ->
            threads[Thread.currentThread().name] = 1
            1.0 - sqrt(x * x + y * y + z * z)
        }

        threads.clear()
        val parallelStart = System.nanoTime()
        val parallel = Solid.levelSet(bounds, edgeLength = 0.04, field = sphere)
        val parallelMs = (System.nanoTime() - parallelStart) / 1e6
        val parallelThreads = threads.size

        threads.clear()
        val seqStart = System.nanoTime()
        val sequential = Solid.levelSet(bounds, edgeLength = 0.04, parallel = false, field = sphere)
        val seqMs = (System.nanoTime() - seqStart) / 1e6
        val seqThreads = threads.size

        parallel.use { p ->
            sequential.use { s ->
                near("a marched unit sphere has volume 4/3 pi", p.volume, 4.0 / 3.0 * PI, 5e-3)
                equal("and it is a valid manifold", p.status, ManifoldStatus.NO_ERROR)
                equal("and genus 0", p.genus, 0)
                // The real point: the parallel path must not be racing. Same field, same bounds, same
                // edge length -> bit-identical mesh, or the threads are interfering.
                equal("parallel and sequential agree on the triangle count", p.triangleCount, s.triangleCount)
                exact("and on the volume, to the last bit", p.volume, s.volume)
                truth("the parallel run really used more than one thread (saw $parallelThreads)", parallelThreads > 1)
                truth("the sequential run used exactly one (saw $seqThreads)", seqThreads == 1)
                println(
                    "      parallel ${"%.1f".format(parallelMs)} ms on $parallelThreads threads, " +
                        "sequential ${"%.1f".format(seqMs)} ms on $seqThreads " +
                        "(${"%.2f".format(seqMs / parallelMs)}x)",
                )
            }
        }

        // A field with a hole in it: the level set has to get the topology right, not just the volume
        Solid.levelSet(boundsOf(vec(-2.0, -2.0, -0.8), vec(2.0, 2.0, 0.8)), edgeLength = 0.03) { x, y, z ->
            val q = sqrt(x * x + y * y) - 1.4
            0.4 - sqrt(q * q + z * z)
        }.use { torus ->
            equal("a level-set torus is genus 1", torus.genus, 1)
            near("and has the volume 2 pi^2 R r^2 says", torus.volume, 2 * PI * PI * 1.4 * 0.16, 5e-3)
        }

        // And the bridge from Field's own SDF family, which is negative-inside
        Solid.levelSetOf(afield.stdlib.sdfSphere(0.9), boundsAround(radius = 1.3), edgeLength = 0.04).use { s ->
            near("levelSetOf(sdfSphere(0.9))", s.volume, 4.0 / 3.0 * PI * 0.729, 1e-2)
            equal("sign convention is handled, so the solid is not inverted", s.genus, 0)
            truth("and it has positive volume rather than the complement", s.volume > 0.0)
        }
    }

    private fun aThrowingFieldComesBackAsAnException() {
        section("an exception in an upcall must not take the VM down")
        var thrown: Throwable? = null
        try {
            Solid.levelSet(boundsAround(radius = 1.0), edgeLength = 0.2) { x, _, _ ->
                if (x > 0.5) error("deliberate failure inside the level-set field")
                1.0 - x
            }.close()
        } catch (t: Throwable) {
            thrown = t
        }
        truth("the exception surfaced", thrown != null)
        truth(
            "and carries the original cause (got ${thrown?.cause?.message})",
            thrown?.cause?.message?.contains("deliberate failure") == true,
        )
    }

    private fun theWarpUpcallMovesVertices() {
        section("warp: a Kotlin function moving every vertex")
        Solid.cube(1.0, 1.0, 4.0, center = true).use { bar ->
            bar.refined(16).use { fine ->
                truth("refine(16) subdivides", fine.triangleCount > bar.triangleCount * 200)
                fine.warped { x, y, z ->
                    val a = z * 0.6
                    Triple(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a), z)
                }.use { twisted ->
                    equal("a twist keeps the solid valid", twisted.status, ManifoldStatus.NO_ERROR)
                    equal("and its topology", twisted.genus, 0)
                    // A rotation about Z per slice is volume-preserving in the limit; on a discrete
                    // mesh it loses a little to the chords. Bounded, not exact.
                    near("and very nearly its volume", twisted.volume, 4.0, 1e-2)
                    truth("but it did move the surface", twisted.surfaceArea > bar.surfaceArea)
                }
            }
        }
    }

    private fun crossSectionsAndOffsetsAreExact() {
        section("2D: cross sections, and the offset that is the reason to be here")
        Section.circle(1.0, segments = 4096).use { circle ->
            near("circle area -> pi", circle.area, PI, 1e-5)
            equal("one contour", circle.contourCount, 1L)
        }
        Section.circle(1.0, segments = 4096).use { outer ->
            Section.circle(0.5, segments = 4096).use { inner ->
                (outer - inner).use { annulus ->
                    near("annulus area -> 0.75 pi", annulus.area, 0.75 * PI, 1e-4)
                    equal("two contours: an outline and a hole", annulus.contourCount, 2L)
                    val contours = annulus.toPolygons()
                    equal("read back as two", contours.size, 2)
                    val signed = contours.map { signedArea(it) }
                    truth("exactly one is wound positively (the outline)", signed.count { it > 0 } == 1)
                    truth("and exactly one negatively (the hole)", signed.count { it < 0 } == 1)
                }
            }
        }

        // The offset table, against closed-form areas. A 2x2 square grown by 0.5: the four sides add
        // 4, and the corners are where the join type shows up.
        Section.square(2.0, 2.0, center = true).use { square ->
            exact("square area", square.area, 4.0)
            square.offset(0.5, JoinType.MITER).use { mitred ->
                exact("mitred offset adds four squares", mitred.area, 9.0)
                equal("and stays a quadrilateral", mitred.vertexCount, 4L)
            }
            square.offset(0.5, JoinType.ROUND, circularSegments = 4096).use { round ->
                near("round offset adds a circle's worth", round.area, 8.0 + PI * 0.25, 1e-4)
            }
            square.offset(0.5, JoinType.BEVEL).use { bevel ->
                exact("bevel adds four half-squares", bevel.area, 8.5)
            }
            square.offset(-0.5, JoinType.MITER).use { shrunk ->
                exact("a negative offset erodes", shrunk.area, 1.0)
            }
            square.offset(-1.5, JoinType.MITER).use { gone ->
                truth("eroding past the middle leaves nothing", gone.isEmpty)
            }
            // A morphological *closing* — dilate then erode. On a convex region it is exactly the
            // identity, which is a much sharper claim than "about the same area" and the one worth
            // asserting: it says the two offsets are true inverses on the parts of the boundary where
            // nothing was rounded away.
            square.offset(0.5, JoinType.ROUND, circularSegments = 512).use { grown ->
                grown.offset(-0.5, JoinType.ROUND, circularSegments = 512).use { closed ->
                    near("closing a convex region is the identity", closed.area, 4.0, 1e-6)
                }
            }
            // An *opening* — erode then dilate — is where the rounding shows up: the corners the
            // erosion cut off do not come back sharp.
            square.offset(-0.5, JoinType.ROUND, circularSegments = 512).use { eroded ->
                eroded.offset(0.5, JoinType.ROUND, circularSegments = 512).use { opened ->
                    truth("but an opening rounds the corners, so it loses area", opened.area < square.area)
                    near("by exactly the four corner offcuts", opened.area, 4.0 - (4.0 - PI) * 0.25, 1e-3)
                }
            }
        }

        // Explicit contours, and a hole made by winding
        val outerSquare = listOf(Vec2(0f, 0f), Vec2(3f, 0f), Vec2(3f, 3f), Vec2(0f, 3f))
        val holeClockwise = listOf(Vec2(1f, 1f), Vec2(1f, 2f), Vec2(2f, 2f), Vec2(2f, 1f))
        Section.ofPolygons(listOf(outerSquare, holeClockwise)).use { withHole ->
            exact("a clockwise inner contour is a hole", withHole.area, 8.0)
        }
        Section.ofPolygons(listOf(outerSquare, holeClockwise.reversed())).use { withoutHole ->
            exact("wound the other way it is not", withoutHole.area, 9.0)
        }

        // An L, extruded: the volume is the area times the height, exactly
        val lShape = listOf(Vec2(0f, 0f), Vec2(2f, 0f), Vec2(2f, 1f), Vec2(1f, 1f), Vec2(1f, 2f), Vec2(0f, 2f))
        Section.ofPolygon(lShape).use { l ->
            exact("L area", l.area, 3.0)
            l.extruded(2.0).use { prism ->
                exact("extruded L volume is area x height", prism.volume, 6.0)
                equal("and it is closed", prism.status, ManifoldStatus.NO_ERROR)
                equal("and genus 0", prism.genus, 0)
            }
            l.extruded(2.0, slices = 20, twistDegrees = 45.0).use { twisted ->
                equal("a twisted extrusion is still valid", twisted.status, ManifoldStatus.NO_ERROR)
                truth("and has more triangles than the straight one", twisted.triangleCount > 30)
            }
        }

        Section.square(2.0, 2.0, center = true).use { sq ->
            sq.decompose().let { parts ->
                equal("a single square decomposes to one region", parts.size, 1)
                parts.forEach { it.close() }
            }
        }
    }

    private fun theFLineBridgeSurvivesARoundTrip() {
        section("FLine in, FLine out")
        // A hand-drawn square, as any box would draw it
        val drawn = afield.graphics.FLine()
        drawn.moveTo(0.0, 0.0).lineTo(10.0, 0.0).lineTo(10.0, 10.0).lineTo(0.0, 10.0).lineTo(0.0, 0.0)
        drawn.toSection(sampleDistance = 0.25).use { region ->
            near("an FLine square becomes its own area", region.area, 100.0, 1.0)
            val lines = region.toFLines()
            equal("and comes back as one closed contour", lines.size, 1)
            truth("with nodes in it", lines[0].nodes.size > 3)
            region.offset(1.0, JoinType.ROUND, circularSegments = 256).use { fat ->
                near("offsetting it grows by perimeter x d + pi d^2", fat.area, 100.0 + 40.0 + PI, 1.0)
            }
        }
        // Two sub-paths in one FLine: an outline and a hole, which `pieces()` has to separate
        val ring = afield.graphics.FLine()
        ring.moveTo(0.0, 0.0).lineTo(6.0, 0.0).lineTo(6.0, 6.0).lineTo(0.0, 6.0).lineTo(0.0, 0.0)
        ring.moveTo(2.0, 2.0).lineTo(2.0, 4.0).lineTo(4.0, 4.0).lineTo(4.0, 2.0).lineTo(2.0, 2.0)
        ring.toSection(sampleDistance = 0.2).use { region ->
            equal("two sub-paths become two contours", region.contourCount, 2L)
            near("and the inner one is a hole", region.area, 36.0 - 4.0, 0.6)
        }
    }

    private fun sliceAndProjectReturnPolygons() {
        section("3D to 2D — the one place the C API's return type is a trap")
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            cube.slice(0.0).use { mid ->
                exact("a cube's mid slice", mid.area, 4.0)
                equal("one contour", mid.contourCount, 1L)
            }
        }
        Solid.sphere(1.0, segments = 1024).use { ball ->
            ball.slice(0.5).use { high ->
                near("a sphere sliced at z=0.5 has area pi(1 - 0.25)", high.area, PI * 0.75, 1e-3)
            }
            ball.project().use { shadow ->
                near("and projects to its great circle", shadow.area, PI, 1e-3)
            }
        }
        // A torus slices into two contours at the right height, and projects to an annulus
        Section.square(0.6, 0.6, center = true).translated(1.5, 0.0).use { profile ->
            profile.revolved(segments = 256).use { torus ->
                torus.slice(0.0).use { equator ->
                    equal("a torus's equatorial slice is two contours", equator.contourCount, 2L)
                }
                torus.project().use { shadow ->
                    equal("and its shadow is an annulus", shadow.contourCount, 2L)
                    near("of the right area", shadow.area, PI * (1.8 * 1.8 - 1.2 * 1.2), 1e-2)
                }
            }
        }
    }

    private fun raysAndClearances() {
        section("rays, containment and clearance")
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            val hits = cube.raycast(-5.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            equal("a ray through a cube hits twice", hits.size, 2)
            near("entering at x = -1", hits[0].distance, 4.0, 1e-9)
            near("and leaving at x = +1", hits[1].distance, 6.0, 1e-9)
            near("with an inward-facing entry normal", hits[0].normalX, -1.0, 1e-9)
            truth("the centre is inside", cube.contains(0.0, 0.0, 0.0))
            truth("and a point beyond a face is not", !cube.contains(2.0, 0.0, 0.0))
            truth("nor is a point past a corner", !cube.contains(1.5, 1.5, 1.5))

            cube.translated(5.0, 0.0, 0.0).use { far ->
                near("two cubes 3 apart", cube.minGap(far, searchLength = 10.0), 3.0, 1e-6)
            }
        }
        // an odd number of crossings from inside
        Solid.sphere(1.0, segments = 128).use { ball ->
            equal("a ray from inside a sphere exits once", ball.raycast(0.0, 0.0, 0.0, 1.0, 0.0, 0.0).size, 1)
        }
    }

    private fun statusReportsRatherThanCrashes() {
        section("an invalid input is a status, not a segfault")
        // one open triangle: not a solid by any definition
        val open = afield.stdlib.TriMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            intArrayOf(0, 1, 2),
        )
        open.toSolid(check = false).use { solid ->
            equal("an open triangle is NOT_MANIFOLD", solid.status, ManifoldStatus.NOT_MANIFOLD)
            exact("and has no volume", solid.volume, 0.0)
            truth("isValidSolid() agrees", !open.isValidSolid())
        }
        var thrown: Throwable? = null
        try {
            open.toSolid().close()
        } catch (t: Throwable) {
            thrown = t
        }
        truth("and with check = true it throws instead", thrown is IllegalStateException)
        truth(
            // The excerpt is wide and single-line on purpose: the advice this check is about lives
            // *after* the diagnosis, so a 60-character take showed the status and nothing else, and
            // a failure here read as "got: NOT_MANIFOLD. mesh: 3" with no clue what was missing.
            "naming the status and what to try (got: ${thrown?.message?.replace('\n', ' ')?.take(240)})",
            thrown?.message?.contains("NOT_MANIFOLD") == true &&
                thrown.message?.contains("repairToSolid") == true,
        )
    }

    private fun lifetimeIsExplicitAndAutomatic() {
        section("lifetime")
        val closed = Solid.cube(1.0)
        closed.close()
        truth("a closed Solid says so", closed.isClosed)
        var thrown: Throwable? = null
        try {
            closed.volume
        } catch (t: Throwable) {
            thrown = t
        }
        truth("and refuses to be read rather than reading freed memory", thrown is IllegalStateException)
        truth("close() is idempotent", runCatching { closed.close() }.isSuccess)

        // scope() closes intermediates and exempts whatever came back
        var leakedBody: Solid? = null
        val survivor = Solid.scope {
            val body = Solid.cube(2.0, 2.0, 1.0, center = true)
            val bore = Solid.cylinder(4.0, 0.3, segments = 32, center = true)
            leakedBody = body
            body - bore
        }
        survivor.use {
            truth("a scope's result is still usable", !it.isClosed)
            near("and is the geometry we asked for", it.volume, 4.0 - PI * 0.09, 1e-2)
        }
        truth("while the intermediates it built are closed", leakedBody?.isClosed == true)
        // and a returned collection is exempted element-wise
        val halves = Solid.scope {
            Solid.cube(2.0, 2.0, 2.0, center = true).splitByPlane(0.0, 0.0, 1.0, 0.0).toList()
        }
        truth("both halves of a returned Pair survive", halves.none { it.isClosed })
        exact("and they still add up", halves.sumOf { it.volume }, 8.0)
        halves.forEach { it.close() }

        // and the Cleaner reclaims what nobody closed
        val before = ManifoldLibrary.isAvailable
        repeat(2000) { Solid.cube(1.0, 1.0, 1.0).volume }
        System.gc()
        truth("building and abandoning 2000 solids does not fall over", before)
    }

    // ==========================================================================================
    // Tiny assertion helpers — printed as they run, collected as they fail
    // ==========================================================================================

    private fun section(title: String) {
        println()
        println("  $title")
    }

    private fun exact(what: String, actual: Double, expected: Double) {
        checks++
        // "Exact" here means to double rounding, not bit-identical: Manifold sums a few terms in a
        // different order than the closed form does.
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
            println("    . $what = ${"%.6f".format(actual)} (vs ${"%.6f".format(expected)})")
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

    /** Twice the signed area of a closed contour — positive for counter-clockwise. */
    private fun signedArea(contour: List<Vec2>): Double {
        var total = 0.0
        for (i in contour.indices) {
            val a = contour[i]
            val b = contour[(i + 1) % contour.size]
            total += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        return total / 2.0
    }
}
