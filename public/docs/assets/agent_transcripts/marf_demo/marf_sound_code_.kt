// MARF as control voltage into a real synth: pat -> Dough -> Steam Audio HRTF, realtime.
// Three readers of one bank become where the sound is, how open its filter is, and how far away.
// Nothing about the bank knows it is being read three times, or heard rather than drawn.
displayClearAll()

val b = marfBank(
    rampTo(0.05, 1.0), rampTo(0.95, 0.5), holdAt(0.45, 2.0),
    oscTo(0.80, 1.0, cycles = 3.0), curveTo(0.15, 0.25, amount = 4.0)
)

// a fractional address, so the source glides rather than stepping between slots
val azR = b.read(saw().slow(8).range(0.0, 4.0), duration = constant(0.25), name = "az")
// reader A's stage shapes, heard as filter movement: the ramp, the step, the osc:3 wiggle
val cutoff = b.read(pat("<0 1 2 1 3 4>").nums(), fields = MarfFieldClock.STAGES, name = "cutoff")
// held addresses, so distance moves in steps and then sits still
val near = b.read(pat("<0 2 4 1 3>").nums().slow(3), fields = MarfFieldClock.STAGES, name = "near")

val voice = pat("bd ~ sd ~, hh*8").with(
    param("az", azR.signal().range(-80.0, 80.0)),
    param("lpf", cutoff.signal().rangex(250.0, 7000.0)),
    param("dist", near.signal().range(1.0, 5.0)),
    gain(0.9)
)

patSpatial(voice, cps = 2.0)
