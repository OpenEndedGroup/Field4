import afield.petri.*

// A groove with a bit of everything: a read arc, a weighted arc, a capacity and
// priorities. `bd hh` goes round fast; meanwhile `build` watches `mid` without
// consuming it, and every third time it has counted, the weighted arc drains all
// three at once and you get a snare — `crash` has no output side, so it is a drain and
// the net stays bounded and periodic. Change a number and run the box again.
val net = petri("""
    #petri
    !grid 1/8
    !conflict priority

    pulse : 1
    fill  : 0 ^3

    pulse  -[ a @1/8 pri=9 : bd ]-> mid
    mid    -[ b @1/8 pri=9 : hh ]-> pulse

    &mid   -[ build @1/8 pri=3      ]-> fill
    3*fill -[ crash @1/8 pri=1 : sd ]->
""")

showPetriNet(net, w = 760.0, h = 400.0)

println("period: ${net.period()}   deadlocks: ${net.willDeadlock()}")
for (c in 0L..3L) println("cycle %d   %s".format(c, net.emits()
    .events(PatArc(PatQ.of(c), PatQ.of(c + 1)))
    .filter { it.hasOnset }.sortedBy { it.arc.start }.joinToString(" ") { it.value }))
println(net.report())

println(showDirtAudio(net.emits(), cycles = 8, cps = 1.5, channel = "sound").describe())
displayOpen("sound")
