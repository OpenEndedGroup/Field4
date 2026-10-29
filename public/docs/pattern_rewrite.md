# Rewriting your own syntax

Suppose you want every `neg(x)` to become `-x`, or every `log(tag, …)` to go
through your logger. With `rewrite { … }`, you can write rules that match the
shape of your code and replace it before the compiler sees it.

The rules live in a box and use Kotlin syntax. They can match and rearrange the
source itself, giving you access to details an ordinary function never sees.

## How rules work

- **It is source-to-source.** A rule matches a piece of your syntax tree and
  splices a replacement in its place. The output is ordinary Kotlin source that
  is then compiled and run exactly as if you had typed it.
- **It works on parse trees, not types.** Matching runs on the PSI (the parsed
  syntax tree) with *no name resolution*. That keeps it fast enough to re-run on
  every keystroke, and it is why the pattern body `from { max(x, y) }` is
  legal even though `max`, `x` and `y` resolve to nothing. A rule body only has
  to *parse*; it never has to *type-check*.
- **The rules are erased before compilation.** The `rewrite { … }` blocks are
  collected and deleted from your source before the compiler runs, so `rewrite`,
  `from`, `to` and the metavariable kinds never actually execute. They exist
  only so your rule parses and reads sensibly.

Because a rule's own pattern body is erased with the block, the pattern
`max(x, y)` is never itself rewritten by another rule.

To use the DSL, import it:

```diffstatic {data-lang=kotlin}
import afield.runtime.macros.*
```

## Your first rule: negation

A rule is a `rewrite { … }` whose lambda parameters declare the
*metavariables*, and whose body holds one `from { }` (the pattern to match) and
one `to { }` (the replacement):

```diffstatic {data-lang=kotlin}
rewrite { x: Expr ->
    from { neg(x) }
    to { -x }
}

neg(10) // →  -10
neg(a) // →  -a
```

`x: Expr` makes `x` a hole that can match any expression. Inside `from` and `to`, the declared
parameters are the holes. Everything else matches literally: this rule looks for `neg`, so it
leaves `pos(a)` alone.

## Metavariables, kinds, and precedence

A metavariable's declared type names its **kind**, the syntax it is allowed to
bind:

<div class="field-reference" data-code-lang="kotlin" markdown>

| Declaration | What it captures |
| --- | --- |
| `x: Expr` | Binds any expression, including a call or arithmetic subtree. Splicing it into a new context adds parentheses when needed to preserve precedence. |
| `name: Ident` | Binds a bare name reference. Use it when the replacement must insert an identifier rather than an arbitrary expression. |
| `body: Stmt` | Binds a statement-position syntax element. This constrains the hole to the statement surface of the parsed source. |
| `t: TypeRef` | Binds a type reference in the source tree. It matches written syntax without resolving the referenced type. |
| `args: Vararg` | Binds a consecutive run of call arguments. Reuse the capture to forward the argument sequence into another call. |

</div>

Matching looks at the structure and ignores redundant parentheses, so `x + y` matches
`(a) + (b)`. A repeated hole must match the same structure each time: `from { f(x, x) }` matches
`f(a, a)` but not `f(a, b)`. The number of arguments and the written operators matter too:
`f(x, y)` matches two-argument calls, and `x + y` does not match `a - b`.

When a captured subtree is spliced into a new context, the rewriter adds
parentheses where they are needed, so splicing never changes precedence:

```diffstatic {data-lang=kotlin}
rewrite { x: Expr ->
    from { twice(x) }
    to { x * 2 }
}

twice(a) // →  a * 2          (atomic capture, not wrapped)
twice(3) // →  3 * 2
twice(a + b) // →  (a + b) * 2    (non-atomic, wrapped — never a + b * 2)
```

Atomic captures (names, literals, calls, `a.b`, `a[i]`) are left unwrapped;
anything looser gets parentheses.

Names the `to {}` side *introduces* (locals it declares, not values carried in
through a metavariable) are gensym-renamed so they cannot capture or shadow
your spliced code. A rule that emits `run { val acc = x; acc + acc }` becomes
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

