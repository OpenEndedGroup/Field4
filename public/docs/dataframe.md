# DataFrames in a Box

Field boxes can use [Kotlin DataFrame](https://kotlin.github.io/dataframe/) with its compiler plugin switched on, so a frame's columns are typed members you can name directly:

```diffstatic {data-lang=kotlin}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)

people.age            // resolves. Not people["age"], not a cast, not a generated file.
```

`people.age` is not a string lookup and not something you generated ahead of time. The plugin reads the `dataFrameOf("name", "age")(...)` call at compile time, works out the schema it produces, and gives the expression a type whose members are its columns. There is no `@DataSchema` in your project, no build step, and nothing on disk to keep in sync.

The typing does not survive from one execution to the next on its own; `typed()`, below, carries it across.

---

## The shape that works: derive and consume in one execution

Within one execution, a chain of calls just works. Every step re-types the frame, so the next step can name the columns the previous one produced:

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-typed-columns.png" wait="3.0" capture="editor" outputs="inline" padding="10" w="900" h="330" timeout="90" embed=true name="dataframe-typed-columns" alt="The Monaco editor holding the snippet, with two inline output strips wedged between its lines. The first, under the names println, reads names: [Alice, Bob, Cara] and kept: [Bob, Cara]. The second, under the decade println, reads decade: [3, 4]. So filter dropped Alice at 15, and the decade column the add call invented is readable by name afterwards."}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
)

// `age` inside the lambda resolves against the *input* schema.
// `decade` afterwards resolves against the schema `add` RETURNS.
val adults = people
    .filter { age >= 18 }
    .add("decade") { age / 10 }

println("names:  " + people.name.toList())
println("kept:   " + adults.name.toList())
println("decade: " + adults.decade.toList())
```

`decade` is not in the source data and is not declared anywhere. The plugin reads the `add("decade") { … }` call and adds the column to the frame's type.

## Where the boundary falls

Typed columns do not survive a compile boundary. Declare `val df = dataFrameOf("a","b")(1,2)` in one snippet, read `df.a` in the next, and the frame comes back as `DataFrame<Any>` with an empty compile-time schema. In live coding you load data once and then work with it over many runs, so you need [`typed()`](#typed-the-way-across).

??? aside "Why the boundary is there"
    The plugin hangs off `FirFunctionCallRefinementExtension`, an extension point the Kotlin
    compiler itself describes as *"highly unstable and not recommended to use"*. At a call to a
    `@Refine`-annotated function it statically interprets the arguments, computes the resulting
    schema, and rewrites the call: `call()` becomes
    `run { class Token; class Scope; call() as DataFrame<Token> }`. `Token` and `Scope` are
    local classes (the extension runs at body-resolve and cannot create top-level
    declarations), and the column accessors are not declarations at all: they are a per-call-site
    local type plus an implicit extension receiver. A local class cannot appear in the type of a
    snippet's property, so the type decays as soon as the frame is stored in a `val` that
    outlives the expression.

    `DataFramePluginSpike.kt` holds both halves: the call rewrite and the
    `ReturnTypeBasedReceiverInjector` that supplies the receiver at `:27`, and `probeAcrossSnippets` at
    `:147`, which is what prints the `<empty compile time schema>` result at `:161`.

A `@DataSchema interface` shared between boxes with [`sharedTypes(...)`](reference/index.md) does not help either: you can cast the frame to that type, but `people.age` still does not resolve. Use `typed()` in the box that needs the columns.

??? aside "Why a shared `@DataSchema` gets no accessors"
    The accessors come from `TopLevelExtensionsGenerator`, which asks K2's predicate-based provider
    for everything annotated `@DataSchema`, and that provider indexes source declarations only. A
    `@DataSchema` compiled into a directory on the classpath, which is what `sharedTypes` produces,
    is invisible to it.

    Kotlin DataFrame also ships `ImportedSchemasGenerator` plus `ImportedSchemasService`, gated on
    the plugin's `schemasPath` option pointing at a directory of schema JSON from a build-time
    generator. Field does not wire it. The mechanism is read in `DataFramePluginSpike.kt:171#ImportedSchemasService`;
    `typed()` does the same job without the build step.

