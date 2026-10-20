# Looking at Data

A box can hold a table of data, summarise it, and draw it, and you can change what it draws while
you look at it. This page does all four with one small, famous dataset: Anscombe's quartet, four
sets of eleven (x, y) points that the statistician Francis Anscombe published in 1973. All four
sets have the same summary statistics, and they look nothing alike.

## A table in a box

Field boxes can use [Kotlin DataFrame](https://kotlin.github.io/dataframe/), so the quartet goes
in as one frame with a column naming the set. `.h` draws a frame as a table, and `groupBy` with
`aggregate` summarises each set:

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_data/table.png" wait="2.5" capture="box" open-outputs="true" trim="true" w="700" padding="8" timeout="120" embed=true name="tour-data-table" alt="Two output panels. table shows the first ten rows of the quartet, columns set, x and y, all from set I (10 and 8.04, 8 and 6.95, down to 7 and 4.82), footed 44 rows × 3 columns — first 10; use .h2 to scroll all of them. summary has one row per set, I to IV: mean x is 9 for every set, mean y is 7.5009 for I, II and IV and 7.5 for III, and sd y is between 2.0304 and 2.0317 for all four."}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

:::fold Anscombe's quartet: 44 rows of set, x and y
val quartet = dataFrameOf(
    "set" to List(44) { ["I", "II", "III", "IV"][it / 11] },
    "x" to [
        10, 8, 13, 9, 11, 14, 6, 4, 12, 7, 5,
        10, 8, 13, 9, 11, 14, 6, 4, 12, 7, 5,
        10, 8, 13, 9, 11, 14, 6, 4, 12, 7, 5,
        8, 8, 8, 8, 8, 8, 8, 19, 8, 8, 8,
    ],
    "y" to [
        8.04, 6.95, 7.58, 8.81, 8.33, 9.96, 7.24, 4.26, 10.84, 4.82, 5.68,
        9.14, 8.14, 8.74, 8.77, 9.26, 8.10, 6.13, 3.10, 9.13, 7.26, 4.74,
        7.46, 6.77, 12.74, 7.11, 7.81, 8.84, 6.08, 5.39, 8.15, 6.42, 5.73,
        6.58, 5.76, 7.71, 8.84, 8.47, 7.04, 5.25, 12.50, 5.56, 7.91, 6.89,
    ],
)
:::endfold

display("table", quartet.h)

val summary = quartet.groupBy { set }.aggregate {
    mean { x } into "mean x"
    mean { y } into "mean y"
    std { y } into "sd y"
}
display("summary", summary.h)
```

`set`, `x` and `y` in that code are the frame's own columns, typed: Field runs the DataFrame
compiler plugin, which reads `dataFrameOf(...)` and gives the frame a member for each column. By
the summary, the four sets are the same: the same mean x, the same mean y, and the same spread
of y to two decimal places.

## A chart you can change

Drawing the sets shows how different they are. The box below filters the frame down to one set,
fits a straight line to it by least squares, and draws both as a `Chart`, a plot made of FLines. A
`menu` in a `controlGroup` picks the set, and choosing another one filters and redraws the chart
straight away, without running the box again:

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_data/chart.png" wait="2.5" capture="box" open-outputs="true" trim="true" w="900" padding="8" timeout="120" embed=true name="tour-data-chart" alt="Two output panels. pick holds a single menu labelled SET with IV chosen. chart is titled set IV: y = 3.00 + 0.50 x, with x from 2 to 20 and y from 2 to 14. Ten orange dots stand in one vertical column at x = 8, spread between y 5.25 and 8.84; the eleventh sits alone at x = 19, y = 12.5. The blue fit line runs from lower left to upper right through the middle of the column and through the lone point. The footnote reads mean y 7.50, sd y 2.03."}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

:::fold Anscombe's quartet: 44 rows of set, x and y
val quartet = dataFrameOf(
    "set" to List(44) { ["I", "II", "III", "IV"][it / 11] },
    "x" to [
        10, 8, 13, 9, 11, 14, 6, 4, 12, 7, 5,
        10, 8, 13, 9, 11, 14, 6, 4, 12, 7, 5,
        10, 8, 13, 9, 11, 14, 6, 4, 12, 7, 5,
        8, 8, 8, 8, 8, 8, 8, 19, 8, 8, 8,
    ],
    "y" to [
        8.04, 6.95, 7.58, 8.81, 8.33, 9.96, 7.24, 4.26, 10.84, 4.82, 5.68,
        9.14, 8.14, 8.74, 8.77, 9.26, 8.10, 6.13, 3.10, 9.13, 7.26, 4.74,
        7.46, 6.77, 12.74, 7.11, 7.81, 8.84, 6.08, 5.39, 8.15, 6.42, 5.73,
        6.58, 5.76, 7.71, 8.84, 8.47, 7.04, 5.25, 12.50, 5.56, 7.91, 6.89,
    ],
)
:::endfold

val draw = { name: String ->
    val one = quartet.filter { set == name }
    val xs = one.x.toList()
    val ys = one.y.toList()

    // the least-squares line through this set
    val mx = xs.average()
    val my = ys.average()
    val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx).pow(2) }
    val fit = [2, 20].map { my + slope * (it - mx) }

    val c = Chart(
        title = "set $name: y = %.2f + %.2f x".format(my - slope * mx, slope),
        xLabel = "x",
        yLabel = "y",
        note = "mean y %.2f, sd y %.2f".format(my, one.y.std()),
    )
    c.xDomain = 2.0 to 20.0 // the same axes for every set, so they compare
    c.yDomain = 2.0 to 14.0
    showChart(c.line("fit", [2, 20], fit).points("set $name", xs, ys, size = 6.0), channel = "chart")
}

controlGroup("pick") {
    menu("set", ["I", "II", "III", "IV"], initialIndex = 3) { _, name -> draw(name) }
}
draw("IV")
```

The box starts on set IV, where ten points share one x and a single outlier sets the slope. Every
set gets the same line, y = 3.00 + 0.50 x. Set I is the scatter that line describes, set II is a
smooth curve, and set III is a straight line of its own, pulled off course by one point. A summary
is a claim about a shape, and a chart is how you check it.

`draw` is a lambda rather than a `fun`: inside a `fun` declared in the box, `set` does not
resolve as a column of `quartet`. [`typed()`](dataframe.md#typed-the-way-across) gives a frame a
schema that every later piece of code can name.

## Where to go from here

- [DataFrames in a Box](dataframe.md) covers typed columns across runs, `.h2` for scrolling a
  frame of any size, and what the compiler plugin can and cannot type.
- [Charts as FLines](chart.md) has the rest of the marks (`bars`, `histogram`, `area`, rules and
  bands), log axes, `expect()` for drawing the range data should stay in, clickable marks, and
  `DataFrame.chart()`, which picks the axes for you.
- [Visualizers & Controls](visualizers.md#build-one-surface-with-mixed-control-types) has
  every control a `controlGroup` can hold, and how to keep a control's value in the document.
- The *Drawing From Data* section starts at [Linear Algebra](linear_algebra.md) and goes on to
  functions, signals over time, simulation and spatial data, all of which end in something you
  can draw.
