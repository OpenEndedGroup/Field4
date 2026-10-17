# Field Document: pattern_condense

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 - Close the loop

<!-- field-box id="condense_from_audio" index="0" -->

```edn [field-box id="condense_from_audio" role="properties"]
{"id" "condense_from_audio" "index" 0 "name" "3 - Close the loop" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Close the loop" "_tombstone" false "frame" #afield/rect [60.0 940.0 400.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "condense_from_audio_code_.kt" "lines" 74 "characters" 2932 "sha256" "7dd72058b3208bd5c869fe2ff440a81c103cdec8dd6fb5b4f9d6196d2d0b580c"}]}
```

```kotlin [field-property box="condense_from_audio" name="code" path="condense_from_audio_code_.kt"]
import afield.audio.synth.*

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
    afield.audio.engine.WavFloatArrayWriter.encodeStereo16(
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
```

## 2. 1 - Condense a rhythm

<!-- field-box id="condense_basics" index="1" -->

```edn [field-box id="condense_basics" role="properties"]
{"id" "condense_basics" "index" 1 "name" "1 - Condense a rhythm" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Condense a rhythm" "_tombstone" false "frame" #afield/rect [60.0 60.0 400.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "condense_basics_code_.kt" "lines" 46 "characters" 1937 "sha256" "ca5cbd8cb575e265504c07c5ea6ff805dfc3ff7cd6673c807f15227f7e18cd9b"}]}
```

```kotlin [field-property box="condense_basics" name="code" path="condense_basics_code_.kt"]
import afield.audio.synth.*

// 1 - Condense a rhythm.
//
// patCondense searches Field's own mini-notation for the shortest string that plays the
// rhythm you hand it, and it accepts a candidate only by playing it: every one goes back
// through pat(...) and its events are compared to the target's. So the lower lane of each
// pair below IS the string in its label, rendered. If a pair does not coincide, the search
// is wrong, and that is a thing to see rather than a thing to be told.
//
// POKE THIS: change a line in `rhythms`, or add one. A mask you invent comes back either as
// a named figure - x(p,s) for Euclidean, x(kN) / x(gN) for Kolakoski - or spelled out, and
// which one you get is decided by character count and nothing else.
val rhythms = listOf(
    "x ~ ~ x ~ x ~ ~",
    "x ~ ~ x x ~ x ~ ~ x ~ ~ x x ~ x",
    "bd [hh hh] sd [cp cp]",
    "a b c d e c a d"
)

val found = showPatCondense(*rhythms.toTypedArray(), channel = "roll")

val rows = rhythms.zip(found).joinToString("\n") { (source, c) ->
    "| `$source` | `${c.notation}` | ${c.notation.length}/${c.flat.length} | " +
        "${"%.2f".format(c.compression)} | ${c.structure.joinToString(", ")} |"
}

markdown(
    "main",
    """
    ### ${found.count { it.exact }} of ${found.size} condensed exactly

    | given | shortest string that plays it | chars | ratio | structure |
    |---|---|---|---|---|
    $rows

    The last row is why the other three mean anything. `a b c d e c a d` has no repeat at any
    split the grammar can take, so nothing shortens it and it comes back as itself. A search
    scored on description length has to be shown *not* naming a figure as well as naming one.

    Bounds this run used: ${found.first().limits}

    The figure is on the **roll** channel beside this one. These rhythms are abstract — `x`,
    `bd`, `a` — so there is nothing to play here; box 3 is the one you can listen to.
    """.trimIndent()
)
```

## 3. 2 - Invent nothing

<!-- field-box id="condense_invents_nothing" index="2" -->

```edn [field-box id="condense_invents_nothing" role="properties"]
{"id" "condense_invents_nothing" "index" 2 "name" "2 - Invent nothing" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Invent nothing" "_tombstone" false "frame" #afield/rect [60.0 500.0 400.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "condense_invents_nothing_code_.kt" "lines" 69 "characters" 2690 "sha256" "62196e309a0c4d896666e250c39c2026ce01e2abbb5462b7f130a88fd7e2c22a"}]}
```

```kotlin [field-property box="condense_invents_nothing" name="code" path="condense_invents_nothing_code_.kt"]
import afield.audio.synth.*
import kotlin.random.Random

// 2 - Invent nothing.
//
// The failure mode of a description-length search is naming a figure that is one step away
// from the rhythm in front of it. This box runs that check live, on masks with no structure
// put into them.
//
// POKE THIS: change `seed`, or `density` (one step in N is on), or `trials`. The count of
// wrong namings has to stay 0 whatever you set, because every mask that came back as a
// whole-span figure is recomputed here from bjorklund / kolakoskiMask and compared.
val seed = 20260910
val density = 3
val trials = 300

val random = Random(seed)
val euclidean = Regex("""^x\((\d+),(\d+)(?:,(\d+))?\)$""")
val kolakoskian = Regex("""^x\(([kg])(\d+)(?:,(\d+))?\)$""")

var named = 0
var wrong = 0
val ratios = mutableListOf<Double>()

repeat(trials) {
    val mask = List(16) { random.nextInt(density) == 0 }
    val condensed = patCondense(mask)
    ratios += condensed.compression
    euclidean.matchEntire(condensed.notation)?.let { m ->
        named++
        val pulses = m.groupValues[1].toInt()
        val steps = m.groupValues[2].toInt()
        val offset = m.groupValues[3].ifEmpty { "0" }.toInt()
        val figure = List(steps) { i -> bjorklund(pulses, steps)[((i + offset) % steps + steps) % steps] }
        if (figure != mask) wrong++
    }
    kolakoskian.matchEntire(condensed.notation)?.let { m ->
        named++
        val reading = if (m.groupValues[1] == "k") KolakoskiReading.RUNS else KolakoskiReading.GAPS
        val steps = m.groupValues[2].toInt()
        val offset = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (kolakoskiMask(steps, reading, rotation = -offset) != mask) wrong++
    }
}

val median = ratios.sorted()[ratios.size / 2]
val structured = patCondense(kolakoskiMask(16)).compression

markdown(
    "main",
    """
    ### $trials random 16-step masks, seed $seed, one step in $density on

    | | |
    |---|---|
    | read as a single whole-span figure | **$named** |
    | of those, wrong | **$wrong** |
    | median compression | ${"%.3f".format(median)} |
    | the same number for `kolakoskiMask(16)` | ${"%.3f".format(structured)} |

    Noise compresses about ${"%.1f".format(median / structured)} times worse than the figure
    it is up against. That separation is the claim. If random masks compressed as well as
    structured ones, the search would be finding figures in nothing.

    None of this rests on the search's own opinion of itself: every string it returned was
    re-parsed and re-played before it was accepted, and the namings above are recomputed from
    the generators rather than read off the label.
    """.trimIndent()
)
```

