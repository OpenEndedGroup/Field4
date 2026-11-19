// On the audio engine: the left hand locked and plain, the right hand locked and improvising.
val muse = PatMuse(seed = 3).lock(8, atCycle = 4)
muse.rightLoop!!.improvise = 0.4
val piano = Piano()
piano.play(muse.pattern(), cycles = 32, cps = 0.5, velocity = 1.0)
audio(piano, gain = 1f)
