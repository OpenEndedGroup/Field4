# Field Document: spreadsheet_tour

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Rates · run me first

<!-- field-box id="spreadsheet_tour_rates" index="0" -->

### Narrative

From `code`

The sheet reads these with box("Rates · run me first")["tax"].
Change one, run this box again, and watch the sheet.

```edn [field-box id="spreadsheet_tour_rates" role="properties"]
{"id" "spreadsheet_tour_rates" "index" 0 "name" "Rates · run me first" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Rates · run me first" "_tombstone" false "frame" #afield/rect [80.0 80.0 260.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spreadsheet_tour_rates_code_.kt" "lines" 4 "characters" 156 "sha256" "dc2a025026c3f5b1dbfe67ddebc1cee642a52ccf5f4445088a7e016a27863f00"}]}
```

```kotlin [field-property box="spreadsheet_tour_rates" name="code" path="spreadsheet_tour_rates_code_.kt"]
// The sheet reads these with box("Rates · run me first")["tax"].
// Change one, run this box again, and watch the sheet.
val tax = 1.08
val discount = 0.9
```

## 2. Bill · run me

<!-- field-box id="spreadsheet_tour_sheet" index="1" -->

```edn [field-box id="spreadsheet_tour_sheet" role="properties"]
{"id" "spreadsheet_tour_sheet" "index" 1 "name" "Bill · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Bill · run me" "_tombstone" false "frame" #afield/rect [80.0 180.0 640.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spreadsheet_tour_sheet_code_.kt" "lines" 29 "characters" 781 "sha256" "0176c5cef72dbe30da85866c7af2e598522d70e4635061261179e0d1a21a7509"}]}
```

```kotlin [field-property box="spreadsheet_tour_sheet" name="code" path="spreadsheet_tour_sheet_code_.kt"]
sheet(height = 320) {
    rows = 8
    cols = 5
    this["A1"] = "Item"
    this["B1"] = "Price"
    this["C1"] = "Qty"
    this["D1"] = "Net"
    this["E1"] = "Width"
    this["A2"] = "paper"
    this["B2"] = 4.5
    this["C2"] = 3
    this["A3"] = "ink"
    this["B3"] = 12
    this["C3"] = 2
    this["A4"] = "pens"
    this["B4"] = 1.25
    this["C4"] = 10
    formula("D2", "B2 * C2")
    formula("D3", "B3 * C3")
    formula("D4", "B4 * C4")
    this["A5"] = "Subtotal"
    formula("D5", "sum(D2:D4)")
    this["A6"] = "Discounted"
    formula("D6", "D5 * box(\"Rates · run me first\")[\"discount\"]")
    this["A7"] = "With tax"
    formula("D7", "round(D6 * box(\"Rates · run me first\")[\"tax\"] * 100) / 100")
    formula("E2", "0.21 | m")
    formula("E3", "E2 * C2")
}
```

