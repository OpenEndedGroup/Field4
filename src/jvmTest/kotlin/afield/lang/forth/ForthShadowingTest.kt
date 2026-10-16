package afield.lang.forth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Redefining a builtin is announced, and `forget` brings the builtin back rather than leaving nothing. */
class ForthShadowingTest {
    @Test
    fun forgettingARedefinedBuiltinRestoresIt() {
        val forth = ForthInterpreter()
        val printed = StringBuilder()
        forth.onOutput = { printed.append(it) }
        forth.eval(": dup 99 ; 1 dup")
        assertTrue("redefines the builtin 'dup'" in printed.toString(), printed.toString())
        assertEquals(99.0, (forth.stack.last() as Number).toDouble(), "the user's dup ran")
        forth.stack.clear()
        forth.eval("forget dup 7 dup")
        assertEquals(listOf(7.0, 7.0), forth.stack.map { (it as Number).toDouble() }, "the builtin dup is back")
    }
}
