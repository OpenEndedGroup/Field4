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

## The seven decisions worth understanding

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
  variable would break `hostStaticCall "max" ([…], fun a b -> b - a) Collections`. The erasure test
  that forces this is one line: `HostTypes.kt#val erased = generic is TypeVariable<*>`.
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

### 5. A `suspend` function type is satisfied by *not* suspending, and that is sound

Added 2026-08-19; this is mechanism D in the table below.

`suspend Frames.() -> Unit` is not its own JVM type. It compiles to

```
Function2<? super Frames, ? super Continuation<? super Unit>, ? extends Object>
```

— the parameters as written, then a trailing `Continuation`, then a return erased to `Object` because
a suspend call may hand back either its value or the `COROUTINE_SUSPENDED` marker. The old row said a
dynamic proxy "cannot satisfy" that, which was half wrong in an interesting way: `Function2` **is** a
functional interface, `functionalMethod` already found its `invoke`, and `samProxy` already built a
proxy. Three narrower things were broken, and each is worth knowing separately because each fails
differently:

- the continuation was **curried into the FieldML closure** as an extra argument, so a one-parameter
  lambda was applied twice and died inside `applyFieldMLFunction`;
- the result was coerced to the SAM's **erased** `Object` return. The declared result only exists as
  the continuation's type argument, so `forever`, whose block returns `Boolean`, got whatever came
  back and `while (block(v))` failed its cast;
- `samParameterTypes` reported **two** parameters, so `checkLambdaArity` refused the correct arity and
  accepted the wrong one, and completion offered the continuation as something to name.

`FieldMLHostBridge.suspendShape` fixes all three from one read: substitute the interface's type
variables into the SAM's last generic parameter, take a wildcard's **lower** bound — Kotlin emits
parameters contravariantly, so `? super Continuation<…>` carries its information below, not above,
which is the opposite of what `resolveClass` does for a *result* — and if what comes out is a
`Continuation`, report both the written arity and the class inside it.

**The soundness argument, in full, because this is the part to argue with.** On the JVM a `suspend`
function is an ordinary method: trailing continuation, and a return that is either the value or the
marker. Returning the value is not a trick, it is the documented completed-without-suspending path —
literally what a suspend body containing no suspension points compiles to. FieldML has no suspension
points: no `await`, no `yield`, and no reflective route to a `suspend` member either, because such a
member's arity counts the continuation the bridge cannot construct, so it never resolves. An adapted
FieldML closure therefore *always* completes synchronously, and the proxy can never need to return
the marker or resume the continuation it dropped.

**And the bound that buys, which is real.** The FieldML body cannot itself suspend. `Frames.frame()`
and `Frames.delay()` stay out of reach, so `frames { }` — whose whole contract is that the body drives
`frame()` — is expressible but useless, while `eachFrame`, `loop`, `forever`, `Frames.end` and
`Box.launchFrames`, where the enclosing Kotlin owns the suspension points and the block is a plain
per-frame body, are exactly right. `FieldMLSuspendCallbackTest.aBodyThatWouldHaveToSuspendIsStillOutOfReach`
is that line as a test, and it is a test of a *refusal* on purpose: the day someone makes `frame()`
callable from FieldML, this argument stops holding and that test is where they will find out.

The measurement is `everySuspendCallbackSlotOnFieldsPerFrameSurfaceReportsTheWrittenArity` in the same
file: 16 suspend-typed callback slots across `PhasedKt`, `StdlibKt` and `Frames`, all 16 reporting the
written arity through the production `FieldMLHostTypes.parameterSlot` + `samParameterTypes` pair, and
the floor is all of them rather than a percentage. The surface is small enough to enumerate, so a
partial answer would not be a partial capability — it would be one signature where the type checker
quietly refuses the correct lambda.

### 6. Named arguments and Kotlin defaults are one mechanism, spelled as a record

Added 2026-08-19; this is mechanisms B and C in the table below, and the reason they close together
is the interesting part.

They read as two rows — "reflection cannot see a default", "reflection cannot see a name" — and they
are one fact. Plain reflection has neither, `kotlin-reflect` has both out of the same metadata
(`KParameter.name`, `KParameter.isOptional`), and `KCallable.callBy` is the single entry point that
acts on them: every parameter absent from the map it is given gets the default the source declared.
Which means naming the arguments is not a convenience *beside* defaults — it is the thing that makes
defaults reachable, because a positional call has no way to say which parameter it is skipping.

The spelling is a **record** where a tuple would go:

```
hostNew {mainWindow = w} (hostClass "afield.four.Renderer")
img |> hostCall "index" {x = 1, y = 0}
```

`FieldMLHostBridge.spreadArguments` already dispatches on the *shape* of the argument — a tuple and a
list spread, `Unit` is no arguments, anything else is one — so a record spreading by name continues
that rule rather than adding to it. No new prelude name, no new cheatsheet row, and the same rule at
every call shape including `hostExt`, where the piped value becomes the extension receiver (bound by
`KParameter.Kind`, since Kotlin gives it no name a `callBy` map would accept).

