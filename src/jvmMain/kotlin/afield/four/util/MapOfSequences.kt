package afield.four.util

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