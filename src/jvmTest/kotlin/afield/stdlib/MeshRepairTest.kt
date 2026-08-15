package afield.stdlib

import afield.four.modules.meshFromAnyFile
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.manifold.isValidSolid
import afield.manifold.toSolid
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `mesh_repair.kt` against a table of meshes broken in exactly one named way each.
 *
 * # Why a table, and why it keeps its failures
 *
 * A repair pipeline is trivially easy to test dishonestly. Feed it three meshes it was written
 * against, assert `isValidSolid()`, print "3 of 3". So this file does three things differently:
 *
 *  1. **The damage is named and deterministic** ([MeshDamage]), generated in-tree from
 *     `mesh_generate.kt` primitives. Nothing is downloaded, and "this mesh has a hole in it" is a
 *     fact about the input rather than an inference from the output.
 *  2. **The cases the pipeline cannot fix stay in the table**, as [Expect.Unfixable] rows that assert
 *     the failure is *reported*. A row is never deleted for failing; if it starts passing, the
 *     expectation has to be changed deliberately.
 *  3. **Success is not `isValidSolid()`.** It is `isValidSolid()` *and* an actual Manifold boolean
 *     against the repaired solid *and* the volume coming out right. A mesh can be a valid manifold
 *     and still be inside out — Manifold reports that as a negative volume and throws nothing — so a
 *     test that stops at "valid" would pass on a solid that is the complement of what was meant.
 *
 * # The baseline this exists to beat
 *
 * [`existing repaired() already fixes half the table`][baselineWhatRepairedAlreadyFixes] measures
 * what was already there before `mesh_repair.kt`, and asserts the number rather than printing it.
 * The honest headline is that `TriMesh.repaired()` — which shipped with `mesh_topology.kt` — already
 * fixes **6 of the 12** meshes here that Manifold rejects. The new code is responsible for the other
 * six, and for none of the first six.
 */
class MeshRepairTest {

    // ==========================================================================================
    // The table
    // ==========================================================================================

    /** What a row of the table claims should happen. */
    private sealed interface Expect {
        /** Repair should produce a valid solid whose volume matches [volume] to [tolerance]. */
        class Solidified(val volume: Double, val tolerance: Double = 1e-3) : Expect

        /** Repair should *fail*, and should say so by leaving [kind] in `unfixed`. */
        class Unfixable(val kind: MeshDefectKind) : Expect
    }

    /**
     * One row: a mesh, the defect it was given, and what should become of it.
     *
     * @param diagnosisMustName the defect kinds [MeshDiagnosis] has to find. Asserting the diagnosis
     *   *names the right thing* is separate from asserting the repair works, because a pipeline that
     *   fixes a mesh for the wrong reason is a pipeline that will stop working silently.
     */
    private class Row(
        val name: String,
        val mesh: TriMesh,
        val diagnosisMustName: List<MeshDefectKind>,
        val expect: Expect,
    )

    private val cube = cubeMesh(1.0)
    private val ico = icosphereMesh(2, 1.0)
    private val torus = torusMesh(24, 12)

    private val cubeVolume = 1.0
    private val icoVolume = ico.signedVolume()
    private val torusVolume = torus.signedVolume()

