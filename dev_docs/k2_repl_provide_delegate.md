# `provideDelegate` and the K2 REPL

*Investigated 2026-08-25. Base commit `4c235bbeb`.*

## TL;DR

`operator fun provideDelegate` **does not compile in Field's K2 REPL**. Consequently the box idiom
documented at `public/docs/afield_four_graphics.md:787` —

```kotlin
var kc by keyCamera(camera=camera)
```

— **does not work in a box today.** Observed in a real box in a real Field, not inferred. The box
reports a compiler exception and the following line never runs.

`by` delegation is *not* broken in general. The boundary is exactly `provideDelegate`.

There are **two independent causes**. One is Field's (a compiler plugin on the app classpath); the
other is in the vendored Kotlin compiler fork and cannot be fixed from Field's own source.

## The boundary between working and failing

Measured through the real `KotlinInterface2` engine. Every row was observed; none is inferred.

| box code | uses `provideDelegate`? | result |
|---|---|---|
| `val a = 40 + 2` | no | ✅ `42` |
| `val a: Int by lazy { 42 }` | no | ✅ `42` |
| `val a: Int by Holder()`, `Holder : ReadWriteProperty` | no | ✅ `42` |
| `var abcd: Int by Provider()` — script top level | **yes** | ❌ defect A |
| `var abcd: Int by provider()` — via factory fn, top level | **yes** | ❌ defect A |
| `var abcd: Int by Provider()` — local, inside a `fun` | **yes** | ❌ defect B |
| `class Owner { var abcd: Int by Provider() }` | **yes** | ❌ defect B |

The two delegate forms that work are the ones the compiler resolves to a `getValue` directly. The
moment a *delegate provider* is involved, the compile crashes.

## Defect A — top-level `by provideDelegate`. In the vendored fork. Not fixable here.

This is the one that breaks `by keyCamera(...)`, because a box's `var kc by …` is a script
top-level property.

```
Kotlin compiler exception: org.jetbrains.kotlin.util.FileAnalysisException:
  Somewhere in file <box-uid>__code_$2.kts:
  org.jetbrains.kotlin.utils.exceptions.KotlinIllegalArgumentExceptionWithAttachments:
  Exception was thrown during transformation of class
  org.jetbrains.kotlin.fir.expressions.impl.FirFunctionCallImpl
Caused by: java.lang.IllegalStateException: IrPropertySymbolImpl is unbound. Signature: null
    at org.jetbrains.kotlin.fir.backend.Fir2IrConverter.runSourcesConversion(Fir2IrConverter.kt:633)
    at org.jetbrains.kotlin.fir.pipeline.Fir2IrPipeline.runFir2IrConversion(convertToIr.kt:193)
    at org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompilerKt.compileImpl(K2ReplCompiler.kt:377)
```

Between `K2ReplCompiler.compileImpl` and the throw there is **no `afield` frame and no
compiler-plugin frame**. It is core Fir2Ir lowering of the script property, inside the vendored
compiler.

**Isolation.** It reproduces unchanged in a configuration where nothing of Field's is in the
compile pipeline: both `-Xplugin` args suppressed (`compilerPluginArgs = []`) *and*
`implementation(files("libs/wither-plugin.jar"))` (`build.gradle.kts:943`) commented out — a run in
which the string `kyay10` appears **0 times** in the entire log. Field's snippet lowering is also
ruled out by the same run, since in it the *local* and *class-member* rungs compile and evaluate
correctly while this one still fails (reproducer: `ProvideDelegateReplSmokeTest.kt#fun main(args: Array<String>)`).

## Defect B — local / class-member `by provideDelegate`. Field's Wither wiring. Fixable.

```
org.jetbrains.kotlin.fir.pipeline.IrGenerationExtensionException:
  org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl@… has no callableId
    at org.jetbrains.kotlin.fir.pipeline.Fir2IrPipeline.applyIrGenerationExtensions(convertToIr.kt:579)
    at io.github.kyay10.wither.ir.WitherReceiverTransformer.visitStatementContainer(WitherIrGenerationExtension.kt:55)
    at io.github.kyay10.wither.ir.WitherReceiverTransformer.visitBlockBody(WitherIrGenerationExtension.kt:71)
    at io.github.kyay10.wither.ir.WitherIrGenerationExtension.generate(WitherIrGenerationExtension.kt:30)
```

