# FieldML examples

FieldML is a small ML-flavored language for Field boxes. It is expression-oriented, statically typed, and meant for compact data transforms that can still return Field-native values such as `FLine`.

In a live document, FieldML boxes usually sit under a parent box whose main language is set to FieldML. A run evaluates the box and annotates the last expression inline. `print`, `println`, and `display` are explicit output helpers when you want the value to appear in an output area instead.

## A tiny typed expression language

FieldML has integers, floats, booleans, strings, tuples, lists, records, functions, and algebraic data types. Arithmetic keeps `Int` and `Float` separate; write `10.0` when you mean a float.

```diffstatic {data-lang=fieldml}
let width = 12
let height = 7

{
  area = width * height
  perimeter = (width + height) * 2
  square = width == height
}
```

The result is a record:

```diffstatic {data-lang=text}
{area = 84, perimeter = 38, square = false} : {area: Int, perimeter: Int, square: Bool}
```

Records are useful as light structured values. Field access uses dot notation:

```diffstatic {data-lang=fieldml}
let point = {
  x = 10.0
  y = 20.0
  label = "seed"
}

(point.label, point.x + point.y)
```

## Functions and pipes

Functions are curried. `let add a b = a + b` means `add` takes `a`, then returns a function that takes `b`.

```diffstatic {data-lang=fieldml}
let add a b = a + b
let scale amount value = value * amount

10
  |> add 5
  |> scale 3
```

The pipe operator sends the value on the left into the function on the right. The previous example is the same as `scale 3 (add 5 10)`.

Anonymous functions are useful for one-off transforms:

```diffstatic {data-lang=fieldml}
[1, 2, 3]
|> (fun values ->
  match values with
  | [a, b, c] -> a + b + c
  | _ -> 0
)
```

## Output taps

`print` and `println` write text output and return the value they were given. That makes them useful as taps in a pipe.

```diffstatic {data-lang=fieldml}
let add a b = a + b

10
  |> add 32
  |> println
```

Plain function application binds tighter than arithmetic, like in ML-family languages. This matters when reading output code:

```diffstatic {data-lang=fieldml}
let x = 100

print x * 10
```

That parses as `(print x) * 10`, so it prints `100`. If the intended printed value is the product, parenthesize it:

```diffstatic {data-lang=fieldml}
let x = 100

print (x * 10)
```

`display` writes to a named Field output channel and also returns the displayed value:

```diffstatic {data-lang=fieldml}
{
  status = "ok"
  count = 42
}
|> display "fieldml"
```

## Matching shapes

Pattern matching works over tuples, lists, literals, wildcards, and constructors. Use `_` for a fallback case.

```diffstatic {data-lang=fieldml}
let classify point =
  match point with
  | (0, 0) -> "origin"
  | (0, _) -> "y axis"
  | (_, 0) -> "x axis"
  | _ -> "free"

classify (4, 0)
```

List patterns match exact list shapes:

```diffstatic {data-lang=fieldml}
let sumSmall values =
  match values with
  | [] -> 0
  | [a] -> a
  | [a, b] -> a + b
  | [a, b, c] -> a + b + c
  | _ -> 0

sumSmall [10, 20, 12]
```

## Algebraic data types

`type` declarations introduce constructors. Constructor names are capitalized, and constructors can carry values.

```diffstatic {data-lang=fieldml}
type Option a = None | Some a

let value = Some 10

match value with
| Some x -> x * 10
| None -> 0
```

Type parameters make constructors reusable:

```diffstatic {data-lang=fieldml}
type Result a = Err String | Ok a

let payload = { x = 20, y = 22 }
let result = Ok payload

match result with
| Ok p -> p.x + p.y
| Err message -> 0
```

Constructors can nest, and patterns can unpack the same shape:

```diffstatic {data-lang=fieldml}
type Option a = None | Some a

match Some (Some 42) with
| Some (Some x) -> x
| Some None -> 0
| None -> 0
```

## Returning Field values

FieldML has a small prelude for common Field interop. The `fline` factory creates an `FLine`, and path/style helpers return the same line so they compose naturally with pipes.

```diffstatic_runner {data-lang=fieldml screenshot="assets/fieldml_examples/lines_interop.png" wait="1.0" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="fieldml-lines-interop" w="760" h="240"}
let lines channel =
  hostTap "afield.graphics.lines" { channel = channel, w = 120.0, h = 80.0 }

fline
  |> moveTo 0.0 0.0
  |> lineTo 120.0 20.0
  |> cubicTo 160.0 90.0 210.0 90.0 260.0 20.0
  |> lineTo 320.0 0.0
  |> stroked true
  |> filled false
  |> color (vec4 1.0 0.55 0.2 1.0)
  |> thickness 2.5
  |> lines "main"
```

Vector helpers create host values. `vec4` is the one most often used directly in current examples because it sets FLine color:

```diffstatic {data-lang=fieldml}
fline
  |> moveTo 0.0 0.0
  |> lineTo 120.0 40.0
  |> color (vec4 0.2 0.85 1.0 1.0)
  |> thickness 4.0
```