Three choices worth being able to argue with:

- **Coercion stays in the bridge.** `callBy` type-checks against the declared `KType` and will not
  narrow a FieldML `Float` — a JVM `Double` — into a `Float` parameter, so every supplied value goes
  through `coerce` against the erased parameter class first. That is also what keeps SAM conversion
  working inside a named call.
- **An ambiguous name set is refused, not guessed.** Two overloads that both accept the names given
  is exactly where picking one is a coin toss, and a wrong reflective call is far harder to diagnose
  than a diagnostic listing the candidates with a `?` on each optional parameter.
- **The type level resolves on names alone**, ignoring argument types, and is therefore *weaker* than
  its positional counterpart. A name set is usually far more selective than an arity, and a third
  scorer that had to be kept in step with the two that already exist is a worse trade than degrading
  to `Host` when two candidates take the same names. It is weak in the only safe direction: the type
  checker never refuses a call the runtime would have made.

The measurement is `FieldMLNamedArgumentTest.everyKotlinDefaultOnTheCoveredSurfaceCanBeOmittedByName`:
over the same seven types the coverage test uses, 60 members have at least one Kotlin default, and
all 60 are reachable naming only their *required* parameters. `FLine.tessellate(samplesPerCubic?,
coplanarity?, meshAcceptor)` is the shape that shows this is not just brevity — the required parameter
is **last**, so no positional call can shorten it at all.

Two costs, both tested rather than asserted:

- a JVM `Map` comes back through `hostToFieldMLValue` as a `RecordValue`, so "a record I got from the
  host" and "a record of argument names" are the same FieldML shape. `hostCall1`, which never
  spread, is the escape — and always was, for lists.
- **a lambda inside a named record does not get its parameters typed.** `preInferHostSpine` looks for
  a lambda *literal* among a spine's arguments, and a record is one argument with a lambda inside it.
  So a callback is still better written positionally, and the demo boxes do.

### 7. A source macro is reached by expanding it and calling what it rewrote to, not by compiling Kotlin

The framing this arrived with was: a macro returns Kotlin source, FieldML has no Kotlin compiler,
therefore FieldML needs either a macro layer of its own or a way to drive a sibling Kotlin compile.
Both are language-scale answers, and neither was needed. Read every registered macro's `Insert` and
the shape is uniform: the ones that rewrite a **call** rewrite it to an ordinary `Box` extension
function. `moduleHere` → `_moduleHere`, `computeModuleHere` → `_computeModuleHere`, `println` →
`_printlnHere`, `plyPointCloudHere` → `_plyPointCloudHere`. `shaders.kt:1010` states the intention
outright about `_fieldStatsHere`: *"The real work behind `Box.fieldStatsHere` — what the macro
rewrites the call site to. Reach for it directly only when you are driving a box from outside and do
not want macro expansion."*

FieldML is exactly a thing driving a box from outside. So the mechanism is two steps, and a compiler
is neither:

```
hostMacroExpand "moduleHere" "{ }"      -- run the expansion; side effects land on this box
hostMacro "moduleHere" "{ }" (renderer) -- and then call the Box extension it rewrote to
```

Four choices worth being able to argue with:

- **Expansion is the valuable half, not the call.** `moduleHere`'s expansion seeds this box's
  `vertex`/`fragment` properties, preprocesses and compiles both stages and assigns them
  (`shaders.kt:4039#fun Box.macro_moduleHereWithDefaultShaders`); `fieldStatsHere`'s expansion builds
  the whole `ShaderFieldStats` block and the rewritten call only re-reads it
  (`shaders.kt:1105#_fieldStatsHere(`). A FieldML box gets all of that. What it does not get is the
  *preamble* — the generated typed accessors — and it does not need them, because every host value is
  `Host`, which is dynamic, so the same members come through `hostGet`/`hostCall`. That is the trade,
  stated once here rather than implied everywhere.
- **The callee is read out of the expansion; only the owning class comes from a table.** The name a
  macro rewrites to is in `Insert.here`, so `hostMacro` follows a macro that changes its target. What
  reflection cannot recover is which *file class* a Kotlin top-level function lives in, so
  `FieldMLMacroLayer.ledger` supplies that and nothing else. The ledger is held to the live registry
  bidirectionally — a new macro with no row fails, and a row naming a macro that is gone fails — so it
  cannot rot into a source of quiet over-claiming.
- **The ledger lives in `afield.ml`, not in `macros.kt`.** It is FieldML's *reading* of the macro
  layer, and a reading that can be wrong belongs next to the thing that can be wrong about it. It also
  keeps the diff off a file every language in Field shares.
- **The sibling Kotlin compile was refused, with a mechanism named.** FieldML evaluation runs inside
  `inMain { }` (`FieldMLInterface.kt:63#val compileResult = FieldMLCompiler.compile(code)`) and the
  Kotlin box pipeline is the same main-thread runloop, so a synchronous compile-and-eval from inside
  FieldML evaluation is a re-entrancy hazard rather than a design. And the only thing it would buy is
  the preamble, which is sugar over APIs FieldML already reaches. It would also make every FieldML box
  that touched a macro depend on a Kotlin compile — the thing FieldML exists not to need.

