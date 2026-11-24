package afield.ui.visualization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FLineSignatureTest {
    @Test
    fun oneLongRunIsEncodedWholeNotCutInTwo() {
        assertEquals("M(Lx140)", ValueVisualizer.shownFLineSignature("M" + "L".repeat(140)))
        assertEquals("MLLLZ", ValueVisualizer.shownFLineSignature("MLLLZ"))
    }

    @Test
    fun longMixedPathsKeepWholeTokensAtBothEnds() {
        val sig = "MLC".repeat(60)
        val shown = ValueVisualizer.shownFLineSignature(sig)
        val (head, tail) = shown.split(" … ")
        assertTrue(head.length <= 42 && tail.length <= 22, shown)
        assertTrue(sig.startsWith(head) && sig.endsWith(tail), shown)
        val runs = ValueVisualizer.shownFLineSignature("M" + "L".repeat(30) + "MC".repeat(40) + "M" + "L".repeat(50))
        assertTrue(runs.startsWith("M(Lx30)MC") && runs.endsWith("M(Lx50)"), runs)
    }
}
