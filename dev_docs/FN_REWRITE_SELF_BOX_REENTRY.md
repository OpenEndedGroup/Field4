# `rewriteKotlin` re-enters its own box

A `@{...}` right-hand side in an `Fn` rewrite spec is compiled on **the box that is running the
rewrite**. Calling `rewriteKotlin` from a box body therefore asks that box's engine to evaluate while
it is already mid-evaluation, and the engine responds by re-running the body — once per matched
expression, recursively, until the stack gives out. **Guarded since 2026-09-09** — see the next
section; the numbers below are what the unguarded path did, and are why the guard exists.

This is the same hazard [`SourceBackedBlock`](../src/jvmMain/kotlin/afield/stdlib/SourceBackedBlock.kt)
documents in its KDoc (measured there at 327 nested runs and 355 seconds), reached by a second route.
`SourceBackedFnRewriteNumberExpression.hydrate` calls `sourceBackedBlockBox(boxId)`, which returns
`Box.context.get()` when the ids match — and they always match, because `kotlinRewriteOptions` takes
the box id from `Box.context` at the same moment.

## Guarded since 2026-09-09, and the guard turned out to be most of a fix

`FnRewriteSelfBoxReentry` refuses the **second** hydration for a box on one thread — a `ThreadLocal`
set of in-flight box ids, since the whole recursion is on one stack. Second rather than first because
the first is indistinguishable from the legitimate cases (a frame loop, a control callback), where the
body has finished and the hydration is not nested inside another; refusing on the first would break
them.

Refusing the second hydration **breaks the recursion**, and the effect is larger than a better error:

| | before the guard | after |
|---|---|---|
| body entries | 172 | **2** |
| outcome | `StackOverflowError`, never returns | **rewrite completes, `f(1.0) = 0.0656`** |

That value is the one the two-box form produces independently, so the one-box spelling now returns the
right answer. **It is still not the form to write:** the body runs twice, so anything it does before
the rewrite happens twice, and the second run dies partway through. Two entries is one too many.

The refusal is **logged as well as thrown**, on channel `error,fn-rewrite`. It has to be: the throw
lands inside a *re-entrant* run of the body, and whatever instantiates the snippet chain absorbs it
without reporting — measured, an unlogged guard left no trace anywhere, `error-lines=0`, and the only
tell was the doubled `println`. That is also why the log line and not a box error output is what
`functions.rewrite-kotlin.live-box` gates on.

**This closes the docs blocker.** `functions_examples.md -> reference/fn-rewrite-language-plot.png`
had failed three consecutive certifying runs with a `StackOverflowError`, each ~1h10m in. Verified
2026-09-09 with the bounded check — one page, not a whole build:

```bash
MODE=always python3 tools/build_zensical_docs.py --page functions_examples.md
```

`regenerated=106 … failures=0` in 1274.5s, no `public/docs-runner-failures/` entry for it, and the
asset rewritten for the first time since 2026-08-04. Inspected rather than trusted: three panels at
1024/1024 samples, and the `after - before` delta is non-zero, so the `@{...}` ran and moved the
signal. Note that `MODE=always` re-encodes **every** asset on the page — 98 of the 99 modified files
were encode churn and want reverting.

What is *not* fixed is the engine half: snippet instantiation is still O(chain) and recursive, which
is the ceiling that `#59`/`#81`-shaped "a long-lived session degrades" reports keep hitting.

## The measurement

`functions.rewrite-kotlin.live-box` (a `jvm-main` FIELD_TEST,
[`FnRewriteKotlinLiveBoxProbe.kt`](../src/jvmMain/kotlin/afield/examples/functions/FnRewriteKotlinLiveBoxProbe.kt))
runs `public/docs/functions_examples.md`'s `fn-rewrite-language-plot` spec in one live box, headlessly,
and counts entries to the body from inside it. Measured 2026-09-09, with the early exit removed so the
run went the distance:

| | |
|---|---|
| body entries | **172** |
| lambda compiles | **285** — 171 of `1.0 + depth * 0.08`, 112 of `if (parentOp == "sin") …` |
| highest snippet index | `fn_rewrite_probe__code_$284` |
| failure | `Unable to instantiate class Fn_rewrite_probe__code__81: java.lang.StackOverflowError` |
| `evaluate=` for one fixed 115-character snippet | 1.58s, then 2.14s, then 2.69s |

Two things follow from the tally. **The multiplier is recursion depth, not match count**: 171 compiles
for 172 body entries is one per level, and the spec matches 11 numeric nodes, so a per-node multiplier
would have produced multiples of 11. And **the cost per compile grows with the chain** — the same
115-character snippet takes 1.58s, then 2.14s, then 2.69s to evaluate, which is the engine's O(chain)
instantiation cost, unrelated to the snippet itself.

The 112 compiles of the *second* expression are the unwind: each of the 172 suspended bodies resumes
its own rewrite after the deeper one fails, gets past the first rule, and hydrates the second rule's
expression with its own freshly-built evaluator.

