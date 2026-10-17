package afield.tools.docs

import afield.tools.docs.StdlibReferenceGenerator.AnchorEntry
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
 * another file, and a member of a type this reference does not emit.
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
    fun `receivers are read from the signature`() {
        assertEquals("Box", StdlibReferenceGenerator.receiverOf("fun Box.animateEvolution(totalSteps: Int)", "animateEvolution"))
        assertEquals("List", StdlibReferenceGenerator.receiverOf("fun <T> List<T>.prefix(n: Int): List<T>", "prefix"))
        assertEquals("Vec", StdlibReferenceGenerator.receiverOf("val afield.math.Vec?.len: Double", "len"))
        assertNull(StdlibReferenceGenerator.receiverOf("fun polarization(v: Box.Thing): Double", "polarization"))
    }
}
