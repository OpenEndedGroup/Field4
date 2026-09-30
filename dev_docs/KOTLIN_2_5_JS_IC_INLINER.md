# Kotlin 2.5 JS: incremental builds vs the klib IR inliner

From the Kotlin 2.5.0-Beta1 uplift (`d0569e108`) an incremental `compileKotlinJs` could die after an
ordinary edit, while a clean build of the same source was green:

    e: org.jetbrains.kotlin.backend.common.CompilationException: Back-end: Please report this problem
    …/src/jsMain/kotlin/afield/Main.kt:-1:-1
    Details: Internal error in file lowering: java.lang.IllegalStateException:
      IrClassSymbolImpl is unbound. Signature: kotlin/Unit|null[0]

It hit the everyday loop: edit `Main.kt`, rebuild. The pre-commit guard, which compiles
incrementally, refused commits on it.

## Cause

The stack runs through `WebKlibInliningPipelinePhase`, the first-stage (pre-serialization) klib IR
inliner, inside nested `IrInlinedFunctionBlock`s. At language 2.5 `-Xklib-ir-inliner` defaults to
inlining within the module (`default` picks the mode from the language version). The landing moved
Field to language 2.5, which is why this is new. Under incremental compilation only the edited file is
recompiled, and the inliner then has to take the IR of an *unchanged* file's inline function from the
IC cache, which it cannot do.

## Reproduction, stock Kotlin 2.5.0-Beta1, no Field

[`repro/kotlin-2.5-js-ic-inliner/`](repro/kotlin-2.5-js-ic-inliner/) is two files: `A.kt` declares
inline functions (a loop, a `when`, one inline nested in another), and `Main.kt` calls them.
`toggle.py` does a clean build, then six incremental rebuilds, each alternating one edit in
`Main.kt` and checking that the file really changed:

| mode | incremental rebuilds that failed |
|---|---|
| default | **6 of 6**, `Cannot deserialize inline function from a non-Kotlin library: FUN IR_EXTERNAL_DECLARATION_STUB name:visitAll … KlibIcDeserializedContainerSource` |
| `-Xklib-ir-inliner=disabled` | **0 of 6** |

The minimal project fails with a different message than Field (the cached inline function cannot be
deserialized at all, rather than deserializing with `Unit` unbound), but it is the same phase and the
same step. A one-file version does not reproduce: an inline function must come from another file.

## Field's mitigation

`build.gradle.kts` passes `-Xklib-ir-inliner=disabled` to `KotlinJsCompile` only. Incremental JS stays
on. On Field itself: a fresh JS build, then four incremental rebuilds alternating `Main.kt` between
`30b411421`'s version and the fixed one: 0 of 4 failed. Before the flag, the same edit failed 2 of 2.
Inline functions are still inlined, by the JS backend's own later inliner. What is off is the
experimental first-stage one.

Neither the JS target nor the inliner mode avoids it: with `target = "es2020"` the default inliner still
failed 3 of 6, and `-Xklib-ir-inliner=full` failed 3 of 6. The only way to keep the first-stage inliner is
`kotlin.incremental.js=false`, a full JS recompile on every edit.

Remove the flag when a Kotlin 2.5 release passes `toggle.py default`. It has not been reported
upstream yet; the repro above is what a report needs.
