package afield

import afield.four.targets.ownedModuleCount
import afield.four.targets.putOwned
import afield.four.targets.releaseOwnedModulesOf
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
    fun aClaimCarriesItsKindsPolicyUnlessItOverridesIt() {
        val box = Box("ledger-policy")
        val byKind = Box.withBoxContextNow(box) { LifecycleLedger.claim(LifecycleKinds.AUDIO_PATCH, "p") }
        val overridden = Box.withBoxContextNow(box) {
            LifecycleLedger.claim(LifecycleKinds.AUDIO_PATCH, "q", policy = ReleasePolicy.KEYED)
        }
        assertEquals(ReleasePolicy.RUNNING, byKind.policy)
        assertEquals(ReleasePolicy.KEYED, overridden.policy)
        box.stopTrackedJobs()
        assertFalse(byKind.isLive, "stop releases a running claim")
        assertTrue(overridden.isLive, "but not one whose claim made it keyed")
        box.runScopes.closeAll()
        assertFalse(overridden.isLive)
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

        val census = lifecycleCensus(listOf(box), includeUnattributed = false)
        val entry = census["boxes"].toString()
        assertTrue("\"control-callback\":1" in entry, entry)
        assertTrue("\"liveTotal\":1" in entry, entry)
        assertTrue("\"ACCUMULATING\":1" in entry, "the census counts ownerships by policy: $entry")
        assertTrue("\"liveRunning\":0" in entry, "a slider is not something a stop would release: $entry")
        val policies = census["policies"].toString()
        assertTrue("\"control-callback\":\"ACCUMULATING\"" in policies, policies)
        assertTrue("\"render-module\":\"KEYED\"" in policies, policies)
        assertTrue("\"audio-patch\":\"RUNNING\"" in policies, policies)
        ownership.close()
        val after = lifecycleCensus(listOf(box), includeUnattributed = false)["boxes"].toString()
        assertTrue("\"liveTotal\":0" in after, after)
    }

    /**
     * A renderer's disposal ends the render-module ownerships its targets' maps hold. The module dies
     * with the device; the ownership used to stay live — keeping the module and the snippet
     * classloader that built it — until the box that put it there retired.
     */
    @Test
    fun disposingARendererReleasesTheModulesItsDependentsPutOnIt() {
        val box = Box("ledger-renderer-dispose")
        val renderer = Any()
        val otherRenderer = Any()
        val modules = mutableMapOf<String, Any>()
        val otherModules = mutableMapOf<String, Any>()
        val run = box.runScopes.begin("default", "launch")
        Box.withBoxContextNow(box) {
            RunScope.withCurrent(run) {
                modules.putOwned("a", Any(), renderer)
                modules.putOwned("b", Any(), renderer)
                otherModules.putOwned("c", Any(), otherRenderer)
            }
        }
        assertEquals(3, ownedBy(box, LifecycleKinds.RENDER_MODULE).size)
        assertEquals(2, ownedModuleCount(renderer))
        assertEquals(3, run.liveCount(ReleasePolicy.KEYED))

        assertEquals(2, releaseOwnedModulesOf(renderer))

        val left = ownedBy(box, LifecycleKinds.RENDER_MODULE)
        assertEquals(listOf("c"), left.map { it.key }, "only the other renderer's module is still owned")
        assertEquals(1, run.liveCount(ReleasePolicy.KEYED), "the box's run scope no longer counts them")
        assertEquals(0, ownedModuleCount(renderer))
        assertEquals(0, releaseOwnedModulesOf(renderer), "a second dispose finds nothing")

        box.runScopes.closeAll()
        assertTrue(ownedBy(box, LifecycleKinds.RENDER_MODULE).isEmpty())
    }
}
