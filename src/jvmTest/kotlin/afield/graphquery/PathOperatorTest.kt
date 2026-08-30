package afield.graphquery

import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The `/` operator over object graphs, and — more importantly — the resolution rules that
 * make defining it on `Any?` survivable. Half of this file is about what `/` must *not*
 * steal: member `div`s, and other extensions with a more specific receiver.
 */
class PathOperatorTest {

    class Osc(var freq: Float, var gain: Float, var label: String)

    class Rig(val carrier: Osc, val modulator: Osc, var name: String)

    private fun rig() = Rig(Osc(220f, 0.8f, "sine"), Osc(440f, 0.2f, "saw"), "rig")

    // ── the sugar itself ────────────────────────────────────────────────────

    @Test
    fun divIsGraphPaths() {
        val rig = rig()

        assertEquals(rig.graphPaths("**").paths(), (rig / "**").paths())
        assertEquals(
            listOf("carrier/freq", "modulator/freq"),
            (rig / "**/freq").paths()
        )
    }

    @Test
    fun divComposesWithTheRestOfTheSurface() {
        val rig = rig()
        val high = (rig / "**/freq").whereValueIs<Float> { it > 300f }

        assertEquals(listOf("modulator/freq"), high.paths())

        // Still live lenses, not a snapshot.
        high.lenses<Float>().set(100f)
        assertEquals(100f, rig.modulator.freq)
    }

    @Test
    fun chainingSplitsThePatternWithoutLosingTheAddress() {
        val rig = rig()

        assertEquals((rig / "carrier/freq").paths(), (rig / "carrier" / "freq").paths())
        assertEquals(listOf("carrier/freq"), (rig / "carrier" / "freq").paths())

        // And the slot reached that way is the same one, writable through the longer chain.
        (rig / "carrier" / "freq").lenses<Float>().set(330f)
        assertEquals(330f, rig.carrier.freq)
    }

    @Test
    fun chainingFromASetFansOutOverEveryRef() {
        val rig = rig()

        assertEquals(
            listOf("carrier/freq", "carrier/gain", "carrier/label", "modulator/freq", "modulator/gain", "modulator/label"),
            (rig / "*" / "*").paths().sorted()
        )
    }

    @Test
    fun chainingFromARefKeepsThatRefsPrefix() {
        val rig = rig()
        val carrier = (rig / "carrier").first()

        assertEquals(listOf("carrier/freq"), (carrier / "freq").paths())
        assertSame(rig.carrier, (carrier / "freq").root)
    }

    @Test
    fun chainingFromTypedLensesStopsAtTheLeaf() {
        val rig = rig()

        // A Float has no queryable children, so this is empty rather than a report of
        // `LensSet`'s own fields — which is the only reason the overload exists.
        assertTrue(((rig / "**/freq").lenses<Float>() / "**").isEmpty)
        assertTrue(((rig / "**/freq").lenses<Float>().firstOrNull()!! / "**").isEmpty)
    }

    @Test
    fun aNullReceiverQueriesNothing() {
        val nothing: Rig? = null

        assertTrue((nothing / "**").isEmpty)
    }

    @Test
    fun divCarriesTheWholePatternSurface() {
        val rig = rig()

        // A glob inside one segment — the shape you reach for when you remember how a slot's
        // name *ends* rather than where it lives.
        // Note the inner parens: `/` binds tighter than `+`, so the pattern has to be
        // assembled before it is handed over.
        assertEquals(listOf("carrier/gain", "modulator/gain"), (rig / ("**/" + "*ain")).paths())
        assertEquals(listOf("carrier", "modulator"), (rig / "*a*r").paths())

        // And case you half remember.
        assertEquals(listOf("carrier/freq"), (rig / "CARRIER/Freq").paths())
        assertEquals(listOf("carrier/label", "modulator/label"), (rig / "**/LABEL").paths())

        // Chained, both features at once.
        assertEquals(listOf("carrier/freq"), (rig / "CARRIER" / "fr*").paths())
    }

    @Test
    fun divTakesAlternationToo() {
        class Look(var contourAlpha: Float, var fillAlpha: Float, var contourColor: Rig)
        val look = Look(1f, 0.25f, rig())

        // The shape this operator exists for: two things you want at once, named by how
        // their slots read rather than by where they sit.
        assertEquals(
            listOf("contourAlpha", "fillAlpha", "contourColor/name"),
            (look / ("**/" + "*alpha | **/name")).paths()
        )
    }

    // ── what the wide receiver must not break ───────────────────────────────

    /** A type that already spells `/` its own way, including with a String on the right. */
    class Fraction(val n: Int) {
        operator fun div(label: String): String = "member:$label"
    }

    @Test
    fun memberDivOperatorsStillWin() {
        // Extensions never shadow members, so arithmetic is untouched — that these lines
        // still *compile* to their own `div` is as much the point as the values.
        assertEquals(1.5f, 3f / 2f)
        assertEquals(2, 5 / 2)

        // And a member that takes exactly a String — the signature this file adds to `Any?`
        // — is still the one that resolves.
        assertEquals("member:x", Fraction(1) / "x")
    }

    @Test
    fun aMoreSpecificExtensionReceiverStillWins() {
        // `kotlin.io.path.div` is an extension too, so this is the case that could have gone
        // ambiguous. Path is more specific than Any?, so it wins and the result is a Path.
        val joined = Path("a") / "b"

        assertEquals(Path("a/b"), joined)
    }

    @Test
    fun dividingByAStringNoLongerFailsToCompile() {
        // The cost of the notation, pinned deliberately: this used to be a compile error and
        // is now an empty query. Reflection stops at String, so there is nothing to find.
        assertTrue(("a" / "b").isEmpty)
    }
}
