# FieldML stage by stage

Every other FieldML page shows you a program and its answer. This one shows you the middle: one
program, and what each stage of the front end actually produced from it. The listings below are not
illustrations of the shapes involved — they are the real output of the real compiler, printed by
`FieldMLStagePageTest`, which recomputes every one of them from the program below and fails if this
page and the compiler ever disagree. Nothing here can go stale quietly.

The [FieldML Cheatsheet](fieldml_cheatsheet.md) is the reference for the prelude, [FieldML
Examples](fieldml_examples.md) is the tour of the language, and the [FieldML JVM
Bridge](fieldml_host_bridge.md) is how a program reaches Kotlin. This page is for when you want to
know what the thing in the middle is doing.

## The program

```diffstatic {data-lang=fieldml}
let twice f x = f (f x)
let bump n = n + 1
5 |> twice bump
```

Three lines, chosen because two of them are awkward in an interesting way. `twice` takes a function
and applies it twice, which means its type has to be inferred as genuinely polymorphic — nothing in
the program says what `f` operates on. And `|>` is a pipe, which turns out to leave no trace at all
in the tree; the section on the surface AST shows what that means concretely.

## The four stages

`FieldMLCompiler.compile` runs four stages in order — lex, parse, infer, lower — and returns a
`CompilationResult` with one field per stage: `tokens`, `surface`, `typed`, `core`. It stops at the
first stage to report an error, and a field is `null` exactly when its stage was not reached.

That makes a `null` mean "not reached" rather than "nothing found", and it makes a partial compile
useful rather than wasted. `bump "five"` is a type error, so its `core` is `null` — but its `tokens`
and `surface` are both there, because lexing and parsing had no complaint. This is what the
completion engine runs on: it compiles a half-written line and reads the names in scope off whatever
tree came back.

A non-`null` tree is therefore not a claim that the program is correct. Ask `successful` before
evaluating anything.

## Stage 1 — tokens

The lexer turns text into lexemes. One line per token: where it starts, which lexeme it is, and the
exact text it was read from.

<!-- stage:tokens -->
```diffstatic {data-lang=text}
1:1   LET           let
1:5   IDENT         twice
1:11  IDENT         f
1:13  IDENT         x
1:15  EQ            =
1:17  IDENT         f
1:19  LPAREN        (
1:20  IDENT         f
1:22  IDENT         x
1:23  RPAREN        )
2:1   LET           let
2:5   IDENT         bump
2:10  IDENT         n
2:12  EQ            =
2:14  IDENT         n
2:16  PLUS          +
2:18  INT           1
3:1   INT           5
3:3   PIPE_FORWARD  |>
3:6   IDENT         twice
3:12  IDENT         bump
3:16  EOF
```

Three things in that listing need pointing out.

**There is no token for whitespace.** `let` ends at column 4 and `twice` starts at column 5, and
nothing sits between them. The lexer skips trivia rather than emitting it, which is why a token list
cannot be printed back as the source it came from.

**`let` is its own kind, not an identifier that happens to spell "let".** That is what lets the
parser ask for a `LET` by kind, and also what stops `let` being used as a variable name.

**`EOF` has empty text.** It was not read out of the source at all; the lexer appends it when it runs
out. Its position is the end of the source, so a diagnostic about a program that stopped too early
still has somewhere to point.

An `INT` token's text is still text — `"1"`, not the number 1. Decoding literals is the parser's
job, and the same goes for a string's quotes and backslashes.

## Stage 2 — the surface AST

The parser builds one node per piece of written syntax.

<!-- stage:surface -->
```diffstatic {data-lang=text}
ELet twice f x
  ECall
    EVar f
    ECall
      EVar f
      EVar x
  ELet bump n
    EBinary ADD
      EVar n
      EInt 1
    ECall
      ECall
        EVar twice
        EVar bump
      EInt 5
```

Each `ELet` holds its value and then its body, and the body of one `let` is the rest of the program
— so a file of top-level bindings is a right-leaning tower of `ELet`, not a list. The third line of
the program is the innermost thing in the tree.

Note that `ELet twice f x` keeps `f` and `x` as *parameters of the let*. The surface tree records
what was written, and what was written was `let twice f x = …`, not a lambda. The lambda appears two
stages later.

### What happened to the pipe

`5 |> twice bump` has no pipe node. Compare the two spellings: `5 |> twice bump` and
`twice bump 5` parse to exactly this, the same tree twice.

<!-- stage:surface-pipe -->
```diffstatic {data-lang=text}
ECall
  ECall
    EVar twice
    EVar bump
  EInt 5
```

The parser desugars `|>` into the application spine that juxtaposition would have built. There is no
`EPipe`, nothing downstream knows a pipe was written, and the two programs are the same program —
the listing above is one rendering, published for both source texts.

It also tells you where a pipe can go. Since `|>` is application, `x |> f` is `f x` — so a helper
meant to sit at the end of a pipe has to take its subject *last*, which is why the prelude is shaped
the way it is.

Two `ECall`s for one call of two arguments is the other half of the same fact: application is
curried, one argument at a time, all the way down.

## Stage 3 — what inference concluded

Before the typed tree, the short version: the type each binding got, and the type of the result.

<!-- stage:types -->
```diffstatic {data-lang=text}
twice : ('a -> 'a) -> 'a -> 'a
bump : Int -> Int
(result) : Int
```

`twice` is polymorphic and nothing in the program said so. `'a` is a type variable: `twice` works
for any function whose argument and result are the same type, and inference derived that from
`f (f x)` alone — `f`'s result is fed back into `f`, so the two must agree.

