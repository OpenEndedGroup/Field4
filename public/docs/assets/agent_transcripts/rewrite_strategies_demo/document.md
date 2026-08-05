# Field Document: rewrite_strategies_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 9 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Baseline (global fixpoint)

<!-- field-box id="rw_baseline" index="0" -->

```edn [field-box id="rw_baseline" role="properties"]
{"id" "rw_baseline" "index" 0 "name" "Baseline (global fixpoint)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Baseline (global fixpoint)" "_tombstone" false "frame" #afield/rect [60.0 60.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_baseline_code_.kt" "lines" 6 "characters" 247 "sha256" "ff4378329971eefb62525fba4bb9995e0bba7c19f13b61db20e939b0b0e60f99"}]}
```

```kotlin [field-property box="rw_baseline" name="code" path="rw_baseline_code_.kt"]
fun p(v: Int) = v
fun A(v: Int) = v * 10
rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
val r = p(1) + p(2)
println("baseline (global fixpoint): r = $r  -> both p() rewritten to A(1)+A(2)")
check(r == 30) { "baseline expected 30, got $r" }
```

## 2. bottomup (inner)

<!-- field-box id="rw_bottomup" index="1" -->

```edn [field-box id="rw_bottomup" role="properties"]
{"id" "rw_bottomup" "index" 1 "name" "bottomup (inner)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "bottomup (inner)" "_tombstone" false "frame" #afield/rect [400.0 260.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_bottomup_code_.kt" "lines" 7 "characters" 269 "sha256" "bf5f22c37b957dcc6404871e79a7f3e7d9efc6e7595196f5c4e186c39481e641"}]}
```

```kotlin [field-property box="rw_bottomup" name="code" path="rw_bottomup_code_.kt"]
fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { bottomup("fg") }
val r = f(f(1))
println("bottomup: r = $r  -> inner f rewritten: f(g(1)) = f(101) = 202")
check(r == 202) { "bottomup expected 202, got $r" }
```

## 3. topdown (outer)

<!-- field-box id="rw_topdown" index="2" -->

```edn [field-box id="rw_topdown" role="properties"]
{"id" "rw_topdown" "index" 2 "name" "topdown (outer)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "topdown (outer)" "_tombstone" false "frame" #afield/rect [60.0 260.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_topdown_code_.kt" "lines" 7 "characters" 264 "sha256" "50c5329fa6458442f3580d5ad6db5ca5151d8de9b61480ad634d2ce4f865292b"}]}
```

```kotlin [field-property box="rw_topdown" name="code" path="rw_topdown_code_.kt"]
fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { topdown("fg") }
val r = f(f(1))
println("topdown: r = $r  -> outer f rewritten: g(f(1)) = g(2) = 102")
check(r == 102) { "topdown expected 102, got $r" }
```

## 4. repeat (bounded)

<!-- field-box id="rw_repeat" index="3" -->

```edn [field-box id="rw_repeat" role="properties"]
{"id" "rw_repeat" "index" 3 "name" "repeat (bounded)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "repeat (bounded)" "_tombstone" false "frame" #afield/rect [400.0 470.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_repeat_code_.kt" "lines" 7 "characters" 307 "sha256" "f63bde9b42245fcbccababf5ac5747aaca9023d7cfcc0c5b75d59986edf826fe"}]}
```

```kotlin [field-property box="rw_repeat" name="code" path="rw_repeat_code_.kt"]
fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { repeat(topdown("fg"), 2) }
val r = f(f(f(1)))
println("repeat(topdown, 2): r = $r  -> two outer steps, inner f(1) left: g(g(f(1))) = 202")
check(r == 202) { "repeat bounded expected 202, got $r" }
```

## 5. then (sequence)

<!-- field-box id="rw_seq" index="4" -->

```edn [field-box id="rw_seq" role="properties"]
{"id" "rw_seq" "index" 4 "name" "then (sequence)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "then (sequence)" "_tombstone" false "frame" #afield/rect [740.0 260.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_seq_code_.kt" "lines" 9 "characters" 325 "sha256" "4b38200fe78747a673207eefd300ce321a25719e7f193bf33b759a78b81cfdd6"}]}
```

