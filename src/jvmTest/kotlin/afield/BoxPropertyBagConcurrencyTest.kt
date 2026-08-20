package afield

import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reproducer for the `Box.resyncAll` `ConcurrentModificationException` that the nightly loop sighted
 * three times, from three agents, in files none of them touched:
 *
 *     java.util.ConcurrentModificationException
 *       at Box.resyncAll:1241 <- OperationTranscript.applyMacroOperation:1343 <- runMacro:453
 *
 * `Boxes.kt` has fewer than 1241 lines: that is a Kotlin *inline* line number, and its SMAP
 * (`SourceDebugExtension`) resolves `1241` to `kotlin/collections/MapsKt__MapsKt:669` — the
 * `for (element in this)` loop of the inlined `Map.filterTo` — inlined at the
 * `properties.filter { … }` on the first line of [Box.resyncAll]. So the exception came out of a
 * `LinkedHashMap` entry iterator, thrown because another thread structurally modified the same box's
 * property bag mid-walk.
 *
 * Everything below drives production code only — a real [Box], real [Box.setPropertyRaw], real
 * [Box.resyncAll] and [Box.resyncAllExcept] — from several threads at once, which is exactly the shape
 * of the live failure: macro replay runs on `Dispatchers.IO` while the main runloop's 500 ms
 * resync/save/file-proxy loop and the AI auto-namer write into the very box being replayed.
 *
 * At `4f55fc4e`, with `Box.properties` a plain `mutableMapOf()`, [replayAndResyncDoNotRaceOnTheBag]
 * fails within milliseconds. With the bag behind [PropertyBag] it completes the fixed stress plan.
 */
class BoxPropertyBagConcurrencyTest {

    /** Property names that [Box.uiResyncProperties] matches, so `resyncAll` does its real work. */
    private val resyncedNames = listOf(
        "code", "name", "notes", "preamble", "attic", "customization", "metadata", "autorun", "fragment"
    )

    private val stressedBoxes = mutableListOf<Box>()

    @AfterTest
    fun dropWhatTheStressLeftInTheGlobalResyncQueue() {
        // resyncAll() feeds the process-wide `needsResync`; don't leave this test's boxes in it for
        // whatever runs next in the same JVM.
        val mine = stressedBoxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        stressedBoxes.clear()
    }

    private fun stressBox(id: String, padding: Int = 400): Box {
        val box = Box(id)
        stressedBoxes += box
        resyncedNames.forEach { box.setPropertyRaw(it, "value of $it", doResync = false) }
        // A bag with some bulk, so the reader spends real time inside the iterator rather than
        // finishing before a writer can get a word in. A live box easily has this many.
        repeat(padding) { box.setPropertyRaw("padding_$it", it, doResync = false) }
        return box
    }

    private fun worker(
        name: String,
        rounds: Int,
        phase: CyclicBarrier,
        failure: AtomicReference<Throwable?>,
        body: () -> Unit
    ) = Thread({
        try {
            repeat(rounds) {
                if (failure.get() != null) return@Thread
                phase.await()
                body()
            }
        } catch (t: Throwable) {
            if (t !is BrokenBarrierException || failure.get() == null) failure.compareAndSet(null, t)
            phase.reset()
        }
    }, name)

