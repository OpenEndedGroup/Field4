// Launch "fx" first. A Field sawtooth goes into its a-rate channel "in"; the reverb's
// left channel, chnset into "wet", comes back as a Field signal that Field plays.
val fx = csoundOf("fx")
val saw = SawOsc(Param(110f)) * Param(0.15f)
fx.node.connect("in", saw)
fx.node.audioOut("wet").pan(-0.5f)
