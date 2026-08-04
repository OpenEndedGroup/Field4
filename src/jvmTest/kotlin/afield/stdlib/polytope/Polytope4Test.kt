package afield.stdlib.polytope

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rank-general incidence core, exercised at a rank the Conway surface operators cannot reach.
 *
 * Every count here is a published fact about a regular 4-polytope, and the whole face lattice is
 * *derived* from vertex coordinates plus outward facet normals rather than declared — so a wrong
 * derivation shows up as a wrong count rather than as a shape nobody checked.
 */
class Polytope4Test {

    private val seeds = listOf(
        "5-cell" to fiveCell(),
        "tesseract" to tesseract(),
        "16-cell" to sixteenCell(),
        "24-cell" to twentyFourCell(),
    )

    @Test
    fun theRegularFourPolytopesHaveTheirPublishedFaceCounts() {
        val expected = mapOf(
            "5-cell" to listOf(5, 10, 10, 5),
            "tesseract" to listOf(16, 32, 24, 8),
            "16-cell" to listOf(8, 24, 32, 16),
            "24-cell" to listOf(24, 96, 96, 24),
        )
        for ((name, polytope) in seeds) {
            assertEquals(
                expected.getValue(name),
                listOf(0, 1, 2, 3).map { polytope.topology.elements(it).size },
                "$name: ${polytope.summary()}",
            )
            assertEquals(1, polytope.topology.elements(4).size, "$name has no whole-polytope element")
        }
    }

    @Test
    fun everyFourPolytopeSatisfiesTheEulerRelationAndValidates() {
        for ((name, polytope) in seeds) {
            // V - E + F - C = 0 for a convex 4-polytope; eulerCharacteristic() alternates over the
            // proper ranks and excludes the whole, which is exactly that sum.
            assertEquals(0, polytope.topology.eulerCharacteristic(), "$name: ${polytope.summary()}")
            val validation = polytope.topology.validate()
            assertTrue(validation.isValid, "$name: ${validation.summary().messages}")
        }
    }

    @Test
    fun cellsAndPolygonsAreTheRightShapes() {
        val fiveCell = fiveCell().summary()
        assertEquals(mapOf(3 to 10), fiveCell.polygonDegrees)
        assertEquals(mapOf(4 to 5), fiveCell.cellPolygonCounts) // five tetrahedra

        val tesseract = tesseract().summary()
        assertEquals(mapOf(4 to 24), tesseract.polygonDegrees)
        assertEquals(mapOf(6 to 8), tesseract.cellPolygonCounts) // eight cubes

        val sixteenCell = sixteenCell().summary()
        assertEquals(mapOf(3 to 32), sixteenCell.polygonDegrees)
        assertEquals(mapOf(4 to 16), sixteenCell.cellPolygonCounts) // sixteen tetrahedra

        val twentyFourCell = twentyFourCell().summary()
        assertEquals(mapOf(3 to 96), twentyFourCell.polygonDegrees)
        assertEquals(mapOf(8 to 24), twentyFourCell.cellPolygonCounts) // twenty-four octahedra
    }

    @Test
    fun everyEdgeBelongsToAsManyPolygonsAsItsSchlafliSymbolSays() {
        // The rank-3 manifold assumption — exactly two faces per edge — is false at rank 4, and the
        // core must not have quietly baked it in. The number of polygons round an edge is the last
        // entry of the Schläfli symbol: {3,3,3} = 3, {4,3,3} = 3, {3,3,4} = 4, {3,4,3} = 3.
        val expected = mapOf("5-cell" to 3, "tesseract" to 3, "16-cell" to 4, "24-cell" to 3)
        for ((name, polytope) in seeds) {
            assertEquals(
                listOf(expected.getValue(name)),
                polytope.edges.map { polytope.topology.incidentUp(it.id).size }.distinct(),
                "$name edge figure",
            )
        }
        // Which is also why the closed-manifold check must reject them, rather than passing by luck.
        assertTrue(!tesseract().topology.validate(checkManifold = true).isValid)
    }

    @Test
    fun theAbstractDualSwapsVerticesWithCellsAndEdgesWithPolygons() {
        val tesseract = tesseract()
        val dual = tesseract.dual()
        assertEquals(
            listOf(8, 24, 32, 16),
            listOf(0, 1, 2, 3).map { dual.topology.elements(it).size },
        )
        // Which is the 16-cell, and the 24-cell is its own.
        assertEquals(
            listOf(0, 1, 2, 3).map { sixteenCell().topology.elements(it).size },
            listOf(0, 1, 2, 3).map { dual.topology.elements(it).size },
        )
        assertEquals(
            listOf(0, 1, 2, 3).map { twentyFourCell().topology.elements(it).size },
            listOf(0, 1, 2, 3).map { twentyFourCell().dual().topology.elements(it).size },
        )
        // The dual is abstract: no coordinates come along, and the type says so.
        assertEquals(null, dual.realization)
    }

