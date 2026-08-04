# Pattern-rewrite strategies

The pattern-rewrite layer (`afield.macros.PatternRewrite`, authored via `afield.macros.RewriteDsl`)
lets a live Field box declare source-to-source rewrite rules that run as a PSI preprocessor in front
of `kotlinc`. By default it is a **global fixpoint**: every rule fires at every match, everywhere,
re-parsing and repeating until nothing changes. That is confluent and convenient, but gives the
author no control over *which* rule fires *where* or *how often*.

The **strategy sublanguage** adds that control — a small Stratego/ELAN-style term-rewriting language
layered on the existing collector/matcher. It is fully backward compatible: **with no `strategy {}`
block the behaviour is exactly the old global fixpoint.**

## Named rules

A strategy refers to rules by name, so name the ones it drives:

```kotlin
rule("negate") { x: Expr -> from { neg(x) }; to { -x } }
```

`rule("name") { … }` is identical to the anonymous `rewrite { … }` — same
`from` / `to` / `where` / `provide` / `ai` body, same erase-before-compile treatment — it just
carries a name. Anonymous `rewrite {}` rules keep working; they simply cannot be named by a strategy.

## The `strategy {}` block

One strategy expression, built from the combinators below. Erased and blanked before compile like
the rest of the DSL, so it only needs to *parse*.

```kotlin
rule("unwrap") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { bottomup("unwrap") }        // rewrite the innermost f(...) only
```

### Combinators

A strategy either *succeeds* (yielding a possibly-changed source) or *fails* (does not apply). That
success/failure is what makes `choice` and fail-fast `seq` meaningful.

| Form | Meaning |
|------|---------|
| `"r"` / `r` | Fire rule `r` at **every outermost** non-overlapping site, one pass. Fails if none match. |
| `once("r")` | Fire `r` at the **single first (outermost)** site — at most once. |
| `topdown("r")` | Fire `r` at the single **outermost** (shallowest) site. |
| `bottomup("r")` | Fire `r` at the single **innermost** (deepest) site. Differs from `topdown` on nested forms: given `f(f(a))`, `topdown` rewrites the outer call, `bottomup` the inner one. |
| `attempt(s)` | Run `s`; on failure fall back to identity. Never fails. (`try` is a Kotlin hard keyword, hence `attempt`.) |
| `repeat(s)` / `repeat(s, n)` | Iterate `s` to a fixpoint / at most `n` times. Never fails. |
| `seq(a, b, …)` / `a then b` | Run in order, threading the result; **fail-fast** (aborts if any step fails). |
| `choice(a, b, …)` | Run the **first** alternative that succeeds; fail only if none do. |

`once("r")` and `topdown("r")` coincide on the single-outermost case and are offered as two
spellings of the same intent (`once` = "at most once", `topdown` = "the shallowest match"). Compose
them for iterating traversals, e.g. `repeat(topdown("r"))` rewrites outermost-first to a fixpoint.

### Fail-closed

An unknown rule name or a malformed strategy expression collapses the **whole** program: it rewrites
nothing and logs an `onDiagnostic` message (mirroring the malformed-pattern rejection in the rule
collector). A typo never silently rewrites with a half-understood program.

### Scope

A strategy is **box-local control**. Ambient *rules* (declared in an ancestor box) are still
inherited and can be named by a strategy, but ambient *strategies* are not — each box drives its own.
`provide {}` / `ai()` side-contributions still accrue whenever a rule actually fires under a strategy.

## Implementation

- Parse (`PatternRewrite.parseStrategy` / `parseStrat`): the `strategy {}` body PSI → a `Strat` AST,
  validating every rule reference against the collected named-rule set.
- Interpret (`PatternRewrite.StrategyRunner`): walks the `Strat` over the whole-file source text,
  reusing the exact matcher, substitution, hygiene, guard and side-contribution machinery of the
  default fixpoint path. `bottomup` uses `findDeepestMatch` (descends fully, keeps the deepest hit);
  everything else reuses `findMatches` restricted to one rule.
- `expand()` branches: a local `strategy {}` present ⇒ `StrategyRunner`; otherwise the untouched
  global-fixpoint `while` loop.

## Try it

Replay the shipped demo into a fresh Field4 (each box prints its result and `check()`s the expected
value, so the replay proves every combinator fires and changes the output):

```
python3 tools/run_field_tests.py --id examples.demo-transcript.exec --port <free-port>
```

The transcript is `dev_docs/agents/transcripts/rewrite_strategies_demo.yaml`
(`baseline` / `once` / `subset` / `topdown` / `bottomup` / `then` / `choice` / `repeat` /
`nested pattern`).

A pattern may itself be nested — `from { f(f(x)) }` matches a pair of calls and binds `x` to what
they wrap — and a strategy drives it like any other rule, so each `topdown` step consumes two levels:
`repeat(topdown("fg"), 2)` takes `f(f(f(f(1))))` to `g(g(1))` (= 201 for `f(v) = v * 2`,
`g(v) = v + 100`). Covered by `testStrategyNestedPatternUnderRepeat` and the transcript's
`rw_nested` box.

Coverage lives in `afield.PatternRewriteSmokeTest` (`kotlin.pattern-rewrite.smoke`) — each strategy
case asserts a result *different from the default global fixpoint*, which is the proof it does
something.