swap(a, b) // →  swapped(b, a)   (distinct holds)
swap(c, c) // →  swap(c, c)      (guard fails; left untouched)
```

The vocabulary:

- `distinct(…)`: all listed captures are pairwise structurally different.
- `identical(a, b)`: two captures are structurally equal.
- `isLiteral(x)`: a numeric/boolean/char constant or a string template.
- `isIdentifier(x)`: a bare name reference.
- `isCall(x)`: a function or constructor call.
- `isCallTo(x, "name")`: a call whose callee is exactly `name`.

Guards are evaluated on the syntax at rewrite time. No code runs and no types
are consulted, so only this vocabulary is understood. A rule with an unknown
predicate never fires, and Field logs a warning.

### Stopping a rule from re-wrapping itself

`isCallTo` is what lets a coercion rule finish. The rule below wraps every
`f(x)` in a runtime adapter, and its guard skips an argument that is already
wrapped, so rewriting stops instead of looping:

```diffstatic {data-lang=kotlin}
rewrite { x: Expr ->
    from { f(x) }
    where { !isCallTo(x, "coerce") }
    to { f(coerce(::f, SomeType::class, x)) }
}

f(a) // →  f(coerce(::f, SomeType::class, a))
f(b + 1) // →  f(coerce(::f, SomeType::class, (b + 1)))
```

On the second pass the argument is now `coerce(…)`, so `!isCallTo(x, "coerce")`
is false and the rule stops. Without the guard the rewriter would wrap the
wrapper again and again until it hit the iteration cap.

## Preamble contributions: `provide { }`

A rule can contribute box-level declarations when it fires. The `provide {}`
block is metavar-substituted and merged into the box preamble under a stable,
content-derived key, so repeated runs deduplicate and the segment only
re-executes when its content actually changes:

```diffstatic {data-lang=kotlin}
rewrite { n: Ident ->
    from { counter(n) }
    provide {
        var Box.n by Counting()
    }
    to { n }
}

counter(hits) // →  hits

//    + preamble:  var Box.hits by Counting()
```

The metavariable is substituted in the declaration name (`n` → `hits`). Firing
the same rule twice with the same capture adds the declaration once; distinct
captures add one each. (Unlike the
`to {}` side, `provide {}` names are *not* gensym-renamed; they are meant to
define stable, shared box-level names.)

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

rev(hello) // →  "olleh"     (a String literal)
```

Because the result is spliced as *code*, you must produce valid Kotlin. Here the
block builds a quoted string literal, so `rev(hello)` becomes the literal
`"olleh"`. (Emitting `unquote { x.reversed() }` would splice the bare token
`olleh`, an unresolved reference.) The block runs in the box's own REPL context,
so it can call box-scoped helpers.

If the block throws, the call becomes `error(...)` when the box runs, with the
failure logged. In the editor's preview of the expansion, where your code does
not run, it shows as `TODO()`.

## Variadic holes

A `Vararg` metavariable binds a *run* of call arguments (zero or more). In a
pattern it consumes the matching arguments; in the replacement it expands them
comma-separated. It composes with fixed holes on either side:

```diffstatic {data-lang=kotlin}
rewrite { first: Expr, rest: Vararg ->
    from { log(first, rest) }
    to { logger(first, rest) }
}

log(tag, a, b, c) // →  logger(tag, a, b, c)
log(tag) // →  logger(tag)          (the run may be empty)
```

## Ambient rules: rules descend the box graph

A rule is not confined to the box that declares it. Rules are **inherited by
descendant boxes**: a `rewrite { … }` written in a parent box applies to the
parent's own code *and* to every child's code. The parent never has to run for
this to take effect, because the layer reads each box's stored source directly.

```diffstatic {data-lang=kotlin}
// in a PARENT box:
rewrite { x: Expr ->
    from { boom(x) }
    to { BANG(x) }
}

// in a CHILD box, with no rule of its own:
boom(hello) // →  BANG(hello)
```

When a child declares a rule with the same pattern as an inherited one, the
local rule wins, by the same declaration-order, first-match-wins tie-break that
governs two rules in a single box. This is how the authoring workspace is laid
out: a `Coerce Rewrite` parent box holds the coercion rule, and a
`Double Increment` child inherits it without repeating it.

## Convergence and the termination cap

Rewriting runs to a fixed point: it keeps finding and replacing outermost
matches until a pass changes nothing. Most rule sets get there quickly; the
coercion rule above gets there because its guard stops the recursion. Two rules
that undo each other never do:

