package afield.graphics

import afield.stdlib.Vec3
import org.graalvm.polyglot.Value
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The three.js viewer's `tessellateCoplanarGroups`, **executed**, against the Kotlin it copies.
 *
 * The test this supersedes — `ThreeJsEditableFillParityTest` — computed both sides of its assertion in
 * Kotlin (`buildThreeGeometryPayload(editable = true)` versus `editable = false`), so the JavaScript
 * never ran and the guard guarded nothing. It could not have caught either of the two defects the
 * human found in this neighbourhood within hours of the seam landing. That test stays, because
 * `meta.triangles` is worth pinning on its own; this one is the differential.
 *
 * Both sides get the same rings, byte-for-byte: the corpus is built as `Float`, and each `Float` is
 * widened to `Double` (exactly) on its way into JSON, so the only remaining arithmetic difference is
 * that the JavaScript works in float64 where [PlanarTessellation] works in float32. The corpus keeps
 * clear of the tolerance boundaries where that could decide an answer; the boundary itself is
 * [CoplanarityToleranceTest]'s job.
 *
 * See [ViewerJavaScript] for the harness, and for why the shimmed `THREE.ShapeUtils.triangulateShape`
 * is [FloatEarcut].
 */
class ThreeJsViewerTessellationParityTest {

    // ------------------------------------------------------------------------------------- corpus

    /**
     * A named ring set. Every case here is a shape that actually bit something, or a boundary of one.
     *
     * @param rotatable false for the handful of cases whose *nesting* is decided at a boundary — a
     * vertex lying exactly on another ring's edge, or two rings with exactly equal projected area.
     * Those answers are correct and stable in one orientation, and a genuine coin-flip under rotation
     * between the JavaScript's float64 and [PlanarTessellation]'s float32. Rotating them would make
     * this test flaky for a reason that is not a defect in either implementation, so they are pinned
     * upright and said out loud rather than quietly dropped.
     */
    private data class Case(val name: String, val rings: List<List<Vec3>>, val rotatable: Boolean = true)

