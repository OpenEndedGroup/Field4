/*
Run this box. Then run it AGAIN, without stopping it first.

The counter restarts and the colour changes, and there is still exactly one loop.
Running a box bumps its run generation; the Frames from the previous run closes over
the OLD generation, notices the mismatch at the top of its next frame(), and cancels.

It gets one more body call before that happens, because shouldend is read at the top of
frame() and the suspended body is already halfway through a call that will return true.

Read the rate line too. That is the whole reason AnimationClock exists: frameNumber
counts work, and the amount of work a second holds is not a constant.

nanoTime, not Random(): a fresh script context can hand out the same first random value
twice, which would make this demo silently claim nothing.
*/
val hue = ((System.nanoTime() / 1_000_000L) % 360L).toInt()
val swatch = "hsl($hue 70% 60%)"
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0

/*
showRow puts the swatch beside the readout. The swatch is raw HTML because its colour
is this launch's data, not styling.
*/
val show: (Int, Double) -> Unit = { n, elapsed ->
    val rate = if (elapsed > 0.5) "%,.0f frames a second".format(n / elapsed) else "measuring"
    showRow(
        "main",
        "<span style='display:inline-block;width:58px;height:58px;border-radius:6px;background:$swatch'></span>",
        """
        ### frame ${"%,d".format(n)}

        {muted:$rate · ${"%.1f".format(elapsed)} s elapsed}

        {muted:this launch's colour is $swatch}
        """,
    )
}

animate {
    val elapsed = AnimationClock.nowSeconds() - started
    if (elapsed >= nextDraw) {
        show(frameNumber, elapsed)
        nextDraw = elapsed + 0.1
    }
}
