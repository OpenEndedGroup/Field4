# FieldML cheatsheet

FieldML is Field's small ML-flavoured expression language, strict and statically typed, with a prelude aimed at generating geometry. Everything in it is an expression and every expression has a type, so the tables below can give a type for every name the language ships with. [FieldML Examples](fieldml_examples.md) is the guided tour.

Types are written the way they read: `->` is right associative, so `Float -> Float -> Vec2` is a function of two arguments. A type in argument position keeps its parentheses, as in `('a -> 'b) -> ['a] -> ['b]`.

## Shape of a box

Top-level `let` bindings stack up, and the last expression is the box's result.

```diffstatic {data-lang=fieldml}
let width = 40.0
let height = 25.0

{ area = width * height, square = width == height }
```

## Syntax

<div class="field-reference" data-code-lang="fieldml" markdown>

| Example | What it means |
| --- | --- |
| `let x = 10` | **Binding.** Gives a value a name for the expressions that follow. This binds `x` to the integer `10`. |
| `let add a b = a + b` | **Function.** Defines a curried function: `add 5` is itself a function, waiting for its second argument. |
| `let x = 6 in x * 7` | **Local binding.** Limits the name to the expression after `in`. This expression evaluates to `42`. |
| `fun x -> x + 1` | **Anonymous function.** Produces a function without naming it. Pass it to a helper such as `map` to transform each element. |
| `add 20 22` | **Application.** Calls a function with space-separated arguments; this returns `42` using `add` above. Parenthesize an argument that is itself an expression. |
| `10 |> add 5` | **Pipe.** Passes the left value as the final argument of the call on the right. This is `add 5 10`, which returns `15`. |
| `if x > 0 then "up" else "down"` | **Conditional.** Chooses one branch and returns its value. Both branches must have the same type; here the result is a `String`. |
| `match v with | Some x -> x | None -> 0` | **Match.** Tests the shape of a value and binds its contents. With the `Option` type below, `Some 7` returns `7` and `None` returns `0`. |
| `type Option a = None | Some a` | **Data type.** Declares two constructors: `None` carries no payload, while `Some` carries a value of type `a`. Declare the type before using its constructors. |
| `(1, "a", true)` | **Tuple.** Groups a fixed number of values, whose types may differ. Use a tuple pattern to extract its parts. |
| `[1, 2, 3]` | **List.** Holds any number of elements of one type. Use `map`, `filter` and `fold` to process them. |
| `{ x = 1.0, y = 2.0 }` | **Record.** Groups values under field names. Bind this to `point` and read its coordinates with `point.x` and `point.y`. |
| `()` | **Unit.** The single value of type `Unit`; use it when a call has no meaningful value to supply. It is distinct from JVM null (`hostNull`). |
| `point.x` | **Field access.** Reads a named field from a record or a supported host value, such as a vector coordinate. |
| `// line`<br>`(* nested block *)` | **Comments.** `//` continues to the end of the line. A block comment can span lines and contain other block comments. |

</div>

## Operators

Listed loosest to tightest. Everything binds looser than function application, so `print x * 10` is `(print x) * 10`.

<div data-code-lang="fieldml" markdown>

| Operators | Meaning | Notes |
| --- | --- | --- |
| `||` | boolean or | Skips the right side when the left side is true. |
| `&&` | boolean and | Skips the right side when the left side is false. |
| `== != < <= > >=` | comparison | Compares values and returns a Boolean. Equality supports `Int`, `Float`, `Bool` and `String`. |
| `::` | prepend to a list | Adds one element at the front. It associates right, so `1 :: 2 :: []` is `[1, 2]`. |
| `+ - ++` | add, subtract, join strings | `+` and `-` also add or subtract vectors component by component. Use `++` to join strings. |
| `* /` | multiply, divide | Multiplies or divides numbers; a vector can also be scaled by a `Float`. |
| `-` (prefix) | negate | Reverses the sign of a number or each vector component. Parenthesize a negative function argument. |
| `|>` | pipe the left value into the right function | `10 |> add 5` is `add 5 10` |

</div>

## Conventions

