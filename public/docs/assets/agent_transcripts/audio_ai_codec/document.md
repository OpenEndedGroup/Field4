# Field Document: audio_ai_codec

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. EnCodec reconstruction microscope

<!-- field-box id="encodec_reconstruction_microscope" index="0" -->

```edn [field-box id="encodec_reconstruction_microscope" role="properties"]
{"id" "encodec_reconstruction_microscope" "index" 0 "name" "EnCodec reconstruction microscope" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "EnCodec reconstruction microscope" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "encodec_reconstruction_microscope_code_.kt" "lines" 10 "characters" 613 "sha256" "22ffa97e153536c4946017e51f540fac063e2ecf70679cb3a5aad7999f243e99"}]}
```

```kotlin [field-property box="encodec_reconstruction_microscope" name="code" path="encodec_reconstruction_microscope_code_.kt"]
import afield.ui.visualization.AudioVisualizer
import afield.ui.visualization.AudioVisualizerOptions
import afield.audio.engine.WavFloatArrayLoader
import java.io.File
import java.nio.file.Path
val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_codec")
val result = json.decodeFromString<CodecReconstructionResult>(File(folder, "result.json").readText())
val data = CodecVisualization.load(result, File(folder, "source.wav").toPath(), File(folder, "reconstruction.wav").toPath(), File(folder, "tokens.npy").toPath())
display("main", CodecVisualization.renderHtml(data, width = 1040))
```

