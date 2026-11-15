# Field Document: evieve_filters

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - Korg35 swept at audio rate

<!-- field-box id="evieve_korg_sweep" index="0" -->

### Narrative

From `code`

A 55 Hz saw through Korg35. The cutoff is a Param whose mod is a Signal, so it moves per sample:
a slow 0.5 Hz sweep plus a 110 Hz wobble that puts sidebands on the resonance.

```edn [field-box id="evieve_korg_sweep" role="properties"]
{"id" "evieve_korg_sweep" "index" 0 "name" "2 - Korg35 swept at audio rate" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Korg35 swept at audio rate" "_tombstone" false "frame" #afield/rect [60.0 260.0 520.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "evieve_korg_sweep_code_.kt" "lines" 5 "characters" 361 "sha256" "204b7e76721ac915a2694afe1dca169ab51628ad1170c532de09d16f8f1e3a36"}]}
```

```kotlin [field-property box="evieve_korg_sweep" name="code" path="evieve_korg_sweep_code_.kt"]
// A 55 Hz saw through Korg35. The cutoff is a Param whose mod is a Signal, so it moves per sample:
// a slow 0.5 Hz sweep plus a 110 Hz wobble that puts sidebands on the resonance.
val cutoff = Param(1200f).also { it.mod = SineOsc(0.5.p) * 900f + SineOsc(110.p) * 250f }
val voice = SawOsc(55.p) / Korg35(cutoff, 0.9.p, drive = 2.p)
audio(voice, gain = 0.15f)
```

## 2. 1 - Korg35 measured response

<!-- field-box id="evieve_korg_response" index="1" -->

### Narrative

From `code`

The Korg35 (MS-20) lowpass at 1 kHz, measured: one second of impulse response, then an FFT, so
bin k is k Hz. The tiny amplitude keeps its tanh linear, so this is the small-signal response.

```edn [field-box id="evieve_korg_response" role="properties"]
{"id" "evieve_korg_response" "index" 1 "name" "1 - Korg35 measured response" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Korg35 measured response" "_tombstone" false "frame" #afield/rect [60.0 60.0 520.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "evieve_korg_response_code_.kt" "lines" 13 "characters" 658 "sha256" "e5de59db5dfd336180206e200c0bed81f50d566eff5980878f59b370ff2e1d7e"}]}
```

```kotlin [field-property box="evieve_korg_response" name="code" path="evieve_korg_response_code_.kt"]
// The Korg35 (MS-20) lowpass at 1 kHz, measured: one second of impulse response, then an FFT, so
// bin k is k Hz. The tiny amplitude keeps its tanh linear, so this is the small-signal response.
fun responseDb(filter: SignalProcessor) =
    fft(impulseResponse(filter, 48_000, amplitude = 1e-4f), 48_000.0).magnitudeDbOneSided().take(5000)

showSignals(
    "resonance 0.1" to responseDb(Korg35(1000.p, 0.1.p)),
    "resonance 0.5" to responseDb(Korg35(1000.p, 0.5.p)),
    "resonance 0.95" to responseDb(Korg35(1000.p, 0.95.p)),
    "highpass 0.8" to responseDb(Korg35(1000.p, 0.8.p, Korg35Mode.HP)),
    channel = "main",
    xTitle = "frequency (Hz)",
)
```

## 3. 3 - Resonators: response and sound

<!-- field-box id="evieve_resonator" index="2" -->

### Narrative

From `code`

Three Resonators on white noise, each frequency drifting on its own slow sine,
with the 1 kHz resonator's response at three decays plotted beside it.

```edn [field-box id="evieve_resonator" role="properties"]
{"id" "evieve_resonator" "index" 2 "name" "3 - Resonators: response and sound" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Resonators: response and sound" "_tombstone" false "frame" #afield/rect [60.0 460.0 520.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "evieve_resonator_code_.kt" "lines" 16 "characters" 819 "sha256" "be6c87d32da30eca3aa28b7d7d0438464aa977f8b1f5bf236382e897519dee67"}]}
```

```kotlin [field-property box="evieve_resonator" name="code" path="evieve_resonator_code_.kt"]
// Three Resonators on white noise, each frequency drifting on its own slow sine,
// with the 1 kHz resonator's response at three decays plotted beside it.
fun responseDb(filter: SignalProcessor) =
    fft(impulseResponse(filter, 48_000), 48_000.0).magnitudeDbOneSided().take(5000)

showSignals(
    "decay 0.02 s" to responseDb(Resonator(1000.p, 0.02.p)),
    "decay 0.1 s" to responseDb(Resonator(1000.p, 0.1.p)),
    "decay 0.5 s" to responseDb(Resonator(1000.p, 0.5.p)),
    channel = "main",
    xTitle = "frequency (Hz)",
)
val noise = WhiteNoise() * 0.3f
fun band(f: Float, drift: Float, rate: Float, decay: Float) =
    noise / Resonator(Param(f).also { it.mod = SineOsc(rate.p) * drift }, decay.p)
audio(band(300f, 80f, 0.2f, 0.6f) + band(750f, 150f, 0.45f, 0.4f) + band(2100f, 300f, 0.7f, 0.3f), gain = 0.5f)
```

