package afield

import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scalar keyframe editor's evaluator (`keyframes(...)`, `afield/math/keyframes.kt`): what a box
 * reads back from the curve the person drew, and that the curve survives a save.
 */
class KeyframeEvaluatorTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun keys(vararg k: Triple<Double, Double, String>) =
        KeyframeEvaluator(k.joinToString(",", "[", "]") { (t, v, type) -> """{"time":$t,"value":$v,"type":"$type"}""" })

    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    @Test
    fun holdIsAStepThatJumpsExactlyAtTheNextKey() {
        val k = keys(Triple(0.0, 0.2, "hold"), Triple(1.0, 0.8, "linear"))
        near(0.2, k.evaluateValueAt(0.5))
        // The last 1/96 of a hold segment used to ramp toward the next value.
        near(0.2, k.evaluateValueAt(0.999))
        near(0.8, k.evaluateValueAt(1.0))
    }

    @Test
    fun linearIsExactBetweenKeys() {
        val k = keys(Triple(0.0, 0.0, "linear"), Triple(2.0, 1.0, "linear"), Triple(3.0, 0.0, "linear"))
        near(0.25, k.evaluateValueAt(0.5))
        near(0.6, k.evaluateValueAt(1.2))
        near(0.5, k.evaluateValueAt(2.5))
    }

    @Test
    fun aTypeTheEditorDoesNotKnowIsLinearAsItIsDrawn() {
        val k = keys(Triple(0.0, 0.0, "smooth"), Triple(1.0, 1.0, "linear"))
        near(0.3, k.evaluateValueAt(0.3))
    }

    @Test
    fun aBezierSegmentPassesThroughItsKeysAndStaysBetweenThemWhenFlat() {
        val k = keys(Triple(0.0, 0.0, "bezierFlat"), Triple(1.0, 1.0, "bezierFlat"))
        near(0.0, k.evaluateValueAt(0.0))
        near(1.0, k.evaluateValueAt(1.0))
        val mid = k.evaluateValueAt(0.5)
        assertTrue(mid > 0.3 && mid < 0.7, "a flat-handled ease should cross the middle near halfway, got $mid")
        // Flat handles: no overshoot anywhere in the segment.
        (0..100).forEach { i ->
            val v = k.evaluateValueAt(i / 100.0)
            assertTrue(v >= -1e-9 && v <= 1.0 + 1e-9, "flat bezier overshot at ${i / 100.0}: $v")
        }
    }

    @Test
    fun outsideTheKeysTheValueHolds() {
        val k = keys(Triple(1.0, 0.4, "linear"), Triple(2.0, 0.9, "linear"))
        near(0.4, k.evaluateValueAt(-5.0))
        near(0.9, k.evaluateValueAt(10.0))
        assertEquals(1.0 to 2.0, k.getTimeRange())
    }

    @Test
    fun theDerivativeOfALinearSegmentIsItsSlope() {
        val k = keys(Triple(0.0, 0.0, "linear"), Triple(2.0, 1.0, "linear"))
        near(0.5, k.evaluateDerivativeAt(1.0), 1e-6)
    }

    @Test
    fun editedKeysAreWrittenWhenTheDocumentIsSaved() {
        val directory = File.createTempFile("keyframe-save", "").also { it.delete(); it.mkdirs(); tempDirs += it }
        val box = Box("kf_box")
        box.name = "kf_box"
        box.setPropertyRaw("code", "val k = keyframes(name = \"x\")", doResync = false)
        box.frame = Rect(0.0, 0.0, 100.0, 50.0)
        saveKeyframeData(box, "x", """[{"time":0.0,"value":0.5,"type":"linear"}]""")

        val transfer = SavingContext().toTransfer(setOf(box), directory.absolutePath).single()
        assertTrue(
            "keyframeData" in transfer.properties.keys,
            "keyframeData was dropped from the save: ${transfer.properties.keys}"
        )
        val reloaded = KeyframeEvaluator(loadKeyframeData(box, "x")!!)
        assertEquals(listOf(0.0), reloaded.getKeyframeTimes())
        assertEquals(listOf(0.5), reloaded.getKeyframeValues())
    }
}
