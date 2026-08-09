package afield

import afield.stdlib.Quantity
import afield.stdlib.withUnit
import eu.sirotin.kotunil.base.m
import eu.sirotin.kotunil.base.mm
import eu.sirotin.kotunil.core.times
import eu.sirotin.kotunil.derived.N
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnitValueVisualizerTest {

    @Test
    fun `h renders a quantity as a numeric value and preferred unit`() {
        val html = ValueVisualizer.visualize(3.withUnit(mm))

        assertTrue("3" in html)
        assertTrue("afield-unit-symbol" in html)
        assertTrue("mm" in html)
        assertFalse("Quantity" in html)
    }

    @Test
    fun `h recognizes a bare derived expression`() {
        val html = ValueVisualizer.visualize(12.0 * N)

        assertTrue("12" in html)
        assertTrue(">N<" in html)
    }

    @Test
    fun `h preserves quantity units inside collections`() {
        val html = ValueVisualizer.visualize(listOf(2.withUnit(m), 350.withUnit(mm)))

        assertTrue(">m<" in html)
        assertTrue(">mm<" in html)
        assertFalse("Quantity" in html)
    }

    @Test
    fun `h2 delegates a scalar quantity to the unit aware h renderer`() {
        val html = LazyValueVisualizer.visualize(7.withUnit(mm))

        assertTrue("afield-unit-symbol" in html)
        assertTrue("mm" in html)
        assertFalse("value-viz-h2" in html)
    }

    @Test
    fun `h2 summarizes a homogeneous quantity collection with its unit`() {
        val values: List<Quantity> = listOf(1.withUnit(mm), 2.withUnit(mm), 3.withUnit(mm))
        val html = LazyValueVisualizer.visualize(values)

        assertTrue("value-viz-h2" in html)
        assertTrue("size=3" in html)
        assertTrue("· mm" in html)
        assertTrue("/h2-explorer" in html)
    }

    @Test
    fun `h2 labels unlike display units as mixed`() {
        val html = LazyValueVisualizer.visualize(listOf(1.withUnit(m), 2.withUnit(mm)))

        assertTrue("mixed units" in html)
    }
}