The **declaration** macros are the other half, and they get native forms rather than expansion.
`prop<T>(name)` generates `var Box.<name> by Inheritance<T>()`, so there is no call to make — but the
delegate is a breadth-first walk over `parents`, and that walk is now shared code:
`afield.stdlib.inheritedPropertyRaw` (`stdlib.kt:245#fun inheritedPropertyRaw`) is called by both
`Inheritance.getValue` and FieldML's `prop`. A Kotlin box's `speed` and a FieldML box's
`prop "speed"` are one property by construction rather than by comment, which is what makes
`FieldMLSourceMacroTest.propAgreesWithTheGeneratedKotlinDelegateOnTheSameGraph` an agreement test
rather than a coincidence test. `propOr`, `setProp`, `rootProp`, `setRootProp` and `once` complete the
family.

What is genuinely left is four macros that generate Kotlin *declarations* and nothing else —
`sourceBlock` (a lambda whose body is the original Kotlin), `jsonParser` (a generated class),
`graphLens` (a `::` reference synthesised from a path) and `ai`. `hostMacroExpand` runs their
expansions and hands the generated source back as **text**, which is inspectable and occasionally
useful, and is honestly as far as an interpreter goes. 15 of 19 reachable, and the four are pinned as
a set rather than as a count, because a set says which.

## `afield.four`: what blocks it, with citations

This is the roadmap, and `FieldMLAfieldFourReachTest` is its executable form — it walks the 19
statements of `~/Documents/AFieldWorkspace/threebox-2` (the minimal complete `afield.four` scene) and
records a verdict and a reason for each. 13 are expressible; 6 of those run headlessly and are
compared against the same call in Kotlin.

The blockers are not a long tail. They are five mechanisms:

**Status as of 2026-08-20 — all five are closed.** One column per row says what changed on the night
that touched it, so the next night can diff this table rather than re-derive it.

| # | Mechanism | Status 2026-08-20 | Evidence | Can the *effect* be reached? |
| --- | --- | --- | --- | --- |
| A | **Source macros.** Box-code names that are rewrites over Kotlin *source*, applied before the compiler runs: `moduleHere`, `meshModuleHere`, `computeModuleHere`, `fieldStatsHere`, `shadertoyHere`, `prop`, `rootProp`, `sourceBlock`, `print`/`println`, and the ones registered further out — `gltfModelHere`, `plyModelHere`, `jsonParser`, `graphLens`, `ai` | **DONE, 2026-08-20 — and it turned out not to be a language-design question.** 15 of the 19 macros `MacroRegistry` registers at run time are reachable | `afield.ml.FieldMLMacroLayer`, and the two prelude names `hostMacroExpand` / `hostMacro`. Measured and floored in `FieldMLSourceMacroTest.theReachableFractionOfTheMacroSurfaceIsFloored`: 15 of 19 (78.9%), floored at 15, with the four that are not reachable pinned as an exact **set** rather than a count. Every call-shaped route is resolved by reflection in `everyCallShapedRouteIsARealBoxExtension`, which certifies `moduleHere` without a device. Pixels and a live Field: `public/docs/assets/fieldml_macro_demo/spiral.png` | **Yes, two different ways, and the difference matters.** The **call-shaped** macros rewrite to an ordinary `Box` extension function — `moduleHere` to `_moduleHere` (`shaders.kt:4242#var here = "$resolvesToCall${args[0]}"`), `println` to `_printlnHere` (`outputs.kt:727#here = "_printlnHere(__line__+1, $args)"`). So `hostMacro` runs the **expansion** — which is where a macro does its work: seeding shader properties, compiling both stages (`shaders.kt:4039#fun Box.macro_moduleHereWithDefaultShaders`), building a `ShaderFieldStats` block — and then calls that function reflectively with the box as receiver. The **declaration** macros have no call to make, and `prop`'s delegate is now shared code: `afield.stdlib.inheritedPropertyRaw` (`stdlib.kt:245#fun inheritedPropertyRaw`) is called both by `Inheritance.getValue` and by FieldML's `prop`, so a Kotlin box's `speed` and a FieldML box's `prop "speed"` are one property by construction. What is left is four macros that generate Kotlin *declarations* — `sourceBlock`, `jsonParser`, `graphLens`, `ai` — and `hostMacroExpand` still hands their generated source back as text |
| B | **Kotlin default arguments** are invisible to reflection | **DONE, 2026-08-19 — and B and C turned out to be one mechanism** | `FieldMLHostBridge.callMethodNamed` (`HostBridge.kt:123#fun callMethodNamed(name: String, target: Any`), `FieldMLHostTypes.namedMethodResultType` (`HostTypes.kt:214#fun namedMethodResultType(receiver: Type`). Measured and floored in `FieldMLNamedArgumentTest.everyKotlinDefaultOnTheCoveredSurfaceCanBeOmittedByName`: 60 defaulted members over the coverage test's seven types, all 60 omittable. Original citations: `Renderer(Window, List)` (`Renderer.kt:147#class Renderer(`), `ComputeMesh(Renderer)` (`ComputeMesh.kt:295#class ComputeMesh(var renderer`) | **Yes — a record of arguments is a named call, and a named call is the only shape that can leave a default unwritten.** `hostNew {mainWindow = w} Renderer` needs no `additionalWindows`. The positional form is unchanged, and `hostNull` still covers the nullable parameters you *do* want to pass |
| C | **Named arguments** are invisible for the same reason | **DONE, 2026-08-19 — the same change; see B** | `img \|> hostCall "index" {x = 1, y = 0}`, and the refusals in `FieldMLNamedArgumentTest`. The cited case, `fieldProbeHere(samples = 100000)`, is *still* out of reach — but as a source macro (A), not as a named argument | **Yes, by name.** Order is not position, and a name reflection does not know is a compile error naming what was given |
| D | **`suspend` function types** erase to a `Function` taking a `Continuation`, which a dynamic proxy cannot satisfy | **DONE, 2026-08-19 — for satisfying one. Calling *into* a suspend member is the part that remains, and it is a different problem** | `FieldMLHostBridge.suspendShape` (`HostBridge.kt:624#fun suspendShape(iface: Class<*>, generic:`) and the drop in `samProxy` (`HostBridge.kt:701#Drop the continuation, and only when one really arrived`); the type-level twin in `FieldMLHostTypes.samParameterTypes` (`HostTypes.kt:447#fun samParameterTypes(parameter: HostParameter)`). Sixteen suspend callback slots measured and floored in `FieldMLSuspendCallbackTest.everySuspendCallbackSlotOnFieldsPerFrameSurfaceReportsTheWrittenArity`. Pixels: `public/docs/assets/fieldml_frames_demo/spin-frames.png` | **Yes, and it was.** `eachFrame`, `loop`, `forever`, `Frames.end`, `Frames.body`/`atEnd`, `animate`, both `start`s and `Box.launchFrames` all take a FieldML closure now, and a FieldML box drives a spinning, colour-cycling triangle on a real device. What is *not* reachable is a suspend **member** — `Renderer.run` is `run(Continuation)`, arity one, and the bridge has no continuation to pass and nowhere to resume |
| E | **A Vulkan device.** `Window`, `Renderer`, `.buffer`, `Mesh`, `Module` cannot be *run* in a headless test JVM | **DONE, 2026-08-08** | `public/docs/assets/fieldml_afield_four_demo/` — captured, and the capture is `triangle.png` beside it | **Yes, and it was.** A FieldML box in a `field_backdoor.py --graphics-context` session built `parameters(renderer) { vertex = …; fragment = … }`, a `Module`, a `ModulePointer`, a `Mesh` resized on the device, and attached it to `renderer.renderTargetFor(0)`. A light blue triangle came out of the native window. No `hostAs` anywhere |

