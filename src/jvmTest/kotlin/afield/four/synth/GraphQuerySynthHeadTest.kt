package afield.four.synth

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterization tests for the *synth-coupled* half of GraphQuery: native [Param]
 * binding, modulation, and supplier-driven scalars.
 *
 * Split out from [GraphQueryCharacterizationTest] deliberately. Attaching a supplier or a
 * modulation registers the target with `GraphBindingRuntime`, which touches
 * `Runloop.runLoop` — and the `Runloop` companion builds a non-daemon thread pool. The
 * pure query core must never need any of that; keeping the two suites apart makes it
 * obvious if that ever regresses.
 *
 * Modulation uses [Const] rather than an oscillator so every expected value is exact.
 */
class GraphQuerySynthHeadTest {

    class Osc(var freq: Float, var gain: Float)

    class Voice(val carrier: Osc, val modulator: Osc)

    class ParamHolder(val x: Param, val y: Param)

    class Patch(val cutoff: Param, val resonance: Param)

    private val cleanup = mutableListOf<ParamSet>()

    /**
     * `GraphBindingRuntime` keeps a *global* set of dynamic targets with no per-query
     * lifecycle, so a suite that attaches suppliers has to detach them by hand or it
     * leaks ticking state into every later test. That missing lifecycle is itself a wart.
     */
    @AfterTest
    fun detachDynamicTargets() {
        cleanup.forEach { it.clearSuppliers().clearModulation() }
        cleanup.clear()
    }

    private fun ParamSet.tracked(): ParamSet = also { cleanup += it }

    // ── the pattern surface, as `matches` sees it ───────────────────────────

    /**
     * `matches` is `params()` with a nicer face, so it shares one glob engine with `/` and
     * `graphPaths`. This pins that the sharing is real: a within-segment glob and a
     * misremembered capitalisation both work here without ParamQuery knowing about either.
     */
    @Test
    fun matchesInheritsGlobsWithinASegmentAndCaseInsensitivity() {
        val patch = Patch(Param(800f), Param(0.3f))

        assertEquals(listOf("cutoff"), (patch matches "cut*").paths())
        assertEquals(listOf("cutoff"), (patch matches "CUTOFF").paths())
        assertEquals(listOf("resonance"), (patch matches "*ance").paths())

        // And the same two features through ParamSet's own filter. (`**` also reports each
        // Param's own `base` edge, so filtering a one-segment pattern is the tidier check.)
        assertEquals(listOf("cutoff"), (patch matches "**").matching("*OFF").paths())
    }

    @Test
    fun matchesUnionsAlternatives() {
        val voice = Voice(Osc(220f, 0.8f), Osc(440f, 0.2f))

        assertEquals(
            listOf("carrier/freq", "modulator/freq", "modulator/gain"),
            (voice matches "**/freq | modulator/gain").paths()
        )
        assertEquals(listOf("carrier/gain"), (voice matches "**").matching("carrier/gain | nope").paths())
    }

    @Test
    fun matchesGlobsStayInsideOneSegment() {
        val voice = Voice(Osc(220f, 0.8f), Osc(440f, 0.2f))

        assertTrue((voice matches "*req").isEmpty, "one segment only")
        assertEquals(
            listOf("carrier/freq", "modulator/freq"),
            (voice matches "**/" + "*REQ").paths()
        )
    }

    // ── native Param binding ────────────────────────────────────────────────

    /** A `Param`-valued edge binds as a native param, exposing the live [Param] object. */
    @Test
    fun paramValuedEdgesBindNatively() {
        val patch = Patch(Param(800f), Param(0.3f))
        val entry = patch.params("cutoff").first()

        assertTrue(entry.isNativeParam, "Param edge binds natively")
        assertSame(patch.cutoff, entry.param)
        assertEquals(800f, entry.base, 1e-6f)
    }

    /** A scalar-backed entry has no underlying Param and says so rather than lying. */
    @Test
    fun scalarEntriesReportNoNativeParam() {
        val entry = Osc(220f, 0.8f).params("freq").first()

        assertFalse(entry.isNativeParam)
        assertNull(entry.paramOrNull())
        assertFailsWith<IllegalStateException>("asking for .param on a scalar must fail loudly") {
            entry.param
        }
    }

    /**
     * Two edges pointing at the *same* Param instance collapse into a single entry that
     * lists both addresses as aliases — identity-grouped, not path-grouped.
     */
    @Test
    fun sharedParamInstancesCollapseIntoAliases() {
        val shared = Param(1f)
        val entry = ParamHolder(shared, shared).params("**")
            .first { it.isNativeParam }

        assertEquals(listOf("x", "y"), entry.aliases)
        assertSame(shared, entry.param)
    }

    /**
     * WART: a native Param's own `base` field is *also* harvested as an independent
     * scalar entry, so a graph holding one Param yields two entries that write to the
     * same underlying value by different routes.
     */
    @Test
    fun aParamsOwnBaseAlsoAppearsAsASeparateScalarEntry() {
        val set = ParamHolder(Param(1f), Param(2f)).params("**")

        val native = set.filter { it.isNativeParam }.map { it.path }
        val scalar = set.filterNot { it.isNativeParam }.map { it.path }

        assertEquals(listOf("x", "y"), native)
        assertEquals(
            listOf("x/base", "y/base"),
            scalar,
            "the Param's own base is double-counted as a scalar (known wart)"
        )
    }

