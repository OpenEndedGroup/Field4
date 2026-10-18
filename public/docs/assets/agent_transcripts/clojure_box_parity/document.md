# Field Document: clojure_box_parity

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage"]}
```

## 1. Clojure root -- children of this box run as Clojure

<!-- field-box id="cparity_root" index="0" -->

### Narrative

From `code`

The language root stays Kotlin; its `mainLanguage` makes its children Clojure.
Its `speed` property and this variable are read by the box "values from the parent".

```edn [field-box id="cparity_root" role="properties"]
{"id" "cparity_root" "index" 0 "name" "Clojure root -- children of this box run as Clojure" "parents" ["root"] "children" ["cparity_error" "cparity_controls" "cparity_context"] "properties" {"children" #{#afield/box "cparity_error" #afield/box "cparity_controls" #afield/box "cparity_context"} "parents" #{#afield/box "root"} "name" "Clojure root -- children of this box run as Clojure" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0] "mainLanguage" "clojure"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cparity_root_code_.kt" "lines" 4 "characters" 250 "sha256" "625be14619edadae224ac9b438ef351fcd156ac05a8ece3bd9041440d3a4e137"}]}
```

```kotlin [field-property box="cparity_root" name="code" path="cparity_root_code_.kt"]
// The language root stays Kotlin; its `mainLanguage` makes its children Clojure.
// Its `speed` property and this variable are read by the box "values from the parent".
val palette = listOf("tomato", "teal", "gold")
println("the root stays Kotlin")
```

## 2. an error is marked where it happened

<!-- field-box id="cparity_error" index="1" -->

### Narrative

From `code`

; The marker lands on line 6, inside mean, where the division failed --
; not on line 8, where mean was called.

```edn [field-box id="cparity_error" role="properties"]
{"id" "cparity_error" "index" 1 "name" "an error is marked where it happened" "parents" ["root" "cparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cparity_root"} "name" "an error is marked where it happened" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0] "code_language" "Clojure"} "blockProperties" [{"name" "code" "language" "clojure" "path" "cparity_error_code_.kt" "lines" 8 "characters" 210 "sha256" "e64ebecbbb15181ce18cfd135d625cfebc70f685065b77a2c43818eb7fe77c51"}]}
```

```clojure [field-property box="cparity_error" name="code" path="cparity_error_code_.kt"]
;; The marker lands on line 6, inside mean, where the division failed --
;; not on line 8, where mean was called.
(def readings [])

(defn mean [values]
  (/ (reduce + values) (count values)))

(mean readings)
```

## 3. values from the parent

<!-- field-box id="cparity_context" index="2" -->

### Narrative

From `code`

; `context` looks on this box, then on its parents: a property by name,
; a Kotlin variable by name, or the nearest value of a class.

```edn [field-box id="cparity_context" role="properties"]
{"id" "cparity_context" "index" 2 "name" "values from the parent" "parents" ["root" "cparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cparity_root"} "name" "values from the parent" "_tombstone" false "frame" #afield/rect [60.0 3040.0 340.0 100.0] "code_language" "Clojure"} "blockProperties" [{"name" "code" "language" "clojure" "path" "cparity_context_code_.kt" "lines" 5 "characters" 266 "sha256" "561a2f3051e39868e5dd975cca5475d785e316c042851c378c1afdcf98cae291"}]}
```

```clojure [field-property box="cparity_context" name="code" path="cparity_context_code_.kt"]
;; `context` looks on this box, then on its parents: a property by name,
;; a Kotlin variable by name, or the nearest value of a class.
(println "speed" (context :speed))
(println "palette" (vec (context "palette")))
(println "missing" (context :no-such-thing 1.0))
```

## 4. a slider and a checkbox, with Clojure handlers

<!-- field-box id="cparity_controls" index="3" -->

### Narrative

From `code`

; The same controls a Kotlin box draws, calling back into Clojure.

```edn [field-box id="cparity_controls" role="properties"]
{"id" "cparity_controls" "index" 3 "name" "a slider and a checkbox, with Clojure handlers" "parents" ["root" "cparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cparity_root"} "name" "a slider and a checkbox, with Clojure handlers" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0] "code_language" "Clojure"} "blockProperties" [{"name" "code" "language" "clojure" "path" "cparity_controls_code_.kt" "lines" 11 "characters" 452 "sha256" "5db1f01d9c8599b8ca886a9436d67e1aa2577ea598575cc4fe2b3ad188d9bc70"}]}
```

```clojure [field-property box="cparity_controls" name="code" path="cparity_controls_code_.kt"]
;; The same controls a Kotlin box draws, calling back into Clojure.
(def state (atom {:freq 3.0 :square false}))

(defn redraw [{:keys [freq square]}]
  (display "main" (format "<b>%s</b> at %.1f Hz" (if square "square" "sine") freq)))

(slider "frequency" #(swap! state assoc :freq %) :min 1 :max 10 :step 0.1 :value 3.0)
(checkbox "square wave" #(swap! state assoc :square %))

(add-watch state :redraw (fn [_ _ _ now] (redraw now)))
(redraw @state)
```

