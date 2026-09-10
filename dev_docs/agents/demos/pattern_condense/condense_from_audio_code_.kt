import afield.four.synth.*

// 3 - Close the loop: samples in, structure out.
//
// patTranscribe recovers a step grid from audio and writes it out one slot per step, which
// is as far as listening alone can go. patCondense takes that string and returns the figure.
// So this box renders a bar to samples, throws the pattern away, listens to the samples, and
// gets the structure back.
//
// POKE THIS: change `bar`. Anything on an eight-step grid at MIDI 60 will be heard. Try
// "60 ~ ~ 60 60 ~ 60 ~", which comes back as 60(k8), or "60 60 ~ 60 ~ ~ 60 ~", which has no
// single figure and comes back as two halves.
val bar = "60 ~ ~ 60 ~ 60 ~ ~"

val audio = patRenderStereo(
    pat(bar),
    cycles = 1,
    cps = 0.5,
    voice = patVoice(decay = 0.06, sustain = 0.6)
)
val heard = patTranscribe(audio.mono, PatSoundDocs.SR, minHz = 180.0, maxHz = 420.0)
val condensed = patCondense(heard)

showPatRollOf(
    "played" to pat(bar),
    "heard" to pat(heard.miniNotation),
    "condensed: ${condensed.notation}" to pat(condensed.notation),
    title = "audio in, structure out",
    subtitle = "${audio.mono.size} samples at ${PatSoundDocs.SR} Hz, and no knowledge of what made them",
    cycles = 1,
    channel = "roll"
)

// The two players are the claim you can hear. The first is the EXACT buffer patTranscribe was
// given - not a re-render of `bar`, the same floats - so what you hear is what was listened to.
// The second is `condensed.notation` sent back through the same voice. If patCondense is right
// they are the same bar, and the point of a shortest notation is that shortening it cost nothing.
display(
    "played",
    afield.four.audio.WavFloatArrayWriter.encodeStereo16(
        audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f
    )
)
showPatAudio(
    pat(condensed.notation),
    cycles = 1,
    cps = 0.5,
    voice = patVoice(decay = 0.06, sustain = 0.6),
    channel = "condensed"
)

markdown(
    "main",
    """
    ### From samples to a figure

    | | |
    |---|---|
    | played | `$bar` |
    | rendered | ${audio.mono.size} samples, ${"%.2f".format(audio.mono.size.toDouble() / PatSoundDocs.SR)} s |
    | grid recovered | ${heard.grid?.describe() ?: "none"} |
    | heard | `${heard.miniNotation}` |
    | **condensed** | **`${condensed.notation}`** |

    The `heard` row is everything `patTranscribe` can say on its own. The last row is the same
    bar after `patCondense(heard)`, and it names the figure. Nothing between the render and the
    transcription looked at `bar`.

    Four outputs, each on its own channel: **roll** draws the three lanes, **played** is the
    buffer that was actually transcribed, **condensed** is `${condensed.notation}` rendered
    back through the same voice, and this table is **main**. The two players should be the
    same bar — that is the claim, and it is the one part of this box you can check by ear.
    """.trimIndent()
)
