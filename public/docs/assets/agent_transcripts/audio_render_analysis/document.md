# Field Document: audio_render_analysis

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 5 · Sonic Pi recording → analysis

<!-- field-box id="analyse_sonic_pi_recording" index="0" -->

```edn [field-box id="analyse_sonic_pi_recording" role="properties"]
{"id" "analyse_sonic_pi_recording" "index" 0 "name" "5 · Sonic Pi recording → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · Sonic Pi recording → analysis" "_tombstone" false "frame" #afield/rect [40.0 240.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_sonic_pi_recording_code_.kt" "lines" 39 "characters" 1493 "sha256" "4093ef26ca1e0ff5cf692b1a4a4efb9d1bceeba6a7475aec30e8576f17215f83"}]}
```

```kotlin [field-property box="analyse_sonic_pi_recording" name="code" path="analyse_sonic_pi_recording_code_.kt"]
import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val ruby = """
    use_bpm 92
    live_loop :analysis_drums do
      sample :bd_haus, amp: 0.55
      sleep 0.5
      sample :elec_blip2, rate: [0.7, 1.0, 1.4].tick, amp: 0.28
      sleep 0.5
    end
    live_loop :analysis_tone do
      use_synth :hollow
      play (scale :e3, :minor_pentatonic).tick, release: 0.7, amp: 0.35
      sleep 1
    end
""".trimIndent()

recordSonicPiWav(
    sonicPi(),
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "sonic-pi-study.wav"),
    ruby, seconds = 18.0, workspace = "field_audio_analysis",
    provenance = mapOf("field_box" to "analyse_sonic_pi_recording", "bpm" to "92"),
).thenCompose { render ->
    display("render", "recorded ${render.path}")
    client.analyzeRenderedAudio(
        render,
        requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/sonic-pi") },
        onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
        onProgress = { named, accepted, event -> display("${named.label} job", fieldAudioJobStatusHtml(accepted, event)) },
    )
}.thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

## 2. 1 · render → analyse guide and jobs

<!-- field-box id="render_analysis_guide" index="1" -->

```edn [field-box id="render_analysis_guide" role="properties"]
{"id" "render_analysis_guide" "index" 1 "name" "1 · render → analyse guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · render → analyse guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "render_analysis_guide_code_.kt" "lines" 11 "characters" 1222 "sha256" "61db32d0b88582f74f3b0dd25303afd4dda197795ff042af98dfe91723d13ca4"}]}
```

```kotlin [field-property box="render_analysis_guide" name="code" path="render_analysis_guide_code_.kt"]
import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Field synthesis → Field Audio analysis</h2>
  <p>Each box below contains the whole loop: make sound, write WAV, import immutable bytes, submit named jobs, stream progress, await results, and draw a synchronized waveform/beat/pitch board. Re-run unchanged code and the content-addressed import plus operation cache return the same results.</p>
  <p><b>Pat, Dirt and Dough</b> render offline. <b>Faust</b> compiles and renders on a daemon future because its first compiler warm-up is slow. <b>Sonic Pi</b> records in real time on that same background executor. None blocks Field's run loop.</p>
  <p>The local <code>RenderedAudioFile.provenance</code> records the producing box and render parameters. The server asset id remains a hash of the WAV bytes; the Field document remains the authority for source code and arrangement.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 390))
