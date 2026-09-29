# Field Document: tone_synth_sequence

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. KotlinJS → Tone

<!-- field-box id="agent_tone_synth_sequence_root" index="0" -->

```edn [field-box id="agent_tone_synth_sequence_root" role="properties"]
{"id" "agent_tone_synth_sequence_root" "index" 0 "name" "KotlinJS → Tone" "parents" ["root"] "children" ["agent_tone_synth_sequence"] "properties" {"children" #{#afield/box "agent_tone_synth_sequence"} "parents" #{#afield/box "root"} "name" "KotlinJS → Tone" "_tombstone" false "frame" #afield/rect [80.0 580.0 300.0 100.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" []}
```

## 2. Tone Synth Sequence

<!-- field-box id="agent_tone_synth_sequence" index="1" -->

### Narrative

From `code`

{{ tone }}
A tiny chord arpeggio played through a reverb, scheduled on the Transport.
Tone is installed as the global `Tone` in the RemoteAR head; every name below is
bound by afield.graphics.ToneKotlinJs and grounded against the vendored Tone.js.

```edn [field-box id="agent_tone_synth_sequence" role="properties"]
{"id" "agent_tone_synth_sequence" "index" 1 "name" "Tone Synth Sequence" "parents" ["root" "agent_tone_synth_sequence_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_tone_synth_sequence_root"} "name" "Tone Synth Sequence" "_tombstone" false "frame" #afield/rect [120.0 690.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_tone_synth_sequence_code_.kt" "lines" 34 "characters" 1061 "sha256" "b3d344d0debab8784c216a13555cd5255dc2550e643a5b9af284e7adfabf9412"}]}
```

```kotlin [field-property box="agent_tone_synth_sequence" name="code" path="agent_tone_synth_sequence_code_.kt"]
// {{ tone }}
// A tiny chord arpeggio played through a reverb, scheduled on the Transport.
// Tone is installed as the global `Tone` in the RemoteAR head; every name below is
// bound by afield.graphics.ToneKotlinJs and grounded against the vendored Tone.js.
onStart {
    val synth = Tone.PolySynth()
    val reverb = Tone.Reverb(2.5)
    reverb.toDestination()
    synth.connect(reverb)
    synth.volume.value = -8.0

    val transport = Tone.getTransport()
    transport.bpm.value = 96.0

    val chord = arrayOf("C4", "E4", "G4", "B4")
    var step = 0
    val event = transport.scheduleRepeat({ time ->
        val note = chord[step % chord.size]
        synth.triggerAttackRelease(note, "8n", time)
        step += 1
    }, "8n")

    // Tone.start() resumes the AudioContext; this follows the click inside the head.
    Tone.start()
    transport.start()
    log("tone: arpeggio scheduled at " + transport.bpm.value + " bpm")

    onStop {
        transport.clear(event)
        transport.stop()
        synth.dispose()
        reverb.dispose()
    }
}
```

## 3. RemoteAR Head (open me — then click to enable audio)

<!-- field-box id="agent_tone_head" index="2" -->

### Narrative

From `code`

The head that plays the sound. It loads Tone.js and connects over the RemoteJS
transport; the // {{ tone }} box below compiles to JS and runs in here.

```edn [field-box id="agent_tone_head" role="properties"]
{"id" "agent_tone_head" "index" 2 "name" "RemoteAR Head (open me — then click to enable audio)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "RemoteAR Head (open me — then click to enable audio)" "_tombstone" false "frame" #afield/rect [80.0 80.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_tone_head_code_.kt" "lines" 11 "characters" 452 "sha256" "d405ce26856e05bf7b64823c6e26abe8b164d95b91c0ec2734172ac10f02ea8d"}]}
```

```kotlin [field-property box="agent_tone_head" name="code" path="agent_tone_head_code_.kt"]
// The head that plays the sound. It loads Tone.js and connects over the RemoteJS
// transport; the // {{ tone }} box below compiles to JS and runs in here.
display("tone-head", """
<iframe
  src="/extensions/remote_ar/index.html?debug=1"
  allow="xr-spatial-tracking; fullscreen; autoplay"
  data-afield-autosize="off"
  style="display:block; width:640px; height:400px; border:0; background:#000">
</iframe>
""".trimIndent())
displayOpen("tone-head")
```

