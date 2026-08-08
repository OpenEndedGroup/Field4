# Field Document: sound_render_path

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["sound_path_roll" "sound_path_stand_in" "sound_path_dirt" "sound_path_fallback" "sound_path_where"] "properties" {"children" #{#afield/box "sound_path_roll" #afield/box "sound_path_stand_in" #afield/box "sound_path_dirt" #afield/box "sound_path_fallback" #afield/box "sound_path_where"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. 3 · The per-event fallback

<!-- field-box id="sound_path_fallback" index="1" -->

### Narrative

From `code`

The second, finer fallback. `bd` and `hh` are in every Dirt install; `zzzznotadrum` is in
none, so THAT EVENT ALONE is voiced by the synth and mixed on top of the sampled ones.
A pattern must not go silent just because the library loaded successfully.

```edn [field-box id="sound_path_fallback" role="properties"]
{"id" "sound_path_fallback" "index" 1 "name" "3 · The per-event fallback" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · The per-event fallback" "_tombstone" false "frame" #afield/rect [60.0 540.0 420.0 130.0] "nested" #{} "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_fallback_code_.kt" "lines" 12 "characters" 625 "sha256" "3158a004267bb94d3f5bb4cd8bc234767e8e1ab1c2997e703b1fe5222f0db684"}]}
```

```kotlin [field-property box="sound_path_fallback" name="code" path="sound_path_fallback_code_.kt"]
// The second, finer fallback. `bd` and `hh` are in every Dirt install; `zzzznotadrum` is in
// none, so THAT EVENT ALONE is voiced by the synth and mixed on top of the sampled ones.
// A pattern must not go silent just because the library loaded successfully.
val mixed = showDirtAudio(
    pat("bd zzzznotadrum bd hh") with gain(0.9),
    cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.SQUARE, baseHz = 330.0, decay = 0.05, sustain = 0.3),
)
println(mixed.describe())
println("matched   = ${mixed.matched}")
println("unmatched = ${mixed.unmatched}")   // <- the tokens that fell back, named
displayOpen("main")
```

## 3. 0 · The schedule

<!-- field-box id="sound_path_roll" index="2" -->

### Narrative

From `code`

Neither voice yet — just the events. Both renders below are handed exactly this list.

```edn [field-box id="sound_path_roll" role="properties"]
{"id" "sound_path_roll" "index" 2 "name" "0 · The schedule" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "0 · The schedule" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 130.0] "nested" #{} "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_roll_code_.kt" "lines" 9 "characters" 299 "sha256" "5ab0a65b98836fcd38b9a6153911a23409de27e2c43349880879a5313c40a605"}]}
```

```kotlin [field-property box="sound_path_roll" name="code" path="sound_path_roll_code_.kt"]
// Neither voice yet — just the events. Both renders below are handed exactly this list.
showPatRollOf(
    "bd ~ sd ~" to pat("bd ~ sd ~"),
    "hh*8" to pat("hh*8"),
    title = "pat(\"bd ~ sd ~, hh*8\")",
    subtitle = "the schedule both voices are given",
    cycles = 2,
)
displayOpen("main")
```

## 4. 1 · The stand-in voice

<!-- field-box id="sound_path_stand_in" index="3" -->

### Narrative

From `code`

patVoice: one oscillator, one one-pole lowpass, one exponential-decay envelope, per
event. `bd` is a 62 Hz sine; `sd` and `hh` are filtered white noise. This is the default
everywhere in Field's docs, and it sounds like bursts of white noise because it is.

```edn [field-box id="sound_path_stand_in" role="properties"]
{"id" "sound_path_stand_in" "index" 3 "name" "1 · The stand-in voice" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · The stand-in voice" "_tombstone" false "frame" #afield/rect [60.0 220.0 420.0 130.0] "nested" #{} "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_stand_in_code_.kt" "lines" 6 "characters" 473 "sha256" "ba862e741aea9081238ea9e78fb74258796de2dc0c5d91a47b8b45521fa6d138"}]}
```

```kotlin [field-property box="sound_path_stand_in" name="code" path="sound_path_stand_in_code_.kt"]
// patVoice: one oscillator, one one-pole lowpass, one exponential-decay envelope, per
// event. `bd` is a 62 Hz sine; `sd` and `hh` are filtered white noise. This is the default
// everywhere in Field's docs, and it sounds like bursts of white noise because it is.
val audio = showPatAudio(pat("bd ~ sd ~, hh*8") with gain(0.9), cycles = 4, cps = 2.0)
println("stand-in: %.2fs, peak %.3f".format(audio.frames / 44100.0, PatSoundDocs.peak(audio.mono)))
displayOpen("main")
```

## 5. 2 · The real Dirt samples

<!-- field-box id="sound_path_dirt" index="4" -->

### Narrative

From `code`

The identical pattern, mixed from the genuine Tidal sample library — offline, with no
audio device. Play box 1 and box 2 back to back: that difference is the whole point.

No library on this machine? You will hear box 1 again, and describe() will say so and
name the override rather than leaving you to guess from the sound.

```edn [field-box id="sound_path_dirt" role="properties"]
{"id" "sound_path_dirt" "index" 4 "name" "2 · The real Dirt samples" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · The real Dirt samples" "_tombstone" false "frame" #afield/rect [60.0 380.0 420.0 130.0] "nested" #{} "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_dirt_code_.kt" "lines" 9 "characters" 522 "sha256" "d04555a21b03b7ff59b20e7acd518201e7213dbae52004b9b732b86d598715a6"}]}
```

```kotlin [field-property box="sound_path_dirt" name="code" path="sound_path_dirt_code_.kt"]
// The identical pattern, mixed from the genuine Tidal sample library — offline, with no
// audio device. Play box 1 and box 2 back to back: that difference is the whole point.
//
// No library on this machine? You will hear box 1 again, and describe() will say so and
// name the override rather than leaving you to guess from the sound.
val played = showDirtAudio(pat("bd ~ sd ~, hh*8") with gain(0.9), cycles = 4, cps = 2.0)
println(played.describe())
println("usedSamples = ${played.usedSamples}")
displayOpen("main")
```

## 6. 4 · Where is the library?

<!-- field-box id="sound_path_where" index="5" -->

### Narrative

From `code`

The probe order, and what it found here. Dirt-Samples ships as a SuperCollider quark, so
anyone who has run Tidal already has it — hence auto-discovery rather than asking you.
~400MB of third-party audio with mixed per-folder licensing, so it is NOT vendored.

```edn [field-box id="sound_path_where" role="properties"]
{"id" "sound_path_where" "index" 5 "name" "4 · Where is the library?" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · Where is the library?" "_tombstone" false "frame" #afield/rect [60.0 700.0 420.0 110.0] "nested" #{} "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_where_code_.kt" "lines" 5 "characters" 421 "sha256" "f337453b6a0e637e0f81939f30d6356c04e6146af314f35ae598cd7c731ce275"}]}
```

```kotlin [field-property box="sound_path_where" name="code" path="sound_path_where_code_.kt"]
// The probe order, and what it found here. Dirt-Samples ships as a SuperCollider quark, so
// anyone who has run Tidal already has it — hence auto-discovery rather than asking you.
// ~400MB of third-party audio with mixed per-folder licensing, so it is NOT vendored.
val root = DirtRender.dirtSampleRoot()
println(if (root != null) "found: $root" else "not found — set ${DirtRender.ENV_VAR} to a copy of Dirt-Samples")
```

