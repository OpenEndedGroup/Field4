package afield.graphquery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for reading graphs built out of **non-public** classes.
 *
 * A public property of a private (or internal) class still needs `setAccessible(true)` to
 * invoke reflectively. The walker used to set `isAccessible = includePrivate`, i.e. *false*
 * by default, so `prop.get` threw `IllegalAccessException`, the walker's `safeGet` turned
 * that into null, and traversal silently stopped one level in: paths appeared but every
 * value read as null and nothing nested was ever discovered.
 *
 * That is the common shape for a user's own model classes, so these cases matter more than
 * their obscurity suggests. Found by a smoke test whose fixtures happened to be private.
 */
class NonPublicClassAccessTest {

    private enum class Mode { OFF, ON }

    private class Osc(
        var freq: Float,
        var gain: Float,
        var label: String,
        var enabled: Boolean,
        var mode: Mode
    )

    private class Voice(val carrier: Osc, val modulator: Osc, var name: String)

    internal class InternalNode(var value: Float, var child: InternalNode? = null)

    private fun voice() = Voice(
        Osc(220f, 0.8f, "carrier", true, Mode.ON),
        Osc(440f, 0.2f, "modulator", false, Mode.OFF),
        "voice"
    )

    /** The walk must descend through a private class, not stop at its boundary. */
    @Test
    fun descendsIntoPrivateClasses() {
        val paths = voice().graphPaths("**").paths()

        assertTrue(paths.contains("carrier/freq"), "must descend into a private class: $paths")
        assertTrue(paths.contains("modulator/mode"), "must reach every leaf: $paths")
        assertEquals(13, paths.size, "3 root edges + 5 leaves per osc: $paths")
    }

    /** …and the values must actually be readable, not silently null. */
    @Test
    fun readsValuesOffPrivateClasses() {
        val v = voice()

        assertEquals(220f, v.graphPath("carrier/freq")!!.get())
        assertEquals("modulator", v.graphPath("modulator/label")!!.get())
        assertEquals(true, v.graphPath("carrier/enabled")!!.get())
        assertEquals(Mode.OFF, v.graphPath("modulator/mode")!!.get())

        val values = v.graphPaths("**").values()
        assertTrue(values.none { it == null }, "no slot should read as null: $values")
    }

    /** Writes must reach through the same accessors. */
    @Test
    fun writesThroughToPrivateClasses() {
        val v = voice()

        v.graphPath("carrier/freq")!!.set(110f)
        assertEquals(110f, v.carrier.freq, 0f)

        v.graphPaths("**").lenses<Boolean>().update { !it }
        assertEquals(false, v.carrier.enabled)
        assertEquals(true, v.modulator.enabled)
    }

    /** `internal` classes have the same JVM-visibility problem as private ones. */
    @Test
    fun descendsIntoInternalClasses() {
        val chain = InternalNode(1f, InternalNode(2f, InternalNode(3f)))
        val paths = chain.graphPaths("**").paths()

        assertTrue(paths.contains("child/child/value"), "must descend two levels: $paths")
        assertEquals(3f, chain.graphPath("child/child/value")!!.get())
    }

    /** The parameter head sees these slots too, which is the point of the fix. */
    @Test
    fun privateClassScalarsAreDrivable() {
        val v = voice()
        val freqs = v.graphPaths("**/freq").where { it.get() is Float }

        assertEquals(listOf("carrier/freq", "modulator/freq"), freqs.paths())
        assertEquals(listOf<Any?>(220f, 440f), freqs.values())
    }

    /** A private class reached through a collection is still fully traversable. */
    @Test
    fun privateClassesInsideCollections() {
        val byName = mutableMapOf<String, Any?>("a" to voice())
        assertEquals(220f, byName.graphPath("a/carrier/freq")!!.get())

        val list = mutableListOf<Any?>(voice())
        assertEquals(440f, list.graphPath("0/modulator/freq")!!.get())
    }

    /** includePrivate still controls *discovery*, independently of accessibility. */
    @Test
    fun includePrivateStillGovernsDiscovery() {
        class Secretive(var open: Float) {
            @Suppress("unused")
            private var closed: Float = 3f
        }

        val subject = Secretive(1f)
        assertEquals(listOf("open"), subject.graphPaths("**").paths())

        val withPrivate = subject.graphPaths("**", GraphWalkOptions(includePrivate = true))
        assertTrue(withPrivate.paths().contains("closed"), "${withPrivate.paths()}")
        assertEquals(3f, withPrivate["closed"]!!.get(), "and the private value is readable")
    }

    /**
     * A getter that genuinely throws does not derail *traversal* — the walk is guarded, so
     * every other slot is still discovered. Reading that one slot, however, propagates the
     * real exception rather than quietly reporting null: a caller who asks for a specific
     * value should see why it failed.
     */
    @Test
    fun throwingGettersDoNotDerailTheWalkButDoPropagateOnRead() {
        class Hostile {
            @Suppress("unused")
            val boom: String get() = error("nope")
            val fine: Float = 1f
        }

        val set = Hostile().graphPaths("**")
        assertNotNull(set["boom"], "the slot is still discovered")
        assertEquals(1f, set["fine"]!!.get(), "the rest of the walk is unaffected")
        assertFails("reading the hostile slot surfaces the real error") { set["boom"]!!.get() }
    }

    /** Excluding nulls also drops slots that cannot be read at all. */
    @Test
    fun includeNullsFalseOmitsUnreadableSlots() {
        class Hostile {
            @Suppress("unused")
            val boom: String get() = error("nope")
            val fine: Float = 1f
        }

        val paths = Hostile().graphPaths("**", GraphWalkOptions(includeNulls = false)).paths()
        assertEquals(listOf("fine"), paths)
    }
}
