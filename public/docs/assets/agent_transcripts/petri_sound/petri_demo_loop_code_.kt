import afield.petri.*

// A place holds tokens; a transition fires when all its inputs have one. Give a
// transition a sound after the `:` and firing it plays that sound. That is the whole
// idea — everything else is more of it.
val net = petri("""
    #petri
    !timing step
    !grid 1/8
    !conflict order

    kick : 1
    kick -[ hit  @1/8 : bd ]-> hats
    hats -[ back @1/8 : sd ]-> kick
""")

showPetriNet(net, w = 640.0, h = 300.0)

// `emits()` is a PatPattern<String>, exactly like `pat("bd sd")` is. So every pattern
// combinator applies, and so does the pattern-language's own audio renderer.
println(showDirtAudio(net.emits(), cycles = 4, cps = 1.0, channel = "sound").describe())

println("plays: " + net.emits().events(PatArc(PatQ.ZERO, PatQ.ONE))
    .filter { it.hasOnset }.sortedBy { it.arc.start }.joinToString(" ") { it.value })
println("period: ${net.period()} cycles")

displayOpen("sound")