- **Single-subject helpers take the subject last**, so they read at the end of a pipe: `7 |> mod 4` is 3, `3.0 |> pow 2.0` is 9, `[1] |> append [2]` is `[1, 2]`.
- **`Int` and `Float` never mix silently.** `10 + 1.0` is a type error; use `toFloat`.
- **Only `let f x = ...` is recursive.** A plain `let x = ...` refers to an outer `x`, so shadowing keeps working.
- **Recursion is not tail-call optimised.** Long loops should use `range`/`tabulate` with `map`/`fold`.
- **Prefix `-` binds looser than application**, so `f -1.0` is a subtraction. Write `f (-1.0)`.

## Numbers

Angles are in radians. `abs`, `sign`, `min`, `max` and `clamp` keep whatever numeric type they are given; the trigonometric, exponential and rounding functions use `Float`. Integer division and modulo, conversions and Boolean negation have their own types below.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name and type | What it does |
| --- | --- |
| `pi`<br>`Float` | Half a turn in radians, approximately `3.14159`. |
| `tau`<br>`Float` | One full turn in radians, equal to `2.0 * pi`. |
| `sin`<br>`Float -> Float` | Sine of an angle in radians. `sin 0.0` is `0.0`. |
| `cos`<br>`Float -> Float` | Cosine of an angle in radians. `cos 0.0` is `1.0`. |
| `tan`<br>`Float -> Float` | Tangent of an angle in radians. Values grow large near odd multiples of `pi / 2.0`. |
| `asin`<br>`Float -> Float` | Inverse sine, returning an angle in radians. The real-valued input range is −1 to 1. |
| `acos`<br>`Float -> Float` | Inverse cosine, returning an angle in radians. The real-valued input range is −1 to 1. |
| `atan`<br>`Float -> Float` | Inverse tangent, returning an angle in radians. |
| `atan2`<br>`Float -> Float -> Float` | `atan2 y x` gives the angle of the vector `(x, y)`, retaining the quadrant. |
| `sqrt`<br>`Float -> Float` | Square root. `sqrt 9.0` is `3.0`; a negative input produces NaN. |
| `exp`<br>`Float -> Float` | Raises the natural base e to the supplied power. `exp 0.0` is `1.0`. |
| `log`<br>`Float -> Float` | Natural logarithm. `log 1.0` is `0.0`. |
| `floor`<br>`Float -> Float` | Rounds down to an integer-valued `Float`. `floor (-1.2)` is `-2.0`. |
| `ceil`<br>`Float -> Float` | Rounds up to an integer-valued `Float`. `ceil 1.2` is `2.0`. |
| `round`<br>`Float -> Float` | Rounds to the nearest integer-valued `Float`; ties round toward positive infinity. |
| `pow`<br>`Float -> Float -> Float` | `pow exponent value` raises the value to the exponent. `3.0 |> pow 2.0` is `9.0`. |
| `fmod`<br>`Float -> Float -> Float` | `fmod divisor value` gives the floating-point remainder using modulo semantics. The divisor comes first for piping. |
| `lerp`<br>`Float -> Float -> Float -> Float` | `lerp from to amount` interpolates between two numbers. Amounts outside 0–1 extrapolate. |
| `abs`<br>`'a -> 'a` | Absolute value, preserving `Int` or `Float`. `abs (-3)` is `3`. |
| `sign`<br>`'a -> 'a` | Returns −1, 0 or 1 in the input numeric type, according to its sign. |
| `min`<br>`'a -> 'a -> 'a` | Returns the smaller of two numbers of the same type. |
| `max`<br>`'a -> 'a -> 'a` | Returns the larger of two numbers of the same type. |
| `clamp`<br>`'a -> 'a -> 'a -> 'a` | `clamp low high value` bounds a number to an interval. `5.0 |> clamp 0.0 1.0` is `1.0`. |
| `mod`<br>`Int -> Int -> Int` | `mod divisor value` gives integer modulo. `7 |> mod 4` is `3`. |
| `idiv`<br>`Int -> Int -> Int` | `idiv divisor value` performs integer division, truncating toward zero. `7 |> idiv 2` is `3`. |
| `toFloat`<br>`Int -> Float` | Converts an integer to a floating-point number. Use before combining it with `Float` values. |
| `toInt`<br>`Float -> Int` | Converts a floating-point number to an integer, truncating toward zero. |
| `not`<br>`Bool -> Bool` | Reverses a Boolean. `not true` is `false`. |

</div>

`lerp from to amount` and `clamp low high value` both end with the subject, so `0.5 |> lerp 0.0 10.0` is 5 and `5.0 |> clamp 0.0 1.0` is 1.

## Lists

