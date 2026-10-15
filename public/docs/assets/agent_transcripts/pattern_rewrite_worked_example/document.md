# Field Document: pattern_rewrite_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. isCallTo Termination

<!-- field-box id="rw_terminate" index="0" -->

```edn [field-box id="rw_terminate" role="properties"]
{"id" "rw_terminate" "index" 0 "name" "isCallTo Termination" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "isCallTo Termination" "_tombstone" false "frame" #afield/rect [60.0 340.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_terminate_code_.kt" "lines" 9 "characters" 200 "sha256" "aad607afc5cf3215713133448d68e22f7c26a07a21b157206adc5574bd318e63"}]}
```

```kotlin [field-property box="rw_terminate" name="code" path="rw_terminate_code_.kt"]
import afield.runtime.macros.*
fun wrap(v: Int) = v
fun f(v: Int) = v + 1
rewrite { x: Expr ->
    from { f(x) }
    where { !isCallTo(x, "wrap") }
    to { f(wrap(x)) }
}
println("f(41) = " + f(41))
```

## 2. Ambient Parent (holds the rule)

<!-- field-box id="rw_ambient_parent" index="1" -->

```edn [field-box id="rw_ambient_parent" role="properties"]
{"id" "rw_ambient_parent" "index" 1 "name" "Ambient Parent (holds the rule)" "parents" ["root"] "children" ["rw_ambient_child"] "properties" {"children" #{#afield/box "rw_ambient_child"} "parents" #{#afield/box "root"} "name" "Ambient Parent (holds the rule)" "_tombstone" false "frame" #afield/rect [460.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_ambient_parent_code_.kt" "lines" 2 "characters" 82 "sha256" "93aa0f490cdc1e50ad10c978efb276fdd74442fd75e628b70db1908f59c5ff4f"}]}
```

```kotlin [field-property box="rw_ambient_parent" name="code" path="rw_ambient_parent_code_.kt"]
import afield.runtime.macros.*
rewrite { x: Expr -> from { boom(x) }; to { -x } }
```

## 3. Ambient Child (inherits it)

<!-- field-box id="rw_ambient_child" index="2" -->

```edn [field-box id="rw_ambient_child" role="properties"]
{"id" "rw_ambient_child" "index" 2 "name" "Ambient Child (inherits it)" "parents" ["root" "rw_ambient_parent"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rw_ambient_parent"} "name" "Ambient Child (inherits it)" "_tombstone" false "frame" #afield/rect [460.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_ambient_child_code_.kt" "lines" 1 "characters" 32 "sha256" "6e2940ee1d61fdadcae881ec49da83a44d424a9b1e0b24e5348af016aaefac15"}]}
```

```kotlin [field-property box="rw_ambient_child" name="code" path="rw_ambient_child_code_.kt"]
println("boom(5) = " + boom(5))
```

## 4. Negation

<!-- field-box id="rw_negation" index="3" -->

```edn [field-box id="rw_negation" role="properties"]
{"id" "rw_negation" "index" 3 "name" "Negation" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Negation" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_negation_code_.kt" "lines" 3 "characters" 113 "sha256" "e64ecf9f257919c657a0a74df2f00f2b4c5e9168b70eebb575171bf63fa195ac"}]}
```

```kotlin [field-property box="rw_negation" name="code" path="rw_negation_code_.kt"]
import afield.runtime.macros.*
rewrite { x: Expr -> from { neg(x) }; to { -x } }
println("neg(10) = " + neg(10))
```

## 5. Procedural unquote

<!-- field-box id="rw_unquote" index="4" -->

```edn [field-box id="rw_unquote" role="properties"]
{"id" "rw_unquote" "index" 4 "name" "Procedural unquote" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Procedural unquote" "_tombstone" false "frame" #afield/rect [60.0 620.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_unquote_code_.kt" "lines" 3 "characters" 156 "sha256" "d63f2caa35459f22ab413ffb8ff3f16bea17a5e450d66ef595e601c8aef3765b"}]}
```

```kotlin [field-property box="rw_unquote" name="code" path="rw_unquote_code_.kt"]
import afield.runtime.macros.*
rewrite { x: Ident -> from { rev(x) }; to { unquote { "\"" + x.reversed() + "\"" } } }
println("rev(hello) = " + rev(hello))
```

## 6. Precedence Guard

<!-- field-box id="rw_precedence" index="5" -->

```edn [field-box id="rw_precedence" role="properties"]
{"id" "rw_precedence" "index" 5 "name" "Precedence Guard" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Precedence Guard" "_tombstone" false "frame" #afield/rect [60.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_precedence_code_.kt" "lines" 3 "characters" 128 "sha256" "022f6135f8c8bcc538e48444a1225ae9042e482916cccca5d0b769dbe3e7d09c"}]}
```

```kotlin [field-property box="rw_precedence" name="code" path="rw_precedence_code_.kt"]
import afield.runtime.macros.*
rewrite { x: Expr -> from { twice(x) }; to { x * 2 } }
println("twice(3 + 4) = " + twice(3 + 4))
```

## 7. Variadic Hole

<!-- field-box id="rw_variadic" index="6" -->

```edn [field-box id="rw_variadic" role="properties"]
{"id" "rw_variadic" "index" 6 "name" "Variadic Hole" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Variadic Hole" "_tombstone" false "frame" #afield/rect [60.0 480.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_variadic_code_.kt" "lines" 7 "characters" 209 "sha256" "4bcc2014038840b9b102ffffb4e4602c2bb61c21be1261249cd86a0f5613e4e3"}]}
```

```kotlin [field-property box="rw_variadic" name="code" path="rw_variadic_code_.kt"]
import afield.runtime.macros.*
fun sink(vararg xs: Int) = xs.sum()
rewrite { first: Expr, rest: Vararg ->
    from { log(first, rest) }
    to { sink(first, rest) }
}
println("log(1, 2, 3) = " + log(1, 2, 3))
```

