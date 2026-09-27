package afield.rectsl.study

import afield.rectsl.compileRectSLWithSource
import afield.rectsl.rectSLKnobs
import afield.rectsl.setRectSLKnobs
import afield.stdlib.SourceStudy
import afield.stdlib.StudyRejection
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the RectSL study gained so it does what the StackSL one does: labels from the evaluator's own
 * record, values checked against what reads them, a point probe, an export budget, the window pinned to
 * a line, and `#rectsl` help inside Kotlin.
 */
class RectSLParityTest {
    private val folders = mutableListOf<java.io.File>()

    @AfterTest
    fun removeFolders() = folders.forEach { it.deleteRecursively() }

    private fun study(scene: String): RectSLStudy {
        var text = ""
        val s = RectSLStudy(Files.createTempDirectory("rectsl-parity").toFile().also { folders += it }, { text }, { expected, next ->
            if (text == expected) { text = next; true } else false
        })
        s.edit(SourceStudy.EMPTY, SourceStudy.hash(""), scene, "first draft")
        return s
    }

    private val scene = "->wall 16 9 meters\n->duration 4\n->fps 20\n\"sun\" rect 0 2 ->xy 2 ->square 0xffcc33 solid ->fill ;\n\"moon\" rect 5 -2 ->xy 1 ->square ;\n"

    @Test
    fun theEvaluatorRecordsWhichWordTookEachNumber() {
        val compiled = compileRectSLWithSource(scene)
        val square = scene.indexOf("2 ->square")
        assertEquals("->square" to 0, compiled.takenBy[square], "the 2 before ->square is what ->square took, from the top")
        val x = scene.indexOf("0 2 ->xy")
        assertEquals("->xy" to 1, compiled.takenBy[x], "->xy takes y first, then x")
        // Where the guess already names that word, it stands; the record corrects only a guess that does not.
        assertEquals("->square", rectSLKnobs(scene, compiled.takenBy).first { it.start == square }.role)
    }

    @Test
    fun aMaterialsNumbersAreLabelledAsTheStackSLCompilerTakesThem() {
        val withMaterial = scene + "material glow '( uv 4 * fbm palette )\n"
        val knobs = rectSLKnobs(withMaterial, compileRectSLWithSource(withMaterial).takenBy)
        val four = knobs.first { it.start == withMaterial.indexOf("4 * fbm") }
        assertEquals("material glow: multiplies", four.role)
    }

    @Test
    fun aValueTheReaderCannotHoldIsRefusedWithTheReason() {
        val knobs = rectSLKnobs(scene)
        val fps = knobs.indexOfFirst { it.role == "->fps frames per second" }
        val wall = knobs.indexOfFirst { it.role == "->wall width" }
        assertTrue("must be more than 0" in assertFailsWith<IllegalArgumentException> { setRectSLKnobs(scene, mapOf(fps to 0.0)) }.message!!)
        assertTrue("must be more than 0" in assertFailsWith<IllegalArgumentException> { setRectSLKnobs(scene, mapOf(wall to -3.0)) }.message!!)
        val colour = knobs.indexOfFirst { it.text.startsWith("0x") }
        assertTrue("24-bit" in assertFailsWith<IllegalArgumentException> { setRectSLKnobs(scene, mapOf(colour to 2e8)) }.message!!)
        assertTrue(setRectSLKnobs(scene, mapOf(fps to 30.0)).contains("->fps 30"))
    }

    @Test
    fun traceProbesAWallPoint() {
        val s = study(scene)
        @Suppress("UNCHECKED_CAST")
        val probe = s.trace(probeX = 0.0, probeY = 2.0).facts["probe"] as Map<String, Any?>
        val covering = probe["covering"] as List<Map<String, Any?>>
        assertEquals("sun", covering.first()["id"])
        assertEquals(4, covering.first()["line"], "the line that made it")
        @Suppress("UNCHECKED_CAST")
        val empty = s.trace(probeX = -7.5, probeY = -4.0).facts["probe"] as Map<String, Any?>
        assertTrue((empty["meaning"] as String).startsWith("nothing visible"))
    }

    @Test
    fun anExportOverItsBudgetIsRefusedWithASizeThatFits() {
        val s = study(scene)
        val folder = Files.createTempDirectory("rectsl-parity-export").toFile().also { folders += it }
        val refused = assertFailsWith<StudyRejection> {
            s.startExport("current", SourceStudy.hash(scene), java.io.File(folder, "big.png"), "png", size = 4096, maxMinutes = 1e-7)
        }
        assertTrue("over the" in refused.message!!, refused.message)
        val started = s.startExport("current", SourceStudy.hash(scene), java.io.File(folder, "small.png"), "png", size = 160)
        assertTrue((started.facts["estimatedSeconds"] as Double) >= 0.0)
    }

    @Test
    fun theWindowCanShowTheSceneAsItStandsAfterALine() {
        val s = study(scene)
        val afterSun = assertNotNull(s.drawableThrough(scene, 4))
        assertEquals(listOf("sun"), afterSun.scene.sample(0.0).rects.map { it.id })
        assertEquals(setOf("sun", "moon"), assertNotNull(s.drawableThrough(scene, 5)).scene.sample(0.0).rects.map { it.id }.toSet())
    }

    @Test
    fun theCardCapturesAKeyAndPinsTheWindowToALine() {
        val s = study(scene)
        val viewer = RectSLStudyViewer(s, afield.Box("rectsl_parity_card"))
        assertEquals("captured dusk", viewer.handleAction("""{"action":"capture-key","name":"dusk","values":{"0":16.0,"5":3.0}}"""))
        assertTrue("\"name\":\"dusk\",\"applies\":true" in viewer.html(), "the card offers the key")
        assertEquals(listOf("dusk: k5 2→3"), s.inspect().facts["keys"], "Inspect says what the key would change")
        assertEquals("the window shows the scene as it stands after line 4", viewer.handleAction("""{"action":"pin","line":4}"""))
        assertEquals("the window shows the whole scene", viewer.handleAction("""{"action":"pin","line":4}"""), "the same line again unpins")
    }

    @Test
    fun aHashRectSLStringInKotlinCompletesAsRectSL() {
        val code = "val a = 1\nval scene = \"\"\"\n#rectsl\n\"dot\" rect mate\n\"\"\"\n"
        val at = code.indexOf("mate") + 4
        val completion = assertNotNull(EmbeddedRectSL.complete(code, at))
        assertTrue(completion.metadata.any { it.text == "material" }, completion.metadata.map { it.text }.toString())
        assertEquals(code.indexOf("mate"), completion.boundsStart)
        assertNull(EmbeddedRectSL.complete(code, code.indexOf("= 1")))
    }
}
