package afield

import org.jetbrains.kotlinx.dataframe.api.groupBy
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf

/*
FIELD_TEST
id: kotlin.dataframe-visualizer.smoke
runner: jvm-main
entrypoint: afield.DataFrameVisualizerSmokeTest
tags: [smoke, kotlin, dataframe, visualization]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "DataFrame visualizer smoke passed"
*/
/**
 * Checks that DataFrame values render through Field's value registries — [DataFrameVisualizer].
 *
 * The assertions are deliberately about *dispatch and laziness* rather than exact markup, which
 * would make this a change-detector on the table renderer:
 *
 *  - the registry is reached at all (a frame must not fall through to `toString()`);
 *  - `.h` shows a head and says how much it is hiding, without materialising the frame;
 *  - `.h` on a large frame produces BOUNDED output — it must not contain the last row's data. That
 *    is the observable form of "only the head was rendered". An earlier version of this test counted
 *    lambda invocations on an added column instead and passed with a count of zero, because `add`
 *    materialises its column eagerly and cell access never re-invokes the lambda: the assertion was
 *    vacuous. Output content is the property worth pinning, since it is what a box actually shows.
 *
 * `install()` is called explicitly because `Server.main` — which calls it in the app — does not run
 * in a bare jvm-main harness.
 */
object DataFrameVisualizerSmokeTest {

    private const val ROWS = 5_000

    fun runTests(): Boolean {
        return try {
            println("Running DataFrame visualizer smoke...")
            DataFrameVisualizer.install()
            DataFrameVisualizer.install() // idempotent

            testFrameDispatchesToTheRegistry()
            testCompactFormIsBounded()
            testGroupByDispatches()
            testRowAndColumnRender()
            testScrollingThroughWindows()

            println("✅ DataFrame visualizer smoke passed")
            true
        } catch (t: Throwable) {
            println("❌ DataFrame visualizer smoke failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "DataFrame visualizer smoke failed" }
    }

    private fun smallFrame() = dataFrameOf("name", "age")("Alice", 15, "Bob", 30, "Cara", 45)

    private fun testFrameDispatchesToTheRegistry() {
        val rendered = smallFrame().h
        require(rendered.contains("Alice")) { "the frame's data never reached the output: $rendered" }
        require(rendered.contains("age")) { "column headers missing: $rendered" }
        require(rendered.contains("3 rows")) { "shape line missing: $rendered" }
        // A bare toString() would show the class name rather than a table.
        require(!rendered.contains("DataFrameImpl")) { "fell through to toString(): $rendered" }
        println("  .h renders a table with a shape line")
    }

    /**
     * Bounded output: the compact form of a 5,000-row frame must show the head and not the tail.
     *
     * Asserted on the rendered text rather than on any internal read count — see the note in the
     * class KDoc about the counting version of this test, which passed while measuring nothing.
     */
    private fun testCompactFormIsBounded() {
        val big = dataFrameOf("i")(*Array(ROWS) { it })

        val rendered = big.h
        require(rendered.contains("$ROWS rows")) { "shape line wrong for a big frame: ${rendered.take(200)}" }
        require(rendered.contains("0")) { "head missing from a big frame: ${rendered.take(200)}" }
        require(!rendered.contains("${ROWS - 1}")) {
            "the compact form contains the LAST row (${ROWS - 1}), so the whole frame is being " +
                "rendered into every box output"
        }
        require(rendered.length < 4_000) {
            "compact form of a $ROWS-row frame is ${rendered.length} chars — not a head"
        }
        println("  .h on a $ROWS-row frame is ${rendered.length} chars and omits the tail")

        val lazy = big.h2
        require(lazy.isNotEmpty()) { ".h2 produced nothing" }
        require(!lazy.contains("${ROWS - 1}")) {
            ".h2's first payload already contains the last row, so it is a dump rather than a window"
        }
        println("  .h2 first payload is ${lazy.length} chars and is a window, not the whole frame")
    }

    /**
     * `GroupBy` is the dispatch risk in this design: [ValuePrinterRegistry] checks the exact class,
     * then superclasses, then only *direct* interfaces — so an implementation that reaches its
     * interface transitively would silently fall through to `toString()`.
     */
    private fun testGroupByDispatches() {
        val grouped = smallFrame().groupBy("name")
        val rendered = grouped.h
        require(!rendered.contains("GroupByImpl") && !rendered.contains("@")) {
            "GroupBy fell through to toString() — the registry lookup only walks DIRECT interfaces, " +
                "so this needs the implementation class registered: $rendered"
        }
        require(rendered.contains("groups")) { "GroupBy summary missing: $rendered" }
        println("  GroupBy dispatches to the registry")
    }

    /**
     * The scroller, driven the way the client drives it.
     *
     * `.h2` mints an `h2-<uuid>` token, retains the *adapted* value behind it, and the browser pages
     * that token through `?root=…&kind=…&offset=…`, which lands in
     * [ValueVisualizer.renderLazyFragment]. So this walks a 5,000-row frame in windows and checks each
     * one shows its own rows and not the others — the difference between a scroller and a dump, and
     * the one claim about `.h2` that cannot be inferred from the compact form.
     */
    private fun testScrollingThroughWindows() {
        val big = dataFrameOf("i")(*Array(ROWS) { it })
        val rendered = big.h2

        val token = Regex("root=(h2-[0-9a-fA-F-]+)").find(rendered)?.groupValues?.get(1)
        require(token != null) { "no h2 token in the rendered output, so it cannot be paged: ${rendered.take(300)}" }

        // A window well into the frame must contain its own rows...
        val deep = ValueVisualizer.renderLazyFragment(token, "list", 2_000, 0)
        require(deep != null) { "paging returned nothing for offset 2000 — the token did not resolve" }
        require(deep.contains("2000")) { "window at offset 2000 does not contain row 2000" }
        require(!deep.contains("\"i\": 0,") && !deep.contains(">0<")) {
            "window at offset 2000 also contains row 0, so offsets are being ignored"
        }
        println("  scroll: offset 2000 window is ${deep.length} chars and starts at its own rows")

        // ...and a window at the end must reach the last row, which the compact form never showed.
        val tail = ValueVisualizer.renderLazyFragment(token, "list", ROWS - 5, 0)
        require(tail != null) { "paging returned nothing near the end of the frame" }
        require(tail.contains("${ROWS - 1}")) {
            "the final window does not contain the last row (${ROWS - 1}) — the frame is not fully scrollable"
        }
        println("  scroll: final window reaches row ${ROWS - 1}")
    }

    private fun testRowAndColumnRender() {
        val frame = smallFrame()

        val column = frame.columns().first().h
        require(column.contains("Alice")) { "column render missing values: $column" }

        val row = frame[0].h
        require(row.contains("Alice")) { "row render missing values: $row" }
        require(row.contains("age")) { "row render missing labels: $row" }
        println("  column and row both render through the registry")
    }
}