The parentheses in `('a -> 'a) -> 'a -> 'a` are the ones that carry meaning. `->` is right
associative, so the trailing `'a -> 'a` needs none; the leading pair does, because there the function
is an *argument*.

## Stage 4 — the typed AST

The same shape as the surface tree, with the inferred type on every node.

<!-- stage:typed -->
```diffstatic {data-lang=text}
TExprLet twice f x : Int
  TExprLambda f x : ('a -> 'a) -> 'a -> 'a
    TExprCall : 'a
      TExprVar f : 'a -> 'a
      TExprCall : 'a
        TExprVar f : 'a -> 'a
        TExprVar x : 'a
  TExprLet bump n : Int
    TExprLambda n : Int -> Int
      TExprBinary ADD : Int
        TExprVar n : Int
        TExprInt 1 : Int
    TExprCall : Int
      TExprCall : Int -> Int
        TExprVar twice : (Int -> Int) -> Int -> Int
        TExprVar bump : Int -> Int
      TExprInt 5 : Int
```

Four readouts here need explaining.

**`TExprLet twice f x : Int`.** The `let` node's type is `Int`, not a function type. A `let` *is* an
expression, and its type is the type of its body — and the body of the outermost `let` is the whole
rest of the program, which evaluates to an `Int`. The function type is on the value, one line below.

**The same name has two different types in one tree.** At its definition, `twice`'s lambda is
`('a -> 'a) -> 'a -> 'a`. At its use site, `TExprVar twice` is `(Int -> Int) -> Int -> Int`. Both are
correct and neither overwrote the other: the definition is generalised, and each use instantiates it
fresh with whatever that use needs. Here the only use applies it to `bump`, so `'a` becomes `Int` at
that node and stays `'a` everywhere inside the definition. That is let-polymorphism, and this listing
is what it looks like.

**A lambda appeared.** `let twice f x = …` has become a `TExprLambda` holding the parameters, sitting
in the let's value. The surface tree recorded the syntax; inference reads it as what it means.

**`'a` here and `'a` in stage 3 are the same variable.** Both listings are printed by a single
`TypePrinter`, which names variables `'a`, `'b`, … in order of first appearance and keeps those names
for its lifetime. Two printers would each start again at `'a`, and the listings would then disagree
about which variable was which.

## Stage 5 — core

The lowerer produces the tree the evaluator runs.

<!-- stage:core -->
```diffstatic {data-lang=text}
CLet twice (recursive) : Int
  CLam f : ('a -> 'a) -> 'a -> 'a
    CLam x : 'a -> 'a
      CApp : 'a
        CVar f : 'a -> 'a
        CApp : 'a
          CVar f : 'a -> 'a
          CVar x : 'a
  CLet bump (recursive) : Int
    CLam n : Int -> Int
      CBinary ADD : Int
        CVar n : Int
        CInt 1 : Int
    CApp : Int
      CApp : Int -> Int
        CVar twice : (Int -> Int) -> Int -> Int
        CVar bump : Int -> Int
      CInt 5 : Int
```

The lowering is a case-for-case mapping from the typed tree, with two changes, and they are the
reason this stage exists.

**Lambdas are curried.** `TExprLambda f x` became `CLam f` wrapping `CLam x`. A `CLam` takes exactly
one parameter, so the evaluator has one rule for application rather than a rule per arity, and
partial application needs no special case at all. Read the types down those two nodes to see it:
`('a -> 'a) -> 'a -> 'a` applied to one argument leaves `'a -> 'a`.

**Ranges are gone.** Every `TExpr` carried a `SourceRange`; no `CoreExpr` does. Which means an
evaluation failure has no position of its own, and a box reports it against the fragment that ran
rather than the subexpression that failed.

**Types stayed.** Every core node still carries its type, so a later pass can still ask what a
subexpression is. Lowering discards positions, not types.

`CLet twice (recursive)`: the `recursive` flag survives lowering and is set for the
`let f x = …` function form, whose own name is in scope inside its body — which is what lets a
FieldML function call itself. Plain `let x = …` bindings stay non-recursive, so `let x = x + 1` still
shadows an outer `x` rather than referring to itself.

`CLet` has no parameter list, because by this stage a binding's parameters are the `CLam` sitting in
its value.

## Stage 6 — the value

The evaluator walks the core tree.

<!-- stage:value -->
```diffstatic {data-lang=text}
7 : Int
```

`bump (bump 5)`. In a live document this is the annotation a run puts beside the last expression of a
FieldML box.

## Where this stops

Two places where a reader reasonably expects the pipeline to keep going, and it does not.

**A FieldML body cannot call `Frames.frame()`.** FieldML closures can be handed to Kotlin functions
that take a `suspend` block — `eachFrame`, `loop`, `forever`, `animate` and the rest — and they run;
a FieldML box drives a triangle on a real device this way. The adapter is sound for a specific
reason: a suspend function that *never suspends* returns its value directly, and a FieldML body never
suspends. So the boundary falls exactly there. `Frames.frame()` is itself `suspend`, so calling it
from a FieldML body is refused: being able to call it is what would make the adapter unsound.
Functions where the enclosing Kotlin owns the suspension work; `frames { }`, whose contract is that
the body drives `frame()` itself, does not.
See the [FieldML JVM Bridge](fieldml_host_bridge.md) for the mechanism.

**There is no stage after core.** No bytecode, no closure conversion, no optimiser. The evaluator
walks the core tree directly, which is what makes a box's response to an edit immediate and what
makes FieldML's performance what it is. If you need speed, the answer is to call Kotlin across the
host bridge, not to expect this pipeline to get faster.
