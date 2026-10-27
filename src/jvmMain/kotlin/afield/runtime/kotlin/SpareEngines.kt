package afield.runtime.kotlin

import afield.app.diagnostics.LatencySpans
import afield.app.diagnostics.logging.lprintln
import afield.document.graph.Box
import afield.document.graph.makeSpareKI
import java.net.URLClassLoader
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred

/**
 * A box's next Kotlin engine, built while the box is idle.
 *
 * Running a box gives it a fresh REPL, and building one costs a few hundred milliseconds that the
 * person who pressed the key waits through. [SpareEngines] moves that wait off the keypress: once a
 * run has its verdict, the box's next engine is built on a background thread, and the next run
 * takes the one already standing by. The run still gets a fresh engine; only the waiting moves.
 *
 * Engines are tens of megabytes, so only a handful of boxes hold one at a time, the least recently
 * prepared giving way first. A spare is dropped when its box is retired, and refused if the shared
 * class loader has gained jars since the spare was built, because an engine fixes its compile
 * classpath when it is created. `-Dafield.kotlin.spareEngines=0` turns the whole thing off.
 */

/**
 * Engines built ahead of a box's next `execute-all`, so running a box does not wait for a new
 * Kotlin engine.
 *
 * Running a box (`execute-all`) gives it a fresh REPL: a new engine whose stdlib intro is compiled
 * and evaluated before the box's code. Built on the spot that is ~250 ms of every run of a one-line
 * box on an M1 Max (`engine.reset` in `dev_docs/testing/FEEL_BENCHMARKS.md`). So once a run has
 * its verdict, [prepare] builds the box's next engine in the background, and the next run [take]s
 * it. The run still gets a fresh engine; only the wait moves.
 *
 * A spare is the box's only until [discard] (called by [Box.disposeKi], so retiring a box, reloading
 * personal code and recycling an engine all drop it), and is refused by [take] if the shared class
 * loader has gained jars since it was built, because an engine snapshots its compile classpath when
 * it is created. Engines are tens of MB, so at most [capacity] boxes hold one, least recently
 * prepared first out; `-Dafield.kotlin.spareEngines=0` (or `AFIELD_SPARE_ENGINES=0`) turns this off.
 */
object SpareEngines {

    val capacity: Int = (System.getProperty("afield.kotlin.spareEngines") ?: System.getenv("AFIELD_SPARE_ENGINES"))
        ?.toIntOrNull() ?: 4

    private class Spare(val engine: KotlinScriptEngine, val classpath: Int)

    /**
     * [token] names the build a slot is waiting for; one that no longer matches has been superseded.
     * [building] completes when that build has stored its spare, or failed.
     */
    private class Slot(var token: Long = 0, var spare: Spare? = null, var building: CompletableDeferred<Unit>? = null)

    private val lock = Any()
    private var nextToken = 0L

    // Access-ordered, so the eldest entry is the box whose spare was prepared or taken longest ago.
    private val slots = LinkedHashMap<Box, Slot>(16, 0.75f, true)

    private val builder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spare-kotlin-engine").apply { isDaemon = true }
    }

    /**
     * The box's spare engine, if it has one and it is still current; it is no longer the spare. A
     * spare still being built is waited for: building another beside it would only compete with it.
     */
    suspend fun take(box: Box): KotlinScriptEngine? {
        synchronized(lock) { slots[box]?.building }?.await()
        val spare = synchronized(lock) { slots[box]?.let { slot -> slot.spare.also { slot.spare = null } } } ?: return null
        if (spare.classpath != classpathFingerprint()) {
            lprintln("info,ki,startup", "spare engine for ${box.id} refused: the classpath changed since it was built")
            dispose(spare.engine)
            return null
        }
        return spare.engine
    }

    /** Build [box]'s next engine in the background, unless it has one or one is being built. */
    fun prepare(box: Box) {
        if (capacity <= 0) return
        val evicted = mutableListOf<KotlinScriptEngine>()
        val (token, done) = synchronized(lock) {
            val slot = slots.getOrPut(box) { Slot() }
            if (slot.spare != null || slot.building != null) return
            slot.building = CompletableDeferred()
            slot.token = ++nextToken
            while (slots.size > capacity) {
                val eldest = slots.entries.first()
                if (eldest.key === box) break
                eldest.value.spare?.let { evicted += it.engine }
                slots.remove(eldest.key)
            }
            slot.token to slot.building!!
        }
        evicted.forEach(::dispose)
        builder.execute {
            val classpath = classpathFingerprint()
            val superseded = { synchronized(lock) { slots[box]?.token != token } }
            val engine = try {
                LatencySpans.time("engine.spare", box = box.id) { makeSpareKI(box, superseded) }
            } catch (t: Throwable) {
                lprintln("warning,ki,startup", "spare engine for ${box.id} failed to build: $t")
                synchronized(lock) { slots[box]?.takeIf { it.token == token }?.let { it.building = null } }
                done.complete(Unit)
                return@execute
            }
            val kept = synchronized(lock) {
                val slot = slots[box]?.takeIf { it.token == token }
                if (slot != null) {
                    slot.building = null
                    slot.spare = Spare(engine, classpath)
                }
                slot != null
            }
            done.complete(Unit)
            if (!kept) dispose(engine)
        }
    }

    /** The spare waiting for [box]'s next run, without taking it; for tests. */
    internal fun peek(box: Box): KotlinScriptEngine? = synchronized(lock) { slots[box]?.spare?.engine }

    /** Drop [box]'s spare, and supersede one being built (it is disposed when its build returns). */
    fun discard(box: Box) {
        val spare = synchronized(lock) { slots.remove(box)?.spare }
        spare?.let { dispose(it.engine) }
    }

    /** Jars only ever join the shared loaders, so their count says whether a spare's classpath is current. */
    private fun classpathFingerprint(): Int =
        listOfNotNull(KotlinInterface.sharedClassLoader, KotlinInterface2.sharedClassLoader)
            .sumOf { (it as? URLClassLoader)?.urLs?.size ?: 0 }

    private fun dispose(engine: KotlinScriptEngine) {
        try {
            engine.dispose()
        } catch (t: Throwable) {
            lprintln("error,ki", "disposing a spare engine failed: ${t.message}")
        }
    }
}
