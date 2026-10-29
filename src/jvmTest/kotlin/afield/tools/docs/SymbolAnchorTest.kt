package afield.tools.docs

import afield.tools.docs.StdlibReferenceGenerator.AnchorEntry
import afield.tools.docs.StdlibReferenceGenerator.FileScope
import afield.tools.docs.StdlibReferenceGenerator.TypeAnchors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * KDoc `[Symbol]` references resolve to every published row, not just to types.
 *
 * [StdlibReferenceGenerator.TypeAnchors] once indexed types only, so `[topLevelFun]`, `[Type.member]`
 * and `[Receiver.extension]` published as plain code. These pin what the widened index links and,
 * as importantly, what it still refuses: a name two files define, a member named bare from
 * another file, a member of a type this reference does not emit, and a name the referring row
 * takes as its own parameter.
 */
class SymbolAnchorTest {

    private val boxes = "afield/Boxes.kt"
    private val alife = "afield/alife.kt"
    private val tuio = "afield/tuio.kt"
    private val csv = "afield/CSVReader.kt"
    private val other = "afield/other.kt"

    private val anchors = TypeAnchors(
        listOf(
            AnchorEntry(boxes, "Box", null, null, true, "core.md#boxes-kt-box"),
            AnchorEntry(boxes, "children", "Box", null, false, "core.md#boxes-kt--box--children"),
            AnchorEntry(alife, "polarization", null, null, false, "alife.md#alife-kt--polarization"),
            AnchorEntry(alife, "animateEvolution", null, "Box", false, "alife.md#alife-kt--animateevolution"),
            AnchorEntry(alife, "add", "Genome", null, false, "alife.md#alife-kt--genome--add"),
            AnchorEntry(alife, "Genome", null, null, true, "alife.md#alife-kt-genome"),
            AnchorEntry(tuio, "TuioReceiver", null, null, true, "data.md#tuio-kt-tuioreceiver"),
            AnchorEntry(tuio, "Frame", "TuioReceiver", null, false, "data.md#tuio-kt--tuioreceiver--frame"),
            AnchorEntry(csv, "Frame", null, null, true, "physics.md#csvreader-kt-frame"),
            AnchorEntry(alife, "smooth", null, null, false, "alife.md#alife-kt--smooth"),
            AnchorEntry(csv, "smooth", null, null, false, "physics.md#csvreader-kt--smooth"),
        )
    )

    @Test
    fun `a top-level function links from anywhere`() {
        assertEquals("alife.md#alife-kt--polarization", anchors.resolveReference("polarization", other))
    }

    @Test
    fun `a member links qualified by its owner, and bare only inside its own file`() {
        assertEquals("core.md#boxes-kt--box--children", anchors.resolveReference("Box.children", other))
        assertEquals("core.md#boxes-kt--box--children", anchors.resolveReference("children", boxes))
        assertNull(anchors.resolveReference("children", other))
    }

    @Test
    fun `an extension links qualified by its receiver`() {
        assertEquals("alife.md#alife-kt--animateevolution", anchors.resolveReference("Box.animateEvolution", other))
        assertEquals("alife.md#alife-kt--animateevolution", anchors.resolveReference("animateEvolution", other))
    }

    @Test
    fun `an ambiguous name is unlinked from a third file but resolves in either owner`() {
        assertNull(anchors.resolveReference("smooth", other))
        assertEquals("alife.md#alife-kt--smooth", anchors.resolveReference("smooth", alife))
        assertEquals("physics.md#csvreader-kt--smooth", anchors.resolveReference("smooth", csv))
    }

    @Test
    fun `a qualifier picks the nested member over a same-named type elsewhere`() {
        assertEquals("data.md#tuio-kt--tuioreceiver--frame", anchors.resolveReference("TuioReceiver.Frame", other))
        assertEquals("physics.md#csvreader-kt-frame", anchors.resolveReference("Frame", other))
    }

    @Test
    fun `an unknown type qualifier does not borrow a top-level function`() {
        assertNull(anchors.resolveReference("List.polarization", other))
        assertEquals("alife.md#alife-kt--polarization", anchors.resolveReference("afield.alife.polarization", other))
        // A known qualifier from another file contradicts the match.
        assertNull(anchors.resolveReference("Box.polarization", other))
    }

    @Test
    fun `a parameter name resolves in its own scope, not against the corpus`() {
        // `polarization` is a top-level function of another file, so bare it links from anywhere —
        // except from a row whose own parameter is called that.
        assertEquals("alife.md#alife-kt--polarization", anchors.resolveReference("polarization", other))
        assertNull(anchors.resolveReference("polarization", other, setOf("polarization")))
        // A qualified reference names a member whatever a parameter is called.
        assertEquals(
            "core.md#boxes-kt--box--children",
            anchors.resolveReference("Box.children", other, setOf("children", "Box")),
        )
        // And a name the row does not take as a parameter is unaffected.
        assertEquals("alife.md#alife-kt--polarization", anchors.resolveReference("polarization", other, setOf("steps")))
    }

