# Field Document: spreadsheet_constraints

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Temperature · run me

<!-- field-box id="spreadsheet_constraints_temperature" index="0" -->

### Narrative

From `code`

B1 and B2 are variables; B4 says how they are related.
Type 37 into B1 and Fahrenheit follows; type 451 into B2 and Celsius follows.

```edn [field-box id="spreadsheet_constraints_temperature" role="properties"]
{"id" "spreadsheet_constraints_temperature" "index" 0 "name" "Temperature · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Temperature · run me" "_tombstone" false "frame" #afield/rect [80.0 80.0 420.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spreadsheet_constraints_temperature_code_.kt" "lines" 15 "characters" 470 "sha256" "1acdbb667f2faa08e098b9af4e9a6c90a065bfd57cc20b39366d96cfc1d53b99"}]}
```

```kotlin [field-property box="spreadsheet_constraints_temperature" name="code" path="spreadsheet_constraints_temperature_code_.kt"]
// B1 and B2 are variables; B4 says how they are related.
// Type 37 into B1 and Fahrenheit follows; type 451 into B2 and Celsius follows.
sheet(height = 200) {
    rows = 4
    cols = 2
    columnWidth("A", 140)
    this["A1"] = "Celsius"
    formula("B1", "free(100)")
    this["A2"] = "Fahrenheit"
    formula("B2", "free(212)")
    this["A3"] = "Kelvin"
    formula("B3", "B1 + 273.15")
    this["A4"] = "F from C"
    formula("B4", "B2 eq B1 * 9.0 / 5.0 + 32.0")
}
```

## 2. Rectangle · run me

<!-- field-box id="spreadsheet_constraints_rectangle" index="1" -->

### Narrative

From `code`

Width and height are variables; area and perimeter are ordinary formulas over them.
The two assertions hold the area at 40 and the width at no more than 6.
Type 8 into the width: the sheet stops it at 6 and the height takes up the area.

```edn [field-box id="spreadsheet_constraints_rectangle" role="properties"]
{"id" "spreadsheet_constraints_rectangle" "index" 1 "name" "Rectangle · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Rectangle · run me" "_tombstone" false "frame" #afield/rect [80.0 380.0 420.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spreadsheet_constraints_rectangle_code_.kt" "lines" 22 "characters" 669 "sha256" "36caa5349f94b79cadd9d5af7e9bf458d424e67a41a403f64b9ca6d84276c650"}]}
```

```kotlin [field-property box="spreadsheet_constraints_rectangle" name="code" path="spreadsheet_constraints_rectangle_code_.kt"]
/*
Width and height are variables; area and perimeter are ordinary formulas over them.
The two assertions hold the area at 40 and the width at no more than 6.
Type 8 into the width: the sheet stops it at 6 and the height takes up the area.
*/
sheet(height = 260) {
    rows = 6
    cols = 2
    columnWidth("A", 140)
    this["A1"] = "width"
    formula("B1", "free(4)")
    this["A2"] = "height"
    formula("B2", "free(5)")
    this["A3"] = "area"
    formula("B3", "B1 * B2")
    this["A4"] = "perimeter"
    formula("B4", "2 * (B1 + B2)")
    this["A5"] = "area is 40"
    formula("B5", "B3 eq 40")
    this["A6"] = "no wider than 6"
    formula("B6", "B1 le 6")
}
```