    private fun ring(vararg points: Vec3): List<Vec3> = points.toList()

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, z: Float = 0f): List<Vec3> =
        ring(Vec3(x0, y0, z), Vec3(x1, y0, z), Vec3(x1, y1, z), Vec3(x0, y1, z))

    /** Reversed: same square, opposite winding, so its Newell normal points the other way. */
    private fun quadReversed(x0: Float, y0: Float, x1: Float, y1: Float, z: Float = 0f): List<Vec3> =
        quad(x0, y0, x1, y1, z).reversed()

    /** One unit square with a square hole, on the plane spanned by [u] and [v] through [origin]. */
    private fun holedFace(origin: Vec3, u: Vec3, v: Vec3): List<List<Vec3>> {
        fun at(a: Float, b: Float) = Vec3(
            origin.x + u.x * a + v.x * b,
            origin.y + u.y * a + v.y * b,
            origin.z + u.z * a + v.z * b,
        )
        return listOf(
            ring(at(0f, 0f), at(1f, 0f), at(1f, 1f), at(0f, 1f)),
            ring(at(0.3f, 0.3f), at(0.3f, 0.7f), at(0.7f, 0.7f), at(0.7f, 0.3f)),
        )
    }

    private fun corpus(): List<Case> = listOf(
        Case("emptyInput", emptyList()),
        Case("plainQuad", listOf(quad(0f, 0f, 100f, 100f))),
        Case("quadWoundTheOtherWay", listOf(quadReversed(0f, 0f, 100f, 100f))),
        Case("quadWithAHole", listOf(quad(0f, 0f, 100f, 100f), quadReversed(30f, 30f, 70f, 70f))),

        // `d3d81fee1` — two holes diagonally is the arrangement that needed FloatEarcut's dead
        // hole-bridge refinement, and the only shape that ever showed it.
        Case(
            "twoHolesDiagonally",
            listOf(
                quad(0f, 0f, 100f, 100f),
                quadReversed(10f, 10f, 30f, 30f),
                quadReversed(60f, 60f, 90f, 90f),
            )
        ),

        // Three mutually perpendicular faces, each holed: the case `f531b1804` was about, and the
        // geometry of the loadable demo.
        Case(
            "threePlanesThreeHoles",
            holedFace(Vec3(0.5f, -0.5f, 0.5f), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)) +
                    holedFace(Vec3(-0.5f, 0.5f, 0.5f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)) +
                    holedFace(Vec3(-0.5f, -0.5f, 0.5f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))
        ),

        // A y-dominant wall: the `(z, x)` versus `(x, z)` case. `same3D` in the ring sanitiser is what
        // makes it survive at all — it has only two distinct `(x, y)` pairs.
        Case("aWallInTheXZPlane", listOf(quadXZ(0f, 0f, 100f, 100f))),
        Case("aWallInTheXZPlaneFacingTheOtherWay", listOf(quadXZ(0f, 0f, 100f, 100f).reversed())),

        // Two disjoint subpaths wound against each other: one hemisphere for the whole line emits one
        // of them inside out. This is the third defect in `dev_docs/fline_fill_orientation.md`.
        Case(
            "twoSubpathsWoundAgainstEachOther",
            listOf(quad(0f, 0f, 40f, 40f), quadReversed(60f, 0f, 100f, 40f))
        ),

        // Three deep: a square, a hole, and an island inside the hole. Even depth fills.
        Case(
            "anIslandInsideAHole",
            listOf(
                quad(0f, 0f, 100f, 100f),
                quadReversed(20f, 20f, 80f, 80f),
                quad(40f, 40f, 60f, 60f),
            )
        ),

        // A concave ring, which a fan gets wrong outside itself.
        Case(
            "anLShape",
            listOf(
                ring(
                    Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 30f, 0f),
                    Vec3(30f, 30f, 0f), Vec3(30f, 100f, 0f), Vec3(0f, 100f, 0f),
                )
            )
        ),

        // A collinear ring has no Newell normal, so it cannot seed a plane and is placed last.
        Case(
            "aCollinearRingBesideRealGeometry",
            listOf(
                ring(Vec3(10f, -20f, 0f), Vec3(50f, -20f, 0f), Vec3(90f, -20f, 0f)),
                quad(0f, 0f, 100f, 100f),
            )
        ),

        // A collinear ring *sharing a vertex* with the quad, which is where the nesting pass has to
        // decide containment for a point that is exactly on a boundary. Upright only — see [Case].
        Case(
            "aCollinearRingSharingAVertexWithTheQuad",
            listOf(
                ring(Vec3(0f, 0f, 0f), Vec3(50f, 0f, 0f), Vec3(100f, 0f, 0f)),
                quad(0f, 0f, 100f, 100f),
            ),
            rotatable = false,
        ),

        // Two congruent overlapping squares, each listing a vertex that lands inside the other. This
        // is the case that hung both implementations until 2026-08-04; see
        // `PlanarTessellationParentCycleTest`. Upright only: the two projected areas are *exactly*
        // equal here, and a rotation makes that tie a float-width coin flip.
        Case(
            "twoOverlappingRingsOfExactlyEqualArea",
            listOf(
                quad(0f, 0f, 100f, 100f),
                ring(Vec3(50f, 50f, 0f), Vec3(-50f, 50f, 0f), Vec3(-50f, -50f, 0f), Vec3(50f, -50f, 0f)),
            ),
            rotatable = false,
        ),
        Case("aCollinearRingListedFirstAndNothingElse", listOf(ring(Vec3(0f, 0f, 0f), Vec3(50f, 0f, 0f)))),
        Case("allPointsIdentical", listOf(ring(Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f)))),

        // A ring in a different plane that projects inside another one is not a hole. Under one shared
        // frame it was subtracted, and the bridge triangles left the plane entirely.
        Case(
            "aRingInAnotherPlaneThatProjectsInside",
            listOf(
                quad(0f, 0f, 100f, 100f),
                ring(Vec3(30f, 30f, 0f), Vec3(70f, 30f, 40f), Vec3(70f, 70f, 40f), Vec3(30f, 70f, 0f)),
            )
        ),
    )

    private fun quadXZ(x0: Float, z0: Float, x1: Float, z1: Float, y: Float = 0f): List<Vec3> =
        ring(Vec3(x0, y, z0), Vec3(x1, y, z0), Vec3(x1, y, z1), Vec3(x0, y, z1))

    /**
     * Every case again, rigidly rotated. Tessellation is supposed to commute with rotation, and the
     * two implementations are supposed to agree about how — including on the axis it lands on.
     *
     * The angles are deliberately generic. A rotation that leaves two normal components nearly equal
     * would put the dominant-axis tie-break on the wrong side of the float32/float64 difference and
     * make this test flaky for a reason that is not a defect.
     */
    private fun rotatedCorpus(): List<Case> = corpus().filter { it.rotatable }.flatMap { case ->
        listOf(
            0.37f to Vec3(0.5773503f, 0.5773503f, 0.5773503f),
            1.13f to Vec3(0f, 1f, 0f),
            2.51f to Vec3(0.26726124f, 0.53452248f, 0.80178373f),
        ).mapIndexed { at, (angle, axis) ->
            Case("${case.name}@rotation$at", case.rings.map { ring -> ring.map { rotate(it, axis, angle) } })
        }
    }

    private fun rotate(p: Vec3, axis: Vec3, angle: Float): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        val d = axis.x * p.x + axis.y * p.y + axis.z * p.z
        return Vec3(
            p.x * c + (axis.y * p.z - axis.z * p.y) * s + axis.x * d * (1f - c),
            p.y * c + (axis.z * p.x - axis.x * p.z) * s + axis.y * d * (1f - c),
            p.z * c + (axis.x * p.y - axis.y * p.x) * s + axis.z * d * (1f - c),
        )
    }

    // -------------------------------------------------------------------- one canonical rendering

    private fun json(rings: List<List<Vec3>>): String = rings.joinToString(",", "[", "]") { ring ->
        ring.joinToString(",", "[", "]") { "[${it.x.toDouble()},${it.y.toDouble()},${it.z.toDouble()}]" }
    }

    private fun render(polygons: List<PlanarTessellation.Polygon>): String =
        polygons.joinToString("\n") { polygon ->
            val triangles = polygon.triangles.chunked(3)
                .joinToString(" ") { t -> t.joinToString(",") { "${it.ring}:${it.vertex}" } }
            "outer=${polygon.outer} holes=${polygon.holes.joinToString(",", "[", "]")} $triangles"
        }

    private fun render(polygons: Value): String =
        (0 until polygons.arraySize).joinToString("\n") { at ->
            val polygon = polygons.getArrayElement(at)
            val holes = polygon.getMember("holes")
            val refs = polygon.getMember("triangles")
            val triangles = (0 until refs.arraySize).map { refs.getArrayElement(it) }
                .map { "${it.getArrayElement(0).asInt()}:${it.getArrayElement(1).asInt()}" }
                .chunked(3).joinToString(" ") { it.joinToString(",") }
            val holeList = (0 until holes.arraySize)
                .joinToString(",", "[", "]") { holes.getArrayElement(it).asInt().toString() }
            "outer=${polygon.getMember("outer").asInt()} holes=$holeList $triangles"
        }

    private fun ViewerJavaScript.Session.tessellate(rings: List<List<Vec3>>): Value =
        call("tessellateCoplanarGroups", fromJson(json(rings)))

    // ------------------------------------------------------------------------- the differential

    @Test
    fun theViewersJavaScriptAgreesWithPlanarTessellationTriangleForTriangle() {
        ViewerJavaScript.open().use { js ->
            val cases = corpus() + rotatedCorpus()
            assertTrue(cases.size >= 45, "the corpus shrank to ${cases.size}")
            var polygons = 0
            var triangles = 0
            cases.forEach { case ->
                val kotlin = PlanarTessellation.tessellate(case.rings)
                polygons += kotlin.size
                triangles += kotlin.sumOf { it.triangleCount }
                assertEquals(
                    render(kotlin),
                    render(js.tessellate(case.rings)),
                    "the viewer's JavaScript and PlanarTessellation disagree on `${case.name}`",
                )
            }
            // A differential that compared two empty answers would also pass, so say how much was
            // actually compared. Every non-degenerate case in the corpus contributes.
            assertTrue(polygons >= 60, "only $polygons polygons were compared")
            assertTrue(triangles >= 200, "only $triangles triangles were compared")
        }
    }

    /**
     * An independent property of the JavaScript's own output, not a comparison: every triangle it
     * emits faces the way its outer ring is wound.
     *
     * This is the invariant `bf65e10e3` and the mirrored-pair defect both broke, and it is worth
     * asserting separately from the differential so that a bug in the harness's shim — which supplies
     * the triangulator on both sides — cannot make the two agree about something wrong.
     */
    @Test
    fun everyPolygonTheJavaScriptEmitsFacesTheWayItsOuterRingIsWound() {
        ViewerJavaScript.open().use { js ->
            var checked = 0
            (corpus() + rotatedCorpus()).forEach { case ->
                val polygons = js.tessellate(case.rings)
                for (at in 0 until polygons.arraySize) {
                    val polygon = polygons.getArrayElement(at)
                    val outer = polygon.getMember("outer").asInt()
                    val expected = PlanarTessellation.newellNormal(case.rings[outer])
                    val length = sqrt(expected.x * expected.x + expected.y * expected.y + expected.z * expected.z)
                    if (length <= 0f) continue
                    val refs = polygon.getMember("triangles")
                    for (i in 0 until refs.arraySize step 3) {
                        val a = pointAt(case.rings, refs.getArrayElement(i))
                        val b = pointAt(case.rings, refs.getArrayElement(i + 1))
                        val c = pointAt(case.rings, refs.getArrayElement(i + 2))
                        val n = cross(sub(b, a), sub(c, a))
                        val area = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
                        // A sliver triangle has no reliable normal, and earcut emits them on
                        // collinear runs. Facing is only a question where there is area to face.
                        if (area < 1e-6f * length) continue
                        val dot = (n.x * expected.x + n.y * expected.y + n.z * expected.z) / (area * length)
                        assertTrue(
                            dot > 0.5f,
                            "`${case.name}` polygon $at triangle ${i / 3} faces the wrong way (cos $dot)",
                        )
                        checked++
                    }
                }
            }
            assertTrue(checked >= 200, "only $checked triangles had a facing to check")
        }
    }

    private fun pointAt(rings: List<List<Vec3>>, ref: Value): Vec3 =
        rings[ref.getArrayElement(0).asInt()][ref.getArrayElement(1).asInt()]

    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

    private fun cross(a: Vec3, b: Vec3) =
        Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    // ------------------------------------------------------------------------- can it go red?

    /**
     * The counterfactual that matters most: **the fan `bf65e10e3` removed**.
     *
     * `editable = true` used to triangulate each subpath as a fan from its first point — no holes, no
     * projection, no concavity — so a hole ring was drawn as a solid quad on top of its own face
     * (`dev_docs/agents/artifacts/tessellate_facing/multi_plane_fill_demo/editable_true_before.png`).
     * The old parity test could not see that, because it never ran the JavaScript. This one does: the
     * pre-fix rule is injected into the region's text and the differential goes red on the very first
     * holed case.
     *
     * The perturbation is never committed into the viewer — it is a rewrite of the extracted source,
     * and [ViewerJavaScript.Session] refuses a rewrite that matched nothing.
     */
    @Test
    fun theHarnessWouldHaveCaughtTheFanThatEditableModeUsedToBe() {
        val rings = listOf(quad(0f, 0f, 100f, 100f), quadReversed(30f, 30f, 70f, 70f))
        val expected = render(PlanarTessellation.tessellate(rings))

        ViewerJavaScript.perturbed(::fanEachSubpath).use { js ->
            val actual = render(js.tessellate(rings))
            assertNotEquals(expected, actual, "the fan counterfactual was not caught")
            // And the specific symptom, not just inequality: the hole ring gets a polygon of its own,
            // which is the filled-in hole in the "before" screenshot.
            assertEquals(2, js.tessellate(rings).arraySize, "the pre-fix rule makes the hole a polygon")
            assertEquals(1, PlanarTessellation.tessellate(rings).size, "the fixed rule makes it a hole")
        }

        // …and green again on the shipped source, on the same geometry. Red-when-perturbed is only
        // half a demonstration.
        ViewerJavaScript.open().use { js ->
            assertEquals(expected, render(js.tessellate(rings)))
        }
    }

    /** One polygon per subpath, fanned from its first point, holes ignored — `editable = true` before 2026-08-03. */
    private fun fanEachSubpath(region: String): String = region.replace(
        "    if (!rings.length) return [];",
        """
        |    if (!rings.length) return [];
        |    return rings.map(function (ring, i) {
        |        const triangles = [];
        |        for (let k = 1; k < ring.length - 1; k += 1) {
        |            triangles.push([i, 0], [i, k], [i, k + 1]);
        |        }
        |        return { outer: i, holes: [], triangles: triangles };
        |    }).filter(function (p) { return p.triangles.length >= 3; });
        """.trimMargin()
    )

    /**
     * The mirrored y-dominant pair: defect 1 of `dev_docs/fline_fill_orientation.md`, which made every
     * wall-like face in the XZ plane come back facing `-y` whatever its winding.
     */
    @Test
    fun theHarnessCatchesTheMirroredProjectionPairOnAYDominantFace() {
        val wall = listOf(quadXZ(0f, 0f, 100f, 100f))
        val expected = render(PlanarTessellation.tessellate(wall))

        ViewerJavaScript.perturbed(::mirrorTheYDominantPair).use { js ->
            assertNotEquals(expected, render(js.tessellate(wall)), "the mirrored pair was not caught")
        }
        // The mirror is invisible on a z-dominant face, which is exactly why it survived: the corpus
        // needs the wall, and this records that it does.
        ViewerJavaScript.perturbed(::mirrorTheYDominantPair).use { js ->
            val flat = listOf(quad(0f, 0f, 100f, 100f))
            assertEquals(render(PlanarTessellation.tessellate(flat)), render(js.tessellate(flat)))
        }
        ViewerJavaScript.open().use { js ->
            assertEquals(expected, render(js.tessellate(wall)))
        }
    }

    private fun mirrorTheYDominantPair(region: String): String = region
        .replace(
            "    const px = (p) => (axis === 0 ? p[1] : axis === 1 ? p[2] : p[0]);",
            "    const px = (p) => (axis === 0 ? p[1] : axis === 1 ? p[0] : p[0]);"
        )
        .replace(
            "    const py = (p) => (axis === 0 ? p[2] : axis === 1 ? p[0] : p[1]);",
            "    const py = (p) => (axis === 0 ? p[2] : axis === 1 ? p[2] : p[1]);"
        )

    /**
     * One hemisphere for the whole plane: defect 3, which emits the subpath wound against the biggest
     * ring inside out. Not a regression from any commit — it always did this, in plain XY.
     */
    @Test
    fun theHarnessCatchesOneHemisphereForTheWholePlane() {
        val backToBack = listOf(quad(0f, 0f, 40f, 40f), quadReversed(60f, 0f, 100f, 40f))
        val expected = render(PlanarTessellation.tessellate(backToBack))

        ViewerJavaScript.perturbed { region ->
            region.replace(
                "        const hemisphere = normals[info.ring][axis];",
                "        const hemisphere = 0;"
            )
        }.use { js ->
            assertNotEquals(expected, render(js.tessellate(backToBack)), "the shared hemisphere was not caught")
        }
        ViewerJavaScript.open().use { js ->
            assertEquals(expected, render(js.tessellate(backToBack)))
        }
    }

    /**
     * Everything in one plane — the pre-`f531b1804` behaviour — caught in *both* directions.
     *
     * The perturbed JavaScript must disagree with the default Kotlin, and must agree with
     * [Coplanarity.SINGLE_PLANE], which is the same counterfactual expressed in Kotlin. Agreeing with
     * the wrong answer for the right reason is a much stronger statement than merely differing.
     */
    @Test
    fun theHarnessCatchesSinglePlaneClusteringAndReproducesItExactly() {
        val threePlanes = corpus().first { it.name == "threePlanesThreeHoles" }.rings

        ViewerJavaScript.perturbed { region ->
            region
                .replace("const COPLANAR_SINE_OF_ANGLE = 1e-3;", "const COPLANAR_SINE_OF_ANGLE = Infinity;")
                .replace("const COPLANAR_OFFSET_FRACTION = 1e-3;", "const COPLANAR_OFFSET_FRACTION = Infinity;")
        }.use { js ->
            val actual = render(js.tessellate(threePlanes))
            assertNotEquals(
                render(PlanarTessellation.tessellate(threePlanes)),
                actual,
                "single-plane clustering was not caught",
            )
            assertEquals(
                render(PlanarTessellation.tessellate(threePlanes, Coplanarity.SINGLE_PLANE)),
                actual,
                "the perturbed JavaScript should reproduce Coplanarity.SINGLE_PLANE exactly",
            )
        }
    }

    // ------------------------------------------------------------------------------- the sanitiser

    /**
     * `sanitizeRing3D` is the viewer's copy of the sanitiser inside `FLine.tessellate`
     * (`FLine.kt:1021-1045`): drop consecutive duplicates in **three** dimensions, then drop a
     * repeated final point, both at `1e-5`. The three-dimensional part is load-bearing — a wall in the
     * XZ plane has only two distinct `(x, y)` pairs along its whole boundary, so a 2D predicate
     * collapses it to two points and the ring is dropped before any projection runs.
     */
    @Test
    fun theViewersRingSanitiserFollowsTheSameRuleAsFLineTessellate() {
        ViewerJavaScript.open().use { js ->
            fun sanitize(json: String): String {
                val out = js.call("sanitizeRing3D", js.fromJson(json))
                return (0 until out.arraySize).joinToString(" ") { at ->
                    val p = out.getArrayElement(at)
                    (0..2).joinToString(",") { p.getArrayElement(it.toLong()).asDouble().toString() }
                }
            }
            assertEquals("0.0,0.0,0.0 1.0,0.0,0.0", sanitize("[[0,0,0],[0,0,0],[1,0,0],[1,0,0]]"))
            assertEquals("0.0,0.0,0.0 1.0,0.0,0.0", sanitize("[[0,0,0],[1,0,0],[0,0,0]]"))
            // Distinct only in z: kept, which is what makes an XZ wall tessellable at all.
            assertEquals(
                "0.0,0.0,0.0 0.0,0.0,1.0",
                sanitize("[[0,0,0],[0,0,1]]"),
            )
            assertEquals(1e-5, js.member("RING_SANITIZE_EPS").asDouble(), 0.0)
            // The eps is a tolerance, not equality: a point inside it is a duplicate.
            assertEquals("0.0,0.0,0.0", sanitize("[[0,0,0],[0,0,0.000001]]"))
        }
    }
}
