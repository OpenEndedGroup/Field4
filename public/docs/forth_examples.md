# Forth examples

Field's Forth support is intentionally small: one data stack, a mutable dictionary, a few control-flow words, some vector helpers, a reflection bridge into JVM objects, and enough `FLine` vocabulary to sketch geometry quickly.

The original examples live in `src/jvmMain/kotlin/afield/forth/ForthInterpreter.kt`. This page pulls those examples into the docs site, expands them a little, and keeps them runnable.

In a live Field document, Forth boxes usually sit under a parent box whose `isForthRoot` property is `true`. The examples below are still authored as raw Forth, but the docs runner executes them through the same `ForthInterpreter` class so the code blocks can stay readable.

## A tiny stack language, not a toy syntax

The first thing to internalize is that every word transforms the stack. If you read the stack comments left-to-right, the code becomes much easier to reason about.

<!--
FIELD_TEST
id: docs.forth.basic-stack
runner: backdoor-box
tags: [docs, forth, stack]
timeoutSec: 45
waitSec: 1.0
expect:
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/basic_stack_and_words.png" wait="1.0" capture="box" embed=true name="forth-basic-stack"}
:::static
: sqr ( n -- n2 ) dup * ;
: hypot2 ( x y -- d2 ) sqr swap sqr + ;

s"hypot^2 = " 3 4 hypot2 str str+ println
```

`: sqr ... ;` defines a new word. `dup` copies the top stack item, `*` multiplies the top two numbers, and `swap` flips the top pair so `hypot2` can reuse `sqr` on both inputs.

There is very little ceremony here. That is the point. The language is meant to stay close to the movement of data.

## Variables, strings, and output channels

Forth variables are just named storage cells, but they are enough for counters, state machines, and launch-time animation state.

<!--
FIELD_TEST
id: docs.forth.output-channels
runner: backdoor-box
tags: [docs, forth, output]
timeoutSec: 45
waitSec: 1.0
expect:
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/variables_and_output_channels.png" wait="1.0" capture="box" embed=true name="forth-output-channels"}
:::static
variable count
0 count !

1 count +!
2 count +!
3 count +!

s"<b>Forth can emit HTML into Field output areas.</b>" display
s"count = " count @ str str+ s"debug" display-channel
```

`display` writes to the default output channel. `display-channel` lets you name the disclosure panel explicitly. That makes it easy to separate pretty output from debug output without inventing a heavier UI model.

## Building an FLine directly

This is the smallest complete drawing workflow: make an `FLine`, add segments, style it, and leave it on the stack.

<!--
FIELD_TEST
id: docs.forth.stroke-ribbon
runner: backdoor-box
tags: [docs, forth, fline]
timeoutSec: 45
waitSec: 1.0
expect:
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/stroke_ribbon.png" wait="1.0" capture="box" embed=true name="forth-stroke-ribbon"}
:::static
: stroke-ribbon ( -- f )
  fline
  0   0   moveto
  120 20  lineto
  160 90  210 90  260 20 cubicto
  320 0   lineto
  true  fline-stroked
  false fline-filled
  1.0 0.55 0.2 1.0 fline-color
  2.5 fline-thickness ;

stroke-ribbon
```

This example is close to SVG path construction. `moveto`, `lineto`, and `cubicto` build the geometry. The later `fline-*` words mutate style properties on the same object and push it back so the chain can continue.

## Vec2 helpers make path code less repetitive

The vector helpers are useful when your geometry is already arriving as points rather than loose coordinates.

<!--
FIELD_TEST
id: docs.forth.vec2-zig
runner: backdoor-box
tags: [docs, forth, vec2, fline]
timeoutSec: 45
waitSec: 1.0
expect:
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/vec2_zig.png" wait="1.0" capture="box" embed=true name="forth-vec2-zig"}
:::static
: zig ( -- f )
  fline
  0    0 vec2 fline-moveto-v2
  40  20 vec2 fline-lineto-v2
  80 -10 vec2 fline-lineto-v2
  120 25 vec2 fline-lineto-v2
  160 -5 vec2 fline-lineto-v2
  true  fline-stroked
  false fline-filled
  0.2 0.85 1.0 1.0 fline-color
  4.0 fline-thickness
  1.0 0.4 0.2 1.0 fline-node-color ;

zig
```

`fline-node-color` styles the current node rather than the whole line. That makes it a good debugging affordance when you want to mark a control point, an endpoint, or the place where a computation last landed.

## Reflection can build geometry too

The `fline` helper is convenient, but the reflection bridge is what makes the language genuinely porous. You can resolve a class by name, construct it, and call methods without leaving Forth.

<!--
FIELD_TEST
id: docs.forth.reflection-fline
runner: backdoor-box
tags: [docs, forth, reflection, fline]
timeoutSec: 45
waitSec: 1.0
expect:
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/reflection_fline.png" wait="1.0" capture="box" embed=true name="forth-reflection-fline"}
:::static
variable f
s"FLine" class-for @? newInstance f !

f @ 0   0   0   3 @?! moveTo drop
f @ 120 30  0   3 @?! lineTo drop
f @ 220 10  0   3 @?! lineTo drop
f @ 260 80  0  320 80  0  380 20  0  9 @?! cubicTo drop

f @ @? properties s"thickness" 3.0 2 @?! put
f @ @? properties s"color" 0.95 0.95 1.0 1.0 vec4 2 @?! put

