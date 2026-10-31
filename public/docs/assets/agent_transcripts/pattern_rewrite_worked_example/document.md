# Field Document: pattern_rewrite_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. isCallTo Termination

<!-- field-box id="rw_terminate" index="0" -->

```edn [field-box id="rw_terminate" role="properties"]
{"id" "rw_terminate" "index" 0 "name" "isCallTo Termination" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "isCallTo Termination" "_tombstone" false "frame" #afield/rect [60.0 340.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_terminate_code_.kt" "lines" 11 "characters" 269 "sha256" "6490e6d54092af4275b2df580f8b9f860ef2d95a3c2807d042e24ea1427c06f9"}]}
```

```kotlin [field-property box="rw_terminate" name="code" path="rw_terminate_code_.kt"]
import afield.runtime.macros.*

fun wrap(v: Int) = v
fun f(v: Int) = v + 1
rewrite { x: Expr ->
    from { f(x) }
    // wrap marks an expanded call, so the next rewrite stops here.
    where { !isCallTo(x, "wrap") }
    to { f(wrap(x)) }
}
println("f(41) = " + f(41))
```

## 2. Ambient Parent (holds the rule)

<!-- field-box id="rw_ambient_parent" index="1" -->

```edn [field-box id="rw_ambient_parent" role="properties"]
{"id" "rw_ambient_parent" "index" 1 "name" "Ambient Parent (holds the rule)" "parents" ["root"] "children" ["rw_ambient_child"] "properties" {"children" #{#afield/box "rw_ambient_child"} "parents" #{#afield/box "root"} "name" "Ambient Parent (holds the rule)" "_tombstone" false "frame" #afield/rect [460.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_ambient_parent_code_.kt" "lines" 6 "characters" 90 "sha256" "b3a0b378b93fb0b03268de40c5192cd88131f398b75fcabb31a8a3e76078f377"}]}
```

```kotlin [field-property box="rw_ambient_parent" name="code" path="rw_ambient_parent_code_.kt"]
import afield.runtime.macros.*

rewrite { x: Expr ->
    from { boom(x) }
    to { -x }
}
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
{"id" "rw_negation" "index" 3 "name" "Negation" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Negation" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_negation_code_.kt" "lines" 7 "characters" 121 "sha256" "8a8366d74105b9cdb5de0be405ff3d51aa45fc0eae9501062b8769b9390d7db2"}]}
```

```kotlin [field-property box="rw_negation" name="code" path="rw_negation_code_.kt"]
import afield.runtime.macros.*

rewrite { x: Expr ->
    from { neg(x) }
    to { -x }
}
println("neg(10) = " + neg(10))
```

## 5. Procedural unquote

<!-- field-box id="rw_unquote" index="4" -->

```edn [field-box id="rw_unquote" role="properties"]
{"id" "rw_unquote" "index" 4 "name" "Procedural unquote" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Procedural unquote" "_tombstone" false "frame" #afield/rect [60.0 620.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_unquote_code_.kt" "lines" 7 "characters" 164 "sha256" "87e0bdc126b05f0261f719bc95056244b71a4dd20f3ee0f0d1c340471b3e2ddd"}]}
```

```kotlin [field-property box="rw_unquote" name="code" path="rw_unquote_code_.kt"]
import afield.runtime.macros.*

rewrite { x: Ident ->
    from { rev(x) }
    to { unquote { "\"" + x.reversed() + "\"" } }
}
println("rev(hello) = " + rev(hello))
```

## 6. Precedence Guard

<!-- field-box id="rw_precedence" index="5" -->

```edn [field-box id="rw_precedence" role="properties"]
{"id" "rw_precedence" "index" 5 "name" "Precedence Guard" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Precedence Guard" "_tombstone" false "frame" #afield/rect [60.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_precedence_code_.kt" "lines" 7 "characters" 136 "sha256" "d1980a6daa59ef6c6b387c794cb9f6d690c2e05fea19efac3321b059d045c895"}]}
```

```kotlin [field-property box="rw_precedence" name="code" path="rw_precedence_code_.kt"]
import afield.runtime.macros.*

rewrite { x: Expr ->
    from { twice(x) }
    to { x * 2 }
}
println("twice(3 + 4) = " + twice(3 + 4))
```

## 7. Variadic Hole

<!-- field-box id="rw_variadic" index="6" -->

```edn [field-box id="rw_variadic" role="properties"]
{"id" "rw_variadic" "index" 6 "name" "Variadic Hole" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Variadic Hole" "_tombstone" false "frame" #afield/rect [60.0 480.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_variadic_code_.kt" "lines" 8 "characters" 210 "sha256" "962480b2e6bd21cc7c8c8305093e222a1547b623b84c8c00c4b024b0e2d0d5c7"}]}
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

