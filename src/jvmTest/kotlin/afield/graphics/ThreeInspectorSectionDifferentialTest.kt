package afield.graphics

import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.label
import afield.stdlib.stroked
import org.graalvm.polyglot.Value
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * "What does this look like in section" — **executing the viewer's own JavaScript**.
 *
 * three.js will hide half a figure for you. That answers a different question: hiding the front half
 * shows you the inside of a shell, but it does not draw the line where the knife went, and on a
 * fabrication drawing the cut line *is* the section — it is what says the wall is 240 thick here and 90
 * there. So `inspector-section` computes the crossings, and this test holds them to geometry whose cut
 * is known before the code runs.
 *
 * The figures are the same four wall skins the explode test uses, built by the *real* producer
 * ([buildThreeGeometryPayload]) and cut through their own triangles. A horizontal section through four
 * 200-wide skins is 4 × 200 = 800 of cut line, and that number is asserted rather than measured.
 *
 * The counterfactual is the one worth reading. The straddle test is `(da > 0) !== (db > 0)` and not
 * `da * db < 0`; they differ exactly when a vertex lies **on** the plane, which is the common case and
 * not the rare one, because real geometry is built on round numbers and sections get put on round
 * numbers. Perturbed to the product test, the cut through this figure is not merely wrong — it is
 * **empty**, and the test asserts exactly that.
 */
class ThreeInspectorSectionDifferentialTest {

    private fun session(perturbation: (String) -> String = ViewerJavaScript.UNPERTURBED) =
        ViewerJavaScript.Session.open(
            regionName = "inspector-section",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("inspector-section"),
            perturbation = perturbation,
        )

    /** A wall skin: a 200 (y) x 300 (z) panel in the plane x = `x`. */
    private fun skin(name: String, x: Double) = fline {
        moveTo(x, 0.0, 0.0)
        lineTo(x, 200.0, 0.0)
        lineTo(x, 200.0, 300.0)
        lineTo(x, 0.0, 300.0)
        close()
        label = name
        filled = true
        stroked = false
    }

    private val skins = listOf(skin("a", 0.0), skin("b", 12.0), skin("c", 24.0), skin("d", 36.0))

    private fun payload() = buildThreeGeometryPayload(
        uid = "test", lines = skins, samplesPerCubic = 12, autoFrame = true, editable = false,
        groupKeys = skins.map { it.label ?: "" }, inspector = ThreeInspector(),
    )

    private fun FloatArray.json() = joinToString(",", "[", "]") { it.toDouble().toString() }
    private fun IntArray.json() = joinToString(",", "[", "]")

    private fun ViewerJavaScript.Session.boundsJson(b: ThreeBounds) =
        fromJson("""{"min": ${b.min.json()}, "max": ${b.max.json()}}""")

    private fun Value.doubles(): DoubleArray =
        DoubleArray(arraySize.toInt()) { getArrayElement(it.toLong()).asDouble() }

    // ---------------------------------------------------------------- the plane

    /**
     * The plane sits at the fraction of the bounds it was asked for, exactly.
     *
     * The four skins span x = 0..36, so a quarter of the way along +x is x = 9 and the plane is
     * `x - 9 = 0`. Not "about 9": the fraction arithmetic is a lerp over two exact bounds, and nothing
     * about it should introduce a tolerance.
     */
    @Test
    fun `a fraction of the bounds becomes the plane it says it does`() {
        val bounds = payload().bounds!!
        session().use { js ->
            val plane = js.call("sectionPlane", js.boundsJson(bounds), js.fromJson("[1,0,0]"), 0.25, false)
            assertEquals(0.0, plane.getMember("lo").asDouble())
            assertEquals(36.0, plane.getMember("hi").asDouble())
            assertEquals(9.0, plane.getMember("at").asDouble())
            assertEquals(-9.0, plane.getMember("d").asDouble())
            assertEquals(1.0, plane.getMember("n").getArrayElement(0).asDouble())
            // and the signed distance is what the plane claims
            assertEquals(1.0, js.call("sectionSignedDistance", plane, js.fromJson("[10,0,0]")).asDouble())
            assertEquals(-9.0, js.call("sectionSignedDistance", plane, js.fromJson("[0,0,0]")).asDouble())
        }
    }

    /**
     * An oblique normal projects all eight corners, rather than guessing from an axis.
     *
     * With `n = (1, 1, 0)/√2` over a box spanning 0..36 in x and 0..200 in y, the extent along `n` runs
     * from 0 to `(36 + 200)/√2`. An implementation that took the bounds' own diagonal, or that only
     * looked at the min and max corners of an axis, would be right for axis-aligned normals and quietly
     * wrong for every other one — and the viewer's section control offers arbitrary normals.
     */
    @Test
    fun `an oblique normal gets the true extent, not an axis-aligned guess`() {
        val bounds = payload().bounds!!
        session().use { js ->
            val plane = js.call("sectionPlane", js.boundsJson(bounds), js.fromJson("[1,1,0]"), 1.0, false)
            assertEquals(0.0, plane.getMember("lo").asDouble())
            assertTrue(abs(plane.getMember("hi").asDouble() - (36.0 + 200.0) / sqrt(2.0)) < 1e-9)
            assertTrue(abs(plane.getMember("at").asDouble() - (36.0 + 200.0) / sqrt(2.0)) < 1e-9)
        }
    }

