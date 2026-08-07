# FieldML's JVM host bridge — design notes

The user-facing story is `public/docs/fieldml_host_bridge.md`. This is the part a reader who is
about to change the code needs: why it is shaped the way it is, which decisions are load-bearing,
and which holes are known and deliberate.

## What was wrong

FieldML gained recursion, list combinators, vector arithmetic, `draw` and `animate` in `d0625e477`
— it became capable *as a language*. What it could not do was reach the host:

- `hostCall0`/`hostCall1`/`hostCall2` — two arguments maximum. `hostNew0` — a no-argument
  constructor only.
- No way to pass a FieldML function where the JVM wants a lambda, which rules out essentially every
  Kotlin builder in Field.
- Every host result typed `THost("Host")`. From the first host call onwards the type checker was
  blind, so nothing downstream inferred, a misspelled member was a run-time crash, and completion
  had nothing to offer.
- `callHostMethod` took the *first* name-and-arity match out of `Class.getMethods`. Field's Kotlin
  surface is full of same-named members differing only in numeric width, so a call with a working
  target could still fail.

`FieldMLHostSurfaceCoverageTest` measures the result: 344 of 362 public methods of a fixed set of
real Field types are callable now (95.0%), against 166 (45.9%) before.

## Where things live

| File | Responsibility |
| --- | --- |
| `afield/ml/core/HostBridge.kt` | Candidates, scored overload resolution, coercion, SAM proxies, member read/write. Everything reflective at *run* time. |
| `afield/ml/type/HostTypes.kt` | JVM types → FieldML types, member and overload resolution at *type-check* time, the member lists completion uses. |
| `afield/ml/type/Inferencer.kt` | `refineHostIntrinsic` — the seam. Also the `Host`-is-dynamic unification rule. |
| `afield/ml/FieldMLRuntimePrelude.kt` | The `host…` names themselves; thin wrappers over the bridge. |
| `afield/ml/FieldMLCompletionEngine.kt` | Host member/method/class-name completion contexts. |

The two scoring functions — `FieldMLHostBridge.scoreArgument` (over values) and
`FieldMLHostTypes.scoreArgument` (over types) — are deliberately parallel and must stay consistent.
They are *not* identical, and the difference matters: the type-level one treats a `TVar` or an
opaque `Host` as **unknown**, which must never refuse a candidate, because inference may not have
pinned an argument down yet and reporting a correct call as a type error is the worst failure mode
available here.

## The three decisions worth understanding

### 1. Refinement replaces the result type; it does not unify with it

In `Inferencer`'s `ECall` branch the prelude scheme has already unified the fresh result variable
with `Host`. Since `Host` unifies with everything, unifying again with what reflection found would
succeed and change nothing. So `refineHostIntrinsic` returns a type and the call is rebuilt with
`copy(type = …)`. If you switch that back to a `unify`, every refinement silently stops working and
no test that only checks *values* will notice — `FieldMLHostBridgeTest`'s type assertions are what
catch it.

### 2. `Host` is the dynamic type, and the boundary is deliberate

`unify` accepts `THost("Host")` against the scalars, `Unit` and any other host type, and
`requireNumeric`/`requireVector`/`requireEquality` accept it too. This is not laziness: reflection
genuinely cannot type an erased generic, and without the escape every such result would need a cast
before any use — which is the state this work exists to end.

It is *bounded*, and the bound is the interesting part. `requireDynamicallyReachable` refuses `Host`
against `TFun`, `TList`, `TTuple` and `TRecord`, with a diagnostic naming the conversion to use. The
justification is that those are not things an opaque host value ever *is*: `hostToFieldMLValue` never
produces a FieldML function from a JVM value, so applying a `Host` fails every single time it runs,
and reaching a list needs `hostAsList` — which really converts, and therefore also handles the
erased-`Object`-that-was-a-`List` case. One honest conversion at the boundary buys the whole class of
mistake that used to type-check and fail on the first run.

`unifyHosts` is the other half: two known, unrelated classes are a type error, and two where one is
assignable from the other unify — Field's linalg surface is declared half against read-only
interfaces (`Vector2fc`), and refusing `Vector2f` there would report a sound call as an error.

