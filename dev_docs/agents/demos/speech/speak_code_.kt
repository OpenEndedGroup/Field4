val said = speak("Hello! I am a box in Field, and this is my voice.")
displayAudioSamples(said.samples, said.sampleRate, label = "F2: ${said.words.size} words, ${"%.0f".format(said.realTimeFactor)}x real time")
print(said.h)
