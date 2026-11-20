// The speech's envelope on a pulse whose pitch you choose: a sung contour.
val said = speak("I can sing whatever pitch you give me.", voice = "M2")
val pitch = Param(110f).also { it.mod = SineOsc(0.5.p) * 30f }
said.sungOn(Impulse(pitch)) * 0.5f