    private fun table(): List<Row> = listOf(
        // --- undamaged controls: the pipeline must not break what is already fine ------------
        Row("pristine cube", cube, emptyList(), Expect.Solidified(cubeVolume)),
        Row("pristine icosphere", ico, emptyList(), Expect.Solidified(icoVolume)),
        Row("pristine torus", torus, emptyList(), Expect.Solidified(torusVolume)),

        // --- open boundaries: fillHoles is the new code ---------------------------------------
        Row(
            "one face deleted from a cube", MeshDamage.deleteFaces(cube, 0, 1),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Solidified(cubeVolume),
        ),
        Row(
            "one face deleted from an icosphere", MeshDamage.deleteFaces(ico, 0, 1),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Solidified(icoVolume),
        ),
        Row(
            "six faces deleted from an icosphere", MeshDamage.deleteFaces(ico, 0, 6),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Solidified(icoVolume, tolerance = 0.05),
        ),
        Row(
            "a cylinder with no caps", cylinderMesh(16, 0.5, 1.0, caps = false),
            listOf(MeshDefectKind.OPEN_BOUNDARY),
            // Two flat circular holes: the cap is exact, so the volume is the analytic prism volume of
            // a 16-gon of circumradius 0.5, height 1 — not pi r^2 h, because the mesh is a polygon.
            Expect.Solidified(0.5 * 16 * 0.5 * 0.5 * kotlin.math.sin(2 * Math.PI / 16) * 1.0, tolerance = 1e-4),
        ),

        // --- non-manifold edges: removeDuplicateFaces / removeNonManifoldFins are the new code -
        Row(
            "a duplicated face", MeshDamage.duplicateFace(ico, 0),
            listOf(MeshDefectKind.DUPLICATE_FACE, MeshDefectKind.NON_MANIFOLD_EDGE),
            Expect.Solidified(icoVolume),
        ),
        Row(
            "a dangling non-manifold fin", MeshDamage.nonManifoldFin(ico, 0),
            listOf(MeshDefectKind.NON_MANIFOLD_EDGE), Expect.Solidified(icoVolume),
        ),

        // --- what the OLD pipeline already handled; these must keep working --------------------
        Row(
            "every vertex split (an STL)", MeshDamage.splitSharedVertices(ico),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Solidified(icoVolume),
        ),
        Row(
            "every vertex split, on a cube", MeshDamage.splitSharedVertices(cube),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Solidified(cubeVolume),
        ),
        Row(
            "split vertices jittered below the weld tolerance",
            MeshDamage.jitter(MeshDamage.splitSharedVertices(ico), 1e-7),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Solidified(icoVolume),
        ),
        Row(
            "one face cracked open", MeshDamage.crackOneFace(ico, 0),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Solidified(icoVolume),
        ),
        Row(
            "a third of the faces flipped", MeshDamage.flipSubsetWinding(ico, 3),
            listOf(MeshDefectKind.INCONSISTENT_WINDING), Expect.Solidified(icoVolume),
        ),
        Row(
            "a third of a cube's faces flipped", MeshDamage.flipSubsetWinding(cube, 3),
            listOf(MeshDefectKind.INCONSISTENT_WINDING), Expect.Solidified(cubeVolume),
        ),
        Row(
            "wound entirely inside out", MeshDamage.invertWinding(ico),
            listOf(MeshDefectKind.INVERTED_ORIENTATION), Expect.Solidified(icoVolume),
        ),
        Row(
            "zero-area triangles inserted", MeshDamage.insertDegenerate(ico, 3),
            listOf(MeshDefectKind.DEGENERATE_FACE), Expect.Solidified(icoVolume),
        ),
        Row(
            "two disjoint components", MeshDamage.secondComponent(cube, ico),
            listOf(MeshDefectKind.MULTIPLE_COMPONENTS), Expect.Solidified(cubeVolume + icoVolume),
        ),

        // --- combinations: the order of the pipeline is what is under test here ----------------
        Row(
            "split vertices AND a hole",
            MeshDamage.deleteFaces(MeshDamage.splitSharedVertices(ico), 0, 1),
            listOf(MeshDefectKind.UNWELDED_VERTICES, MeshDefectKind.OPEN_BOUNDARY),
            Expect.Solidified(icoVolume),
        ),
        Row(
            "a hole AND flipped windings AND a duplicate",
            MeshDamage.duplicateFace(MeshDamage.flipSubsetWinding(MeshDamage.deleteFaces(ico, 0, 1), 3), 5),
            listOf(MeshDefectKind.OPEN_BOUNDARY, MeshDefectKind.INCONSISTENT_WINDING, MeshDefectKind.DUPLICATE_FACE),
            Expect.Solidified(icoVolume),
        ),

        // --- the ones it cannot fix, kept in the table on purpose ------------------------------
        Row(
            "a NaN position", nanVertex(ico),
            listOf(MeshDefectKind.NON_FINITE_POSITION),
            Expect.Unfixable(MeshDefectKind.NON_FINITE_POSITION),
        ),
        Row(
            "two closed spheres sharing a face", twoSpheresSharingAFace(),
            listOf(MeshDefectKind.NON_MANIFOLD_EDGE),
            Expect.Unfixable(MeshDefectKind.NON_MANIFOLD_EDGE),
        ),
    )

