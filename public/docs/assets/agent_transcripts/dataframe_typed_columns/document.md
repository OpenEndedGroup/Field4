# Field Document: dataframe_typed_columns

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 1 - Typed columns, no strings

<!-- field-box id="dataframe_typed" index="0" -->

```edn [field-box id="dataframe_typed" role="properties"]
{"id" "dataframe_typed" "index" 0 "name" "1 - Typed columns, no strings" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Typed columns, no strings" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 270.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_typed_code_.kt" "lines" 13 "characters" 437 "sha256" "e8770b348fcd4a66af8a968d6514797ed03c91c3ff22c1afb174931b3a1d8bf1"}]}
```

```kotlin [field-property box="dataframe_typed" name="code" path="dataframe_typed_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// No @DataSchema, no generated accessors checked into the project. The plugin reads this
// call at compile time and types the result by the columns it produces.
val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)

println("names: " + people.name.toList())
println("ages: " + people.age.toList())
println("mean age: " + people.age.toList().average())
```

## 2. 2 - A schema that changes

<!-- field-box id="dataframe_derived" index="1" -->

```edn [field-box id="dataframe_derived" role="properties"]
{"id" "dataframe_derived" "index" 1 "name" "2 - A schema that changes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A schema that changes" "_tombstone" false "frame" #afield/rect [510.0 40.0 440.0 270.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_derived_code_.kt" "lines" 15 "characters" 505 "sha256" "895bf5da759ccf41b313766afe301daa95ea9116999d3eb7842048ce847c1d43"}]}
```

```kotlin [field-property box="dataframe_derived" name="code" path="dataframe_derived_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// `decade` is not in the source data and is not a string lookup: `add` returns a NEW
// schema, and the plugin worked out what that schema is. `age` resolving inside both
// lambdas is the same mechanism one step earlier.
val adults = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)
    .filter { age >= 18 }
    .add("decade") { age / 10 }

println("kept: " + adults.name.toList())
println("decade: " + adults.decade.toList())
```

## 3. 4 - Typed across the boundary

<!-- field-box id="dataframe_live" index="2" -->

```edn [field-box id="dataframe_live" role="properties"]
{"id" "dataframe_live" "index" 2 "name" "4 - Typed across the boundary" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Typed across the boundary" "_tombstone" false "frame" #afield/rect [510.0 340.0 440.0 340.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_live_code_.kt" "lines" 27 "characters" 1384 "sha256" "5b854d7e58211bb4b9279d78034b70e60ef61ddc1ec50962d103de909f84b3b1"}]}
```

```kotlin [field-property box="dataframe_live" name="code" path="dataframe_live_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// The plugin's inferred type cannot outlive the expression that made it: it is built from
// LOCAL classes, which cannot appear in the type of a snippet's property, so on its own
// `people` would come back as DataFrame<Any> the moment it crossed a compile boundary.
//
// `typed()` fixes that by reading the frame's RUNTIME columns — the data is already
// loaded, so they are exactly right — and writing a real schema into this box's preamble:
//   @DataSchema interface Peach { val name: String; val age: Int }
val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
).typed("Peach")

// `//%-` is what makes this work in ONE execution. Field splits the box there and runs the
// halves in order, re-running the preamble setup before compiling each one (the
// per-fragment runLaunchSetupBlocks in execution.kt), so `Peach` is declared by the time
// the half below is compiled. Delete the marker and this box stops compiling: with a
// single fragment the schema is written only after that fragment was already compiled.
//
// The same thing works across separate executions — load once here, then select and run
// the lines below as often as you like without reloading.
//%-
val p = people.cast<Peach>()
println("ages: " + p.age.toList())
println("adults: " + p.filter { age >= 18 }.name.toList())
```

## 4. 3 - The whole chain in one pass

<!-- field-box id="dataframe_one_pass" index="3" -->

```edn [field-box id="dataframe_one_pass" role="properties"]
{"id" "dataframe_one_pass" "index" 3 "name" "3 - The whole chain in one pass" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - The whole chain in one pass" "_tombstone" false "frame" #afield/rect [40.0 340.0 440.0 250.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_one_pass_code_.kt" "lines" 13 "characters" 391 "sha256" "1f0a1684a215fc6c0eb50991b56cd00fed3cd0756f691545ec6b5d2d3c537a8f"}]}
```

```kotlin [field-property box="dataframe_one_pass" name="code" path="dataframe_one_pass_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// The shape that works: derive and consume inside a single execution, so the refined
// type never has to outlive the expression that created it.
val summary = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)
    .add("adult") { age >= 18 }
    .let { d -> d.name.toList().zip(d.adult.toList()) }

println(summary)
```

