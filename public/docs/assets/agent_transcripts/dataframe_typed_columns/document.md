# Field Document: dataframe_typed_columns

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Typed columns, no strings

<!-- field-box id="dataframe_typed" index="0" -->

```edn [field-box id="dataframe_typed" role="properties"]
{"id" "dataframe_typed" "index" 0 "name" "1 - Typed columns, no strings" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Typed columns, no strings" "_tombstone" false "frame" #afield/rect [40.0 40.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_typed_code_.kt" "lines" 16 "characters" 449 "sha256" "d6f1ba63160c661521d0950f83b626ba82efb459db7fdf9b53cebb8c7bfefe09"}]}
```

```kotlin [field-property box="dataframe_typed" name="code" path="dataframe_typed_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// No @DataSchema, no generated accessors checked into the project. The plugin reads this
// call at compile time and types the result by the columns it produces.
val people = dataFrameOf("name", "age")(
    "Alice",
    15,
    "Bob",
    30,
    "Cara",
    45,
)

println("names: " + people.name.toList())
println("ages: " + people.age.toList())
println("mean age: " + people.age.toList().average())
```

## 2. 2 - A schema that changes

<!-- field-box id="dataframe_derived" index="1" -->

```edn [field-box id="dataframe_derived" role="properties"]
{"id" "dataframe_derived" "index" 1 "name" "2 - A schema that changes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A schema that changes" "_tombstone" false "frame" #afield/rect [480.0 40.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_derived_code_.kt" "lines" 21 "characters" 582 "sha256" "101174cde8c94d0b59c503e535b381f3c4defbb49167ed9c16a723a0b8c629f7"}]}
```

```kotlin [field-property box="dataframe_derived" name="code" path="dataframe_derived_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

/*
`decade` is not in the source data and is not a string lookup: `add` returns a NEW
schema, and the plugin worked out what that schema is. `age` resolving inside both
lambdas is the same mechanism one step earlier.
*/
val adults =
    dataFrameOf("name", "age")(
            "Alice",
            15,
            "Bob",
            30,
            "Cara",
            45,
        )
        .filter { age >= 18 }
        .add("decade") { age / 10 }

println("kept: " + adults.name.toList())
println("decade: " + adults.decade.toList())
```

## 3. 4 - Typed across the boundary

<!-- field-box id="dataframe_live" index="2" -->

```edn [field-box id="dataframe_live" role="properties"]
{"id" "dataframe_live" "index" 2 "name" "4 - Typed across the boundary" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Typed across the boundary" "_tombstone" false "frame" #afield/rect [480.0 190.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_live_code_.kt" "lines" 27 "characters" 823 "sha256" "e3c931afe14e881f54fe8cfd8567e05327798fdcc7f958468f3dbc4c05f589e3"}]}
```

```kotlin [field-property box="dataframe_live" name="code" path="dataframe_live_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

/*
typed() reads the loaded columns and writes a named schema into this box's preamble:
@DataSchema interface Peach { val name: String; val age: Int }
That named type lets later fragments use the columns as Kotlin properties.
*/
val people =
    dataFrameOf("name", "age")(
            "Alice",
            15,
            "Bob",
            30,
            "Cara",
            45,
        )
        .typed("Peach")

/*
Keep this break: the first fragment writes Peach, then Field reloads the preamble
before compiling the fragment below. Once loaded, select and run the lines below
repeatedly to query the same frame without reloading its data.
*/
//%-
val p = people.cast<Peach>()
println("ages: " + p.age.toList())
println("adults: " + p.filter { age >= 18 }.name.toList())
```

## 4. 3 - The whole chain in one pass

<!-- field-box id="dataframe_one_pass" index="3" -->

```edn [field-box id="dataframe_one_pass" role="properties"]
{"id" "dataframe_one_pass" "index" 3 "name" "3 - The whole chain in one pass" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - The whole chain in one pass" "_tombstone" false "frame" #afield/rect [40.0 190.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_one_pass_code_.kt" "lines" 17 "characters" 471 "sha256" "4f8b91ff6ad227e6714f171150fa367f8b076d762d809d32949a91d2edce33c3"}]}
```

```kotlin [field-property box="dataframe_one_pass" name="code" path="dataframe_one_pass_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// The shape that works: derive and consume inside a single execution, so the refined
// type never has to outlive the expression that created it.
val summary =
    dataFrameOf("name", "age")(
            "Alice",
            15,
            "Bob",
            30,
            "Cara",
            45,
        )
        .add("adult") { age >= 18 }
        .let { d -> d.name.toList().zip(d.adult.toList()) }

println(summary)
```