    // ==========================================================================================
    // The falsification baseline
    // ==========================================================================================

    /**
     * How much of the table the pipeline that already existed gets right on its own.
     *
     * This is here so that the claim "the new code was necessary" is a measurement rather than an
     * assertion in a commit message. The denominator is the meshes in [table] that **Manifold
     * actually rejects** — not the whole table, because four of the damaged inputs (an inverted
     * winding, zero-area triangles, two disjoint components and a NaN-free pristine mesh) are
     * accepted unchanged, and counting those as "repairs" would inflate both numbers.
     */
    @Test
    fun baselineWhatRepairedAlreadyFixes() {
        if (!manifold()) return
        val rows = table()
        val rejected = rows.filter { !it.mesh.isValidSolid() }
        val oldFixes = rejected.filter { runCatching { it.mesh.repaired().isValidSolid() }.getOrDefault(false) }
        val newFixes = rejected.filter { runCatching { it.mesh.repairToSolid().isSolid }.getOrDefault(false) }

        println("=== baseline: what TriMesh.repaired() already does ===")
        println("denominator: ${rejected.size} of ${rows.size} rows are rejected by Manifold as-is")
        for (r in rejected) {
            val old = runCatching { r.mesh.repaired().isValidSolid() }.getOrDefault(false)
            val new = runCatching { r.mesh.repairToSolid().isSolid }.getOrDefault(false)
            println("  %-46s repaired()=%-5s repairToSolid()=%-5s".format(r.name, old, new))
        }
        println("repaired() fixes ${oldFixes.size}/${rejected.size}; repairToSolid() fixes ${newFixes.size}/${rejected.size}")

        // Floors near the numbers above, so the headline cannot rot into narrative.
        assertTrue(rejected.size >= 12, "the table must actually contain broken meshes, got ${rejected.size}")
        assertTrue(
            oldFixes.size >= 6,
            "the pre-existing repaired() was measured at 6 of these; if it now fixes fewer, something regressed",
        )
        assertTrue(
            newFixes.size >= oldFixes.size + 5,
            "the new pipeline should fix at least five more than the old one; got ${newFixes.size} vs ${oldFixes.size}",
        )
        // And the two families the old pipeline provably could not touch.
        val holed = rows.first { it.name == "a cylinder with no caps" }.mesh
        assertTrue(!holed.repaired().isValidSolid(), "repaired() must still not fill holes — else this test is stale")
        assertTrue(holed.repairToSolid().isSolid, "the new pipeline must fill them")
        val duped = rows.first { it.name == "a duplicated face" }.mesh
        assertTrue(!duped.repaired().isValidSolid(), "repaired() must still not drop duplicate faces")
        assertTrue(duped.repairToSolid().isSolid, "the new pipeline must drop them")
    }

    // ==========================================================================================
    // Diagnosis: does it name the right defect?
    // ==========================================================================================

    /** Every row's diagnosis must name the defect that row was given. */
    @Test
    fun diagnosisNamesTheRightDefect() {
        val rows = table()
        var checked = 0
        for (r in rows) {
            val found = r.mesh.diagnose().defects.map { it.kind }.toSet()
            for (want in r.diagnosisMustName) {
                assertTrue(
                    want in found,
                    "'${r.name}': diagnosis should name $want, found $found\n${r.mesh.diagnose().report()}",
                )
                checked++
            }
            if (r.diagnosisMustName.isEmpty()) {
                assertTrue(
                    r.mesh.diagnose().isSolid,
                    "'${r.name}' is undamaged and should have no blocking defects, got ${r.mesh.diagnose().blockers.map { it.kind }}",
                )
            }
        }
        assertTrue(checked >= 20, "expected at least 20 defect expectations across the table, checked $checked")
    }

