package afield

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `box.jobs.remove(job)` mutually recursed with its own iterator until the stack died.
 *
 * `BoxJobList` overrides `iterator()` (to read from a snapshot under its lock) and routed the
 * iterator's `remove()` back into `this@BoxJobList.remove(target)`. But `remove(E)` was *not*
 * overridden, so it resolved to `java.util.AbstractCollection.remove(Object)` — which walks
 * `iterator()` and calls `it.remove()` on the match. Round and round:
 *
 * ```
 * afield.BoxJobList.remove(Boxes.kt:979)
 * afield.BoxJobList$iterator$1.remove(Boxes.kt:1065)
 * java.base/java.util.AbstractCollection.remove(AbstractCollection.java:285)
 * ```
 *
 * It looked rare only because `AbstractCollection.remove` never reaches `it.remove()` for an element it
 * cannot find, and a job that has already finished has been pulled by `removeCompleted` — so the
 * `inside.jobs.remove(job)` that `Phased`, `stopTrackedJobs`, `ReturnValueRegistry` and every synth
 * return value do was usually a no-op against an absent job. Remove one that is still *running* and it
 * was a `StackOverflowError`: it killed the docs runner's `running_and_stopping.mp4` capture for
 * `animation_time_model.md` on 2026-08-23.
 *
 * These are plain `Job()` handles rather than launched coroutines, so completion is deterministic and
 * nothing here races: the defect is dispatch, not timing. `BoxTopologyConcurrencyTest` covers the
 * concurrent half.
 */
class BoxJobListRemoveTest {

    /** Turn the crash into a legible failure instead of a bare `StackOverflowError` from kotlin.test. */
    private fun <T> notRecursing(what: String, body: () -> T): T = try {
        body()
    } catch (e: StackOverflowError) {
        fail(
            "$what recursed until the stack died: BoxJobList.remove(Job) is dispatching to " +
                "AbstractCollection.remove(Object), which drives iterator().remove(), which calls " +
                "back into it. Top of the stack:\n" +
                e.stackTrace.take(4).joinToString("\n") { "    $it" }
        )
    }

    @Test
    fun removeByValueRemovesTheJobAndLeavesTheRest() {
        val box = Box("jobs-remove-direct")
        val keep = Job()
        val target = Job()
        box.jobs.add(keep)
        box.jobs.add(target)

        val removed = notRecursing("jobs.remove(job)") { box.jobs.remove(target) }

        assertTrue(removed, "remove() reported no change for a job that was in the list")
        assertEquals(listOf(keep), box.jobs.toList(), "remove() took the wrong job, or too many")
        assertTrue(target.isActive, "remove() must only stop tracking the job, not cancel it")
    }

    /**
     * The one case that passed *before* the fix, kept because it pins the behaviour the crash hid
     * behind: an absent element never reaches the iterator's `remove()`, so the recursion never began.
     */
    @Test
    fun removeOfAJobThatWasNeverTrackedReportsNoChange() {
        val box = Box("jobs-remove-absent")
        val present = Job()
        box.jobs.add(present)

        val removed = notRecursing("jobs.remove(untracked)") { box.jobs.remove(Job()) }

        assertFalse(removed, "remove() claimed to have removed a job it never held")
        assertEquals(listOf(present), box.jobs.toList(), "a failed remove() disturbed the list")
    }

    @Test
    fun removeThroughTheIteratorWritesToTheLiveList() {
        val box = Box("jobs-remove-iterator")
        val jobs = List(3) { Job() }
        jobs.forEach { box.jobs.add(it) }

        notRecursing("iterator().remove()") {
            val walk = box.jobs.iterator()
            while (walk.hasNext()) if (walk.next() === jobs[1]) walk.remove()
        }

        assertEquals(listOf(jobs[0], jobs[2]), box.jobs.toList(), "the iterator did not write through")
    }

    /** `removeAll` is `AbstractCollection`'s too, and reaches `it.remove()` for every match. */
    @Test
    fun removeAllThroughTheInheritedPathTerminates() {
        val box = Box("jobs-remove-all")
        val jobs = List(4) { Job() }
        jobs.forEach { box.jobs.add(it) }

        val changed = notRecursing("jobs.removeAll(…)") { box.jobs.removeAll(listOf(jobs[0], jobs[3])) }

        assertTrue(changed, "removeAll() reported no change")
        assertEquals(listOf(jobs[1], jobs[2]), box.jobs.toList(), "removeAll() removed the wrong jobs")
    }

    /**
     * White-box, because disposal has no public surface: a `remove()` that dropped the entry from
     * `delegate` but left the `invokeOnCompletion` handle behind would pass every test above while
     * leaking a live reference to the job for as long as the box lives. `removeAt` and `clear` both
     * dispose; so must this.
     */
    @Test
    fun removeDisposesTheCompletionHandle() {
        val box = Box("jobs-remove-untrack")
        val job = Job()
        box.jobs.add(job)
        assertEquals(1, trackedHandles(box), "add() did not register a completion handle to begin with")

        assertTrue(notRecursing("jobs.remove(job)") { box.jobs.remove(job) })

        assertEquals(0, trackedHandles(box), "remove() left the job's completion handle registered")
    }

    private fun trackedHandles(box: Box): Int {
        val jobs = box.jobs
        val field = jobs.javaClass.getDeclaredField("completionHandles")
        field.isAccessible = true
        return (field.get(jobs) as Map<*, *>).size
    }
}
