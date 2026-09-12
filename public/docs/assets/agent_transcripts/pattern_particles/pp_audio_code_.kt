import afield.four.synth.BandSplit
import afield.four.synth.PatSynthLaunch
import afield.four.synth.PatWave
import afield.four.synth.audioEngine
import afield.four.synth.logBands
import afield.four.synth.pat
import afield.four.synth.patSynth
import afield.four.synth.patVoice
import afield.four.synth.snapshot
import afield.four.synth.stack

// The numbers the compute box reads. An inherited property rather than a global, so the
// box graph is what says which shader is listening to which pattern.
prop<DoubleArray>(bandEnergy)
prop<PatSynthLaunch>(patternSynth)

bandEnergy = DoubleArray(4)

// A kick on the beat, a mid-register square stab on the offbeats, and sixteen hats a
// cycle: three parts in three parts of the spectrum, so four bands have something to tell
// apart. This is `PatSynthDocs`' seam-bands scene.
patternSynth = patSynth(
    stack(pat("bd*4"), pat("~ 52 ~ 52"), pat("hh*16")),
    cps = 2.0,
    voice = patVoice(wave = PatWave.SQUARE, decay = 0.012, sustain = 0.08),
)

// Four band-passes, log-spaced. Field's own Biquad, owned by this box rather than by the
// synth graph, reading a snapshot the audio path published.
val split = logBands(4, lowHz = 70.0, highHz = 8000.0, sampleRate = patternSynth.bank.sampleRate)

// This box pulls the bank itself, so the analysis has samples whether or not the machine
// has an output device. To hear it, return `patternSynth` from the box instead and let the
// engine pull; do not do both, or the voices advance twice and the bar plays double speed.
println("output device present: ${audioEngine().outputActive}; this box is pulling the bank")
val perFrame = patternSynth.bank.sampleRate / 60

frames {
    while (frame()) {
        patternSynth.advanceOffline(perFrame)
        bandEnergy = split.measure(patternSynth.tap.snapshot())
    }
}
