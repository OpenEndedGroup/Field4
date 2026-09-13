# Field Document: audio_ai_underfit_mancini

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 2 · generate and audition

<!-- field-box id="underfit_mancini_generate" index="0" -->

```edn [field-box id="underfit_mancini_generate" role="properties"]
{"id" "underfit_mancini_generate" "index" 0 "name" "2 · generate and audition" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · generate and audition" "_tombstone" false "frame" #afield/rect [500.0 40.0 420.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "underfit_mancini_generate_code_.kt" "lines" 32 "characters" 1312 "sha256" "c4074f716656a0d8ad34be514ec933c68757904e86b1a74113bd25f3e5f7c1ce"}]}
```

```kotlin [field-property box="underfit_mancini_generate" name="code" path="underfit_mancini_generate_code_.kt"]
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val sourcePath = Path.of("/home/marc/temp/music/audio-project/underfit-proof/source-mjq-skating.wav")
val loraRoot = Path.of(requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_LORA_ROOT")))
val checkpoint = UnderfitLoraCheckpoint.fromFile(
    loraRoot.resolve("mancini-experiment-in-terror/step-1000.safetensors"),
    workerRoot = loraRoot,
    strength = 0.85,
    intervalMax = 0.92,
)
val source = client.importWav(sourcePath).get()
val region = AssetRegion(source.id, startSeconds = 0.0, durationSeconds = 12.0)
client.submit(underfitCandidates(
    source = region,
    prompt = "mancini-eit, suspenseful nocturnal orchestral jazz, flute, vibraphone and muted brass",
    negativePrompt = "vocals, speech, harsh distortion",
    durationSeconds = 30.0,
    checkpoints = listOf(checkpoint),
    model = UnderfitModel.SMALL_MUSIC_BASE,
    controls = UnderfitGenerationControls(
        steps = 50,
        cfg = 1.5,
        initNoiseLevel = 0.65,
        durationPaddingSeconds = 2.0,
        chunkedDecode = true,
    ),
    seeds = 1962L..1962L,
    requestedBy = "field:audio-ai-underfit-mancini",
)).thenCompose(client::awaitCandidates)
    .thenAccept { display("Mancini LoRA", candidateBoardHtml(it)) }
```

## 2. 1 · Mancini LoRA guide

<!-- field-box id="underfit_mancini_guide" index="1" -->

```edn [field-box id="underfit_mancini_guide" role="properties"]
{"id" "underfit_mancini_guide" "index" 1 "name" "1 · Mancini LoRA guide" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · Mancini LoRA guide" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "underfit_mancini_guide_code_.kt" "lines" 9 "characters" 957 "sha256" "6ea7e8e1eb59aba9aaf96224d333104d16b21a11aaa6346fbf2d98c7a64fc096"}]}
```

```kotlin [field-property box="underfit_mancini_guide" name="code" path="underfit_mancini_guide_code_.kt"]
import afield.stdlib.noir.audioai.*

display("guide", """
  <section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
    <h2 style="margin:0 0 8px;color:#7fcbd7">Experiment in Terror · Underfit LoRA</h2>
    <p>This request uses 165 twelve-second chunks from the complete Henry Mancini album as training material. The pinned rank-16 DoRA checkpoint is applied to Stable Audio 3's small-music base model, with a 12-second source window extended to a 30-second candidate.</p>
    <p>Set <code>FIELD_AUDIO_UNDERFIT_LORA_ROOT</code> and start the Underfit worker before running box 2. The worker must have the staged base model and the checkpoint beneath that root. The request retains its model revisions, checkpoint digest, seed, and sampling controls in the durable candidate provenance.</p>
  </section>
""".trimIndent())
```