`range start end` stops before `end`; `linspace start end count` includes both ends. Together with `tabulate` they are how FieldML iterates, since there is no loop statement.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name and type | What it does |
| --- | --- |
| `range`<br>`Int -> Int -> [Int]` | `range start end` produces consecutive integers, excluding `end`. `range 0 3` is `[0, 1, 2]`. |
| `linspace`<br>`Float -> Float -> Int -> [Float]` | `linspace start end count` produces evenly spaced values including both endpoints. A count of 1 returns only `start`. |
| `tabulate`<br>`(Int -> 'a) -> Int -> ['a]` | `tabulate f count` calls `f` on each index from 0 up to, but excluding, `count`. |
| `map`<br>`('a -> 'b) -> ['a] -> ['b]` | Applies a function to each element, preserving list order. `[1, 2] |> map (fun x -> x + 1)` is `[2, 3]`. |
| `mapIndexed`<br>`(Int -> 'a -> 'b) -> ['a] -> ['b]` | Calls the function with the zero-based index followed by the element. Use it when a transformation depends on position. |
| `filter`<br>`('a -> Bool) -> ['a] -> ['a]` | Keeps only elements for which the predicate returns `true`, preserving their order. |
| `fold`<br>`('a -> 'b -> 'a) -> 'a -> ['b] -> 'a` | `fold step initial list` accumulates left to right. Each step receives the accumulator, then the element. |
| `length`<br>`['a] -> Int` | Counts the elements. `length []` is `0`. |
| `isEmpty`<br>`['a] -> Bool` | Returns `true` if the list has no elements. Check this before reading its head or tail. |
| `head`<br>`['a] -> 'a` | Returns the first element. An empty list is a runtime error. |
| `tail`<br>`['a] -> ['a]` | Returns every element except the first. An empty list is a runtime error. |
| `nth`<br>`Int -> ['a] -> 'a` | `nth index list` reads a zero-based position. A negative or out-of-range index is a runtime error. |
| `take`<br>`Int -> ['a] -> ['a]` | Keeps at most the first `count` elements. A non-positive count returns an empty list. |
| `drop`<br>`Int -> ['a] -> ['a]` | Removes the first `count` elements. A non-positive count leaves the list unchanged. |
| `reverse`<br>`['a] -> ['a]` | Returns the elements in reverse order. |
| `append`<br>`['a] -> ['a] -> ['a]` | `append suffix list` places the suffix after the subject. `[1] |> append [2]` is `[1, 2]`. |
| `concat`<br>`[['a]] -> ['a]` | Flattens one level of nested lists. `concat [[1], [2, 3]]` is `[1, 2, 3]`. |
| `zip`<br>`['a] -> ['b] -> [('b, 'a)]` | Pairs the subject list with the supplied list, stopping at the shorter length. The piped element comes first in each pair. |
| `sortBy`<br>`('a -> Float) -> ['a] -> ['a]` | Sorts ascending by a floating-point key computed for each element. |
| `sum`<br>`[Float] -> Float` | Adds a list of `Float` values. The sum of an empty list is `0.0`. |

</div>

`head`, `tail` and `nth` fail at run time on a list that is too short. `zip` pairs the piped list first, so `[1, 2] |> zip ["a", "b"]` is `[(1, "a"), (2, "b")]`.

```diffstatic {data-lang=fieldml}
range 0 6
  |> filter (fun i -> mod 2 i == 0)
  |> map (fun i -> toFloat i / 5.0)
```

## Vectors

`Vec2`, `Vec3` and `Vec4` add and subtract component by component, multiply either component-wise or by a `Float`, divide by a `Float`, and read out through `.x`, `.y`, `.z` and `.w`. In `dot`, `vlength`, `distance`, `normalize` and `mix` the variable `'a` stands for any one of the three widths; both sides have to agree, and mixing widths is a type error.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name and type | What it does |
| --- | --- |
| `vec2`<br>`'a -> 'b -> Vec2` | Constructs a two-component vector from x and y. Each component accepts an `Int` or `Float`. |
| `vec3`<br>`'a -> 'b -> 'c -> Vec3` | Constructs a three-component vector from x, y and z. Each component accepts an `Int` or `Float`. |
| `vec4`<br>`'a -> 'b -> 'c -> 'd -> Vec4` | Constructs a four-component vector from x, y, z and w; also used for RGBA colours. |
| `dot`<br>`'a -> 'a -> Float` | Returns the scalar dot product of two vectors of the same width. |
| `vlength`<br>`'a -> Float` | Returns the Euclidean length of a vector. `vlength (vec2 3 4)` is `5.0`. |
| `distance`<br>`'a -> 'a -> Float` | Returns the Euclidean distance between two vectors of the same width. |
| `normalize`<br>`'a -> 'a` | Returns a unit-length vector in the same direction. A zero vector is left unchanged. |
| `mix`<br>`'a -> 'a -> Float -> 'a` | `mix a b amount` interpolates component by component. Both vectors must have the same width; the amount is not clamped. |
| `cross`<br>`Vec3 -> Vec3 -> Vec3` | Returns the oriented cross product of two three-component vectors. Argument order matters. |
| `polar`<br>`Float -> Float -> Vec2` | `polar angle radius` converts polar coordinates to a `Vec2`. The angle is in radians. |

