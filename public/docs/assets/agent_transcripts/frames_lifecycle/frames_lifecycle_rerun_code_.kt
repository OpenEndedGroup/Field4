/*
Run twice without stopping: the counter restarts, the colour changes, and one loop
remains. The old run cancels at its next frame(), after its suspended body resumes.
Compare frameNumber with elapsed time: the counter measures work, not seconds.
A time-derived hue makes the new launch visible even with a fresh script context.
*/
val hue = ((System.nanoTime() / 1_000_000L) % 360L).toInt()
val swatch = "hsl($hue 70% 60%)"
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0

// Keep this launch's swatch beside its frame count and elapsed-time readout.
val show: (Int, Double) -> Unit = { n, elapsed ->
    val rate = if (elapsed > 0.5) "%,.0f frames a second".format(n / elapsed) else "measuring"
    showRow(
        "main",
        "<span style='display:inline-block;width:58px;height:58px;border-radius:6px;background:$swatch'></span>",
        """
        ### frame ${"%,d".format(n)}

        {muted:$rate · ${elapsed.f1} s elapsed}

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
