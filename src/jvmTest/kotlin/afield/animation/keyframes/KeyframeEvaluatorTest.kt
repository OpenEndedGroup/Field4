package afield.animation.keyframes

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.animation.interpolation.KeySegment
import afield.animation.interpolation.macro_interpolant
import afield.animation.interpolation.registerInterpolant
import afield.app.protocol.Rect
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.doResync
import afield.document.persistence.SavingContext
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.map
import afield.ui.visualization.h

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

    @Test
    fun theExactDerivativeAgreesWithAFineDifference() {
        val k = keys(Triple(0.0, 0.1, "bezierContinuous"), Triple(1.0, 0.9, "bezierFree"), Triple(2.0, 0.2, "linear"))
        listOf(0.13, 0.5, 0.87, 1.3, 1.7).forEach { t ->
            val h = 1e-6
            val numeric = (k.evaluateValueAt(t + h) - k.evaluateValueAt(t - h)) / (2 * h)
            near(numeric, k.evaluateDerivativeAt(t), 1e-4)
        }
        near(0.0, keys(Triple(0.0, 0.3, "hold"), Triple(1.0, 0.9, "linear")).evaluateDerivativeAt(0.5))
    }

    @Test
    fun aBezierSegmentIsSmoothAndMonotoneInTime() {
        // Continuous values everywhere along an eased segment: no step bigger than the curve can make.
        val k = keys(Triple(0.0, 0.0, "bezierContinuous"), Triple(1.0, 1.0, "linear"))
        var last = k.evaluateValueAt(0.0)
        (1..1000).forEach { i ->
            val v = k.evaluateValueAt(i / 1000.0)
            assertTrue(abs(v - last) < 0.01, "jump of ${abs(v - last)} at ${i / 1000.0}")
            last = v
        }
    }

    @Test
    fun editingFromCodeSavesWithTheBox() {
        val box = Box("kf_api")
        val k = KeyframeEvaluator("[]")
        k.associatedBox = box
        k.associatedName = "x"
        k.add(1.0, 0.5)
        k.add(0.0, 0.2, "hold")
        assertEquals(listOf(0.0, 1.0), KeyframeEvaluator(loadKeyframeData(box, "x")!!).getKeyframeTimes())
        k.edit { replaceAll { it.copy(value = it.value * 2) } }
        assertEquals(listOf(0.4, 1.0), KeyframeEvaluator(loadKeyframeData(box, "x")!!).getKeyframeValues())
        k.removeAt(0)
        assertEquals(1, KeyframeEvaluator(loadKeyframeData(box, "x")!!).getKeyframeCount())
        near(1.0, k.evaluateValueAt(5.0))
    }

    @Test
    fun aCustomKeyTypeShapesTheSegmentThatLeavesIt() {
        val box = Box("kf_custom")
        box.registerInterpolant("square", "u * u") { lerp(u * u) }
        val k = KeyframeEvaluator("""[{"time":0.0,"value":0.0,"type":"square"},{"time":2.0,"value":1.0,"type":"linear"}]""")
        k.associatedBox = box
        near(0.25, k.evaluateValueAt(1.0))            // u = 0.5, u² = 0.25
        near(0.5, k.evaluateDerivativeAt(1.0), 1e-4)   // d/dt of (t/2)² at t = 1
        near(1.0, k.evaluateValueAt(2.0))
    }

    @Test
    fun anUndefinedKeyTypeIsAStraightLine() {
        val k = KeyframeEvaluator("""[{"time":0.0,"value":0.0,"type":"nobody-defined-this"},{"time":1.0,"value":1.0,"type":"linear"}]""")
        k.associatedBox = Box("kf_undefined")
        near(0.3, k.evaluateValueAt(0.3))
    }

    @Test
    fun theInterpolantMacroKeepsTheSourceAndTheSourceIsSavedWithTheBox() {
        val box = Box("kf_macro")
        val insert = box.macro_interpolant(listOf("(\"ease\")", " { lerp(smoothstep(u)) }"))!!
        assertEquals("registerInterpolant(\"ease\", \"lerp(smoothstep(u))\") {\nlerp(smoothstep(u))\n}", insert.here)
        box.registerInterpolant("ease", "lerp(smoothstep(u))") { lerp(smoothstep(u)) }
        assertEquals("lerp(smoothstep(u))", org.json.JSONObject(box.getPropertyRaw("keyframeInterpolants") as String).getString("ease"))
        assertTrue(Box.ioPersistentProperties.any { it.toRegex().matches("keyframeInterpolants") }, "keyframeInterpolants is not saved")
    }

    @Test
    fun everyLibraryTypeStartsAndEndsOnItsKeys() {
        val before = Keyframe(-1.0, 0.9, "linear")
        val after = Keyframe(3.0, 0.1, "linear")
        KeyTypeLibrary.entries.forEach { e ->
            listOf(null to null, before to after).forEach { (b, a) ->
                val start = e.shape(KeySegment(0.0, 0.0, 0.0, 2.0, 0.2, 0.7, b, a))
                val end = e.shape(KeySegment(2.0, 1.0, 0.0, 2.0, 0.2, 0.7, b, a))
                assertTrue(abs(start - 0.2) < 1e-6, "${e.name} starts at $start, not 0.2")
                assertTrue(abs(end - 0.7) < 1e-6, "${e.name} ends at $end, not 0.7")
                (0..50).forEach { i ->
                    val v = e.shape(KeySegment(i / 25.0, i / 50.0, 0.0, 2.0, 0.2, 0.7, b, a))
                    assertTrue(v.isFinite(), "${e.name} is not finite at u=${i / 50.0}")
                }
            }
        }
        assertTrue(KeyTypeLibrary.entries.size >= 50, "the library has ${KeyTypeLibrary.entries.size} types")
    }

    @Test
    fun theMonotoneSplinesDoNotOvershoot() {
        val box = Box("kf_mono")
        listOf("spline.monotone", "spline.steffen").forEach { type ->
            val k = KeyframeEvaluator("""[{"time":0.0,"value":0.0,"type":"$type"},{"time":1.0,"value":1.0,"type":"$type"},{"time":2.0,"value":1.0,"type":"$type"},{"time":3.0,"value":0.0,"type":"linear"}]""")
            k.associatedBox = box
            (0..300).forEach { i ->
                val v = k.evaluateValueAt(i / 100.0)
                assertTrue(v >= -1e-9 && v <= 1.0 + 1e-9, "$type overshoots: $v at ${i / 100.0}")
            }
        }
    }

    // A recorded take: four seconds at 60 keys a second, a slow wave with a little jitter on it.
    private fun take(): KeyframeEvaluator {
        var seed = 1L
        fun jitter(): Double { seed = (seed * 16807) % 2147483647; return seed / 2147483647.0 - 0.5 }
        return KeyframeEvaluator("[]").apply {
            setKeys((0..240).map { i ->
                val t = i / 60.0
                Keyframe(t, 0.5 + 0.35 * kotlin.math.sin(t * 2.1) + 0.1 * kotlin.math.sin(t * 7) + 0.01 * jitter(), "linear")
            })
        }
    }

    @Test
    fun simplifyKeepsTheCurveWithinTheTolerance() {
        val k = take()
        val before = (0..4000).map { k.evaluateValueAt(it / 1000.0) }
        k.simplify(0.01)
        val after = (0..4000).map { k.evaluateValueAt(it / 1000.0) }
        val worst = before.zip(after).maxOf { (a, b) -> abs(a - b) }
        // The planner measures the error at the keys and eight times between each: a hair over is fine.
        assertTrue(worst <= 0.0105, "the simplified curve strays $worst from the take")
        assertTrue(k.getKeyframeCount() < 60, "241 keys should simplify to far fewer, got ${k.getKeyframeCount()}")
        near(before.first(), after.first())
        near(before.last(), after.last())
        assertTrue(k.getKeyframeTypes().all { it == "bezierContinuous" })
    }

    @Test
    fun simplifyLeavesLockedAndHoldKeysAsTheyAre() {
        val k = take()
        val keys = k.getKeyframes().toMutableList()
        keys[100] = keys[100].copy(locked = true)
        keys[150] = keys[150].copy(type = "hold")
        k.setKeys(keys)
        k.simplify(0.05)
        val left = k.getKeyframes()
        assertTrue(left.any { it.time == keys[100].time && it.locked && it.type == "linear" })
        assertTrue(left.any { it.time == keys[150].time && it.type == "hold" })
        // And the key the hold jumps to stays, so the jump happens where it did.
        assertTrue(left.any { it.time == keys[151].time })
    }

    @Test
    fun smoothRemovesJitterAndKeepsTheEnds() {
        val k = take()
        val first = k.getKeyframes().first()
        val last = k.getKeyframes().last()
        fun roughness() = k.getKeyframeValues().zipWithNext().zipWithNext().sumOf { (a, b) -> abs(b.second - 2 * a.second + a.first) }
        val rough = roughness()
        k.smooth(0.05)
        assertTrue(roughness() < rough * 0.3, "smoothing should take most of the jitter out: ${roughness()} from $rough")
        assertEquals(first, k.getKeyframes().first())
        assertEquals(last, k.getKeyframes().last())
        assertEquals(241, k.getKeyframeCount())
        // A straight line is left exactly where it is, ends included.
        val line = KeyframeEvaluator("[]").apply { setKeys((0..20).map { Keyframe(it * 0.1, 0.3 + it * 0.02, "linear") }) }
        line.smooth(0.3)
        line.getKeyframes().forEachIndexed { i, kf -> near(0.3 + i * 0.02, kf.value, 1e-9) }
    }
}
