package afield

import afield.stdlib.Action
import afield.stdlib.actions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The second half of the `resyncAll` race, found by auditing outwards from it rather than by a sighting.
 *
 * Making `Box.properties` a `PropertyBag` stopped the *bag* throwing, but the values stored in it were
 * still plain `LinkedHashSet`s — and `children` / `parents` / `nested` are walked on the main runloop while
 * replay threads connect and disconnect boxes:
 *
 *  - `doResync`'s `"children"` and `"nested"` branches walk the live set with
 *    `(value as? Collection<*>)?.mapNotNull { … }`, on the runloop, every 500 ms;
 *  - `visitParents` walks the live `parents` set, and is how `gather` reaches a box's ancestors — which is
 *    what `doResync`'s own `"actions"` branch calls, and what every preamble, shader and actor lookup uses;
 *  - `Box.connect` / `Box.disconnect` do `accessor() += to` from macro replay on `Dispatchers.IO`.
 *
 * Both tests below drive production code only: real `doResync()`, real `gather()`, real `connect()` /
 * `disconnect()`, against a real `SessionInformation`. Both fail at `4f55fc4e` (and equally with
 * `Box.init` put back to `mutableSetOf()`), on `LinkedHashMap`/`HashMap`'s iterator.
 */
class BoxTopologyConcurrencyTest {