```kotlin [field-property box="rw_seq" name="code" path="rw_seq_code_.kt"]
fun p(v: Int) = v
fun q(v: Int) = v
fun r0(v: Int) = v * 1000
rule("p2q") { x: Expr -> from { p(x) }; to { q(x) } }
rule("q2r") { x: Expr -> from { q(x) }; to { r0(x) } }
strategy { "p2q" then "q2r" }
val out = p(5)
println("then: out = $out  -> p -> q -> r0(5) = 5000")
check(out == 5000) { "then expected 5000, got $out" }
```

## 6. choice (first applicable)

<!-- field-box id="rw_choice" index="5" -->

```edn [field-box id="rw_choice" role="properties"]
{"id" "rw_choice" "index" 5 "name" "choice (first applicable)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "choice (first applicable)" "_tombstone" false "frame" #afield/rect [60.0 470.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_choice_code_.kt" "lines" 9 "characters" 361 "sha256" "c05d877dc3bb3be6ab388f896c1a5586adf2d6a31ddd5f442d14d1c94fe8cfef"}]}
```

```kotlin [field-property box="rw_choice" name="code" path="rw_choice_code_.kt"]
fun pick(v: Int) = v
fun X(v: Int) = v + 1
fun Y(v: Int) = v + 2
rule("toX") { x: Expr -> from { pick(x) }; to { X(x) } }
rule("toY") { x: Expr -> from { pick(x) }; to { Y(x) } }
strategy { choice("toY", "toX") }
val r = pick(10)
println("choice: r = $r  -> prefers toY though toX is declared first: Y(10) = 12")
check(r == 12) { "choice expected 12, got $r" }
```

## 7. subset (name one rule)

<!-- field-box id="rw_subset" index="6" -->

```edn [field-box id="rw_subset" role="properties"]
{"id" "rw_subset" "index" 6 "name" "subset (name one rule)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "subset (name one rule)" "_tombstone" false "frame" #afield/rect [740.0 60.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_subset_code_.kt" "lines" 10 "characters" 352 "sha256" "d79ea08eb15fd7a3900a681d5605f938c60f9809853e135d49a3005e218d7a43"}]}
```

```kotlin [field-property box="rw_subset" name="code" path="rw_subset_code_.kt"]
fun p(v: Int) = v
fun q(v: Int) = v
fun A(v: Int) = v * 10
fun B(v: Int) = v * 100
rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
rule("q2b") { x: Expr -> from { q(x) }; to { B(x) } }
strategy { "p2a" }
val r = p(1) + q(2)
println("subset: r = $r  -> only p2a fires: A(1) + q(2); q2b left alone")
check(r == 12) { "subset expected 12, got $r" }
```

## 8. once (single site)

<!-- field-box id="rw_once" index="7" -->

```edn [field-box id="rw_once" role="properties"]
{"id" "rw_once" "index" 7 "name" "once (single site)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "once (single site)" "_tombstone" false "frame" #afield/rect [400.0 60.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_once_code_.kt" "lines" 7 "characters" 256 "sha256" "a556f67fbdf54aa1b31aaec84306ff218e51be00ed855edb6293ea5990bc1142"}]}
```

```kotlin [field-property box="rw_once" name="code" path="rw_once_code_.kt"]
fun p(v: Int) = v
fun A(v: Int) = v * 10
rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
strategy { once("p2a") }
val r = p(1) + p(2)
println("once: r = $r  -> only the first p() rewritten: A(1) + p(2)")
check(r == 12) { "once expected 12, got $r" }
```

## 9. nested pattern (two levels deep)

<!-- field-box id="rw_nested" index="8" -->

```edn [field-box id="rw_nested" role="properties"]
{"id" "rw_nested" "index" 8 "name" "nested pattern (two levels deep)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "nested pattern (two levels deep)" "_tombstone" false "frame" #afield/rect [740.0 470.0 230.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rw_nested_code_.kt" "lines" 8 "characters" 307 "sha256" "155a5e7223ffbc6b3e897ae57b0db994387108f7ea91ee86c5dc5c3911174fde"}]}
```

```kotlin [field-property box="rw_nested" name="code" path="rw_nested_code_.kt"]
fun f(v: Int) = v * 2
fun g(v: Int) = v + 100
rule("fg") { x: Expr -> from { f(f(x)) }; to { g(x) } }
strategy { repeat(topdown("fg"), 2) }
val r = f(f(f(f(1))))
println("nested pattern: r = $r  -> each step eats TWO f's: g(g(1)) = g(101) = 201")
check(r == 201) { "nested pattern expected 201, got $r" }
r
```

