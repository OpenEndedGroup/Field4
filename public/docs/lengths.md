# Lengths in Millimetres and Inches

Use `Length` when a dimension needs to carry its unit. `12.7.mm`, `0.5.inch`, `4.ft` and `3.m`
are all lengths: you can add and compare them, then read the result in the unit you need. Draw a
part in inches and send its dimensions to a machine that wants millimetres without scattering
`* 25.4` or `/ 12` through your code.

Start a box with this import to use lengths:

```diffstatic {data-lang=kotlin}
:::static
import afield.math.units.length.*
```

## Writing a length

Put the unit after the number: `.mm`, `.cm`, `.m`, `.inch`, `.ft` or `.yd`. It is `.inch` rather
than `.in` because `in` is a Kotlin keyword. A length prints in millimetres, and `.mm`, `.cm`,
`.m`, `.inches`, `.feet` and `.yards` read it back as a plain `Double`.

This sets up the stock and parts for a laser-cut run, mixing inches and millimetres:

<!--
FIELD_TEST
id: docs.lengths.writing
runner: backdoor-box
tags: [docs, lengths, units]
timeoutSec: 60
expect:
  outputContains:
    - "88.9 mm"
    - "3.5"
    - "2' 0"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/lengths/writing.png" alt="the code with each println's value beside it: Length 88.9 mm, 3.5, 2 feet 0 inches, and 609.6" wait="1.0" capture="editor" outputs="inline" padding="10" timeout="60" embed=true name="lengths-writing"}
:::static
import afield.math.units.length.*

val stock = 2.ft
val part = 3.5.inch
val kerf = 0.15.mm

println(part)

println(part.inches)

println(stock.formatFeetInches())

println(stock.mm)
```

Every conversion is exact where the decimal you wrote allows it. An inch is exactly 25.4 mm, so
`12.inch == 1.ft`, `0.5.inch == 12.7.mm` and `1.234.m == 1234.mm` are all true, and `3.inch.inches`
is `3.0`, not the `2.9999999999999996` that multiplying and dividing by 25.4 gives.

## Arithmetic

Lengths add and subtract, scale by a number (`part * 3`, `3 * part`, `part / 2`), and compare with
`<` and `>`. Dividing one length by another gives a plain number: how many of the second fit in
the first. `abs`, `min`, `max` and `sum()` work as they do for numbers.

This works out how many parts fit across the stock, leaving a kerf's width between each:

<!--
FIELD_TEST
id: docs.lengths.arithmetic
runner: backdoor-box
tags: [docs, lengths, units]
timeoutSec: 60
expect:
  outputContains:
    - "6 parts"
    - "left over"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/lengths/arithmetic.png" alt="the code with its printed result beside the println: 6 parts, 3 inches left over" wait="1.0" capture="editor" outputs="inline" padding="10" timeout="60" embed=true name="lengths-arithmetic"}
:::static
import afield.math.units.length.*

val stock = 2.ft
val part = 3.5.inch
val kerf = 0.15.mm

val across = ((stock + kerf) / (part + kerf)).toInt()
val leftover = stock - (part + kerf) * across + kerf
println("$across parts, ${leftover.formatInches()} left over")
```

!!! note "Compare results with `near`, not `==`"
    Arithmetic on lengths is `Double` arithmetic, so it rounds in the last digit the way doubles
    do: `0.1.mm + 0.2.mm == 0.3.mm` is false. `a.near(b)` is true when the two are within a
    millionth of a millimetre, and `a.near(b, 0.01.mm)` takes the tolerance you want.

## Drawing with lengths

An `FLine` takes plain numbers, so read a length out in the unit your drawing uses as you pass it
in. This plate is specified in inches and drawn in millimetres: a 3 1/2" by 2" rectangle with four
1/4" holes, each 3/8" in from the corners.

<!--
FIELD_TEST
id: docs.lengths.drawing
runner: backdoor-box
tags: [docs, lengths, units, fline]
timeoutSec: 60
expect:
  outputContains:
    - "88.9 x 50.8 mm"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/lengths/plate.png" alt="a rectangle with a small circle near each corner" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="lines" wait="2" timeout="60" embed=true name="lengths-plate" padding="12"}