    private lateinit var info: SessionInformation
    private val boxes = mutableListOf<Box>()

    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val session = SessionInformation(
            filename = "<$label>",
            documentName = label,
            root = root,
            ephemeral = true
        )
        root.inside = session
        // doResync() skips a session that is still loading, and would then never walk anything.
        session.loading = false
        info = session
        boxes += root
        return session
    }

    private fun boxIn(session: SessionInformation, id: String): Box = Box(id).also {
        it.inside = session
        session.boxes[id] = it
        boxes += it
    }

    @AfterTest
    fun leaveTheGlobalQueueAndDocumentTableAsWeFoundThem() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        boxes.clear()
        thisSessionInfo.remove()
    }

    /** Runs fixed, barrier-phased work on its own thread, with a session bound. */
    private fun worker(
        name: String,
        session: SessionInformation,
        rounds: Int,
        phase: CyclicBarrier,
        failure: AtomicReference<Throwable?>,
        body: () -> Unit
    ) = Thread({
        thisSessionInfo.set(session)
        try {
            repeat(rounds) {
                if (failure.get() != null) return@Thread
                // Readers and writers begin every round together. A loaded machine can stretch a
                // round, but cannot fail the test merely because it completed fewer rounds per second.
                phase.await()
                body()
            }
        } catch (t: Throwable) {
            if (t !is BrokenBarrierException || failure.get() == null) {
                failure.compareAndSet(null, t)
            }
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

    private fun assertNoRace(
        failure: AtomicReference<Throwable?>,
        what: String,
        vararg counters: Triple<String, AtomicLong, Long>
    ) {
        val raised = failure.get()
        assertNull(
            raised,
            "$what raised ${raised?.javaClass?.name} after " +
                counters.joinToString { "${it.first}=${it.second.get()}" } + ", at:\n" +
                raised?.stackTrace?.take(5)?.joinToString("\n") { "    $it" }
        )
        counters.forEach { (label, counter, expected) ->
            assertEquals(expected, counter.get(), "$label did not complete its fixed stress work")
        }
    }

    /**
     * The runloop's `doResync()` against replay's `connect`/`disconnect` — the 500 ms loop in `Server.kt`
     * versus the `create` and `connect` macro verbs.
     */
    @Test
    fun doResyncDoesNotRaceConnectAndDisconnect() {
        val session = session("topology-resync")
        val root = session.root
        val pool = (0 until 1_500).map { boxIn(session, "child-$it") }
        // Most of them attached up front: the window is the *length* of the walk, and a
        // handful of edges is a walk of nanoseconds. A document root really does carry
        // hundreds of children.
        thisSessionInfo.set(session)
        pool.take(1_200).forEach { root.connect(it) }

        val rounds = 200
        val failure = AtomicReference<Throwable?>(null)
        val resyncs = AtomicLong(0)
        val edges = AtomicLong(0)
        val phase = CyclicBarrier(3)

        val resyncer = worker("resync-loop", session, rounds, phase, failure) {
            // Keep the queue fed the way the server does, then drain it exactly as the runloop does.
            needsResync.add(root to "children")
            needsResync.add(root to "actions")
            doResync()
            resyncs.incrementAndGet()
        }
        val connectors = (0 until 2).map { slot ->
            var n = 0
            worker("edge-churn-$slot", session, rounds, phase, failure) {
                // Alternate on the *same* box, in a range this thread owns: connecting an
                // already-connected box (or disconnecting an absent one) returns early without
                // touching the set, and a churn loop that saturates into no-ops is a test that
                // passes for the wrong reason. Each paired round here is one add and one remove.
                val child = pool[1_200 + slot * 150 + ((n / 2) % 150)]
                if (n % 2 == 0) root.connect(child) else root.disconnect(child)
                n++
                edges.incrementAndGet()
            }
        }

        runWorkers(resyncer, *connectors.toTypedArray())

        assertNoRace(
            failure,
            "doResync() while boxes connect and disconnect",
            Triple("resyncs", resyncs, rounds.toLong()),
            Triple("edges", edges, (rounds * connectors.size).toLong())
        )
    }

    /**
     * `gather()` — via `visitParents` — against parent-edge churn. This is the walk behind
     * `doResync`'s `"actions"` branch, `gatherPreambleUpwards`, the shader-source lookup and the actor
     * registry, so it runs on more threads than any other traversal in the system.
     */
    @Test
    fun gatherDoesNotRaceParentEdgeChurn() {
        val session = session("topology-gather")
        val leaf = boxIn(session, "leaf")
        val ancestors = (0 until 1_500).map { boxIn(session, "ancestor-$it") }
        // A long fringe, for the same reason: visitParents' addAll over a handful of parents
        // is far too short a window to catch anything.
        leaf.parents.add(session.root)
        ancestors.take(1_200).forEach { leaf.parents.add(it) }
        leaf.actions["own"] = Action("own", "detail") { }

        val rounds = 200
        val failure = AtomicReference<Throwable?>(null)
        val gathers = AtomicLong(0)
        val edges = AtomicLong(0)
        val phase = CyclicBarrier(3)

        val gatherer = worker("gather-loop", session, rounds, phase, failure) {
            leaf.gather<MutableMap<String, Action>>("actions")
            leaf.visitParents { }
            gathers.incrementAndGet()
        }
        val churners = (0 until 2).map { slot ->
            var n = 0
            worker("parent-churn-$slot", session, rounds, phase, failure) {
                val ancestor = ancestors[1_200 + slot * 150 + ((n / 2) % 150)]
                // The shape used by attachRootToMetaroot, history.kt's topology restore and the
                // completion probes: mutate the edge set directly.
                if (n % 2 == 0) leaf.parents.add(ancestor) else leaf.parents.remove(ancestor)
                n++
                edges.incrementAndGet()
            }
        }

        runWorkers(gatherer, *churners.toTypedArray())

        assertNoRace(
            failure,
            "gather()/visitParents() while parent edges churn",
            Triple("gathers", gathers, rounds.toLong()),
            Triple("edges", edges, (rounds * churners.size).toLong())
        )
    }

    /**
     * `gather("actions")` merges every ancestor's `actions` map with `putAll`, i.e. iterates it — from
     * `doResync`'s `"actions"` branch on the runloop — while box code registers actions from execution
     * threads (`root.actions["reset"] = …` in `execution.kt`, the slideshow and alignment installers).
     * Same walk, different collection: the maps *inside* the graph, not the edges between boxes.
     */
    @Test
    fun gatherDoesNotRaceActionRegistration() {
        val session = session("actions-race")
        val leaf = boxIn(session, "actions-leaf")
        val ancestor = boxIn(session, "actions-ancestor")
        leaf.parents.add(ancestor)
        // A long map, for the same reason the topology walks needed long sets: `putAll` over three
        // entries is far too short a window to catch anything.
        repeat(1_500) { ancestor.actions["preinstalled-$it"] = Action("a$it", "detail") { } }

        val rounds = 250
        val failure = AtomicReference<Throwable?>(null)
        val gathers = AtomicLong(0)
        val registrations = AtomicLong(0)
        val phase = CyclicBarrier(2)

        val gatherer = worker("actions-gather", session, rounds, phase, failure) {
            leaf.gather<MutableMap<String, Action>>("actions")
            gathers.incrementAndGet()
        }
        var n = 0
        val registrar = worker("actions-register", session, rounds, phase, failure) {
            // Alternate on the same key so every iteration is a real structural modification.
            val key = "churn-${n / 2}"
            if (n % 2 == 0) ancestor.actions[key] = Action(key, "detail") { } else ancestor.actions.remove(key)
            n++
            registrations.incrementAndGet()
        }

        runWorkers(gatherer, registrar)

        assertNoRace(
            failure, "gather(\"actions\") while actions are registered",
            Triple("gathers", gathers, rounds.toLong()),
            Triple("registrations", registrations, rounds.toLong())
        )
    }

    /**
     * `BoxJobList` is the most instructive one in the family, because it was clearly *written* to be
     * thread-safe: every method takes its `lock` and bumps `modCount`. But the iterator it inherits from
     * `AbstractMutableList` checks `modCount` outside that lock — so `jobs.any { it.isActive }`, which
     * `syncJobRunningState` runs for every box on the runloop every 500 ms, raced `removeCompleted`,
     * which fires from `job.invokeOnCompletion` on whatever dispatcher finished the job.
     *
     * No synthetic mutation here: the churn is *real coroutines completing*, which is the production
     * trigger.
     */
    @Test
    fun jobsAnyDoesNotRaceCoroutineCompletion() {
        val box = Box("jobs-race")
        boxes += box
        val scope = CoroutineScope(Dispatchers.Default)
        // A long list of jobs that outlive the test, so the `any {}` walk has a real window.
        val parked = CompletableDeferred<Unit>()
        repeat(1_500) { box.jobs.add(scope.launch { parked.await() }) }

        val rounds = 1_000
        val failure = AtomicReference<Throwable?>(null)
        val walks = AtomicLong(0)
        val completions = AtomicLong(0)
        val phase = CyclicBarrier(2)

        // Exactly Server.kt's and agent_backdoor's expression.
        val walker = Thread({
            try {
                repeat(rounds) {
                    phase.await()
                    box.jobs.any { !it.isCancelled && it.isActive }
                    walks.incrementAndGet()
                }
            } catch (t: Throwable) {
                if (t !is BrokenBarrierException || failure.get() == null) failure.compareAndSet(null, t)
                phase.reset()
            }
        }, "jobs-walker")

        val churner = Thread({
            try {
                repeat(rounds) {
                    phase.await()
                    // Completes almost immediately on Dispatchers.Default, so its completion handler
                    // calls removeCompleted from a pool thread while the walker iterates.
                    box.jobs.add(scope.launch { })
                    completions.incrementAndGet()
                }
            } catch (t: Throwable) {
                if (t !is BrokenBarrierException || failure.get() == null) failure.compareAndSet(null, t)
                phase.reset()
            }
        }, "jobs-churner")

        runWorkers(walker, churner)
        parked.complete(Unit)
        scope.cancel()

        val raised = failure.get()
        assertNull(
            raised,
            "jobs.any {} while coroutines complete raised ${raised?.javaClass?.name} after " +
                "walks=${walks.get()} launches=${completions.get()}, at:\n" +
                raised?.stackTrace?.take(5)?.joinToString("\n") { "    $it" }
        )
        assertEquals(rounds.toLong(), walks.get(), "the jobs walker did not complete its fixed stress work")
        assertEquals(rounds.toLong(), completions.get(), "the job churner did not complete its fixed stress work")
    }

    /**
     * The guarantee is only worth anything if it survives the document loader, which does not construct
     * boxes — it calls `setPropertyRaw("children", …)` with a plain `LinkedHashSet` (`saving.kt`). So the
     * normalisation lives in `setPropertyRaw`, and this pins it.
     */
    @Test
    fun aPlainSetHandedToSetPropertyRawIsNormalised() {
        val session = session("topology-normalise")
        val a = boxIn(session, "a")
        val b = boxIn(session, "b")

        // Exactly saving.kt's shape.
        session.root.setPropertyRaw("children", session.root.children.toMutableSet().apply { add(a) })
        a.setPropertyRaw("parents", a.parents.toMutableSet().apply { add(session.root) })
        a.setPropertyRaw("nested", mutableSetOf(b))

        listOf("children" to session.root, "parents" to a, "nested" to a).forEach { (property, box) ->
            val stored = box.getPropertyRaw(property)
            assertTrue(
                stored is java.util.concurrent.CopyOnWriteArraySet<*>,
                "$property was stored as ${stored?.javaClass?.name}, so a loaded document would still race"
            )
        }
        assertEquals(setOf(a), session.root.children)
        assertEquals(setOf(b), a.nested)

        // And a non-set value on one of those names is left alone rather than mangled.
        a.setPropertyRaw("nestedIn", session.root)
        assertEquals(session.root, a.getPropertyRaw("nestedIn"))
    }

    /**
     * The other half of that normalisation, and the regression it caused.
     *
     * Normalising inside `setPropertyRaw` means the stored object is no longer the object handed in, and
     * `Delegation.getValue` used to fill in a missing default by writing it back and then returning the
     * value it had *passed*, not the one now in the bag. So the first-ever read of `children` / `parents` /
     * `nested` yielded a throwaway `LinkedHashSet` while the bag held a separate `CopyOnWriteArraySet`, and
     * every mutation through that first reference went nowhere.
     *
     * It broke `nestInto`, whose whole job is `parent.nested.add(this)`: on a parent whose `nested` had
     * never been read the child ended up with `nestedIn` correctly set and absent from the parent's
     * `nested`, which is `examples.import.metadata-nested` failing on `require(child in nestParent.nested)`.
     * A one-way edge like that is worse than a missing one — the child claims a parent that disowns it.
     */
    @Test
    fun firstReadOfADefaultedTopologySetReturnsTheStoredObject() {
        val session = session("topology-default")
        val parent = boxIn(session, "parent")
        val child = boxIn(session, "child")

        listOf<Pair<String, () -> MutableSet<Box>>>(
            "nested" to { parent.nested },
            "children" to { parent.children },
            "parents" to { parent.parents },
        ).forEach { (property, read) ->
            // The first read is the one that fills in the default and writes it back.
            val firstRead = read()
            assertSame(
                parent.getPropertyRaw(property), firstRead,
                "the first read of an unset `$property` handed back a different object than the bag holds, " +
                    "so anything mutating it (nestInto, connect) writes into a throwaway"
            )

            // Which is the property that actually matters: a mutation through it has to survive.
            firstRead.add(child)
            assertEquals(
                setOf(child), read(),
                "mutating the object returned by the first read of `$property` did not persist"
            )
        }
    }
}
