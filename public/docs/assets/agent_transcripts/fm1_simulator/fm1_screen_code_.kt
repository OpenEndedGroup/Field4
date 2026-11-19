/*
The FM-1's LCD for factory project 4, from a private simulator rendered offline for half a
second, shown at three times its size.
*/
FM1Sim.create().use { fm1 ->
    fm1.preset(4)
    val l = FloatArray(4800)
    val r = FloatArray(4800)
    repeat(5) { fm1.render(l, r, 4800, 48000f) }

    display("screen", fm1.screenPng(scale = 3))
}
