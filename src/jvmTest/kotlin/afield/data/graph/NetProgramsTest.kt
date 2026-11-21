package afield.data.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [NetPrograms] reduces to the answer ordinary evaluation gives, with nothing left stuck. */
class NetProgramsTest {
    @Test
    fun `arithmetic and a shared let reduce to a number`() {
        val run = NetPrograms.run(netProgram { main { let(n(3) + 4) { x -> x * x } } })
        assertEquals(49L, run.value)
        assertEquals(emptyList(), run.stuck)
    }

    @Test
    fun `a switch takes only its branch`() {
        val p = netProgram {
            val pick by fn()
            pick.def { k, y -> switch(k, zero = y) { j -> j * 100 + y } }
            main { pick(0, 7) + pick(3, 7) }
        }
        assertEquals(7L + 207L, NetPrograms.run(p).value)
    }

    @Test
    fun `recursion through REF computes fibonacci`() {
        val p = netProgram {
            val fib by fn()
            fib.def { k ->
                switch(k, zero = 0) { j ->
                    switch(j, zero = 1) { i -> fib(i + 1) + fib(i) }
                }
            }
            main { fib(12) }
        }
        val run = NetPrograms.run(p)
        assertEquals(144L, run.value)
        assertEquals(emptyList(), run.stuck)
        assertTrue(run.rounds < run.interactions, "the two recursive calls reduce side by side")
    }

    @Test
    fun `mutual recursion between functions declared up front`() {
        val p = netProgram {
            val isEven by fn()
            val isOdd by fn()
            isEven.def { k -> switch(k, zero = 1) { j -> isOdd(j) } }
            isOdd.def { k -> switch(k, zero = 0) { j -> isEven(j) } }
            main { isEven(10) * 10 + isOdd(7) }
        }
        assertEquals(11L, NetPrograms.run(p).value)
    }

    @Test
    fun `comparisons give one or zero, and ints work on either side`() {
        assertEquals(1L, NetPrograms.run(netProgram { main { (n(5) eq 5) + (6 and n(3)) - 2 } }).value)
    }

    @Test
    fun `a function given no body is reported by name`() {
        val e = assertFailsWith<IllegalArgumentException> {
            netProgram {
                val ghost by fn()
                main { ghost(1) }
            }
        }
        assertTrue("ghost" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a call with the wrong number of arguments is reported`() {
        val p = netProgram {
            val inc by fn()
            inc.def { k -> k + 1 }
            main { inc(1, 2) }
        }
        val e = assertFailsWith<IllegalArgumentException> { NetPrograms.compile(p) }
        assertTrue("inc takes 1" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `n-queens counts match backtracking`() {
        for (size in 1..6) {
            val run = NetPrograms.run(NetPrograms.queens(size))
            assertEquals(NetPrograms.queensReference(size), run.value, "queens($size)")
            assertEquals(emptyList(), run.stuck, "queens($size)")
        }
        val five = NetPrograms.run(NetPrograms.queens(5))
        assertEquals(10L, five.value)
        // The compiled net is unchanged by the DSL; the docs page quotes these numbers.
        assertEquals(4444, five.interactions)
        assertEquals(205, five.rounds)
        assertEquals(76, five.widest)
    }
}
