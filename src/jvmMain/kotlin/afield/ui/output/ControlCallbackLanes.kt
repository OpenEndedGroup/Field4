package afield.ui.output

import afield.app.diagnostics.logging.lprintln
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How a control's callback runs when a person moves it: one box at a time, the newest value of each
 * control winning, off the websocket's thread.
 *
 * A drag sends about sixty updates a second. Each one's callback used to run at once, on the thread
 * handling the websocket message, one after another; a redraw that costs 32 ms (a shadowed mesh, a
 * ray-traced image) then fell further behind the hand with every event, and the picture finished two
 * seconds after the drag did. Now an update is queued in its box's lane. While a callback of that
 * box runs, a newer value for a control replaces the older one still waiting, so a slow redraw skips
 * the values it could not have shown, and the last value of a drag always runs. The lane runs on a
 * background thread, as callbacks did before (never the Runloop); a box's callbacks never overlap,
 * so box code reading the variables a callback writes sees one change at a time.
 *
 * A momentary control (a button, a key) is different: its press and its release are edges, not
 * values, and both always run, in order. A control declared `everyValue = true` (a gesture recorder
 * that needs the whole path) opts out and runs every value.
 *
 * [runNow] is the synchronous path for code that sets a control and expects its callback to have
 * run (`setControl`, a presentation step, a glide's step): it waits for the lane's current callback,
 * drops that control's waiting value, which it supersedes, and runs on the caller's thread.
 */
internal object ControlCallbackLanes {
    private class Lane {
        val running = ReentrantLock()
        /** Waiting callbacks per control, in the order the controls first queued: one value, or every edge. */
        val waiting = LinkedHashMap<String, ArrayDeque<() -> Unit>>()
        var draining = false
    }

    private val lanes = ConcurrentHashMap<String, Lane>()

    /** Controls declared `everyValue = true`: each of their values runs, none is skipped. */
    private val everyValue: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Starts a lane's drain; tests run it inline or hold it. */
    @OptIn(DelicateCoroutinesApi::class)
    internal var dispatch: (() -> Unit) -> Unit = { work -> GlobalScope.launch { work() } }

    /** Mark [controlId] as wanting every value (a gesture recorder), or not. */
    fun everyValue(controlId: String, on: Boolean) { if (on) everyValue += controlId else everyValue -= controlId }

    /**
     * Queue [callback] for [controlId] in [lane]. A value replaces the value still waiting for that
     * control; an [edge] (a press or a release) is appended, so none is lost, and so is every value of
     * a control declared `everyValue`.
     */
    fun submit(lane: String, controlId: String, edge: Boolean = false, callback: () -> Unit) {
        val l = lanes.computeIfAbsent(lane) { Lane() }
        val start = synchronized(l) {
            val queue = l.waiting.getOrPut(controlId) { ArrayDeque() }
            if (!edge && controlId !in everyValue) queue.clear()
            queue.addLast(callback)
            if (l.draining) false else { l.draining = true; true }
        }
        if (start) dispatch { drain(l) }
    }

    /**
     * Run [callback] for [controlId] now, after whatever [lane] is running, on the caller's thread. A
     * value still waiting for that control is dropped: this one supersedes it.
     */
    fun <T> runNow(lane: String, controlId: String, callback: () -> T): T {
        val l = lanes.computeIfAbsent(lane) { Lane() }
        synchronized(l) { if (controlId !in everyValue) l.waiting.remove(controlId) }
        return l.running.withLock { callback() }
    }

    private fun drain(l: Lane) {
        while (true) {
            val next = synchronized(l) {
                val first = l.waiting.entries.firstOrNull()
                if (first == null) { l.draining = false; return }
                val work = first.value.removeFirst()
                if (first.value.isEmpty()) l.waiting.remove(first.key)
                work
            }
            l.running.withLock {
                try { next() } catch (e: Throwable) { lprintln("error,controls", "Error in control callback: ${e.message}") }
            }
        }
    }
}

/** The lane a control's callbacks run in: its box, or the control itself when no box claims it. */
internal fun controlLane(controlId: String): String = controlOwnerBoxId(controlId) ?: controlId

/**
 * The one way a person's change to a control reaches its callback: every `*-update` handler (slider,
 * vec2, checkbox, string, colour, array, button and keys) calls this. Values coalesce, newest winning;
 * an [edge] (a momentary control's press or release) never does. See [ControlCallbackLanes].
 */
internal fun deliverControlCallback(controlId: String, edge: Boolean = false, kind: String = "", callback: () -> Unit) =
    // [kind] keeps two message types of one control apart: a gesture pad's drag values and its
    // recorded take share an id, and a drag value must not replace the take still waiting.
    ControlCallbackLanes.submit(controlLane(controlId), if (kind.isEmpty()) controlId else "$controlId#$kind", edge, callback)
