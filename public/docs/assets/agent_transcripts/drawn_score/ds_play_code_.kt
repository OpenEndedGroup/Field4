// The whole score is one cycle, so 0.25 cycles a second is 120 beats a minute.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
showPatAudio(score.toPattern(), cycles = 2, cps = 0.25, voice = DrawnScoreDocs.VOICE, channel = "audio")