f @
```

`@?` is the heuristic form: it tries field access, zero-argument methods, and other obvious cases. `@?!` is the explicit-arity form: you tell it how many arguments to consume before the next method name. That is the escape hatch when overloads get ambiguous.

For styling, numeric and vector values are a good fit for reflected `properties.put(...)`. Boolean style toggles are better expressed with the dedicated `fline-*` words.

The class helpers are useful on their own:

```diffstatic {data-lang=forth}
s"FLine" class-for @? getName println
s"Vec3" class-for @? getName println
s"Fli" class-matches println
```

That is not just introspection for its own sake. It is how you make host APIs discoverable enough to stay live-codable.

## Dictionary tools, recursion, and temporary stack storage

These words are the first ones to reach for when Forth starts feeling opaque. `words` shows the dictionary, `see name` prints a definition, and `forget name` removes a user-defined word when an experiment has gone stale.

```diffstatic {data-lang=forth}
: sqr dup * ;
words
see sqr
forget sqr
```

`recurse` means “run the word I am currently inside.” Here it makes a small factorial. The base case leaves `1`; the recursive case keeps a copy of `n`, computes `(n-1)!`, then multiplies.

```diffstatic {data-lang=forth}
: fact ( n -- n! )
  dup 1 <= if
    drop 1
  else
    dup 1- recurse *
  then ;

5 fact println
```

The return stack is a second, temporary stack. Use it sparingly, mostly to park a value while the data stack is doing something else. `>r` moves to the return stack, `r@` copies back, and `r>` moves back.

```diffstatic {data-lang=forth}
: add-twice ( a b -- a+b+b )
  >r        \ save b away from the data stack
  r@ +      \ add a copy of b
  r> + ;    \ restore b and add it again

10 3 add-twice println
```

## `again`, diagnostics, and display helpers

`begin ... again` is an endless loop. In normal one-shot execution that is dangerous; in launch-mode animation it is useful when the loop contains `frame`, because `frame` yields back to Field for the next tick. In a one-shot example, pair it with `leave`.

```diffstatic {data-lang=forth}
variable n
0 n !

: count3
  begin
    1 n +!
    n @ 3 >= if leave then
  again ;

count3
n @ println
```

When something fails, Forth errors now include the current token, a stack snapshot, and the word call stack. For example:

```diffstatic {data-lang=forth}
: inner nope ;
: outer 10 inner ;
outer
```

Reports the unknown word, shows that `10` was still on the data stack, and shows the path through `outer` into `inner`.

The small display vocabulary gives Forth direct access to Field output areas without going through Kotlin:

<!--
FIELD_TEST
id: docs.forth.visualization-helpers
runner: backdoor-box
tags: [docs, forth, output, visualization]
timeoutSec: 45
waitSec: 1.0
expect:
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 16
-->
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/visualization_helpers.png" wait="1.0" capture="box" embed=true name="forth-visualization-helpers"}
:::static
s"<b>Hello from raw Forth HTML.</b>" html

0.72 1.0 s"progress" bar
42 s"answer" watch

1 4 9 16
stack-display
plot-stack
```

`html` and `html-channel` send raw HTML. `watch` gives a named value its own output channel. `stack-display` shows the current stack as a table, and `plot-stack` turns numeric stack values into a quick bar chart.

## Extending the dictionary from Kotlin

The host bridge goes the other direction too. You can inject new words from Kotlin when you want Forth to drive a renderer, a buffer, or some other subsystem that should stay on the JVM side.

```diffstatic {data-lang=kotlin}
:::static
import afield.forth.ForthInterpreter
import afield.four.buffers.DynamicallyWriteable
import afield.stdlib.Vec4

val forth = ForthInterpreter()

forth.defineWord("append-vec4", "( buf x y z w -- buf )") {
    val w = popNum().toFloat()
    val z = popNum().toFloat()
    val y = popNum().toFloat()
    val x = popNum().toFloat()
    val buf = pop() as DynamicallyWriteable

    buf.append {
        put(Vec4(x, y, z, w))
    }

    push(buf)
}
```

Once the host has pushed a real buffer object onto the Forth stack, the call site stays tiny:

```diffstatic {data-lang=forth}
0.0 1.0 0.0 1.0 append-vec4
1.0 0.0 0.0 1.0 append-vec4
0.0 0.0 1.0 1.0 append-vec4
```

That is the pattern to keep in mind when Forth should orchestrate something but not own the underlying resource.

## Launch mode and `frame`

The interpreter also has a cooperative `frame` primitive for launch-mode animation. This example is lifted almost directly from the interpreter docs and is meant to run in a real Forth box inside Field, not inside the docs build.

```diffstatic {data-lang=forth}
variable tick
variable line
0 tick !

: spinner ( -- )
  s"FLine" class-for @? newInstance line !

  line @ 0 0 0 3 @?! moveTo drop

  line @
  tick @ 0.05 * dup cos 120 *          ( x )
             swap sin 120 *            ( x y )
  0 3 @?! lineTo drop

  line @ @? properties s"thickness" 2.0 2 @?! put
  line @ @? properties s"stroked" true 2 @?! put
  line @ s"geom" display-channel

  s"tick=" tick @ str str+ s"debug" display-channel

  1 tick +!
  frame
;

spinner
```

The important constraint is that `frame` yields immediately and the word starts again on the next tick, so persistent state needs to live in variables rather than in transient stack structure.

## What to pay attention to

Forth in Field is most useful when you treat it as a sketchpad for live objects rather than as an isolated language. The stack words are small. The geometry words are small. The bridge into JVM objects is small. The composition of those pieces is where it gets interesting.

That makes it a good fit for procedural drawing, little control systems, and situations where you want to stay very close to the data flow instead of hiding it behind layers of structure.