`FieldMLHostDynamicBoundaryTest` holds both sides of the line: eight programs that must be refused
(each also checked for a diagnostic that says what to do instead) and eleven that must still compile.
If you widen the escape, that second list is what tells you whether you broke the ergonomics; if you
narrow it, the first list is what tells you whether it was already narrow enough.

The residual cost, stated plainly: a program can still pass the type checker and fail at run time
inside a `Host` — just no longer by using it as a function or a structure.
`hostAsInt`/`hostAsFloat`/`hostAsBool`/`hostAsString`/`hostAsList`/`hostAs "Class"` are the explicit
way out of the dynamic region, and `hostAs` doubles as the way FieldML *names* a class.

### 3. SAM conversion has to read the generic type at the use site

`kotlin.jvm.functions.Function2.invoke` erases to `(Object, Object) -> Object`. A proxy built from
the erased signature hands a boxed `Double` to a parameter declared `Function2<Int, Int, Float>` and
the Kotlin body fails its implicit cast. `samProxy` therefore takes the *parameter's*
`genericParameterType`, maps the interface's type variables to the actual arguments, and coerces the
result to the resolved return class. `Img.generate(w, h) { x, y -> … }` is the test that pins this.

## `afield.four`: what blocks it, with citations

This is the roadmap, and `FieldMLAfieldFourReachTest` is its executable form — it walks the 19
statements of `~/Documents/AFieldWorkspace/threebox-2` (the minimal complete `afield.four` scene) and
records a verdict and a reason for each. 12 are expressible; 5 of those run headlessly and are
compared against the same call in Kotlin.

The blockers are not a long tail. They are five mechanisms:

| # | Mechanism | Evidence | Can the *effect* be reached? |
| --- | --- | --- | --- |
| A | **Source macros.** Nine box-code names are rewrites over Kotlin *source*, applied before the compiler runs: `moduleHere`, `meshModuleHere`, `computeModuleHere`, `fieldStatsHere`, `shadertoyHere`, `prop`, `rootProp`, `sourceBlock`, `print`/`println` | `MacroRegistry.callRewrite`, `macros.kt:338-374`. `Box.prop` throws `MacroNotExpandedException` if actually called, `props.kt:39` | Partly. `prop<T>(name)` expands to `var Box.<name> by Inheritance<T>()` (`props.kt:71`) — a *generated declaration*, so there is nothing to call. But `Inheritance.getValue` (`stdlib.kt:265`) is just `getPropertyRaw` plus a breadth-first walk over `parents`, and both of those are ordinary methods. FieldML does it in four lines, tested. `once` (`scratch.kt:15`, `Onceing.getValue` `scratch.kt:26`) is get-or-compute-and-store over the same pair, also tested |
| B | **Kotlin default arguments** are invisible to reflection | `Renderer(Window, List)` (`Renderer.kt:147`) — `additionalWindows = emptyList()` must be written out. `ComputeMesh(Renderer)` (`ComputeMesh.kt:295`) — `renderer = fromContext()` likewise | Yes, by spelling every parameter. `hostNull` covers the nullable ones |
| C | **Named arguments** are invisible for the same reason | `fieldProbeHere(samples = 100000)` | Yes, positionally |
| D | **`suspend` function types** erase to a `Function` taking a `Continuation`, which a dynamic proxy cannot satisfy | `start(a: suspend Frames.() -> Unit)`, `stdlib.kt:516` | Not with the current `samProxy`. Would need either a coroutine-aware adapter or a non-suspend entry point |
| E | **A Vulkan device.** `Window`, `Renderer`, `.buffer`, `Mesh`, `Module` are expressible but cannot be *run* in a headless test JVM | — | Only in a graphics-context session; the docs runner has one (`graphics_context` mode) and that is where a live `afield.four` FieldML demo would have to be captured |

Two smaller findings from the same walk, both fixed:

- a JVM `Set` — `Box.parents` — had no FieldML shape at all, because `hostToFieldMLValue` only unwraps
  a `List`. `hostAsList` is the way in, and it also handles arrays and maps;
