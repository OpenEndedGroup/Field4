package afield.four.util

import afield.Generator

class MapOfSequences<T> {

    val starts = mutableMapOf<T, Sequence<Any?>>()
    var ongoings = mutableMapOf<T, Iterator<Any?>>()

    fun update() {
        ongoings.values.retainAll {
            if (it.hasNext()) {
                it.next()
            }
            it.hasNext()
        }
        starts.forEach { (t, u) ->
            ongoings[t] = u.iterator()
        }
        starts.clear()
    }

    operator fun set(t: T, sequence: Sequence<Any?>) {
        starts[t] = sequence
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

    operator fun set(t: String, g: Generator<O, T>) {
        starts[t] = g
    }
}