Two smaller findings from the same walk, both fixed:

- a JVM `Set` — `Box.parents` — had no FieldML shape at all, because `hostToFieldMLValue` only unwraps
  a `List`. `hostAsList` is the way in, and it also handles arrays and maps;
- `Int.vec4s` is a **member** extension property of `BufferParameters`, so it exists only inside the
  `defaultBuffer { }` lambda. On the JVM it is `BufferParameters.getVec4s(int)`, which the bridge
  reaches — this is the case that makes "extension properties are unreachable" false as a blanket
  statement.

The lambda-parameter typing that used to sit beside these is **done** — see decision 4 above and the
"Known holes" entry below for what is left of it. So are D (decision 5) and B/C (decision 6), all on
2026-08-19, and A (decision 7) on 2026-08-20. **There is no mechanism left.** What remains inside the
bridge is not a mechanism but a list of named holes, and that list is below.

`FieldMLAfieldFourReachTest.theResidueIsNoMechanismAtAll` is the executable form of that sentence,
and its exact-set assertion has flipped direction. It used to catch a new refusal for a *bridge*
reason hiding behind a steady count; it now catches a statement sliding back into `SOURCE_MACRO`,
which is what a regression in the macro layer would look like from the survey. 18 of `threebox-2`'s
19 statements are expressible, and the one that is not is the `import`.

**A is done, and this is what "done" cost.** The brief for it was written as a language-design
question — a FieldML macro layer, new syntax, a sibling Kotlin compile driven from inside FieldML
evaluation. None of that was needed, and the reason is one observation: **every macro that rewrites a
*call* rewrites it to an ordinary `Box` extension function.** `shaders.kt:1010` says so in prose about
`_fieldStatsHere` — *"what the macro rewrites the call site to. Reach for it directly only when you
are driving a box from outside and do not want macro expansion"* — and FieldML **is** driving a box
from outside. So reaching such a macro needs the expansion (for its side effects on the box) plus a
reflective call, and the bridge already had the second half.

