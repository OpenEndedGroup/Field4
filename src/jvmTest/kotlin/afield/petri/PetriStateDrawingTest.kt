package afield.petri

import afield.stdlib.filled
import afield.stdlib.thickness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The node bound on the state-graph drawing, and the honesty of what it draws instead.
 *
 * A drawing is not verified by compiling, and it is not verified by a screenshot either — a
 * screenshot says the picture is not black, and this file is the part a screenshot cannot check:
 * that the summary is a *structurally different* picture from the graph, that neither of its two
 * panels has quietly stopped being drawn, and that it costs under a twentieth of the thing it
 * replaces. The figures themselves come out of `PetriGallery` and were looked at by a person.
 *
 * Counts here are by FLine *thickness*, which sounds fragile and is the opposite: every piece of the
 * figure has its own weight — 2.2 for the dashed refusal rule, 1.0 for the histogram baseline, 3.0
 * for a path arrow, 1.8 for an end ring, 0.7 for a glyph — so counting by weight names the piece
 * rather than the total, and a total is exactly what a change to one piece hides.
 *
 * Note the shape of the caption assertion. `label` returns nothing rather than throwing when no font
 * resolves — a real case in a headless run, and one this test cannot control — so a bare "the
 * caption is present" would be a flake on some machines and a false pass on others. The test
 * **asserts its own premise**: it works out from public API whether this JVM produces glyph outlines
 * at all, and only then requires the words. Everything font-free is asserted unconditionally, which
 * is the reason the dashed rule exists at all.
 */
class PetriStateDrawingTest {

    private fun philosophers(n: Int, reverseLast: Boolean = false) = petri {
        conflict = PetriConflict.ORDER
        grid = cyc(1, 4)
        for (i in 0 until n) place("fork.$i", tokens = 1)
        for (i in 0 until n) {
            val left = i
            val right = (i + 1) % n
            val reversed = reverseLast && i == n - 1
            t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
            t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
            t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
        }
    }

    private val w = 1100.0
    private val h = 640.0

    /** How many dashes the refusal rule has at [w] — derived the way the drawing derives it. */
    private val dashes = ((w - 2 * 46.0) / 26.0).toInt()

    /**
     * True when this JVM can turn a string into glyph outlines.
     *
     * Worked out through the public API rather than by asking AWT: *under* the node bound the only
     * thing `labels` changes is the transition names along the path, so if the two drawings are the
     * same size then no glyph reached the picture.
     */
    private val glyphsAvailable: Boolean by lazy {
        val graph = philosophers(5).stateSpace()
        graph.toFLines(labels = true).size > graph.toFLines(labels = false).size
    }

    @Test
    fun `the eighty-two-marking table is still drawn in full, because that is the case the limit allows`() {
        val graph = philosophers(5).stateSpace()
        assertEquals(82, graph.stateCount, "the dining table is the tuning case and its size is pinned")
        assertFalse(graph.tooDenseToDraw(), "82 is under the limit of $PETRI_STATE_DRAW_LIMIT")

        // A dot per marking, a ring on the start and on each deadlock, and one FLine per firing.
        val drawn = graph.toFLines(w = w, h = h, labels = false)
        assertEquals(graph.stateCount + 1 + graph.deadMarkings.size + graph.edgeCount, drawn.size)
        assertEquals(graph.stateCount, drawn.count { it.filled }, "one filled dot per marking")
        assertEquals(0, drawn.count { it.thickness == 2.2 }, "nothing was declined, so no refusal rule")
        assertFalse(graph.summaryHtml().contains("drawing declined"))
    }

    @Test
    fun `above the bound the drawing declines the nodes and draws both panels instead`() {
        val graph = philosophers(9).stateSpace()
        assertEquals(2_786, graph.stateCount, "nine philosophers, pinned")
        assertEquals(9, graph.diameterFound)
        assertTrue(graph.tooDenseToDraw(), "2 786 is over the limit of $PETRI_STATE_DRAW_LIMIT")
        val trace = assertNotNull(graph.deadlockTrace())
        assertEquals(9, trace.length, "nine philosophers, nine grabs")

        val drawn = graph.toFLines(w = w, h = h, labels = false)
        val layers = graph.diameterFound + 1

        // Both panels, each counted by its own weight, so a panel that stopped being drawn fails
        // here rather than being absorbed into a total.
        assertEquals(dashes, drawn.count { it.thickness == 2.2 }, "the dashed refusal rule")
        assertEquals(1, drawn.count { it.thickness == 1.0 }, "the histogram baseline")
        assertEquals(trace.length, drawn.count { it.thickness == 3.0 }, "one arrow per firing")
        assertEquals(2, drawn.count { it.thickness == 1.8 }, "a ring on the start and on the end")
        assertEquals(
            layers + (trace.length + 1) + trace.length,
            drawn.count { it.filled },
            "a bar per layer, a dot per marking on the path, and an arrowhead per firing",
        )

        // The counterfactual, floored at a number and measured against the same graph drawn the old
        // way — not against "it differs", which 2 785 FLines would also satisfy.
        val insisted = graph.toFLines(w = w, h = h, labels = false, maxNodes = Int.MAX_VALUE)
        assertEquals(graph.stateCount + 1 + graph.deadMarkings.size + graph.edgeCount, insisted.size)
        assertTrue(
            drawn.size * 20 < insisted.size,
            "the summary is ${drawn.size} FLines against ${insisted.size} — too small a saving to be a refusal",
        )
        // …and a non-empty guard under it, so a blank figure cannot satisfy the line above.
        assertTrue(drawn.size >= 40, "only ${drawn.size} FLines — that is a blank figure, not a summary")

        // The card beside the picture has to tell the same story as the picture.
        assertTrue(graph.summaryHtml().contains("drawing declined above $PETRI_STATE_DRAW_LIMIT markings"))
    }