    @Test
    fun `parameter names are read from the signature`() {
        assertEquals(
            setOf("totalSteps"),
            StdlibReferenceGenerator.parameterNames("fun Box.animateEvolution(totalSteps: Int)"),
        )
        assertEquals(
            setOf("xs", "seed", "onStep"),
            StdlibReferenceGenerator.parameterNames(
                "fun walk(vararg xs: Int, seed: Long = 0L, crossinline onStep: (i: Int) -> Unit)"
            ),
        )
        // A constructor's `val`, an annotation and a backticked name all reduce to the name.
        assertEquals(
            setOf("line", "in"),
            StdlibReferenceGenerator.parameterNames("class Span(val line: Int, @JvmField `in`: String)"),
        )
        // A signature with no parameter list has no parameters.
        assertEquals(emptySet<String>(), StdlibReferenceGenerator.parameterNames("val Box.frame: Rect"))
        // And a function type is not a parameter list, so `Box` stays linkable from the summary.
        assertEquals(emptySet<String>(), StdlibReferenceGenerator.parameterNames("val handler: (Box) -> Unit"))
        // An arrow is not a closing bracket: every parameter after the first function-typed one
        // used to be invisible here, which left `set` linking to an unrelated `set` elsewhere.
        assertEquals(
            setOf("name", "get", "set"),
            StdlibReferenceGenerator.parameterNames(
                "fun slider(name: String, get: () -> Double, set: (Double) -> Unit)"
            ),
        )
        assertEquals(
            setOf("candidates", "check", "facts"),
            StdlibReferenceGenerator.parameterNames(
                "fun <T> tryAll(candidates: List<Candidate>, check: (String) -> Admission<T>, " +
                    "facts: (value: T) -> Map<String, Any?>): Trial"
            ),
        )
    }

    @Test
    fun `receivers are read from the signature`() {
        assertEquals("Box", StdlibReferenceGenerator.receiverOf("fun Box.animateEvolution(totalSteps: Int)", "animateEvolution"))
        assertEquals("List", StdlibReferenceGenerator.receiverOf("fun <T> List<T>.prefix(n: Int): List<T>", "prefix"))
        assertEquals("Vec", StdlibReferenceGenerator.receiverOf("val afield.math.Vec?.len: Double", "len"))
        assertNull(StdlibReferenceGenerator.receiverOf("fun polarization(v: Box.Thing): Double", "polarization"))
    }
}

/** An ambiguous bare name settled by the referencing file's package and imports. */
class SymbolAnchorScopeTest {
    private val boxes = "afield/document/graph/Boxes.kt"
    private val steam = "afield/audio/spatial/steamaudio/Geometry.kt"
    private val dyn = "afield/simulation/physics/Dyn4jScene.kt"
    private val linalg = "afield/math/linalg/linalg.kt"
    private val ceres = "afield/math/optimization/ceres/CeresGeometry.kt"

    private fun entry(file: String, pkg: String, name: String, owner: String? = null) =
        AnchorEntry(file, name, owner, null, true, "$file#$name", pkg)

    private val anchors = TypeAnchors(
        listOf(
            entry(boxes, "afield.document.graph", "Box"),
            entry(steam, "afield.audio.spatial.steamaudio", "Box"),
            entry(dyn, "afield.simulation.physics", "Dyn4jScene"),
            AnchorEntry(dyn, "Box", "Dyn4jScene", null, false, "$dyn#nested-box", "afield.simulation.physics"),
            entry(linalg, "afield.math.linalg", "Vec3"),
            entry(ceres, "afield.math.optimization.ceres", "Vec3"),
        ),
        mapOf(
            "a/samePackage.kt" to FileScope("afield.document.graph", emptyList()),
            "a/explicit.kt" to FileScope("afield.agents", listOf("afield.document.graph.Box", "afield.math.linalg.Vec3")),
            "a/star.kt" to FileScope("afield.agents", listOf("afield.math.optimization.ceres.*")),
            "a/both.kt" to FileScope("afield.agents", listOf("afield.math.linalg.*", "afield.math.optimization.ceres.*")),
            "a/none.kt" to FileScope("afield.agents", emptyList()),
        ),
    )

    @Test
    fun `a nested type never claims the bare name`() {
        assertEquals("$dyn#nested-box", anchors.resolveReference("Dyn4jScene.Box", "a/none.kt"))
        assertEquals("$boxes#Box", anchors.resolveReference("Box", "a/samePackage.kt"))
    }

    @Test
    fun `an ambiguous name resolves by package, then explicit import, then star import`() {
        assertEquals("$boxes#Box", anchors.resolveReference("Box", "a/explicit.kt"))
        assertEquals("$linalg#Vec3", anchors.resolveReference("Vec3", "a/explicit.kt"))
        assertEquals("$ceres#Vec3", anchors.resolveReference("Vec3", "a/star.kt"))
    }

    @Test
    fun `an ambiguous name stays unlinked when scope picks none or two`() {
        assertNull(anchors.resolveReference("Box", "a/none.kt"))
        assertNull(anchors.resolveReference("Vec3", "a/both.kt"))
    }
}