What a Kotlin compile was ever *for* is the macro's **preamble**: the generated typed accessors, `val
Mesh.pos by FromInput()` and `var Structure.p` and the `triangles` constant. FieldML does not get
those and does not need them, because every host value is typed `Host`, which is dynamic, and the same
members are reached through `hostGet`/`hostCall`. That is the trade, and it is why this is a bridge
answer rather than a language one. **The sibling-compile design was considered and refused**: FieldML
evaluation runs inside `inMain { }` (`FieldMLInterface.kt:63#val compileResult = FieldMLCompiler.compile(code)`)
and the Kotlin box pipeline is the same main-thread runloop, so a synchronous compile-and-eval from
inside FieldML evaluation is a re-entrancy hazard rather than a design; and it would make every
FieldML box that touched a macro depend on a Kotlin compile, which is the thing FieldML exists not to
need.

Three things from that walk, in the same spirit as D's and E's three:

- **The runtime function's name is read out of the expansion, not out of a table.** `FieldMLMacroLayer`
  has a ledger, but the ledger supplies only the *owning class* — the part reflection genuinely cannot
  recover, since a Kotlin top-level function lives in a file class whose name has nothing to do with
  the function's. The method name comes from `Insert.here`, so a macro that changes its rewrite target
  keeps working and the ledger row is what goes stale. `gltfModelWithTextureHere` is the case that
  makes this worth doing: it rewrites to `_gltfModelHereWithTexture`, which is not its own name with an
  underscore.
- **A macro's arguments do two different jobs, and only one of them can be text.** They configure the
  expansion — a shader block, a sample count — and they are passed through to the rewritten call. An
  interpreter has no source for the second, so `hostMacro` takes the text once and the values once:
  `hostMacro "println" "(x)" (7, "hello")`. A record of values is a **named** call, which matters more
  here than anywhere else, because a macro's runtime function is usually all-defaults —
  `_fieldStatsHere` declares a default for every one of its seven parameters (`shaders.kt:1019#fun Box._fieldStatsHere`),
  so the positional form would make you write all seven to change one. This is where mechanism A
  composes with B and C.
- **A FieldML integer is a JVM `Long`, and a shared `prop<Int>` slot notices.** The obvious story —
  that a `Long` in an `Int` slot makes the Kotlin reader throw — is **false**, and the test that
  claimed it was wrong: Kotlin compiles `Any as Int` to `Number.intValue()`, not to a checkcast on
  `Integer`, so it reads fine and silently *truncates* once the value leaves `Int` range. What is
  observably wrong is the **raw** property: its class becomes `java.lang.Long` and it stops comparing
  equal to the `Int` a Kotlin box wrote, which everything that reads `getPropertyRaw` directly or
  type-switches on the result sees — including `hostToFieldMLValue`, `Structure.writeToBuffer` (which
  prints `Warning: unknown type` rather than failing) and `setPropertyRaw`'s own resync check. So
  `setProp` narrows to the width already in the slot, and the counterfactual in
  `FieldMLSourceMacroTest.aFieldMLWriteKeepsTheWidthAKotlinBoxDeclared` is on the observable rather
  than on the story. A non-numeric mismatch — a `String` into a `Boolean` slot — does still throw,
  which is why the narrowing is only ever between JVM widths of one FieldML type.

And one thing that is not about the bridge at all, but will cost the next person an hour if it is not
written down. **`applyRuntimeToggle` converts only the *direct children* of a language root**
(`LanguageRuntimeSupport.kt:57#root.children.forEach { child ->`). A FieldML box written as a
*grandchild* — the natural shape when it inherits a `prop` from the box above — silently runs as
**Kotlin**, and the symptoms are Kotlin compiler diagnostics dressed as FieldML errors:
`prop "swirl"` reports *"Cannot infer type for type parameter 'T'"* (because `Box.prop` is
`fun <T>`) and `linspace 0.0 1.0 320` reports *"Expecting an element"*. Nothing says "this box is not
FieldML". The fix is two parents — the root for the language, the other box for the value — and
`fieldml_macro_demo` says so in its own comment because it is not guessable. This is how that demo
failed in a live Field while passing every headless test.

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
feeds vertex buffers still runs into the mesh-writing surface. The per-frame update that used to be
named here is **done** — see below, and see `public/docs/assets/fieldml_frames_demo/`.

**D is done too, and the same paragraph is owed.** `public/docs/assets/fieldml_frames_demo/` is that
still life with a `Frames` loop on it: the same `Module`/`Mesh`/`ModulePointer` construction, plus a
`vec4` push-constant member and

```
hostStaticCall "eachFrame" (fun f -> …) (hostClass "afield.PhasedKt")
```

whose body writes the current `frameNumber` into that member, launched with `Box.launchFrames`. The
triangle spins and colour-cycles; `spin-0.png` … `spin-3.png` beside the document are four captures a
second apart from a real Vulkan device, and `spin-frames.png` is the four of them together.

The movement is the evidence, which is why it was built this way rather than with a self-timing
`Structure.bind` lambda: with the adapter absent the block never runs, the member stays (0,0,0,0), and
the triangle is upright and pink. Measured on the captures, consecutive frames differ in up to 28.5%
of pixels and the fill colour tracks `0.5 + 0.5·cos(spin.x + …)`.

