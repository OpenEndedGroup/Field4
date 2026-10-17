# Field Document: sound_render_path

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 · The per-event fallback

<!-- field-box id="sound_path_fallback" index="0" -->

### Narrative

From `code`

The second, finer fallback. `bd` and `hh` are in every Dirt install; `zzzznotadrum` is in
none, so THAT EVENT ALONE is voiced by the synth and mixed on top of the sampled ones.
A pattern must not go silent just because the library loaded successfully.

```edn [field-box id="sound_path_fallback" role="properties"]
{"id" "sound_path_fallback" "index" 0 "name" "3 · The per-event fallback" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · The per-event fallback" "_tombstone" false "frame" #afield/rect [60.0 540.0 420.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_fallback_code_.kt" "lines" 15 "characters" 624 "sha256" "7d9ead7f64a18362cdfd50e92bcbb273cf14194723fa8039eae4e0ab032659a3"}]}
```

```kotlin [field-property box="sound_path_fallback" name="code" path="sound_path_fallback_code_.kt"]
/*
The second, finer fallback. `bd` and `hh` are in every Dirt install; `zzzznotadrum` is in
none, so THAT EVENT ALONE is voiced by the synth and mixed on top of the sampled ones.
A pattern must not go silent just because the library loaded successfully.
*/
val mixed = showDirtAudio(
    pat("bd zzzznotadrum bd hh") with gain(0.9),
    cycles = 4,
    cps = 2.0,
    voice = patVoice(wave = PatWave.SQUARE, baseHz = 330.0, decay = 0.05, sustain = 0.3),
)
println(mixed.describe())
println("matched   = ${mixed.matched}")
println("unmatched = ${mixed.unmatched}") // <- the tokens that fell back, named
displayOpen("main")
```

## 2. 0 · The schedule

<!-- field-box id="sound_path_roll" index="1" -->

### Narrative

From `code`

Neither voice yet — just the events. Both renders below are handed exactly this list.

```edn [field-box id="sound_path_roll" role="properties"]
{"id" "sound_path_roll" "index" 1 "name" "0 · The schedule" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "0 · The schedule" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_roll_code_.kt" "lines" 9 "characters" 299 "sha256" "5ab0a65b98836fcd38b9a6153911a23409de27e2c43349880879a5313c40a605"}]}
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

## 3. 1 · The stand-in voice

<!-- field-box id="sound_path_stand_in" index="2" -->

### Narrative

From `code`

patVoice: one oscillator, one one-pole lowpass, one exponential-decay envelope, per
event. `bd` is a 62 Hz sine; `sd` and `hh` are filtered white noise. This is the default
everywhere in Field's docs, and it sounds like bursts of white noise because it is.

```edn [field-box id="sound_path_stand_in" role="properties"]
{"id" "sound_path_stand_in" "index" 2 "name" "1 · The stand-in voice" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · The stand-in voice" "_tombstone" false "frame" #afield/rect [60.0 220.0 420.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_stand_in_code_.kt" "lines" 8 "characters" 470 "sha256" "5b0301191529de12194dafc9d271431ffc9ca7b0782245379ae75098147b89c8"}]}
```

```kotlin [field-property box="sound_path_stand_in" name="code" path="sound_path_stand_in_code_.kt"]
/*
patVoice: one oscillator, one one-pole lowpass, one exponential-decay envelope, per
event. `bd` is a 62 Hz sine; `sd` and `hh` are filtered white noise. This is the default
everywhere in Field's docs, and it sounds like bursts of white noise because it is.
*/
val audio = showPatAudio(pat("bd ~ sd ~, hh*8") with gain(0.9), cycles = 4, cps = 2.0)
println("stand-in: %.2fs, peak %.3f".format(audio.frames / 44100.0, PatSoundDocs.peak(audio.mono)))
displayOpen("main")
```

## 4. 2 · The real Dirt samples

<!-- field-box id="sound_path_dirt" index="3" -->

### Narrative

From `code`

The identical pattern, mixed from the genuine Tidal sample library — offline, with no
audio device. Compare the synth and Dirt audio boxes to hear the two render paths.

No library on this machine? The synth fallback plays, and describe() will say so and
name the override rather than leaving you to guess from the sound.

```edn [field-box id="sound_path_dirt" role="properties"]
{"id" "sound_path_dirt" "index" 3 "name" "2 · The real Dirt samples" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · The real Dirt samples" "_tombstone" false "frame" #afield/rect [60.0 380.0 420.0 130.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_dirt_code_.kt" "lines" 11 "characters" 511 "sha256" "1bf9bac2c0c74c8941c52aa6b7a509e18906ccdb03c3feca03ae736afc450d4b"}]}
```

```kotlin [field-property box="sound_path_dirt" name="code" path="sound_path_dirt_code_.kt"]
/*
The identical pattern, mixed from the genuine Tidal sample library — offline, with no
audio device. Compare the synth and Dirt audio boxes to hear the two render paths.

No library on this machine? The synth fallback plays, and describe() will say so and
name the override rather than leaving you to guess from the sound.
*/
val played = showDirtAudio(pat("bd ~ sd ~, hh*8") with gain(0.9), cycles = 4, cps = 2.0)
println(played.describe())
println("usedSamples = ${played.usedSamples}")
displayOpen("main")
```

## 5. 4 · Where is the library?

<!-- field-box id="sound_path_where" index="4" -->

### Narrative

From `code`

The probe order, and what it found here. Dirt-Samples ships as a SuperCollider quark, so
anyone who has run Tidal already has it — hence auto-discovery rather than asking you.
~400MB of third-party audio with mixed per-folder licensing, so it is NOT vendored.

```edn [field-box id="sound_path_where" role="properties"]
{"id" "sound_path_where" "index" 4 "name" "4 · Where is the library?" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · Where is the library?" "_tombstone" false "frame" #afield/rect [60.0 700.0 420.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_path_where_code_.kt" "lines" 7 "characters" 418 "sha256" "99864b2c034d7ec1324ec5ca71f04aa30ec6f01bcea3f0fc77427f5adb674970"}]}
```

```kotlin [field-property box="sound_path_where" name="code" path="sound_path_where_code_.kt"]
/*
The probe order, and what it found here. Dirt-Samples ships as a SuperCollider quark, so
anyone who has run Tidal already has it — hence auto-discovery rather than asking you.
~400MB of third-party audio with mixed per-folder licensing, so it is NOT vendored.
*/
val root = DirtRender.dirtSampleRoot()
println(if (root != null) "found: $root" else "not found — set ${DirtRender.ENV_VAR} to a copy of Dirt-Samples")
```