    /** The diagnosis must order blockers before warnings before information, worst first. */
    @Test
    fun diagnosisIsOrderedByWhatBlocks() {
        val d = MeshDamage.secondComponent(MeshDamage.deleteFaces(ico, 0, 1), cube).diagnose()
        val severities = d.defects.map { it.severity.ordinal }
        assertEquals(severities.sorted(), severities, "defects must be ordered by severity: ${d.defects}")
        assertTrue(d.blockers.isNotEmpty(), "a holed mesh has a blocking defect")
        assertEquals(
            MeshDefectKind.OPEN_BOUNDARY, d.blockers.last().kind,
            "the open boundary is the blocker here; got ${d.blockers.map { it.kind }}",
        )
        // MULTIPLE_COMPONENTS is real but informational — measured: Manifold accepts it.
        val info = d.defects.filter { it.severity == MeshDefectSeverity.INFORMATIONAL }.map { it.kind }
        assertTrue(MeshDefectKind.MULTIPLE_COMPONENTS in info, "components should be informational, got $info")
    }

    // ==========================================================================================
    // Repair: before / after, plus a real boolean
    // ==========================================================================================

    /**
     * The headline table. For every row: repair, then require a valid solid, the right volume, and
     * that two actual Manifold booleans against it partition that volume exactly.
     */
    @Test
    fun repairTableBeforeAndAfter() {
        if (!manifold()) return
        val rows = table()
        var solidified = 0
        var reportedUnfixable = 0
        println("=== repair table ===")
        println("%-46s %-8s %-8s %s".format("case", "before", "after", "what happened"))
        for (r in rows) {
            val before = r.mesh.isValidSolid()
            val result = runCatching { r.mesh.repairToSolid() }
            val fixed = result.getOrNull()
            val after = fixed?.isSolid ?: false
            println("%-46s %-8s %-8s %s".format(
                r.name, before, after,
                fixed?.let { f -> f.steps.filter { it.changed > 0 }.joinToString("; ") { it.name } } ?: "threw",
            ))

            when (val e = r.expect) {
                is Expect.Solidified -> {
                    val f = fixed ?: error("'${r.name}': repairToSolid threw ${result.exceptionOrNull()}")
                    assertTrue(f.isSolid, "'${r.name}' should repair to a solid, but:\n${f.report()}")
                    assertTrue(
                        f.mesh.isValidSolid(),
                        "'${r.name}': diagnosis says solid but Manifold disagrees\n${f.report()}",
                    )
                    f.mesh.toSolid().use { solid ->
                        assertEquals(
                            e.volume, solid.volume, e.tolerance,
                            "'${r.name}': repaired volume is wrong (a negative value means it came back inside out)",
                        )
                        assertBooleansPartition(r.name, solid)
                    }
                    solidified++
                }
                is Expect.Unfixable -> {
                    val f = fixed
                    if (f == null) {
                        // Throwing is an acceptable way to fail, as long as it is not silent.
                        reportedUnfixable++
                    } else {
                        assertTrue(
                            !f.isSolid,
                            "'${r.name}' is expected to be unfixable; if the pipeline now fixes it, " +
                                "change the expectation deliberately rather than deleting the row",
                        )
                        assertTrue(
                            e.kind in f.unfixed.map { it.kind },
                            "'${r.name}': the failure must be REPORTED as ${e.kind}, got ${f.unfixed.map { it.kind }}\n${f.report()}",
                        )
                        reportedUnfixable++
                    }
                }
            }
        }
        println("solidified $solidified of ${rows.size} rows; $reportedUnfixable rows correctly reported as unfixable")
        assertTrue(solidified >= 16, "expected at least 16 rows to become solids, got $solidified")
        assertTrue(reportedUnfixable >= 2, "the table must retain cases the pipeline cannot fix, got $reportedUnfixable")
    }

    /**
     * Two booleans against a repaired solid whose results must add back up to it.
     *
     * A half-space cut through the middle of the bounding box: the intersection and the difference
     * partition the volume, so `|a| == |a & h| + |a - h|` exactly. That is a much stronger statement
     * than "the boolean did not throw" — it fails if the mesh is self-intersecting in a way Manifold
     * papers over, and it fails if the winding is subtly wrong.
     */
    private fun assertBooleansPartition(name: String, solid: Solid) {
        val b = solid.bounds
        val spanX = (b.max.x - b.min.x).toDouble()
        val spanY = (b.max.y - b.min.y).toDouble()
        val spanZ = (b.max.z - b.min.z).toDouble()
        val big = maxOf(spanX, spanY, spanZ) * 4.0 + 1.0
        val midZ = (b.min.z + b.max.z) / 2.0
        // A box occupying everything above midZ.
        Solid.cube(big, big, big, center = true)
            .translated((b.min.x + b.max.x) / 2.0, (b.min.y + b.max.y) / 2.0, midZ + big / 2.0)
            .use { half ->
                (solid and half).use { upper ->
                    (solid - half).use { lower ->
                        assertEquals(
                            solid.volume, upper.volume + lower.volume, abs(solid.volume) * 1e-6 + 1e-9,
                            "'$name': a half-space cut must partition the volume exactly",
                        )
                        assertTrue(
                            upper.volume > 0.01 * solid.volume,
                            "'$name': the cut must actually remove something — upper is ${upper.volume} " +
                                "of ${solid.volume}, so this assertion is not exercising a boolean",
                        )
                    }
                }
            }
    }

