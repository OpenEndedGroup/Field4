package afield.four.modules

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The carry-over wrappers a module pointer's `reload` puts on its [Parameters]' resolvers. A reload is
 * handed `current.parameters.copy()`, which brings the previous reload's wrapper along; the pointers
 * used to wrap that, so the chain grew one closure per reload. This drives the same sequence — wrap,
 * copy forward, wrap again — without a renderer, since `Parameters` itself needs one.
 */
class ReloadResolversTest {
    @Test
    fun repeatedReloadsKeepTheWrapperDepthConstant() {
        var originalCalls = 0
        val user: (String) -> String? = { originalCalls++; "user:$it" }
        var carried: Map<String, String> = emptyMap()

        var resolver: (String) -> String? = user
        repeat(100) { reload ->
            resolver = reloadWrap(resolver) { original, name -> carried[name] ?: original(name) }
            assertEquals(1, reloadWrapDepth(resolver), "after reload ${reload + 1}")
        }

        assertEquals("user:a", resolver("a"))
        assertEquals(1, originalCalls, "the user's resolver is reached once, not through a hundred layers")
        carried = mapOf("a" to "carried")
        assertEquals("carried", resolver("a"))
    }

    @Test
    fun aResolverTheUserReplacesIsWhatTheNextReloadWraps() {
        var resolver: (String) -> String? = reloadWrap<String, String?>({ "first" }) { original, name -> original(name) }
        resolver = { "replaced" }
        resolver = reloadWrap(resolver) { original, name -> original(name) }
        assertEquals(1, reloadWrapDepth(resolver))
        assertEquals("replaced", resolver("x"))
    }
}