    /** Writing a native ParamSet moves the real Param's base. */
    @Test
    fun settingNativeParamsWritesThrough() {
        val patch = Patch(Param(800f), Param(0.3f))
        patch.params("cutoff").set(1200f)

        assertEquals(1200f, patch.cutoff.base, 1e-6f)
    }

    /** `params()` returns only the native Params, skipping scalar-backed entries. */
    @Test
    fun paramsAccessorReturnsOnlyNativeParams() {
        val patch = Patch(Param(800f), Param(0.3f))
        val natives = patch.params("cutoff").params()

        assertEquals(1, natives.size)
        assertSame(patch.cutoff, natives.single())
    }

    // ── modulation ──────────────────────────────────────────────────────────

    /** REPLACE (the default) swaps the modulation signal outright. */
    @Test
    fun modulateReplacesByDefault() {
        val patch = Patch(Param(800f), Param(0.3f))
        val first = Const(1f)
        val second = Const(2f)

        patch.params("cutoff").tracked().modulate(first)
        assertSame(first, patch.cutoff.mod)

        patch.params("cutoff").tracked().modulate(second)
        assertSame(second, patch.cutoff.mod, "REPLACE overwrites rather than summing")
    }

    /** ADD composes onto whatever modulation is already attached. */
    @Test
    fun modulateAddComposesOntoExisting() {
        val patch = Patch(Param(800f), Param(0.3f))
        val first = Const(1f)

        patch.params("cutoff").tracked().modulate(first)
        patch.params("cutoff").tracked().modulate(Const(2f), ParamModulationMode.ADD)

        val mod = patch.cutoff.mod
        assertTrue(mod != null && mod !== first, "ADD wraps the existing signal in a sum")
    }

    /** ADD with nothing attached simply installs the signal. */
    @Test
    fun modulateAddOntoNothingInstallsDirectly() {
        val patch = Patch(Param(800f), Param(0.3f))
        val signal = Const(1f)

        patch.params("cutoff").tracked().modulate(signal, ParamModulationMode.ADD)
        assertSame(signal, patch.cutoff.mod)
    }

    /** Modulation can be cleared back off again. */
    @Test
    fun clearModulationDetaches() {
        val patch = Patch(Param(800f), Param(0.3f))
        patch.params("cutoff").tracked().modulate(Const(1f))
        patch.params("cutoff").clearModulation()

        assertNull(patch.cutoff.mod)
    }

    /** The per-entry lambda form can give each entry its own signal, and skip some. */
    @Test
    fun perEntryModulationLambdaCanSkipEntries() {
        val patch = Patch(Param(800f), Param(0.3f))
        patch.params("**").tracked().modulate { entry, _ ->
            if (entry.path == "cutoff") Const(1f) else null
        }

        assertTrue(patch.cutoff.mod != null, "cutoff modulated")
        assertNull(patch.resonance.mod, "null from the lambda leaves the entry alone")
    }

    // ── scalar suppliers driven through the binding runtime ─────────────────

    /**
     * Attaching a supplier to a *plain numeric field* applies it immediately — this is
     * the mechanism that makes any object's numeric property behave like a synth param.
     */
    @Test
    fun scalarSupplierAppliesImmediately() {
        val osc = Osc(220f, 0.8f)
        osc.params("freq").tracked().supply { _, _, _ -> 55f }

        assertEquals(55f, osc.freq, 1e-6f, "supplier is evaluated on attach")
    }

    /** Ticking the binding runtime re-evaluates attached suppliers. */
    @Test
    fun tickGraphBindingsReEvaluatesSuppliers() {
        val osc = Osc(220f, 0.8f)
        var next = 10f
        osc.params("freq").tracked().supply { _, _, _ -> next }
        assertEquals(10f, osc.freq, 1e-6f)

        next = 20f
        tickGraphBindings("graphquery-test", deltaTime = 1f / 60f)
        assertEquals(20f, osc.freq, 1e-6f, "tick re-runs the supplier")
    }

    /** A scalar's modulation is summed on top of its base when ticked. */
    @Test
    fun scalarModulationSumsOntoBase() {
        val osc = Osc(220f, 0.8f)
        osc.params("freq").tracked().modulate(Const(5f))

        // base (220) + modulation (5)
        assertEquals(225f, osc.freq, 1e-6f)
    }

    /** Clearing a supplier stops it driving the field. */
    @Test
    fun clearSuppliersDetaches() {
        val osc = Osc(220f, 0.8f)
        val set = osc.params("freq")
        set.supply { _, _, _ -> 55f }
        assertEquals(55f, osc.freq, 1e-6f)

        set.clearSuppliers()
        var observed = 0
        tickGraphBindings("graphquery-test", deltaTime = 1f / 60f)
        observed++
        assertEquals(1, observed)
        assertNull(set.first().supplier, "supplier detached")
    }

    /** The supplier lambda sees its entry, index, and the surrounding set statistics. */
    @Test
    fun supplierLambdaReceivesIndexAndScope() {
        val v = Voice(Osc(100f, 0f), Osc(300f, 0f))
        val seenIndices = mutableListOf<Int>()

        v.params("**/freq").tracked().supply { _, index, scope ->
            seenIndices += index
            scope.mean
        }

        assertEquals(listOf(0, 1), seenIndices.sorted())
        // mean of the two observed bases (100, 300)
        assertEquals(200f, v.carrier.freq, 1e-6f)
    }
}
