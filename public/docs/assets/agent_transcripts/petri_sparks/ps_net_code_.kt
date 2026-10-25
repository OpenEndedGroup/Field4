import afield.simulation.petri.PetriNet
import afield.simulation.petri.PetriRun
import afield.simulation.petri.petri

// Inherited by the box below, which is all it takes for the particles to hear the net.
prop<PetriNet>(sparkNet)
prop<PetriRun>(sparkRun)

sparkNet =
    petri(
        """
    #petri
    !grid 1/8

    // four places round: a kick each lap, and a token into charge
    k0 : 1
    k0 -[ kick : bd ]-> k1 + charge
    k1 -[ k1.go ]-> k2
    k2 -[ k2.go ]-> k3
    k3 -[ k3.go ]-> k0

    // three places round: a snare each lap
    s0 : 1
    s0 -[ snare : sn ]-> s1
    s1 -[ s1.go ]-> s2
    s2 -[ s2.go ]-> s0

    // five places round: two hats each lap
    h0 : 1
    h0 -[ hat : hh ]-> h1
    h1 -[ h1.go ]-> h2
    h2 -[ hat.2 : hh ]-> h3
    h3 -[ h3.go ]-> h4
    h4 -[ h4.go ]-> h0

    // four kicks fill charge, and emptying it is a clap
    charge : 0 ^4
    4*charge -[ clap : cp ]->
""",
    )

// One run, shared: a run remembers the steps it has taken, so every query below reuses them.
sparkRun = sparkNet.run()
println("net ready: ${sparkNet.places.size} places, ${sparkNet.transitions.size} transitions")
