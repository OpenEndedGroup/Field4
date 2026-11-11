# Field Document: forth_step_sequencer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage"]}
```

## 1. forth steps

<!-- field-box id="forth_steps_root" index="0" -->

```edn [field-box id="forth_steps_root" role="properties"]
{"id" "forth_steps_root" "index" 0 "name" "forth steps" "parents" ["root"] "children" ["forth_steps_alternation" "forth_steps_synth_word" "forth_steps_chime" "forth_steps_dirt" "forth_steps_live"] "properties" {"children" #{#afield/box "forth_steps_alternation" #afield/box "forth_steps_synth_word" #afield/box "forth_steps_chime" #afield/box "forth_steps_dirt" #afield/box "forth_steps_live"} "parents" #{#afield/box "root"} "name" "forth steps" "_tombstone" false "frame" #afield/rect [80.0 40.0 160.0 70.0] "mainLanguage" "forth"} "blockProperties" []}
```

## 2. synth word

<!-- field-box id="forth_steps_synth_word" index="1" -->

```edn [field-box id="forth_steps_synth_word" role="properties"]
{"id" "forth_steps_synth_word" "index" 1 "name" "synth word" "parents" ["root" "forth_steps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "forth_steps_root"} "name" "synth word" "_tombstone" false "frame" #afield/rect [80.0 300.0 560.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "forth_steps_synth_word_code_.kt" "lines" 5 "characters" 150 "sha256" "0a6f14d345117dc875b2201fbd2e3655012e55a6bd17598b23b1a8451ffcbfa0"}]}
```

```kotlin [field-property box="forth_steps_synth_word" name="code" path="forth_steps_synth_word_code_.kt"]
: acid  saw 900 lpf 0.08 dur ;
130 bpm
seq: bass  acid c2 note | acid c3 note | | acid < eb2 g2 bb2 > note ;seq
bass 8 run-steps
bass 24 render-steps
```

## 3. dirt samples

<!-- field-box id="forth_steps_dirt" index="2" -->

```edn [field-box id="forth_steps_dirt" role="properties"]
{"id" "forth_steps_dirt" "index" 2 "name" "dirt samples" "parents" ["root" "forth_steps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "forth_steps_root"} "name" "dirt samples" "_tombstone" false "frame" #afield/rect [680.0 160.0 560.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "forth_steps_dirt_code_.kt" "lines" 4 "characters" 134 "sha256" "353ddd8e523132d7a977709c2d807b89bcd386772b8248874e440e4e3a797b27"}]}
```

```kotlin [field-property box="forth_steps_dirt" name="code" path="forth_steps_dirt_code_.kt"]
130 bpm
seq: kit  s" bd" sound | s" hh" sound | < s" sn" s" hh" s" cp" > sound | s" hh" sound ;seq
kit 8 run-steps
kit 24 render-dirt
```

## 4. alternation

<!-- field-box id="forth_steps_alternation" index="3" -->

```edn [field-box id="forth_steps_alternation" role="properties"]
{"id" "forth_steps_alternation" "index" 3 "name" "alternation" "parents" ["root" "forth_steps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "forth_steps_root"} "name" "alternation" "_tombstone" false "frame" #afield/rect [80.0 160.0 560.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "forth_steps_alternation_code_.kt" "lines" 4 "characters" 103 "sha256" "1a0276a355d1195d736445a26a04d8963b329a8c5cef6ca4b22e61275aee93b5"}]}
```

```kotlin [field-property box="forth_steps_alternation" name="code" path="forth_steps_alternation_code_.kt"]
120 bpm
seq: line  < c4 eb3 g3 > note | | < g3 c4 > note | ;seq
line 12 run-steps
line 12 render-steps
```

## 5. chime

<!-- field-box id="forth_steps_chime" index="4" -->

```edn [field-box id="forth_steps_chime" role="properties"]
{"id" "forth_steps_chime" "index" 4 "name" "chime" "parents" ["root" "forth_steps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "forth_steps_root"} "name" "chime" "_tombstone" false "frame" #afield/rect [80.0 450.0 560.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "forth_steps_chime_code_.kt" "lines" 5 "characters" 199 "sha256" "6aae187ec51bb4a0db3f9ae924953f1401536855b6ef4e42f243427cc7a74ff2"}]}
```

```kotlin [field-property box="forth_steps_chime" name="code" path="forth_steps_chime_code_.kt"]
: chime  tri 0.5 dur 0.5 gain ;
90 bpm
seq: bells  chime [ c5 e5 g5 b5 ] iter index note | | 0.2 dur [ c3 g3 eb3 ] [ 6 3 1 ] wchoose note | ;seq
7 seed bells 8 run-steps
7 seed bells 32 render-steps
```

## 6. live (launch me)

<!-- field-box id="forth_steps_live" index="5" -->

```edn [field-box id="forth_steps_live" role="properties"]
{"id" "forth_steps_live" "index" 5 "name" "live (launch me)" "parents" ["root" "forth_steps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "forth_steps_root"} "name" "live (launch me)" "_tombstone" false "frame" #afield/rect [80.0 600.0 560.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "forth_steps_live_code_.kt" "lines" 4 "characters" 101 "sha256" "02069a094239aafc847099d9c7dfb6b42697fa9a9220c4f5c8d5f001f9b199ec"}]}
```

```kotlin [field-property box="forth_steps_live" name="code" path="forth_steps_live_code_.kt"]
120 bpm
seq: line  < c4 eb3 g3 > note | | < g3 c4 > note | ;seq
: play  line tick-steps frame ;
play
```

