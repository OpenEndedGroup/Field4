import afield.four.synth.*

// 1 - Kolakoski counts, Euclid places.
//
// The Kolakoski word is the only sequence over {1,2} that is its own run-length encoding.
// Here its terms are read as DENSITIES rather than durations: each one says how many hits
// go into the next group of three slots, and `bd(c,3)` spreads those hits as evenly as the
// group allows. Locally maximally even; globally self-describing.
//
// POKE THIS: the 3 in "[bd($it,3)]" is the group size, and kolakoskiWord(8) is how many
// groups. Try `kolakoskiWord(8, a = 2, b = 3)` with a group of 4 - no group is ever empty
// and none is ever full, so the bar keeps moving without ever locking to the grid.
val counts = kolakoskiWord(8)                                  // 1 2 2 1 1 2 1 2 - sums to 12
val hybrid = pat(counts.joinToString(" ") { "[bd($it,3)]" })   // [bd(1,3)] [bd(2,3)] ...

showPatRollOf(
    "kolakoski x euclid" to hybrid,
    "euclid(12,24)" to struct(euclid(12, 24), pat("bd")),
    "kolakoski(24)" to struct(kolakoski(24), pat("bd")),
    title = "12 onsets in 24 slots, three ways", cycles = 2, channel = "roll"
)
// TEMPO. This bar is 24 slots, not 4, so cps means something very different here than it does
// in `pat("bd sd hh sd")`: a slot lasts 1/(24*cps) seconds and the figure plays 12*cps hits per
// second. At the usual docs tempo of cps = 2 that is 24 kicks a second - a 24Hz pulse train,
// which the ear hears as a low growl rather than as a rhythm. 0.35 puts it at 4.2 hits/s.
val cps = 0.35

// showDirtAudio, not showPatAudio: the same call shape, but it mixes the real Dirt-Samples
// offline instead of patVoice's 62Hz sine. A player in the output AND a real file on disk -
// `"name.wav".file` resolves into this document's own files/ folder.
val dirt = showDirtAudio(hybrid, cycles = 3, cps = cps, channel = "audio")

// The same density, maximally even, at the same tempo - the thing the hybrid is NOT.
showDirtAudio(struct(euclid(12, 24), pat("bd")), cycles = 3, cps = cps, channel = "audio-even")

val audio = dirt.audio
val wav = "kolakoski-flat.wav".file
afield.four.audio.WavFloatArrayWriter.writeStereo16(wav, audio.left, audio.right, PatSoundDocs.SR, 0.9f)
println("wrote $wav — ${"%.1f".format(12 * cps)} hits/s, samples: ${dirt.usedSamples}, matched ${dirt.matched}, synth fallback ${dirt.unmatched}")

markdown("main", """
### The same density, distributed three ways

- **kolakoski x euclid** - the hybrid. Counts from the word, placement from bjorklund.
- **euclid(12,24)** - maximally even, and therefore a flat alternation.
- **kolakoski(24)** - the word read as run lengths instead, the figure Field already had.

`patCondense(hybrid).notation` is `${patCondense(hybrid).notation}` - the language recognising
the Kolakoski in it on its own.
""")

// Two things an output has to get right to be visible at all. A channel that is never opened
// is collapsed - the player is in the DOM at zero size - and a channel holds ONE thing, so a
// markdown() into "main" would have replaced the player had it not been given its own channel.
displayOpen("roll")
displayOpen("audio")
displayOpen("audio-even")
displayOpen("main")
