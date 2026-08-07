# FieldML cheatsheet

FieldML is Field's small ML-flavoured expression language: statically typed, strict, and expression-oriented, with a prelude aimed at generating geometry. This page is the lookup surface — every name the language ships with, with its type. [FieldML Examples](fieldml_examples.md) is the guided tour.

Types are written the way they read: `->` is right associative, so `Float -> Float -> Vec2` is a function of two arguments. A type in argument position keeps its parentheses, as in `('a -> 'b) -> ['a] -> ['b]`.

## Shape of a box

Top-level `let` bindings stack up, and the last expression is the box's result.

```diffstatic {data-lang=fieldml}
let width = 40.0
let height = 25.0

{ area = width * height, square = width == height }
```

## Syntax

| Form | Example |
| --- | --- |
| Binding | `let x = 10` |
| Function | `let add a b = a + b` |
| Local binding | `let x = 6 in x * 7` |
| Anonymous function | `fun x -> x + 1` |
| Application | `add 20 22` |
| Pipe | `10 |> add 5` |
| Conditional | `if x > 0 then "up" else "down"` |
| Match | `match v with | Some x -> x | None -> 0` |
| Data type | `type Option a = None | Some a` |
| Tuple | `(1, "a", true)` |
| List | `[1, 2, 3]` |
| Record | `{ x = 1.0, y = 2.0 }` |
| Field | `point.x` |
| Comments | `// line` and `(* nested block *)` |

## Operators

Listed loosest to tightest. Everything binds looser than function application, so `print x * 10` is `(print x) * 10`.

| Operators | Meaning | Notes |
| --- | --- | --- |
| `||` | boolean or | short circuits |
| `&&` | boolean and | short circuits |
| `== != < <= > >=` | comparison | `==` needs Int, Float, Bool or String |
| `::` | prepend to a list | right associative |
| `+ - ++` | add, subtract, join strings | `+` and `-` also work on vectors |
| `* /` | multiply, divide | a vector may be scaled by a Float |
| `-` (prefix) | negate | numbers and vectors |
| `|>` | pipe the left value into the right function | |

## Conventions worth knowing

- **Single-subject helpers take the subject last**, so they read at the end of a pipe: `7 |> mod 4` is 3, `3.0 |> pow 2.0` is 9, `[1] |> append [2]` is `[1, 2]`.
- **`Int` and `Float` never mix silently.** `10 + 1.0` is a type error; use `toFloat`.
- **Only `let f x = ...` is recursive.** A plain `let x = ...` refers to an outer `x`, so shadowing keeps working.
- **Recursion is not tail-call optimised.** Long loops should use `range`/`tabulate` with `map`/`fold`.
- **Prefix `-` binds looser than application**, so `f -1.0` is a subtraction. Write `f (-1.0)`.

## Numbers

Angles are in radians. `abs`, `sign`, `min`, `max` and `clamp` keep whatever numeric type they are given; everything else here is `Float`.

| Name | Type |
| --- | --- |
| `pi` | `Float` |
| `tau` | `Float` |
| `sin` | `Float -> Float` |
| `cos` | `Float -> Float` |
| `tan` | `Float -> Float` |
| `asin` | `Float -> Float` |
| `acos` | `Float -> Float` |
| `atan` | `Float -> Float` |
| `atan2` | `Float -> Float -> Float` |
| `sqrt` | `Float -> Float` |
| `exp` | `Float -> Float` |
| `log` | `Float -> Float` |
| `floor` | `Float -> Float` |
| `ceil` | `Float -> Float` |
| `round` | `Float -> Float` |
| `pow` | `Float -> Float -> Float` |
| `fmod` | `Float -> Float -> Float` |
| `lerp` | `Float -> Float -> Float -> Float` |
| `abs` | `'a -> 'a` |
| `sign` | `'a -> 'a` |
| `min` | `'a -> 'a -> 'a` |
| `max` | `'a -> 'a -> 'a` |
| `clamp` | `'a -> 'a -> 'a -> 'a` |
| `mod` | `Int -> Int -> Int` |
| `idiv` | `Int -> Int -> Int` |
| `toFloat` | `Int -> Float` |
| `toInt` | `Float -> Int` |
| `not` | `Bool -> Bool` |

`lerp from to amount` and `clamp low high value` both end with the subject, so `0.5 |> lerp 0.0 10.0` is 5 and `5.0 |> clamp 0.0 1.0` is 1.

## Lists

`range start end` stops before `end`; `linspace start end count` includes both ends. Together with `tabulate` they are how FieldML iterates, since there is no loop statement.

| Name | Type |
| --- | --- |
| `range` | `Int -> Int -> [Int]` |
| `linspace` | `Float -> Float -> Int -> [Float]` |
| `tabulate` | `(Int -> 'a) -> Int -> ['a]` |
| `map` | `('a -> 'b) -> ['a] -> ['b]` |
| `mapIndexed` | `(Int -> 'a -> 'b) -> ['a] -> ['b]` |
| `filter` | `('a -> Bool) -> ['a] -> ['a]` |
| `fold` | `('a -> 'b -> 'a) -> 'a -> ['b] -> 'a` |
| `length` | `['a] -> Int` |
| `isEmpty` | `['a] -> Bool` |
| `head` | `['a] -> 'a` |
| `tail` | `['a] -> ['a]` |
| `nth` | `Int -> ['a] -> 'a` |
| `take` | `Int -> ['a] -> ['a]` |
| `drop` | `Int -> ['a] -> ['a]` |
| `reverse` | `['a] -> ['a]` |
| `append` | `['a] -> ['a] -> ['a]` |
| `concat` | `[['a]] -> ['a]` |
| `zip` | `['a] -> ['b] -> [('b, 'a)]` |
| `sortBy` | `('a -> Float) -> ['a] -> ['a]` |
| `sum` | `[Float] -> Float` |

