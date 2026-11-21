package afield.data.graph

import afield.data.graph.NetExprScope.and
import afield.data.graph.NetExprScope.call
import afield.data.graph.NetExprScope.eq
import afield.data.graph.NetExprScope.let
import afield.data.graph.NetExprScope.minus
import afield.data.graph.NetExprScope.n
import afield.data.graph.NetExprScope.plus
import afield.data.graph.NetExprScope.switch
import afield.data.graph.NetExprScope.times
import afield.data.graph.NetExprScope.v
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [NetPrograms] reduces to the answer ordinary evaluation gives, with nothing left stuck. */
class NetProgramsTest {
    @Test
    fun `arithmetic and a shared let reduce to a number`() {
        val run = NetPrograms.run(netProgram { main { let("x", n(3) + n(4)) { v("x") * v("x") } } })
        assertEquals(49L, run.value)
        assertEquals(emptyList(), run.stuck)
    }

    @Test
    fun `a switch takes only its branch`() {
        val p = netProgram {
            def("pick", "k", "y") { switch(v("k"), zero = v("y"), pred = "j") { v("j") * n(100) + v("y") } }
            main { call("pick", n(0), n(7)) + call("pick", n(3), n(7)) }
        }
        assertEquals(7L + 207L, NetPrograms.run(p).value)
    }

    @Test
    fun `recursion through REF computes fibonacci`() {
        val p = netProgram {
            def("fib", "k") {
                switch(v("k"), zero = n(0), pred = "j") {
                    switch(v("j"), zero = n(1), pred = "i") { call("fib", v("i") + n(1)) + call("fib", v("i")) }
                }
            }
            main { call("fib", n(12)) }
        }
        val run = NetPrograms.run(p)
        assertEquals(144L, run.value)
        assertEquals(emptyList(), run.stuck)
        assertTrue(run.rounds < run.interactions, "the two recursive calls reduce side by side")
    }

    @Test
    fun `comparisons give one or zero`() {
        assertEquals(1L, NetPrograms.run(netProgram { main { (n(5) eq n(5)) + (n(6) and n(3)) - n(2) } }).value)
    }

    @Test
    fun `n-queens counts match backtracking`() {
        for (size in 1..6) {
            val run = NetPrograms.run(NetPrograms.queens(size))
            assertEquals(NetPrograms.queensReference(size), run.value, "queens($size)")
            assertEquals(emptyList(), run.stuck, "queens($size)")
        }
        assertEquals(10L, NetPrograms.queensReference(5))
    }
}
