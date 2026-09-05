# Rewriting your own syntax

Most of the time you write the code you mean. But sometimes the code you mean is
a *shape* — every `neg(x)` should become `-x`, every `f(x)` should be wrapped in
a runtime coercion, every `log(tag, …)` should route through your logger — and
writing each occurrence by hand is exactly the busywork a language should absorb.

The pattern-rewrite layer lets a live box declare those shapes as rules. You
author a `rewrite { … }` block right in a box, in Kotlin syntax, and it rewrites
your source before the compiler ever sees it. It is a small, structural
term-rewriting system that runs as a preprocessor in front of the Kotlin engine.

## The mental model

Rule behaviour follows from three facts:

- **It is source-to-source.** A rule matches a piece of your syntax tree and
  splices a replacement in its place. The output is ordinary Kotlin source that
  is then compiled and run exactly as if you had typed it.
- **It works on parse trees, not types.** Matching runs on the PSI (the parsed
  syntax tree) with *no name resolution*. That is what makes it cheap enough to
  re-run on every keystroke, and why the pattern body `from { max(x, y) }` is
  legal even though `max`, `x` and `y` resolve to nothing. A rule body only has
  to *parse*; it never has to *type-check*.
- **The rules are erased before compilation.** The `rewrite { … }` blocks are
  collected and deleted from your source before the compiler runs, so `rewrite`,
  `from`, `to` and the metavariable kinds never actually execute. They exist
  only so your rule parses and reads sensibly.

That last point is also the *quote barrier*: because a rule's own pattern body
is blanked out with the block, the pattern `max(x, y)` is never itself rewritten
by another rule. Patterns are quoted data, not live code.

To use the DSL, import it:

```diffstatic {data-lang=kotlin}
import afield.macros.*
```

## Your first rule: negation

A rule is a `rewrite { … }` whose lambda parameters declare the
*metavariables*, and whose body holds one `from { }` (the pattern to match) and
one `to { }` (the replacement):

```diffstatic {data-lang=kotlin}
rewrite { x: Expr -> from { neg(x) }; to { -x } }

neg(10)          // →  -10
neg(a)           // →  -a
```

`x: Expr` says "`x` is a hole that binds any expression." Inside `from` and
`to`, a name is a hole exactly when it is one of those declared parameters —
there is no `$x` or `unquote` escape to remember, the parameter list *is* the
set of holes. `neg` is not a parameter, so it must match literally; `pos(a)`
does not match this rule and is left untouched.

## Metavariables, kinds, and precedence

A metavariable's declared type names its **kind** — what syntax it is allowed to
bind:

| Kind      | Binds                                    |
|-----------|------------------------------------------|
| `Expr`    | any expression (the common case)         |
| `Ident`   | a bare name reference                    |
| `Stmt`    | a statement-position element             |
| `TypeRef` | a type reference                         |
| `Vararg`  | a *run* of call arguments (see below)    |

Holes are matched structurally and modulo redundant parentheses, so `x + y`
matches `(a) + (b)`. A repeated hole must bind structurally equal subtrees:
`from { f(x, x) }` matches `f(a, a)` but not `f(a, b)`. Arity and leaf tokens
are part of the shape too — `f(x, y)` matches only two-argument calls, and
`x + y` will not match `a - b`.

When a captured subtree is spliced into a new syntactic context, the rewriter
**paren-guards** it so text splicing can never silently change precedence:

```diffstatic {data-lang=kotlin}
rewrite { x: Expr -> from { twice(x) }; to { x * 2 } }

twice(a)         // →  a * 2          (atomic capture, not wrapped)
twice(3)         // →  3 * 2
twice(a + b)     // →  (a + b) * 2    (non-atomic, wrapped — never a + b * 2)
```

Atomic captures (names, literals, calls, `a.b`, `a[i]`) are left unwrapped;
anything looser gets parentheses.

