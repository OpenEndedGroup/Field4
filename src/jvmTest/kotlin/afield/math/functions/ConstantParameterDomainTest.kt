package afield.math.functions

import afield.math.functions.Functions.c
import afield.math.functions.Functions.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** A constant configuring a function does not narrow its domain, however it was spelled. */
class ConstantParameterDomainTest {
    @Test
    fun `c, constant and dot-c configure an oscillator identically`() {
        val viaC = sin(frequency = c(2.0))
        val viaDotC = with(Functions) { sin(frequency = 2.0.c) }
        val viaConstant = sin(frequency = Functions.constant(2.0))
        assertEquals(viaDotC.domain, viaC.domain)
        assertEquals(viaDotC.domain, viaConstant.domain)
        assertEquals(viaDotC.eval(3.3), viaC.eval(3.3), 0.0)
    }

    @Test
    fun `a constant on its own keeps the default domain`() {
        assertEquals(Functions.Domain.DEFAULT, c(0.5).domain)
    }
}
