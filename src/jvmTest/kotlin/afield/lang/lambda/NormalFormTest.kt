package afield.lang.lambda

import afield.data.graph.ActivationOrder
import afield.data.graph.Lambda
import afield.data.graph.Reducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A reduced λ net still has `ROOT` facing its answer; [isNormal] is the termination check to use. */
class NormalFormTest {
    @Test
    fun `a reduced net is normal although ROOT still faces the answer`() {
        val compiled = LambdaTerms.compile(LambdaTerms.parse("(\\x. x) (\\y. y)"))
        val rules = Lambda.rules()
        assertFalse(compiled.net.isNormal(rules), "the redex is reducible before reduction")
        Reducer(rules, order = ActivationOrder.FIRST_FOUND, relaxIterations = 0).reduceDetailed(compiled.net, maxSteps = 100) { _, _, _ -> }
        assertTrue(compiled.net.isNormal(rules))
        assertEquals(0, compiled.net.reduciblePairs(rules).size)
        assertTrue(compiled.net.activePairs().isNotEmpty(), "ROOT ⟂ LAM is an active pair no rule covers")
    }
}