```diffstatic {data-lang=kotlin}
rewrite { ->
    from { a }
    to { b }
}
rewrite { ->
    from { b }
    to { a }
}
```

Rather than hang your box, the rewriter stops after a bounded number of
iterations (64 by default) and reports a `pattern-rewrite did not converge …`
diagnostic, keeping whatever it expanded so far. When you see that message, look
for a cycle in your rules or a missing termination guard.

Two smaller rules govern the rest:

- Declaration order decides ties: when two rules match the same spot, the one
  declared first wins, and no other rule fires there that round.
- A rule whose pattern is a lone metavariable, or whose body fails to parse, is
  rejected with a diagnostic, and its block is still erased from the compiled
  source.

## Controlling where and when rules fire: strategies

Everything above uses the **default engine**: every rule fires at every match,
everywhere, to a fixed point, which suits most rule sets. **Strategies** let you
rewrite one site instead of all of them, rewrite the innermost form first, run
rule A only where rule B did not apply, or apply a step a bounded number of
times. They are a small
[Stratego](https://strategoxt.org/)/ELAN-style term-rewriting language layered
on top of the same matcher.

**With no `strategy { }` block the engine runs the global fixpoint.** A strategy
takes over only when you write one, and then only the rules it names fire, in the
order and at the sites it dictates.

### Name the rules a strategy drives

A strategy refers to rules by name, so use `rule("name") { … }` instead of the
anonymous `rewrite { … }`. Everything else about the rule (`from`, `to`,
`where`, `provide`, `ai`, and its erasure before compilation) is the same. With
no strategy, a named rule fires everywhere, like an anonymous one:

```diffstatic {data-lang=kotlin}
rule("neg") { x: Expr ->
    from { neg(x) }
    to { -x }
}

neg(a) // →  -a      (no strategy: every site, as before)
neg(b) // →  -b
```

Anonymous `rewrite { }` rules carry no name, so a `strategy { }` can never fire
them; under a strategy they are simply inert.

### The `strategy { }` block

One `strategy { }` block per box holds a single strategy expression, built from
the combinators below. Like a rule, it is erased before compilation, so it only
has to parse. Each combinator either *succeeds*, possibly changing the source,
or *fails* because it does not apply; `choice` and `then` act on that outcome.

**Fire once, or at a chosen depth.** By default the fixpoint hits both `neg`
sites; `once` stops after the first:

```diffstatic {data-lang=kotlin}
rule("neg") { x: Expr ->
    from { neg(x) }
    to { -x }
}
strategy { once("neg") }

neg(a) // →  -a         (first site fires)
neg(b) // →  neg(b)     (left untouched — the observable difference)
```

For nested forms, `topdown` and `bottomup` pick opposite ends. Given `f(f(a))`
and a rule `f(x) → g(x)`:

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr ->
    from { f(x) }
    to { g(x) }
}

strategy { topdown("fg") } //  f(f(a))  →  g(f(a))   (outer call only)
strategy { bottomup("fg") } //  f(f(a))  →  f(g(a))   (inner call only)
```

Each fires once: `topdown` does not go on to rewrite the inner `f`. To drive it to a fixed point, wrap it in `repeat`.

**Select a subset of rules.** Naming one rule fires only that rule, even where
another would have matched too:

```diffstatic {data-lang=kotlin}
rule("p2a") { x: Expr ->
    from { p(x) }
    to { A(x) }
}
rule("q2b") { x: Expr ->
    from { q(x) }
    to { B(x) }
}
strategy { "p2a" }

p(1) // →  A(1)
q(2) // →  q(2)      (q2b is not named, so it never fires)
```

A bare rule name is itself the simplest strategy (fire that rule wherever it
applies).

**Sequence with `then` / `seq`.** `a then b` runs `a`, then `b` on its result,
and fails as soon as either step fails:

```diffstatic {data-lang=kotlin}
rule("p2q") { x: Expr ->
    from { p(x) }
    to { q(x) }
}
rule("q2r") { x: Expr ->
    from { q(x) }
    to { r(x) }
}

