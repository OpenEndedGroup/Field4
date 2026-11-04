# Field Document: fieldml_macro_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. FieldML — Field's source macros

<!-- field-box id="fieldml-macro-root" index="0" -->

```edn [field-box id="fieldml-macro-root" role="properties"]
{"id" "fieldml-macro-root" "index" 0 "name" "FieldML — Field's source macros" "parents" ["root"] "children" ["fieldml-macro-parameter" "fieldml-macro-drawing" "fieldml-macro-println" "fieldml-macro-expansion"] "properties" {"children" #{#afield/box "fieldml-macro-parameter" #afield/box "fieldml-macro-drawing" #afield/box "fieldml-macro-println" #afield/box "fieldml-macro-expansion"} "parents" #{#afield/box "root"} "name" "FieldML — Field's source macros" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 2. prop — a live, inheritable parameter

<!-- field-box id="fieldml-macro-parameter" index="1" -->

### Narrative

From `code`

A LIVE PARAMETER, WITH NO KOTLIN ANYWHERE.

`prop<Double>(swirl)` in a Kotlin box is a source macro: it generates
`var Box.swirl by Inheritance<Double>()` into the box's preamble. There is no
function to call, so FieldML implements the delegate instead — the same
breadth-first walk over `parents`, the same slot, the same box property the
inspector shows. A Kotlin box's `swirl` and this box's `prop "swirl"` are one
property.

EDIT EITHER NUMBER AND RE-RUN THIS BOX, then re-run the box below.

```edn [field-box id="fieldml-macro-parameter" role="properties"]
{"id" "fieldml-macro-parameter" "index" 1 "name" "prop — a live, inheritable parameter" "parents" ["root" "fieldml-macro-root"] "children" ["fieldml-macro-drawing"] "properties" {"children" #{#afield/box "fieldml-macro-drawing"} "parents" #{#afield/box "root" #afield/box "fieldml-macro-root"} "name" "prop — a live, inheritable parameter" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-macro-parameter_code_.kt" "lines" 27 "characters" 1146 "sha256" "00dc1734c8f6e7aeadf43660fef33e798e7c3d8d5a667c2b7ef02a1896c6c424"}]}
```

```kotlin [field-property box="fieldml-macro-parameter" name="code" path="fieldml-macro-parameter_code_.kt"]
// A LIVE PARAMETER, WITH NO KOTLIN ANYWHERE.
//
// `prop<Double>(swirl)` in a Kotlin box is a source macro: it generates
// `var Box.swirl by Inheritance<Double>()` into the box's preamble. There is no
// function to call, so FieldML implements the delegate instead — the same
// breadth-first walk over `parents`, the same slot, the same box property the
// inspector shows. A Kotlin box's `swirl` and this box's `prop "swirl"` are one
// property.
//
// EDIT EITHER NUMBER AND RE-RUN THIS BOX, then re-run the box below.
let _ = setProp "swirl" 0.85
let _ = setProp "turns" 4

// `once` is the other generated delegate — get, or compute and store. Re-running
// this box does not change it, because the slot is no longer empty.
let firstRun =
  once "firstRunAt" (fun ignored ->
    hostClass "afield.runtime.lifecycle.AnimationClock" |> hostGet "INSTANCE" |> hostCall0 "nowSeconds"
  )

{
  swirl = prop "swirl" |> hostAsFloat
  turns = prop "turns" |> hostAsInt
  firstRunAt = firstRun |> hostAsFloat
  // `propOr` is the read that does not fail when nothing up the graph has it.
  missing = propOr "neverWritten" "not set" |> hostAsString
}
```

## 3. the inherited parameter, drawn

<!-- field-box id="fieldml-macro-drawing" index="2" -->

### Narrative

From `code`

THE SAME PARAMETER, INHERITED.

This box never writes `swirl`. It reads it, and the read walks up `parents`
until an ancestor has it — which is what makes `prop` worth having over a
plain variable. Run the box above with a different number and run this one
again; nothing here changes.

One thing about the wiring, because it is not guessable: this box has TWO parents.
`swirl` comes down from the box above, but the FieldML runtime is only applied to the
direct* children of a FieldML root (LanguageRuntimeSupport.kt:57), so a grandchild
would quietly run as Kotlin. Being a child of both is what makes it FieldML *and*
an inheritor.

```edn [field-box id="fieldml-macro-drawing" role="properties"]
{"id" "fieldml-macro-drawing" "index" 2 "name" "the inherited parameter, drawn" "parents" ["root" "fieldml-macro-parameter" "fieldml-macro-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-macro-parameter" #afield/box "fieldml-macro-root"} "name" "the inherited parameter, drawn" "_tombstone" false "frame" #afield/rect [400.0 170.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-macro-drawing_code_.kt" "lines" 25 "characters" 1063 "sha256" "20da1b2621bec3c368253e65ae17a6fd62ffe68fcd3a070347ce26b9eeea248c"}]}
```

```kotlin [field-property box="fieldml-macro-drawing" name="code" path="fieldml-macro-drawing_code_.kt"]
// THE SAME PARAMETER, INHERITED.
//
// This box never writes `swirl`. It reads it, and the read walks up `parents`
// until an ancestor has it — which is what makes `prop` worth having over a
// plain variable. Run the box above with a different number and run this one
// again; nothing here changes.
//
// One thing about the wiring, because it is not guessable: this box has TWO parents.
// `swirl` comes down from the box above, but the FieldML runtime is only applied to the
// *direct* children of a FieldML root (LanguageRuntimeSupport.kt:57), so a grandchild
// would quietly run as Kotlin. Being a child of both is what makes it FieldML *and*
// an inheritor.
let swirl = prop "swirl" |> hostAsFloat
let turns = prop "turns" |> hostAsInt

linspace 0.0 1.0 320
  |> map (fun t ->
       polar (t * tau * toFloat turns) (10.0 + t * 105.0 * swirl) + vec2 125.0 125.0
     )
  |> fold (fun line point -> line |> lineToV point) fline
  |> stroked true
  |> filled false
  |> color (vec4 0.42 0.86 1.0 1.0)
  |> thickness 2.0
  |> drawSized "main" 250.0 250.0
```

## 4. hostMacro — expand, then call

<!-- field-box id="fieldml-macro-println" index="3" -->

### Narrative

From `code`

FIELD'S OWN `println` MACRO, RUN FROM FIELDML.

`println(x)` in a Kotlin box is a source macro too: it rewrites to
`_printHere(__line__+1, x)` — an ordinary `Box` extension function. So FieldML
does not need a Kotlin compiler to reach it. It needs the *expansion* (which
is where a macro does its work) and then a reflective call, and the host
bridge already had the second half.

The line below really goes through Field's macro. The number is the line the
output claims to come from — change it and watch the gutter marker move.

```edn [field-box id="fieldml-macro-println" role="properties"]
{"id" "fieldml-macro-println" "index" 3 "name" "hostMacro — expand, then call" "parents" ["root" "fieldml-macro-root"] "children" ["fieldml-macro-expansion"] "properties" {"children" #{#afield/box "fieldml-macro-expansion"} "parents" #{#afield/box "root" #afield/box "fieldml-macro-root"} "name" "hostMacro — expand, then call" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-macro-println_code_.kt" "lines" 20 "characters" 1055 "sha256" "8b2bf5755a1ef814ba4e5b6c1b41d7c6fe4b49581164b84a4a5c2d9ed3219b88"}]}
```

```kotlin [field-property box="fieldml-macro-println" name="code" path="fieldml-macro-println_code_.kt"]
// FIELD'S OWN `println` MACRO, RUN FROM FIELDML.
//
// `println(x)` in a Kotlin box is a source macro too: it rewrites to
// `_printHere(__line__+1, x)` — an ordinary `Box` extension function. So FieldML
// does not need a Kotlin compiler to reach it. It needs the *expansion* (which
// is where a macro does its work) and then a reflective call, and the host
// bridge already had the second half.
//
// The line below really goes through Field's macro. The number is the line the
// output claims to come from — change it and watch the gutter marker move.
let _ = hostMacro "println" "(x)" (4, "this line came out of Field's println macro")

// A record of arguments is a *named* call, which is the only shape that can
// leave a Kotlin default unwritten. `_printlnHere(line, vararg a)` with only
// `line` named prints an empty line.
let _ = hostMacro "println" "(x)" {line = 5}

// And the expansion itself, which is what routed both of those.
let expansion = hostMacroExpand "println" "(x)"
{ rewritesTo = expansion.call, callSite = expansion.here }
```

## 5. hostMacroExpand — the generated Kotlin, as text

<!-- field-box id="fieldml-macro-expansion" index="4" -->

### Narrative

From `code`

THE ESCAPE HATCH, AND WHERE IT STOPS.

`hostMacroExpand` runs any registered macro's expansion against this box and
hands the rewrite back as a typed record — so a macro's generated Kotlin is
readable from FieldML even when it is not runnable. `.declarations` below is
the exact text `prop<Double>(speed)` would have put in a Kotlin box.

```edn [field-box id="fieldml-macro-expansion" role="properties"]
{"id" "fieldml-macro-expansion" "index" 4 "name" "hostMacroExpand — the generated Kotlin, as text" "parents" ["root" "fieldml-macro-println" "fieldml-macro-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-macro-println" #afield/box "fieldml-macro-root"} "name" "hostMacroExpand — the generated Kotlin, as text" "_tombstone" false "frame" #afield/rect [760.0 170.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-macro-expansion_code_.kt" "lines" 20 "characters" 893 "sha256" "eefc447a9bbc99ed29ee77d2cc82025f50e362fd8e0a3fbb23457ca30ec6c100"}]}
```

```kotlin [field-property box="fieldml-macro-expansion" name="code" path="fieldml-macro-expansion_code_.kt"]
// THE ESCAPE HATCH, AND WHERE IT STOPS.
//
// `hostMacroExpand` runs any registered macro's expansion against this box and
// hands the rewrite back as a typed record — so a macro's generated Kotlin is
// readable from FieldML even when it is not runnable. `.declarations` below is
// the exact text `prop<Double>(speed)` would have put in a Kotlin box.
let asProp = hostMacroExpand "prop" ("<Double>", "(speed)")

// `sourceBlock` is the honest bound. It *is* call-shaped, and still unreachable:
// the callee is a constructor taking a lambda whose body is the original Kotlin.
// So `.call` is empty, and the reason is visible in `.here` rather than asserted
// in a comment.
let asBlock = hostMacroExpand "sourceBlock" "{ 2 + 2 }"

{
  propGenerates = asProp.declarations
  propCallSiteBecomes = asProp.here
  blockCallSiteBecomes = asBlock.here
  blockIsNotCallable = asBlock.call == ""
}
```