    // ==========================================================================================
    // Hole filling on its own
    // ==========================================================================================

    /** A flat hole fills exactly, and the fill is reported as trustworthy. */
    @Test
    fun aFlatHoleFillsExactly() {
        val holed = MeshDamage.deleteFaces(cube, 0, 2) // one whole face of the cube (two triangles)
        val d = holed.diagnose()
        assertEquals(1, d.boundaryLoops.size, "removing both triangles of one cube face leaves one loop")
        assertEquals(4, d.boundaryLoops[0].edgeCount, "that loop is the square face's four edges")
        assertTrue(d.boundaryLoops[0].isPlanar, "a cube face is flat; planarity was ${d.boundaryLoops[0].planarity}")

        val filled = holed.fillHoles()
        assertEquals(1, filled.filled, "one hole should be filled")
        assertEquals(HoleFillMethod.EAR_CLIP, filled.fills[0].method, "a 4-gon ear-clips")
        assertTrue(filled.fills.none { it.isSuspect }, "a flat square cap is not suspect")
        assertTrue(filled.mesh.isWatertight(), "the cube should be closed again")
        assertEquals(
            1.0, filled.mesh.signedVolume(), 1e-6,
            "the cap restores exactly the unit cube — and a POSITIVE volume, i.e. the cap is wound outward",
        )
    }

    /** A three-edge hole is closed by one triangle, the only fill that is exact by construction. */
    @Test
    fun aTriangularHoleTakesOneTriangle() {
        val holed = MeshDamage.deleteFaces(ico, 0, 1)
        val filled = holed.fillHoles()
        assertEquals(1, filled.fills.size)
        assertEquals(HoleFillMethod.SINGLE_TRIANGLE, filled.fills[0].method)
        assertEquals(1, filled.fills[0].trianglesAdded)
        assertEquals(ico.triangleCount, filled.mesh.triangleCount, "back to the original triangle count")
        assertEquals(icoVolume, filled.mesh.signedVolume(), 1e-9, "and the original volume, sign included")
    }

    /**
     * The documented failure: a deliberately non-planar hole is filled, is a valid solid, and is
     * **flagged**, because its volume is wrong.
     *
     * This is the assertion that keeps [TriMesh.fillHoles]'s KDoc honest. The cap is flat, the rim is
     * not, so the enclosed volume is not the volume of the shape the rim came from — and the only
     * defensible behaviour is to say so rather than to pretend.
     */
    @Test
    fun aNonPlanarHoleIsFilledAndFlagged() {
        // Take a cylinder's open rims and push alternate vertices along each rim's OWN normal: a
        // zig-zag crown. Along the rim's normal, not along z — cylinderMesh's axis is y, so displacing
        // z moves the rim vertices *within* their own plane and the loop stays perfectly flat. That
        // was a real bug in this test, and it is exactly the kind of thing planarity is meant to catch.
        val open = cylinderMesh(16, 0.5, 1.0, caps = false)
        val loops = open.diagnose().boundaryLoops
        assertEquals(2, loops.size, "the uncapped cylinder has two rims")
        assertTrue(loops.all { it.isPlanar }, "and both rims start out flat")
        val pos = open.positions.copyOf()
        for (loop in loops) {
            val n = loop.normal
            for ((i, v) in loop.vertices.withIndex()) {
                if (i % 2 != 0) continue
                pos[v * 3] += n.x * 0.35f; pos[v * 3 + 1] += n.y * 0.35f; pos[v * 3 + 2] += n.z * 0.35f
            }
        }
        val wavy = TriMesh(pos, open.indices)

        val d = wavy.diagnose()
        val worst = d.boundaryLoops.maxByOrNull { it.planarity }!!
        assertTrue(
            worst.planarity > 0.05,
            "the crown should read as non-planar; planarity was ${worst.planarity}",
        )
        assertTrue(!worst.isPlanar, "and isPlanar should therefore be false")

        val filled = wavy.fillHoles()
        assertTrue(filled.suspect.isNotEmpty(), "a non-planar fill must be reported as suspect")
        assertTrue(
            filled.suspect.all { it.note != null || !it.loop.isPlanar },
            "and must carry a reason: ${filled.suspect.map { it.note }}",
        )
        println("non-planar fill: ${filled.fills.joinToString("; ")}")
    }