    @Test
    fun theSeedsAreRegularWhereTheyClaimToBe() {
        for ((name, polytope) in seeds) {
            val realization = assertNotNull(polytope.realization)
            val index = polytope.vertices.mapIndexed { at, vertex -> vertex.id.value to at }.toMap()
            val lengths = polytope.edges.map { edge ->
                val ends = polytope.topology.incidentDown(edge.id)
                val a = realization[index.getValue(ends[0])]
                val b = realization[index.getValue(ends[1])]
                kotlin.math.sqrt((0..3).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })
            }
            val mean = lengths.average()
            assertTrue(lengths.all { abs(it - mean) < 1e-9 }, "$name has unequal edges: ${lengths.distinct()}")
        }
    }

    @Test
    fun perspectiveProjectionMakesTheTesseractACubeInsideACube() {
        val tesseract = tesseract()
        val projected = tesseract.project(PerspectiveProjection4(eye = 3.0, axis = 3))
        assertEquals(16, projected.size)
        assertTrue(projected.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })

        // The eight vertices with w = +1 are nearer the 4D viewpoint, so they project to the LARGER
        // cube; the w = -1 eight to the smaller one nested inside it. Two radii, nothing between.
        val radii = projected.map { it.length().toDouble() }.sorted()
        val inner = radii.take(8)
        val outer = radii.drop(8)
        assertTrue(inner.all { abs(it - inner.first()) < 1e-5 }, "inner cube was not uniform: $inner")
        assertTrue(outer.all { abs(it - outer.first()) < 1e-5 }, "outer cube was not uniform: $outer")
        assertTrue(outer.first() > inner.first() * 1.8, "the nesting was not visible: $inner vs $outer")

        // Orthographic collapses exactly that structure into one cube, which is why it is not the
        // default and why the KDoc says so.
        val flat = tesseract.project(OrthographicProjection4(axis = 3)).map { it.length().toDouble() }
        assertTrue(flat.all { abs(it - flat.first()) < 1e-6 }, "orthographic did not collapse: $flat")
    }

    @Test
    fun rotatingInFourSpaceChangesWhichCellFacesTheViewpoint() {
        val tesseract = tesseract()
        fun radii(angle: Double) = tesseract.project(
            RotatedProjection4(0, 3, angle, PerspectiveProjection4(eye = 3.0, axis = 3)),
        ).map { it.length().toDouble() }

        fun spread(angle: Double) = radii(angle).let { it.max() / it.min() }

        // A quarter turn in the (x, w) plane maps the tesseract's vertex set onto itself, so the
        // projection has to come back to the same nesting: this is the check that the rotation is
        // a rotation and not a shear.
        assertTrue(abs(spread(0.0) - spread(PI / 2)) < 1e-4, "${spread(0.0)} vs ${spread(PI / 2)}")

        // At an eighth turn the tesseract stands on a *cell diagonal*: the w coordinates spread from
        // ±1 to 0 and ±sqrt(2), so one cell comes closer to the 4D viewpoint and one recedes, and
        // the projection's nesting becomes more extreme rather than less.
        assertTrue(spread(PI / 4) > spread(0.0) * 1.2, "${spread(PI / 4)} vs ${spread(0.0)}")
        // Three distinct depths at an eighth turn, two at rest — the structure the picture shows.
        assertEquals(3, radii(PI / 4).map { "%.4f".format(it) }.distinct().size)
        assertEquals(2, radii(0.0).map { "%.4f".format(it) }.distinct().size)
    }

    @Test
    fun projectedEdgesBecomeFLinesWithStableIdentity() {
        val polytope = twentyFourCell()
        val lines = polytope.toEdgeFLines(PerspectiveProjection4(eye = 3.0))
        assertEquals(96, lines.size)
        assertEquals(
            polytope.edges.map { it.id.value },
            lines.map { it.properties[POLYTOPE_FACE_ID_PROPERTY] },
        )
        assertTrue(lines.all { it.properties[POLYTOPE_FACE_RANK_PROPERTY] == 1 })

        // One octahedral cell of the 24-cell has 12 of those 96 edges.
        val cell = polytope.cells.first()
        val cellLines = polytope.toCellEdgeFLines(cell.id, PerspectiveProjection4(eye = 3.0))
        assertEquals(12, cellLines.size)
        assertTrue(cellLines.all { it.properties[POLYTOPE_CELL_ID_PROPERTY] == cell.id.value })
    }

    @Test
    fun aRankFourTopologyCannotBeMistakenForAPolyhedron() {
        // PolyhedralObject is deliberately rank-agnostic, so wrapping a rank-4 topology in one
        // succeeds. The rank-3 assumption belongs to the operators that need it, so this asserts the
        // rejection where it is really performed: the Conway surface transforms.
        val four = PolyhedralObject(tesseract().topology)
        assertEquals(4, four.topology.rank, "PolyhedralObject should have kept the rank-4 topology")
        val conwayFailure = runCatching { four.dual() }.exceptionOrNull()
        assertNotNull(conwayFailure, "a Conway surface transform accepted a rank-4 topology")
        assertTrue(
            conwayFailure.message.orEmpty().contains("require rank 3"),
            "the rank check should be what rejected it, not something downstream: $conwayFailure",
        )
        // And Polytope4 rejects a rank-3 topology just as firmly, in its own constructor.
        val rankThreeFailure = runCatching { Polytope4(cube().topology) }.exceptionOrNull()
        assertNotNull(rankThreeFailure, "Polytope4 accepted a rank-3 topology")
        assertTrue(
            rankThreeFailure.message.orEmpty().contains("requires a rank-4 topology"),
            "expected Polytope4's own rank check: $rankThreeFailure",
        )
    }
}