## JVM interop

FieldML values can cross into JVM code as ordinary host values. The most direct path is to use curated helpers such as `fline`, `vec2`, `moveTo`, and `color`; they are typed, pipe-friendly, and keep the common graphics workflow compact.

When a Kotlin function is not curated, use `hostTap` to make a small FieldML wrapper around it. `hostTap` looks up the target in Field's Kotlin source index, injects the current `Box` receiver for extension functions, fills Kotlin source defaults when they are simple literals, and places the piped value into the required data argument. The record names only the arguments you want to override.

```diffstatic {data-lang=fieldml}
let lines channel =
  hostTap "afield.graphics.lines" { channel = channel, w = 120.0, h = 80.0 }

fline
  |> moveTo 0.0 0.0
  |> lineTo 80.0 40.0
  |> lines "main"
```

The target name is the Kotlin package plus function name, not the generated JVM class. In the example above, FieldML writes `afield.graphics.lines`; the bridge resolves the generated `SomeSkikoKt` owner class and the `Box.lines(...)` extension shape.

Use `hostTap` for JVM calls that are effects in the middle of a pipe. It runs the JVM call and returns the original piped value, so downstream FieldML code can continue transforming the same object.

The same wrapper can expose a different part of the Kotlin function without changing the FieldML call shape. Here the wrapper keeps the piped `FLine` implicit, but opts into Field's `fitToSize` behavior:

```diffstatic_runner {data-lang=fieldml screenshot="assets/fieldml_examples/lines_fit_interop.png" wait="1.0" wait-output=true wait-output-kind=display wait-output-channel="fit" capture="box" embed=true name="fieldml-lines-fit-interop" w="760" h="240"}
let fitLines channel =
  hostTap "afield.graphics.lines" { channel = channel, w = 180.0, h = 90.0, fitToSize = true }

fline
  |> moveTo 0.0 70.0
  |> lineTo 35.0 20.0
  |> cubicTo 80.0 140.0 180.0 5.0 260.0 70.0
  |> color (vec4 0.2 0.85 1.0 1.0)
  |> thickness 3.0
  |> fitLines "fit"
```

`hostTap` is not limited to graphics. FieldML records, lists, strings, numbers, and host values can all cross the boundary. This example sends a structured FieldML record into Field's JVM value visualizer:

```diffstatic_runner {data-lang=fieldml screenshot="assets/fieldml_examples/viz_record_interop.png" wait="1.0" wait-output=true wait-output-kind=display wait-output-channel="model" capture="box" embed=true name="fieldml-viz-record-interop" w="760" h="260"}
let viz channel =
  hostTap "afield.viz" { channel = channel }

{
  label = "run 17"
  samples = [3, 5, 8, 13, 21]
  state = { ok = true, active = 4 }
}
  |> viz "model"
```

Scalar values work the same way. `progressBar` wants a name and a numeric value; the wrapper fixes the display text and lets the piped float fill the JVM `value` parameter:

```diffstatic {data-lang=fieldml}
let progress name =
  hostTap "afield.progressBar" { name = name, details = "from FieldML hostTap" }

0.72
  |> progress "interop"
```

For object construction and ad hoc member access, the lower-level host bridge is still available. It is deliberately explicit: resolve a class, construct it, call a method, or read/write a member.

```diffstatic {data-lang=fieldml}
let line =
  hostClass "afield.graphics.FLine"
  |> hostNew0

line
  |> hostCall1 "moveTo" (vec2 0.0 0.0)
  |> hostCall1 "lineTo" (vec2 120.0 40.0)
  |> hostCall1 "lineTo" (vec2 200.0 10.0)
```

For the rare cases where source-indexed lookup is not enough, `hostStaticTap` and `hostStaticCall` expose the raw reflective form. `hostStaticTap` uses `hostInput` as a placeholder for the piped value and returns that value after the call; `hostStaticCall` just invokes a static JVM method and returns the JVM result.

```diffstatic {data-lang=fieldml}
let lines channel =
  hostStaticTap "lines"
    (thisBox, [hostInput], channel, 120.0, 80.0, false, false, false, false)
    (hostClass "afield.graphics.SomeSkikoKt")
```

Use the curated helpers first when they exist, `hostTap` when the source index knows the Kotlin function, and the raw host bridge only when you need to spell out the JVM ABI yourself.

## Things to remember

FieldML is strict and typed. A few rules explain most surprises:

| Rule | Consequence |
| --- | --- |
| Function application binds tighter than operators | `print x * 10` means `(print x) * 10`; use `print (x * 10)` for the product. |
| `Int` and `Float` do not silently mix | `10 + 1.0` is a type error; make both sides the same numeric type. |
| Lists are homogeneous | `[1, 2, 3]` is valid; `[1, true]` is not. |
| Match branches must agree | Every branch of a `match` expression has the same result type. |
| The last expression is the result | Leave an `FLine`, record, list, or scalar at the end of the box to return it to Field. |