    private fun runWorkers(vararg workers: Thread) {
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }
        val stuck = workers.filter { it.isAlive }
        stuck.forEach { it.interrupt() }
        assertTrue(stuck.isEmpty(), "stress worker(s) did not finish fixed work: ${stuck.map { it.name }}")
    }

    /**
     * Two `resyncAll`/`resyncAllExcept` walkers against two writers inserting and removing fresh keys —
     * i.e. structural modification, which is what invalidates a `LinkedHashMap` iterator.
     *
     * The writers keep the bag's size bounded (insert one, drop the one from `window` ago) so this is a
     * race test and not a memory test.
     */
    @Test
    fun replayAndResyncDoNotRaceOnTheBag() {
        val box = stressBox("resync-race")

        val rounds = 500
        val failure = AtomicReference<Throwable?>(null)
        val walks = AtomicLong(0)
        val writes = AtomicLong(0)
        val threadCount = 4
        val phase = CyclicBarrier(threadCount)

        val walkers = listOf(
            worker("resync-walker-all", rounds, phase, failure) {
                box.resyncAll()
                walks.incrementAndGet()
            },
            worker("resync-walker-except", rounds, phase, failure) {
                box.resyncAllExcept(setOf("code"))
                walks.incrementAndGet()
            }
        )
        val writers = (0 until 2).map { slot ->
            val window = 64
            var n = 0
            worker("bag-writer-$slot", rounds, phase, failure) {
                // A fresh key every time: this is the `ai4_auto_name` / `file_proxy_error` /
                // Delegation-default-writeback shape, and it bumps the backing map's modCount.
                box.setPropertyRaw("churn_${slot}_$n", n, doResync = false)
                if (n >= window) box.properties.remove("churn_${slot}_${n - window}")
                n++
                writes.incrementAndGet()
            }
        }

        runWorkers(*(walkers + writers).toTypedArray())

        // The race first, so a failure reports the exception and where it came from rather than the
        // did-anything-run guard below (at base the walkers die on their very first walk, so the
        // counters are still 0 when they do).
        val raised = failure.get()
        assertNull(
            raised,
            "concurrent resync + property writes raised ${raised?.javaClass?.name} after ${walks.get()} " +
                "walks / ${writes.get()} writes, at:\n" +
                raised?.stackTrace?.take(4)?.joinToString("\n") { "    $it" }
        )
        // And then the other failure mode this loop keeps rediscovering: a race test that passes
        // because nothing actually ran.
        assertEquals((rounds * walkers.size).toLong(), walks.get(), "resync walkers did not finish fixed work")
        assertEquals((rounds * writers.size).toLong(), writes.get(), "bag writers did not finish fixed work")
    }

    /**
     * The bag's contract, pinned directly: iteration sees a detached snapshot, so a walk cannot be
     * invalidated even by a write from the walking thread itself.
     */
    @Test
    fun iterationSeesASnapshotNotALiveView() {
        val box = stressBox("snapshot", padding = 8)

        val seen = mutableListOf<String>()
        // Under a live view this is the textbook CME: mutate the map inside its own iteration.
        box.properties.forEach { (name, _) ->
            seen += name
            box.setPropertyRaw("inserted_while_walking_${seen.size}", 1, doResync = false)
        }
        assertTrue(seen.isNotEmpty(), "walked an empty bag")
        assertEquals(seen.size, seen.distinct().size, "the snapshot repeated a key")
        assertTrue(
            box.properties.containsKey("inserted_while_walking_1"),
            "the writes during iteration did not land in the bag"
        )
    }

    /**
     * Insertion order is load-bearing, not incidental: `SavingContext.toTransfer` writes each persisted
     * box's properties in bag order, so reordering here silently rewrites every saved document.
     */
    @Test
    fun insertionOrderSurvives() {
        val box = Box("ordering")
        stressedBoxes += box
        val before = box.properties.keys.toList()
        val added = listOf("zebra", "alpha", "mike", "bravo")
        added.forEach { box.setPropertyRaw(it, it, doResync = false) }
        assertEquals(before + added, box.properties.keys.toList())
    }

    /**
     * The same defect on the same object, one field along: `syncExecutionStatus` walks every box's
     * `currentRunningStates` on the main runloop every 500 ms (`Server.kt`'s resync loop), while
     * `Phased`, `ReturnValueRegistry`, the launch coroutines, `KotlinJs` and `FieldMLInterface` all
     * write it from whatever thread is running the box.
     *
     * Found by auditing outwards from the `resyncAll` sighting rather than by a sighting of its own,
     * so this is the evidence that it was a real race and not a tidy-up: at `4f55fc4e` the walker
     * dies on `LinkedHashMap$LinkedHashIterator.nextNode`, exactly as `resyncAll` did.
     */
    @Test
    fun executionStatusWalksDoNotRaceOnRunningStates() {
        val box = Box("running-states-race")
        stressedBoxes += box

        val rounds = 1_000
        val failure = AtomicReference<Throwable?>(null)
        val walks = AtomicLong(0)
        val writes = AtomicLong(0)
        val phase = CyclicBarrier(2)

        // What syncExecutionStatus (Boxes.kt) does to each box, 500ms apart, on the main runloop.
        val walker = worker("execution-status-walker", rounds, phase, failure) {
            box.currentRunningStates.toMap().forEach { (_, state) -> state.watermark }
            walks.incrementAndGet()
        }
        var n = 0
        val writer = worker("strand-writer", rounds, phase, failure) {
            box.currentRunningStates["strand_$n"] = RunningState(n, "launch")
            if (n >= 32) box.currentRunningStates.remove("strand_${n - 32}")
            n++
            writes.incrementAndGet()
        }

        runWorkers(walker, writer)

        val raised = failure.get()
        assertNull(
            raised,
            "walking currentRunningStates while strands start and stop raised " +
                "${raised?.javaClass?.name} at:\n" +
                raised?.stackTrace?.take(4)?.joinToString("\n") { "    $it" }
        )
        assertEquals(rounds.toLong(), walks.get(), "status walker did not finish fixed work")
        assertEquals(rounds.toLong(), writes.get(), "strand writer did not finish fixed work")
    }

    /** The bag holds nulls; `ConcurrentHashMap` would have thrown here. */
    @Test
    fun nullValuesAreStorable() {
        val box = Box("nullable")
        stressedBoxes += box
        box.properties["explicitly_null"] = null
        assertTrue(box.properties.containsKey("explicitly_null"))
        assertNull(box.properties["explicitly_null"])
    }
}