    @Test
    fun `the caption is on the figure whether or not the transition names are`() {
        if (!glyphsAvailable) {
            // Honest rather than green: this JVM resolved no font, `label` returned nothing by
            // design, and the words cannot be checked here. In that environment the dashed rule is
            // what carries the refusal, and that is asserted unconditionally above.
            println("PetriStateDrawingTest: no glyph outlines in this JVM — the caption is not checkable here")
            return
        }
        val graph = philosophers(9).stateSpace()
        val bare = graph.toFLines(w = w, h = h, labels = false)
        val named = graph.toFLines(w = w, h = h, labels = true)

        // The caption ignores `labels`, so glyphs must be present even with the names turned off.
        assertTrue(
            bare.count { it.thickness == 0.7 } > 40,
            "the caption is missing at labels = false: only ${bare.count { it.thickness == 0.7 }} glyphs",
        )
        // And naming the transitions must add strictly more on top of it.
        assertTrue(
            named.count { it.thickness == 0.7 } > bare.count { it.thickness == 0.7 },
            "the transition names and the widest-layer count add nothing",
        )
        // The structure is untouched by either — the caption is not stealing space from a panel.
        assertEquals(bare.count { it.filled }, named.count { it.filled })
    }

    @Test
    fun `a graph with no counterexample declines honestly rather than drawing nothing`() {
        // Breaking the symmetry removes the deadlock, so there is no path to show — which is the case
        // where a lazy implementation returns an empty list and the figure is a black rectangle.
        val graph = philosophers(9, reverseLast = true).stateSpace()
        assertEquals(false, graph.canDeadlock, "the asymmetric table cannot wedge")
        assertTrue(graph.tooDenseToDraw())
        assertNull(graph.deadlockTrace())

        val drawn = graph.toFLines(w = w, h = h, labels = false)
        assertEquals(dashes, drawn.count { it.thickness == 2.2 }, "the refusal is still marked")
        assertEquals(1, drawn.count { it.thickness == 1.0 }, "the histogram baseline is still there")
        assertEquals(0, drawn.count { it.thickness == 3.0 }, "there is no path, so there are no arrows")
        assertEquals(
            graph.diameterFound + 1,
            drawn.count { it.filled },
            "the layer bars survive with no counterexample at all",
        )
        assertTrue(drawn.size >= 40, "only ${drawn.size} FLines with no counterexample to draw")
    }

    @Test
    fun `the SVG the gallery and the docs write is well formed at every size`() {
        // Through `toSvg`, which is the call `PetriGallery` and the docs page make, so what is
        // checked is the artefact rather than the FLine list behind it. Temp directory; nothing
        // committed is touched.
        val dir = File(System.getProperty("java.io.tmpdir"), "petri-state-drawing-${System.nanoTime()}")
        dir.mkdirs()
        try {
            for ((name, graph) in listOf(
                "small" to philosophers(2).stateSpace(),
                "dense" to philosophers(5).stateSpace(),
                "declined" to philosophers(9).stateSpace(),
            )) {
                val svg = graph.toSvg(w = w, h = h)
                assertTrue(svg.contains("<svg"), "$name: not an SVG")
                assertTrue(svg.trimEnd().endsWith("</svg>"), "$name: truncated SVG")
                assertTrue(svg.length > 600, "$name: ${svg.length} bytes is a blank SVG")
                File(dir, "state-graph-$name.svg").writeText(svg)
            }
            // The declined figure against the same graph drawn the old way. Bytes rather than FLines
            // here on purpose: this is the number a docs page actually pays.
            val declined = philosophers(9).stateSpace()
            val summarised = declined.toSvg(w = w, h = h).length
            val hairball = declined.toSvg(w = w, h = h, maxNodes = Int.MAX_VALUE).length
            assertTrue(
                summarised * 5 < hairball,
                "the summarised SVG is $summarised bytes against the hairball's $hairball",
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
