# Field Document: kolakoski_euclid

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 2 - the branching tree

<!-- field-box id="kolakoski_tree" index="0" -->

```edn [field-box id="kolakoski_tree" role="properties"]
{"id" "kolakoski_tree" "index" 0 "name" "2 - the branching tree" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - the branching tree" "_tombstone" false "frame" #afield/rect [60.0 226.0 381.8 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kolakoski_tree_code_.kt" "lines" 90 "characters" 3926 "sha256" "9e041f59cd534018ae2c58dd72c703263463aed144c8d6e7f8b0249432d4da68"}]}
```

```kotlin [field-property box="kolakoski_tree" name="code" path="kolakoski_tree_code_.kt"]
import afield.four.synth.*

// 2 - Kolakoski branches, Euclid places, and the levels are pulse levels.
//
// Every pulse asks the word how many children it has. bjorklund puts those children on the
// parent's own `slots` grid as evenly as it can, and each child runs until the next one - so
// a level always tiles the bar, and a count of 1 is a pulse that simply holds. The level
// sizes are the word's own partial sums: 1, 1, 2, 3, 4, 7, 11, ...
//
// POKE THIS: `slots` is where the Euclid lives - on 3 slots two children split the parent
// 2:1, not down the middle. `a`/`b` is the alphabet: {2,3} on 4 slots never leaves a lone
// hit. `depth` is how far down the tree the music goes.
val depth = 5
val slots = 3
val a = 1
val b = 2

fun tile(depth: Int, slots: Int, a: Int, b: Int): List<List<PatArc>> {
    val word = kolakoskiWord(4 shl depth, a, b)   // more terms than the tree can eat
    var level = listOf(PatArc(PatQ.ZERO, PatQ.ONE))
    var cursor = 0
    val levels = mutableListOf(level)
    repeat(depth) {
        val next = mutableListOf<PatArc>()
        for (parent in level) {
            val children = word[cursor++].coerceAtMost(slots)
            val step = parent.dur / PatQ.of(slots)
            val on = bjorklund(children, slots).withIndex().filter { it.value }.map { it.index }
            on.forEachIndexed { k, slot ->
                val start = parent.start + step * PatQ.of(slot)
                val end = if (k + 1 < on.size) parent.start + step * PatQ.of(on[k + 1]) else parent.end
                next += PatArc(start, end)
            }
        }
        level = next
        levels += level
    }
    return levels
}

fun asPat(arcs: List<PatArc>, name: String = "bd") = fromCycle { sub ->
    arcs.mapNotNull { arc -> arc.intersect(sub)?.let { PatEvent(it, name, whole = arc) } }
}

val t = tile(depth, slots, a, b)

// The word starts on a 1, so R1 is R0 held - the first branch that splits anything is R2.
showPatRollOf(
    *t.mapIndexed { n, level -> "R$n (${level.size})" to asPat(level) }.toTypedArray(),
    title = "each pulse subdivides into ${a} or ${b} on a ${slots}-slot grid", cycles = 1,
    channel = "tree"
)

// The rings as hierarchical pulse levels: one voice per level, so every hit of a fine level
// sits inside exactly one hit of the level above it.
// Coarse loud, fine quiet. Every coarse onset is also a fine onset - that is what a tree means -
// so at equal gain all four voices land together on the strong hits and the hierarchy turns into
// a pile. The gain ramp makes it foreground and background instead. Short samples for the same
// reason: sd:2 and click decay before the next level arrives.
val voices = listOf("bd" to 1.0, "sd:2" to 0.7, "hh:2" to 0.45, "click" to 0.35)
val played = t.takeLast(voices.size)
val music = stack(
    *played.mapIndexed { i, level ->
        asPat(level, voices[i].first) with gain(voices[i].second)
    }.toTypedArray()
)

showPatRollOf(
    *played.mapIndexed { i, level ->
        "${voices[i].first} (${level.size})" to asPat(level, voices[i].first)
    }.toTypedArray(),
    title = "the last four levels, one voice each", cycles = 2, channel = "levels"
)
val dirt = showDirtAudio(music, cycles = 8, cps = 1.0, channel = "audio")   // real samples, not patVoice
val audio = dirt.audio
val wav = "kolakoski-tree-${slots}-${a}${b}-d$depth.wav".file
afield.four.audio.WavFloatArrayWriter.writeStereo16(wav, audio.left, audio.right, PatSoundDocs.SR, 0.9f)
println("wrote $wav — samples: ${dirt.usedSamples}, matched ${dirt.matched}, synth fallback ${dirt.unmatched}")

markdown("main", """
### Level sizes: ${t.joinToString(", ") { it.size.toString() }}

Those are the partial sums of the Kolakoski word - each level eats as many terms as the level
above it has pulses, and the sum of those terms is the next level's size.
""")

displayOpen("tree")
displayOpen("audio")
displayOpen("levels")
displayOpen("main")
```

## 2. 1 - counts and placement

<!-- field-box id="kolakoski_x_euclid" index="1" -->

```edn [field-box id="kolakoski_x_euclid" role="properties"]
{"id" "kolakoski_x_euclid" "index" 1 "name" "1 - counts and placement" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - counts and placement" "_tombstone" false "frame" #afield/rect [60.0 60.0 405.3 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kolakoski_x_euclid_code_.kt" "lines" 58 "characters" 3151 "sha256" "57677757698fda6204df6876d7bc4da389e636a8d5a357332e6d6086614c8a12"}]}
```

```kotlin [field-property box="kolakoski_x_euclid" name="code" path="kolakoski_x_euclid_code_.kt"]
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
```

## 3. 3 - a wav on the canvas

<!-- field-box id="wav_on_canvas" index="2" -->

```edn [field-box id="wav_on_canvas" role="properties"]
{"id" "wav_on_canvas" "index" 2 "name" "3 - a wav on the canvas" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - a wav on the canvas" "_tombstone" false "frame" #afield/rect [60.0 392.0 393.5 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wav_on_canvas_code_.kt" "lines" 37 "characters" 1687 "sha256" "78ce61b67601dcd58933d43c4a6f84f9a52a94623a9e3b9e385dde289ce81c3f"}]}
```

```kotlin [field-property box="wav_on_canvas" name="code" path="wav_on_canvas_code_.kt"]
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
```