Three things from *that* walk, worth the same treatment as E's three:

- **`Structure.set` takes `Any`, so the bridge cannot narrow for you.** A FieldML `Float` is a JVM
  `Double`, and `Structure.writeToBuffer` has no `Double` case — it prints `Warning: unknown type` and
  the member silently keeps its old bytes (`Structure.kt:418#Warning: unknown type`). The symptom is a
  scene that draws and does not move, with nothing in the log that names your box. Go through a
  *typed* setter instead: `Vector4f.set(float, float, float, float)` really declares floats, so the
  numeric ladder narrows the Double, and the same object stays in the structure and is re-serialised
  on every draw. The same trap waits for anything else typed `Any` on the far side of the bridge.
- **The push-constant name that matters is the member name.** `Mesh.setMergedPushMembers` flattens
  every stage's `pushConstants` down to `member.name` and keys offsets by that, so the block name and
  the instance name are both decoration. Sanity-check with `module.pushConstantSize`: if it is 0,
  neither stage declared a block and every write is a silent no-op.
- **`renderer::run` is the part that is still refused, and it is a *different* mechanism.**
  `Renderer.run` is a `suspend` **member**: on the JVM `run(Continuation)`, arity one. Satisfying a
  suspend function *type* is sound because a FieldML closure always completes synchronously; calling
  *into* one is not, because `Renderer.run` never completes at all — it would return
  `COROUTINE_SUSPENDED` and leave a coroutine with nobody to resume it. So the demo's window-and-
  renderer box is Kotlin, and says so in its own first comment.

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
  mangles them with the module name — which is why the surface-coverage test drops every method whose
  JVM name carries a `$` before it counts anything
  (`FieldMLHostSurfaceCoverageTest.kt#'$' !in it.name`).
- **A method whose declaring class is not *exported* is invoked through a public supertype instead.**
  Not a hole — a fix, landed 2026-08-19 and missing from this page until 2026-08-20, and it belongs
  here because it is the one place the bridge deliberately calls a method other than the one
  reflection handed it. `MemoryUtil.memFloatBuffer` returns a `java.nio.DirectFloatBufferU`:
  package-private, in a package `java.base` does not export, so `Class.getMethods` yields a `Method`
  whose `declaringClass` is that, `setAccessible` cannot open it, and `invoke` throws
  `IllegalAccessException` — which inside a `Proxy` arrives as an `UndeclaredThrowableException` with
  a **null message**, so the box reports the wrapper and the cause exists only in the server log.
  `FieldMLHostBridge.invocable` (`HostBridge.kt:558#fun invocable(method: Method)`) walks the public
  supertypes breadth-first and takes the first that declares the same name and parameters somewhere
  invocable — `java.nio.FloatBuffer`, which is the type the author had in mind. Deliberately *not*
  `--add-opens`: `java.base` is closed on purpose and a FieldML box should not need a JVM flag. Best
  effort by design — when nothing public declares it the original comes back and the call fails as it
  did before, because a poor diagnostic beats the wrong method. This was found writing vertex data
  from a FieldML `eachFrame` body, which is the `fieldml_mesh_demo` box above; every value in sight
  there is a JDK buffer.
- **Buffers, kernels and anchors** — types FieldML can neither construct nor obtain — are most of
  the remaining 5% in the coverage measurement.
- **A `suspend` *member* cannot be called**, which is the other half of decision 5 and a genuinely
  harder problem than the one that was solved. `Renderer.run` is `run(Continuation)` — arity one — so
  no candidate matches at arity zero and the refusal is automatic
  (reproducer: `FieldMLSuspendCallbackTest.kt#fun aBodyThatWouldHaveToSuspendIsStillOutOfReach`).
  It is also *correct*: satisfying a
  suspend type is sound because a FieldML closure always completes synchronously, and `Renderer.run`
  never completes at all. Calling it would mean returning `COROUTINE_SUSPENDED` to a caller with
  nowhere to resume. Doing this properly needs the FieldML evaluator to be suspendable, not another
  proxy trick.
- **A parameter typed `Any` gets no narrowing, and the failure is silent at the far end.** A FieldML
  `Float` is a JVM `Double`; `coerce` sees an `Object` slot, finds the argument already assignable and
  passes it straight through. That is right, and it is also how `Structure.set("spin", 0.5)` writes
  nothing at all: `Structure.writeToBuffer` has cases for `Float`, `Int`, `Long`, `UInt` and the
  vector types and none for `Double`, so it prints `Warning: unknown type` and moves on
  (`Structure.kt:418#Warning: unknown type`). Nothing in the box's own output says so. Where a
  *typed* setter exists — `Vector4f.set(float, float, float, float)` — going through it is the fix,
  because then the numeric ladder has something to narrow towards. This is the first `Any`-typed slot
  the bridge has met in anger; there will be others.
