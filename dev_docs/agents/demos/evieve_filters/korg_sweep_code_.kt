// A 55 Hz saw through Korg35. The cutoff is a Param whose mod is a Signal, so it moves per sample:
// a slow 0.5 Hz sweep plus a 110 Hz wobble that puts sidebands on the resonance.
val cutoff = Param(1200f).also { it.mod = SineOsc(0.5.p) * 900f + SineOsc(110.p) * 250f }
val voice = SawOsc(55.p) / Korg35(cutoff, 0.9.p, drive = 2.p)
audio(voice, gain = 0.15f)
