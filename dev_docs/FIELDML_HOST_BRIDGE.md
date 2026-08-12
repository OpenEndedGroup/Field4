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
| `afield/ml/type/Inferencer.kt` | `refineHostIntrinsic` — the seam. `preInferHostSpine` — expected types into lambda parameters. Also the `Host`-is-dynamic unification rule. |
| `afield/ml/FieldMLRuntimePrelude.kt` | The `host…` names themselves; thin wrappers over the bridge. |
| `afield/ml/FieldMLCompletionEngine.kt` | Host member/method/class-name completion contexts, and `enclosingCallbackParameters` — completion inside a lambda the JVM will call. |

The two scoring functions — `FieldMLHostBridge.scoreArgument` (over values) and
`FieldMLHostTypes.scoreArgument` (over types) — are deliberately parallel and must stay consistent.
They are *not* identical, and the difference matters: the type-level one treats a `TVar` or an
opaque `Host` as **unknown**, which must never refuse a candidate, because inference may not have
pinned an argument down yet and reporting a correct call as a type error is the worst failure mode
available here.

## The four decisions worth understanding

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

`FieldMLHostTypes.samParameterTypes` is the type-level twin of the same read, and the two have to
agree for the same reason the two `scoreArgument`s do.

### 4. The `ECall` rule is restructured for host calls, rather than run twice

This is what gives a lambda's parameters a type, and the reason it is *not* a second inference pass
is worth keeping.

FieldML is curried, so `hostCall1 "transform" f line` is `((hostCall1 "transform") f) line`. The
inferencer's walk types `expr.function` before `expr.argument`, so it reaches `f` before it has ever
looked at `line` — and `line` is the only thing that says which JVM method, and therefore which
parameter type, is meant. `refineHostIntrinsic` runs late enough to know, but by then `f`'s
parameters are anonymous variables.

A second pass over the argument is the obvious fix and is wrong, because `abort` is a *throw*: a body
that only type-checks *with* the expected type — `fun p -> p.x` pins `p` to a one-field record and
then `p.y` is genuinely missing — never survives its first pass to reach a second. It would also
double the diagnostics produced inside the lambda and leave the substitution holding two different
types for the same source node.

So `Inferencer.preInferHostSpine` runs at the *outermost* call of a spine, flattens the **source**
application chain, recognises a saturated `host…` intrinsic with a lambda literal among its
arguments, and infers in dependency order: receiver, then the non-lambda arguments, then the lambdas
with their parameters bound. Results go into an identity-keyed memo (`preInferred`) that `inferExpr`
consults before doing anything else, so the ordinary left-nested walk that follows picks them up and
**every source node is still inferred exactly once**. The memo must be identity-keyed: two
structurally equal arguments of one call are different nodes.

Three rules stop it from ever narrowing the language, and they are the part to preserve if this is
ever touched:

- **A slot reflection cannot pin down keeps its fresh variable.** An erased generic reads back as
  `Host`, and `Host` refuses to unify with a function or a list, so substituting it for an inference
  variable would break `hostStaticCall "max" ([…], fun a b -> b - a) Collections`.
- **A nullable Kotlin callback argument is treated the same way**, read from `kotlin-reflect`
  exactly as nullable *returns* already are (`nullableFunctionArguments`).
  `FLine.byFiltering((Vec3?, Node) -> Boolean)` is the case: the JVM really does pass null and the
  runtime turns it into `Unit`, so `Vec3` would be a type the first run violates. The second
  parameter of that same signature *is* typed, which is the granularity to aim for.
- **The lambda's result is left alone.** `samProxy` coerces whatever comes back, so a FieldML `Int`
  body already satisfies a `Function2<…, Float>`; unifying the result would refuse a working program
  for no gain.

The reordering only happens when a lambda literal is present, so no program that worked before even
changes the order its diagnostics come out in.

