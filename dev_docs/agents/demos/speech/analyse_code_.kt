// Analyse what was said: the LPC envelope of the stressed vowel in each word.
val said = speak("Speak, analyse, and plot.")
val audio = said.resampled(48_000)
val curves =
    said.words.mapNotNull { w ->
        val v = w.phones.firstOrNull { it.stress == 1 } ?: return@mapNotNull null
        val mid = ((v.start + v.end) / 2 * 48_000).toInt()
        "${w.text} /${v.symbol}/" to
            Lpc.analyze(audio.copyOfRange(mid - 1024, mid + 1024), 52).envelopeDb(1025).take(214).toList()
    }
showSignals(
    *curves.toTypedArray(),
    x = 214 { it * 48_000.0 / 2048 },
    channel = "main",
    xTitle = "frequency (Hz)",
    yTitle = "dB",
)
