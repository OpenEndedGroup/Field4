// A prepared piano on the audio engine: one bolt and one rattle across the keyboard, the
// soft and middle pedals. Slide the bolt while a chord rings.
val at = Param(0.3f)
val grams = Param(3f)
val rubber = Param(0f)
val rattle = Param(0f)
val gap = Param(0.1f)
val soft = Param(0f)
val sost = Param(0f)
slider("bolt position", 0.0, 1.0, initialValue = 0.3, channel = "at", onUpdate = { at.base = it.toFloat() })
// grams are exponential: the slider runs -1..2, the bolt 0.1 g..100 g
slider(
    "bolt grams (log10)",
    -1.0,
    2.0,
    initialValue = 0.48,
    channel = "grams",
    onUpdate = { grams.base = Math.pow(10.0, it).toFloat() },
)
slider("rubber", 0.0, 100.0, initialValue = 0.0, channel = "rubber", onUpdate = { rubber.base = it.toFloat() })
slider("rattle", 0.0, 4.0, initialValue = 0.0, channel = "rattle", onUpdate = { rattle.base = it.toFloat() })
slider("rattle gap (mm)", 0.0, 3.0, initialValue = 0.1, channel = "gap", onUpdate = { gap.base = it.toFloat() })
slider("una corda", 0.0, 1.0, initialValue = 0.0, channel = "soft", onUpdate = { soft.base = it.toFloat() })
slider("sostenuto", 0.0, 1.0, initialValue = 0.0, channel = "sost", onUpdate = { sost.base = it.toFloat() })
["at", "grams", "rubber", "rattle", "gap", "soft", "sost"].forEach { displayOpen(it) }
val prep = PianoPreparation(21..108, position = at, mass = grams, damping = rubber, rattle = rattle, gap = gap)
val piano = Piano(preparations = listOf(prep), unaCorda = soft, sostenuto = sost)
piano.play(pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]"), cycles = 32, cps = 0.8)
audio(piano, gain = 1f)
