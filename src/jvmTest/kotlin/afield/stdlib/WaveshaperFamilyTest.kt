package afield.stdlib

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The waveshaper family `rectify` / `quantize` / `crush` — promoted from opaque
 * `mapY` lambdas to first-class rebuildable graph ops (alongside `clip`/`fold`/
 * `chebyshev`).
 *
 * For each op this proves it is genuinely first-class: (a) it survives a
 * serialize→rebuild graph round-trip (`deepCopy`) with its op and param children
 * intact and identical eval, and (b) it is a real `OpRegistry` transmutation
 * target reachable by canonical name, alias, and the rewrite DSL. Finally it
 * renders a visual-proof montage SVG into the worktree so a human can eyeball
 * that rectify flips/clamps negatives, quantize stairsteps, and crush bit-reduces.
 */
class WaveshaperFamilyTest {

    // ---- graph round-trip: op + params survive deepCopy, eval unchanged ----

    @Test
    fun rectifyRoundTripsThroughTheGraph() = with(Functions) {
        for (mode in listOf(Functions.RectifyMode.HALF, Functions.RectifyMode.FULL)) {
            val built = sin(2.0).rectify(mode)
            assertEquals("rectify", built.graphNode().op)
            assertEquals(mode.name, built.graphNode().strings["mode"], "mode tag stored on node")

            val rt = built.deepCopy()
            assertEquals("rectify", rt.graphNode().op)
            assertEquals(mode.name, rt.graphNode().strings["mode"], "mode survives round-trip")
            assertNotNull(rt.graphNode().child("source"), "source child preserved")
            for (i in 0..40) {
                val x = i / 40.0
                assertEquals(built.eval(x), rt.eval(x), 1e-12, "$mode eval after round-trip at x=$x")
            }
        }
    }

    @Test
    fun quantizeRoundTripsThroughTheGraph() = with(Functions) {
        val built = sin(2.0).quantize(5)
        assertEquals("quantize", built.graphNode().op)
        val rt = built.deepCopy()
        assertEquals("quantize", rt.graphNode().op)
        assertNotNull(rt.graphNode().child("source"), "source child preserved")
        assertNotNull(rt.graphNode().child("levels"), "levels param child preserved")
        for (i in 0..40) {
            val x = i / 40.0
            assertEquals(built.eval(x), rt.eval(x), 1e-12, "eval after round-trip at x=$x")
        }
    }

    @Test
    fun crushRoundTripsThroughTheGraph() = with(Functions) {
        val built = sin(2.0).bitCrush(3)
        assertEquals("crush", built.graphNode().op)
        val rt = built.deepCopy()
        assertEquals("crush", rt.graphNode().op)
        assertNotNull(rt.graphNode().child("source"), "source child preserved")
        assertNotNull(rt.graphNode().child("bits"), "bits param child preserved")
        for (i in 0..40) {
            val x = i / 40.0
            assertEquals(built.eval(x), rt.eval(x), 1e-12, "eval after round-trip at x=$x")
        }
    }

    // ---- OpRegistry transmutation: reachable by name, alias, and rewrite DSL ----

    @Test
    fun familyAreTransmutationTargetsInTheRegistry() = with(Functions) {
        for (op in listOf("rectify", "quantize", "crush")) {
            assertTrue(OpRegistry.isTransmutable(op), "$op must be transmutable")
            assertEquals("transform", OpRegistry.family(op), "$op is a value transform")
        }
        // aliases resolve to canonical
        assertEquals("rectify", OpRegistry.canonical("rectifier"))
        assertEquals("quantize", OpRegistry.canonical("quant"))
        assertEquals("crush", OpRegistry.canonical("bitcrush"))
        assertEquals("crush", OpRegistry.canonical("bitCrush"))

        // A value transform carries a `source` child, so transform => X succeeds.
        val source = sin(1.0).fold(1.0)
        for (op in listOf("rectify", "quantize", "crush")) {
            val rebuilt = OpRegistry.rebuild(source, op)
            assertNotNull(rebuilt, "rebuild(fold-node, $op) must be non-null")
            assertEquals(op, rebuilt.graphNode().op)
            for (i in 0..20) assertTrue(rebuilt.eval(i / 20.0).isFinite(), "$op finite at x=${i/20.0}")
        }

        // Reachable through the rewrite DSL too (canonical + alias).
        assertEquals("quantize", sin(1.0).clip(0.9).rewrite("clip => op=quantize").graphNode().op)
        assertEquals("crush", sin(1.0).clip(0.9).rewrite("clip => op=bitcrush").graphNode().op)
        assertEquals("rectify", sin(1.0).clip(0.9).rewrite("clip => op=rectify").graphNode().op)
    }