    /** A hole bigger than the caller's limit is left open, and said to be left open. */
    @Test
    fun anOversizeHoleIsRefusedNotSilentlyCapped() {
        val open = cylinderMesh(16, 0.5, 1.0, caps = false)
        val filled = open.fillHoles(maxEdges = 4)
        assertEquals(2, filled.fills.size, "two rims")
        assertEquals(2, filled.skipped, "both are 16 edges, past the limit of 4")
        assertTrue(filled.fills.all { it.method == HoleFillMethod.SKIPPED_TOO_LARGE })
        assertTrue(!filled.mesh.isWatertight(), "and the mesh is honestly still open")
    }

    // ==========================================================================================
    // The individual new repairs
    // ==========================================================================================

    /** A duplicated face is removed; the geometry is untouched. */
    @Test
    fun duplicateFacesAreRemoved() {
        val dup = MeshDamage.duplicateFace(ico, 0)
        assertEquals(ico.triangleCount + 1, dup.triangleCount)
        assertEquals(3, dup.diagnose().nonManifoldEdges.size, "one duplicate makes three edges non-manifold")
        val cleaned = dup.removeDuplicateFaces()
        assertEquals(ico.triangleCount, cleaned.triangleCount, "back to the original count")
        assertTrue(cleaned.diagnose().nonManifoldEdges.isEmpty())
        assertEquals(icoVolume, cleaned.signedVolume(), 1e-9)
    }

