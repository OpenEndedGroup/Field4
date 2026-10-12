// Open rows follow the value. This box keeps running and adds a reading every half second;
// open "readings" in the tree and watch its size and last rows change without re-running.
val readings = mutableListOf<Double>()
val state = linkedMapOf<String, Any?>("readings" to readings, "last" to null)
tree("live", state)

frames {
    var next = System.currentTimeMillis()
    while (frame()) {
        if (System.currentTimeMillis() >= next && readings.size < 120) {
            next += 500
            readings += Math.round(Math.sin(readings.size * 0.3) * 1000) / 1000.0
            state["last"] = readings.last()
        }
    }
}