The same knowledge pays in the negative direction. `checkLambdaArity` refuses a lambda that cannot
be the callback it is passed to, naming the method and both counts — but only in the one case that
is genuinely wrong. *Fewer* parameters than the interface takes is legitimate, because FieldML is
curried and `fun ignored -> …` is the only way to write a zero-argument `Runnable` at all; *more* is
legitimate when the interface's result is itself a functional interface, because `coerce` proxies
what comes back in its turn (`samResultAcceptsFunction`). Only extra parameters against a plain
result are refused.

Completion gets the same types, but it cannot read them off a typed tree: the source it is handed is
half-written and does not parse. `FieldMLCompletionEngine.enclosingCallbackParameters` therefore
*asks* — it finds the innermost unclosed bracket that has a `fun … ->` inside it (walking outward,
because in `hostCall "generate" (4, 4, fun x y -> toFloat (x` the caret is two levels in), rebuilds
that call with everything up to the arrow kept verbatim and the body replaced by a parameter name,
compiles it, and reads the parameter types out. Asking rather than re-deriving is deliberate: the map
from `hostCall2 "m" a f t` to a JVM parameter index lives in `preInferHostSpine`, and a second copy
in the completion engine would be a second copy to keep in step.

## `afield.four`: what blocks it, with citations

This is the roadmap, and `FieldMLAfieldFourReachTest` is its executable form — it walks the 19
statements of `~/Documents/AFieldWorkspace/threebox-2` (the minimal complete `afield.four` scene) and
records a verdict and a reason for each. 12 are expressible; 5 of those run headlessly and are
compared against the same call in Kotlin.

The blockers are not a long tail. They are five mechanisms:

**Status as of 2026-08-08.** One column per row says what changed tonight, so the next night can
diff this table rather than re-derive it.

| # | Mechanism | Status 2026-08-08 | Evidence | Can the *effect* be reached? |
| --- | --- | --- | --- | --- |
| A | **Source macros.** Nine box-code names are rewrites over Kotlin *source*, applied before the compiler runs: `moduleHere`, `meshModuleHere`, `computeModuleHere`, `fieldStatsHere`, `shadertoyHere`, `prop`, `rootProp`, `sourceBlock`, `print`/`println` | **UNCHANGED.** A language-design question, not a bridge one | `MacroRegistry.callRewrite`, `macros.kt:328#val callRewrite = mutableMapOf`. `Box.prop` throws `MacroNotExpandedException` if actually called, `props.kt:39#prop should not be callable directly` | Partly. `prop<T>(name)` expands to `var Box.<name> by Inheritance<T>()` (`props.kt:71#return Insert(`) — a *generated declaration*, so there is nothing to call. But `Inheritance.getValue` (`stdlib.kt:265#operator fun getValue(f: Box, property: KProperty<*>)`) is just `getPropertyRaw` plus a breadth-first walk over `parents`, and both of those are ordinary methods. FieldML does it in four lines, tested. `once` (`scratch.kt:10#val Box.once`, `Onceing.getValue` `scratch.kt:26#operator fun getValue(f: Once, property`) is get-or-compute-and-store over the same pair, also tested |
| B | **Kotlin default arguments** are invisible to reflection | **UNCHANGED** | `Renderer(Window, List)` (`Renderer.kt:147#class Renderer(`) — `additionalWindows = emptyList()` must be written out. `ComputeMesh(Renderer)` (`ComputeMesh.kt:295#class ComputeMesh(var renderer`) — `renderer = fromContext()` likewise | Yes, by spelling every parameter. `hostNull` covers the nullable ones |
| C | **Named arguments** are invisible for the same reason | **UNCHANGED** | `fieldProbeHere(samples = 100000)` | Yes, positionally |
| D | **`suspend` function types** erase to a `Function` taking a `Continuation`, which a dynamic proxy cannot satisfy | **UNCHANGED — and now the top bridge item** | `start(a: suspend Frames.() -> Unit)`, `stdlib.kt:516#fun start(a: suspend Frames.() -> Unit)` | Not with the current `samProxy`. Would need either a coroutine-aware adapter or a non-suspend entry point |
| E | **A Vulkan device.** `Window`, `Renderer`, `.buffer`, `Mesh`, `Module` cannot be *run* in a headless test JVM | **DONE, 2026-08-08** | `public/docs/assets/fieldml_afield_four_demo/` — captured, and the capture is `triangle.png` beside it | **Yes, and it was.** A FieldML box in a `field_backdoor.py --graphics-context` session built `parameters(renderer) { vertex = …; fragment = … }`, a `Module`, a `ModulePointer`, a `Mesh` resized on the device, and attached it to `renderer.renderTargetFor(0)`. A light blue triangle came out of the native window. No `hostAs` anywhere |

