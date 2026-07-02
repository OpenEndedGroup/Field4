package afield.four.util

import afield.Generator

class MapOfSequences<T> {

    private val lock = Any()
    val starts = mutableMapOf<T, Sequence<Any?>>()
    var ongoings = mutableMapOf<T, Iterator<Any?>>()

    fun update() {
        val current = synchronized(lock) {
            ongoings.entries.map { it.key to it.value }
        }

        current.forEach { (key, iterator) ->
            val stillCurrent = synchronized(lock) {
                ongoings[key] === iterator
            }
            if (!stillCurrent) return@forEach

            val keep = if (iterator.hasNext()) {
                iterator.next()
                iterator.hasNext()
            } else {
                false
            }

            synchronized(lock) {
                if (ongoings[key] === iterator && !keep) {
                    ongoings.remove(key)
                }
            }
        }

        synchronized(lock) {
            starts.forEach { (t, u) ->
                ongoings[t] = u.iterator()
            }
            starts.clear()
        }
    }

    operator fun set(t: T, sequence: Sequence<Any?>) {
        synchronized(lock) {
            starts[t] = sequence
        }
    }

    fun remove(t: T) {
        synchronized(lock) {
            starts.remove(t)
            ongoings.remove(t)
        }
    }
}

class MapOfGenerators<O, T> {

    val starts = mutableMapOf<String, Generator<O, T>>()
    var ongoings = mutableMapOf<String, Generator<O, T>>()

    fun update(input: O): MutableList<T> {
        val collected = mutableListOf<T>()
        ongoings.values.retainAll {
            val o = it.next(input)
            if (o != null)
                collected.add(o)
            !it.isCompleted
        }
        starts.forEach { (t, u) ->
            val o = u.start()
            if (!u.isCompleted)
                ongoings[t] = u
        }
        starts.clear()
        return collected
    }

    fun updateNoCollect(input: O) {
        val ongoingIterator = ongoings.values.iterator()
        while (ongoingIterator.hasNext()) {
            val gen = ongoingIterator.next()
            gen.next(input)
            if (gen.isCompleted) {
                ongoingIterator.remove()
            }
        }

        val startIterator = starts.entries.iterator()
        while (startIterator.hasNext()) {
            val entry = startIterator.next()
            val gen = entry.value
            gen.start()
            if (!gen.isCompleted) {
                ongoings[entry.key] = gen
            }
            startIterator.remove()
        }
    }

    operator fun set(t: String, g: Generator<O, T>) {
        starts[t] = g
    }

    fun remove(t : String) {
        starts.remove(t)
        ongoings.remove(t)
    }
}