</div>

The arguments of `vec2`, `vec3` and `vec4` are numeric rather than strictly `Float`, so `vec2 0 1` and `vec2 0.0 1.0` are both fine. `polar angle radius` is where most generated geometry starts.

```diffstatic {data-lang=fieldml}
let spoke i count =
  polar (tau * toFloat i / toFloat count) 50.0

range 0 4 |> map (fun i -> spoke i 4) |> length
```

## Lines

Path and style helpers all take the `FLine` last and return it, so they chain in a pipe. Coordinates are numeric, and `moveTo3`/`lineTo3` are the three-dimensional forms.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name and type | What it does |
| --- | --- |
| `fline`<br>`FLine` | Produces a fresh empty path each time it is evaluated. Use it at the start of a path-building pipe. |
| `moveTo`<br>`'a -> 'b -> FLine -> FLine` | `moveTo x y line` starts a new contour at a two-dimensional point, without drawing a segment. |
| `lineTo`<br>`'a -> 'b -> FLine -> FLine` | `lineTo x y line` adds a straight segment to a two-dimensional point. |
| `moveTo3`<br>`'a -> 'b -> 'c -> FLine -> FLine` | `moveTo3 x y z line` starts a new contour at a three-dimensional point. |
| `lineTo3`<br>`'a -> 'b -> 'c -> FLine -> FLine` | `lineTo3 x y z line` adds a straight segment to a three-dimensional point. |
| `moveToV`<br>`Vec2 -> FLine -> FLine` | Starts a new contour at a `Vec2` point. Useful when geometry is already represented as vectors. |
| `lineToV`<br>`Vec2 -> FLine -> FLine` | Adds a straight segment to a `Vec2` point. Use it in a fold over a list of points. |
| `cubicTo`<br>`'a -> 'b -> 'c -> 'd -> 'e -> 'f -> FLine -> FLine` | Takes two control points `(x0, y0)` and `(x1, y1)`, then the endpoint `(x, y)`, then the path. |
| `close`<br>`FLine -> FLine` | Closes the current contour back to its start and returns the path. |
| `thickness`<br>`'a -> FLine -> FLine` | Sets the path’s stroke thickness and returns the same path. |
| `color`<br>`Vec4 -> FLine -> FLine` | Sets the path’s RGBA colour from a `Vec4`. The fourth component is opacity. |
| `filled`<br>`Bool -> FLine -> FLine` | Enables or disables filling the path’s interior. |
| `stroked`<br>`Bool -> FLine -> FLine` | Enables or disables drawing the path’s outline. |
| `pointed`<br>`Bool -> FLine -> FLine` | Enables or disables drawing the path’s points. |
| `pointSize`<br>`Float -> FLine -> FLine` | Sets the size of rendered points. Enable them with `pointed true`. |

</div>

Folding a list of points into `fline` is the idiom for generated geometry: the accumulator is the line, and each step adds one instruction.

```diffstatic {data-lang=fieldml}
linspace 0.0 tau 24
  |> map (fun a -> polar a 30.0)
  |> fold (fun line point -> line |> lineToV point) fline
  |> close
  |> stroked true
```

## Output and animation