Two smaller findings from the same walk, both fixed:

- a JVM `Set` — `Box.parents` — had no FieldML shape at all, because `hostToFieldMLValue` only unwraps
  a `List`. `hostAsList` is the way in, and it also handles arrays and maps;
- `Int.vec4s` is a **member** extension property of `BufferParameters`, so it exists only inside the
  `defaultBuffer { }` lambda. On the JVM it is `BufferParameters.getVec4s(int)`, which the bridge
  reaches — this is the case that makes "extension properties are unreachable" false as a blanket
  statement.

The lambda-parameter typing that used to sit beside these is **done** — see decision 4 above and the
"Known holes" entry below for what is left of it. That makes D the next bridge item; A is a language
design question (a FieldML macro layer) rather than a bridge one.

**E is done, and it is worth saying what "done" means.** `public/docs/assets/fieldml_afield_four_demo/`
is one FieldML box that, in a `field_backdoor.py --graphics-context` session, walks up its parents to
the live `Renderer`, calls `parameters(renderer) { vertex = …; fragment = … }` with plain GLSL
strings, constructs a `Module`, takes its `ModulePointer` through the `Module.pointer()` *extension*
(`hostExt`, not `hostCall0` — that was the first thing that went wrong), attaches a `Mesh` resized to
three vertices **on the device**, and registers the module with `renderer.renderTargetFor(0)`. A
light blue triangle comes out of the native window; `triangle.png` beside the document is that
capture.

Three things that cost time and are worth writing down:

- `Renderer.renderTarget` is not readable reflectively — use `renderTargetFor(0)`, which is;
- `RenderTarget`'s three `plusAssign` overloads carry `@JvmName`, so from FieldML they are
  `plusAssign`, `plusAssign_mp` and `plusAssign_module`, and you must pick the right spelling;
- `module.meshes` is a `Map`, and the runtime turns a JVM `Map` into a FieldML **record**, so
  `hostCall2 "put"` on it fails with `Expected host target, found {}`. Go through
  `ModulePointer.plusAssign(Pair<String, Mesh>)` instead. That mismatch is the "`Map`-returning
  members are typed `Host` but the runtime hands back a `RecordValue`" hole below, biting for real
  for the first time.

What is *not* done: the demo builds a pipeline whose vertex shader needs no inputs. Anything that
feeds vertex buffers still runs into the mesh-writing surface, and anything that wants a per-frame
update still runs into D.

One thing decision 4 changes about *this* table: a Kotlin **receiver** lambda arrives with its
receiver typed, because `BufferParameters.() -> Unit` compiles to `Function1<BufferParameters, Unit>`
and the receiver is argument zero like any other. So in `defaultBuffer(null, "inData") { … }` the
lambda's `p` is inferred `BufferParameters`, and `p |> hostCall1 "getVec5s" N` is now a *compile*
error rather than a first-run one. Asserted in
`FieldMLLambdaExpectedTypeTest.aKotlinReceiverLambdaHasItsReceiverTyped`.

## Known holes, deliberate

