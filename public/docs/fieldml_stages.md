# FieldML stage by stage

This page follows one FieldML program through the compiler and shows what each stage produces from
it: tokens, syntax tree, types, the tree the evaluator runs, and the value. Read it when you want to
know what the compiler does with your code, or why an error or a completion looks the way it does.

The [FieldML Cheatsheet](fieldml_cheatsheet.md) is the reference for the prelude, [FieldML
Examples](fieldml_examples.md) is the tour of the language, and the [FieldML JVM
Bridge](fieldml_host_bridge.md) is how a program reaches Kotlin.

??? aside "Where the listings come from"
    Every listing on this page is printed by the compiler. `FieldMLStagePageTest` recomputes each
    one from the program below and fails if the page and the compiler disagree.

## The program

The program defines a function that applies another function twice, and pipes `5` through it:

```diffstatic {data-lang=fieldml}
let twice f x = f (f x)
let bump n = n + 1
5 |> twice bump
```

`twice` takes a function and applies it twice. Nothing in the program says what `f` works on, so
inference has to give `twice` a polymorphic type. `|>` is a pipe, and it leaves no trace in the
tree; the section on the surface AST shows what that means.

## How the compiler runs

`FieldMLCompiler.compile` runs four stages in order (lex, parse, infer, lower) and returns a
`CompilationResult` with one field per stage: `tokens`, `surface`, `typed`, `core`. It stops at the
first stage that reports an error, and a field is `null` when its stage was not reached.

So a partial compile still gives you the early stages. `bump "five"` is a type error, so its `core`
is `null`, but its `tokens` and `surface` are both there because lexing and parsing succeeded. The
completion engine uses this: it compiles a half-written line and reads the names in scope from
whatever tree came back.

A non-`null` tree does not mean the program is correct. Check `successful` before evaluating
anything.

## Tokens

The lexer turns the text into tokens. The listing has one line per token: where it starts, its
kind, and the text it was read from.

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

There is no token for whitespace or comments: the lexer skips them, so a token list cannot be
printed back as the exact source it came from.

`let` is a keyword with its own kind, `LET`, so it cannot be used as a variable name.

`EOF` has empty text. The lexer adds it at the end of the source, so an error about a program that
stopped too early still has a position to point at.

An `INT` token's text is still text: `"1"`, not the number 1. The parser decodes literals, including
a string's quotes and escapes.

## The surface AST

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

Each `ELet` holds its value and then its body, and the body of a `let` is the rest of the program.
A file of top-level bindings is therefore a nested chain of `ELet`s rather than a list, with the
program's last line innermost.

`ELet twice f x` keeps `f` and `x` as parameters of the `let`, because the surface tree records what
was written: `let twice f x = …`, not a lambda. The lambda appears in the typed tree.

### What happened to the pipe

`5 |> twice bump` has no pipe node. `5 |> twice bump` and `twice bump 5` parse to the same tree:

<!-- stage:surface-pipe -->
```diffstatic {data-lang=text}
ECall
  ECall
    EVar twice
    EVar bump
  EInt 5
```

The parser rewrites `x |> f` as the application `f x`, so nothing after the parser knows a pipe was
written. That is why a function meant for the end of a pipe takes its subject last, and why the
prelude's functions are ordered that way.

The two nested `ECall`s show that application is curried: a call with two arguments is two calls of
one argument each.

## Inferred types

Before the typed tree, the summary: the type each binding got, and the type of the result.

<!-- stage:types -->
```diffstatic {data-lang=text}
twice : ('a -> 'a) -> 'a -> 'a
bump : Int -> Int
(result) : Int
```

`'a` is a type variable: `twice` works for any function whose argument and result have the same
type. Inference worked that out from `f (f x)` alone, since `f`'s result is passed back into `f`.

`->` is right associative, so the trailing `'a -> 'a` needs no parentheses. The leading pair is
needed because that function is an argument.

## The typed AST

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

`TExprLet twice f x` has type `Int`, not a function type. A `let` is an expression whose type is the
type of its body, and the body of the outermost `let` is the rest of the program, which evaluates to
an `Int`. The function type is on the value, one line below.

The same name has two types in one tree. At its definition, `twice`'s lambda is
`('a -> 'a) -> 'a -> 'a`; at its use, `TExprVar twice` is `(Int -> Int) -> Int -> Int`. The
definition is generalised, and each use gets a fresh copy with its own types. Here the only use
applies `twice` to `bump`, so `'a` becomes `Int` at that node and stays `'a` inside the definition.
This is let-polymorphism.

`let twice f x = …` is now a `TExprLambda` holding the parameters, in the value of the `let`.

The `'a` here is the same type variable as the `'a` in the summary above. Both listings use one
naming, so variables keep their names from one listing to the next.

## Core

Lowering produces the tree the evaluator runs.

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

The core tree follows the typed tree node for node, with two differences.

Lambdas take one parameter each. `TExprLambda f x` became `CLam f` wrapping `CLam x`, so the
evaluator has a single rule for application and partial application needs no special case. The
types show it: `('a -> 'a) -> 'a -> 'a` applied to one argument leaves `'a -> 'a`.

Source positions are gone. Every `TExpr` carries a `SourceRange` and no `CoreExpr` does, so an
evaluation error is reported against the box fragment that ran, not the subexpression that failed.
Every core node still carries its type.

`CLet twice (recursive)` marks a function binding, `let f x = …`, whose own name is in scope in its
body, so a FieldML function can call itself. A plain `let x = …` is not recursive, so
`let x = x + 1` shadows an outer `x` instead of referring to itself.

`CLet` has no parameter list: a binding's parameters are the `CLam` in its value.

## The value

The evaluator walks the core tree and produces the result:

<!-- stage:value -->
```diffstatic {data-lang=text}
7 : Int
```

That is `bump (bump 5)`. In a document, a run shows this value beside the last expression of a
FieldML box.

## Limits

A FieldML body cannot call `Frames.frame()`. You can pass FieldML functions to Kotlin functions
that take a `suspend` block, such as `eachFrame`, `loop`, `forever` and `animate`, and they run every
frame. This works because a FieldML body never suspends. `Frames.frame()` suspends, so FieldML
refuses to call it, and `frames { }`, whose body is expected to call `frame()` itself, cannot take a
FieldML function. See the [FieldML JVM Bridge](fieldml_host_bridge.md).

There is no stage after core: no bytecode and no optimiser. The evaluator walks the core tree
directly, which is why a box responds to an edit immediately. For code that needs to be fast, call
Kotlin through the host bridge.
