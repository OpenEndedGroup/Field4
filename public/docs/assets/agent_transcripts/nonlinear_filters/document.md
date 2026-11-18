# Field Document: nonlinear_filters

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - ZdfSvf as an oscillator

<!-- field-box id="nonlinear_svf" index="0" -->

### Narrative

From `code`

A self-oscillating ZdfSvf band-pass: faint noise in, a sine-like tone at the cutoff out.
Its saturating damping holds the oscillation steady; the pitch slider glides over 40 ms.

```edn [field-box id="nonlinear_svf" role="properties"]
{"id" "nonlinear_svf" "index" 0 "name" "2 - ZdfSvf as an oscillator" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - ZdfSvf as an oscillator" "_tombstone" false "frame" #afield/rect [60.0 260.0 520.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nonlinear_svf_code_.kt" "lines" 8 "characters" 563 "sha256" "9efbc8252df028302cde60560b4c77f5e878d815620c9b46bc4a5b5027733dd9"}]}
```

```kotlin [field-property box="nonlinear_svf" name="code" path="nonlinear_svf_code_.kt"]
// A self-oscillating ZdfSvf band-pass: faint noise in, a sine-like tone at the cutoff out.
// Its saturating damping holds the oscillation steady; the pitch slider glides over 40 ms.
val pitch = Param(220f)
val resonance = Param(1.05f)
slider("pitch (Hz)", min = 55.0, max = 1760.0, initialValue = 220.0) { pitch.base = it.toFloat() }
slider("resonance", min = 0.9, max = 1.2, initialValue = 1.05) { resonance.base = it.toFloat() }
val voice = WhiteNoise() * 0.02f / ZdfSvf(SVFMode.BP, pitch.smoothed(0.04f), resonance.smoothed(0.04f))
audio(voice, gain = 0.3f)
```

## 2. 3 - tanh with and without ADAA

<!-- field-box id="nonlinear_adaa" index="1" -->

### Narrative

From `code`

A sine driven into tanh twice, once naive and once with ADAA; the crossfade slider picks
between them. Raise the frequency past 3 kHz and listen for
the inharmonic tones under the naive one.

```edn [field-box id="nonlinear_adaa" role="properties"]
{"id" "nonlinear_adaa" "index" 1 "name" "3 - tanh with and without ADAA" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - tanh with and without ADAA" "_tombstone" false "frame" #afield/rect [60.0 460.0 520.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nonlinear_adaa_code_.kt" "lines" 14 "characters" 681 "sha256" "4532ceeab80fa8ee4a810f1edd683223001cb295902a1edf0d3f4f49735d6dee"}]}
```

```kotlin [field-property box="nonlinear_adaa" name="code" path="nonlinear_adaa_code_.kt"]
/*
A sine driven into tanh twice, once naive and once with ADAA; the crossfade slider picks
between them. Raise the frequency past 3 kHz and listen for
the inharmonic tones under the naive one.
*/
val freq = Param(1000f)
val mix = Param(0f)
slider("frequency (Hz)", min = 100.0, max = 8000.0, initialValue = 1000.0) { freq.base = it.toFloat() }
slider("naive 0 … ADAA 1", min = 0.0, max = 1.0, initialValue = 0.0) { mix.base = it.toFloat() }
val sine = SineOsc(freq.smoothed(0.03f))
val smoothMix = mix.smoothed(0.03f)
val naive = sine / Waveshaper.tanh(6.p)
val adaa = sine / AdaaShaper(AdaaShape.TANH, 6.p)
audio(naive * (Param(1f) - smoothMix) + adaa * smoothMix, gain = 0.15f)
```

## 3. 1 - ZdfLadder with sliders

<!-- field-box id="nonlinear_ladder" index="2" -->

### Narrative

From `code`

A 55 Hz saw through the zero-delay-feedback ladder. The sliders write plain Params;
smoothed() glides each change over 30 ms so dragging a slider never clicks.

```edn [field-box id="nonlinear_ladder" role="properties"]
{"id" "nonlinear_ladder" "index" 2 "name" "1 - ZdfLadder with sliders" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - ZdfLadder with sliders" "_tombstone" false "frame" #afield/rect [60.0 60.0 520.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nonlinear_ladder_code_.kt" "lines" 10 "characters" 687 "sha256" "bbd28d3bbb4777c6c554af9d3b7d0fca192598b50a8d9606e1738ff7a20323f0"}]}
```

```kotlin [field-property box="nonlinear_ladder" name="code" path="nonlinear_ladder_code_.kt"]
// A 55 Hz saw through the zero-delay-feedback ladder. The sliders write plain Params;
// smoothed() glides each change over 30 ms so dragging a slider never clicks.
val cutoff = Param(800f)
val resonance = Param(0.7f)
val drive = Param(1.5f)
slider("cutoff (Hz)", min = 60.0, max = 6000.0, initialValue = 800.0) { cutoff.base = it.toFloat() }
slider("resonance (sings past 1)", min = 0.0, max = 1.2, initialValue = 0.7) { resonance.base = it.toFloat() }
slider("drive", min = 0.2, max = 8.0, initialValue = 1.5) { drive.base = it.toFloat() }
val voice = SawOsc(55.p) * 0.5f / ZdfLadder(cutoff.smoothed(0.03f), resonance.smoothed(0.03f), drive.smoothed(0.03f))
audio(voice, gain = 0.3f)
```

