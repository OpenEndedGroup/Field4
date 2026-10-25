# Field Document: stacksl_evolve_studio

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Evolve

<!-- field-box id="stacksl_evolve_studio" index="0" -->

### Narrative

From `code`

A StackSL search toward a picture, steered from this box's AI panel. Ask, for instance:
"evolve toward the target, and bring the watchmaker in when it stalls".
The chat runs generations (evolve_step), looks at the leaders (evolve_look), proposes programs
(evolve_propose), asks the fast model for a round of edits (evolve_watchmaker), takes a gradient
step on a leader's numbers (evolve_fit) and changes the metric or the population (evolve_configure).
The population belongs to the conversation: rewind the chat and the search goes back with it, and
evolve_crossbreed breeds with any population the conversation has been in, on any branch.
/population and /sheet show the leaders. For your own picture:
StackSLTarget.of("logo", imgFromFile("/path/to/logo.png"))

```edn [field-box id="stacksl_evolve_studio" role="properties"]
{"id" "stacksl_evolve_studio" "index" 0 "name" "Evolve" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Evolve" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacksl_evolve_studio_code_.kt" "lines" 14 "characters" 936 "sha256" "64bf70da18e1d5a152026d8184ae0139791dce7d08706863cebd01a5d5c46c6e"}]}
```

```kotlin [field-property box="stacksl_evolve_studio" name="code" path="stacksl_evolve_studio_code_.kt"]
/*
A StackSL search toward a picture, steered from this box's AI panel. Ask, for instance:
"evolve toward the target, and bring the watchmaker in when it stalls".
The chat runs generations (evolve_step), looks at the leaders (evolve_look), proposes programs
(evolve_propose), asks the fast model for a round of edits (evolve_watchmaker), takes a gradient
step on a leader's numbers (evolve_fit) and changes the metric or the population (evolve_configure).
The population belongs to the conversation: rewind the chat and the search goes back with it, and
evolve_crossbreed breeds with any population the conversation has been in, on any branch.
/population and /sheet show the leaders. For your own picture:
  StackSLTarget.of("logo", imgFromFile("/path/to/logo.png"))
*/
val evolution = stackSLEvolution(StackSLTargets.rings(32))
stackSLEvolveStudio(evolution)
display("ai-chat", afield.ai.conversation.AiBoxConversation.read(thisBox))
```

