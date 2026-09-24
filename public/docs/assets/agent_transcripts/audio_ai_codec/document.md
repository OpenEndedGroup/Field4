# Field Document: audio_ai_codec

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. EnCodec reconstruction microscope

<!-- field-box id="encodec_reconstruction_microscope" index="0" -->

```edn [field-box id="encodec_reconstruction_microscope" role="properties"]
{"id" "encodec_reconstruction_microscope" "index" 0 "name" "EnCodec reconstruction microscope" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "EnCodec reconstruction microscope" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "encodec_reconstruction_microscope_code_.kt" "lines" 27 "characters" 1108 "sha256" "0f3f086fa9d4daf6bee1ba994ed4f42f3922857474ae4caf54612747e7229956"}]}
```

```kotlin [field-property box="encodec_reconstruction_microscope" name="code" path="encodec_reconstruction_microscope_code_.kt"]
import afield.stdlib.noir.audioai.*

val sampleRate = 8000
val seconds = 4.0
val source = FloatArray((sampleRate * seconds).toInt()) { i ->
    val t = i / sampleRate.toDouble()
    val chirp = sin(2.0 * PI * (180.0 * t + 110.0 * t * t))
    val room = 0.28 * sin(2.0 * PI * 720.0 * t) * exp(-0.16 * t)
    (0.62 * chirp + room + 0.025 * sin(2.0 * PI * 3100.0 * t)).toFloat()
}
val reconstruction = FloatArray(source.size) { i ->
    source[i] * 0.91f +
        (0.008 * sin(i * 0.173) + 0.004 * sin(i * 0.037)).toFloat()
}
val tokenFrames = (seconds * 50).toInt()
val tokenValues = LongArray(4 * tokenFrames) { i ->
    val codebook = i / tokenFrames
    val frame = i % tokenFrames
    ((frame * (31 + codebook * 8) + 173 * codebook + 97 * sin(frame * 0.11)).toLong() and 2047)
}
val data = CodecVisualizationData(
    source, reconstruction, sampleRate,
    NpyIntTensor(intArrayOf(1, 4, tokenFrames), tokenValues),
    ReconstructionMetrics(0.0123, 18.7, 0.143),
    50.0, 2048, "EnCodec 32 kHz · deterministic documentation fixture",
)
display("main", CodecVisualization.renderHtml(data, width = 1100))
```