- `Int.vec4s` is a **member** extension property of `BufferParameters`, so it exists only inside the
  `defaultBuffer { }` lambda. On the JVM it is `BufferParameters.getVec4s(int)`, which the bridge
  reaches — this is the case that makes "extension properties are unreachable" false as a blanket
  statement.

The next night's highest-value item is D or the lambda-parameter typing below; A is a language design
question (a FieldML macro layer) rather than a bridge one.

## Known holes, deliberate

- **Kotlin default arguments are invisible** to a reflective call, so every parameter must be
  spelled. `hostNull` exists for the nullable ones. `hostTap` remains the tool that *does* read
  defaults, because it reads Field's **source** index rather than reflection.
- **A lambda's parameters have no inferred type.** The JVM calls back with a value the checker knows
  nothing about, so `hostAs` is needed inside the lambda. Fixing this properly means expected types
  threaded through inference: the receiver of `hostCall1 "transform" f line` is inferred *after* `f`
  (the application spine is left-nested), so the lambda cannot be checked against the reflected
  parameter type without either a second inference pass over the argument or a restructured `ECall`
  rule. That is the single biggest remaining ergonomic win.
- **`Map`-returning members are typed `Host`, but the runtime hands back a `RecordValue`.** A
  `TRecord` needs field names, which reflection does not have. The mismatch is harmless because
  `Host` is dynamic, but it is a mismatch.
- **Nullability comes from `kotlin-reflect`.** JetBrains' `@Nullable` is `CLASS`-retention and so
  invisible to plain reflection, and a Kotlin `T?` really can arrive as `Unit`. `HostTypes` caches
  `Method` → nullable in a `ConcurrentHashMap` and falls back to non-null when the metadata cannot
  be read. This is the only `kotlin-reflect` use in FieldML; if it ever shows up in a completion
  latency profile, that map is where to look.
- **`internal` Kotlin members are unreachable** under their source names, because the compiler
  mangles them with the module name.
- **Buffers, kernels and anchors** — types FieldML can neither construct nor obtain — are most of
  the remaining 5% in the coverage measurement.

## Tests, and what each is for

| Test | Guards |
| --- | --- |
| `FieldMLHostBridgeTest` | One capability per test — arity, SAM, overloads, widening, `hostNull`, varargs, arrays, statics, inferred types, type errors, the dynamic escape, nullable returns. A failure here is a capability regression. |
| `FieldMLHostSurfaceCoverageTest` | The measurement. Every method is called twice — through the bridge and through plain `Method.invoke` — and the results compared, so the headline number is *agreement*, not reachability. `disagreed.isEmpty()` is asserted outright, because a disagreement is a bug. It also computes the "before" number in the same run. Raise the floors when the bridge learns something. |
| `FieldMLHostAgreementTest` | The semantically interesting calls, written beside the Kotlin they should equal. Covers what a synthesised constant-returning lambda cannot: arguments travelling *into* a FieldML function the JVM calls back, and bit-exact narrowing. |
| `FieldMLAfieldFourReachTest` | The `threebox-2` survey above, plus the five `afield.four` calls that run headlessly. |
| `FieldMLHostDynamicBoundaryTest` | Both sides of the `Host` boundary: what must be refused, and what must still compile. |
| `FieldMLCompletionEngineTest` | The six host completion contexts. |
| `FieldMLSmokeExamplesTest` | Every FieldML snippet on the public pages, **and** every box of the loadable demo document, compiled and evaluated. |
| `FieldMLCheatsheetCoverageTest` | The cheatsheet lists every prelude name with its type. Adding a `host…` name without a row fails the build. |

## The demo document

`public/docs/assets/fieldml_host_bridge_demo/metadata.edn` — a FieldML root (`mainLanguage
"fieldml"`, `isFieldMLRoot true`) with four child boxes. It was verified by copying it into a
throwaway workspace, starting Field with `PORT=… ./gradlew runJvm --args="--workspace …"`, and
driving `tools/field_backdoor.py open FieldMLHostBridgeDemo` + `run` + `screenshot-box` on each box.
Its code is also compiled and evaluated by `FieldMLSmokeExamplesTest`, so it cannot rot silently.
