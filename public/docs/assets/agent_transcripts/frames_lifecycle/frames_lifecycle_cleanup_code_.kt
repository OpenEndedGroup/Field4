/*
Launch this box yourself and try three exits:
1. Stop before eight seconds: .end runs, then finally, on a later Runloop tick.
2. Let eight seconds elapse: the body returns and only finally runs.
3. Re-run while it is counting: the old run prints .end, then finally.
The .end block handles cancellation; finally also handles a normal return.
*/
val started = AnimationClock.nowSeconds()
val runFor = 8.0
var nextDraw = 0.0

val show: (String) -> Unit = { headline -> markdown("main", headline.md) }

// Keep a bounded cleanup history across launches. Old cleanup cannot overwrite a
// newer run's cleanup; the live readout uses a separate channel.
val note: (String) -> Unit = { line ->
    markdownAppend("cleanup", "{ok:${line.md}}", maxEntries = 12)
}

frames {
    try {
        var elapsed = AnimationClock.nowSeconds() - started
        while (elapsed < runFor) {
            if (elapsed >= nextDraw) {
                show("running - ${elapsed.f1} s of ${runFor.f0}, frame ${"%,d".format(frameNumber)}")
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