## The two-box form works — measured, not assumed

`functions.rewrite-kotlin.two-box` is the positive control, and without it "the rewrite blows up" and
"`@{...}` cannot work at all" would be the same observation. The same spec, the same graph, the only
difference being that the snippet is compiled in a **second, idle** box via
[`Box.rewriteFn`](../src/jvmMain/kotlin/afield/stdlib/FunctionRewriteKotlin.kt) — the receiver form
whose KDoc exists for callers "not running inside the box whose context the snippets should compile
in":

| | one box, unguarded | one box, guarded | two boxes |
|---|---|---|---|
| body entries | **172** | **2** | **1** |
| result | `StackOverflowError` | completes | returns in **6.6s** |
| `f(1.0)` before → after | — | → 0.0656 | **−0.3517 → 0.0656** |

The value moving is the part worth keeping: the `@{...}` Kotlin really ran and really changed the
sampled signal, so this is a working rewrite rather than a rewrite that merely finished. And the two
right-hand columns agreeing on `0.0656` is what says the guard breaks the recursion without changing
the answer.

The loadable version is [`fn_rewrite_kotlin_boxes.yaml`](agents/transcripts/fn_rewrite_kotlin_boxes.yaml)
— three boxes, all executed now that the guard makes the one-box form terminate. It takes its host from
**topology** rather than a hard-coded id: `parents.lastOrNull()`, so whatever the box is connected
beneath is where the snippets compile, and dragging in a different parent moves the rewrite with it.

### One trap when writing the two-box form in a box

`?: error("execute the host box first")` does **not** throw. Inside a box, `error` resolves to
`HasOutput.error(message, property, line)` (`outputs.kt:42`), which returns `Unit`, so the elvis
widens the expression to `Any` and the failure surfaces at the *next* line as

```
Candidate 'fun Box.rewriteFn(...)' is inapplicable because of a receiver type mismatch.
```

which says nothing about `error`. Use an explicit `?: throw IllegalStateException(...)`.

## `SourceBackedBlock` has the same guard, on the same in-flight set

`SourceBackedBlockSelfBoxReentry` is the identical guard on the other route: a rehydrating block
invoked from inside its own box's running body. Its KDoc had documented the hazard at 327 nested runs
since the `keyframe_code_blend` split, but nothing had ever driven it — `kotlin.source-backed-block.{self-box,two-box}`
are the first tests that do. Measured 2026-09-09:

| | self-box | idle parent |
|---|---|---|
| body entries | **2** (was 327) | **1** |
| outcome | completes, `b() = 3.0` | completes, `b() = 3.0` |
| guard message | `error,source-block` | — |

**The two guards share one in-flight set** ([`hydratingBoxes`](../src/jvmMain/kotlin/afield/stdlib/SourceBackedBlock.kt)),
deliberately rather than one each: both routes hydrate by evaluating a snippet on a box's own engine,
so a `SourceBackedBlock` invoked from a body that a `@{...}` hydration is already re-running is the
same recursion arriving by two doors. Two sets would let that mixed case through.

Both guards also had to be **logged** rather than only thrown, for the same measured reason —
`error-lines=0` on both routes, the throw absorbed by the snippet-chain instantiation, the doubled
`println` the only tell.

## Why a negative cache does not help

`SourceBackedFnRewriteNumberExpression.hydratedBlock` is assigned only when `hydrate()` returns
normally, so the memo caches successes and not failures. That is true, and it is not this bug:

- A hydration failure is **not** retried per matched node. Nothing between `hydrate()` and the caller
  catches — not `applyNumberAction`, not `previewRewrite`'s per-node lambda, not `deepCopy`'s
  recursion — so the first failure ends the whole rewrite with the other matched nodes unvisited.
  Fenced by [`FnRewriteHydrateFailureTest`](../src/jvmTest/kotlin/afield/stdlib/FnRewriteHydrateFailureTest.kt):
  11 matching nodes, one attempt.
- The repeat compiles above are on **different objects**. Every level runs a fresh `rewriteKotlin`
  call, which builds a fresh `SourceBackedFnRewriteNumberEvaluator` and thus a fresh expression with
  an empty memo. Remembering a failure on one of them cannot stop the next.

What the missing negative cache *does* cost is narrower: an evaluator reused across rewrites — the
form `SourceBackedFnRewriteNumberEvaluator`'s KDoc recommends for a loop — re-attempts a known-bad
compile once per rewrite call. Once per call, never once per node.

## Working around it today

Split the work in two, as `dev_docs/agents/transcripts/keyframe_code_blend.yaml` does for
`SourceBackedBlock`: build the spec in one box and run the rewrite from **another** box, a frame loop,
or a control callback — `host.rewriteFn(fn, spec)` rather than `fn.rewriteKotlin(spec)`. Hydrating
into a box that is idle replays nothing, which the table above measures rather than assumes. A
`fnRewriteNumber { }` evaluator carries a live lambda, never hydrates, and is safe anywhere — it is
the typed alternative and the reliable one.
