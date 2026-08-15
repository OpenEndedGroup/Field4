package afield.manifold

import afield.stdlib.TriMesh
import afield.stdlib.Vec2
import afield.stdlib.boundsAround
import afield.stdlib.eulerCharacteristic
import afield.stdlib.genus
import afield.stdlib.icosphereMesh
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume
import afield.stdlib.surfaceArea
import afield.stdlib.torusMesh
import afield.stdlib.weld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `afield.manifold` against geometry with closed-form answers, and against Field's own mesh family.
 *
 * # Why these particular assertions
 *
 * Manifold measures the solid rather than summing float triangles, so **most of the expected values
 * here are exact** — a 2x3x4 cube really does report `24.0` and `52.0`, an L extruded by 2 really does
 * report `6.0`. That matters more than it sounds: FFM does not type-check a [java.lang.foreign
 * .FunctionDescriptor] against the C header, so the failure mode of a binding bug is a plausible
 * *wrong* number, not a link error. A `size_t` declared as an `int`, a struct-by-value return declared
 * as a pointer, an argument in the wrong position — all of them compile, link and return something.
 * Exact expectations are the only cheap defence.
 *
 * Where the value cannot be exact, it is checked against **an independent implementation rather than a
 * remembered constant**: [signedVolume], [genus], [isWatertight] and [eulerCharacteristic] come from
 * `afield.stdlib`'s mesh family, which shares no code with Manifold. Those tests fail if either side
 * drifts, which is what makes the bridge trustworthy rather than merely tested.
 *
 * # Manifold is optional, so these tests skip
 *
 * `brew install manifold` is not a build prerequisite and a clone without it must still pass the whole
 * suite — the same contract the Ceres wrapper has. Each test therefore begins with [requireManifold],
 * which returns `false` and prints once when the library is absent. That is a deliberate trade: a
 * silent skip cannot fail a build, so the *presence* of the library in CI is asserted separately by
 * the `manifold.ffm.smoke` entry in [ManifoldSmokeTest], which is where a machine that is supposed to
 * have it says so.
 */
class ManifoldTest {

    // ==========================================================================================
    // Primitives: the exact ones
    // ==========================================================================================

    @Test
    fun `primitive volumes and areas are exact`() {
        if (!requireManifold()) return
        Solid.cube(2.0, 3.0, 4.0, center = true).use { box ->
            assertEquals(24.0, box.volume, 0.0, "a 2x3x4 box encloses exactly 24")
            assertEquals(52.0, box.surfaceArea, 0.0, "and has exactly 52 of surface")
            // The minimal closed mesh, not a triangulated 24-vertex one. Same guarantee boxMesh makes.
            assertEquals(8L, box.vertexCount)
            assertEquals(12L, box.triangleCount)
            assertEquals(18L, box.edgeCount)
            assertEquals(0, box.genus)
            assertEquals(ManifoldStatus.NO_ERROR, box.status)
        }
        Solid.tetrahedron().use { t ->
            // Vertices at alternating corners of the cube [-1,1]^3, so V = 8/3.
            assertEquals(8.0 / 3.0, t.volume, 1e-12)
            assertEquals(4L, t.triangleCount)
        }
    }

    @Test
    fun `a cube's centre flag moves it rather than resizing it`() {
        if (!requireManifold()) return
        Solid.cube(2.0, 2.0, 2.0, center = true).use { centred ->
            Solid.cube(2.0, 2.0, 2.0, center = false).use { corner ->
                assertEquals(centred.volume, corner.volume, 0.0)
                assertEquals(-1.0, centred.bounds.min.x.toDouble(), 1e-9)
                assertEquals(0.0, corner.bounds.min.x.toDouble(), 1e-9)
            }
        }
    }

    @Test
    fun `curved primitives converge on their closed forms as segments rise`() {
        if (!requireManifold()) return
        // An inscribed polygon is always *smaller* than the circle it approximates, so the error must
        // be one-sided and must shrink. Both halves matter: a sign error would pass a bare tolerance.
        var previousError = Double.MAX_VALUE
        for (segments in listOf(32, 128, 512)) {
            Solid.sphere(1.0, segments = segments).use { ball ->
                val error = 4.0 / 3.0 * PI - ball.volume
                assertTrue(error > 0.0, "an inscribed sphere must under-estimate, at $segments segments")
                assertTrue(error < previousError, "and get closer: $error should beat $previousError")
                previousError = error
            }
        }
        assertTrue(previousError < 1e-3, "512 segments should be within 1e-3, was $previousError")
    }

    // ==========================================================================================
    // Booleans
    // ==========================================================================================