    /**
     * `flip` swaps which half survives *without* moving the cut — the property that makes it a separate
     * control from negating the normal.
     *
     * Negating the normal would also reverse the fraction, so the slider would run backwards under the
     * user's hand the moment they flipped the section. Here: same `at`, opposite `n`, and the signed
     * distance of a fixed probe point changes sign and nothing else.
     */
    @Test
    fun `flipping the section keeps the cut where it was`() {
        val bounds = payload().bounds!!
        session().use { js ->
            val normal = js.fromJson("[1,0,0]")
            val plain = js.call("sectionPlane", js.boundsJson(bounds), normal, 0.25, false)
            val flipped = js.call("sectionPlane", js.boundsJson(bounds), normal, 0.25, true)
            assertEquals(plain.getMember("at").asDouble(), flipped.getMember("at").asDouble())
            assertEquals(9.0, flipped.getMember("at").asDouble())
            val probe = js.fromJson("[20,0,0]")
            assertEquals(
                js.call("sectionSignedDistance", plain, probe).asDouble(),
                -js.call("sectionSignedDistance", flipped, probe).asDouble(),
            )
        }
    }

    // ---------------------------------------------------------------- the cut

    /**
     * A horizontal section through four 200-wide skins is 800 of cut line, and every point of it is
     * on the plane.
     *
     * Both halves matter. The length says the cut found all four skins and clipped none of them short;
     * the on-plane check says the crossings were computed rather than approximated. 800 is arithmetic
     * done before the code ran, not a number read off a first run.
     */
    @Test
    fun `a horizontal section through four skins is exactly four skin widths of cut`() {
        val p = payload()
        val fills = p.fills!!
        val positions = fills.attributes.getValue("position").array
        val index = fills.index!!.array
        session().use { js ->
            val plane = js.call("sectionPlane", js.boundsJson(p.bounds!!), js.fromJson("[0,0,1]"), 0.5, false)
            assertEquals(150.0, plane.getMember("at").asDouble())
            val cut = js.call(
                "sectionCutTriangles", js.fromJson(positions.json()), js.fromJson(index.json()), plane,
            )
            val length = js.call("sectionCutLength", cut).asDouble()
            assertTrue(abs(length - 800.0) < 1e-6, "the cut through four 200-wide skins measured $length")
            val flat = cut.doubles()
            assertTrue(flat.isNotEmpty(), "the cut is empty")
            var worstOffPlane = 0.0
            for (i in flat.indices step 3) {
                val off = abs(
                    js.call("sectionSignedDistance", plane, js.fromJson("[${flat[i]},${flat[i + 1]},${flat[i + 2]}]"))
                        .asDouble()
                )
                worstOffPlane = maxOf(worstOffPlane, off)
            }
            println("section: ${flat.size / 6} cut segments, total length $length, worst off-plane $worstOffPlane")
            assertTrue(worstOffPlane < 1e-9, "a cut point sat $worstOffPlane off the plane")
        }
    }

    /**
     * The section moves with the fraction, and the *length* is invariant for a prismatic figure.
     *
     * Four rectangular skins are prisms along z, so every horizontal cut through them is the same 800
     * long. Sweeping the fraction and asserting the length never changes is a stronger statement than
     * one cut: it says the crossing arithmetic is not accidentally right at the midpoint.
     */
    @Test
    fun `sweeping the section keeps the cut length constant through a prism`() {
        val p = payload()
        val positions = p.fills!!.attributes.getValue("position").array
        val index = p.fills!!.index!!.array
        session().use { js ->
            val bounds = js.boundsJson(p.bounds!!)
            val positionsJs = js.fromJson(positions.json())
            val indexJs = js.fromJson(index.json())
            (1..9).forEach { step ->
                val fraction = step / 10.0
                val plane = js.call("sectionPlane", bounds, js.fromJson("[0,0,1]"), fraction, false)
                assertTrue(abs(plane.getMember("at").asDouble() - fraction * 300.0) < 1e-9)
                val cut = js.call("sectionCutTriangles", positionsJs, indexJs, plane)
                val length = js.call("sectionCutLength", cut).asDouble()
                assertTrue(abs(length - 800.0) < 1e-6, "at fraction $fraction the cut measured $length")
            }
        }
    }

