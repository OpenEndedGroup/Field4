package afield.clojure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard on **where a Clojure box's output goes**, which is the only part of the Clojure runtime
 * that is Field's own invention rather than Clojure's.
 *
 * [ClojureHost] evaluates a box one top-level form at a time instead of handing the whole buffer to
 * `load-string`, and the only reason to do that is the line number: printed text and thrown errors
 * have to come back attached to the form that caused them, because that is what every other kind of
 * Field box does and what the editor draws beside the line. That property is invisible in the
 * output itself — a box that reports every line as `-1` prints exactly the same text — so it needs
 * asserting rather than eyeballing. `public/docs/clojure.md` is the page that promises it.
 *
 * Deliberately no `Box` here. The host half has no Field in it, which makes this a plain unit test:
 * no server, no runloop, no document. The box half ([ClojureExecution]) adds `execute.line` to
 * everything below and is covered by the `docs.clojure.*` captures on that page.
 */
class ClojureHostTest {

    /** stdout, stderr and the final value from one run, each with the line it was attributed to. */
    private class Run(host: ClojureHost, code: String, executeIfFn: Boolean = false) {
        val out = mutableListOf<Pair<String, Int>>()
        val err = mutableListOf<Pair<String, Int>>()
        val result: EvalResult = host.evalPage(
            code,
            executeIfFn = executeIfFn,
            onStdout = { text, line -> out += text.trim() to line },
            onStderr = { text, line -> err += text.trim() to line },
        )

        val ok: EvalResult.Ok get() = result as? EvalResult.Ok ?: error("expected success, got $result")
        val error: CompileError get() = (result as? EvalResult.Err)?.error ?: error("expected failure, got $result")
    }

    private fun host() = ClojureHost(defaultNs = "field.test.host")

    @Test
    fun `printed text is attributed to the form that printed it`() {
        val run = Run(
            host(),
            """
            (println "first")

            (println "third")
            (defn twice [x] (* 2 x))
            (println (twice 21))
            """.trimIndent()
        )

        assertEquals(
            listOf("first" to 1, "third" to 3, "42" to 5),
            run.out,
            "each line of output should carry the 1-based line of its own top-level form"
        )
    }

    @Test
    fun `the last form's value carries its line and is printed as Clojure`() {
        val run = Run(
            host(),
            """
            (def xs [1 2 3])
            (mapv inc xs)
            """.trimIndent()
        )

        assertEquals(2, run.ok.result.line)
        // `toString` on a Clojure vector happens to agree here; `nil` and a map are where it does
        // not, which is why the box path publishes `printed` rather than `value.toString()`.
        assertEquals("[2 3 4]", run.ok.result.printed)
        // `def` evaluates to the *var*, not to nil — so a box that only defines things still has
        // something to echo, and the box path's "say nothing when the value is nil" rule does not
        // silence it. `println` and `(lines …)` are the forms that do return nil.
        assertEquals("#'field.test.host/only-a-def", Run(host(), "(def only-a-def 1)").ok.result.printed)
        assertEquals("nil", Run(host(), "(println)").ok.result.printed)
    }

    @Test
    fun `a runtime error reports the line of the form that threw`() {
        val run = Run(
            host(),
            """
            (println "before")
            (def n 0)
            (/ 1 n)
            (println "never")
            """.trimIndent()
        )

        assertEquals(3, run.error.line, "an ArithmeticException carries no line of its own: ${run.error}")
        assertTrue("Divide by zero" in run.error.message, "unexpected message: ${run.error.message}")
        assertEquals(
            listOf("before" to 1),
            run.out,
            "forms above the failing one should have run and printed; forms below it should not"
        )
    }

    @Test
    fun `a read error is reported by compileOnly, which is what stops the box evaluating`() {
        val host = host()
        val code = """
            (println "fine")
            (println "unbalanced"
        """.trimIndent()

        val compiled = host.compileOnly(code)
        val errors = (compiled as? CompileOnlyResult.Err)?.errors ?: error("expected a read error, got $compiled")
        assertTrue(errors.isNotEmpty(), "an Err with no errors in it tells the box nothing")
        assertTrue(
            errors[0].message.isNotBlank(),
            "the message is what the editor puts beside the line: ${errors[0]}"
        )
    }

