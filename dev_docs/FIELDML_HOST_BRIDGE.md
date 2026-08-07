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

### 2. `Host` is the dynamic type

`unify` accepts `THost("Host")` against anything, and `requireNumeric`/`requireVector`/
`requireEquality` accept it too. This is not laziness: reflection genuinely cannot type an erased
generic, and without the escape every such result would need a cast before any use — which is the
state this work exists to end. The confinement is that `Host` only *arises* from host interop, and
a host value whose class is known keeps its own name.

The cost is real and worth stating: a program can pass the type checker and fail at run time inside
a `Host`. `hostAsInt`/`hostAsFloat`/`hostAsBool`/`hostAsString`/`hostAs "Class"` are the explicit
way to leave the dynamic region, and `hostAs` doubles as the way FieldML *names* a class.

### 3. SAM conversion has to read the generic type at the use site

`kotlin.jvm.functions.Function2.invoke` erases to `(Object, Object) -> Object`. A proxy built from
the erased signature hands a boxed `Double` to a parameter declared `Function2<Int, Int, Float>` and
the Kotlin body fails its implicit cast. `samProxy` therefore takes the *parameter's*
`genericParameterType`, maps the interface's type variables to the actual arguments, and coerces the
result to the resolved return class. `Img.generate(w, h) { x, y -> … }` is the test that pins this.

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
| `FieldMLHostSurfaceCoverageTest` | The measurement, with floors three points under what it prints. It computes the "before" number in the same run, so the delta is measured rather than remembered. Raise the floors when the bridge learns something. |
| `FieldMLCompletionEngineTest` | The six host completion contexts. |
| `FieldMLSmokeExamplesTest` | Every FieldML snippet on the public pages, **and** every box of the loadable demo document, compiled and evaluated. |
| `FieldMLCheatsheetCoverageTest` | The cheatsheet lists every prelude name with its type. Adding a `host…` name without a row fails the build. |

## The demo document

`public/docs/assets/fieldml_host_bridge_demo/metadata.edn` — a FieldML root (`mainLanguage
"fieldml"`, `isFieldMLRoot true`) with four child boxes. It was verified by copying it into a
throwaway workspace, starting Field with `PORT=… ./gradlew runJvm --args="--workspace …"`, and
driving `tools/field_backdoor.py open FieldMLHostBridgeDemo` + `run` + `screenshot-box` on each box.
Its code is also compiled and evaluated by `FieldMLSmokeExamplesTest`, so it cannot rot silently.
