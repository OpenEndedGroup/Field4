/*
Launch this box yourself and try three exits:
1. Stop before eight seconds: .end runs, then finally, on a later Runloop tick.
2. Let eight seconds elapse: the body returns and only finally runs.
3. Re-run while it is counting: the old run prints .end, then finally.
The .end block handles cancellation; finally also handles a normal return.
*/
val started = AnimationClock.nowSeconds()
val runFor = 8.0
val notes = mutableListOf<String>()
var nextDraw = 0.0

val show: (String) -> Unit = { headline -> markdown("main", headline.md) }

// Keep cleanup in its own channel so a new launch's readout leaves it visible.
val note: (String) -> Unit = { line ->
    notes += line
    markdown("cleanup", notes.joinToString("\n\n") { "{ok:${it.md}}" })
}

frames {
    try {
        var elapsed = AnimationClock.nowSeconds() - started
        while (elapsed < runFor) {
            if (elapsed >= nextDraw) {
                show("running - %.1f s of %.0f, frame %,d".format(elapsed, runFor, frameNumber))
                nextDraw = elapsed + 0.1
            }
            frame()
            elapsed = AnimationClock.nowSeconds() - started
        }
        show("the body returned normally at frame %,d".format(frameNumber))
        note("returned, not cancelled - so .end { } will not run")
    } finally {
        note("finally inside the body - runs whatever happens")
    }
}
    .end {
        note(".end { } - on a cancellation (stop, re-run or end()), and it runs first")
    }
