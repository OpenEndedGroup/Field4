# DataFrames in a Box

*Kotlin DataFrame's compiler plugin, running inside Field's K2 REPL — and the one thing it cannot do on its own.*

Field boxes can use [Kotlin DataFrame](https://kotlin.github.io/dataframe/) with its **compiler plugin** switched on. That is a bigger claim than "the library is on the classpath", so it is worth being exact about what it buys you:

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

This page is about the shape that works, the boundary the shape has, *why* the boundary is where it is, and the one call — `typed()` — that gets you across it.

!!! tip "Run it, don't read it"
    Everything here is in the shipped, replayable example `dataframe_typed_columns` — four boxes, in the [Agent Transcript Gallery](agent_transcripts.md).

---

## The shape that works: derive and consume in one execution

The plugin's happy path is a chain written in one go. Every step re-types the frame, so the *next* step can name the columns the previous one produced:

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-typed-columns.png" wait="3.0" capture="editor" padding="10" w="900" h="330" timeout="90" embed=true name="dataframe-typed-columns"}
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

`decade` is the line that cannot pass by accident. It is not in the source data, it is not a string key, and nothing declared it: it exists in the type only because the plugin statically interpreted the `add("decade") { … }` call. If the plugin were not loaded, that line would not compile.

## Why that is surprising, and where the boundary comes from

The plugin hangs off `FirFunctionCallRefinementExtension` — an extension point the Kotlin compiler itself describes as *"highly unstable and not recommended to use"*. At a call to a `@Refine`-annotated function it statically interprets the arguments, computes the resulting schema, and then **rewrites the call**: `call()` becomes `run { class Token; class Scope; call() as DataFrame<Token> }`. `Token` and `Scope` are **local** classes — the extension runs at body-resolve and cannot create top-level declarations — and the column accessors are not declarations anywhere at all. They are a per-call-site local type plus an implicit extension receiver.

That is the whole boundary, and it sits one step earlier than most people guess. Typed columns do not survive a compile boundary, but not because Field forgets, or the REPL resets, or the schema is thrown away. It is that *a local class cannot appear in the type of a snippet's property*. So the moment a frame is stored in a `val` that outlives the expression, its type decays: declare `val df = dataFrameOf("a","b")(1,2)` in one snippet, read `df.a` in the next, and the frame comes back as `DataFrame<Any>` with an empty compile-time schema. For live coding — where the entire point is to load once and then poke at the result — that is fatal.

??? aside "Where the rewrite and the decay were established"
    Both halves are recorded in `DataFramePluginSpike.kt`: the call rewrite and the
    `ReturnTypeBasedReceiverInjector` that supplies the receiver at `:22`, and `probeAcrossSnippets` at
    `:147`, which is what prints the `<empty compile time schema>` result at `:161`.

### Naming a `@DataSchema` does not, by itself, rescue it

The obvious repair is to declare a real named schema and cast to it. In Field the obvious way to do that is [`sharedTypes(...)`](reference/index.md), which compiles a `@DataSchema interface` onto the shared classloader so every box can name it. **The type crosses. The accessors do not.**

That is a more interesting failure than "it doesn't work", because the frame really does arrive as `DataFrame<Person>` with a known schema. What is missing is the generated `people.age` extension, and the reason is a discovery mechanism. The accessors come from `TopLevelExtensionsGenerator`, which asks K2's predicate-based provider for everything annotated `@DataSchema` — and that provider indexes **source** declarations only. A `@DataSchema` compiled into a directory on the classpath, which is exactly what `sharedTypes` produces, is invisible to it. Adding `import shared.*` does not help either: an `import` cannot conjure callables that were never created.

### The route that upstream designed for this is not wired here

Kotlin DataFrame does ship a second, separate mechanism for schemas arriving from outside the module: `ImportedSchemasGenerator` plus `ImportedSchemasService`, gated on the plugin's `schemasPath` option pointing at a directory of schema JSON produced by a build-time generator.

**Field does not wire it**, and does not need to: the `typed()` route below does the same job without a build step, which is the right trade for a live-coding environment. The thing to take from this section is the negative result — typed columns for a schema defined *outside* a box are not reachable by declaring the schema and hoping.

??? aside "If the imported-schemas route is ever wanted"
    `schemasPath` is where to start, and the mechanism is read in `DataFramePluginSpike.kt:171`. It wants a
    build-time schema generator writing JSON into a directory — which is precisely the step `typed()`
    exists to avoid.

---

## `typed()`: the way across

`DataFrame<T>.typed(name)` (`DataFrameSchemas.kt:50`) stops asking the plugin to remember, and gives it something it can see instead. It reads the frame's **runtime** columns — which are exactly right, because the data has already been loaded — and writes a real schema into the calling box's **preamble**:

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

From then on `People` is an ordinary **source** declaration in that box — which is the one thing `TopLevelExtensionsGenerator`'s predicate provider *does* index — so `people.cast<People>().age` resolves, in this execution and every later one.

Three choices in that design are worth knowing, because they are what make it survive a live-coding loop rather than merely work once:

| choice | why | where |
|---|---|---|
| **The preamble, not an inline eval.** | `typed()` runs *inside* the box's engine, and the compile dispatcher is a single thread, so evaluating another snippet from there risks deadlock. The preamble is the existing channel for "source this box should have compiled first". | `DataFrameSchemas.kt:31` |
| **Unchanged schemas are free.** | `checkPreambleAndExecute` only evaluates preamble lines that *changed*, so re-running a box whose columns are the same costs nothing. | `DataFrameSchemas.kt:34` |
| **Replace by name, not append.** | Matching is on the `interface <name> {` prefix rather than the whole line, so a re-run with a *changed* set of columns rewrites the declaration instead of accumulating a second one. | `DataFrameSchemas.kt:100` |

Redeclaring is safe here in a way it is not for `sharedTypes`: nothing is loaded into a classloader, so a shape change is ordinary REPL shadowing rather than an unrepeatable event.

**What you do not get.** Flat value columns get their real element type. A **column group** or **frame column** would need a nested interface to type properly, so it is declared `Any?` — you still get the column, just not its shape — and the log names which ones landed that way (`DataFrameSchemas.kt:67`). Calling `typed()` with no box in context logs a warning and does nothing (`DataFrameSchemas.kt:58`).

### One execution, two halves: the `//%-` marker { #fragment-marker }

`typed()` publishes to the preamble, so the naive reading is "you have to run the box twice". You do not — and the reason is a detail of Field's execution model that is worth knowing for its own sake.

Field re-runs a box's setup blocks (preamble, introduction, attic) before **every** `//%-` fragment, not once per execution: `runLaunchSetupBlocks(box)` sits *inside* the fragment loop (`execution.kt:1400`, defined at `execution.kt:1488`). So a `typed()` call in the first half of a box has already published its schema by the time the second half is compiled:

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

**Delete the `//%-` and the box stops compiling.** With a single fragment the schema is written only *after* that fragment has already been compiled, so `Peach` does not exist yet. This is the rare case where a fragment marker is load-bearing rather than cosmetic.

Once it has run, the second half is independently re-runnable: select those lines, run them as often as you like, and the frame is never reloaded. That is the live-coding loop this whole feature exists for.

---

## Looking at a frame: `.h` and `.h2` { #looking-at-a-frame }

A DataFrame is a first-class Field value: it renders through [the value visualizer](visualizers.md#the-value-visualizer-what-h-draws) like anything else. `DataFrame` is a third-party interface, so it cannot implement `ToHTML` the way Field's own `Table` and `Img` do — which is exactly what `ValuePrinterRegistry` and `H2ValueRegistry` exist for (`DataFrameVisualizer.kt:55`, `DataFrameVisualizer.kt:62`).

Four things dispatch: a **frame**, a **column**, a **row**, and a **`GroupBy`**.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-h-forms.png" wait="2.5" capture="box" open-outputs="true" w="900" h="620" padding="8" embed=true name="dataframe-h-forms"}
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

Nothing here is a bespoke widget. `.h` renders all four through the **existing** `table()` and `facts()` primitives, so DataFrame output matches every other table in Field rather than introducing a second style.

### A head is a head, even for a million rows

The compact `.h` form shows the first **10** rows (`DataFrameVisualizer.kt:44`) and says how much it is hiding. The interesting part is *how* it takes them: cell by cell, **by index**, never `toList()` on the frame and never `take` on a column (`DataFrameVisualizer.kt:101`). So printing a very large frame costs what printing ten rows costs — which matters, because this is the form that appears in a box output on **every** execution.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-big-head.png" wait="3.0" capture="box" open-outputs="true" w="900" h="440" padding="8" timeout="120" embed=true name="dataframe-big-head"}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

// 200,000 rows. A spread argument is one the plugin cannot statically interpret,
// so this frame is deliberately schema-less — the point here is the RENDERING cost.
val big = dataFrameOf("i")(*Array(200_000) { it })

val compact = big.h
display("head", compact)
display("cost", ("the compact form of a " + big.rowsCount() + "-row frame is " +
    compact.length + " characters").h)
```

The capture above is the claim, measured: **1,143 characters for 200,000 rows.** The same rendering of a 5,000-row frame is 1,141 characters — the two extra characters are `200000` being two digits longer than `5000` in the shape line, and nothing else grows.

??? aside "Why that claim is pinned by a comparison rather than a size limit"
    `kotlin.dataframe-visualizer.smoke` renders both frames and fails unless their compact forms differ by
    no more than 40 characters (`DataFrameVisualizerSmokeTest.kt:137`). A rendering that was linear in rows
    with a small constant would satisfy a plain size ceiling at 5,000 rows and blow past it at 200,000, so a
    ceiling alone would not catch the regression that matters. There is a ceiling as well — under 4,000
    characters, no last row (`DataFrameVisualizerSmokeTest.kt:105`).

### `.h2` is a real scroller, for free

`.h2` does not produce a bigger HTML dump. It *adapts* the frame into a lazily-indexed row view — a `List<Map<String, Any?>>` that materialises a row only when asked (`DataFrameVisualizer.kt:136`) — and the **h2 explorer** streams windows of that view by index. The server holds the **frame** behind an `.h2` token, not a rendering of it, so each scroll builds only the window being looked at.

```diffstatic_runner {data-lang=kotlin screenshot="assets/dataframe-h2-scroller.png" wait="4.5" capture="box" open-outputs="true" w="900" h="560" padding="8" timeout="150" embed=true name="dataframe-h2-scroller"}
:::static
import org.jetbrains.kotlinx.dataframe.api.*

// 15,000 rows, and nothing below pages them: `.h2` hands the whole FRAME to the
// explorer, which asks for the window it is showing and for nothing else. A spread
// argument is one the plugin cannot statically interpret, so this frame is
// deliberately schema-less — what is on show here is the rendering, not the typing.
val n = 15_000
val big = dataFrameOf("i", "sq")(
    *Array<Any?>(n * 2) { k ->
        val r = k / 2
        if (k % 2 == 0) r else r.toLong() * r
    }
)

display("scroller", big.h2)
```

Three things in that picture are worth naming. The header is the frame's real shape, `15000 rows × 2 columns`, not the size of what was sent. The rows are a **table** — one column per key, in the row's own key order (`h2explorer.kt:180#fun recordCells`) — because a list of same-shaped maps is a table and not a column of summaries. And `i` and `sq` are the frame's own column names, arriving from the data rather than from anything this page declared.

Mechanically, the `.h2` output is an `<iframe>` onto `/h2-explorer?root=h2-<token>` (`h2explorer.kt:559#fun installH2ExplorerHandler`), and the explorer asks for its windows over the `/ws` `__h2_explorer__` channel. That is a different path from `/lazy-value-fragment`, which `renderListContinuation` (`ValueVisualizer.kt:2269`) serves when a value is expanded in place — worth keeping straight, because the two are easy to confuse and only one of them is what `.h2` opens.

Doing it this way rather than with a bespoke widget has a second payoff: a frame nested *inside* another value — a list of frames, a data class with a frame field — expands with the same machinery.

---

## Switches, and the version pin

| what | how |
|---|---|
| Turn the plugin off for this JVM | `-Dafield.dataframePlugin=false` (`KotlinInterface2.kt:2959`) |
| Point at a different plugin jar | `-Dafield.dataframePlugin.jar=<path>` (`KotlinInterface2.kt:2963`) |
| Default jar location | `libs/dataframe-plugin.jar`, git-lfs tracked |

The plugin jar is deliberately **not** on the app classpath, and this is not an accident of packaging. It vendors ~1000 classes of the DataFrame runtime of its own, so putting it beside the real `dataframe-core` dependency would put **two copies of `DataFrame`** in front of box code (`KotlinInterface2.kt:2940`). `-Xplugin` loads it in the compiler's own plugin classloader, so only the compiler ever sees it — while the *runtime* goes on the classpath normally (`build.gradle.kts:952`).

The runtime version is pinned to `1.0.0-Beta2`, and **it is a pin rather than a preference**: the plugin was built from Field's `2.4.255-SNAPSHOT` Kotlin fork, whose `libs.versions.toml` names that runtime version, and *the plugin statically interprets the runtime's API*. Moving to a released DataFrame 1.0 therefore means re-syncing the fork and rebuilding the plugin — not bumping a line in `build.gradle.kts`.

### What it costs every other box

Field builds one compiler engine per box, so anything added to the compilation state is paid per box rather than per DataFrame user. That is worth a number, and it has one, from `kotlin.dataframe-plugin-cost.probe`: toggling the plugin moves per-engine cost by **~4%** — 7449 ms against 7785 ms over three engines — and `dataframe-core` adds four entries to a 471-entry classpath (`DataFramePluginCostProbe.kt:27`).

Engine cost is dominated by warm-up rather than by either addition: the first engine costs ~4.6 s to construct and ~13 s to first-evaluate, the second and third ~200 ms and ~1–3 s.

??? aside "The scare that produced that probe"
    A 243-box test appeared to go from a 39–43 s pass to a 284 s failure when the plugin landed. The real
    cause was two Field JVMs and a Gradle daemon competing for the machine; run alone, the same test passes
    in ~103 s. Treat any timing taken on a busy machine as noise until it is reproduced idle.

---

## Where to go next

- **[Agent Transcript Gallery](agent_transcripts.md)** — `dataframe_typed_columns`, the four-box example, replayable.
- **[Visualizers, Displays, and Little Instruments](visualizers.md)** — everything else `.h` and `.h2` know how to draw.
- **[Kotlin in the Head](kotlin_in_the_head.md)** and **[How Box Code Runs](box_code_pipeline.md)** — snippets, fragments, preambles, and why a compile boundary exists at all.
- `dev_docs/DATAFRAME_PLUGIN.md` — the wiring: `-Xplugin` versus classpath, the probes, the unwired `schemasPath` route.

## Known limits, stated plainly

- A frame's plugin-inferred type **does not** cross a compile boundary. `typed()` is the supported way across; there is no way to make the inferred token itself survive.
- A `@DataSchema` compiled into a directory (what `sharedTypes` produces) gives you the type but **no accessors**. Use `typed()` in the box that needs the columns.
- Column groups and frame columns come back from `typed()` as `Any?`.
- `schemasPath` / `ImportedSchemasGenerator` — the designed route for schemas from outside the module — is **not wired** in Field.
- `FirFunctionCallRefinementExtension` is documented by the Kotlin compiler as unstable. This feature rides on it knowingly.
