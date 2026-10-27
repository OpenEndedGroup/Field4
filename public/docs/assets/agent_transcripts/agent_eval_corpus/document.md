# Field Document: agent_eval_corpus

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. What was mined

<!-- field-box id="corpus_summary" index="0" -->

### Narrative

From `code`

The checked-in corpus: tasks by kind and split, and the reasons boxes were not mined.

```edn [field-box id="corpus_summary" role="properties"]
{"id" "corpus_summary" "index" 0 "name" "What was mined" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "What was mined" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "corpus_summary_code_.kt" "lines" 11 "characters" 749 "sha256" "03602c3313888931c76b719439371a6bff3be11ba1f95a185b08fc57e410d064"}]}
```

```kotlin [field-property box="corpus_summary" name="code" path="corpus_summary_code_.kt"]
/* The checked-in corpus: tasks by kind and split, and the reasons boxes were not mined. */
import afield.ai.agent.AgentEvalCorpus

val corpus = AgentEvalCorpus.load()
println("${corpus.tasks.size} tasks from ${corpus.transcripts} transcripts (${corpus.codeBoxes} code boxes)")
val rows = corpus.tasks.groupingBy { it.kind + " / " + it.split }.eachCount().toSortedMap()
    .entries.joinToString("") { "<tr><td>${it.key}</td><td>${it.value}</td></tr>" }
val dropped = corpus.rejectionCounts().entries.take(12)
    .joinToString("") { "<tr><td>${it.key}</td><td>${it.value}</td></tr>" }
display("main", "<table><tr><th>kind / split</th><th>tasks</th></tr>$rows</table>" +
    "<table><tr><th>dropped because</th><th>boxes</th></tr>$dropped</table>")
```

## 2. One task

<!-- field-box id="corpus_one_task" index="1" -->

### Narrative

From `code`

One held-out task, drawn by seed: the goal and the checks the agent is judged by.

```edn [field-box id="corpus_one_task" role="properties"]
{"id" "corpus_one_task" "index" 1 "name" "One task" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "One task" "_tombstone" false "frame" #afield/rect [60.0 240.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "corpus_one_task_code_.kt" "lines" 7 "characters" 380 "sha256" "fd7341f8fe05651ed210ec30159fa5db947a3fa75431249882dde7d91903dda0"}]}
```

```kotlin [field-property box="corpus_one_task" name="code" path="corpus_one_task_code_.kt"]
/* One held-out task, drawn by seed: the goal and the checks the agent is judged by. */
import afield.ai.agent.AgentEvalCorpus

val task = AgentEvalCorpus.select(AgentEvalCorpus.load(), "heldout", 1, seed = 3).first()
println(task.id + " (" + task.kind + ")")
println(task.request.goal)
task.request.checks.forEach { println("  check " + it.kind + " " + it.box + " " + it.text) }
```

