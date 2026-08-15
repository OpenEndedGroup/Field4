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

    /**
     * What a row of the table claims should happen.
     *
     * The distinction between [Faithful] and [ValidButWrong] is the whole point of this file's second
     * draft. `isValidSolid()` is not success: a hole filled with a flat cap seals the mesh, satisfies
     * Manifold, lets every boolean through — and can be a completely different shape. Only comparison
     * against the **undamaged original** catches that, and every row here has one.
     */
    private sealed interface Expect {
        /**
         * Repair should give a valid solid that is *also* the original shape: volume and surface area
         * within [tolerance] of the undamaged mesh, and the same genus.
         */
        class Faithful(val tolerance: Double = 0.005) : Expect

        /**
         * Repair should give a valid solid that is measurably **not** the original shape.
         *
         * These rows are not failures of the test, they are the honest product boundary, and deleting
         * them would turn the headline into a lie. [minVolumeError] asserts a floor on how wrong it
         * is, so a row cannot quietly become faithful without somebody noticing.
         */
        class ValidButWrong(val minVolumeError: Double, val why: String) : Expect

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
        /** The undamaged mesh this row was made from — the ground truth a repair is judged against. */
        val truth: TriMesh? = null,
    )

    private val cube = cubeMesh(1.0)
    private val ico = icosphereMesh(2, 1.0)
    private val ico3 = icosphereMesh(3, 1.0)
    private val torus = torusMesh(24, 12)

    private val cubeVolume = 1.0
    private val icoVolume = ico.signedVolume()
    private val torusVolume = torus.signedVolume()

    private fun table(): List<Row> = listOf(
        // --- undamaged controls: the pipeline must not break what is already fine ------------
        Row("pristine cube", cube, emptyList(), Expect.Faithful(), cube),
        Row("pristine icosphere", ico, emptyList(), Expect.Faithful(), ico),
        Row("pristine torus", torus, emptyList(), Expect.Faithful(), torus),

        // --- FLAT holes. Every one of these caps exactly, and that is the point of saying so:
        //     a planar rim is the easy case, and a table made only of these measures nothing.
        Row(
            "flat: one face off a cube", MeshDamage.deleteFaces(cube, 0, 1),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Faithful(), cube,
        ),
        Row(
            "flat: a whole face off a cube", MeshDamage.deleteFaces(cube, 0, 2),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Faithful(), cube,
        ),
        Row(
            "flat: one face off an icosphere", MeshDamage.deleteFaces(ico, 0, 1),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Faithful(), ico,
        ),
        Row(
            "flat: six faces off an icosphere", MeshDamage.deleteFaces(ico, 0, 6),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Faithful(), ico,
        ),
        Row(
            "flat: a cylinder with no caps", cylinderMesh(16, 0.5, 1.0, caps = false),
            listOf(MeshDefectKind.OPEN_BOUNDARY), Expect.Faithful(), cylinderMesh(16, 0.5, 1.0, caps = true),
        ),

        // --- CURVED and NON-PLANAR holes: the cases a fan fill cannot get right ----------------
        Row(
            "curved: a hole across a torus's tube", MeshDamage.deleteFaces(torus, 0, 16),
            listOf(MeshDefectKind.OPEN_BOUNDARY),
            Expect.ValidButWrong(
                0.005,
                "the rim wraps a saddle — 43% of its own diameter out of plane — so ear clipping " +
                    "stalls and the centroid fan bulges through the tube",
            ),
            torus,
        ),
        Row(
            "curved: a hole that severs a torus's tube", MeshDamage.deleteFaces(torus, 0, 24),
            listOf(MeshDefectKind.OPEN_BOUNDARY),
            Expect.ValidButWrong(
                0.02,
                "the worst case in this file: two PERFECTLY FLAT rims, cleanly ear-clipped, no " +
                    "suspicion from planarity or from the fan — and the donut comes back with genus 0. " +
                    "Nothing local to a hole can tell 'this end was flat' from 'the model continued'.",
            ),
            torus,
        ),
        Row(
            "curved: a wide hole over an icosphere", MeshDamage.deleteFaces(ico3, 0, 80),
            listOf(MeshDefectKind.OPEN_BOUNDARY),
            Expect.ValidButWrong(0.005, "a flat lid chords off a spherical cap"),
            ico3,
        ),
        Row(
            "non-planar: a crowned cylinder rim", crownedCylinder(),
            listOf(MeshDefectKind.OPEN_BOUNDARY),
            Expect.ValidButWrong(0.10, "alternate rim vertices lifted along the rim's own normal"),
            cylinderMesh(16, 0.5, 1.0, caps = true),
        ),

        // --- non-manifold edges: removeDuplicateFaces / removeNonManifoldFins are the new code -
        Row(
            "a duplicated face", MeshDamage.duplicateFace(ico, 0),
            listOf(MeshDefectKind.DUPLICATE_FACE, MeshDefectKind.NON_MANIFOLD_EDGE),
            Expect.Faithful(), ico,
        ),
        Row(
            "a dangling non-manifold fin", MeshDamage.nonManifoldFin(ico, 0),
            listOf(MeshDefectKind.NON_MANIFOLD_EDGE), Expect.Faithful(), ico,
        ),

        // --- what the OLD pipeline already handled; these must keep working --------------------
        Row(
            "every vertex split (an STL)", MeshDamage.splitSharedVertices(ico),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Faithful(), ico,
        ),
        Row(
            "every vertex split, on a cube", MeshDamage.splitSharedVertices(cube),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Faithful(), cube,
        ),
        Row(
            "split vertices jittered below the weld tolerance",
            MeshDamage.jitter(MeshDamage.splitSharedVertices(ico), 1e-7),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Faithful(), ico,
        ),
        Row(
            "one face cracked open", MeshDamage.crackOneFace(ico, 0),
            listOf(MeshDefectKind.UNWELDED_VERTICES), Expect.Faithful(), ico,
        ),
        Row(
            "a third of the faces flipped", MeshDamage.flipSubsetWinding(ico, 3),
            listOf(MeshDefectKind.INCONSISTENT_WINDING), Expect.Faithful(), ico,
        ),
        Row(
            "a third of a cube's faces flipped", MeshDamage.flipSubsetWinding(cube, 3),
            listOf(MeshDefectKind.INCONSISTENT_WINDING), Expect.Faithful(), cube,
        ),
        Row(
            "wound entirely inside out", MeshDamage.invertWinding(ico),
            listOf(MeshDefectKind.INVERTED_ORIENTATION), Expect.Faithful(), ico,
        ),
        Row(
            "zero-area triangles inserted", MeshDamage.insertDegenerate(ico, 3),
            listOf(MeshDefectKind.DEGENERATE_FACE), Expect.Faithful(), ico,
        ),
        Row(
            "two disjoint components", MeshDamage.secondComponent(cube, ico),
            listOf(MeshDefectKind.MULTIPLE_COMPONENTS), Expect.Faithful(),
            MeshDamage.secondComponent(cube, ico),
        ),

        // --- combinations: the order of the pipeline is what is under test here ----------------
        Row(
            "split vertices AND a hole",
            MeshDamage.deleteFaces(MeshDamage.splitSharedVertices(ico), 0, 1),
            listOf(MeshDefectKind.UNWELDED_VERTICES, MeshDefectKind.OPEN_BOUNDARY),
            Expect.Faithful(), ico,
        ),
        Row(
            "a hole AND flipped windings AND a duplicate",
            MeshDamage.duplicateFace(MeshDamage.flipSubsetWinding(MeshDamage.deleteFaces(ico, 0, 1), 3), 5),
            listOf(MeshDefectKind.OPEN_BOUNDARY, MeshDefectKind.INCONSISTENT_WINDING, MeshDefectKind.DUPLICATE_FACE),
            Expect.Faithful(), ico,
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
        val holed = rows.first { it.name == "flat: a cylinder with no caps" }.mesh
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
     * The headline table: for every row, repair, then judge it against the **undamaged original**.
     *
     * `isValidSolid()` is the weak half of the claim and it is reported separately from the strong
     * half. A repaired mesh is only called *faithful* when its volume and surface area are within the
     * row's tolerance of the mesh it was damaged from and its genus is unchanged. Anything that is a
     * valid solid but fails that is printed as `VALID-BUT-WRONG` with its actual error, because those
     * are two different products and only the first deserves the word "repair".
     */
    @Test
    fun repairTableAgainstGroundTruth() {
        if (!manifold()) return
        val rows = table()
        var faithful = 0
        var validButWrong = 0
        var reportedUnfixable = 0
        println("=== repair table, judged against the undamaged original ===")
        println("%-44s %-6s %-6s %9s %9s %-9s %s".format(
            "case", "before", "after", "volErr", "areaErr", "genus", "verdict"))
        for (r in rows) {
            val before = r.mesh.isValidSolid()
            val result = runCatching { r.mesh.repairToSolid() }
            val fixed = result.getOrNull()
            val after = fixed?.isSolid ?: false
            val vErr = if (fixed == null || r.truth == null) Double.NaN
                else relativeError(r.truth.signedVolume(), fixed.mesh.signedVolume())
            val aErr = if (fixed == null || r.truth == null) Double.NaN
                else relativeError(r.truth.surfaceArea(), fixed.mesh.surfaceArea())
            val genusPair = if (fixed == null || r.truth == null) "-"
                else "${r.truth.genus()}->${fixed.mesh.genus()}"

            when (val e = r.expect) {
                is Expect.Faithful -> {
                    val f = fixed ?: error("'${r.name}': repairToSolid threw ${result.exceptionOrNull()}")
                    val truth = r.truth ?: error("'${r.name}': a Faithful row needs ground truth")
                    println("%-44s %-6s %-6s %9.5f %9.5f %-9s %s".format(
                        r.name, before, after, vErr, aErr, genusPair, "FAITHFUL"))
                    assertTrue(f.isSolid, "'${r.name}' should repair to a solid, but:\n${f.report()}")
                    assertTrue(f.mesh.isValidSolid(), "'${r.name}': diagnosis says solid, Manifold disagrees")
                    assertTrue(
                        vErr <= e.tolerance,
                        "'${r.name}': valid but the WRONG SHAPE — volume is off by ${"%.4f".format(vErr)}, " +
                            "tolerance ${e.tolerance}. Either the repair regressed or this row belongs in " +
                            "ValidButWrong; do not widen the tolerance to make it pass.",
                    )
                    assertTrue(
                        aErr <= e.tolerance * 4,
                        "'${r.name}': surface area is off by ${"%.4f".format(aErr)}",
                    )
                    assertEquals(
                        truth.genus(), f.mesh.genus(),
                        "'${r.name}': the repair changed the genus, which means it changed the shape",
                    )
                    f.mesh.toSolid().use { solid ->
                        assertEquals(
                            truth.signedVolume(), solid.volume, kotlin.math.abs(truth.signedVolume()) * e.tolerance + 1e-9,
                            "'${r.name}': Manifold's own volume must match the original too " +
                                "(a negative value means it came back inside out)",
                        )
                        assertBooleansPartition(r.name, solid)
                    }
                    faithful++
                }
                is Expect.ValidButWrong -> {
                    val f = fixed ?: error("'${r.name}': repairToSolid threw ${result.exceptionOrNull()}")
                    println("%-44s %-6s %-6s %9.5f %9.5f %-9s %s".format(
                        r.name, before, after, vErr, aErr, genusPair, "VALID-BUT-WRONG"))
                    assertTrue(f.isSolid, "'${r.name}' is expected to be valid, just wrong:\n${f.report()}")
                    assertTrue(
                        vErr >= e.minVolumeError,
                        "'${r.name}': expected a measurably wrong shape (>= ${e.minVolumeError}) but the " +
                            "volume error is only ${"%.5f".format(vErr)}. If the filler genuinely got " +
                            "better, move this row to Faithful deliberately — ${e.why}",
                    )
                    // It still has to be a usable solid; being wrong is not licence to be broken.
                    f.mesh.toSolid().use { solid -> assertBooleansPartition(r.name, solid) }
                    validButWrong++
                }
                is Expect.Unfixable -> {
                    println("%-44s %-6s %-6s %9s %9s %-9s %s".format(
                        r.name, before, after, "-", "-", "-", "UNFIXABLE (reported)"))
                    if (fixed == null) {
                        reportedUnfixable++
                    } else {
                        assertTrue(
                            !fixed.isSolid,
                            "'${r.name}' is expected to be unfixable; if the pipeline now fixes it, " +
                                "change the expectation deliberately rather than deleting the row",
                        )
                        assertTrue(
                            e.kind in fixed.unfixed.map { it.kind },
                            "'${r.name}': the failure must be REPORTED as ${e.kind}, " +
                                "got ${fixed.unfixed.map { it.kind }}\n${fixed.report()}",
                        )
                        reportedUnfixable++
                    }
                }
            }
        }
        // The headline the docs quote. The denominator is the meshes Manifold actually rejects, and
        // the numerator is split, because "valid" and "the shape you had" are different claims.
        val rejectedRows = rows.filter { !it.mesh.isValidSolid() }
        val rejectedFaithful = rejectedRows.count { r ->
            r.expect is Expect.Faithful && runCatching { r.mesh.repairToSolid().isSolid }.getOrDefault(false)
        }
        val rejectedWrong = rejectedRows.count { it.expect is Expect.ValidButWrong }
        val rejectedUnfixed = rejectedRows.count { it.expect is Expect.Unfixable }
        println()
        println("HEADLINE — denominator is the ${rejectedRows.size} of ${rows.size} rows Manifold rejects as-is:")
        println("  become a valid solid:      ${rejectedFaithful + rejectedWrong} of ${rejectedRows.size}")
        println("    ... and FAITHFUL:        $rejectedFaithful  (volume, area and genus match the undamaged original)")
        println("    ... but VALID-BUT-WRONG: $rejectedWrong  (a solid you can cut, and not the shape you had)")
        println("  correctly reported broken: $rejectedUnfixed")
        println("across all ${rows.size} rows (including the 6 Manifold already accepted): " +
            "$faithful faithful, $validButWrong valid-but-wrong, $reportedUnfixable unfixable")

        assertEquals(
            rejectedRows.size, rejectedFaithful + rejectedWrong + rejectedUnfixed,
            "every rejected row must land in exactly one bucket, or the headline is not a partition",
        )
        assertTrue(
            rejectedFaithful >= 15,
            "expected at least 15 of the rejected rows to come back faithful, got $rejectedFaithful",
        )
        assertTrue(faithful >= 18, "expected at least 18 faithful rows overall, got $faithful")
        assertTrue(
            validButWrong >= 4,
            "the table must keep cases the filler gets geometrically wrong, got $validButWrong — " +
                "a table of only flat holes measures the easy case and inflates the headline",
        )
        assertTrue(reportedUnfixable >= 2, "the table must retain cases the pipeline cannot fix at all")
    }

    /**
     * The flat-hole rows really are flat, asserted — so nobody can later claim the table was hard.
     *
     * This exists because the first draft of this file contained *only* flat holes: every one had a
     * boundary planarity of 0.0000 and a post-repair volume error of 0.00000, which made a fan fill
     * trivially correct and made "14 of 16" a measurement of the easy case.
     */
    @Test
    fun theEasyRowsAreEasyAndTheHardRowsAreHard() {
        val flat = listOf(
            MeshDamage.deleteFaces(cube, 0, 2),
            MeshDamage.deleteFaces(ico, 0, 1),
            cylinderMesh(16, 0.5, 1.0, caps = false),
        )
        for (m in flat) {
            val worst = m.diagnose().boundaryLoops.maxOf { it.planarity }
            assertTrue(worst < 0.01, "this row is supposed to be a FLAT hole, planarity was $worst")
        }
        val hard = mapOf(
            "torus tube" to MeshDamage.deleteFaces(torus, 0, 16),
            "crowned rim" to crownedCylinder(),
        )
        for ((name, m) in hard) {
            val worst = m.diagnose().boundaryLoops.maxOf { it.planarity }
            assertTrue(worst > 0.10, "'$name' is supposed to be a NON-PLANAR hole, planarity was $worst")
        }
        // And the one that is flat and still wrong, which is the case no planarity check can catch.
        val severed = MeshDamage.deleteFaces(torus, 0, 24)
        val loops = severed.diagnose().boundaryLoops
        assertTrue(
            loops.all { it.isPlanar },
            "the severed torus's rims are flat — that is precisely why it is dangerous: ${loops.map { it.planarity }}",
        )
    }

    /**
     * **No local signal can tell a good flat cap from a bad one** — proved by a pair of
     * counterexamples that point opposite ways.
     *
     * This is the sharpest negative result in the feature, and it is a test rather than a comment
     * because it is the thing most likely to be "fixed" later by somebody adding a plausible
     * heuristic. A cap-area threshold was tried during development and removed:
     *
     *  - the **uncapped cylinder** gets two flat, cleanly ear-clipped caps that are a *large* share of
     *    the surface, and the volume comes out **exactly** right;
     *  - the **severed torus** gets two flat, cleanly ear-clipped caps that are a *small* share of the
     *    surface, and the shape comes back with the wrong genus.
     *
     * Every local property that could be measured — planarity, method, edge count, cap area — is
     * equal-or-better on the wrong one. Only the undamaged original separates them, and at run time
     * nobody has it.
     */
    @Test
    fun noLocalSignalSeparatesAGoodFlatCapFromABadOne() {
        val goodBroken = cylinderMesh(16, 0.5, 1.0, caps = false)
        val goodTruth = cylinderMesh(16, 0.5, 1.0, caps = true)
        val badBroken = MeshDamage.deleteFaces(torus, 0, 24)

        val good = goodBroken.fillHoles()
        val bad = badBroken.fillHoles()

        // Both are geometrically impeccable by every local measure.
        for ((label, r) in listOf("cylinder" to good, "severed torus" to bad)) {
            assertTrue(r.fills.all { it.method == HoleFillMethod.EAR_CLIP }, "$label: clean ear clip, no fan")
            assertTrue(r.fills.all { it.loop.isPlanar }, "$label: perfectly flat rims")
        }

        // And the WRONG one has the SMALLER caps, so size cannot be the discriminator.
        val goodShare = good.fills.maxOf { it.capAreaFraction }
        val badShare = bad.fills.maxOf { it.capAreaFraction }
        println("cap share of surface — correct cylinder %.3f, wrong torus %.3f".format(goodShare, badShare))
        assertTrue(
            badShare < goodShare,
            "the whole point: the wrong fill's caps ($badShare) are smaller than the right fill's " +
                "($goodShare), so no size threshold can separate them",
        )

        // Ground truth, and only ground truth, tells them apart.
        val goodErr = relativeError(goodTruth.signedVolume(), good.mesh.signedVolume())
        val badErr = relativeError(torus.signedVolume(), bad.mesh.repairToSolid().mesh.signedVolume())
        assertTrue(goodErr < 1e-6, "the cylinder's caps are exact, error was $goodErr")
        assertTrue(badErr > 0.02, "the torus's caps are not, error was $badErr")
        assertEquals(1, torus.genus(), "the original is a donut")
        assertEquals(
            0, bad.mesh.repairToSolid().mesh.genus(),
            "and the repaired one is not — the clearest possible statement of 'valid and wrong'",
        )
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
        val wavy = crownedCylinder()
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
    // The error message
    // ==========================================================================================

    /**
     * `toSolid()` on a broken mesh must say **what is wrong**, not what the reader might try.
     *
     * The old message named `TriMesh.weld` and `TriMesh.fixWinding` as things to attempt — neither of
     * which fixes a hole, which is the single most common reason a mesh off disk is refused. The
     * message now carries the diagnosis, so it names the defect and the call that addresses it.
     */
    @Test
    fun theRefusalNamesTheDefect() {
        if (!manifold()) return
        val holed = MeshDamage.deleteFaces(cube, 0, 2)
        val thrown = runCatching { holed.toSolid() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "toSolid should refuse a holed cube, got $thrown")
        val message = thrown.message ?: ""
        println("--- toSolid() refusal message ---\n$message")
        assertTrue(
            MeshDefectKind.OPEN_BOUNDARY.name in message,
            "the message must name the actual defect; got:\n$message",
        )
        assertTrue("boundary edges" in message, "and describe it in words: \n$message")
        assertTrue("repairToSolid" in message, "and point at the fix: \n$message")
        assertTrue(
            message.lines().size > 4,
            "a one-line refusal cannot carry a diagnosis; got ${message.lines().size} lines",
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

    /** Error between two measurements, relative to the larger — the scale-free comparison. */
    private fun relativeError(a: Double, b: Double): Double {
        val scale = maxOf(kotlin.math.abs(a), kotlin.math.abs(b))
        return if (scale == 0.0) 0.0 else kotlin.math.abs(a - b) / scale
    }

    /**
     * An uncapped cylinder whose rims have had alternate vertices lifted **along each rim's own
     * normal** — a zig-zag crown, and a genuinely non-planar boundary loop.
     *
     * Along the rim's normal, not along z: `cylinderMesh`'s axis is y, so displacing z moves the rim
     * vertices *within* their own plane and leaves the loop perfectly flat. That was a real bug in an
     * earlier version of this file, and it is exactly the sort of thing planarity is meant to catch.
     */
    private fun crownedCylinder(): TriMesh {
        val open = cylinderMesh(16, 0.5, 1.0, caps = false)
        val pos = open.positions.copyOf()
        for (loop in open.diagnose().boundaryLoops) {
            val n = loop.normal
            for ((i, v) in loop.vertices.withIndex()) {
                if (i % 2 != 0) continue
                pos[v * 3] += n.x * 0.35f; pos[v * 3 + 1] += n.y * 0.35f; pos[v * 3 + 2] += n.z * 0.35f
            }
        }
        return TriMesh(pos, open.indices)
    }

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
