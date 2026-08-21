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

## 3. 4 - The limit (do not run)

<!-- field-box id="dataframe_boundary" index="2" -->

```edn [field-box id="dataframe_boundary" role="properties"]
{"id" "dataframe_boundary" "index" 2 "name" "4 - The limit (do not run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - The limit (do not run)" "_tombstone" false "frame" #afield/rect [510.0 340.0 440.0 250.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataframe_boundary_code_.kt" "lines" 19 "characters" 1122 "sha256" "1179cdec8697ab3d5b8162872f597c007fa040186574e8bc090b85c25dd19b76"}]}
```

```kotlin [field-property box="dataframe_boundary" name="code" path="dataframe_boundary_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// MEASURED LIMIT — this box is here to be read, not run. Executing it fails with
//   <empty compile time schema>
//   Unresolved reference 'age' on receiver of type 'DataFrame<Any>'
//
// The plugin rewrites a refined call into `run { class Token; ...; call() as
// DataFrame<Token> }` with LOCAL classes, because its extension point works at
// body-resolve and cannot create top-level declarations. A local class cannot appear in
// the type of a snippet's property, so the refinement is erased at the boundary.
//
// Naming a @DataSchema does not rescue it either: the type then survives as
// DataFrame<Person> with a known schema, but TopLevelExtensionsGenerator finds schemas
// through K2's predicate-based provider, which is source-only — so a schema compiled
// elsewhere generates no accessors. The designed route is the plugin's `schemasPath`
// option plus ImportedSchemasGenerator, which Field does not wire yet.
val df = dataFrameOf("name", "age")("Alice", 15)
// ... then, in a LATER execution of this same box:
// df.age.toList()      // <- does not compile
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

