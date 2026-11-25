package afield.geometry.solid.manifold

import afield.geometry.mesh.boxMesh
import afield.geometry.mesh.signedVolume
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The public example of the fast JVM-only lane (`./gradlew fastTest`): plain kotlin.test against core
 * geometry, in one test JVM, with no Field server. The Manifold case shows a native-backed solid in the
 * same lane; like every Manifold test it skips, saying so, where `brew install manifold` has not run.
 */
class FastLaneSolidTest {
    @Test
    fun `a box mesh encloses its exact volume`() {
        assertEquals(24.0, boxMesh(2.0, 3.0, 4.0).signedVolume(), 1e-12)
    }

    @Test
    fun `a window cut from a wall removes exactly the window`() {
        org.junit.Assume.assumeTrue("FastLaneSolidTest: skipping the Manifold case — ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // A 4 x 0.2 x 3 wall with a 1 x 1.2 opening at sill height 0.9. The cutter overshoots the wall's
        // thickness so the difference is a clean through-hole rather than a coplanar sliver.
        Solid.cube(4.0, 0.2, 3.0).use { wall ->
            Solid.cube(1.0, 0.4, 1.2).use { cutter ->
                cutter.translated(1.5, -0.1, 0.9).use { window ->
                    (wall - window).use { opened ->
                        assertEquals(4.0 * 0.2 * 3.0 - 1.0 * 0.2 * 1.2, opened.volume, 1e-9)
                        assertEquals(1, opened.genus, "an opening clear of every edge is one hole through the wall")
                    }
                }
            }
        }
    }
}
