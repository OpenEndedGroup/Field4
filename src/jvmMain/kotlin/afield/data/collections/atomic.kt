package afield.data.collections

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A counter that code on several threads can change at once, such as a tally kept by the
 * processes of a parallel spatial rule. Write `val standing = 0.atomic` and then
 * `standing += 1` or `standing -= 1`; with `var standing = 0.atomic`, `standing++` and
 * `standing--` work too. Each change is a single atomic step, so no update is lost.
 * `"$standing"` prints the count and [value] reads it.
 *
 * In ordinary box code, where one coroutine runs at a time, a plain `var` is all a counter needs.
 */
class AtomicCount internal constructor(initial: Int) : Comparable<AtomicCount> {
    private val count = AtomicInteger(initial)

    var value: Int
        get() = count.get()
        set(value) = count.set(value)

    operator fun plusAssign(n: Int) {
        count.addAndGet(n)
    }

    operator fun minusAssign(n: Int) {
        count.addAndGet(-n)
    }

    /** Adds one and returns this same counter, so that `standing++` on a `var` counts atomically. */
    operator fun inc(): AtomicCount = apply { count.incrementAndGet() }

    /** Takes one away and returns this same counter. */
    operator fun dec(): AtomicCount = apply { count.decrementAndGet() }

    operator fun compareTo(other: Int): Int = value.compareTo(other)

    override fun compareTo(other: AtomicCount): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}

/** A running total that code on several threads can add to at once; see [AtomicCount]. */
class AtomicSum internal constructor(initial: Double) {
    private val bits = AtomicLong(initial.toRawBits())

    var value: Double
        get() = Double.fromBits(bits.get())
        set(value) = bits.set(value.toRawBits())

    operator fun plusAssign(x: Double) {
        while (true) {
            val before = bits.get()
            if (bits.compareAndSet(before, (Double.fromBits(before) + x).toRawBits())) return
        }
    }

    operator fun minusAssign(x: Double) = plusAssign(-x)

    override fun toString(): String = value.toString()
}

/** A counter starting at this value that several threads can change at once: `val n = 0.atomic`. */
val Int.atomic: AtomicCount get() = AtomicCount(this)

/** A total starting at this value that several threads can add to at once: `val sum = 0.0.atomic`. */
val Double.atomic: AtomicSum get() = AtomicSum(this)
