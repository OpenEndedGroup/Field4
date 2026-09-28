# Field Document: pattern_rewrite_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. isCallTo Termination

<!-- field-box id="rw_terminate" index="0" -->

```edn [field-box id="rw_terminate" role="properties"]
{"id" "rw_terminate" "index" 0 "name" "isCallTo Termination" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "isCallTo Termination" "_tombstone" false "frame" #afield/rect [60.0 340.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_terminate_code_.kt" "lines" 9 "characters" 192 "sha256" "33d2ab4911856ba3a672536d8f6bd8ee06a370fca2baccd93615319015b64e6e"}]}
```

```kotlin [field-property box="rw_terminate" name="code" path="rw_terminate_code_.kt"]
import afield.macros.*
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
{"id" "rw_ambient_parent" "index" 1 "name" "Ambient Parent (holds the rule)" "parents" ["root"] "children" ["rw_ambient_child"] "properties" {"children" #{#afield/box "rw_ambient_child"} "parents" #{#afield/box "root"} "name" "Ambient Parent (holds the rule)" "_tombstone" false "frame" #afield/rect [460.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_ambient_parent_code_.kt" "lines" 2 "characters" 74 "sha256" "ed4630569a40eed619a96424c84aa2b571efccd6dcb13e3bb9092694dada1a48"}]}
```

```kotlin [field-property box="rw_ambient_parent" name="code" path="rw_ambient_parent_code_.kt"]
import afield.macros.*
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
{"id" "rw_negation" "index" 3 "name" "Negation" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Negation" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_negation_code_.kt" "lines" 3 "characters" 105 "sha256" "e5de4c997b2e0b65eba5643d057fe4b9bd14dfdf22dd1eac7ea25d01b137c7cf"}]}
```

```kotlin [field-property box="rw_negation" name="code" path="rw_negation_code_.kt"]
import afield.macros.*
rewrite { x: Expr -> from { neg(x) }; to { -x } }
println("neg(10) = " + neg(10))
```

## 5. Procedural unquote

<!-- field-box id="rw_unquote" index="4" -->

```edn [field-box id="rw_unquote" role="properties"]
{"id" "rw_unquote" "index" 4 "name" "Procedural unquote" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Procedural unquote" "_tombstone" false "frame" #afield/rect [60.0 620.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_unquote_code_.kt" "lines" 3 "characters" 148 "sha256" "d84e09aecd4fd75e921f45bbc7a2349547a8a30abc37ae426f509e167d9a9d57"}]}
```

```kotlin [field-property box="rw_unquote" name="code" path="rw_unquote_code_.kt"]
import afield.macros.*
rewrite { x: Ident -> from { rev(x) }; to { unquote { "\"" + x.reversed() + "\"" } } }
println("rev(hello) = " + rev(hello))
```

## 6. Precedence Guard

<!-- field-box id="rw_precedence" index="5" -->

```edn [field-box id="rw_precedence" role="properties"]
{"id" "rw_precedence" "index" 5 "name" "Precedence Guard" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Precedence Guard" "_tombstone" false "frame" #afield/rect [60.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_precedence_code_.kt" "lines" 3 "characters" 120 "sha256" "d9613d6fd60134af9a206fd2152de18358478305844e2c2ee8c6adb55327b8eb"}]}
```

```kotlin [field-property box="rw_precedence" name="code" path="rw_precedence_code_.kt"]
import afield.macros.*
rewrite { x: Expr -> from { twice(x) }; to { x * 2 } }
println("twice(3 + 4) = " + twice(3 + 4))
```

## 7. Variadic Hole

<!-- field-box id="rw_variadic" index="6" -->

```edn [field-box id="rw_variadic" role="properties"]
{"id" "rw_variadic" "index" 6 "name" "Variadic Hole" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Variadic Hole" "_tombstone" false "frame" #afield/rect [60.0 480.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_variadic_code_.kt" "lines" 7 "characters" 201 "sha256" "2c9cb4e01d8cf1d525f49ac9decc58d648fe38c06d37b35859f6f243c8413d3d"}]}
```

```kotlin [field-property box="rw_variadic" name="code" path="rw_variadic_code_.kt"]
import afield.macros.*
fun sink(vararg xs: Int) = xs.sum()
rewrite { first: Expr, rest: Vararg ->
    from { log(first, rest) }
    to { sink(first, rest) }
}
println("log(1, 2, 3) = " + log(1, 2, 3))
```