- **Kotlin default arguments are invisible** to a reflective call, so every parameter must be
  spelled. `hostNull` exists for the nullable ones. `hostTap` remains the tool that *does* read
  defaults, because it reads Field's **source** index rather than reflection.
- ~~**A lambda's parameters have no inferred type.**~~ **Fixed** — decision 4 above, the restructured
  `ECall` rule. `hostCall1 "transform" (fun p -> vec3 p.x p.y p.z) line` types `p` as `Vec3` and
  needs no `hostAs`. Three narrower holes replace it, and they are the ones to read next:
  - **The receiver has to be known where the call is written.** `let warp line = line |> hostCall1
    "transform" (fun p -> …)` gets nothing, because `line` is a function parameter, FieldML has no
    type annotations, and there is no class to reflect on at the point the callback is checked. This
    is the residual a reader hits first — a callback in a *pipeline* is typed, the same callback
    lifted into a *helper* is not. Fixing it means either FieldML type annotations or propagating
    expected types backwards through `let`, both of which are language changes rather than bridge
    ones. `FieldMLLambdaExpectedTypeTest.aReceiverThatIsAFunctionParameterHasNoClassToRead…` pins the
    diagnostic it produces today, which is `Record is missing field 'y'` — honest, but not the
    message a reader wants.
  - **An erased or nullable callback argument still says nothing**, on purpose. See decision 4.
    Measured 91.3% — 21 of 23 callback argument slots across the coverage test's types — and floored
    at 88.0%, which is under one slot of slack (20/23 is 87.0% and fails). The two that are not typed
    are exactly one erased and one nullable.
  - **The lambda's result is unconstrained**, also on purpose. A body returning the wrong shape is
    still a run-time failure inside `samProxy`'s coercion. Constraining it would refuse `fun x y -> 0`
    against a `Function2<…, Float>`, which works, so this one probably wants a *coercion-aware*
    check rather than a unification if it is ever done.
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
| `FieldMLLambdaExpectedTypeTest` | Expected types into lambda parameters, in two halves: what is claimed (every `host…` shape that can carry a lambda, a receiver lambda, a nested lambda, a refused misspelling) and what is deliberately not (erased, nullable, the result, an unknown receiver, a non-literal spread). Also the asserted 21-of-23 measurement over the callback surface. |
| `FieldMLCompletionEngineTest` | The six host completion contexts. |
| `FieldMLSmokeExamplesTest` | Every FieldML snippet on the public pages, **and** every box of both loadable demo documents, compiled and evaluated. It also refuses a `hostAs` in any box of the lambda demo that does not carry the sentence documenting the one case that still needs one. |
| `FieldMLCheatsheetCoverageTest` | The cheatsheet lists every prelude name with its type. Adding a `host…` name without a row fails the build. |

## The demo documents

Two, both FieldML roots (`mainLanguage "fieldml"`, `isFieldMLRoot true`) with four child boxes each:

- `public/docs/assets/fieldml_host_bridge_demo/metadata.edn` — the bridge itself.
- `public/docs/assets/fieldml_lambda_demo/metadata.edn` — typed callback parameters. Three of its
  boxes contain no `hostAs`; the fourth is the one that names what still does.

Both were verified by copying them into a throwaway workspace and driving
`tools/field_backdoor.py --workspace <ws> --with-playwright --command "open <Doc>" --command "run
<box>" --command "screenshot-box <box> …"`. Note that the backdoor only forwards `--workspace` when
it *starts* the server, which is why this is one invocation rather than a running Field plus a
client; a `boxes: []` reply almost always means a different workspace, not a broken document.
Their code is also compiled and evaluated by `FieldMLSmokeExamplesTest`, so neither can rot silently.

One layout gotcha, learned the hard way: a `drawSized`/`showImg` output canvas is placed *directly
below* its box in world coordinates and is as tall as the size you asked for, so a child box placed
under a drawing box will be hidden behind that canvas. Leave the drawn height plus a margin between
rows.
