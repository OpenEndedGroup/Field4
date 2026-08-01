package afield.docs

import afield.Box
import afield.morpho.MorphoCircuit
import afield.morpho.MorphoExecution
import afield.morpho.MorphoRuntime
import afield.morpho.flines
import afield.morpho.morphoPlaneWindow
import afield.morpho.planeBounds
import afield.stdlib.showPlaneGrid

/**
 * Render a MorphoHDL snippet the way a morpho box would, for a `data-lang=morpho` docs fence.
 *
 * The point is that the code shown on the page is **MorphoHDL**, not Kotlin that happens to mention
 * it. `docs_hooks.py` wraps a `morpho` fence's body into a call to this, exactly as it already does
 * for `nano` and `forth` — so the reader sees the language and the figure below it is that language
 * having been compiled, grown, laid out and drawn.
 *
 * Directive handling matches [MorphoExecution] rather than reimplementing it, so a snippet on the
 * page and the same text pasted into a box behave identically. That includes the refusal: no
 * `# main` line is an error here too, because a size-agnostic cell has no defensible default width.
 */
fun Box.renderMorphoDocsSnippet(source: String) {
    val directive = MorphoExecution.parseDirective(source)
        ?: throw IllegalStateException(
            "no '# main …' directive — a MorphoHDL docs snippet has to say which design to build " +
                "and how wide, e.g.  # main ripple_adder(8, 8, 1)"
        )

    // A snippet that defines no cells of its own still gets morpho's standard library, so a page can
    // show a one-line program. Same rule as a morpho box.
    val defines = runCatching { MorphoRuntime.cellNames(source) }.getOrDefault(emptyList())
    val full = if (defines.isEmpty()) MorphoRuntime.standardLibrary + "\n" + source else source

    MorphoCircuit.compile(
        source = full,
        design = directive.design,
        signature = directive.signature,
        optimize = directive.optimize,
    ).use { circuit ->
        circuit.growFully(relaxSteps = 240)
        println(circuit.summary())
        showPlaneGrid(
            circuit.summary() to circuit.flines(),
            columns = 1,
            cellWidth = 560,
            cellHeight = 560,
            window = morphoPlaneWindow(circuit.planeBounds()),
        )
    }
}