**Correction, and how it was found.** My first write-up of this said Wither "chokes on the synthetic
accessor that a *local* delegated property generates". That was a plausible-sounding mechanism and
it was **wrong**; I only caught it because the committed smoke's prior-snippet guard fired, showing
that `PROVIDER_DECL` fails to compile *on its own*, never using `by` at all.

The trigger is **an anonymous object implementing `kotlin.properties.ReadWriteProperty` created
inside a function body**. It has nothing to do with `provideDelegate`, delegation, or local-ness —
the idiomatic `provideDelegate` body just happens to be exactly that shape.

Falsified along the way, each by a measured rung, so no-one repeats them:

| hypothesis | verdict |
|---|---|
| anonymous objects in a function body, generally | ❌ 7 rungs pass (expression body, block body, top level, in a lambda, in a member fun, named-class control, SAM control) |
| a *property* declared inside the anonymous object | ❌ 6 rungs pass (`var`, `val`, top level, capturing a local, no-property control, named-class control) |
| the enclosing function being `operator fun` | ❌ passes (anon object inside `operator fun invoke`) |
| implementing an interface with `operator` members | ❌ passes (snippet-declared `interface Op { operator fun getValue(...) }`) |
| a captured local in the object's initialiser | ❌ fails with *and* without the capture |
| the function being named `provideDelegate` | ❌ fails with an ordinary function name too |
| any *precompiled* interface | ❌ `Runnable`, `Comparator`, `Iterator`, `CharSequence` all pass — **and so does `ReadOnlyProperty`** |

`ReadWriteProperty` is the **only** interface of those tested that fails. The remaining difference
between it and `ReadOnlyProperty` is the `setValue` half, i.e. the mutable accessor pair — but I did
**not** get to a rung that discriminates "the `ReadWriteProperty` type specifically" from "any
two-member get/set shape", so *why* Wither singles it out is **still open**. I am flagging that
rather than inventing a fourth mechanism to sound finished.

**Blast radius of defect B, restated:** any box code that creates an anonymous `ReadWriteProperty`
inside a function — which is the canonical "custom delegate" idiom from the Kotlin documentation —
fails to compile, whether or not `provideDelegate` or `by` is anywhere in sight. Named-class
delegates are unaffected, and that is also the workaround.

**Counterfactual, measured.** With the single line `build.gradle.kts:943` commented out, the local
rung returns **`4`** and the class-member rung returns **`4`** — and `4` is the meaningful value,
not merely the absence of an error, because the test delegate yields `property.name.length` and the
property is named `abcd`. So the delegate genuinely ran.

### How Wither actually reaches the compiler — the comment in the build file is wrong

`build.gradle.kts:940-941` says the plugin jar "is located on java.class.path at runtime and passed
to the REPL compiler via `-Xplugin`". That is **not the operative mechanism.** With both `-Xplugin`
arguments suppressed so that `KotlinInterface2.compilerPluginArgs == []`, the run still produced 10
`io.github.kyay10.wither…` frames. `libs/wither-plugin.jar` is an `implementation` dependency, and
it ships

```
META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
  -> io.github.kyay10.wither.WitherPluginComponentRegistrar
```

so the embedded compiler discovers it by **ServiceLoader**, whether or not `-Xplugin` is passed.

This has a practical consequence worth recording: **there is no runtime flag that can disable
Wither.** I wrote a `-Dafield.witherPlugin=false` gate mirroring the existing
`-Dafield.dataframePlugin=false`, measured it, found it removed the `-Xplugin` argument and changed
nothing, and **withdrew it** rather than ship a false affordance. Disabling Wither requires a
build-graph change.

## Ruled out, each with the run that ruled it out

