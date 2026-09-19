package afield.examples.stdlib

import afield.graphics.FLine
import afield.graphics.drawToSVG
import afield.stdlib.*
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Writes the figure for `dev_docs/INTERACTION_NETS_LAMBDA.md`: one reduction, one panel per step, left to right.
 *
 * `(λx. x x) (λy. y)` is the term, because it is the smallest one that shows the whole story — a β against the
 * abstraction, a fan built by [LambdaTerms.compile] for the variable used twice, `dup-lam` duplicating an abstraction
 * whose binder is wired straight to its own body (the case that used to throw), and `fan-fan` annihilating the two
 * halves of that duplication.
 *
 * Run it through the smoke harness's own launcher:
 *
 * ```
 * python3 tools/field_backdoor.py --port 9101 run-jvm-main \
 *     afield.examples.stdlib.LambdaNetFigure --arg <absolute output path>.svg
 * ```
 *
 * Each panel is fitted independently, so a panel's scale says nothing about the next one's — what a panel shows is
 * the *shape* of the net at that step, and the agent count in the caption is what says how big it was.
 */
object LambdaNetFigure {

    private const val TERM = "(\\x. x x) (\\y. y)"

    private const val PANEL_WIDTH = 300.0
    private const val PANEL_HEIGHT = 300.0
    private const val GUTTER = 14.0
    private const val PADDING = 26.0

    @JvmStatic
    fun main(args: Array<String>) {
        val target = args.firstOrNull()
        check(target != null) { "usage: LambdaNetFigure <output .svg path>" }

        val term = LambdaTerms.parse(TERM)
        val compiled = LambdaTerms.compile(term)
        // 8 relax iterations per step, unlike the headless default of 0: this one *is* the picture.
        val reducer = Reducer(Lambda.rules(), relaxIterations = 8)

        val stages = ArrayList<Pair<String, String>>()
        compiled.net.relax(iterations = 60)
        stages += "compiled" to snapshotDescription(compiled.net)

        var step = 0
        while (true) {
            val detail = reducer.stepDetailed(compiled.net) ?: break
            step++
            compiled.net.relax(iterations = 24)
            stages += "$step. ${detail.ruleName}" to snapshotDescription(compiled.net)
            check(step < 40) { "Reduction did not settle in 40 steps" }
        }

        val readback = LambdaTerms.readback(compiled)
        val oracle = LambdaTerms.referenceNormalOrder(term)

        // Panels are drawn from the *live* net at each stage, so the net has to be re-reduced once per panel: the
        // reducer mutates in place and there is no snapshotting. Cheap at this size, and it keeps the panels honest
        // about what the reducer actually did rather than reconstructing it.
        val panelLines = ArrayList<FLine>()
        for ((index, stage) in stages.withIndex()) {
            val net = replay(index)
            val x0 = index * (PANEL_WIDTH + GUTTER)
            panelLines += frame(x0)
            panelLines += fit(net, x0)
            check(stage.second.isNotEmpty()) { "empty stage description" }
        }

        val width = stages.size * PANEL_WIDTH + (stages.size - 1) * GUTTER

        // NO BACKGROUND PLATE. This used to prepend an opaque white rectangle over the whole figure,
        // and it was the only one of the 30 committed SVGs under public/docs to paint a background at
        // all -- so on the dark documentation site it showed up as a white slab with the page dark
        // around it. Every sibling figure is transparent and inherits the page, and now so is this.
        // The strokes and discs are pale (#BDEBEE, #C7C5E4, #FFF5E6 and friends), which is why they
        // read fine either way; the plate was never doing any work.
        val svg = drawToSVG(panelLines, width, PANEL_HEIGHT)
        File(target).writeText(svg)

        println("term        : $TERM")
        println("panels      : ${stages.size} (${stages.joinToString(" | ") { it.first }})")
        for ((label, description) in stages) println("  ${label.padEnd(18)} $description")
        println("readback    : ${LambdaTerms.format(readback)}")
        println("oracle      : ${LambdaTerms.format(oracle)}")
        println("agree       : ${LambdaTerms.alphaEquivalent(readback, oracle)}")
        println("wrote       : $target (${File(target).length()} bytes, ${width.toInt()}x${PANEL_HEIGHT.toInt()})")
        check(LambdaTerms.alphaEquivalent(readback, oracle)) { "Figure term did not reduce correctly" }
    }

    /** Re-runs the reduction from scratch and stops after [steps] reactions, so each panel gets its own net. */
    private fun replay(steps: Int): Net {
        val compiled = LambdaTerms.compile(LambdaTerms.parse(TERM))
        val reducer = Reducer(Lambda.rules(), relaxIterations = 8)
        compiled.net.relax(iterations = 60)
        repeat(steps) {
            reducer.stepDetailed(compiled.net)
            compiled.net.relax(iterations = 24)
        }
        return compiled.net
    }

    private fun snapshotDescription(net: Net): String {
        val byType = net.nodes.groupingBy { it.type.name }.eachCount().toSortedMap()
        return "${net.nodes.size} agents: " + byType.entries.joinToString(", ") { "${it.key} x${it.value}" }
    }

    /** A hairline box around a panel, so the reader can see where one net stops and the next begins. */
    private fun frame(x0: Double): List<FLine> = listOf(
        fline {
            moveTo(x0 + 2.0, 2.0)
            lineTo(x0 + PANEL_WIDTH - 2.0, 2.0)
            lineTo(x0 + PANEL_WIDTH - 2.0, PANEL_HEIGHT - 2.0)
            lineTo(x0 + 2.0, PANEL_HEIGHT - 2.0)
            close()
            filled = false
            stroked = true
            thickness = 1.0
            color = vec(0.78, 0.80, 0.84, 1.0)
        }
    )

    /**
     * The net's lines, scaled and centred into the panel at [x0].
     *
     * Fitted per panel rather than once for the whole figure: the nets differ in extent by several times over a
     * reduction, and a single scale makes the last panels a dot. The cost is that panel scales are not comparable,
     * which the KDoc above says out loud.
     */
    private fun fit(net: Net, x0: Double): List<FLine> {
        val lines = net.toFLines()
        if (lines.isEmpty() || net.nodes.isEmpty()) return emptyList()
        val points = net.nodes.map { net.positionOf(it) }
        // The node radius is about 20 and the layout positions are centres, so the extent has to allow for it.
        val minX = points.minOf { it.x.toDouble() } - 34.0
        val maxX = points.maxOf { it.x.toDouble() } + 34.0
        val minY = points.minOf { it.y.toDouble() } - 34.0
        val maxY = points.maxOf { it.y.toDouble() } + 34.0
        val scale = min(
            (PANEL_WIDTH - 2 * PADDING) / max(1.0, maxX - minX),
            (PANEL_HEIGHT - 2 * PADDING) / max(1.0, maxY - minY)
        )
        val offsetX = x0 + PANEL_WIDTH / 2.0 - (minX + maxX) / 2.0 * scale
        val offsetY = PANEL_HEIGHT / 2.0 - (minY + maxY) / 2.0 * scale
        return lines.map { line ->
            line.transform { p -> vec(p.x * scale + offsetX, p.y * scale + offsetY, p.z.toDouble()) }
        }
    }
}
