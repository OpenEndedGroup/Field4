# Field Document: audio_render_analysis

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 5 · Sonic Pi recording → analysis

<!-- field-box id="analyse_sonic_pi_recording" index="0" -->

```edn [field-box id="analyse_sonic_pi_recording" role="properties"]
{"id" "analyse_sonic_pi_recording" "index" 0 "name" "5 · Sonic Pi recording → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · Sonic Pi recording → analysis" "_tombstone" false "frame" #afield/rect [40.0 340.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_sonic_pi_recording_code_.kt" "lines" 46 "characters" 1574 "sha256" "d694652965e6704f45359963d52e8c2c22c1facd526ece6fadeef9b4b4699322"}]}
```

```kotlin [field-property box="analyse_sonic_pi_recording" name="code" path="analyse_sonic_pi_recording_code_.kt"]
import java.nio.file.Path

val client = FieldAudioClient()
val ruby =
    """
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
"""
        .trimIndent()

recordSonicPiWav(
        sonicPi(),
        Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "sonic-pi-study.wav"),
        ruby,
        seconds = 18.0,
        workspace = "field_audio_analysis",
        provenance = mapOf("field_box" to "analyse_sonic_pi_recording", "bpm" to "92"),
    )
    .thenCompose { render ->
        display("render", "recorded ${render.path}")
        client.analyzeRenderedAudio(
            render,
            requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/sonic-pi") },
            onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
            onProgress = { named, accepted, event ->
                display("${named.label} job", fieldAudioJobStatusHtml(accepted, event))
            },
        )
    }
    .thenAccept { completed ->
        display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
    }
    .exceptionally { error ->
        display("analysis error", error.cause?.message ?: error.message ?: error.toString())
        null
    }
```

## 2. 1 · render → analyse guide and jobs

<!-- field-box id="render_analysis_guide" index="1" -->

```edn [field-box id="render_analysis_guide" role="properties"]
{"id" "render_analysis_guide" "index" 1 "name" "1 · render → analyse guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · render → analyse guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "render_analysis_guide_code_.kt" "lines" 13 "characters" 1205 "sha256" "b2b3b40d579d771018f5f78ac2e4e56742afe416e2ae81fdf2d869c0e354bde7"}]}
```

```kotlin [field-property box="render_analysis_guide" name="code" path="render_analysis_guide_code_.kt"]
display(
    "guide",
    """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Field synthesis → Field Audio analysis</h2>
  <p>Each box below contains the whole loop: make sound, write WAV, import immutable bytes, submit named jobs, stream progress, await results, and draw a synchronized waveform/beat/pitch board. Re-run unchanged code and the content-addressed import plus operation cache return the same results.</p>
  <p><b>Pat, Dirt and Dough</b> render offline. <b>Faust</b> compiles and renders on a daemon future because its first compiler warm-up is slow. <b>Sonic Pi</b> records in real time on that same background executor. None blocks Field's run loop.</p>
  <p>The local <code>RenderedAudioFile.provenance</code> records the producing box and render parameters. The server asset id remains a hash of the WAV bytes; the Field document remains the authority for source code and arrangement.</p>
</section>
"""
        .trimIndent(),
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 390))
```

## 3. 4 · Faust render → analysis

<!-- field-box id="analyse_faust_render" index="2" -->

```edn [field-box id="analyse_faust_render" role="properties"]
{"id" "analyse_faust_render" "index" 2 "name" "4 · Faust render → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · Faust render → analysis" "_tombstone" false "frame" #afield/rect [520.0 190.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_faust_render_code_.kt" "lines" 37 "characters" 1425 "sha256" "84693120a06b8216a14bc4ff7e3fb1d80245dddbaf3fe803f6791d28b7eb61fb"}]}
```

```kotlin [field-property box="analyse_faust_render" name="code" path="analyse_faust_render_code_.kt"]
import java.nio.file.Path

val client = FieldAudioClient()
val faustSource =
    """
    import("stdfaust.lib");
    sweep = 240 + 170 * os.osc(0.11);
    voice = os.sawtooth(sweep) : fi.lowpass(3, 900 + 650 * os.osc(0.07));
    process = voice * 0.18 <: _,_;
"""
        .trimIndent()

renderFaustWavAsync(
        Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "faust-sweep-study.wav"),
        name = "faust-sweep-study",
        source = faustSource,
        seconds = 18.0,
        provenance = mapOf("field_box" to "analyse_faust_render"),
    )
    .thenCompose { render ->
        display("render", "rendered ${render.path} · ${render.sampleRate} Hz stereo")
        client.analyzeRenderedAudio(
            render,
            requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/faust") },
            onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
            onProgress = { named, accepted, event ->
                display("${named.label} job", fieldAudioJobStatusHtml(accepted, event))
            },
        )
    }
    .thenAccept { completed ->
        display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
    }
    .exceptionally { error ->
        display("analysis error", error.cause?.message ?: error.message ?: error.toString())
        null
    }
```