Names the `to {}` side *introduces* (locals it declares, not values carried in
through a metavariable) are gensym-renamed so they cannot capture or shadow
your spliced code — a rule that emits `run { val acc = x; acc + acc }` becomes
`run { val acc__hyg0 = a; acc__hyg0 + acc__hyg0 }`, leaving any `acc` of yours
alone.

## Guards: `where { }`

A rule can carry a structural guard; it fires only when the guard holds. The
guard is built from a fixed vocabulary of predicates over the captures, combined
with `&&`, `||`, `!`:

```diffstatic {data-lang=kotlin}
rewrite { x: Expr, y: Expr ->
    from { swap(x, y) }
    where { distinct(x, y) }
    to { swapped(y, x) }
}

swap(a, b)       // →  swapped(b, a)   (distinct holds)
swap(c, c)       // →  swap(c, c)      (guard fails; left untouched)
```

The vocabulary:

- `distinct(…)` — all listed captures are pairwise structurally different.
- `identical(a, b)` — two captures are structurally equal.
- `isLiteral(x)` — a numeric/boolean/char constant or a string template.
- `isIdentifier(x)` — a bare name reference.
- `isCall(x)` — a function or constructor call.
- `isCallTo(x, "name")` — a call whose callee is exactly `name`.

Guards are evaluated *structurally* at rewrite time — no code runs, no types are
consulted — so only this fixed vocabulary is understood. An unknown predicate
makes the rule fail closed (it never fires) with a warning, rather than doing
something surprising.

### The termination idiom

`isCallTo` earns its keep in the type-directed coercion pattern: wrap every
`f(x)` in a runtime adapter, but *guard against re-wrapping the wrapper you just
introduced*, so the rewrite reaches a fixed point instead of looping forever:

```diffstatic {data-lang=kotlin}
rewrite { x: Expr ->
    from { f(x) }
    where { !isCallTo(x, "coerce") }
    to { f(coerce(::f, SomeType::class, x)) }
}

f(a)             // →  f(coerce(::f, SomeType::class, a))
f(b + 1)         // →  f(coerce(::f, SomeType::class, (b + 1)))
```

On the second pass the argument is now `coerce(…)`, so `!isCallTo(x, "coerce")`
is false and the rule stops. Without that guard the rewriter would wrap the
wrapper again and again until it hit the iteration cap. The live `Coerce Rewrite`
box in the authoring workspace uses this idiom, with a box-scoped `_coerce`
helper.

## Preamble contributions: `provide { }`

A rule can contribute box-level declarations when it fires. The `provide {}`
block is metavar-substituted and merged into the box preamble under a stable,
content-derived key, so repeated runs deduplicate and the segment only
re-executes when its content actually changes:

```diffstatic {data-lang=kotlin}
rewrite { n: Ident ->
    from { counter(n) }
    provide { var Box.n by Counting() }
    to { n }
}

counter(hits)    // →  hits
                 //    + preamble:  var Box.hits by Counting()
```

The metavariable is substituted in the *declaration name* position (`n` →
`hits`), and the emitted preamble segment is wrapped as a keyed
`/*start*/ … /*end*/` block — the "stack mark" idempotency mechanism the preamble
machinery understands. Firing the same rule twice with the same capture
contributes the segment once; distinct captures get distinct keys. (Unlike the `to {}` side,
`provide {}` names are *not* gensym-renamed — they are meant to define stable, shared
box-level names.)

## Running code at rewrite time: `unquote { }`

Sometimes the replacement is not a fixed shape but the *result of a
computation*. Inside a `to {}` body, `unquote { … }` runs Kotlin **at rewrite
time** and splices its `toString()` result into the emitted source **as Kotlin
source code**. Each metavariable is available inside the block as a `String`
holding the captured source text, so you can transform captures textually:

```diffstatic {data-lang=kotlin}
rewrite { x: Ident ->
    from { rev(x) }
    to { unquote { "\"" + x.reversed() + "\"" } }
}

rev(hello)       // →  "olleh"     (a String literal)
```

Because the result is spliced as *code*, you must produce valid Kotlin. Here the
block builds a quoted string literal, so `rev(hello)` becomes the literal
`"olleh"`. (Emitting `unquote { x.reversed() }` would splice the bare token
`olleh`, an unresolved reference — a classic gotcha.) The block runs in the
box's own REPL context, so it can call box-scoped helpers.

Evaluation is fail-soft: on the execute path a failing block becomes a compiling
`error(...)` stand-in with a logged diagnostic, and in editor-preview
expansions — where your code is not run — the call becomes a well-typed `TODO()`
placeholder.

## Variadic holes

A `Vararg` metavariable binds a *run* of call arguments (zero or more). In a
pattern it consumes the matching arguments; in the replacement it expands them
comma-separated. It composes with fixed holes on either side:

```diffstatic {data-lang=kotlin}
rewrite { first: Expr, rest: Vararg ->
    from { log(first, rest) }
    to { logger(first, rest) }
}

log(tag, a, b, c)    // →  logger(tag, a, b, c)
log(tag)             // →  logger(tag)          (the run may be empty)
```

## Ambient rules: rules descend the box graph

A rule is not confined to the box that declares it. Rules are **inherited by
descendant boxes**: a `rewrite { … }` written in a parent box applies to the
parent's own code *and* to every child's code — and the parent never has to run
for this to take effect, because the layer reads each box's stored source
directly.

```diffstatic {data-lang=kotlin}
// in a PARENT box:
rewrite { x: Expr -> from { boom(x) }; to { BANG(x) } }

// in a CHILD box, with no rule of its own:
boom(hello)          // →  BANG(hello)
```

When a child declares a rule with the same pattern as an inherited one, the
local rule wins — the same declaration-order, first-match-wins rule that governs
two rules in a single box. This is how the authoring workspace is laid out: a
`Coerce Rewrite` parent box holds the coercion rule, and a `Double Increment`
child inherits it without repeating it.

## Convergence and the termination cap

