package afield.geometry.solid.manifold

import kotlin.test.*

class SampleSlicesTest {
    @Test fun samplesMidpointsAndClosesEveryNativeSection() {
        if (!ManifoldLibrary.isAvailable) {
            println("SKIPPED sampleSlices native test: ${ManifoldLibrary.describe()}")
            return
        }
        Solid.cube(2.0, 3.0, 4.0).use { solid ->
            val sections = mutableListOf<Section>()
            val sampled = solid.sampleSlices(4) { z, section ->
                sections += section
                z to section.area
            }
            val low = solid.bounds.min.z.toDouble()
            assertEquals(List(4) { low + it + 0.5 }, sampled.map { it.first })
            sampled.forEach { assertEquals(6.0, it.second, 1e-9) }
            assertTrue(sections.all { it.isClosed })
            var thrownSection: Section? = null
            assertFailsWith<IllegalStateException> {
                solid.sampleSlices(4) { _, section -> thrownSection = section; error("callback failed") }
            }
            assertTrue(thrownSection!!.isClosed)
            assertFailsWith<IllegalArgumentException> { solid.sampleSlices(0) { _, section -> section.area } }
        }
    }
}
