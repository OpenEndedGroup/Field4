# DataFrames in a Box

*Kotlin DataFrame's compiler plugin, running inside Field's K2 REPL.*

Field boxes can use [Kotlin DataFrame](https://kotlin.github.io/dataframe/) with its **compiler plugin** switched on, which is a larger claim than having the library on the classpath:

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

`people.age` is not a string lookup and not something you generated ahead of time. The plugin read the `dataFrameOf("name", "age")(...)` call **at compile time**, worked out the schema it produces, and gave the expression a type whose members *are* its columns. There is no `@DataSchema` in your project, no build step, and nothing on disk to keep in sync.

The typing does not survive a compile boundary, though, and `typed()` is the call that gets you across. The sections below cover the shape that works, where the boundary falls, and why it falls there.

!!! tip "This page as a document you can open"
    Everything here is in the shipped, replayable example `dataframe_typed_columns` — four boxes, in the [Agent Transcript Gallery](agent_transcripts.md).

---

## The shape that works: derive and consume in one execution

The plugin's happy path is a chain written in one go. Every step re-types the frame, so the *next* step can name the columns the previous one produced:

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

`decade` is not in the source data, is not a string key, and is not declared anywhere. It exists in the type only because the plugin statically interpreted the `add("decade") { … }` call, and without the plugin loaded that line does not compile.

## Where the boundary comes from

The plugin hangs off `FirFunctionCallRefinementExtension`, an extension point the Kotlin compiler itself describes as *"highly unstable and not recommended to use"*. At a call to a `@Refine`-annotated function it statically interprets the arguments, computes the resulting schema, and then **rewrites the call**: `call()` becomes `run { class Token; class Scope; call() as DataFrame<Token> }`. `Token` and `Scope` are **local** classes (the extension runs at body-resolve and cannot create top-level declarations), and the column accessors are not declarations anywhere at all. They are a per-call-site local type plus an implicit extension receiver.

That is where the boundary comes from. Typed columns do not survive a compile boundary, and the reason is earlier than Field or the REPL: *a local class cannot appear in the type of a snippet's property*. So the moment a frame is stored in a `val` that outlives the expression, its type decays: declare `val df = dataFrameOf("a","b")(1,2)` in one snippet, read `df.a` in the next, and the frame comes back as `DataFrame<Any>` with an empty compile-time schema. Live coding means loading once and then poking at the result, so that is fatal.

??? aside "Both halves, in the code"
    `DataFramePluginSpike.kt` holds both: the call rewrite and the
    `ReturnTypeBasedReceiverInjector` that supplies the receiver at `:27`, and `probeAcrossSnippets` at
    `:147`, which is what prints the `<empty compile time schema>` result at `:161`.

### Naming a `@DataSchema` does not, by itself, rescue it

The obvious repair is to declare a real named schema and cast to it. In Field the obvious way to do that is [`sharedTypes(...)`](reference/index.md), which compiles a `@DataSchema interface` onto the shared classloader so every box can name it. **The type crosses. The accessors do not.**

The frame really does arrive as `DataFrame<Person>` with a known schema; what is missing is the generated `people.age` extension. The reason is a discovery mechanism. The accessors come from `TopLevelExtensionsGenerator`, which asks K2's predicate-based provider for everything annotated `@DataSchema` — and that provider indexes **source** declarations only. A `@DataSchema` compiled into a directory on the classpath, which is exactly what `sharedTypes` produces, is invisible to it. Adding `import shared.*` does not help either: an `import` cannot conjure callables that were never created.

### The route that upstream designed for this is not wired here

Kotlin DataFrame does ship a second, separate mechanism for schemas arriving from outside the module: `ImportedSchemasGenerator` plus `ImportedSchemasService`, gated on the plugin's `schemasPath` option pointing at a directory of schema JSON produced by a build-time generator.

**Field does not wire it.** The `typed()` route below does the same job without a build step. So typed columns for a schema defined *outside* a box are not reachable by declaring the schema and casting to it.

??? aside "If the imported-schemas route is ever wanted"
    `schemasPath` is where to start, and the mechanism is read in `DataFramePluginSpike.kt:171#ImportedSchemasService`. It wants a
    build-time schema generator writing JSON into a directory, which is the step `typed()` avoids.

---

## `typed()`: the way across

`DataFrame<T>.typed(name)` (`DataFrameSchemas.kt:50#fun <T> DataFrame<T>.typed(name: String): DataFrame<T> {`) does not ask the plugin to remember anything. It reads the frame's **runtime** columns — which are exactly right, because the data has already been loaded — and writes a real schema into the calling box's **preamble**:

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

From then on `People` is an ordinary **source** declaration in that box, and a source declaration is the one thing `TopLevelExtensionsGenerator`'s predicate provider *does* index, so `people.cast<People>().age` resolves, in this execution and every later one.

Three choices in that design are what let it survive a live-coding loop:

<div class="field-data-table" markdown>

| choice | why | where |
|---|---|---|
| **The preamble, not an inline eval.** | `typed()` runs *inside* the box's engine, and the compile dispatcher is a single thread, so evaluating another snippet from there risks deadlock. The preamble is the existing channel for "source this box should have compiled first". | `DataFrameSchemas.kt:31#- **The preamble, not an inline eval.** We are executing inside` |
| **Unchanged schemas are free.** | `checkPreambleAndExecute` only evaluates preamble lines that *changed*, so re-running a box whose columns are the same costs nothing. | `DataFrameSchemas.kt:34#only evaluates lines that *changed* — so re-running a box with` |
| **Replace by name, not append.** | Matching is on the `interface <name> {` prefix rather than the whole line, so a re-run with a *changed* set of columns rewrites the declaration instead of accumulating a second one. | `DataFrameSchemas.kt:100#val marker = "interface $name {"` |

</div>

Redeclaring is safe here in a way it is not for `sharedTypes`: nothing is loaded into a classloader, so a shape change is ordinary REPL shadowing rather than an unrepeatable event.

**What you do not get.** Flat value columns get their real element type. A **column group** or **frame column** would need a nested interface to type properly, so it is declared `Any?` — you still get the column, just not its shape — and the log names which ones landed that way (`DataFrameSchemas.kt:67#val rendered = columns().map { it.name() to`). Calling `typed()` with no box in context logs a warning and does nothing (`DataFrameSchemas.kt:58#lprintln("warning,dataframe", "typed(\"$name\"): no box in`).

### One execution, two halves: the `//%-` marker { #fragment-marker }

`typed()` publishes to the preamble, so it looks as though you have to run the box twice. You do not, and the reason is a detail of Field's execution model.

Field re-runs a box's setup blocks (preamble, introduction, attic) before **every** `//%-` fragment, not once per execution: `runLaunchSetupBlocks(box)` sits *inside* the fragment loop (`execution.kt:1427#withContext(Runloop.runLoop.main) { runLaunchSetupBlocks(box) }`, defined at `execution.kt:1539#private suspend fun runLaunchSetupBlocks(box: Box): Boolean {`). So a `typed()` call in the first half of a box has already published its schema by the time the second half is compiled:

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

**Delete the `//%-` and the box stops compiling.** With a single fragment the schema is written only *after* that fragment has already been compiled, so `Peach` does not exist yet. Here a fragment marker changes what compiles, not just how the code reads.

Once it has run, the second half is independently re-runnable: select those lines, run them as often as you like, and the frame is never reloaded.

---

## Looking at a frame: `.h` and `.h2` { #looking-at-a-frame }

A DataFrame is a first-class Field value: it renders through [the value visualizer](visualizers.md#the-value-visualizer-what-h-draws) like anything else. `DataFrame` is a third-party interface, so it cannot implement `ToHTML` the way Field's own `Table` and `Img` do — which is exactly what `ValuePrinterRegistry` and `H2ValueRegistry` exist for (`DataFrameVisualizer.kt:62#ValuePrinterRegistry.register(DataFrame::class.java) { frame ->`, `DataFrameVisualizer.kt:69#H2ValueRegistry.register(DataFrame::class.java) { frame ->`).

Four things dispatch: a **frame**, a **column**, a **row**, and a **`GroupBy`**.

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

None of this is a bespoke widget. `.h` renders all four through the existing `table()` and `facts()` primitives, so DataFrame output matches every other table in Field.

### The compact form costs the same at any size

The compact `.h` form shows the first **10** rows (`DataFrameVisualizer.kt:51#const val HEAD_ROWS: Int = 10`) and says how much it is hiding. It takes them cell by cell, **by index**, never `toList()` on the frame and never `take` on a column (`DataFrameVisualizer.kt:119#private fun headCells(column: DataColumn<*>): List<Any?> {`), so printing a very large frame costs what printing ten rows costs. That matters, because this is the form that appears in a box output on **every** execution.

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

The capture above measures it: **1,143 characters for 200,000 rows.** A 5,000-row frame renders to 1,141. The two extra characters are `200000` being two digits longer than `5000` in the shape line, and nothing else grows.

??? aside "The test behind those two numbers"
    `kotlin.dataframe-visualizer.smoke` renders both frames and fails unless their compact forms differ by
    no more than 40 characters (`DataFrameVisualizerSmokeTest.kt:137#require(grew in -SHAPE_LINE_SLACK..SHAPE_LINE_SLACK) {`). The comparison is what catches a
    rendering that is linear in rows with a small constant, since that would pass a plain size ceiling at
    5,000 rows. There is a ceiling too — under 4,000 characters, no last row
    (`DataFrameVisualizerSmokeTest.kt:105#require(rendered.length < 4_000) {`).

### `.h2` scrolls the frame itself

`.h2` does not produce a bigger HTML dump. It *adapts* the frame into a lazily-indexed row view — a `List<Map<String, Any?>>` that materialises a row only when asked (`DataFrameVisualizer.kt:154#private class LazyRows`) — and the **h2 explorer** streams windows of that view by index. The server holds the **frame** behind an `.h2` token, not a rendering of it, so each scroll builds only the window being looked at.

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

Three things in that picture. The header is the frame's real shape, `15000 rows × 2 columns`, not the size of what was sent. The rows are a **table**, one column per key in the row's own key order (`h2explorer.kt:261#fun recordCells`), because a list of same-shaped maps is a table rather than a column of summaries. And `i` and `sq` are the frame's own column names, arriving from the data rather than from anything this page declared.

Mechanically, the `.h2` output is an `<iframe>` onto `/h2-explorer?root=h2-<token>` (`h2explorer.kt:849#fun installH2ExplorerHandler`), and the explorer asks for its windows over the `/ws` `__h2_explorer__` channel. That is a different path from `/lazy-value-fragment`, which `renderListContinuation` (`ValueVisualizer.kt:2345#private fun renderListContinuation(value: Any?, offset: Int,`) serves when a value is expanded in place. The two are easy to confuse, and only `/h2-explorer` is what `.h2` opens.

Reusing the general machinery pays off again for nesting: a frame *inside* another value (a list of frames, a data class with a frame field) expands the same way.

---

## Switches, and the version pin

<div class="field-data-table" markdown>

| what | how |
|---|---|
| Turn the plugin off for this JVM | `-Dafield.dataframePlugin=false` (`KotlinInterface2.kt:2996#if (System.getProperty("afield.dataframePlugin", "true") != "true") {`) |
| Point at a different plugin jar | `-Dafield.dataframePlugin.jar=<path>` (`KotlinInterface2.kt:3000#val override = System.getProperty("afield.dataframePlugin.jar")`) |
| Default jar location | `libs/dataframe-plugin.jar`, git-lfs tracked |

</div>

The plugin jar is **not** on the app classpath. It vendors ~1000 classes of the DataFrame runtime of its own, so putting it beside the real `dataframe-core` dependency would put **two copies of `DataFrame`** in front of box code (`KotlinInterface2.kt:2974#The two plugins are located differently on purpose. Wither's jar`). `-Xplugin` loads it in the compiler's own plugin classloader, so only the compiler ever sees it — while the *runtime* goes on the classpath normally (`build.gradle.kts:999#Kotlin DataFrame. The RUNTIME goes on the classpath here`).

The runtime version is pinned to `1.0.0-Beta2`. The plugin was built from Field's `2.4.255-SNAPSHOT` Kotlin fork, whose `libs.versions.toml` names that runtime version, and *the plugin statically interprets the runtime's API*. Moving to a released DataFrame 1.0 therefore means re-syncing the fork and rebuilding the plugin, not bumping a line in `build.gradle.kts`.

### What it costs every other box

Field builds one compiler engine per box, so anything added to the compilation state is paid per box rather than per DataFrame user. `kotlin.dataframe-plugin-cost.probe` measures it: toggling the plugin moves per-engine cost by **~4%** — 7449 ms against 7785 ms over three engines — and `dataframe-core` adds four entries to a 471-entry classpath (`DataFramePluginCostProbe.kt:27#cost by ~4% (7449ms vs 7785ms over three engines), and`).

Engine cost is dominated by warm-up rather than by either addition: the first engine costs ~4.6 s to construct and ~13 s to first-evaluate, the second and third ~200 ms and ~1–3 s.

---

## Where to go next

- **[Agent Transcript Gallery](agent_transcripts.md)** — `dataframe_typed_columns`, the four-box example, replayable.
- **[Visualizers, Displays, and Little Instruments](visualizers.md)** — everything else `.h` and `.h2` know how to draw.
- **[Kotlin in the Head](kotlin_in_the_head.md)** and **[Running code in a box](running_code.md)** — snippets, fragments, preambles, and why a compile boundary exists at all.
- `dev_docs/DATAFRAME_PLUGIN.md` — the wiring: `-Xplugin` versus classpath, the probes, the unwired `schemasPath` route.

## Known limits

- A frame's plugin-inferred type **does not** cross a compile boundary. `typed()` is the supported way across; there is no way to make the inferred token itself survive.
- A `@DataSchema` compiled into a directory (what `sharedTypes` produces) gives you the type but **no accessors**. Use `typed()` in the box that needs the columns.
- Column groups and frame columns come back from `typed()` as `Any?`.
- `schemasPath` / `ImportedSchemasGenerator` — the designed route for schemas from outside the module — is **not wired** in Field.
- `FirFunctionCallRefinementExtension` is documented by the Kotlin compiler as unstable. This feature rides on it knowingly.
