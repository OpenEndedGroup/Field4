package afield.stdlib.polytope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The whole Conway vocabulary chains, and chaining changes nothing but where the operator is written.
 *
 * Before 2026-08-05 the eight composites were declared on [PolyhedralObject] only, so
 * `cube().ambo().bevel()` did not compile, and `needle`, `zip` and `ortho` had no named function at
 * all — they were reachable through `transform(ConwayOperator.NEEDLE)` and nowhere else.
 *
 * [everyOperatorIsNamedOnBothReceivers] is the guard that keeps it that way: it enumerates
 * [ConwayOperator] and looks the extension functions up reflectively, so adding a fourteenth operator
 * without giving it both halves fails here rather than being noticed by a user a year later.
 */
class PolytopeConwayChainingTest {

    @Test
    fun everyOperatorIsNamedOnBothReceivers() {
        val declared = Class.forName("afield.stdlib.polytope.TransformsKt").methods
        for (operator in ConwayOperator.entries) {
            // Every operator's `displayName` is exactly its extension function's name, which is what
            // makes this enumerable rather than a hand-written list that can go stale.
            val named = declared.filter { it.name == operator.displayName }
            assertTrue(
                named.any { it.parameterTypes.firstOrNull() == PolyhedralObject::class.java },
                "${operator.displayName} has no PolyhedralObject extension",
            )
            assertTrue(
                named.any { it.parameterTypes.firstOrNull() == PolytopeTransformResult::class.java },
                "${operator.displayName} has no PolytopeTransformResult extension, so it cannot be chained",
            )
        }
    }

    @Test
    fun theChainedOperatorAgreesWithTheOperatorValueExactly() {
        // The named extension and the `transform(value)` escape hatch must be the same operation,
        // geometry included — otherwise one of the two is choosing a different realization policy.
        val chained = cube().ambo().bevel()
        val byValue = cube().ambo().transform(ConwayOperator.BEVEL)
        assertSameObject(byValue, chained)

        // …and the same as letting the notation parser do the whole thing.
        assertSameObject(conway("baC"), chained)
    }

    @Test
    fun cubeAmboBevelHasTheCountsBevelPromises() {
        val result = cube().ambo().bevel()
        val topology = result.polyhedron.topology
        // aC is the cuboctahedron: 12 vertices, 24 edges, 14 faces. b = ta over that gives one face
        // per original face, vertex and edge — 14 + 12 + 24 = 50 — and a vertex per (face, corner).
        assertEquals(14 + 12 + 24, topology.elements(2).size)
        assertEquals(48 + 24 + 24, topology.elements(0).size)
        assertEquals(2, topology.eulerCharacteristic())
        assertEquals(0, topology.totalFaceHoleCount())

        // The composite is recorded as itself, and its primitive word as what ran.
        assertEquals(listOf(ConwayOperator.AMBO, ConwayOperator.BEVEL), result.notationHistory)
        assertEquals(
            listOf(ConwayOperator.AMBO, ConwayOperator.AMBO, ConwayOperator.TRUNCATE),
            result.history,
        )
    }

    @Test
    fun longerChainsRunAndStayClosedSurfaces() {
        val chains: List<Pair<String, PolytopeTransformResult>> = listOf(
            "cube().ambo().bevel()" to cube().ambo().bevel(),
            "cube().ambo().bevel().kis()" to cube().ambo().bevel().kis(),
            "cube().join().needle()" to cube().join().needle(),
            "octahedron().ortho().zip()" to octahedron().ortho().zip(),
            "tetrahedron().expand().meta()" to tetrahedron().expand().meta(),
            "tetrahedron().snub().dual()" to tetrahedron().snub().dual(),
            "cube().truncate().ambo().join()" to cube().truncate().ambo().join(),
        )
        for ((name, result) in chains) {
            val topology = result.polyhedron.topology
            assertEquals(2, topology.eulerCharacteristic(), "$name is not a closed surface")
            topology.validate(checkManifold = true).throwIfInvalid()
            assertTrue(topology.elements(2).size > 3, "$name produced only ${topology.elements(2).size} faces")
            // Lineage survives the whole chain: every output face knows a seed element.
            for (face in topology.elements(2)) {
                assertTrue(result.sourceElements(face.id).isNotEmpty(), "$name lost lineage for a face")
            }
        }
    }

    @Test
    fun namedCompositesMatchTheirNotationLetters() {
        // Each named function is the notation letter, on both receivers, for a seed each.
        val cases = listOf(
            "jC" to cube().join(),
            "nC" to cube().needle(),
            "zC" to cube().zip(),
            "oC" to cube().ortho(),
            "eC" to cube().expand(),
            "sC" to cube().snub(),
            "mC" to cube().meta(),
            "bC" to cube().bevel(),
        )
        for ((expression, direct) in cases) {
            assertSameObject(conway(expression), direct)
            // Mid-chain: `d` then the composite, against the same notation with a `d` in front of C.
            val chained = cube().dual().let { seeded ->
                when (expression.first()) {
                    'j' -> seeded.join()
                    'n' -> seeded.needle()
                    'z' -> seeded.zip()
                    'o' -> seeded.ortho()
                    'e' -> seeded.expand()
                    's' -> seeded.snub()
                    'm' -> seeded.meta()
                    else -> seeded.bevel()
                }
            }
            assertSameObject(conway("${expression.first()}dC"), chained)
        }
    }

    @Test
    fun chainingStillRefusesARealizationPolicyForAComposite() {
        // The guard that stops a chained composite silently taking one primitive's policy for all of
        // its steps. The named extensions cannot even offer one, which is the point of their shape.
        val message = assertFailsWith<IllegalArgumentException> {
            cube().ambo().transform(ConwayOperator.BEVEL, realizationPolicy = DualCentroidRealization)
        }.message
        assertTrue(message!!.contains("single realization policy cannot realize composite"), message)
        assertTrue(message.contains("bevel"), message)
    }

    /** Same combinatorics, same coordinates, same recorded notation — not merely the same counts. */
    private fun assertSameObject(expected: PolytopeTransformResult, actual: PolytopeTransformResult) {
        val a = expected.polyhedron
        val b = actual.polyhedron
        assertEquals(a.topology.elementsByRank.map { it.size }, b.topology.elementsByRank.map { it.size })
        for ((face, other) in a.topology.elements(2).zip(b.topology.elements(2))) {
            assertEquals(
                a.topology.faceCycle(face.id).toList(),
                b.topology.faceCycle(other.id).toList(),
                "face boundaries diverged",
            )
        }
        val expectedPoints = requireNotNull(a.realization).vertexCoordinates
        val actualPoints = requireNotNull(b.realization).vertexCoordinates
        assertEquals(expectedPoints.size, actualPoints.size)
        for (i in expectedPoints.indices) {
            assertEquals(expectedPoints[i].x, actualPoints[i].x, 1e-6f, "vertex $i x")
            assertEquals(expectedPoints[i].y, actualPoints[i].y, 1e-6f, "vertex $i y")
            assertEquals(expectedPoints[i].z, actualPoints[i].z, 1e-6f, "vertex $i z")
        }
        assertEquals(expected.notationHistory, actual.notationHistory)
        assertEquals(expected.history, actual.history)
    }
}
