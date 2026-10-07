/*
NOT launched by the transcript, because the point is that you drive this one yourself.
Three things to try, in this order:

  1. Run it and press STOP inside the eight seconds.
     -> both lines, with .end { } first. Note that they appear on the frame AFTER the
        button: cancelling the body resumes it through the dispatcher, so the end phase
        lands on a later Runloop tick and stop is not a synchronous barrier for it.
  2. Run it and let the eight seconds elapse.
     -> only the finally line. The body returned; there was no cancellation to catch,
        and a cancellation is the only route into .end { }.
  3. Run it, and while it is still counting, RUN IT AGAIN.
     -> both lines again. A re-run bumps the strand generation rather than the launch
        epoch, but either way the end job registers and runs — and it runs from inside
        frame()'s catch, before the cancellation unwinds the body's finally.

So .end { } means "when this animation is cancelled", not "when it finishes".
*/
val started = AnimationClock.nowSeconds()
val runFor = 8.0
val notes = mutableListOf<String>()
var nextDraw = 0.0

val show: (String) -> Unit = { headline -> markdown("main", headline.md) }

/*
Cleanup goes to its OWN channel. Re-running the box immediately repaints "main" from
the new launch, which would clobber the old launch's cleanup line before you could
read it -- and the re-run case is the whole point of instruction 3.
{ok:} styles them as the theme's success colour.
*/
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