---

## `typed()`: the way across

`DataFrame<T>.typed(name)` (`DataFrameSchemas.kt:50#fun <T> DataFrame<T>.typed(name: String): DataFrame<T> {`) reads the frame's columns at run time, when the data has already been loaded, and writes a schema for them into the calling box's preamble:

```diffstatic {data-lang=kotlin}
:::static
val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
).typed("People")

// …and this appears in the box's preamble:
// @org.jetbrains.kotlinx.dataframe.annotations.DataSchema
// interface People { val `name`: kotlin.String; val `age`: kotlin.Int }
```

From then on `People` is an ordinary source declaration in that box, so `people.cast<People>().age` resolves in every later execution.

Calling `typed()` on every run is cheap and safe. The preamble only re-evaluates lines that changed, so a re-run with unchanged columns costs nothing; a re-run with a *changed* schema rewrites the old declaration instead of accumulating a second one. Unlike `sharedTypes`, nothing is loaded into a classloader, so changing the columns and re-running simply redeclares the interface.

??? aside "The three design choices"
    <div class="field-data-table" markdown>

    | choice | why | where |
    |---|---|---|
    | **The preamble, not an inline eval.** | `typed()` runs *inside* the box's engine, and the compile dispatcher is a single thread, so evaluating another snippet from there risks deadlock. The preamble is the existing channel for "source this box should have compiled first". | `DataFrameSchemas.kt:31#- **The preamble, not an inline eval.** We are executing inside` |
    | **Unchanged schemas are free.** | `checkPreambleAndExecute` only evaluates preamble lines that *changed*, so re-running a box whose columns are the same costs nothing. | `DataFrameSchemas.kt:34#only evaluates lines that *changed* — so re-running a box with` |
    | **Replace by name, not append.** | Matching is on the `interface <name> {` prefix rather than the whole line, so a re-run with a *changed* set of columns rewrites the declaration instead of accumulating a second one. | `DataFrameSchemas.kt:100#val marker = "interface $name {"` |

    </div>

Flat value columns get their real element type. A column group or frame column would need a nested interface, so it is declared `Any?`: you still get the column, but not its shape. The log names which ones landed that way (`DataFrameSchemas.kt:67#val rendered = columns().map { it.name() to`). Calling `typed()` with no box in context logs a warning and does nothing (`DataFrameSchemas.kt:58#lprintln("warning,dataframe", "typed(\"$name\"): no box in`).

### One execution, two halves: the `//%-` marker { #fragment-marker }

`typed()` writes to the preamble, so it looks as though you have to run the box twice. You do not, if you split the box with a `//%-` marker. A `typed()` call in the first half has already published its schema by the time the second half is compiled:

```diffstatic {data-lang=kotlin}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

val people = dataFrameOf("name", "age")(
    "Alice", 15,
    "Bob", 30,
    "Cara", 45,
).typed("Peach")

//%-
val p = people.cast<Peach>()
println("ages:   " + p.age.toList())
println("adults: " + p.filter { age >= 18 }.name.toList())
```

Delete the `//%-` and the box stops compiling: with a single fragment, the schema is written only after that fragment has been compiled, so `Peach` does not exist yet.

??? aside "Why: setup blocks re-run before each fragment"
    Field re-runs a box's setup blocks (preamble, introduction, attic) before every `//%-` fragment, not once per execution: `runLaunchSetupBlocks(box)` sits *inside* the fragment loop (`execution.kt:1427#withContext(Runloop.runLoop.main) { runLaunchSetupBlocks(box) }`, defined at `execution.kt:1539#private suspend fun runLaunchSetupBlocks(box: Box): Boolean {`). A `typed()` call in the first fragment has already written its schema to the preamble, and that preamble re-compiles before the second fragment runs, so the schema is available.

Once it has run, the second half is independently re-runnable: select those lines, run them as often as you like, and the frame is never reloaded.

---

## Looking at a frame: `.h` and `.h2` { #looking-at-a-frame }