```

## 3. 4 · Faust render → analysis

<!-- field-box id="analyse_faust_render" index="2" -->

```edn [field-box id="analyse_faust_render" role="properties"]
{"id" "analyse_faust_render" "index" 2 "name" "4 · Faust render → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · Faust render → analysis" "_tombstone" false "frame" #afield/rect [420.0 140.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_faust_render_code_.kt" "lines" 30 "characters" 1348 "sha256" "0a496010e303e9f776d983418e8f6235e6253a1f4cfc58bb5b91db20e7a5a26e"}]}
```

```kotlin [field-property box="analyse_faust_render" name="code" path="analyse_faust_render_code_.kt"]
import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val faustSource = """
    import("stdfaust.lib");
    sweep = 240 + 170 * os.osc(0.11);
    voice = os.sawtooth(sweep) : fi.lowpass(3, 900 + 650 * os.osc(0.07));
    process = voice * 0.18 <: _,_;
""".trimIndent()

renderFaustWavAsync(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "faust-sweep-study.wav"),
    name = "faust-sweep-study", source = faustSource, seconds = 18.0,
    provenance = mapOf("field_box" to "analyse_faust_render"),
).thenCompose { render ->
    display("render", "rendered ${render.path} · ${render.sampleRate} Hz stereo")
    client.analyzeRenderedAudio(
        render,
        requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/faust") },
        onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
        onProgress = { named, accepted, event -> display("${named.label} job", fieldAudioJobStatusHtml(accepted, event)) },
    )
}.thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

## 4. 3 · Dough render → analysis

<!-- field-box id="analyse_dough_render" index="3" -->

```edn [field-box id="analyse_dough_render" role="properties"]
{"id" "analyse_dough_render" "index" 3 "name" "3 · Dough render → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · Dough render → analysis" "_tombstone" false "frame" #afield/rect [40.0 140.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_dough_render_code_.kt" "lines" 28 "characters" 1268 "sha256" "6a76e598c45daa450497e12432dc17c4a56343e2b491c1304ae8d9331a367972"}]}
```

```kotlin [field-property box="analyse_dough_render" name="code" path="analyse_dough_render_code_.kt"]
import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val source = ZawOsc(Param(110f)) * 0.35f
val voice = source.through(Phaser(
    rate = Param(0.23f), depth = Param(0.78f),
    center = Param(540f), sweep = Param(1800f), mix = Param(0.92f),
))
val render = renderDoughWav(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "dough-phaser-study.wav"),
    voice, seconds = 18.0,
    provenance = mapOf("field_box" to "analyse_dough_render", "voice" to "ZawOsc → Phaser"),
)
display("render", "rendered ${render.path} · ${render.sampleRate} Hz mono")

client.analyzeRenderedAudio(
    render,
    requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/dough") },
    onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
    onProgress = { named, accepted, event -> display("${named.label} job", fieldAudioJobStatusHtml(accepted, event)) },
).thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

## 5. 2 · Pat / Dirt render → analysis

<!-- field-box id="analyse_pat_render" index="4" -->

```edn [field-box id="analyse_pat_render" role="properties"]
{"id" "analyse_pat_render" "index" 4 "name" "2 · Pat / Dirt render → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · Pat / Dirt render → analysis" "_tombstone" false "frame" #afield/rect [420.0 40.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_pat_render_code_.kt" "lines" 31 "characters" 1350 "sha256" "c2be874cc48e91a10ea41da085eae36266998ade23509773f2eb4038917bf9b7"}]}
```

```kotlin [field-property box="analyse_pat_render" name="code" path="analyse_pat_render_code_.kt"]
import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val pattern = (pat("{60 64 67 71, bd ~ sd ~, hh*8}")
    with gain(sine().range(0.22, 0.72))
    with pan("0.2 0.8"))
val render = renderDirtWav(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "pat-dirt-study.wav"),
    pattern, cycles = 12, cps = 1.6,
    provenance = mapOf("field_box" to "analyse_pat_render", "pattern" to "pitched chord + drums"),
)
display("render", "rendered ${render.path} · ${"%.2f".format(render.durationSeconds)} s")

client.analyzeRenderedAudio(
    render,
    requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/pat") },
    onAccepted = { named, accepted ->
        display("${named.label} job", fieldAudioJobStatusHtml(accepted))
    },
    onProgress = { named, accepted, event ->
        display("${named.label} job", fieldAudioJobStatusHtml(accepted, event))
    },
    onProgressError = { named, error -> display("${named.label} stream", error.message ?: error.toString()) },
).thenAccept { completed ->
    display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

