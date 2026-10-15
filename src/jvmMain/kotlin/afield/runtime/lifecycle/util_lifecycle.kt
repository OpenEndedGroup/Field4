package afield.runtime.lifecycle

import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.Box
import afield.runtime.execution.plus
import afield.runtime.kotlin.cache

import afield.audio.synth.*
import afield.data.table.get
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.app.settings.PersistOnExit.needingPersistance
import afield.document.persistence.SavingContext.Companion.format
import afield.app.diagnostics.logging.lprintln
import afield.data.collections.invoke
import afield.math.linalg.invoke
import java.io.File
import java.lang.reflect.*
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A var-friendly lazy: if never explicitly set, the first get() runs [initializer] and caches it.
 * If explicitly set via the setter, initializer will not run.
 *
 * [discard] forgets the value, and never waits for an initializer that is running on another thread.
 * That is the point of the class rather than a detail: its one user is `Box.ki`, whose initializer
 * builds a K2 engine (about a second on a cold JVM, under the SYNCHRONIZED lock), and whose discard
 * runs on a Ktor IO thread when a document's last client leaves. A value that would otherwise be
 * dropped uncached goes to [onDiscard] instead: the cached value a discard forgets, an initializer's
 * result when a discard ran while it was being built, and an initializer's result that lost to the
 * setter. So a value that needs releasing is released exactly once, by whichever thread drops it.
 */
class LazyVar<T> private constructor(
    private val initializer: () -> T,
    private val mode: LazyThreadSafetyMode,
    private val onDiscard: ((T) -> Unit)?
) : ReadWriteProperty<Any?, T> {

    private object UNSET

    private val value = AtomicReference<Any?>(UNSET)
    private val discards = AtomicInteger()

    // For SYNCHRONIZED mode
    private val lock = Any()

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val v1 = value.get()
        if (v1 !== UNSET) {
            @Suppress("UNCHECKED_CAST")
            return v1 as T
        }
        return when (mode) {
            LazyThreadSafetyMode.SYNCHRONIZED -> synchronized(lock) {
                val v2 = value.get()
                @Suppress("UNCHECKED_CAST")
                if (v2 !== UNSET) v2 as T else build()
            }
            // PUBLICATION: concurrent builders may all run; the first to publish wins.
            else -> build()
        }
    }

    /**
     * Run the initializer and cache its result, unless it has been overtaken while it ran.
     *
     * If the setter published first (or, for PUBLICATION, another builder did), the result goes to
     * [onDiscard] and the caller gets the published value. If [discard] ran, the result goes to
     * [onDiscard] and is still returned: this caller asked before the discard, and gets what it
     * would have had if it had asked a moment earlier.
     *
     * The check comes after publishing so it cannot race [discard], which bumps [discards] before
     * taking the value: either that take finds the result and releases it, or this check sees the
     * bump and does. The compare-and-sets make sure only one of them does.
     */
    private fun build(): T {
        val generation = discards.get()
        val built = initializer()
        if (!value.compareAndSet(UNSET, built)) {
            val published = value.get()
            onDiscard?.invoke(built)
            @Suppress("UNCHECKED_CAST")
            return if (published !== UNSET) published as T else built
        }
        if (discards.get() != generation && value.compareAndSet(built, UNSET)) onDiscard?.invoke(built)
        return built
    }

    /** Never waits for an initializer in flight: a value set now wins over the one it is building. */
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value.set(value)
    }

    /**
     * Forget the value, so the next get() runs the initializer again, without waiting for an
     * initializer running on another thread. The cached value, if there was one, goes to
     * [onDiscard] here, on the calling thread. An initializer already running hands its result to
     * [onDiscard] when it returns, on its own thread, and does not cache it.
     *
     * @return whether a cached value was discarded (an initializer in flight does not count).
     */
    fun discard(): Boolean {
        discards.incrementAndGet()
        val previous = value.getAndSet(UNSET)
        if (previous === UNSET) return false
        @Suppress("UNCHECKED_CAST")
        onDiscard?.invoke(previous as T)
        return true
    }

    /**
     * How many times [discard] has run. An initializer that reads this when it starts can tell later
     * whether a discard has overtaken it, before its result is even returned (see `Box.buildKi`).
     */
    val generation: Int get() = discards.get()

    /** Returns true iff a value (either set or computed) is currently cached. */
    val isInitialized: Boolean get() = value.get() !== UNSET

    /** The cached value, or null without running the initializer. */
    fun valueOrNull(): T? {
        val v = value.get()
        @Suppress("UNCHECKED_CAST")
        return if (v === UNSET) null else v as T
    }

    companion object {
        fun <T> of(
            mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
            onDiscard: ((T) -> Unit)? = null,
            initializer: () -> T
        ): LazyVar<T> = LazyVar(initializer, mode, onDiscard)
    }
}

/** Convenience factory mirroring `lazy { }`; see [LazyVar] for [onDiscard]. */
fun <T> lazyVar(
    mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
    onDiscard: ((T) -> Unit)? = null,
    initializer: () -> T
): LazyVar<T> = LazyVar.of(mode, onDiscard, initializer)


