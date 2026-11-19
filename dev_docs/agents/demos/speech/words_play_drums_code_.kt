// Stressed words strike a kick, the rest a hat, exactly on the words.
val said = speak("Speech can play the drums, if you let it.")
val drums = said.pattern { if (it.stressed) "bd" else "hh" }
val beat = patRenderStereo(drums, cycles = 1, cps = 1 / said.seconds, sampleRate = 48_000).mono
val voice = said.resampled(48_000)
val mix = (voice.size) { i -> 0.8f * voice[i] + 0.5f * beat.getOrElse(i) { 0f } }.toFloatArray()
displayAudioSamples(mix, 48_000, label = "words as a pattern: ${drums.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value }}")
