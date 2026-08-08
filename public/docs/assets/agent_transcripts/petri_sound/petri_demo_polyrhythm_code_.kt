import afield.petri.*

// Two independent rings, one five long and one three long, sharing a clock. Nothing in
// this source says "fifteen" — that is a property of the net, and the net can tell you.
val net = petri("""
    #petri
    !grid 1/15

    a0 : 1
    a0 -[ a0.go : bd ]-> a1
    a1 -[ a1.go      ]-> a2
    a2 -[ a2.go : bd ]-> a3
    a3 -[ a3.go      ]-> a4
    a4 -[ a4.go      ]-> a0

    b0 : 1
    b0 -[ b0.go : hh ]-> b1
    b1 -[ b1.go      ]-> b2
    b2 -[ b2.go : hh ]-> b0
""")

showPetriNet(net, w = 700.0, h = 340.0)

val lasso = net.settle()!!
println("settles after ${lasso.loopSteps} steps = ${lasso.duration} cycles")
println("${net.tInvariants().size} T-invariants — one per independent ring")

// Four cycles, so you can hear the two rings pull apart and line back up.
println(showDirtAudio(net.emits(), cycles = 4, cps = 0.75, channel = "sound").describe())
displayOpen("sound")