strategy { "p2q" then "q2r" } //  p(1)  →  q(1)  →  r(1)
strategy { "q2r" then "p2q" } //  p(1)  →  p(1)  (q2r fails first ⇒ whole sequence aborts)
```

**Choose with `choice`.** `choice(a, b, …)` runs the first alternative that
applies, in the order you list them rather than the order the rules are
declared:

```diffstatic {data-lang=kotlin}
rule("toX") { x: Expr ->
    from { pick(x) }
    to { X(x) }
}
rule("toY") { x: Expr ->
    from { pick(x) }
    to { Y(x) }
}
strategy { choice("toY", "toX") }

pick(1) // →  Y(1)      (prefers its first alternative, though toX is declared first)
```

A non-matching alternative falls through to the next; `choice` fails only if
none apply.

**Bound repetition and rescue failure.** `repeat(s)` iterates to a fixpoint;
`repeat(s, n)` at most `n` times. `attempt(s)` runs `s` but falls back to
identity on failure, so a following step can still run:

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr ->
    from { f(x) }
    to { g(x) }
}

strategy { repeat(topdown("fg"), 1) } //  f(f(f(a)))  →  g(f(f(a)))   (exactly one step)
strategy { repeat(topdown("fg")) } //  f(f(a))     →  g(g(a))      (to a fixpoint)

rule("zzz") { x: Expr ->
    from { zzz(x) }
    to { Z(x) }
}
strategy { attempt("zzz") then "fg" } //  f(a)  →  g(a)   (the missing zzz is rescued, fg still runs)
```

**Nested patterns.** A pattern can be more than one level deep. `from { f(f(x)) }`
matches a pair of nested calls and binds `x` to whatever they wrap, so each
`topdown` step below removes two levels:

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr ->
    from { f(f(x)) }
    to { g(x) }
}
strategy { repeat(topdown("fg"), 2) }

f(f(f(f(a)))) // →  g(g(a))    (step 1 collapses the outer pair, step 2 the inner)
```

With real definitions behind those names — `fun f(v: Int) = v * 2` and
`fun g(v: Int) = v + 100` — `f(f(f(f(1))))` evaluates to 201, where the
unrewritten source would give 16.

### The combinators

<div class="field-reference" data-code-lang="kotlin" markdown>

| Strategy | Effect |
| --- | --- |
| `"name"` | Fire rule `name` wherever it applies. |
| `once("name")` | Fire at the single first (outermost) site — at most once. |
| `topdown("name")` | Fire at the single outermost (shallowest) match. |
| `bottomup("name")` | Fire at the single innermost (deepest) match. |
| `attempt(s)` | Run `s`; fall back to identity on failure. Never fails. |
| `repeat(s)` / `repeat(s, n)` | Iterate `s` to a fixpoint / at most `n` times. Never fails. |
| `a then b`, `seq(a, b, …)` | Sequence, threading the result; fail-fast. |
| `choice(a, b, …)` | First alternative that succeeds; fail only if none do. |

</div>

`once("r")` and `topdown("r")` do the same thing. Other rewriting languages call
`attempt` `try`, which is a keyword in Kotlin.

### Mistakes in a strategy

A strategy that names an unknown rule, or that is malformed, rewrites nothing
and reports a diagnostic.

```diffstatic {data-lang=kotlin}
rule("fg") { x: Expr ->
    from { f(x) }
    to { g(x) }
}
strategy { once("nope") } //  f(a)  →  f(a)   + "unknown rule name" diagnostic
strategy { 1 + 2 } //  f(a)  →  f(a)   + malformed-strategy diagnostic
```

A strategy belongs to one box. Rules declared in an ancestor box are inherited
and a strategy can name them, but the strategy itself is not inherited.
`provide { }` and `ai()` still contribute whenever a rule fires under a strategy.

## Try it

Two starting points exercise everything on this page. Open either from the welcome screen:

- [pattern_rewrite_worked_example](agent_transcripts.md#pattern_rewrite_worked_example):
  negation, the precedence guard, the `isCallTo` termination guard, a variadic
  hole, `unquote`, and an inherited rule.
- [rewrite_strategies_demo](agent_transcripts.md#rewrite_strategies_demo):
  named rules driven by `once`, `topdown`, `bottomup`, `then`, `choice` and
  `repeat`, plus the nested pattern, each box checking its result.
