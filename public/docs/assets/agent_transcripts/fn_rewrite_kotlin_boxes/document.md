# Field Document: fn_rewrite_kotlin_boxes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Host

<!-- field-box id="fnrw_host" index="0" -->

### Narrative

From `code`

An ordinary, idle box. Its only job is to own a live Kotlin engine that somebody else
can compile in. Executing it once is what brings that engine up.

```edn [field-box id="fnrw_host" role="properties"]
{"id" "fnrw_host" "index" 0 "name" "Host" "parents" ["root"] "children" ["fnrw_working"] "properties" {"children" #{#afield/box "fnrw_working"} "parents" #{#afield/box "root"} "name" "Host" "_tombstone" false "frame" #afield/rect [60.0 60.0 400.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fnrw_host_code_.kt" "lines" 3 "characters" 186 "sha256" "ae1f0977ff1f0c713f43958562103af09b6328a85adc64319a41b4bb1acd8da0"}]}
```

```kotlin [field-property box="fnrw_host" name="code" path="fnrw_host_code_.kt"]
// An ordinary, idle box. Its only job is to own a live Kotlin engine that somebody else
// can compile in. Executing it once is what brings that engine up.
println("host engine ready")
```

## 2. Two boxes - works

<!-- field-box id="fnrw_working" index="1" -->

### Narrative

From `code`

WORKS. `rewriteFn` names the box to compile the `@{...}` snippets in, and that box is
idle, so hydrating there replays nothing. One pass, and the sampled signal really moves.

```edn [field-box id="fnrw_working" role="properties"]
{"id" "fnrw_working" "index" 1 "name" "Two boxes - works" "parents" ["root" "fnrw_host"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fnrw_host"} "name" "Two boxes - works" "_tombstone" false "frame" #afield/rect [60.0 250.0 560.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fnrw_working_code_.kt" "lines" 31 "characters" 1546 "sha256" "f366d95883672cab6d1ddd546cff80457a1453f2ea66d67be5b7b5f7b9b64045"}]}
```

```kotlin [field-property box="fnrw_working" name="code" path="fnrw_working_code_.kt"]
// WORKS. `rewriteFn` names the box to compile the `@{...}` snippets in, and that box is
// idle, so hydrating there replays nothing. One pass, and the sampled signal really moves.
import afield.stdlib.Functions
import afield.stdlib.rewriteFn

val spec = listOf(
    "#fnrewrite",
    "sin/*/saw => op=triangle",
    "sin/**/[tag=number][value>0.15] => *= @{1.0 + depth * 0.08}",
    "**/[role=amplitude] => *= @{if (parentOp == \"sin\") 0.75 else 1.0}"
).joinToString("\n")

// The host comes from TOPOLOGY, not from a hard-coded id: whatever this box is connected
// beneath is where the `@{...}` snippets get compiled. Drag a different parent in and the
// rewrite follows it.
//
// NOT `?: error(...)`. Inside a box, `error` is `HasOutput.error(message, property, line)`,
// which returns Unit, so the elvis widens this to `Any` and the failure surfaces as a
// baffling "receiver type mismatch" on rewriteFn below. Throw explicitly.
val host = parents.lastOrNull()
    ?: throw IllegalStateException("connect this box under a box to compile the @{...} in")
println("compiling in parent: " + host.name)

with(Functions) {
    val base = (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0)
    val changed = host.rewriteFn(base, spec)
    println("before  f(1.0) = " + base.eval(1.0))
    println("after   f(1.0) = " + changed.eval(1.0))
    base.withName("before").plot(0.0..3.0, channel = "before", heightPx = 200, title = "before rewrite")
    changed.withName("after").plot(0.0..3.0, channel = "after", heightPx = 200, title = "after rewrite")
}
```

## 3. One box - does not work

<!-- field-box id="fnrw_broken" index="2" -->

### Narrative

From `code`

THE WRONG WAY, kept runnable so you can watch it refuse. `rewriteKotlin` takes its box
from `Box.context`, i.e. THIS box, so the first `@{...}` asks this engine to evaluate
while it is already mid-evaluation and the engine re-runs this body.

Unguarded that was 172 nested entries and 285 snippet compiles, ending in
Unable to instantiate class ...__code__81: java.lang.StackOverflowError
with the same 115-character snippet taking 1.58s, 2.14s, 2.69s as the chain grew.

The FnRewriteSelfBoxReentry guard now refuses the second hydration, which breaks the
recursion: this body runs exactly TWICE (watch for two "one box:" lines), the second run
dies at the first @{...}, and the rewrite then completes with the right answer. Look in
the log, channel `error,fn-rewrite`, for the refusal and the three ways out.

Twice is still once too many -- anything this body does before the rewrite happens twice,
which is why the box above is the form to copy. dev_docs/FN_REWRITE_SELF_BOX_REENTRY.md.

```edn [field-box id="fnrw_broken" role="properties"]
{"id" "fnrw_broken" "index" 2 "name" "One box - does not work" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "One box - does not work" "_tombstone" false "frame" #afield/rect [60.0 460.0 560.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fnrw_broken_code_.kt" "lines" 32 "characters" 1584 "sha256" "42a9619d9488a5b561d3a4fa9179bc53962c395d82d6261fcabbddfa1729ed8b"}]}
```

```kotlin [field-property box="fnrw_broken" name="code" path="fnrw_broken_code_.kt"]
// THE WRONG WAY, kept runnable so you can watch it refuse. `rewriteKotlin` takes its box
// from `Box.context`, i.e. THIS box, so the first `@{...}` asks this engine to evaluate
// while it is already mid-evaluation and the engine re-runs this body.
//
// Unguarded that was 172 nested entries and 285 snippet compiles, ending in
//   Unable to instantiate class ...__code__81: java.lang.StackOverflowError
// with the same 115-character snippet taking 1.58s, 2.14s, 2.69s as the chain grew.
//
// The FnRewriteSelfBoxReentry guard now refuses the second hydration, which breaks the
// recursion: this body runs exactly TWICE (watch for two "one box:" lines), the second run
// dies at the first @{...}, and the rewrite then completes with the right answer. Look in
// the log, channel `error,fn-rewrite`, for the refusal and the three ways out.
//
// Twice is still once too many -- anything this body does before the rewrite happens twice,
// which is why the box above is the form to copy. dev_docs/FN_REWRITE_SELF_BOX_REENTRY.md.
import afield.stdlib.Functions
import afield.stdlib.rewriteKotlin

val spec = listOf(
    "#fnrewrite",
    "sin/*/saw => op=triangle",
    "sin/**/[tag=number][value>0.15] => *= @{1.0 + depth * 0.08}",
    "**/[role=amplitude] => *= @{if (parentOp == \"sin\") 0.75 else 1.0}"
).joinToString("\n")

println("one box: body entered")

with(Functions) {
    val base = (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0)
    val changed = base.rewriteKotlin(spec)
    println("one box: f(1.0) = " + changed.eval(1.0) + " (correct, but the body ran twice)")
}
```