## 4. 3 · Dough render → analysis

<!-- field-box id="analyse_dough_render" index="3" -->

```edn [field-box id="analyse_dough_render" role="properties"]
{"id" "analyse_dough_render" "index" 3 "name" "3 · Dough render → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · Dough render → analysis" "_tombstone" false "frame" #afield/rect [40.0 190.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_dough_render_code_.kt" "lines" 37 "characters" 1327 "sha256" "a40237a052212d2d0c5bf3427d09a9903c8a20b5d5f8b7209894252ddb224785"}]}
```

```kotlin [field-property box="analyse_dough_render" name="code" path="analyse_dough_render_code_.kt"]
import java.nio.file.Path

val client = FieldAudioClient()
val source = ZawOsc(Param(110f)) * 0.35f
val voice = source.through(
    Phaser(
        rate = Param(0.23f),
        depth = Param(0.78f),
        center = Param(540f),
        sweep = Param(1800f),
        mix = Param(0.92f),
    ),
)
val render = renderDoughWav(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "dough-phaser-study.wav"),
    voice,
    seconds = 18.0,
    provenance = mapOf("field_box" to "analyse_dough_render", "voice" to "ZawOsc → Phaser"),
)
display("render", "rendered ${render.path} · ${render.sampleRate} Hz mono")

client
    .analyzeRenderedAudio(
        render,
        requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/dough") },
        onAccepted = { named, accepted -> display("${named.label} job", fieldAudioJobStatusHtml(accepted)) },
        onProgress = { named, accepted, event ->
            display("${named.label} job", fieldAudioJobStatusHtml(accepted, event))
        },
    )
    .thenAccept { completed ->
        display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
    }
    .exceptionally { error ->
        display("analysis error", error.cause?.message ?: error.message ?: error.toString())
        null
    }
```

## 5. 2 · Pat / Dirt render → analysis

<!-- field-box id="analyse_pat_render" index="4" -->

```edn [field-box id="analyse_pat_render" role="properties"]
{"id" "analyse_pat_render" "index" 4 "name" "2 · Pat / Dirt render → analysis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · Pat / Dirt render → analysis" "_tombstone" false "frame" #afield/rect [520.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_pat_render_code_.kt" "lines" 32 "characters" 1362 "sha256" "97bf29fbaebf498fc121bf5bef94a4639ca1997e605a97b1743e146fb336a272"}]}
```

```kotlin [field-property box="analyse_pat_render" name="code" path="analyse_pat_render_code_.kt"]
import java.nio.file.Path

val client = FieldAudioClient()
val pattern = (pat("{60 64 67 71, bd ~ sd ~, hh*8}") with gain(sine().range(0.22, 0.72)) with pan("0.2 0.8"))
val render = renderDirtWav(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "pat-dirt-study.wav"),
    pattern,
    cycles = 12,
    cps = 1.6,
    provenance = mapOf("field_box" to "analyse_pat_render", "pattern" to "pitched chord + drums"),
)
display("render", "rendered ${render.path} · ${"%.2f".format(render.durationSeconds)} s")

client
    .analyzeRenderedAudio(
        render,
        requests = { region -> standardAudioAnalyses(region, requestedBy = "field:render-analysis/pat") },
        onAccepted = { named, accepted ->
            display("${named.label} job", fieldAudioJobStatusHtml(accepted))
        },
        onProgress = { named, accepted, event ->
            display("${named.label} job", fieldAudioJobStatusHtml(accepted, event))
        },
        onProgressError = { named, error -> display("${named.label} stream", error.message ?: error.toString()) },
    )
    .thenAccept { completed ->
        display("synchronized result", completed.visualizationHtml(width = 1040, panelHeight = 230))
    }
    .exceptionally { error ->
        display("analysis error", error.cause?.message ?: error.message ?: error.toString())
        null
    }
```

