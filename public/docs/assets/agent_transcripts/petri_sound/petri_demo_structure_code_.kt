import afield.petri.*

// Two nets in one. The fast ring plays the bar; the slow ring is the arrangement, one
// token walking verse -> verse -> chorus and back. Same mechanism at both timescales,
// which is the argument for using a net rather than a sequencer and a separate song mode.
val net = petri("""
    #petri
    !grid 1/4

    verse.a : 1
    verse.a -[ v1 @1     ]-> verse.b
    verse.b -[ v2 @1     ]-> chorus
    chorus  -[ back @1   ]-> verse.a

    beat : 1
    &verse.a + beat -[ vbeat @1/4 : bd ]-> beat2
    &verse.b + beat -[ vbeat2 @1/4 : bd ]-> beat2
    &chorus + beat  -[ cbeat @1/4 : sd ]-> beat2
    beat2 -[ off @1/4 : hh ]-> beat
""")

showPetriNet(net, w = 760.0, h = 420.0)

// Eight cycles: two verses, a chorus, and round again — audibly.
println(showDirtAudio(net.emits(), cycles = 8, cps = 2.0, channel = "sound").describe())
println("bar length: ${net.period()} cycles")
displayOpen("sound")