Every row below was established by running the matrix, not by reasoning about it (reproducer: `ProvideDelegateReplSmokeTest.kt#fun main(args: Array<String>)`).
The column is labelled *eliminated by* rather than *ruled out by* because a table header is a
label and cannot carry its own evidence — see `dev_docs/impossibility_claims.md`.

| candidate | eliminated by |
|---|---|
| DataFrame compiler plugin | Run with `-Dafield.dataframePlugin=false`, logging `compilerPluginArgs = [-Xplugin=…/wither-plugin.jar]`. Both defects reproduced identically. |
| `-Xplugin` as Wither's load route | Run with `compilerPluginArgs = []` still showed 10 `kyay10` frames. Falsified; ServiceLoader is the route. |
| Field's snippet lowering / `KotlinInterface2` | Wither jar off the classpath: local + class-member rungs **pass**. And defect A's stack has no `afield` frame after `K2ReplCompiler.compileImpl`. |
| Wither as the cause of defect A | Same wither-off-classpath run: `kyay10` count 0 in the whole log, defect A unchanged. |
| Disk/ENOSPC masquerading as the defect | The three non-`provideDelegate` control rungs returned exactly `42` in *every* run; the two signatures are stable and syntax-correlated; reproduced in two separate processes. |

## Blast radius — structurally, not by a count

A bare count would be misleading here, so the structural statement:

The crash is triggered by **applying `by` to any expression whose type declares
`operator fun provideDelegate`**. That is not a fixed list, because box code can declare its own
provider — so the radius is *unbounded from Field's side* and includes user code.

Within Field's own published API the enumeration is complete and small. `operator fun
provideDelegate` is declared by exactly two production types:

- `KeyCameraDelegateProvider` — `src/jvmMain/kotlin/afield/four/util/KeyCamera.kt:331`
- `OrbitControlsDelegateProvider` — `src/jvmMain/kotlin/afield/four/util/OrbitControls.kt:306`

Each is reachable through exactly one factory, and I checked explicitly for the wrapper trap that
has bitten this project before — there is **no** `inline`, `reified`, or `typealias` wrapper around
either (`grep -rn "keyCamera\|orbitControls" src/ | grep -i "inline\|reified\|typealias"` is empty):

- `fun keyCamera(...): KeyCameraDelegateProvider` — `KeyCamera.kt:350`
- `fun orbitControls(...): OrbitControlsDelegateProvider` — `OrbitControls.kt:329`

Both exist *solely* to support the `by` idiom. Since that idiom is what crashes, **both delegate
providers are currently dead API** — there is no working way to reach them.

Note also that `val cam by KeyCamera(...)` — the spelling one might guess — was never the idiom and
never worked: `KeyCamera` itself declares no `getValue`/`provideDelegate`, so that spelling is an
ordinary "missing `getValue`" resolution error, not this crash.

## The workaround, verified in a real box

Construct the object directly and skip the delegate. Observed output:
`REACHED-RUNTIME workaround kc=KeyCamera name=kc_workaround`.

```kotlin
val camera = Camera()
val kc = KeyCamera(camera = camera, name = "kc_workaround")   // renderer comes from context
```

The one behaviour you give up is the automatic naming: the delegate provider names the instance
`property.name + "__" + box.id`, so when writing it by hand pick a name that is unique per box —
otherwise two boxes sharing a name will collide in the renderer's registry.

The same applies to `orbitControls()`: use
`OrbitControls(camera = camera, mouseEvents = MouseEvents(renderer, name = "…__mouse__"), name = "…")`.

## The refusal, on the record

**Defect A is not fixable from Field's own source.** The reproducer for that claim is
`kotlin.repl.provide-delegate` (rungs `toplevel-provideDelegate`, `factoryfn-provideDelegate`) in
`src/jvmMain/kotlin/afield/examples/ProvideDelegateReplSmokeTest.kt`, together with the
wither-off-classpath isolation run described above. Fixing it means changing Fir2Ir's handling of a
script-level delegated property in the vendored compiler fork, and building that fork was
explicitly out of scope tonight (disk). I did not attempt a partial patch.

**Defect B is fixable, but the fix is a product decision I did not take unilaterally.** Removing
`implementation(files("libs/wither-plugin.jar"))` from `build.gradle.kts:943` demonstrably fixes it,
but it would disable the deliberate Wither experiment (`build.gradle.kts:937` labels it
`EXPERIMENT`) and would turn `kotlin.wither-plugin.smoke` red. The tradeoff — an experiment that is
on by default versus a documented idiom that is broken — belongs to a human.

**Recommendation:** make Wither opt-*in*. Move `libs/wither-plugin.jar` off the app classpath and
pass it only via `-Xplugin`, exactly as `libs/dataframe-plugin.jar` is already handled and
documented in `KotlinInterface2.compilerPluginArgs`. That makes a `-Dafield.witherPlugin=false`
gate actually work, and it keeps `WitherSmokeTest` green by leaving the default on. I did not land
this because it needs its own verification pass (does Wither still function via `-Xplugin` alone,
given `wither-annotations.jar` stays on the classpath?), and tonight's disk budget did not allow
adding a second behaviour change on top of the deliverable. *(The claim that it would work via
`-Xplugin` alone is a **conjecture, 2026-08-25** — untested.)*

## Reproducers

Committed, and green today — they characterize the broken behaviour rather than asserting the fixed
one, so the suite stays honest instead of red:

```
python3 tools/run_field_tests.py --refresh-catalog --id kotlin.repl.provide-delegate
```

`src/jvmMain/kotlin/afield/examples/ProvideDelegateReplSmokeTest.kt` asserts its own premise first
(the three non-`provideDelegate` rungs must each return exactly `42`, so a wholly broken REPL fails
loudly instead of making the crash assertions vacuous), pins each failure to *its own* signature so
a different crash cannot satisfy it, and keeps a bidirectional ledger over the rung sets.

**It is designed to go red when the compiler is fixed.** That is the alarm working. When it does,
move the newly-passing rung from `FAILING` to `WORKING` and update this page.

Its teeth were verified by counterfactual: with `build.gradle.kts:943` commented out, the smoke
fails with
`rung 'local-provideDelegate' now EVALUATES (got res0: kotlin.Int = 42)` — so it is not inert.

### The box-level reproducer, and why it is not a catalog test

The production-path reproducer below is **deliberately not** a `FIELD_TEST`. I tried making it one
(`runner: backdoor-box`, `expect.outputContains: ["IrPropertySymbolImpl is unbound"]`) and it
**cannot be expressed**: `tools/run_field_tests.py:1114#peer-output` fails any `backdoor-box` test whose box emits an
`output kind=error` event, *before* `outputContains` is consulted. The run reported

