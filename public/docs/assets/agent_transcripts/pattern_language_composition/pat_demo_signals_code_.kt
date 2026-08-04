// A signal is not a new type: it is a PatPattern<Double> that answers EVERY arc with
// exactly one event, sampled at the arc's exact rational midpoint. Nothing plays a
// continuous pattern directly — you discretise it, and segment(n) is how.
showPatRoll("signals-segment")

// The same renderer, live, on whatever you feel like editing:
showPatRollOf(
    "sine()" to sine().segment(8).texts(),
    "sine().rev()" to sine().segment(8).rev().texts(),
    "sine().quantise(0.25)" to sine().quantise(0.25).segment(8).texts(),
    title = "edit me — a signal, reversed, and quantised",
    subtitle = "the midpoint convention is what makes rev() line up",
    cycles = 2,
    channel = "live",
)
displayOpen("main"); displayOpen("live")

// Exactness, not floating point: the midpoint of 3/7 .. 5/7 is exactly 4/7.
val arc = PatArc(PatQ.of(3, 7), PatQ.of(5, 7))
println("midpoint of $arc = ${patArcMidpoint(arc)}")
println("segment(4) arcs = " + sine().segment(4)
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { "${it.arc}" })
println("segment(4) values = " + sine().segment(4)
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { "%.4f".format(it.value) })
