# ForthField examples

ForthField is Field's Forth: a data stack and a dictionary of words that push and pop it. A definition is usually one line long (`: sqr dup * ;` is a complete one), and the dictionary can be rewritten while the box is still running. Around that core sit the control-flow words, vec2 helpers, enough `FLine` vocabulary to sketch geometry, and a reflection bridge that lets ForthField call methods on any JVM object it can reach.

To get a ForthField box, run **Set Main Language: ForthField** on a parent box; its children then run ForthField rather than Kotlin. Documents carrying the older `isForthRoot` property still work, but the action is the way to do it now.

Every example below is raw ForthField, run through the same `ForthInterpreter` your boxes use. The interpreter's KDoc in `src/jvmMain/kotlin/afield/forth/ForthInterpreter.kt` carries a few examples of its own; the ones here expand on them.

## A tiny stack language

Every word transforms the stack. Read stack comments from left to right and the code becomes much easier to follow.

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

## Variables, strings, and output channels

ForthField variables are named storage cells. They are enough for counters, state machines, and launch-time animation state.

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
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/variables_and_output_channels.png" wait="1.0" capture="box" trim=true embed=true name="forth-output-channels"}
:::static
variable count
0 count !

1 count +!
2 count +!
3 count +!

s"ForthField writes straight into a Field output area." display
s"count = " count @ str str+ s"debug" display-channel
```

`display` writes to the default output channel. `display-channel` names the disclosure panel explicitly, which is enough to keep pretty output and debug output apart.

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

## Vec2 helpers: geometry that arrives as points

The vector helpers are useful when geometry is already arriving as points rather than loose coordinates.

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

`fline-node-color` styles the current node rather than the whole line. Use it to mark a control point, an endpoint, or the last place a computation landed.

## Reflection: reaching a JVM class by name

The reflection bridge resolves a class by name, constructs it, and calls methods without leaving ForthField, which is how ForthField reaches anything the `fline-*` words do not already cover.

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

`@?` is the heuristic form. It looks for a public field of that name first, then for methods of that name sorted by parameter count, preferring a zero-argument one and otherwise taking the first whose arity fits what is currently on the stack — which is why it can pick differently depending on how deep the stack happens to be. A field holding `null` pushes nothing at all, so the stack silently comes back one shorter than you expected; and on a `Class`, the member names `new` and `newInstance` mean "construct". `@?!` is the explicit-arity form, taking the object first and the argument count off the top of the stack, and it is the escape hatch when `@?` picks the wrong overload.

For styling, numeric and vector values are a good fit for reflected `properties.put(...)`. Boolean style toggles are better expressed with the dedicated `fline-*` words.

The class helpers are useful on their own:

```diffstatic {data-lang=forth}
s"FLine" class-for @? getName println
s"Vec3" class-for @? getName println
s"Fli" class-matches println
```

`class-for` tries the name as a fully-qualified one first and only then looks it up as a simple name. If a simple name matches more than one class it refuses rather than guessing, and the error lists up to eight candidates for you to pick from. `class-matches` takes a *prefix* instead and returns up to 64 fully-qualified names, which is how you find out what a class is actually called before trying to resolve it; `class-help` prints the same list, capped at 24.

## Dictionary tools, recursion, and temporary stack storage

These words are the first ones to reach for when ForthField starts feeling opaque. `words` shows the dictionary, `see name` prints a definition, and `forget name` removes a user-defined word when an experiment has gone stale.

```diffstatic {data-lang=forth}
: sqr dup * ;
words
see sqr
forget sqr
```

`recurse` means "run the word I am currently inside." Here it makes a small factorial. The base case leaves `1`; the recursive case keeps a copy of `n`, computes `(n-1)!`, then multiplies.

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

When something fails, ForthField errors include the current token, a stack snapshot, and the word call stack. For example:

```diffstatic {data-lang=forth}
: inner nope ;
: outer 10 inner ;
outer
```

Reports the unknown word, shows that `10` was still on the data stack, and shows the path through `outer` into `inner`.

The small display vocabulary gives ForthField direct access to Field output areas without going through Kotlin:

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
```diffstatic_runner {data-lang=forth screenshot="assets/forth_examples/visualization_helpers.png" wait="1.0" capture="box" trim=true embed=true name="forth-visualization-helpers"}
:::static
s"<b>Hello from raw ForthField HTML.</b>" html

0.72 1.0 s"progress" bar
42 s"answer" watch

1 4 9 16
stack-display
plot-stack
```

`html` and `html-channel` send raw HTML. `watch` gives a named value its own output channel. `stack-display` shows the current stack as a table, and `plot-stack` turns numeric stack values into a quick bar chart.

## Extending the dictionary from Kotlin

The host bridge goes the other direction too. Kotlin can inject new words when ForthField should drive a renderer, a buffer, or some other JVM-side subsystem.

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

With a real buffer object on the ForthField stack, the call site stays tiny:

```diffstatic {data-lang=forth}
0.0 1.0 0.0 1.0 append-vec4
1.0 0.0 0.0 1.0 append-vec4
0.0 0.0 1.0 1.0 append-vec4
```

The host keeps hold of the resource; ForthField only names it.

## Launch mode and `frame`

The interpreter also has a cooperative `frame` primitive for launch-mode animation. This one needs a real, launched ForthField box to do anything — it animates, so there is nothing to print.

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

`frame` yields immediately and the word starts again on the next tick, so persistent state belongs in variables rather than transient stack structure.

## When a definition misbehaves

`words` shows the dictionary and `see name` prints a definition, which together answer most of "is the word I think I redefined actually the one running". A ForthField error already carries the token that failed, a snapshot of the data stack and the path of word calls that led there, so the usual next step is to read the error rather than to add prints.

Naming a word has one trap in it. Builtins and user definitions share a single flat dictionary, and `:` overwrites without complaint, so `: dup ... ;` replaces the real `dup` for the rest of the run. `forget` refuses to remove anything host-defined — but it will happily remove your replacement, and that leaves no entry at all rather than restoring the builtin underneath.