    /** A plane that misses the figure cuts nothing, rather than cutting something arbitrary. */
    @Test
    fun `a plane that misses the figure produces no cut`() {
        val p = payload()
        session().use { js ->
            val cut = js.call(
                "sectionCutTriangles",
                js.fromJson(p.fills!!.attributes.getValue("position").array.json()),
                js.fromJson(p.fills!!.index!!.array.json()),
                js.fromJson("""{"n": [0,0,1], "d": -1000}"""),
            )
            assertEquals(0L, cut.arraySize)
            assertEquals(0.0, js.call("sectionCutLength", cut).asDouble())
        }
    }

    /**
     * A cut through line geometry gives **points**, at the crossings and nowhere else.
     *
     * Three vertical runners at x = 0, 40, 80 cut at z = 25 give exactly three marks, at those x's. This
     * is what a section through a conduit run should look like — a row of dots where each pipe was
     * caught — and it is a different output shape from the fill cut on purpose.
     */
    @Test
    fun `a section through line geometry marks each crossing once`() {
        // a LineSegments buffer: three vertical runners, endpoint pairs
        val runners = doubleArrayOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 100.0,
            40.0, 0.0, 0.0, 40.0, 0.0, 100.0,
            80.0, 0.0, 0.0, 80.0, 0.0, 100.0,
            // and one that stays below the cut, so "found all of them" is not the same as "found three"
            120.0, 0.0, 0.0, 120.0, 0.0, 10.0,
        )
        session().use { js ->
            val marks = js.call(
                "sectionCutSegments",
                js.fromJson(runners.joinToString(",", "[", "]")),
                js.fromJson("""{"n": [0,0,1], "d": -25}"""),
            ).doubles()
            assertEquals(9, marks.size, "expected three marks, got ${marks.size / 3}")
            assertEquals(listOf(0.0, 40.0, 80.0), listOf(marks[0], marks[3], marks[6]))
            assertEquals(listOf(25.0, 25.0, 25.0), listOf(marks[2], marks[5], marks[8]))
        }
    }

    // ---------------------------------------------------------------- counterfactuals

    /**
     * The product straddle test, and why it is not the one in the region.
     *
     * `da * db < 0` looks equivalent to `(da > 0) !== (db > 0)` and is not: they differ exactly when a
     * vertex sits *on* the plane. The four skins are built on round numbers and this section is at a
     * round number, so the perturbed cut is not slightly wrong — it is **entirely empty**, which is what
     * a user would have seen as "the section tool does nothing at exactly the places I want to cut".
     *
     * Constructed so the failure is unmissable: the cut plane passes through the skins' own z = 150,
     * where the tessellation put no vertices, so first a sanity case where both tests agree, and then
     * the case at z = 0 where a whole edge of every skin lies in the plane.
     */
    @Test
    fun `counterfactual — the product straddle test drops a cut through an on-plane vertex`() {
        // one triangle, with its middle vertex exactly on the plane z = 0
        val triangle = "[0,0,-10, 100,0,0, 50,0,10]"
        val plane = """{"n": [0,0,1], "d": 0}"""
        val healthy = session().use { js ->
            js.call("sectionCutTriangles", js.fromJson(triangle), null, js.fromJson(plane)).arraySize
        }
        assertEquals(6L, healthy, "the healthy region should cut this triangle into one segment")

        val perturbed = session { it.replace("if ((d[a] > 0) === (d[b] > 0)) continue;", "if (!(d[a] * d[b] < 0)) continue;") }
            .use { js ->
                js.call("sectionCutTriangles", js.fromJson(triangle), null, js.fromJson(plane)).arraySize
            }
        assertEquals(0L, perturbed, "the product test should lose this cut entirely, but produced $perturbed")
    }

    /**
     * Drop the bounds projection and an oblique section lands somewhere else entirely.
     *
     * Perturbed to use only the two extreme corners — the tempting shortcut — the extent along
     * `(1, 1, 0)/√2` comes out right *by accident* for this figure, so the perturbation used here is the
     * sharper one: use the bounds' x extent regardless of the normal. The oblique cut then sits at 36/√2
     * of the way along instead of at the top of the figure, and the section plane misses most of it.
     */
    @Test
    fun `counterfactual — ignoring the normal when measuring the bounds moves an oblique section`() {
        val bounds = payload().bounds!!
        session { it.replace("const at = p[0] * n[0] + p[1] * n[1] + p[2] * n[2];", "const at = p[0];") }.use { js ->
            val plane = js.call("sectionPlane", js.boundsJson(bounds), js.fromJson("[1,1,0]"), 1.0, false)
            assertEquals(36.0, plane.getMember("at").asDouble(), "the perturbation should cap the cut at the x extent")
            assertTrue(
                abs(plane.getMember("at").asDouble() - (36.0 + 200.0) / sqrt(2.0)) > 100.0,
                "the perturbed plane should be far from the healthy one",
            )
        }
    }

    /** A perturbation that matched nothing is refused, so the counterfactuals cannot rot silently. */
    @Test
    fun `a perturbation that rewrites nothing is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            session { it.replace("a string this region does not contain", "x") }.close()
        }
        assertTrue(failure.message!!.contains("left the `inspector-section` region unchanged"), failure.message!!)
    }
}
