# Looking at Data

Field is meant for looking at data while you work on it. This stop takes one small, real dataset
through the tools you will use most: get the data, browse it with `.h` and `.h2`, see its
distribution next to the code that made it, plot it with Plotly, and then watch a value that is
changing too fast to print.

## Get some data

The data here is every time zone the JVM knows, with its offset from UTC on one day in January.
It comes from `java.time`, so it is already on your machine. `.h` turns any value into a compact
view, and `.h2` opens the same value in an explorer you can scroll and expand:

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_data/browse.png" wait="3" capture="box" open-outputs="true" trim="true" w="900" padding="8" timeout="120" embed=true name="tour-data-browse" alt="Three output panels. zones is the compact .h view of the map: Africa/Abidjan → 0, Africa/Accra → 0, Africa/Addis_Ababa → 3, Africa/Algiers → 1, Africa/Asmara → 3, then '… +599 more entries' and the size, 604. browse is the .h2 explorer of the same map, headed LinkedHashMap (size=604) with outliner and columns controls, showing a key/value table whose first rows are the same African zones with values 0.00, 3.00 and 1.00. offsets is one line: the first twenty offsets, '… +584 more items', then μ=0.606, σ=6.142, range=-12…14, the count 604, and a small sparkline of all of them."}
:::static
import java.time.*

val day = Instant.parse("2026-01-15T12:00:00Z")
val zones = ZoneId.getAvailableZoneIds().sorted().associateWith {
    ZoneId.of(it).rules.getOffset(day).totalSeconds / 3600.0
}
val offsets = zones.values.toDoubleArray()

display("zones", zones.h)
display("browse", zones.h2)
display("offsets", offsets.h)
```

The compact view of `offsets` already says a lot: 604 zones, offsets from −12 to +14 hours, a
mean a little east of Greenwich. `.h2` pages its rows in as you scroll, so it costs the same for a
map of six hundred entries or six million.

## See the distribution

A mean and a spread do not say what shape the data has. `__m("name")` makes a marker on the line
it is written on, and `histogram` and `stats` draw what that marker has seen next to the line, in
the editor. `stats` adds the median, quartiles, skew and the values in the order they arrived:

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_data/histogram.png" capture="editor" outputs="inline" wait="3" padding="10" timeout="120" embed=true name="tour-data-histogram" w="900" h="420" alt="The editor holding the snippet, with an inline panel under each marker line. Under histogram, twelve bins from min -12 to max 14 with the median at 1 and the quartiles at -5 and 5: one crowd of bins around -5, a taller one around +1, and a long low tail out to +14. Under stats, a summary headed n 604, mean 0.61, stddev 6.15, IQR 10.00, skew 0.27, the same histogram, and the series of offsets in alphabetical order of zone."}
:::static
import java.time.*

:::fold the zones and their offsets, as above
val day = Instant.parse("2026-01-15T12:00:00Z")
val zones = ZoneId.getAvailableZoneIds().sorted().associateWith {
    ZoneId.of(it).rules.getOffset(day).totalSeconds / 3600.0
}
val offsets = zones.values.toDoubleArray()
:::endfold

for (o in offsets) {
    __m("offsets").histogram(o, dur = offsets.size)
    __m("summary").stats(o, dur = offsets.size)
}
```

Two crowds: the Americas around five hours west of UTC, and Europe and Africa around one hour
east, with Asia and the Pacific trailing out to +14. The same markers work inside a `loop { }`,
where they keep a rolling window of the last `dur` values, which is how you watch a value that
changes every frame.

## Plot it

For a figure with axes, titles and hover, Field has [Plotly](https://plotly.com/javascript/). This
counts the zones at each exact offset and draws the counts as bars, which shows what twelve bins
could not: the zones that sit on a half or quarter hour.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_data/plot.png" wait="3" capture="box" open-outputs="true" trim="true" w="900" padding="8" timeout="120" embed=true name="tour-data-plot" alt="A Plotly bar chart titled time zones per UTC offset, x axis hours from UTC from -12 to 14, y axis zones. The tallest bars, around 50, are at 0 and +1 hours, with others above 40 at -5, -4, -3 and +2. Short bars sit between the whole hours, at offsets such as -3.5, +5.5, +5.75 and +9.5, and the right tail runs out to +14."}
:::static
import java.time.*
import space.kscience.plotly.*

:::fold the zones and their offsets, as above
val day = Instant.parse("2026-01-15T12:00:00Z")
val zones = ZoneId.getAvailableZoneIds().sorted().associateWith {
    ZoneId.of(it).rules.getOffset(day).totalSeconds / 3600.0
}
val offsets = zones.values.toDoubleArray()
:::endfold

val counts = offsets.toList().groupingBy { it }.eachCount().toSortedMap()

display(
    "plot",
    Plotly.plot {
        bar {
            x.set(counts.keys.toList())
            y.set(counts.values.toList())
        }
        layout {
            title = "time zones per UTC offset"
            xaxis { title = "hours from UTC" }
            yaxis { title = "zones" }
        }
    }
        .darkMode(),
)
```

For one-call plots there are `showSignals`, `linePlot`, `scatter` and `violin`. To draw a plot as
FLines, so it can sit in the same drawing as your other geometry, use a `Chart`; see
[Charts as FLines](chart.md).

## Probe a live value

Some values cannot be printed at all. The particles of a compute shader live on the GPU, a
hundred thousand of them, and change every frame. `fieldProbe` is one line in the shader that
records a value:

```diffstatic {data-lang=glsl}
:::static
fieldProbe(length(delta)); // how far this particle moved this step
```

and one line in the box that turns it on and shows what it recorded:

```diffstatic {data-lang=kotlin}
:::static
fieldProbeHere(samples = 4096).display("probe")
```

The card counts every value the shader recorded, says whether any were NaN or infinite, and draws
a sample of them with the same summary panel as `stats`, a few times a second while the particles
move:

```diffstatic_runner {data-lang=kotlin video="assets/tour_data/probe.mp4" capture="box" open-outputs=true wait-output=true wait-output-kind=display wait-output-channel=probe embed=true hidden=true force=true timeout="180" duration="6" fps="10" restart-target=false name="tour-data-probe" alt="The probe channel of the particle system's compute box, updating as the particles move. A header line reads calls 100000, finite 100000, nan 0, inf 0, then exact min 0.0000, sample mean about 0.56, p95 about 1.13 and exact max about 1.95. Below it the same summary panel that stats draws, a fine-binned histogram of step lengths rising to a broad peak near 0.5 and tailing off to the right, and the sorted values as a curve that turns sharply up at the maximum." macro-file="../../dev_docs/agents/transcripts/threebox.yaml" capture-box="tb_compute"}
// The "probe" display channel on the compute box of dev_docs/agents/transcripts/threebox.yaml
// (renderer + compute + draw), recorded live while the particles animate.
```

[Field Probes](field_probes.md) has the whole particle system, and `fieldStats`, which computes
the same statistics on the GPU and hands them back to the shader.

## Where to go from here

- [Visualizers & Controls](visualizers.md) has everything `.h` and `.h2` draw (vectors, FLines,
  images, functions), the other inline markers, and the controls you can put beside a plot.
- [Charts as FLines](chart.md) draws plots as geometry, with clickable marks and `expect()` for
  marking the range the data should stay in.
- [DataFrames in a Box](dataframe.md) is for data that arrives as a table: Kotlin DataFrame with
  typed columns, shown with the same `.h` and `.h2`.
- The *Drawing From Data* section starts at [Linear Algebra](linear_algebra.md) and goes on to
  functions, signals over time, simulation and spatial data.