    @Test
    fun `booleans satisfy inclusion-exclusion`() {
        if (!requireManifold()) return
        Solid.cube(2.0, 2.0, 2.0, center = true).use { a ->
            a.translated(1.0, 0.0, 0.0).use { b ->
                (a and b).use { intersection ->
                    (a + b).use { union ->
                        (a - b).use { difference ->
                            assertEquals(4.0, intersection.volume, 1e-12, "the overlap is a 1x2x2 slab")
                            assertEquals(12.0, union.volume, 1e-12)
                            assertEquals(4.0, difference.volume, 1e-9)
                            assertEquals(
                                union.volume,
                                a.volume + b.volume - intersection.volume,
                                1e-9,
                                "|a| + |b| - |a and b| must equal |a or b|",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a batch union matches the pairwise fold`() {
        if (!requireManifold()) return
        // Worth asserting rather than assuming: the batch path is a different code path upstream (it
        // plans the whole tree), and it is the one this binding recommends.
        val balls = (0 until 7).map { Solid.sphere(0.6, segments = 128).translated(it * 0.35, 0.0, 0.0) }
        try {
            Solid.union(balls).use { batch ->
                balls.reduce { acc, s -> acc + s }.use { folded ->
                    assertEquals(folded.volume, batch.volume, 1e-9)
                    assertEquals(folded.genus, batch.genus)
                }
            }
        } finally {
            balls.forEach { it.close() }
        }
    }

    @Test
    fun `subtracting a solid from itself is empty and not an error`() {
        if (!requireManifold()) return
        Solid.sphere(1.0, segments = 64).use { s ->
            s.copy().use { same ->
                (s - same).use { nothing ->
                    assertTrue(nothing.isEmpty)
                    assertEquals(0.0, nothing.volume, 0.0)
                    // The distinction that matters: empty is a legitimate answer, not a failure, and a
                    // pipeline must be able to tell one from the other.
                    assertEquals(ManifoldStatus.NO_ERROR, nothing.status)
                }
            }
        }
    }

    @Test
    fun `splitting keeps both halves and conserves volume`() {
        if (!requireManifold()) return
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            // `first` is the side the normal points TOWARD, which is the half of this API's
            // conventions most likely to be assumed backwards — so it is pinned with a number that
            // could only come out one way round.
            val (aboveThePlane, theRest) = cube.splitByPlane(0.0, 0.0, 1.0, 0.5)
            aboveThePlane.use { cap ->
                theRest.use { base ->
                    assertEquals(8.0, cap.volume + base.volume, 1e-9, "a split loses nothing")
                    assertEquals(2.0, cap.volume, 1e-9, "the cap above z = 0.5 is 0.5 tall over a 2x2 face")
                    assertEquals(6.0, base.volume, 1e-9, "and the rest is 1.5 tall")
                }
            }
            // trimByPlane keeps exactly that first half, which is the claim its KDoc makes.
            cube.trimByPlane(0.0, 0.0, 1.0, 0.5).use { trimmed ->
                assertEquals(2.0, trimmed.volume, 1e-9, "trimByPlane keeps the normal side")
            }
        }
    }

    @Test
    fun `decompose finds the components a union hid`() {
        if (!requireManifold()) return
        Solid.cube(1.0, 1.0, 1.0, center = true).use { a ->
            a.translated(5.0, 0.0, 0.0).use { b ->
                b.translated(5.0, 0.0, 0.0).use { c ->
                    Solid.union(listOf(a, b, c)).use { all ->
                        assertEquals(3.0, all.volume, 1e-9)
                        // Genus is (2 - chi) / 2 over the WHOLE surface, and chi adds up across
                        // components, so n disjoint blocks report 1 - n rather than 0. Worth asserting
                        // because a negative genus reads like a bug until you know that.
                        assertEquals(-2, all.genus, "three disjoint components give genus 1 - 3")
                        val parts = all.decompose()
                        try {
                            assertEquals(3, parts.size)
                            parts.forEach {
                                assertEquals(1.0, it.volume, 1e-9)
                                assertEquals(0, it.genus, "and each part on its own is genus 0")
                            }
                        } finally {
                            parts.forEach { it.close() }
                        }
                    }
                }
            }
        }
    }

    // ==========================================================================================
    // Genus — the property that needs the closedness guarantee to mean anything
    // ==========================================================================================

    @Test
    fun `each bore adds one to the genus`() {
        if (!requireManifold()) return
        Solid.cube(6.0, 2.0, 1.0, center = true).use { plate ->
            for (bores in 0..3) {
                val drills = (0 until bores).map {
                    Solid.cylinder(4.0, 0.3, segments = 64, center = true).translated(it * 1.5 - 1.5, 0.0, 0.0)
                }
                try {
                    val drilled = if (drills.isEmpty()) plate.copy() else Solid.union(drills).use { plate - it }
                    drilled.use {
                        assertEquals(bores, it.genus, "$bores bores should be genus $bores")
                        assertEquals(
                            plate.volume - bores * PI * 0.09 * 1.0,
                            it.volume,
                            2e-3,
                            "and should remove one cylinder's worth each",
                        )
                    }
                } finally {
                    drills.forEach { d -> d.close() }
                }
            }
        }
    }

    @Test
    fun `Manifold and Field agree about genus on a torus`() {
        if (!requireManifold()) return
        // The reason this is a torus and not a sphere: `assertEquals(0, 0)` would pass on a stub.
        val mesh = torusMesh(majorSegments = 64, minorSegments = 32, majorRadius = 1.0, minorRadius = 0.35)
        assertEquals(1, mesh.genus(), "Field's own topology says genus 1")
        mesh.toSolid().use { solid ->
            assertEquals(1, solid.genus, "and so does Manifold")
            assertEquals(0, mesh.eulerCharacteristic(), "chi = 2 - 2g = 0 for a torus")
        }
    }

    // ==========================================================================================
    // The TriMesh bridge
    // ==========================================================================================

    @Test
    fun `the TriMesh bridge agrees with Field's own measurements`() {
        if (!requireManifold()) return
        val mesh = icosphereMesh(subdivisions = 3, radius = 1.0)
        assertTrue(mesh.isWatertight(), "the input is closed to begin with")
        mesh.toSolid().use { solid ->
            assertEquals(ManifoldStatus.NO_ERROR, solid.status)
            // Two implementations sharing no code: Field sums signed tetrahedra over float triangles,
            // Manifold integrates the solid in double.
            assertEquals(mesh.signedVolume(), solid.volume, 1e-5)
            assertEquals(mesh.surfaceArea(), solid.surfaceArea, 1e-4)
            assertEquals(mesh.triangleCount.toLong(), solid.triangleCount)
            assertEquals(mesh.vertexCount.toLong(), solid.vertexCount)
        }
    }

    @Test
    fun `a TriMesh round trip preserves the geometry`() {
        if (!requireManifold()) return
        val mesh = icosphereMesh(subdivisions = 2, radius = 1.3)
        mesh.toSolid().use { solid ->
            val back = solid.toTriMesh()
            assertEquals(mesh.triangleCount, back.triangleCount)
            assertEquals(mesh.vertexCount, back.vertexCount)
            assertTrue(back.isWatertight(), "and it comes back closed")
            assertEquals(mesh.signedVolume(), back.signedVolume(), 1e-4)
            assertEquals(mesh.genus(), back.genus())
        }
    }

    @Test
    fun `normals come back in the property slot right after the position`() {
        if (!requireManifold()) return
        // The trap: Manifold indexes normal channels *excluding* position, so asking for index 3 puts
        // the normal at MeshGL index 6 with three zeroed channels in front of it — a mesh whose
        // "normals" are all (0,0,0) and which therefore renders black rather than failing.
        Solid.sphere(1.0, segments = 64).use { ball ->
            val mesh = ball.toTriMesh(normals = true)
            val normals = assertNotNull(mesh.normals, "normals were asked for")
            assertEquals(mesh.vertexCount * 3, normals.size)
            var worstDeviation = 0.0
            for (v in 0 until mesh.vertexCount) {
                val p = Triple(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
                val n = Triple(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2])
                val nLength = sqrt((n.first * n.first + n.second * n.second + n.third * n.third).toDouble())
                assertTrue(nLength > 0.9, "vertex $v's normal is degenerate (length $nLength)")
                val pLength = sqrt((p.first * p.first + p.second * p.second + p.third * p.third).toDouble())
                val dot = (p.first * n.first + p.second * n.second + p.third * n.third) / pLength / nLength
                worstDeviation = maxOf(worstDeviation, abs(1.0 - dot))
            }
            // On a sphere at the origin the outward normal *is* the normalised position.
            assertTrue(worstDeviation < 3e-2, "normals should point outward; worst deviation $worstDeviation")
        }
    }

    @Test
    fun `asking for normals splits vertices at creases`() {
        if (!requireManifold()) return
        // The surprise worth pinning down, because it makes an honest-looking check lie: a mesh with
        // per-vertex normals CANNOT share a vertex across a hard edge, since the two faces need
        // different normals there. So Manifold duplicates the position — and the result is a mesh that
        // is geometrically closed but whose *index* topology is not, which is what Field's
        // isWatertight() and genus() read.
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            val plain = cube.toTriMesh()
            assertEquals(8, plain.vertexCount, "a cube is 8 shared vertices")
            assertTrue(plain.isWatertight(), "and closed by Field's reckoning")
            assertEquals(0, plain.genus())

            val shaded = cube.toTriMesh(normals = true)
            assertEquals(24, shaded.vertexCount, "each corner splits three ways, one per face")
            assertTrue(
                !shaded.isWatertight(),
                "so the crease-split mesh is NOT index-watertight — this is the trap, not a bug",
            )
            assertEquals(plain.signedVolume(), shaded.signedVolume(), 1e-9, "though the geometry is identical")
            // ...and welding puts the topology back, at the cost of the normals that caused the split.
            assertTrue(shaded.weld(1e-6).isWatertight())
            assertEquals(0, shaded.weld(1e-6).genus())
        }
        // Which is why the topology check in the round-trip test above uses the plain form. Asserted
        // here rather than implied, so nobody "fixes" it by adding normals.
        Solid.scope {
            val part = Solid.cube(6.0, 4.0, 1.0, center = true) -
                Solid.cylinder(4.0, 0.5, segments = 32, center = true)
            assertEquals(1, part.genus)
            assertEquals(1, part.toTriMesh().genus(), "Field agrees, on the plain mesh")
            assertTrue(part.toTriMesh(normals = true).genus() != 1, "but not on the crease-split one")
            Unit
        }
    }

    @Test
    fun `an open mesh is reported rather than accepted`() {
        if (!requireManifold()) return
        val singleTriangle = TriMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            intArrayOf(0, 1, 2),
        )
        assertTrue(!singleTriangle.isValidSolid())
        singleTriangle.toSolid(check = false).use { solid ->
            assertEquals(ManifoldStatus.NOT_MANIFOLD, solid.status)
            assertEquals(0.0, solid.volume, 0.0, "and it has no volume, which is the silent failure")
        }
        // ...and the checking form turns that silence into an exception naming the DEFECT.
        //
        // This assertion used to require the word "weld", because the message was a list of repairs
        // the reader might try. That was the wrong contract: welding does nothing for a single open
        // triangle, so the message sent the reader down a dead end. It now carries
        // [afield.stdlib.TriMesh.diagnose]'s report, which says what is actually wrong.
        val failure = runCatching { singleTriangle.toSolid().close() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "expected an IllegalStateException, got $failure")
        assertTrue(
            failure.message?.contains("NOT_MANIFOLD") == true,
            "the message should name the status: ${failure.message}",
        )
        assertTrue(
            failure.message?.contains("OPEN_BOUNDARY") == true,
            "and the defect, which for a lone triangle is its open edge: ${failure.message}",
        )
        assertTrue(
            failure.message?.contains("repairToSolid") == true,
            "and the call that attempts it: ${failure.message}",
        )
    }

    @Test
    fun `an unwelded mesh is repaired by the weld tolerance`() {
        if (!requireManifold()) return
        // Two triangles per face, each face's vertices duplicated: geometrically a closed cube,
        // topologically 36 loose vertices. This is what comes off an STL, and it is exactly the input
        // that makes people think Manifold is broken.
        val unwelded = unweldedCube(1.0f)
        assertTrue(!unwelded.isValidSolid(), "an unwelded cube is not a manifold")
        unwelded.toSolid(weldTolerance = 1e-6).use { solid ->
            assertEquals(ManifoldStatus.NO_ERROR, solid.status, "welding first fixes it")
            assertEquals(1.0, solid.volume, 1e-6)
            assertEquals(0, solid.genus)
        }
    }

    // ==========================================================================================
    // The upcalls
    // ==========================================================================================

    @Test
    fun `a level set from a Kotlin lambda is closed and correct`() {
        if (!requireManifold()) return
        Solid.levelSet(boundsAround(radius = 1.3), edgeLength = 0.04) { x, y, z ->
            1.0 - sqrt(x * x + y * y + z * z)
        }.use { ball ->
            assertEquals(ManifoldStatus.NO_ERROR, ball.status)
            assertEquals(0, ball.genus)
            assertEquals(4.0 / 3.0 * PI, ball.volume, 5e-3)
            // The guarantee that distinguishes this from marchingCubes: whatever the field did, the
            // output is a valid closed manifold, so Field's own watertightness check must agree.
            assertTrue(ball.toTriMesh().isWatertight())
        }
    }

    @Test
    fun `the parallel and sequential level sets produce identical meshes`() {
        if (!requireManifold()) return
        // This is the real test of the upcall contract. Manifold calls the lambda from its own TBB
        // worker threads; if that were racing, or if the upcall stub were not re-entrant, the two runs
        // would differ. Bit-identical volume is a much stronger statement than "both look like a
        // sphere".
        val field: (Double, Double, Double) -> Double = { x, y, z ->
            0.9 - sqrt(x * x + y * y + z * z) + 0.05 * kotlin.math.sin(9 * x) * kotlin.math.sin(9 * y)
        }
        Solid.levelSet(boundsAround(radius = 1.2), edgeLength = 0.045, parallel = true, field = field).use { p ->
            Solid.levelSet(boundsAround(radius = 1.2), edgeLength = 0.045, parallel = false, field = field).use { s ->
                assertEquals(s.triangleCount, p.triangleCount)
                assertEquals(s.vertexCount, p.vertexCount)
                assertEquals(s.volume, p.volume, 0.0, "identical inputs must give a bit-identical volume")
                assertEquals(s.genus, p.genus)
            }
        }
    }

    @Test
    fun `a level set really does run the lambda on more than one thread`() {
        if (!requireManifold()) return
        // Not a performance assertion — a *contract* assertion. The documented requirement that the
        // field be thread-safe is only justified if it is genuinely called concurrently, and if that
        // ever stopped being true the docs would quietly become wrong.
        val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        Solid.levelSet(boundsAround(radius = 1.2), edgeLength = 0.03) { x, y, z ->
            threads += Thread.currentThread().name
            1.0 - sqrt(x * x + y * y + z * z)
        }.use {
            assertTrue(
                threads.size > 1,
                "the parallel level set should use several threads, saw ${threads.size}: $threads",
            )
        }
        val sequentialThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        Solid.levelSet(boundsAround(radius = 1.2), edgeLength = 0.06, parallel = false) { x, y, z ->
            sequentialThreads += Thread.currentThread().name
            1.0 - sqrt(x * x + y * y + z * z)
        }.use {
            assertEquals(1, sequentialThreads.size, "parallel = false must mean exactly one thread")
        }
    }

    @Test
    fun `an exception inside an upcall surfaces as an exception`() {
        if (!requireManifold()) return
        // Without the catch in ManifoldNative.SdfBridge this does not fail the test — it takes the
        // whole JVM down, because there is no unwinding a Java exception through C++ frames.
        val failure = runCatching {
            Solid.levelSet(boundsAround(radius = 1.0), edgeLength = 0.15) { x, _, _ ->
                if (x > 0.4) error("field failed on purpose")
                1.0 - x
            }.close()
        }.exceptionOrNull()
        assertNotNull(failure, "the throw should have surfaced")
        assertTrue(
            failure.cause?.message?.contains("field failed on purpose") == true,
            "and should carry the original cause, got ${failure.cause?.message}",
        )
    }

    @Test
    fun `warp moves vertices without breaking the solid`() {
        if (!requireManifold()) return
        Solid.cube(1.0, 1.0, 4.0, center = true).use { bar ->
            bar.refined(12).use { fine ->
                fine.warped { x, y, z ->
                    val a = z * 0.5
                    Triple(
                        x * kotlin.math.cos(a) - y * kotlin.math.sin(a),
                        x * kotlin.math.sin(a) + y * kotlin.math.cos(a),
                        z,
                    )
                }.use { twisted ->
                    assertEquals(ManifoldStatus.NO_ERROR, twisted.status)
                    assertEquals(0, twisted.genus)
                    // A per-slice rotation is volume-preserving in the limit; the discrete mesh loses
                    // a little to the chords, and gains surface because the sides are no longer flat.
                    assertEquals(4.0, twisted.volume, 4e-2)
                    assertTrue(twisted.surfaceArea > bar.surfaceArea)
                }
            }
        }
        // A warp with no vertices to move is a no-op, which is the mistake worth documenting
        Solid.cube(1.0, 1.0, 4.0, center = true).use { coarse ->
            coarse.warped { x, y, z -> Triple(x, y, z) }.use { same ->
                assertEquals(coarse.volume, same.volume, 1e-12)
                assertEquals(coarse.triangleCount, same.triangleCount, "warp does not re-mesh")
            }
        }
    }

    // ==========================================================================================
    // 2D
    // ==========================================================================================

    @Test
    fun `offset areas match their closed forms for every join type`() {
        if (!requireManifold()) return
        // A 2x2 square grown by 0.5: area 4 to begin with, the four sides add 2*0.5 each, and the
        // corners are exactly where the join type shows up. Every one of these is a closed form.
        Solid.scope {
            val square = Section.square(2.0, 2.0, center = true)
            assertEquals(4.0, square.area, 0.0)
            assertEquals(9.0, square.offset(0.5, JoinType.MITER).area, 1e-12, "miter adds four 0.5 squares")
            assertEquals(8.5, square.offset(0.5, JoinType.BEVEL).area, 1e-12, "bevel adds four half-squares")
            assertEquals(
                8.0 + PI * 0.25,
                square.offset(0.5, JoinType.ROUND, circularSegments = 4096).area,
                1e-4,
                "round adds one circle of radius 0.5",
            )
            assertEquals(1.0, square.offset(-0.5, JoinType.MITER).area, 1e-12, "and a negative offset erodes")
            assertTrue(square.offset(-1.5, JoinType.MITER).isEmpty, "eroding past the middle leaves nothing")
            Unit
        }
    }

    @Test
    fun `a closing is the identity on a convex region and an opening is not`() {
        if (!requireManifold()) return
        Solid.scope {
            val square = Section.square(2.0, 2.0, center = true)
            val closed = square.offset(0.5, JoinType.ROUND, circularSegments = 512)
                .offset(-0.5, JoinType.ROUND, circularSegments = 512)
            assertEquals(4.0, closed.area, 1e-6, "dilate-then-erode returns a convex region unchanged")

            val opened = square.offset(-0.5, JoinType.ROUND, circularSegments = 512)
                .offset(0.5, JoinType.ROUND, circularSegments = 512)
            // Erode-then-dilate cannot restore the corners the erosion cut off: what comes back is the
            // square with each corner replaced by a quarter circle of radius 0.5.
            assertEquals(4.0 - (4.0 - PI) * 0.25, opened.area, 1e-3)
            assertTrue(opened.area < square.area)
            Unit
        }
    }

    @Test
    fun `winding decides what is a hole`() {
        if (!requireManifold()) return
        val outer = listOf(Vec2(0f, 0f), Vec2(3f, 0f), Vec2(3f, 3f), Vec2(0f, 3f))       // counter-clockwise
        val inner = listOf(Vec2(1f, 1f), Vec2(1f, 2f), Vec2(2f, 2f), Vec2(2f, 1f))       // clockwise
        Section.ofPolygons(listOf(outer, inner)).use { withHole ->
            assertEquals(8.0, withHole.area, 1e-12, "a clockwise inner contour is a hole")
            assertEquals(2L, withHole.contourCount)
        }
        Section.ofPolygons(listOf(outer, inner.reversed())).use { without ->
            assertEquals(9.0, without.area, 1e-12, "wound the same way it is absorbed")
        }
    }

    @Test
    fun `contours come back with outlines positive and holes negative`() {
        if (!requireManifold()) return
        Solid.scope {
            val annulus = Section.circle(1.0, segments = 512) - Section.circle(0.5, segments = 512)
            assertEquals(0.75 * PI, annulus.area, 1e-3)
            val contours = annulus.toPolygons()
            assertEquals(2, contours.size)
            val areas = contours.map { signedArea(it) }
            assertEquals(1, areas.count { it > 0 }, "exactly one outline")
            assertEquals(1, areas.count { it < 0 }, "exactly one hole")
            // And the round trip preserves it, which is what makes read-modify-write safe. The
            // tolerance is float32's, not Clipper's: Field's Vec2 is Float, so the points narrow on
            // the way out and the area moves in the ninth significant figure.
            Section.ofPolygons(contours).use { rebuilt ->
                assertEquals(annulus.area, rebuilt.area, 1e-5)
                assertEquals(annulus.contourCount, rebuilt.contourCount)
            }
            Unit
        }
    }

    @Test
    fun `extrusion and revolution give the volumes the calculus does`() {
        if (!requireManifold()) return
        val lShape = listOf(Vec2(0f, 0f), Vec2(2f, 0f), Vec2(2f, 1f), Vec2(1f, 1f), Vec2(1f, 2f), Vec2(0f, 2f))
        Section.ofPolygon(lShape).use { l ->
            assertEquals(3.0, l.area, 1e-12)
            l.extruded(2.0).use { prism ->
                assertEquals(6.0, prism.volume, 1e-12, "volume is area times height, exactly")
                assertEquals(ManifoldStatus.NO_ERROR, prism.status)
                assertEquals(0, prism.genus)
            }
        }
        // Pappus's theorem: revolving an area A whose centroid is at radius R sweeps 2 pi R A.
        Section.square(1.0, 0.5, center = true).translated(1.5, 0.0).use { profile ->
            profile.revolved(segments = 512).use { torus ->
                assertEquals(1, torus.genus, "a revolved offset profile is a torus")
                assertEquals(2 * PI * 1.5 * 0.5, torus.volume, 1e-3)
            }
        }
    }

    /**
     * `Section.revolved`'s axis of symmetry is **Z**, pinned by measurement.
     *
     * Volume and genus — which is all the Pappus test above checks — are rotation invariant, so the
     * whole suite passed for as long as this binding has existed while the KDoc claimed the axis was
     * Y. It is not: Manifold revolves the 2D cross-section about *its own* y-axis and that axis comes
     * out as the solid's z. Reported by the agent working on `afield.solidsl`, whose independent
     * point-membership cross-check sat at ~65% agreement until it assumed Z; confirmed here from
     * scratch, against bounds and inside/outside tests rather than against their conclusion.
     *
     * Worth pinning because `afield.stdlib.revolveMesh`, Field's other lathe, revolves about **Y** —
     * so the two disagree, and an invariant that cannot see the difference is how that got missed.
     */
    @Test
    fun `revolved sweeps around Z, not Y`() {
        if (!requireManifold()) return
        // x in [1,2], y in [0,3]. Around Z this is a tube: radius 1..2, height 3 along z.
        val profile = listOf(Vec2(1f, 0f), Vec2(2f, 0f), Vec2(2f, 3f), Vec2(1f, 3f))
        Section.ofPolygon(profile).use { section ->
            section.revolved(segments = 256).use { tube ->
                val b = tube.bounds
                assertEquals(-2.0, b.min.x.toDouble(), 1e-6, "x is radial")
                assertEquals(2.0, b.max.x.toDouble(), 1e-6, "x is radial")
                assertEquals(-2.0, b.min.y.toDouble(), 1e-6, "y is radial too")
                assertEquals(2.0, b.max.y.toDouble(), 1e-6, "y is radial too")
                assertEquals(0.0, b.min.z.toDouble(), 1e-6, "and the profile's y became z")
                assertEquals(3.0, b.max.z.toDouble(), 1e-6, "and the profile's y became z")

                // The decisive pair: the hole runs along Z, and Y passes through solid material.
                assertTrue(!tube.contains(0.0, 0.0, 1.5), "the Z axis is the bore — it must be empty")
                assertTrue(tube.contains(0.0, 1.5, 0.0), "the Y axis is inside the wall — it must be solid")

                assertEquals(1, tube.genus, "a tube is a torus topologically")
                assertEquals(PI * (2.0 * 2.0 - 1.0 * 1.0) * 3.0, tube.volume, 1e-2, "an annulus of height 3")
            }
        }
    }

    @Test
    fun `slice and project return real cross sections`() {
        if (!requireManifold()) return
        // The C API returns ManifoldPolygons* from both of these, not ManifoldCrossSection*. Getting
        // that wrong does not throw: it constructs a Polygons inside cross-section-sized memory and
        // aborts the process in libc++ the first time anything reads the object. There is no Java
        // stack when that happens, so this test is the guard.
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            cube.slice(0.0).use { mid ->
                assertEquals(4.0, mid.area, 1e-12)
                assertEquals(1L, mid.contourCount)
            }
            cube.slice(0.5).use { high ->
                assertEquals(4.0, high.area, 1e-12, "a prism's slices are all the same")
            }
        }
        Solid.sphere(1.0, segments = 512).use { ball ->
            ball.slice(0.5).use { high ->
                assertEquals(PI * 0.75, high.area, 1e-3, "a sphere at z = 0.5 slices to radius sqrt(3)/2")
            }
            ball.project().use { shadow ->
                assertEquals(PI, shadow.area, 1e-3)
            }
        }
        Section.square(0.6, 0.6, center = true).translated(1.5, 0.0).use { profile ->
            profile.revolved(segments = 256).use { torus ->
                torus.slice(0.0).use { equator ->
                    assertEquals(2L, equator.contourCount, "a torus's equator is two contours")
                }
                torus.project().use { shadow ->
                    // THIS is the number that knows which axis `revolved` sweeps around, and for a
                    // long time it was the only one in this file that did. `project()` flattens onto
                    // XY, so a Z-revolved torus casts an annulus of 5.6549 where a Y-revolved one
                    // would cast a 2.1600 rectangle. Genus, the Pappus volume above and the equator's
                    // contour count are all rotation-invariant and pass under either reading — which
                    // is how `Section.revolved`'s KDoc claimed "about the Y axis" for the lifetime of
                    // the binding while this assertion quietly disproved it on every run. The lesson
                    // is not about revolve: an invariant that cannot see a property does not defend
                    // it, however green it is. See `revolved sweeps around Z, not Y` below.
                    assertEquals(2L, shadow.contourCount, "and its shadow is an annulus")
                    assertEquals(PI * (1.8 * 1.8 - 1.2 * 1.2), shadow.area, 1e-2)
                }
            }
        }
    }

    @Test
    fun `the FLine bridge separates sub-paths into contours`() {
        if (!requireManifold()) return
        val ring = afield.graphics.FLine()
        ring.moveTo(0.0, 0.0).lineTo(6.0, 0.0).lineTo(6.0, 6.0).lineTo(0.0, 6.0).lineTo(0.0, 0.0)
        ring.moveTo(2.0, 2.0).lineTo(2.0, 4.0).lineTo(4.0, 4.0).lineTo(4.0, 2.0).lineTo(2.0, 2.0)
        ring.toSection(sampleDistance = 0.1).use { region ->
            assertEquals(2L, region.contourCount, "two moveTo's, two contours")
            assertEquals(32.0, region.area, 0.5, "36 minus the 4 the inner sub-path removes")
            val back = region.toFLines()
            assertEquals(2, back.size)
            back.forEach { assertTrue(it.nodes.size > 3) }
        }
    }

    // ==========================================================================================
    // Rays and clearance
    // ==========================================================================================

    @Test
    fun `ray distances are in the solid's own units`() {
        if (!requireManifold()) return
        // The C API reports the hit as a fraction of the origin-to-end segment, so this asserts the
        // conversion: the same geometry must give the same distance whatever ray length is asked for.
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            for (length in listOf(10.0, 1e3, 1e5)) {
                val hits = cube.raycast(-5.0, 0.0, 0.0, 1.0, 0.0, 0.0, length = length)
                assertEquals(2, hits.size, "at ray length $length")
                assertEquals(4.0, hits[0].distance, 1e-6, "enters at x = -1, i.e. 4 from the origin")
                assertEquals(6.0, hits[1].distance, 1e-6, "and leaves at x = +1")
                assertEquals(-1.0, hits[0].normalX, 1e-9)
                assertEquals(1.0, hits[1].normalX, 1e-9)
            }
        }
    }

    @Test
    fun `containment is decided by crossing parity`() {
        if (!requireManifold()) return
        Solid.cube(2.0, 2.0, 2.0, center = true).use { cube ->
            assertTrue(cube.contains(0.0, 0.0, 0.0))
            assertTrue(cube.contains(0.9, 0.9, 0.9))
            assertTrue(!cube.contains(1.1, 0.0, 0.0))
            assertTrue(!cube.contains(1.5, 1.5, 1.5))
            assertTrue(!cube.contains(-3.0, 0.0, 0.0))
        }
        // A shape with a hole: a point in the bore is outside, which parity gets right and a bounding
        // box does not.
        Solid.scope {
            val tube = Solid.cylinder(4.0, 1.0, segments = 64, center = true) -
                Solid.cylinder(6.0, 0.5, segments = 64, center = true)
            assertTrue(tube.contains(0.75, 0.0, 0.0), "in the wall")
            assertTrue(!tube.contains(0.0, 0.0, 0.0), "in the bore")
            Unit
        }
    }

    @Test
    fun `minGap measures the clearance between two parts`() {
        if (!requireManifold()) return
        Solid.cube(2.0, 2.0, 2.0, center = true).use { a ->
            a.translated(5.0, 0.0, 0.0).use { b ->
                assertEquals(3.0, a.minGap(b, searchLength = 10.0), 1e-6)
            }
            a.translated(2.0, 0.0, 0.0).use { touching ->
                assertEquals(0.0, a.minGap(touching, searchLength = 10.0), 1e-6, "face to face is zero")
            }
        }
    }

    // ==========================================================================================
    // Lifetime
    // ==========================================================================================

    @Test
    fun `a closed Solid refuses to be read`() {
        if (!requireManifold()) return
        val solid = Solid.cube(1.0)
        assertEquals(1.0, solid.volume, 1e-12)
        solid.close()
        assertTrue(solid.isClosed)
        // The alternative to this check is reading freed memory, which is a crash somewhere else later.
        val failure = runCatching { solid.volume }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "expected a refusal, got $failure")
        solid.close()      // idempotent
    }

    @Test
    fun `scope closes what it built and keeps what came back`() {
        if (!requireManifold()) return
        lateinit var body: Solid
        lateinit var bore: Solid
        val part = Solid.scope {
            body = Solid.cube(2.0, 2.0, 1.0, center = true)
            bore = Solid.cylinder(4.0, 0.3, segments = 32, center = true)
            body - bore
        }
        part.use {
            assertTrue(!it.isClosed, "the result survives its scope")
            assertEquals(4.0 - PI * 0.09, it.volume, 1e-2)
        }
        assertTrue(body.isClosed, "and the intermediates do not")
        assertTrue(bore.isClosed)
    }

    @Test
    fun `scope exempts every solid in a returned collection`() {
        if (!requireManifold()) return
        val halves = Solid.scope {
            Solid.cube(2.0, 2.0, 2.0, center = true).splitByPlane(0.0, 0.0, 1.0, 0.0).toList()
        }
        try {
            assertTrue(halves.none { it.isClosed }, "both halves of the returned pair survive")
            assertEquals(8.0, halves.sumOf { it.volume }, 1e-9)
        } finally {
            halves.forEach { it.close() }
        }
    }

    @Test
    fun `a nested scope hands its result to the enclosing one`() {
        if (!requireManifold()) return
        lateinit var inner: Solid
        Solid.scope {
            inner = Solid.scope { Solid.cube(1.0) }
            assertTrue(!inner.isClosed, "the inner scope kept its result")
            Unit
        }
        // ...and then the outer scope, which now owns it, released it. Without the hand-off the inner
        // result would outlive every scope and only the Cleaner would ever free it.
        assertTrue(inner.isClosed, "the enclosing scope took ownership and closed it")
    }

    @Test
    fun `abandoned solids do not need closing`() {
        if (!requireManifold()) return
        // Not a leak test — a "does the Cleaner path work at all" test. If registering the cleaning
        // action captured the Solid it would never fire, and the only symptom would be native memory
        // growing in a long session.
        repeat(5000) { Solid.cube(1.0, 1.0, 1.0).volume }
        System.gc()
        Solid.cube(1.0).use { assertEquals(1.0, it.volume, 1e-12) }
    }

    // ==========================================================================================
    // Library discovery, which is the one thing testable without the library
    // ==========================================================================================

    @Test
    fun `the library path is described whether or not it is installed`() {
        val description = ManifoldLibrary.describe()
        assertTrue(description.isNotBlank())
        if (ManifoldLibrary.isAvailable) {
            assertTrue(description.startsWith("Manifold "), description)
            assertNotNull(ManifoldLibrary.resolvedPath)
            assertTrue(ManifoldLibrary.searchedPaths.isNotEmpty())
        } else {
            // The message a box gets is the whole error-handling story for an optional dependency, so
            // it has to say what to do rather than just what is wrong.
            assertTrue(description.contains("not installed"), description)
            assertTrue(
                description.contains("brew install manifold") ||
                    description.contains("MANIFOLD_CBIND") ||
                    description.contains("package manager"),
                description,
            )
        }
    }

    @Test
    fun `every status code maps to a name`() {
        // No library needed: this is the enum staying in step with `ManifoldError` in types.h.
        for (code in 0..14) {
            assertEquals(code, ManifoldStatus.of(code).code, "code $code should round trip")
        }
        assertEquals(ManifoldStatus.NO_ERROR, ManifoldStatus.of(0))
        assertEquals(ManifoldStatus.NOT_MANIFOLD, ManifoldStatus.of(2))
        assertEquals(ManifoldStatus.CANCELLED, ManifoldStatus.of(14))
        assertTrue(!ManifoldStatus.NO_ERROR.isError)
        assertTrue(ManifoldStatus.NOT_MANIFOLD.isError)
        // A code from a newer Manifold than this enum knows about must not throw.
        assertTrue(ManifoldStatus.of(99).isError)
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private companion object {
        private var announced = false

        /**
         * `true` when Manifold is installed. Prints once when it is not, so a skipped run says so in
         * the test output instead of looking like a pass.
         */
        fun requireManifold(): Boolean {
            if (ManifoldLibrary.isAvailable) return true
            if (!announced) {
                announced = true
                println("ManifoldTest: skipping — ${ManifoldLibrary.describe()}")
            }
            return false
        }

        /** Twice the signed area of a closed contour, halved — positive for counter-clockwise. */
        fun signedArea(contour: List<Vec2>): Double {
            var total = 0.0
            for (i in contour.indices) {
                val a = contour[i]
                val b = contour[(i + 1) % contour.size]
                total += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
            }
            return total / 2.0
        }

        /**
         * A cube whose every triangle has its own three vertices — 36 of them, none shared.
         *
         * This is what an STL is (the format has no index buffer at all), so it is the realistic
         * "why does Manifold reject my mesh" input rather than a contrived one.
         */
        fun unweldedCube(size: Float): TriMesh {
            val h = size / 2f
            val corners = arrayOf(
                floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(-h, h, -h),
                floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h),
            )
            // Counter-clockwise seen from outside, matching TriMesh's convention.
            val faces = listOf(
                intArrayOf(0, 3, 2, 1),     // -z
                intArrayOf(4, 5, 6, 7),     // +z
                intArrayOf(0, 1, 5, 4),     // -y
                intArrayOf(2, 3, 7, 6),     // +y
                intArrayOf(0, 4, 7, 3),     // -x
                intArrayOf(1, 2, 6, 5),     // +x
            )
            val positions = ArrayList<Float>()
            val indices = ArrayList<Int>()
            faces.forEach { face ->
                listOf(
                    intArrayOf(face[0], face[1], face[2]),
                    intArrayOf(face[0], face[2], face[3]),
                ).forEach { triangle ->
                    triangle.forEach { corner ->
                        indices += positions.size / 3
                        positions += corners[corner][0]
                        positions += corners[corner][1]
                        positions += corners[corner][2]
                    }
                }
            }
            val mesh = TriMesh(positions.toFloatArray(), indices.toIntArray())
            check(mesh.vertexCount == 36) { "expected 36 loose vertices, got ${mesh.vertexCount}" }
            return mesh
        }
    }
}