- **Overloads that differ only in lambda arity tie.** `start` is declared over
  `suspend Frames.() -> Unit` and over `suspend () -> Unit` (`stdlib.kt:516#fun start(a: suspend Frames.() -> Unit)`,
  `stdlib.kt:527#fun start(a: suspend () -> Unit)`), and a FieldML closure scores identically against
  both, because `scoreArgument` sees a `ClosureValue` and not how many parameters it will accept.
  `resolve` still reports the result type — both return `Frames` — but `parametersOf` correctly
  declines to claim a parameter slot, so the callback goes untyped. The type level *does* know the
  lambda's arity (the argument is a `TFun` chain), so teaching `FieldMLHostTypes.scoreArgument` to
  prefer the functional interface whose visible arity matches is a real and fairly small fix; the
  runtime `choose` would still tie, and the honest way to break that is to feed the type checker's
  choice through rather than re-deciding over values.
  `FieldMLAfieldFourReachTest.theOverloadThatThreeboxActuallyWritesRunsButDoesNotTypeItsCallback` is
  where that lands the day someone does it.

## Tests, and what each is for

| Test | Guards |
| --- | --- |
| `FieldMLHostBridgeTest` | One capability per test — arity, SAM, overloads, widening, `hostNull`, varargs, arrays, statics, inferred types, type errors, the dynamic escape, nullable returns. A failure here is a capability regression. |
| `FieldMLHostSurfaceCoverageTest` | The measurement. Every method is called twice — through the bridge and through plain `Method.invoke` — and the results compared, so the headline number is *agreement*, not reachability. `disagreed.isEmpty()` is asserted outright, because a disagreement is a bug. It also computes the "before" number in the same run. Raise the floors when the bridge learns something. |
| `FieldMLHostAgreementTest` | The semantically interesting calls, written beside the Kotlin they should equal. Covers what a synthesised constant-returning lambda cannot: arguments travelling *into* a FieldML function the JVM calls back, and bit-exact narrowing. |
| `FieldMLAfieldFourReachTest` | The `threebox-2` survey above, plus the `afield.four` calls that run headlessly — including, since D closed, the `Frames` builders, because `Frames` is a plain object with no Vulkan in it. Also pins what is left of `start(renderer::run)`: an overload tie, not a coroutine problem. |
| `FieldMLSourceMacroTest` | Mechanism A. Asserts its own premise off the live `MacroRegistry` maps first, because everything else here is a statement about "the source macros" and a JVM where `runAll(macroRegistration)` did not fire would see twelve instead of nineteen. Then a **bidirectional** ledger (missing, stale, set equality), reflective resolution of every call-shaped route, `println` driven end to end into `Box._printlnHere`, `prop` compared against the generated `Inheritance<T>` delegate on one box graph, and the demo's two captures held to the claim they support. |
| `FieldMLSuspendCallbackTest` | Mechanism D. Every test here is "the closure **ran**, this many times" rather than "a proxy was constructed" — a proxy was constructible before this work too. Plus the 16-slot surface measurement, and the refusal that marks the bound. |
| `FieldMLHostDynamicBoundaryTest` | Both sides of the `Host` boundary: what must be refused, and what must still compile. |
| `FieldMLLambdaExpectedTypeTest` | Expected types into lambda parameters, in two halves: what is claimed (every `host…` shape that can carry a lambda, a receiver lambda, a nested lambda, a refused misspelling) and what is deliberately not (erased, nullable, the result, an unknown receiver, a non-literal spread). Also the asserted 21-of-23 measurement over the callback surface. |
| `FieldMLCompletionEngineTest` | The six host completion contexts. |
| `FieldMLSmokeExamplesTest` | Every FieldML snippet on the public pages, **and** every box of both evaluable demo documents, compiled and evaluated; the two `afield.four` demos are compiled only, because they need a device. It also refuses a `hostAs` in any box of the lambda demo that does not carry the sentence documenting the one case that still needs one, and guards the frames demo's *subject* — it must still reach `eachFrame` and `launchFrames`, or it is the still life again with a longer comment. |
| `FieldMLCheatsheetCoverageTest` | The cheatsheet lists every prelude name with its type. Adding a `host…` name without a row fails the build. |

## The demo documents

Five. Three need nothing but a Field, and are FieldML roots (`mainLanguage "fieldml"`,
`isFieldMLRoot true`):

- `public/docs/assets/fieldml_host_bridge_demo/metadata.edn` — the bridge itself, five boxes. The
  fifth is named arguments and Kotlin defaults, and it is the *production* evidence for decision 6:
  `FieldMLSmokeExamplesTest.demoDocumentBoxesCompileAndEvaluate` runs it on every build, and it was
  run in a real Field on 2026-08-19, printing
  `{defaulted = 3, reordered = 5, colouredChannels = 3, …}` with the whole record type inferred.
- `public/docs/assets/fieldml_lambda_demo/metadata.edn` — typed callback parameters. Three of its
  boxes contain no `hostAs`; the fourth is the one that names what still does.