:::static
import afield.math.units.length.*

val w = 3.5.inch
val h = 2.inch
val inset = 0.375.inch
val hole = 0.25.inch

val plate = FLine().rect(0, 0, w.mm, h.mm)
val holes = listOf(inset, w - inset).flatMap { x ->
    listOf(inset, h - inset).map { y -> FLine().circle(x.mm, y.mm, (hole / 2).mm) }
}
println("${w.mm} x ${h.mm} mm")
lines(listOf(plate) + holes, w = 360.0, h = 220.0, fitToSize = true)
```

## Printing a length

`format` writes a length as a decimal in any unit. It keeps three decimal places and drops
trailing zeros unless you say otherwise, and groups digits in threes with a space.
`formatFeetInches` writes feet and inches with a fraction, rounded to the nearest 16th unless you
pass another `denominator`, and `formatInches` does the same in inches alone:

<!--
FIELD_TEST
id: docs.lengths.printing
runner: backdoor-box
tags: [docs, lengths, units]
timeoutSec: 60
expect:
  outputContains:
    - "1 250 mm"
    - "1.25 m"
    - "49.21 in"
    - "4' 1 3/16"
    - "49 3/16"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/lengths/printing.png" alt="the code with each format's result beside it: 1 250 mm, 1.25 m, 1.3 m, 49.21 in, 4 feet 1 3/16 inches, 4 feet 1 1/4 inches, 49 3/16 inches and 1 foot 0 inches" wait="1.0" capture="editor" outputs="inline" padding="10" timeout="60" embed=true name="lengths-printing"}
:::static
import afield.math.units.length.*

val w = 1250.mm
println(w)

println(w.format(LengthUnit.M))

println(w.format(LengthUnit.M, decimals = 1))

println(w.format(LengthUnit.IN, decimals = 2))

println(w.formatFeetInches())

println(w.formatFeetInches(denominator = 8))

println(w.formatInches())

println(11.99.inch.formatFeetInches())
```

The rounding carries, so 11.99 inches is `1' 0"` rather than `0' 12"`. Fractions are reduced
(`8/16` prints as `1/2`), and a negative length has one minus sign in front: `-4' 6 1/2"`.
`format(decimals = 1, trimZeros = false)` keeps a fixed number of places (`1 250.0 mm`), and
`groupSeparator = ""` turns the grouping off.

## Reading a length from text

`Length.parse` reads the lengths in a cut list, whether you type them into a text property or
load them from a file. It understands everything the printing functions write, as well as
`1250mm`, `1,250 mm`, `125 cm`, `6 inches`, `6-1/2"`, `4'-6"`, `4ft 6in` and typographic primes such as `4′ 6″`:

<!--
FIELD_TEST
id: docs.lengths.parsing
runner: backdoor-box
tags: [docs, lengths, units]
timeoutSec: 60
expect:
  outputContains:
    - "54 1/2"
    - "3/8"
    - "is not a unit"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/lengths/parsing.png" alt="the code with its output: 54 1/2, 49 3/16 (twice, shown once), 6 and 3/8 inches from the loop, then not a length, then the error naming the x that is not a unit" wait="1.0" capture="editor" outputs="inline" padding="10" timeout="60" embed=true name="lengths-parsing"}
:::static
import afield.math.units.length.*

val cutList = ["4' 6 1/2\"", "1250 mm", "1.25m", "6 in", "3/8\""]
for (cut in cutList) println(Length.parse(cut).formatInches())

println(Length.parseOrNull("12 furlongs") ?: "not a length")
try {
    Length.parse("4' 6 1/2x")
} catch (e: IllegalArgumentException) {
    println("${e.message}")
}
```

A number with no unit is an error, because it could be anything; `Length.parse("12", LengthUnit.MM)`
reads bare numbers in the unit you give. `parseOrNull` returns `null` instead of throwing.

## See also

- The [generated reference](reference/math.md#length-kt) lists every function on `Length`.
- [Spreadsheets](spreadsheets.md#values-that-carry-units-vectors-and-drawings) have their own
  units, written `3 | mm` inside a cell's formula, which also cover mass, time and the other
  physical quantities.
