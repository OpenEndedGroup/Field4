package afield

import afield.four.util.MapOfSequences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lifecycle ledger behind `lifecycle-census`: attribution at registration, and the slot rule that
 * lets two runs register under one name without the older one's teardown removing the newer one.
 */
class LifecycleLedgerTest {

    private fun ownedBy(box: Box, kind: String) =
        LifecycleLedger.entries().filter { it.ownerId == box.id && it.kind == kind }

    @Test
    fun aRegistrationIsAttributedToTheExecutingBox() {
        val box = Box("ledger-attribution")
        val registry = OwnedCallbackRegistry<(Double) -> Unit>("test-slider")
        val ownership = Box.withBoxContextNow(box) { registry.register("s1", {}) }

        assertEquals(listOf(ownership), ownedBy(box, LifecycleKinds.CONTROL_CALLBACK))
        ownership.close()
        assertNull(registry["s1"], "closing the ownership should take the callback back out")
        assertTrue(ownedBy(box, LifecycleKinds.CONTROL_CALLBACK).isEmpty())
    }

    @Test
    fun aNewerClaimOnTheSameSlotSurvivesTheOlderOnesClose() {
        val box = Box("ledger-slot")
        val registry = OwnedCallbackRegistry<() -> Unit>("test-slot")
        val first: () -> Unit = {}
        val second: () -> Unit = {}
        val older = Box.withBoxContextNow(box) { registry.register("same", first) }
        val newer = Box.withBoxContextNow(box) { registry.register("same", second) }

        older.close()

        assertTrue(registry["same"] === second, "the older run's teardown must not remove the newer callback")
        assertTrue(newer.isLive)
        assertFalse(older.isLive)
        newer.close()
        assertNull(registry["same"])
    }

    @Test
    fun aRunloopHookThatEndsDropsOutOfTheCensus() {
        val box = Box("ledger-runloop")
        val hooks = MapOfSequences<String>(LifecycleKinds.RUNLOOP_PRE_RUN)
        Box.withBoxContextNow(box) { hooks["once"] = sequence { yield(Unit) } }
        assertEquals(1, ownedBy(box, LifecycleKinds.RUNLOOP_PRE_RUN).size)

        hooks.update() // starts it
        hooks.update() // runs its one step and drops it

        assertTrue(ownedBy(box, LifecycleKinds.RUNLOOP_PRE_RUN).isEmpty(), "a finished sequence is no longer held")
    }

    @Test
    fun theCensusCountsWhatABoxHolds() {
        val box = Box("ledger-census")
        val registry = OwnedCallbackRegistry<() -> Unit>("test-census")
        val ownership = Box.withBoxContextNow(box) { registry.register("c", {}) }

        val entry = lifecycleCensus(listOf(box), includeUnattributed = false)["boxes"].toString()
        assertTrue("\"control-callback\":1" in entry, entry)
        assertTrue("\"liveTotal\":1" in entry, entry)
        ownership.close()
        val after = lifecycleCensus(listOf(box), includeUnattributed = false)["boxes"].toString()
        assertTrue("\"liveTotal\":0" in after, after)
    }
}
