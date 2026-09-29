# Field Document: box_layout_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 9 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. ingest

<!-- field-box id="bl_ingest" index="0" -->

### Narrative

From `code`

reads the raw files

```edn [field-box id="bl_ingest" role="properties"]
{"id" "bl_ingest" "index" 0 "name" "ingest" "parents" ["root"] "children" ["bl_clean"] "properties" {"children" #{#afield/box "bl_clean"} "parents" #{#afield/box "root"} "name" "ingest" "_tombstone" false "frame" #afield/rect [980.0 570.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_ingest_code_.kt" "lines" 1 "characters" 22 "sha256" "e673fc4dee4de390f47e33d99eb5d8dc5b2a4083c115a093dc90878481395058"}]}
```

```kotlin [field-property box="bl_ingest" name="code" path="bl_ingest_code_.kt"]
// reads the raw files
```

## 2. clean

<!-- field-box id="bl_clean" index="1" -->

### Narrative

From `code`

drops the malformed rows

```edn [field-box id="bl_clean" role="properties"]
{"id" "bl_clean" "index" 1 "name" "clean" "parents" ["root" "bl_ingest"] "children" ["bl_features" "bl_cache"] "properties" {"children" #{#afield/box "bl_features" #afield/box "bl_cache"} "parents" #{#afield/box "root" #afield/box "bl_ingest"} "name" "clean" "_tombstone" false "frame" #afield/rect [240.0 300.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_clean_code_.kt" "lines" 1 "characters" 27 "sha256" "3f29754c6acc5957bd527000f4081c3b99e0c95a7bbd8857894567076087b8d6"}]}
```

```kotlin [field-property box="bl_clean" name="code" path="bl_clean_code_.kt"]
// drops the malformed rows
```

## 3. features

<!-- field-box id="bl_features" index="2" -->

### Narrative

From `code`

windows, deltas, rolling means

```edn [field-box id="bl_features" role="properties"]
{"id" "bl_features" "index" 2 "name" "features" "parents" ["root" "bl_clean"] "children" ["bl_model" "bl_plot"] "properties" {"children" #{#afield/box "bl_model" #afield/box "bl_plot"} "parents" #{#afield/box "root" #afield/box "bl_clean"} "name" "features" "_tombstone" false "frame" #afield/rect [300.0 660.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_features_code_.kt" "lines" 1 "characters" 33 "sha256" "cae36acd109717f90c7e7d1fa31036e04fa0aa3f3a81b5872b61ca6034c06adb"}]}
```

```kotlin [field-property box="bl_features" name="code" path="bl_features_code_.kt"]
// windows, deltas, rolling means
```

## 4. model

<!-- field-box id="bl_model" index="3" -->

### Narrative

From `code`

fits the thing

```edn [field-box id="bl_model" role="properties"]
{"id" "bl_model" "index" 3 "name" "model" "parents" ["root" "bl_features"] "children" ["bl_eval"] "properties" {"children" #{#afield/box "bl_eval"} "parents" #{#afield/box "root" #afield/box "bl_features"} "name" "model" "_tombstone" false "frame" #afield/rect [900.0 120.0 290.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_model_code_.kt" "lines" 1 "characters" 17 "sha256" "c5f573ea61765c6c47faedd53482f574a185004cb20da3d8d219ac5dd20e6f0d"}]}
```

```kotlin [field-property box="bl_model" name="code" path="bl_model_code_.kt"]
// fits the thing
```

## 5. eval

<!-- field-box id="bl_eval" index="4" -->

### Narrative

From `code`

holdout scores

```edn [field-box id="bl_eval" role="properties"]
{"id" "bl_eval" "index" 4 "name" "eval" "parents" ["root" "bl_model"] "children" ["bl_report" "bl_alert"] "properties" {"children" #{#afield/box "bl_report" #afield/box "bl_alert"} "parents" #{#afield/box "root" #afield/box "bl_model"} "name" "eval" "_tombstone" false "frame" #afield/rect [150.0 120.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_eval_code_.kt" "lines" 1 "characters" 17 "sha256" "0f637c4d76c1c39dbd03518b47335fe6e4f7d7828c93779c08db3a8c2267e90e"}]}
```

```kotlin [field-property box="bl_eval" name="code" path="bl_eval_code_.kt"]
// holdout scores
```

## 6. plot

<!-- field-box id="bl_plot" index="5" -->

### Narrative

From `code`

showSignals(...)

```edn [field-box id="bl_plot" role="properties"]
{"id" "bl_plot" "index" 5 "name" "plot" "parents" ["root" "bl_features"] "children" ["bl_report"] "properties" {"children" #{#afield/box "bl_report"} "parents" #{#afield/box "root" #afield/box "bl_features"} "name" "plot" "_tombstone" false "frame" #afield/rect [660.0 300.0 280.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_plot_code_.kt" "lines" 1 "characters" 19 "sha256" "b557f1ffb90e1b9ffc404303a2d869c8af2a2ea8c58e0dfd1eccdebec46d429b"}]}
```

```kotlin [field-property box="bl_plot" name="code" path="bl_plot_code_.kt"]
// showSignals(...)
```

## 7. report

<!-- field-box id="bl_report" index="6" -->

### Narrative

From `code`

the page someone actually reads

```edn [field-box id="bl_report" role="properties"]
{"id" "bl_report" "index" 6 "name" "report" "parents" ["root" "bl_eval" "bl_plot"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bl_eval" #afield/box "bl_plot"} "name" "report" "_tombstone" false "frame" #afield/rect [720.0 700.0 310.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_report_code_.kt" "lines" 1 "characters" 34 "sha256" "bfdb1309e10e75fff381c8ce423d1773dafb9d96a7ac25949819b45ac74aa11a"}]}
```

```kotlin [field-property box="bl_report" name="code" path="bl_report_code_.kt"]
// the page someone actually reads
```

## 8. cache

<!-- field-box id="bl_cache" index="7" -->

### Narrative

From `code`

parquet on disk

```edn [field-box id="bl_cache" role="properties"]
{"id" "bl_cache" "index" 7 "name" "cache" "parents" ["root" "bl_clean"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bl_clean"} "name" "cache" "_tombstone" false "frame" #afield/rect [420.0 430.0 280.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_cache_code_.kt" "lines" 1 "characters" 18 "sha256" "86f41264e8ccf5fc1c1fcacc4a384d6b5ed2137e15cdcef9e34e35fe92679546"}]}
```

```kotlin [field-property box="bl_cache" name="code" path="bl_cache_code_.kt"]
// parquet on disk
```

## 9. alert

<!-- field-box id="bl_alert" index="8" -->

### Narrative

From `code`

pings when a score drops

```edn [field-box id="bl_alert" role="properties"]
{"id" "bl_alert" "index" 8 "name" "alert" "parents" ["root" "bl_eval"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bl_eval"} "name" "alert" "_tombstone" false "frame" #afield/rect [840.0 400.0 270.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bl_alert_code_.kt" "lines" 1 "characters" 27 "sha256" "d78d0647c3831f690e67110d3b9516e59b414ebfcf65988c3849218e26adb35a"}]}
```

```kotlin [field-property box="bl_alert" name="code" path="bl_alert_code_.kt"]
// pings when a score drops
```

