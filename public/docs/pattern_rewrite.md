# Rewriting your own syntax

Most of the time you write the code you mean. But sometimes the code you mean is
a *shape* — every `neg(x)` should become `-x`, every `f(x)` should be wrapped in
a runtime coercion, every `log(tag, …)` should route through your logger — and
writing each occurrence by hand is exactly the busywork a language should absorb.

The pattern-rewrite layer lets a live box declare those shapes as rules. You
author a `rewrite { … }` block right in a box, in Kotlin syntax, and it rewrites
your source before the compiler ever sees it. It is a small, structural
term-rewriting system that runs as a preprocessor in front of the Kotlin engine.

Every rule and every expansion on this page is real: the rules are the ones the
[pattern-rewrite smoke test][smoke] actually exercises, and each "→" shows the
source that expansion genuinely emits (the smoke asserts on exactly those
strings). The end of the page points at a demo you can replay to watch boxes
expand and run.

[smoke]: https://github.com/ — see `src/jvmMain/kotlin/afield/PatternRewriteSmokeTest.kt`

## The mental model

Three facts explain almost everything about how rules behave:

- **It is source-to-source.** A rule matches a piece of your syntax tree and
  splices a replacement in its place. The output is ordinary Kotlin source that
  is then compiled and run exactly as if you had typed it.
- **It works on parse trees, not types.** Matching runs on the PSI (the parsed
  syntax tree) with *no name resolution*. That is the trick that makes it
  cheap and re-runnable on every keystroke — and it is why the pattern body
  `from { max(x, y) }` is legal even though `max`, `x` and `y` resolve to
  nothing. A rule body only has to *parse*; it never has to *type-check*.
- **The rules are erased before compilation.** The `rewrite { … }` blocks are
  collected and deleted from your source before the compiler runs, so `rewrite`,
  `from`, `to` and the metavariable kinds never actually execute. They exist
  only so your rule parses and reads sensibly.

That last point is also the *quote barrier*: because a rule's own pattern body
is blanked out with the block, the pattern `max(x, y)` is never itself rewritten
by another rule. Patterns are quoted data, not live code.

To use the DSL, import it:

```kotlin
import afield.macros.*
```

## Your first rule: negation

A rule is a `rewrite { … }` whose lambda parameters declare the
*metavariables*, and whose body holds one `from { }` (the pattern to match) and
one `to { }` (the replacement):

```kotlin
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

```kotlin
rewrite { x: Expr -> from { twice(x) }; to { x * 2 } }

twice(a)         // →  a * 2          (atomic capture, not wrapped)
twice(3)         // →  3 * 2
twice(a + b)     // →  (a + b) * 2    (non-atomic, wrapped — never a + b * 2)
```

Atomic captures (names, literals, calls, `a.b`, `a[i]`) are left unwrapped;
anything looser gets parentheses. This is the single most important guarantee of
the layer, and it is verified by the smoke's `testPrecedenceHazard` and
`testAtomicCapturesNotOverParenthesized` cases.

Names the `to {}` side *introduces* (locals it declares, not values carried in
through a metavariable) are gensym-renamed so they cannot capture or shadow
your spliced code — a rule that emits `run { val acc = x; acc + acc }` becomes
`run { val acc__hyg0 = a; acc__hyg0 + acc__hyg0 }`, leaving any `acc` of yours
alone.

## Guards: `where { }`

A rule can carry a structural guard; it fires only when the guard holds. The
guard is built from a fixed vocabulary of predicates over the captures, combined
with `&&`, `||`, `!`:

```kotlin
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

```kotlin
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
wrapper again and again until it hit the iteration cap. This is precisely the
idiom the live `Coerce Rewrite` box in the authoring workspace uses (with a
box-scoped `_coerce` helper), and the smoke's `testCoercionIdiomTerminates`
proves it both wraps once *and* converges.

## Preamble contributions: `provide { }`

A rule can contribute box-level declarations when it fires. The `provide {}`
block is metavar-substituted and merged into the box preamble under a stable,
content-derived key, so repeated runs deduplicate and the segment only
re-executes when its content actually changes:

```kotlin
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
`/*start*/ … /*end*/` block — the "stack mark" idempotency mechanism the
existing preamble machinery already understands. Firing the same rule twice with
the same capture contributes the segment once; distinct captures get distinct
keys. (Unlike the `to {}` side, `provide {}` names are deliberately *not*
gensym-renamed — they are meant to define stable, shared box-level names.)

## Running code at rewrite time: `unquote { }`

Sometimes the replacement is not a fixed shape but the *result of a
computation*. Inside a `to {}` body, `unquote { … }` runs Kotlin **at rewrite
time** and splices its `toString()` result into the emitted source **as Kotlin
source code**. Each metavariable is available inside the block as a `String`
holding the captured source text, so you can transform captures textually:

```kotlin
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

```kotlin
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

```kotlin
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

```kotlin
rewrite { -> from { a }; to { b } }
rewrite { -> from { b }; to { a } }
```

Rather than hang your box, the rewriter stops after a bounded number of
iterations (64 by default) and reports a `pattern-rewrite did not converge …`
diagnostic, keeping whatever it expanded so far. When you see that message, look
for a cycle in your rules or a missing termination guard.

Two more things worth knowing:

- Declaration order decides ties: when two rules match the same spot, the one
  declared first wins, and no other rule fires there that round.
- A malformed rule is never silently dropped or silently degenerated into a
  too-permissive pattern (which would run away). A rule whose pattern is a lone
  metavariable, or whose body fails to parse, is rejected with a diagnostic and
  its block is still erased from the compiled source.

## Try it

A runnable demo lives at
`dev_docs/agents/transcripts/pattern_rewrite_worked_example.yaml`. It builds a
small box graph exercising negation, the precedence guard, the `isCallTo`
termination idiom, a variadic hole, `unquote`, and an inherited (ambient) rule,
then runs each box so you can watch the rewrites take effect. Replay it through
the real engine with:

```
python3 tools/run_field_tests.py --id examples.demo-transcript.exec
```

which drives every shipped demo transcript — including this one — through the
identical compile-and-run pipeline a live box uses.