    @Test
    fun `forms share one namespace across runs`() {
        val host = host()
        Run(host, "(def carried 7)")
        assertEquals("7", Run(host, "carried").ok.result.printed)
    }

    @Test
    fun `compileOnly counts top-level forms and evaluates none of them`() {
        val host = host()
        val counted = host.compileOnly(
            """
            (def a 1)
            (def b 2)
            (+ a b)
            """.trimIndent()
        )
        assertEquals(3, (counted as? CompileOnlyResult.Ok)?.formsCount)
        // If `compileOnly` had evaluated anything, `side-effect` would be bound by the time the
        // second call reads it.
        host.compileOnly("(def side-effect 1)")
        assertEquals("nil", Run(host, "(resolve 'side-effect)").ok.result.printed)
    }

    /**
     * The three things a Clojure box's completion has to reach, each of which used to be missing for
     * its own reason: `clojure.core` (the namespace was created but never initialised), `field.core`
     * (never referred), and a *local* binding (no `:context` was passed, so Compliment could not
     * see one). A candidate list that is merely non-empty satisfies none of them.
     */
    @Test
    fun `completion offers core names, Field's own names, and locals in scope`() {
        val host = host()
        fun candidatesFor(code: String): List<String> =
            host.complete(code, code.length).mapNotNull { it.displayText }

        assertTrue(
            "map" in candidatesFor("(ma"),
            "clojure.core is not reachable from a box namespace: ${candidatesFor("(ma")}"
        )
        assertTrue(
            "lines" in candidatesFor("(lin"),
            "field.core is not referred into a box namespace: ${candidatesFor("(lin")}"
        )
        val locals = candidatesFor("(let [alpha 1]\n  (+ alp")
        assertTrue("alpha" in locals, "a let-bound local is not offered: $locals")

        // The shape `public/docs/clojure.md` photographs, and the one that made context *balancing*
        // necessary. A local survives an unbalanced context only while the `let` binding it is in is
        // trivial: with `[spiral 1]` Compliment found it either way, and with a binding whose value
        // is itself a form it found it only once the context was closed. Both caret positions, so
        // the figure does not have to be posed around it.
        for (caret in listOf("(+ sp", "(lines [sp")) {
            val nested = candidatesFor("(let [spiral (polyline [[0 0] [10 10]])]\n  $caret")
            assertTrue("spiral" in nested, "no `spiral` at `$caret` under a nested binding: $nested")
            assertTrue("spit" in nested, "no core names at a two-character prefix: $nested")
        }
    }

    @Test
    fun `a completed name carries the remainder, its arglists and its docstring`() {
        val reduce = host().complete("(redu", 5).firstOrNull { it.displayText == "reduce" }
            ?: error("no `reduce` among the candidates for `(redu`")

        // The box path inserts at the caret rather than replacing a range, so the text is what is
        // left of the name after what was typed.
        assertEquals("ce", reduce.candidate)
        // One line, no quotes and no wrapping parens: this is the grey tail the popup renders.
        assertEquals("[f coll] [f val coll]", reduce.arglists)
        assertEquals("function", reduce.type, "the type is a Clojure keyword; the leading colon is not wanted")
        assertTrue(reduce.doc?.isNotBlank() == true, "no docstring, so the popup's panel is empty")
    }

    @Test
    fun `executeIfFn invokes a returned function only when asked`() {
        val code = "(fn [] (println \"called\") :done)"

        assertEquals(emptyList(), Run(host(), code).out)

        val invoked = Run(host(), code, executeIfFn = true)
        assertEquals(listOf("called" to 1), invoked.out)
        assertEquals(":done", invoked.ok.result.printed)
    }
}