`head` and `nth` fail at run time on a list that is too short. `zip` pairs the piped list first, so `[1, 2] |> zip ["a", "b"]` is `[(1, "a"), (2, "b")]`.

```diffstatic {data-lang=fieldml}
range 0 6
  |> filter (fun i -> mod 2 i == 0)
  |> map (fun i -> toFloat i / 5.0)
```

## Vectors

`Vec2`, `Vec3` and `Vec4` add and subtract component by component, multiply either component-wise or by a `Float`, divide by a `Float`, and read out through `.x`, `.y`, `.z` and `.w`. In `dot`, `vlength`, `distance`, `normalize` and `mix` the variable `'a` stands for any one of the three widths — both sides have to agree, and mixing widths is a type error.

| Name | Type |
| --- | --- |
| `vec2` | `'a -> 'b -> Vec2` |
| `vec3` | `'a -> 'b -> 'c -> Vec3` |
| `vec4` | `'a -> 'b -> 'c -> 'd -> Vec4` |
| `dot` | `'a -> 'a -> Float` |
| `vlength` | `'a -> Float` |
| `distance` | `'a -> 'a -> Float` |
| `normalize` | `'a -> 'a` |
| `mix` | `'a -> 'a -> Float -> 'a` |
| `cross` | `Vec3 -> Vec3 -> Vec3` |
| `polar` | `Float -> Float -> Vec2` |

The arguments of `vec2`, `vec3` and `vec4` are numeric rather than strictly `Float`, so `vec2 0 1` and `vec2 0.0 1.0` are both fine. `polar angle radius` is where most generated geometry starts.

```diffstatic {data-lang=fieldml}
let spoke i count =
  polar (tau * toFloat i / toFloat count) 50.0

range 0 4 |> map (fun i -> spoke i 4) |> length
```

## Lines

Path and style helpers all take the `FLine` last and return it, so they chain in a pipe. Coordinates are numeric, and `moveTo3`/`lineTo3` are the three-dimensional forms.

| Name | Type |
| --- | --- |
| `fline` | `FLine` |
| `moveTo` | `'a -> 'b -> FLine -> FLine` |
| `lineTo` | `'a -> 'b -> FLine -> FLine` |
| `moveTo3` | `'a -> 'b -> 'c -> FLine -> FLine` |
| `lineTo3` | `'a -> 'b -> 'c -> FLine -> FLine` |
| `moveToV` | `Vec2 -> FLine -> FLine` |
| `lineToV` | `Vec2 -> FLine -> FLine` |
| `cubicTo` | `'a -> 'b -> 'c -> 'd -> 'e -> 'f -> FLine -> FLine` |
| `close` | `FLine -> FLine` |
| `thickness` | `'a -> FLine -> FLine` |
| `color` | `Vec4 -> FLine -> FLine` |
| `filled` | `Bool -> FLine -> FLine` |
| `stroked` | `Bool -> FLine -> FLine` |
| `pointed` | `Bool -> FLine -> FLine` |
| `pointSize` | `Float -> FLine -> FLine` |

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

| Name | Type |
| --- | --- |
| `print` | `'a -> 'a` |
| `println` | `'a -> 'a` |
| `display` | `String -> 'a -> 'a` |
| `show` | `'a -> String` |
| `tap` | `('a -> 'b) -> 'a -> 'a` |
| `draw` | `String -> FLine -> FLine` |
| `drawSized` | `String -> Float -> Float -> FLine -> FLine` |
| `animate` | `(Float -> 'a) -> Host` |
| `animateFor` | `Float -> (Float -> 'a) -> Host` |
| `Unit` | `Unit` |

`animate` returns a value Field drives once per frame, passing the seconds since the box was launched, until the box is stopped. The function runs for its effects, so draw inside it; `animateFor` is the same with a stopping time.

## JVM interop

`hostTap` is the one to reach for: it looks a Kotlin function up in Field's source index, fills in defaults, and puts the piped value in the data argument. The rest spell out the JVM ABI by hand. See [FieldML Examples](fieldml_examples.md#jvm-interop) for worked versions.

| Name | Type |
| --- | --- |
| `thisBox` | `Host` |
| `hostTap` | `String -> 'a -> 'b -> 'b` |
| `hostInput` | `Host` |
| `hostClass` | `String -> Host` |
| `hostNew0` | `Host -> Host` |
| `hostCall0` | `String -> 'a -> Host` |
| `hostCall1` | `String -> 'a -> 'b -> Host` |
| `hostCall2` | `String -> 'a -> 'b -> 'c -> Host` |
| `hostStaticCall` | `String -> 'a -> Host -> Host` |
| `hostStaticTap` | `String -> 'a -> Host -> 'b -> 'b` |
| `hostGet` | `String -> 'a -> Host` |
| `hostSet` | `String -> 'a -> 'b -> Host` |

The bridge is deliberately narrow: constructors must take no arguments, calls top out at two, and no FieldML function can cross into Kotlin as a callback. Anything beyond that shape belongs in a Kotlin box for now.