Rewriting runs to a fixed point: it keeps finding and replacing outermost
matches until a pass changes nothing. Most rule sets converge quickly (the
coercion idiom above is the canonical "converges because a guard stops the
recursion" case). A non-confluent set does not — the classic example is two
rules that undo each other:

```diffstatic {data-lang=kotlin}
rewrite { -> from { a }; to { b } }
rewrite { -> from { b }; to { a } }
```

Rather than hang your box, the rewriter stops after a bounded number of
iterations (64 by default) and reports a `pattern-rewrite did not converge …`
diagnostic, keeping whatever it expanded so far. When you see that message, look
for a cycle in your rules or a missing termination guard.

Two smaller rules govern the rest:

- Declaration order decides ties: when two rules match the same spot, the one
  declared first wins, and no other rule fires there that round.
- A malformed rule is never silently dropped or silently degenerated into a
  too-permissive pattern (which would run away). A rule whose pattern is a lone
  metavariable, or whose body fails to parse, is rejected with a diagnostic and
  its block is still erased from the compiled source.

## Controlling where and when rules fire: strategies

Everything above uses the **default engine**: every rule fires at every match,
everywhere, to a fixed point. That is confluent and convenient, and for most
rule sets it is exactly what you want. But sometimes you need control — rewrite
*one* site, not all of them; rewrite the *innermost* form rather than the
outermost; run rule A only where rule B did not apply; apply a step a bounded
number of times. That is what **strategies** add: a small
[Stratego](https://strategoxt.org/)/ELAN-style term-rewriting language layered
on top of the same matcher.

**With no `strategy { }` block the engine runs the global fixpoint.** A strategy
takes over only when you write one, and then only the rules it names fire, in the
order and at the sites it dictates.

### Name the rules a strategy drives

A strategy refers to rules by name, so use `rule("name") { … }` instead of the
anonymous `rewrite { … }`. Everything else about the rule — `from`/`to`/`where`/
`provide`/`ai`, the erase-before-compile treatment — is identical. A named rule
with *no* strategy still behaves exactly like the anonymous global fixpoint:

```diffstatic {data-lang=kotlin}
rule("neg") { x: Expr -> from { neg(x) }; to { -x } }

neg(a)           // →  -a      (no strategy: every site, as before)
neg(b)           // →  -b
```

Anonymous `rewrite { }` rules carry no name, so a `strategy { }` can never fire
them — under a strategy they are simply inert.

### The `strategy { }` block

One `strategy { }` block per box holds a single strategy expression, built from
the combinators below. Like a rule, it is erased before compilation, so it only
has to parse. Each combinator either *succeeds* (yielding possibly-changed
source) or *fails* (does not apply) — that success/failure is what makes `choice`
and fail-fast sequencing meaningful.

**Fire once, or at a chosen depth.** By default the fixpoint hits both `neg`
sites; `once` stops after the first:

```diffstatic {data-lang=kotlin}
rule("neg") { x: Expr -> from { neg(x) }; to { -x } }
strategy { once("neg") }

neg(a)           // →  -a         (first site fires)
neg(b)           // →  neg(b)     (left untouched — the observable difference)
```

For nested forms, `topdown` and `bottomup` pick opposite ends. Given `f(f(a))`
and a rule `f(x) → g(x)`:

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }

strategy { topdown("fg") }    //  f(f(a))  →  g(f(a))   (outer call only)
strategy { bottomup("fg") }   //  f(f(a))  →  f(g(a))   (inner call only)
```

Each is a *single* application, not a fixpoint — `topdown` does not go on to
rewrite the inner `f`. To drive it to a fixed point, wrap it in `repeat`.

**Select a subset of rules.** Naming one rule fires only that rule, even when
others would match — unlike the default, which fires every rule:

```diffstatic {data-lang=kotlin}
rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
rule("q2b") { x: Expr -> from { q(x) }; to { B(x) } }
strategy { "p2a" }

p(1)             // →  A(1)
q(2)             // →  q(2)      (q2b is not named, so it never fires)
```

A bare rule name is itself the simplest strategy (fire that rule wherever it
applies).

**Sequence with `then` / `seq`.** `a then b` runs `a`, then `b` on its result,
and fails as soon as either step fails:

```diffstatic {data-lang=kotlin}
rule("p2q") { x: Expr -> from { p(x) }; to { q(x) } }
rule("q2r") { x: Expr -> from { q(x) }; to { r(x) } }

strategy { "p2q" then "q2r" }   //  p(1)  →  q(1)  →  r(1)
strategy { "q2r" then "p2q" }   //  p(1)  →  p(1)  (q2r fails first ⇒ whole sequence aborts)
```

**Choose with `choice`.** `choice(a, b, …)` runs the first alternative that
applies. Because it is ordered independently of declaration order, it can pick a
rule the default first-match-wins would not:

```diffstatic {data-lang=kotlin}
rule("toX") { x: Expr -> from { pick(x) }; to { X(x) } }
rule("toY") { x: Expr -> from { pick(x) }; to { Y(x) } }
strategy { choice("toY", "toX") }

pick(1)          // →  Y(1)      (prefers its first alternative, though toX is declared first)
```

A non-matching alternative falls through to the next; `choice` fails only if
none apply.

**Bound repetition and rescue failure.** `repeat(s)` iterates to a fixpoint;
`repeat(s, n)` at most `n` times. `attempt(s)` runs `s` but falls back to
identity on failure, so a following step can still run:

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }

strategy { repeat(topdown("fg"), 1) }   //  f(f(f(a)))  →  g(f(f(a)))   (exactly one step)
strategy { repeat(topdown("fg")) }      //  f(f(a))     →  g(g(a))      (to a fixpoint)

rule("zzz") { x: Expr -> from { zzz(x) }; to { Z(x) } }
strategy { attempt("zzz") then "fg" }   //  f(a)  →  g(a)   (the missing zzz is rescued, fg still runs)
```

**Nested patterns.** Nothing says a pattern has to be one level deep. `from { f(f(x)) }`
matches a *pair* of nested calls and binds `x` to whatever they wrap, and a strategy
drives it like any other rule — so each `topdown` step eats two levels at a time:

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr -> from { f(f(x)) }; to { g(x) } }
strategy { repeat(topdown("fg"), 2) }

