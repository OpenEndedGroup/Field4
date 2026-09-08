import afield.petri.*
import afield.layout.D2Layout

fun philosophers(count: Int = 5): PetriNet = petri {
    for (index in 0 until count) place("fork.$index", tokens = 1)
    for (index in 0 until count) {
        val right = (index + 1) % count
        place("think.$index", tokens = 1)
        place("eat.$index")
        t("take.$index") {
            from("think.$index"); from("fork.$index"); from("fork.$right"); to("eat.$index")
        }
        t("drop.$index") {
            from("eat.$index"); to("think.$index"); to("fork.$index"); to("fork.$right")
        }
    }
}

val net = philosophers()
// Asking for TALA is safe whether or not the helper exists — that is the whole contract.
showPetriNet(net, layout = PetriLayout.TALA, w = 620.0, h = 520.0)

if (D2Layout.available)
    "TALA: d2 ${D2Layout.version}, places sized as real boxes and arcs routed"
else
    "helper absent — this is FORCE's drawing. Box 3 says how to build it."
