package afield

import afield.stdlib.Action
import afield.stdlib.actions
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    /** Runs [body] until [deadline] or first failure, on its own thread, with a session bound. */
    private fun worker(
        name: String,
        session: SessionInformation,
        start: CyclicBarrier,
        deadline: Long,
        failure: AtomicReference<Throwable?>,
        body: () -> Unit
    ) = Thread({
        thisSessionInfo.set(session)
        start.await()
        try {
            while (System.currentTimeMillis() < deadline && failure.get() == null) body()
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        }
    }, name)

    private fun assertNoRace(failure: AtomicReference<Throwable?>, what: String, vararg counters: Pair<String, AtomicLong>) {
        val raised = failure.get()
        assertNull(
            raised,
            "$what raised ${raised?.javaClass?.name} after " +
                counters.joinToString { "${it.first}=${it.second.get()}" } + ", at:\n" +
                raised?.stackTrace?.take(5)?.joinToString("\n") { "    $it" }
        )
        counters.forEach { (label, counter) ->
            assertTrue(counter.get() > 50, "$label barely ran (${counter.get()})")
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

        val deadline = System.currentTimeMillis() + 2_000
        val failure = AtomicReference<Throwable?>(null)
        val resyncs = AtomicLong(0)
        val edges = AtomicLong(0)
        val start = CyclicBarrier(3)

        val resyncer = worker("resync-loop", session, start, deadline, failure) {
            // Keep the queue fed the way the server does, then drain it exactly as the runloop does.
            needsResync.add(root to "children")
            needsResync.add(root to "actions")
            doResync()
            resyncs.incrementAndGet()
        }
        val connectors = (0 until 2).map { slot ->
            var n = 0
            worker("edge-churn-$slot", session, start, deadline, failure) {
                // Alternate on the *same* box, in a range this thread owns: connecting an
                // already-connected box (or disconnecting an absent one) returns early without
                // touching the set, and a churn loop that saturates into no-ops is a test that
                // passes for the wrong reason. Measured: 18M "edges", ~300 real mutations.
                val child = pool[1_200 + slot * 150 + ((n / 2) % 150)]
                if (n % 2 == 0) root.connect(child) else root.disconnect(child)
                n++
                edges.incrementAndGet()
            }
        }

        val all = listOf(resyncer) + connectors
        all.forEach { it.start() }
        all.forEach { it.join(30_000) }

        assertNoRace(failure, "doResync() while boxes connect and disconnect", "resyncs" to resyncs, "edges" to edges)
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

        val deadline = System.currentTimeMillis() + 2_000
        val failure = AtomicReference<Throwable?>(null)
        val gathers = AtomicLong(0)
        val edges = AtomicLong(0)
        val start = CyclicBarrier(3)

        val gatherer = worker("gather-loop", session, start, deadline, failure) {
            leaf.gather<MutableMap<String, Action>>("actions")
            leaf.visitParents { }
            gathers.incrementAndGet()
        }
        val churners = (0 until 2).map { slot ->
            var n = 0
            worker("parent-churn-$slot", session, start, deadline, failure) {
                val ancestor = ancestors[1_200 + slot * 150 + ((n / 2) % 150)]
                // The shape used by attachRootToMetaroot, history.kt's topology restore and the
                // completion probes: mutate the edge set directly.
                if (n % 2 == 0) leaf.parents.add(ancestor) else leaf.parents.remove(ancestor)
                n++
                edges.incrementAndGet()
            }
        }

        val all = listOf(gatherer) + churners
        all.forEach { it.start() }
        all.forEach { it.join(30_000) }

        assertNoRace(failure, "gather()/visitParents() while parent edges churn", "gathers" to gathers, "edges" to edges)
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
}