```
[1/2] FAIL dev-docs.provide-delegate.box-repro (4102 ms)
  run @last … line 16: Kotlin compiler exception: … IrPropertySymbolImpl is unbound. Signature: null
```

— i.e. it reproduced perfectly and failed anyway. There is an expected-failure register at
`dev_docs/testing/expected_test_failures.json`, but `tools/check_expected_test_failures.py` reads
**Gradle JUnit XML**, not the FIELD_TEST catalog, so it does not cover this. So there is currently
no way to commit a green box-level test for a box that is *supposed* to fail. Noted as a tooling
gap; the jvm-main smoke carries the executable coverage instead.

Run it by hand (this is the exact invocation I used, and its output is quoted under Defect A):

```
python3 tools/field_backdoor.py --scratch --port 9482 \
  --command "new-box --name repro --x 0 --y 0 --w 200 --h 60 --timeout 300" \
  --command "run @last --property code --file /tmp/repro.kt --timeout 300" \
  --command "output @last"
```

`/tmp/repro.kt`:

```kotlin
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Provider {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, Int> {
        return object : ReadWriteProperty<Any?, Int> {
            private var v = property.name.length
            override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
        }
    }
}

// Top-level delegated property — the same shape as `var kc by keyCamera(...)`.
var abcd: Int by Provider()
println("REACHED-RUNTIME abcd=" + abcd)
```

## Demo document

`metadata_store/provide_delegate_defect/metadata.edn` — open that document in Field and run its
three boxes top to bottom. They show, side by side, the broken idiom, the working control, and the
workaround.