`print`, `println`, `display`, `draw` and the `tap` family all return what they were given, so they sit in the middle of a pipe without breaking it.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name and type | What it does |
| --- | --- |
| `print`<br>`'a -> 'a` | Prints the value without adding a newline and returns it unchanged. |
| `println`<br>`'a -> 'a` | Prints the value followed by a newline and returns it unchanged. |
| `display`<br>`String -> 'a -> 'a` | `display channel value` sends the value to a named output area, then returns it unchanged. |
| `show`<br>`'a -> String` | Formats a value as a string without sending it to an output area. |
| `tap`<br>`('a -> 'b) -> 'a -> 'a` | `tap effect value` calls the effect and returns the original value, discarding the effect’s result. |
| `draw`<br>`String -> FLine -> FLine` | `draw channel line` displays a path in a named output area and returns the path. |
| `drawSized`<br>`String -> Float -> Float -> FLine -> FLine` | `drawSized channel width height line` displays a path with an explicit output size. |
| `animate`<br>`(Float -> 'a) -> Host` | Returns an animation that Field drives after the box is launched. The function receives elapsed seconds each frame. |
| `animateFor`<br>`Float -> (Float -> 'a) -> Host` | `animateFor seconds f` runs the same animation model with a finite duration. |
| `Unit`<br>`Unit` | The named unit value, equivalent to `()`. It is not JVM null. |

</div>

`animate` returns a value Field drives once per frame, passing the seconds since the box was launched, until the box is stopped. The function runs for its effects, so draw inside it; `animateFor` is the same with a stopping time.

## JVM interop

Use `hostTap` when a Kotlin function is an effect in the middle of a pipe: it looks the function up in Field's source index, fills in defaults, and puts the piped value in the data argument. `hostCall` covers everything else. See [The JVM bridge](fieldml_host_bridge.md) for the mechanism and [FieldML Examples](fieldml_examples.md#jvm-interop) for worked versions.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name and type | What it does |
| --- | --- |
| `thisBox`<br>`Box` | The Field box evaluating this program. Use it as the target of host member calls or reads. |
| `hostTap`<br>`String -> 'a -> 'b -> 'b` | `hostTap name arguments value` finds a Kotlin helper through Field’s source index, applies the effect and returns the piped value. |
| `hostInput`<br>`Host` | A placeholder for the piped input inside an argument structure used by `hostStaticTap`. |
| `hostNull`<br>`Host` | JVM null, for a nullable host argument. Use this instead of `Unit` when a method requires an absent value. |
| `hostClass`<br>`String -> Host` | Resolves a fully qualified JVM class name for construction or static calls. |
| `hostNew`<br>`'a -> Host -> Host` | `hostNew arguments class` calls a constructor. Tuples and lists spread positionally; records supply named arguments and allow omitted defaults. |
| `hostNew0`<br>`Host -> Host` | Calls the class’s zero-argument constructor. |
| `hostCall`<br>`String -> 'a -> 'b -> Host` | `hostCall name arguments target` invokes an instance method. Use a record for Kotlin named arguments and defaults. |
| `hostCall0`<br>`String -> 'a -> Host` | `hostCall0 name target` invokes a method with no arguments. |
| `hostCall1`<br>`String -> 'a -> 'b -> Host` | `hostCall1 name argument target` passes exactly one argument. A list or record remains one value rather than being spread. |
| `hostCall2`<br>`String -> 'a -> 'b -> 'c -> Host` | `hostCall2 name a b target` invokes a method with exactly two arguments. |
| `hostStaticCall`<br>`String -> 'a -> Host -> Host` | `hostStaticCall name arguments class` invokes a static JVM method. Argument spreading follows `hostCall`. |
| `hostExt`<br>`Host -> String -> 'a -> 'b -> Host` | `hostExt class name arguments receiver` invokes a Kotlin extension compiled into that class. The piped receiver is separate from the written arguments. |
| `hostStaticTap`<br>`String -> 'a -> Host -> 'b -> 'b` | Calls a static method, substituting the piped value for `hostInput` in its arguments. Returns the piped value rather than the method result. |
| `hostGet`<br>`String -> 'a -> Host` | `hostGet name target` reads a host property or field. Reflection can preserve a more precise type than the static `Host` signature. |
| `hostSet`<br>`String -> 'a -> 'b -> Host` | `hostSet name value target` writes a host property or field and returns the target object. |
| `hostAs`<br>`String -> 'a -> Host` | `hostAs className value` asserts a specific JVM class after type erasure. The cast is checked at runtime. |
| `hostAsInt`<br>`'a -> Int` | Converts a compatible host value to a FieldML integer after a reflective call. |
| `hostAsFloat`<br>`'a -> Float` | Converts a compatible host value to a FieldML floating-point number. |
| `hostAsBool`<br>`'a -> Bool` | Converts a compatible host value to a FieldML Boolean. |
| `hostAsString`<br>`'a -> String` | Converts the supplied value to a FieldML string. |
| `hostAsList`<br>`'a -> [Host]` | Converts a host collection or array into a FieldML list so it can be mapped or folded. |