    @Test
    fun editorOpWordsSurfaceTheFamily() {
        // The reverse-sync guard already runs in FunctionsGraphRewriteTest, but pin the
        // three new words here too so a regression points straight at this feature.
        val js = File("src/commonMain/resources/fnrewrite.js").readText()
        for (word in listOf("\"rectify\"", "\"quantize\"", "\"crush\"")) {
            assertTrue(js.contains(word), "fnrewrite.js opWords must surface $word")
        }
    }

    // ---- numeric behaviour is preserved exactly (pinned to the math) ----

    @Test
    fun numericBehaviourIsPreserved() = with(Functions) {
        val drive = sin(2.0, domain = Functions.Domain.of(0.0, 1.0))
        for (i in 0..200) {
            val x = i / 200.0
            val v = drive.eval(x)
            // full-wave rectify == |v|; half-wave clamps negatives to 0
            assertEquals(kotlin.math.abs(v), drive.rectify(Functions.RectifyMode.FULL).eval(x), 1e-12)
            assertEquals(if (v < 0.0) 0.0 else v, drive.rectify(Functions.RectifyMode.HALF).eval(x), 1e-12)
        }
        // quantize snaps to exactly N distinct levels across the sweep
        val q = drive.quantize(5)
        val distinct = (0..1000).map { (q.eval(it / 1000.0) * 1e6).toLong() }.toSet()
        assertEquals(5, distinct.size, "quantize(5) must produce 5 distinct levels")
    }

    // ---- visual proof montage ----

    @Test
    fun rendersVisualProofMontage() = with(Functions) {
        val d = Functions.Domain.of(0.0, 1.0)
        val drive = sin(2.0, domain = d) // two cycles, values in [-1,1]
        data class Row(val label: String, val fn: Functions.Fn, val color: String)
        val rows = listOf(
            Row("rectify(FULL)  — |x|, negatives flip up", drive.rectify(Functions.RectifyMode.FULL), "#e6194b"),
            Row("rectify(HALF)  — negatives clamp to 0", drive.rectify(Functions.RectifyMode.HALF), "#3cb44b"),
            Row("quantize(6)    — 6-level staircase", drive.quantize(6), "#4363d8"),
            Row("crush(3)       — 3-bit reduction", drive.bitCrush(3), "#f58231")
        )
        val samples = 500
        val w = 720.0
        val h = 140.0
        val pad = 8.0

        fun polyline(pts: List<Vec2>, color: String, width: Double): String {
            val coords = pts.joinToString(" ") { v ->
                val px = pad + v.x * (w - 2 * pad)
                val py = h / 2 - v.y * (h / 2 - pad)
                "%.2f,%.2f".format(px, py)
            }
            return """<polyline fill="none" stroke="$color" stroke-width="$width" points="$coords"/>"""
        }

        val body = StringBuilder()
        val ranges = mutableListOf<Double>()
        rows.forEachIndexed { idx, row ->
            val pts = row.fn.toVec2List(samples, d)
            ranges += pts.map { it.y.toDouble() }.let { it.max() - it.min() }
            val y0 = idx * h
            body.append("""<g transform="translate(0,$y0)">""")
            body.append("""<line x1="$pad" y1="${h / 2}" x2="${w - pad}" y2="${h / 2}" stroke="#ccc" stroke-width="0.5"/>""")
            body.append(polyline(drive.toVec2List(samples, d), "#bbb", 1.0))
            body.append(polyline(pts, row.color, 1.8))
            body.append("""<text x="${pad + 4}" y="16" font-family="monospace" font-size="12" fill="${row.color}">${row.label}</text>""")
            body.append("</g>")
        }
        val totalH = rows.size * h
        val svg = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $totalH" width="$w" height="$totalH">
<rect width="$w" height="$totalH" fill="white"/>
$body
</svg>"""
        // A temp dir by default — a relative path here littered the repository (and every nightly
        // agent worktree) with a `waveshaper-family-proof.svg`. Set AFIELD_PROOF_DIR to collect it.
        val out = File(afield.proofDir("waveshaper-family"), "waveshaper-family-proof.svg")
        out.writeText(svg)
        println("[waveshaper-family] wrote visual proof to ${out.absolutePath} (${out.length()} bytes)")

        // Guard the render is real: every shaped curve must actually swing.
        ranges.forEachIndexed { i, r ->
            assertTrue(r > 0.3, "${rows[i].label} output range $r too small — flat/garbled render")
        }
    }
}