- `public/docs/assets/fieldml_macro_demo/metadata.edn` — mechanism A, four boxes, and the one to open
  first if you want to *feel* what closed on 2026-08-20. A `prop` written in one box and inherited by
  another that draws a spiral from it; Field's own `println` macro expanded and called; and the
  generated Kotlin of `prop` and `sourceBlock` as readable text. `spiral.png` and `spiral-edited.png`
  are the same box before and after the parameter was changed to `swirl` 0.35 / `turns` 9 — 7.13% of
  pixels differ, and `theDemoCapturesShowTheParameterActuallyChangingTheDrawing` floors that at 5.0%
  with an ink guard on each, so a re-capture that pasted the same image twice fails.

Three need a GPU:

- `public/docs/assets/fieldml_afield_four_demo/metadata.edn` — mechanism E, the still life. One box,
  which expects an ancestor that already owns a `Renderer`.
- `public/docs/assets/fieldml_frames_demo/metadata.edn` — mechanism D, the moving one, and the first
  of these that is **self-contained**: a Kotlin `window and renderer` box, a FieldML root, and the
  FieldML box. Open it, run the Kotlin box, run the FieldML box. No `--graphics-context`, no
  connecting anything up. That is the difference worth copying — a demo the reader has to wire up is a
  demo most readers do not see.
- `public/docs/assets/fieldml_macro_module_demo/metadata.edn` — **mechanism A on a real device, and
  the prize this table named for closing it.** The other three GPU demos hand-build their pipeline:
  `parameters(renderer) { vertex = …; fragment = … }`, `Module(params)`, `.pointer()`. This one calls
  Field's `moduleHere` instead — the macro a Kotlin graphics box actually uses — so the GLSL lives in
  the box's own `vertex`/`fragment` properties, where a Kotlin box keeps it, with the box's include
  resolvers, source names and shader-error reporting attached. Self-contained: run the Kotlin
  `window and renderer` box, then the scene box.

  Its *second* subject is the one only mechanism A could give it: a **live parameter**. A fourth box
  writes `spinRate` with `setRootProp`, the scene's `eachFrame` body reads it with `propOr` on every
  frame, and so the speed of a running Vulkan scene changes from a sibling box without the scene box
  being re-run or recompiled. Run on 2026-08-20 through `field_backdoor.py --port 9601`: the scene
  reported `{builtBy = "_moduleHere", vertices = 3, pushConstantBytes = 16, …}` — a 16-byte
  push-constant block is a real pipeline and not a description of one — and the live box, run twice
  three seconds apart, took `spinRate` from 0.04 to 0.3 while `framesDrawn` went 3309 → 5859. That
  is 2550 frames drawn between two reads, each of them re-reading the shared slot.

  **There is no capture beside it, on purpose.** The native-window capture path timed out in that
  session, and so did the *trusted control* — the existing `fieldml_frames_demo`, whose committed
  captures prove it worked before, failed identically at
  `native-screenshot`/`wait-native-renderer` with "Timed out waiting for native renderer". That
  control is what says the fault is the capture path in this environment and not the scene, and it is
  also what stopped an hour being spent debugging the wrong thing. A synthesised image would have
  been worse than the gap. The next night with a working capture path should take `spin-*.png`-style
  frames of it, and the frame counter above is the measurement to reproduce first.
  `FieldMLSmokeExamplesTest.theModuleHereDemoTypeChecksAndKeepsItsSubject` guards both subjects, the
  live-parameter one **bidirectionally** — the slot the live box writes has to be the slot the scene
  reads, and it has to be read *inside* `eachFrame`, because read once at build time it is a constant.

- `public/docs/assets/fieldml_mesh_demo/metadata.edn` — **the wall past the moving one**, landed
  2026-08-19 and missing from this page until 2026-08-20. Its subject is a vertex shader with *real
  inputs*: two `layout(location = …) in` variables whose data FieldML recomputes every frame and
  writes through `Mesh.write`, one call positional and one **named**. The named one is why it could not
  have been written before mechanism C — it is the shape that omits `Mesh.write`'s `inside` default.
  Self-contained in the same way the frames demo is, and `ring-0.png` … `ring-3.png` beside it are the
  captures from a real device. `FieldMLSmokeExamplesTest.theMeshDemoTypeChecksAndKeepsItsSubject`
  guards its subject rather than its syntax: lose either shader input, or either shape of `write`, and
  it fails.

The first two were verified by copying them into a throwaway workspace and driving
`tools/field_backdoor.py --workspace <ws> --with-playwright --command "open <Doc>" --command "run
<box>" --command "screenshot-box <box> …"`. Note that the backdoor only forwards `--workspace` when
it *starts* the server, which is why this is one invocation rather than a running Field plus a
client; a `boxes: []` reply almost always means a different workspace, not a broken document.
Their code is also compiled and evaluated by `FieldMLSmokeExamplesTest`, so neither can rot silently.

One layout gotcha, learned the hard way: a `drawSized`/`showImg` output canvas is placed *directly
below* its box in world coordinates and is as tall as the size you asked for, so a child box placed
under a drawing box will be hidden behind that canvas. Leave the drawn height plus a margin between
rows.
