import afield.math.distribution.*

/*
LAUNCH me (sound on). The weighted pentatonic from box 6, as frequencies, picks the pitch of a sine
at every rising edge of an 8 Hz pulse; the same pulse gates the envelope. The view under `heard`
is the pitches you are hearing, settling onto the scale's exact probabilities. Change the weights
or the clock and re-launch.
*/
val scale = categorical(listOf(0, 3, 5, 7, 10, 12), listOf(4, 1, 2, 3, 1, 2))
val hz = 220 * (scale * (kotlin.math.ln(2.0) / 12)).exp()

val clock = PulseOsc(freq = Param(8f), width = Param(0.35f))
val pitch = hz.param(trigger = clock)
val env =
    ADSR(gate = clock, attack = Param(0.004f), decay = Param(0.06f), sustain = Param(0.35f), release = Param(0.08f))
val voice = SineOsc(freq = pitch) * env * 0.5f

audio(voice) +
    loop {
        __m("heard").distribution(pitch, title = "heard")
        frame()
    }