</div>

`hostCall name arguments target` takes as many arguments as the method does: a tuple, or a list written out in place, is spread; a **record** is spread *by name*, and every Kotlin parameter you leave out of it gets the default the source declared; anything else is a single argument. `hostNew` is the same for constructors. `hostCall0`, `hostCall1` and `hostCall2` fix the argument count instead; `hostCall1` never spreads, so it is how you pass one list — or one record — *as* a single argument. A FieldML function passed where the JVM wants a functional interface (a Kotlin lambda parameter, `Runnable`, `Comparator`, or a `suspend` one such as `eachFrame`'s) is converted on the way in.

The types in this table are the *static* ones. In a real call the type checker does better than `Host`: it resolves the member by reflection, so `thisBox |> hostGet "width"` is an `Int`, a misspelled member name is a type error rather than a crash, and a result whose class is known keeps that class as its type. `Host` is what is left when reflection cannot say — a generic erased to `Object`, a nullable Kotlin return, an array. It unifies with every type, which is what the `hostAs…` family is for.

## Source macros

Field's box-code macros (`moduleHere`, `prop`, `println` and the rest) are rewrites over Kotlin *source*, applied before the Kotlin compiler runs. FieldML has no Kotlin compiler, so it reaches them a different way: `hostMacroExpand` runs a macro's **expansion** against this box (which is where most of a macro's work happens — shaders compiled, properties seeded, actions installed) and hands the rewrite back; `hostMacro` does that and then **calls** the function the macro rewrote the call site to. See [The JVM bridge](fieldml_host_bridge.md#source-macros) for which macros this reaches and which it cannot.

The `prop` family is native rather than expanded: `prop<T>(name)` generates a delegate *declaration*, so there is no call to make — but the delegate is a named, inheritable slot on a box, and FieldML reads and writes the same one. A Kotlin box's `speed` and a FieldML box's `prop "speed"` are one property.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name and type | What it does |
| --- | --- |
| `hostMacroExpand`<br>`String -> 'a -> {call: String, arguments: String, here: String, preamble: String, declarations: String, start: String, end: String}` | Runs a box-code macro’s expansion using source-text arguments. Returns the rewritten call and its supporting source without calling it. |
| `hostMacro`<br>`String -> 'a -> 'b -> Host` | Expands a macro, then calls its rewritten target with the supplied runtime values. Expansion arguments are source text; call arguments are values. |
| `prop`<br>`String -> Host` | Reads a named box property, searching this box and its ancestors. A missing property is an error. |
| `propOr`<br>`String -> 'a -> Host` | `propOr name fallback` reads a property through the same inheritance chain and uses the fallback if it is absent. |
| `setProp`<br>`String -> 'a -> 'a` | `setProp name value` writes locally and returns the value. This shadows an ancestor’s property without changing it. |
| `rootProp`<br>`String -> Host` | Reads a named property from the document root. |
| `setRootProp`<br>`String -> 'a -> 'a` | Writes a named property on the document root and returns the value, making it available through inheritance. |
| `once`<br>`String -> ('a -> 'b) -> Host` | `once name f` computes and stores a value in this box’s own slot on first use. Later reads reuse that stored value; a JVM-null result is not cached. |

</div>

A macro's arguments are **source text**, because that is what a macro consumes: `hostMacroExpand "prop" ("<Double>", "(speed)")` is the `prop<Double>(speed)` call site, one string per piece. `hostMacro` takes those *and* the argument values for the rewritten call, since only the first can be text in an interpreter: `hostMacro "println" "(x)" (7, "hello")` expands `println`, then calls `Box._printlnHere` with line 7 and one payload.

Reading a `prop` that nothing up the graph has is an error — nearly always a box that has not run yet, rather than a typo. `propOr` is the form that takes a fallback instead. Writing with `setProp` is always **local**: it shadows an ancestor's value for this box and everything below it rather than changing theirs. `setRootProp` puts the slot on the document root, so every box shares the one value. `once` computes on the first read and stores the result, so the second read does not recompute.
