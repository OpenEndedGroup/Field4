import afield.four.synth.*

// 3 - A player that lives on the canvas, not in an output.
//
// showPatAudio puts an <audio> in the box's OUTPUT, which is recomputed every run and is not
// saved with the document. `customization` is a box property - JS run against the box's own
// element on the canvas, and it IS saved - so an audio element appended there is part of the
// document: reopen it tomorrow, press play, no run needed.
//
// POKE THIS: change the pattern. Keep the clip short - it travels as base64 inside the document,
// so ~4s of stereo is ~700KB of property. cps is low because the bar is 24 slots wide: see the
// tempo note in kolakoski_x_euclid.
val counts = kolakoskiWord(8)
val hybrid = pat(counts.joinToString(" ") { "[bd($it,3)]" })

val dirt = patRenderStereoDirt(hybrid, cycles = 2, cps = 0.35)  // 5.7s; 24-slot bar, so 4.2 hits/s
val rendered = dirt.audio
val bytes = afield.four.audio.WavFloatArrayWriter.encodeStereo16(
    rendered.left, rendered.right, PatSoundDocs.SR, 0.9f
)

// The file on disk, for dragging into an editor...
val wav = "kolakoski-canvas.wav".file
wav.writeBytes(bytes)

// ...and the same bytes pinned into this box on the canvas.
val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
customization = """
    this.style.background = "var(--field-bg)"
    if (!this.querySelector("audio.field-wav")) {
        this.insertAdjacentHTML("beforeend",
            "<audio class='field-wav' controls style='width:100%'></audio>")
    }
    this.querySelector("audio.field-wav").src = "data:audio/wav;base64,$b64"
"""

println("${bytes.size / 1024}KB of wav, on the canvas and at $wav — samples: ${dirt.usedSamples} ${dirt.matched}")