A DataFrame renders through [the value visualizer](visualizers.md#the-value-visualizer-what-h-draws) like any other value. Field registers it with `ValuePrinterRegistry` and `H2ValueRegistry` (`DataFrameVisualizer.kt:62#ValuePrinterRegistry.register(DataFrame::class.java) { frame ->`, `DataFrameVisualizer.kt:69#H2ValueRegistry.register(DataFrame::class.java) { frame ->`).

`.h` works on a frame, a column, a row and a `GroupBy`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-h-forms.png" wait="2.5" capture="box" open-outputs="true" w="900" h="620" padding="8" embed=true name="dataframe-h-forms" alt="Four stacked output panels, one per form. frame: a name/city/age table of Alice and Bob in Berlin, Cara and Dev in Lisbon, footed 4 rows × 3 columns. column: the age values 15, 30, 45, 22 under a kotlin.Int type line. row: the single record name Alice, city Berlin, age 15, with the field names in blue. grouped: groups 2, keyed by city, then a one-column table of Berlin and Lisbon footed 2 rows × 1 columns."}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

val people = dataFrameOf("name", "city", "age")(
    "Alice", "Berlin", 15,
    "Bob", "Berlin", 30,
    "Cara", "Lisbon", 45,
    "Dev", "Lisbon", 22,
)

display("frame", people.h)                    // a Table, plus a shape line
display("column", people.columns()[2].h)      // one column, plus its element type
display("row", people[0].h)                   // label/value facts
display("grouped", people.groupBy("city").h)  // how many groups, keyed by what
```

All four are drawn with the same `table()` and `facts()` that draw every other table in Field.

### The compact form costs the same at any size

The compact `.h` form shows the first 10 rows (`DataFrameVisualizer.kt:51#const val HEAD_ROWS: Int = 10`) and says how many it is hiding. It reads them cell by cell, by index (`DataFrameVisualizer.kt:119#private fun headCells(column: DataColumn<*>): List<Any?> {`), so printing a very large frame costs what printing ten rows costs, and you can leave a large frame as a box's last expression.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-big-head.png" wait="3.0" capture="box" open-outputs="true" w="900" h="440" padding="8" timeout="120" embed=true name="dataframe-big-head" alt="Two panels. head shows a single column i listing 0 through 9, footed 200000 rows × 1 columns — first 10; use .h2 to scroll all of them. cost below it reads: the compact form of a 200000-row frame is 1143 characters."}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

// 200,000 rows. A spread argument is one the plugin cannot statically interpret,
// so this frame is schema-less — the point here is the RENDERING cost.
val big = dataFrameOf("i")(*Array(200_000) { it })

val compact = big.h
display("head", compact)
display("cost", ("the compact form of a " + big.rowsCount() + "-row frame is " +
    compact.length + " characters").h)
```

??? aside "Agents: the test behind this"
    `kotlin.dataframe-visualizer.smoke` renders a 5,000-row and a 200,000-row frame and fails unless their compact forms differ by
    no more than 40 characters (`DataFrameVisualizerSmokeTest.kt:137#require(grew in -SHAPE_LINE_SLACK..SHAPE_LINE_SLACK) {`). The comparison is what catches a
    rendering that is linear in rows with a small constant, since that would pass a plain size ceiling at
    5,000 rows. There is a ceiling too: under 4,000 characters, no last row
    (`DataFrameVisualizerSmokeTest.kt:105#require(rendered.length < 4_000) {`).

### `.h2` scrolls the frame itself

`.h2` opens the frame in the h2 explorer, which you can scroll through the whole frame. Field keeps the frame itself on the server and builds only the rows you are looking at (`DataFrameVisualizer.kt:154#private class LazyRows`), so a large frame scrolls as quickly as a small one.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-h2-scroller.png" wait="4.5" capture="box" open-outputs="true" w="900" h="560" padding="8" timeout="150" embed=true name="dataframe-h2-scroller" alt="The scroller panel, headed 15000 rows × 2 columns with outliner and columns controls beside it. Inside, a LazyRows table labelled list | 15000 rows with columns #, i and sq, showing rows 0 to 5: i running 0,1,2,3,4,5 and sq the squares 0,1,4,9,16,25. Each row number carries a disclosure chevron."}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

// 15,000 rows, and nothing below pages them: `.h2` hands the whole FRAME to the
// explorer, which asks for the window it is showing and for nothing else. A spread
// argument is one the plugin cannot statically interpret, so this frame is
// schema-less — what is on show here is the rendering, not the typing.
val n = 15_000
val big = dataFrameOf("i", "sq")(
    *Array<Any?>(n * 2) { k ->
        val r = k / 2
        if (k % 2 == 0) r else r.toLong() * r
    }
)

display("scroller", big.h2)
```

The header gives the frame's full shape, `15000 rows × 2 columns`. The rows are shown as a table with one column per frame column, in the frame's order (`h2explorer.kt:261#fun recordCells`).

A frame inside another value, such as a list of frames or a data class with a frame field, expands the same way.

??? aside "How `.h2` reaches the explorer"
    The `.h2` output is an `<iframe>` onto `/h2-explorer?root=h2-<token>` (`h2explorer.kt:849#fun installH2ExplorerHandler`),
    and the explorer asks for its windows over the `/ws` `__h2_explorer__` channel. That is a
    different path from `/lazy-value-fragment`, which `renderListContinuation`
    (`ValueVisualizer.kt:2345#private fun renderListContinuation(value: Any?, offset: Int,`) serves
    when a value is expanded in place.

---

## Switches, and the version pin

<div class="field-data-table" markdown>

| what | how |
|---|---|
| Turn the plugin off for this JVM | `-Dafield.dataframePlugin=false` (`KotlinInterface2.kt:2996#if (System.getProperty("afield.dataframePlugin", "true") != "true") {`) |
| Point at a different plugin jar | `-Dafield.dataframePlugin.jar=<path>` (`KotlinInterface2.kt:3000#val override = System.getProperty("afield.dataframePlugin.jar")`) |
| Default jar location | `libs/dataframe-plugin.jar`, git-lfs tracked |

</div>

The DataFrame runtime is pinned to `1.0.0-Beta4`, the version the plugin was built against.

??? aside "The plugin jar and the version pin"
    The plugin jar carries its own copy of the DataFrame runtime, so putting it beside the real
    `dataframe-core` dependency would put two copies of `DataFrame` in front of box code
    (`KotlinInterface2.kt:2974#The two plugins are located differently on purpose. Wither's jar`).
    `-Xplugin` loads it in the compiler's own plugin classloader, while the runtime goes on the
    classpath normally (`build.gradle.kts:999#Kotlin DataFrame. The RUNTIME goes on the classpath here`).

    The plugin was built from Field's Kotlin fork, whose `libs.versions.toml` names that runtime
    version, and the plugin reads the runtime's API at compile time. Moving to a released
    DataFrame 1.0 means re-syncing the fork and rebuilding the plugin. The wiring and the probes are
    in [`dev_docs/DATAFRAME_PLUGIN.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/DATAFRAME_PLUGIN.md).

---

## Where to go next

- [`dataframe_typed_columns`](agent_transcripts.md#dataframe_typed_columns) in the Agent Transcript Gallery: a four-box example you can replay.
- [Visualizers, Displays, and Little Instruments](visualizers.md): everything else `.h` and `.h2` can draw.
- [Charts as FLines](chart.md): `DataFrame.chart()` plots a frame.
- [Kotlin in the Head](kotlin_in_the_head.md) and [Running code in a box](running_code.md): snippets, fragments and preambles.

## Known limits

- A frame's inferred type does not carry from one execution to the next. Use `typed()`.
- A `@DataSchema` shared with `sharedTypes` gives you the type but no column accessors. Use `typed()` in the box that needs the columns.
- Column groups and frame columns come back from `typed()` as `Any?`.
- The plugin uses a compiler extension point that the Kotlin compiler documents as unstable, so a Kotlin upgrade can break it.
