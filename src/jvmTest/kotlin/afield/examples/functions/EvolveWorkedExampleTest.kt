package afield.examples.functions

import afield.stdlib.graphSignature
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the CPU-only [EvolveWorkedExample]: it must produce the committed SVG/trace
 * evidence, actually improve on the seed, and be deterministic under a fixed `rngSeed`.
 *
 * This is the guaranteed-verifiable floor for the doc walkthrough — no GPU, no window.
 */
class EvolveWorkedExampleTest {

    @Test
    fun producesDeterministicImprovingEvidence() {
        // Render into a temp dir so the test never depends on (or clobbers) the committed asset.
        val tmp = File.createTempFile("evolve-example", "").let {
            it.delete(); it.mkdirs(); it
        }
        val a = EvolveWorkedExample.render(tmp)

        // The run improved on the seed and recorded a trace.
        assertTrue(a.run.improved, "evolution should beat the seed: ${a.run.initialScore} -> ${a.run.bestScore}")
        assertTrue(a.run.bestScore > a.run.initialScore)
        assertTrue(a.run.steps.isNotEmpty(), "expected at least one accepted mutation")

        // The artifacts exist and are non-trivial, well-formed-ish.
        val svg = a.plotSvg.readText()
        assertTrue(svg.startsWith("<svg"), "svg header")
        assertTrue(svg.trimEnd().endsWith("</svg>"), "svg footer")
        assertTrue(svg.contains("polyline"), "svg should contain series polylines")
        assertTrue(svg.length > 500, "svg looks too small: ${svg.length} bytes")

        val trace = a.traceMarkdown.readText()
        assertTrue(trace.contains("| iteration |"), "trace table header")
        assertTrue(trace.contains("best score"), "trace summary")

        // Determinism: a second render with the same fixed seed reproduces the numbers and structure.
        val tmp2 = File.createTempFile("evolve-example2", "").let {
            it.delete(); it.mkdirs(); it
        }
        val b = EvolveWorkedExample.render(tmp2)
        assertEquals(a.run.bestScore, b.run.bestScore, 0.0, "bestScore must be reproducible")
        assertEquals(a.run.initialScore, b.run.initialScore, 0.0)
        assertEquals(a.run.best.graphSignature(), b.run.best.graphSignature(), "evolved graph must be reproducible")
        assertEquals(a.plotSvg.readText(), b.plotSvg.readText(), "svg must be byte-identical across runs")
        assertEquals(a.traceMarkdown.readText(), b.traceMarkdown.readText(), "trace must be byte-identical across runs")

        tmp.deleteRecursively()
        tmp2.deleteRecursively()
    }
}
