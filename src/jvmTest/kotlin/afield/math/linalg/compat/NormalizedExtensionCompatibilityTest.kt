package afield.math.linalg.compat

import afield.math.linalg.*
import afield.math.linalg.normalized as libraryNormalized
import kotlin.test.Test
import kotlin.test.assertEquals

// A document/package may already have a no-argument direction policy, as Light does.
private fun Vector2dc.normalized(): Vector2d =
    if (length() < 1e-15) Vector2d() else Vector2d(x(), y()).normalize()

class NormalizedExtensionCompatibilityTest {
    @Test fun anExistingLocalDirectionPolicyIsNotSilentlyOverriddenByTheLibrary() {
        val tiny = Vector2d(1e-20, 0.0)
        assertEquals(0.0, tiny.normalized().length())
        assertEquals(1.0, tiny.libraryNormalized().length())
        assertEquals(0.0, tiny.libraryNormalized(epsilon = 1e-15).length())
    }
}
