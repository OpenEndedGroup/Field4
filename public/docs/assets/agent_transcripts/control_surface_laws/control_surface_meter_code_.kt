// Two meters, both fed by a `source` lambda. Nothing here loops: the box says where the
// number comes from, and a single shared daemon thread samples it at 30 Hz. The sampler is
// retired when this box runs again without declaring the meter, so it does not outlive it.
//
// A meter is an output. It is not cached across a rerun (there is nothing to preserve), it
// posts nothing when you press it, and it is the one control here that reads rather than
// writes.
val start = System.nanoTime()
fun envelope(): Double {
    val seconds = (System.nanoTime() - start) / 1.0e9
    return 0.5 + 0.48 * Math.sin(seconds * 2.2)
}

controlGroup("levels") {
    meter("level", min = 0.0, max = 1.0, source = { envelope() })
    // The same signal on a logarithmic meter, which is how a level is actually read: the
    // interesting 20 dB near the top gets most of the bar.
    meter(
        "level, log",
        min = 0.001,
        max = 1.0,
        law = ControlLaw.LOGARITHMIC,
        source = { envelope().coerceAtLeast(0.001) }
    )
    // And a slider, so you can see the meter is not one: this posts, the meters do not.
    slider("trim", min = -24.0, max = 24.0, step = 0.1, initialValue = 0.0) { }
}

println("two meters, sampled at 30 Hz from a source lambda. Nothing in this box loops.")
println("the remote tablet polls at 450 ms, so there they are indicators rather than VU meters.")
