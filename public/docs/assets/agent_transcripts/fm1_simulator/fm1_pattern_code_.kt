/*
A Field pattern on ACID ALLEY's DX7 track (track 2), one cycle to the project's bar, while a
slow sine turns the 303's filter on track 0. Launch it to hear it; re-run with another
pattern and it replaces this one.
*/
val fm1 = FM1Sim.shared
fm1.preset(1)
fm1.start()
fm1.play(pat("<c5 ef5> ~ [g5 bf5] ~ c6 ~ g5 ~"), track = 2)
fm1.modulate(0, FM1Sim.CC.CUTOFF, SineOsc(0.25.p) * 0.5 + 0.5)

fm1.node