f(f(f(f(a))))    // →  g(g(a))    (step 1 collapses the outer pair, step 2 the inner)
```

With real definitions behind those names — `fun f(v: Int) = v * 2` and
`fun g(v: Int) = v + 100` — `f(f(f(f(1))))` evaluates to **201**, not the 16 the
unrewritten source would give. The `nested pattern` box of the
`rewrite_strategies_demo` transcript below runs exactly that and `check`s the 201.

### The combinators

| Strategy | Effect |
|----------|--------|
| `"name"` | Fire rule `name` wherever it applies. |
| `once("name")` | Fire at the single first (outermost) site — at most once. |
| `topdown("name")` | Fire at the single outermost (shallowest) match. |
| `bottomup("name")` | Fire at the single innermost (deepest) match. |
| `attempt(s)` | Run `s`; fall back to identity on failure. Never fails. |
| `repeat(s)` / `repeat(s, n)` | Iterate `s` to a fixpoint / at most `n` times. Never fails. |
| `a then b`, `seq(a, b, …)` | Sequence, threading the result; fail-fast. |
| `choice(a, b, …)` | First alternative that succeeds; fail only if none do. |

`once("r")` and `topdown("r")` coincide on the single-outermost case and are
offered as two spellings of the same intent. (`try` would be the usual name for
`attempt`, but it is a Kotlin hard keyword and cannot be a call name.)

### Fail-closed

A strategy that names an unknown rule, or that is malformed, is treated the same
way a malformed rule is: it rewrites **nothing** and reports a diagnostic, rather
than silently doing something surprising.

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
strategy { once("nope") }   //  f(a)  →  f(a)   + "unknown rule name" diagnostic
strategy { 1 + 2 }          //  f(a)  →  f(a)   + malformed-strategy diagnostic
```

A strategy is **box-local control**: ambient *rules* declared in an ancestor box
are still inherited and can be named by a strategy, but a strategy itself is not
inherited — each box drives its own. `provide { }` and `ai()` side-contributions
still accrue whenever a rule actually fires under a strategy.

`dev_docs/pattern_rewrite_strategies.md` documents the `Strat` AST and the
`StrategyRunner` that executes it.

## Try it

Every "→" on this page is a string the pattern-rewrite smoke test
(`src/jvmMain/kotlin/afield/PatternRewriteSmokeTest.kt`) asserts on. Two runnable
demos ship with the engine as well, both driven through the identical
compile-and-run pipeline a live box uses:

- `dev_docs/agents/transcripts/pattern_rewrite_worked_example.yaml` — a box graph
  exercising negation, the precedence guard, the `isCallTo` termination idiom, a
  variadic hole, `unquote`, and an inherited (ambient) rule.
- `dev_docs/agents/transcripts/rewrite_strategies_demo.yaml` — named rules driven
  by `once` / `topdown` / `bottomup` / `then` / `choice` / `repeat`, plus a nested
  two-level pattern, each box self-checking that the strategy produced a
  *different* result than the default fixpoint would.

Replay every shipped demo transcript — including both of these — through the real
engine with:

```diffstatic {data-lang=bash}
python3 tools/run_field_tests.py --id examples.demo-transcript.exec
```