    /** A back-to-back pair of faces encloses nothing, so both go. */
    @Test
    fun aZeroThicknessSheetCancels() {
        val sheet = TriMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            intArrayOf(0, 1, 2, 0, 2, 1),
        )
        assertEquals(2, sheet.triangleCount)
        val cleaned = sheet.removeDuplicateFaces()
        assertEquals(0, cleaned.triangleCount, "two opposite copies of one face enclose nothing; both should go")
    }

    /** A dangling fin is removed; two properly closed sheets meeting on an edge are not. */
    @Test
    fun finsGoAndRealSeamsStay() {
        val finned = MeshDamage.nonManifoldFin(ico, 0)
        assertEquals(1, finned.diagnose().nonManifoldEdges.size)
        val cleaned = finned.removeNonManifoldFins()
        assertEquals(ico.triangleCount, cleaned.triangleCount, "exactly the fin should go")
        assertTrue(cleaned.diagnose().nonManifoldEdges.isEmpty())

        // The conservative half: a shared face between two closed spheres has no boundary edge, so
        // the fin rule must leave it alone rather than unzip the model.
        val shared = twoSpheresSharingAFace()
        val before = shared.diagnose().nonManifoldEdges.size
        assertTrue(before > 0, "the fixture must actually be non-manifold, got $before")
        val untouched = shared.removeNonManifoldFins()
        assertEquals(
            shared.triangleCount, untouched.triangleCount,
            "no triangle here is a dangling flap, so nothing should be deleted",
        )
    }

    // ==========================================================================================
    // The one real asset
    // ==========================================================================================

    /**
     * `ToyCar.glb` — a real, multi-part, exported model, run end to end and reported honestly.
     *
     * This is the case that finds the assumptions. Nothing here asserts that the car becomes a solid;
     * it asserts that the pipeline **makes measurable progress and describes its own failure**, which
     * is the contract. The numbers it prints are the interesting output of this whole file.
     */
    @Test
    fun theRealAssetIsHandledHonestly() {
        if (!manifold()) return
        val path = File("public/docs/assets/fabricate_demo/ToyCar.glb")
        if (!path.exists()) { println("ToyCar.glb not present at ${path.absolutePath} — skipping"); return }
        val car = meshFromAnyFile(path.path)
        println("=== ToyCar.glb ===")
        val before = car.diagnose()
        println(before.report())

        val result = car.repairToSolid()
        println(result.report())

        // Progress, asserted rather than admired.
        assertTrue(
            before.blockers.isNotEmpty(),
            "the premise of this test is that a real exported asset is not a solid; if it is, say so",
        )
        assertTrue(
            before.boundaryLoops.size > 2000,
            "ToyCar was measured at 2782 open loops before repair, got ${before.boundaryLoops.size}",
        )
        assertTrue(
            result.mesh.triangleCount >= car.triangleCount * 0.9,
            "repair must not throw away the model: ${car.triangleCount} -> ${result.mesh.triangleCount}",
        )
        assertEquals(
            0, result.after.boundaryLoops.size,
            "every hole should be closed; ${before.boundaryLoops.size} -> ${result.after.boundaryLoops.size}",
        )
        assertTrue(result.isSolid, "ToyCar should repair to a solid:\n${result.report()}")
        assertTrue(result.mesh.isValidSolid(), "and Manifold should agree")

        // It IS a solid and you CAN cut it — but the volume is not to be trusted, and saying only the
        // first half of that would be the overclaim this file is written against. Nine of the 84 caps
        // are flagged: one is a 335-edge centroid fan, which is a lid the size of a body panel.
        result.mesh.toSolid().use { solid ->
            assertBooleansPartition("ToyCar", solid)
            println(
                "ToyCar repaired: volume=${solid.volume}, ${result.mesh.triangleCount} triangles, " +
                    "${result.after.components.size} components, ${result.suspectFills.size} suspect caps " +
                    "of ${result.holeFills.size} — the volume is a NUMBER, not an ANSWER, until those are looked at"
            )
        }
        assertTrue(
            result.suspectFills.isNotEmpty(),
            "a 108k-triangle multi-part export that produced zero suspect fills would mean the flagging " +
                "is broken, not that the model was clean",
        )
        println(
            "ToyCar: ${before.blockers.size} blockers -> ${result.unfixed.size}; " +
                "${before.boundaryLoops.size} loops -> ${result.after.boundaryLoops.size}; " +
                "solid=${result.isSolid}; suspect caps=${result.suspectFills.size}"
        )
    }

    // ==========================================================================================
    // Fixtures and helpers
    // ==========================================================================================

    /** [mesh] with vertex 0 moved to `NaN` — the one defect nothing can repair. */
    private fun nanVertex(mesh: TriMesh): TriMesh {
        val pos = mesh.positions.copyOf()
        pos[0] = Float.NaN
        return TriMesh(pos, mesh.indices)
    }

    /**
     * Two closed tetrahedra glued along a shared face — non-manifold with **no boundary edge
     * anywhere**, so the conservative fin rule correctly refuses to touch it.
     *
     * Built by hand rather than damaged, because this is the shape of defect that needs a modelling
     * decision (which sheet survives?) rather than a repair, and the test's job is to prove the
     * pipeline knows the difference.
     */
    private fun twoSpheresSharingAFace(): TriMesh {
        // A shared triangle (0,1,2) with an apex above (3) and below (4).
        val pos = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
            0.3f, 0.3f, 1f,
            0.3f, 0.3f, -1f,
        )
        val idx = intArrayOf(
            0, 2, 1, // the shared face
            0, 1, 3, 1, 2, 3, 2, 0, 3, // upper tetrahedron
            0, 1, 4, 1, 2, 4, 2, 0, 4, // lower tetrahedron
        )
        return TriMesh(pos, idx)
    }

    private companion object {
        private var announced = false

        /** `true` when Manifold is installed; prints once when it is not, so a skip is visible. */
        fun manifold(): Boolean {
            if (ManifoldLibrary.isAvailable) return true
            if (!announced) {
                announced = true
                println("MeshRepairTest: skipping the Manifold rows — ${ManifoldLibrary.describe()}")
            }
            return false
        }
    }
}
