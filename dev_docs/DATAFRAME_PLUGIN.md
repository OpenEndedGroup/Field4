# Kotlin DataFrame's compiler plugin in Field's K2 REPL: the wiring

*2026-08-22. The public-facing page is `public/docs/dataframe.md`; this is the half a reader of that page does not need and a maintainer does.*

Everything below carries a `path:line#anchor` citation, resolved by anchor rather than by line
(`dev_docs/citation_contracts.md`). Anything not cited is marked `(conjecture, 2026-08-22)`.

---

## 1. Two plugins, located two different ways, on purpose

Field passes two `-Xplugin=` arguments into every K2 compilation state it builds, and the *way each
jar is found* is the load-bearing part:
`KotlinInterface2.kt:2945#witherPluginArgs + dataFramePluginArgs`.

| plugin | where the jar is found | why |
|---|---|---|
| Wither | on `java.class.path` | it carries annotations that **box code names**, so it has to be on the app classpath |
| DataFrame | `libs/dataframe-plugin.jar`, off the classpath | it vendors ~1000 DataFrame runtime classes of its own |

The DataFrame case is the one that would go wrong quietly if it were done the obvious way. The plugin
jar contains its own copy of `DataFrame`; put it on the app classpath beside the real `dataframe-core`
dependency and box code sees **two copies of the same type**:
`KotlinInterface2.kt:2940#two copies of`. `-Xplugin` loads it into the compiler's own plugin
classloader instead, so only the compiler ever sees it. The runtime goes on the classpath the ordinary
way: `build.gradle.kts:952#dataframe-core:1.0.0-Beta2`.

The jar is 9,758,425 bytes and is git-lfs tracked: `.gitattributes:28#libs/dataframe-plugin.jar filter=lfs`.

### Switches

| switch | effect | site |
|---|---|---|
| `-Dafield.dataframePlugin=false` | drop the `-Xplugin` argument entirely; logs that it did | `KotlinInterface2.kt:2959#System.getProperty("afield.dataframePlugin", "true")` |
| `-Dafield.dataframePlugin.jar=<path>` | first candidate in the search list, ahead of `libs/` and `user.dir` | `KotlinInterface2.kt:2963#System.getProperty("afield.dataframePlugin.jar")` |

Both are read through a `lazy`, so **they must be set before the first engine is built**. That is why
the cost probe is an entrypoint rather than something a box can call:
`DataFramePluginCostProbe.kt:45#which is why this is an entrypoint`.

The property list was renamed in `8189cc649`: what used to be `witherPluginArgs` (the single source of
`-Xplugin` arguments) is now `compilerPluginArgs`, with `witherPluginArgs` demoted to one of its two
contributors.

### The version pin is a pin

`dataframe-core` is pinned to `1.0.0-Beta2`. The comment beside it states the constraint that makes
this a pin rather than a preference: the plugin was built from Field's `2.4.255-SNAPSHOT` Kotlin fork,
whose `libs.versions.toml` names that runtime version, and **the plugin statically interprets the
runtime's API** — `build.gradle.kts:949#The plugin was built from`. So moving to a released DataFrame
1.0 means re-syncing the fork and rebuilding the plugin. It is not a line edit.

---

## 2. What the plugin actually does, and therefore where it stops

`FirFunctionCallRefinementExtension` — described by the Kotlin compiler itself as *"highly unstable
and not recommended to use"*. At a `@Refine`-annotated call it statically interprets the arguments,
computes the schema, and rewrites `call()` into
`run { class Token; class Scope; call() as DataFrame<Token> }`, with **local** classes, because the
extension runs at body-resolve and cannot create top-level declarations. The accessors are not
declarations anywhere: a per-call-site local type plus an implicit extension receiver injected by
`ReturnTypeBasedReceiverInjector` — `DataFramePluginSpike.kt:27#ReturnTypeBasedReceiverInjector`.

That is the whole boundary. A local class cannot appear in the type of a snippet's property, so the
inferred type cannot outlive the expression that produced it. Two mechanisms were candidates for
making it work anyway and are worth naming because they are the reason this was *measured* rather than
asserted: `addReceiversFromExtensions` bails unless the enclosing container is a `FirCallableSymbol` or
`FirCodeFragmentSymbol`, and `FirReplSnippetSymbol` is neither
(`DataFramePluginSpike.kt:37#is neither, so the injected receiver`); but the schema state rides on the FIR
declaration via `FirDeclarationDataRegistry` rather than a session component, which could have carried
it across (`DataFramePluginSpike.kt:39#FirDeclarationDataRegistry`).

### The spike, and its five probes

`kotlin.dataframe-plugin.spike`, runner `jvm-main`, entrypoint `afield.DataFramePluginSpike`
(`DataFramePluginSpike.kt:8#kotlin.dataframe-plugin.spike`). It asserts two invariants loudly and then
reports either way on five probes — the invariants exist because a spike that silently measures plain
Kotlin is worse than no spike:

| probe | question | site |
|---|---|---|
| A | does refinement fire here at all? `df.a` in the same snippet | `DataFramePluginSpike.kt:111#private fun probeWithinOneSnippet` |
| C | `add("c") { a + b }` — a schema that *changes* | `DataFramePluginSpike.kt:129#private fun probeRefinedOperation` |
| B | **the measurement**: `val df` in snippet 1, `df.a` in snippet 2 | `DataFramePluginSpike.kt:147#private fun probeAcrossSnippets` |
| D | a `@DataSchema` published through `SharedTypes` | `DataFramePluginSpike.kt:176#private fun probeSharedDataSchema` |
| E1/E2 | a `@DataSchema` declared **in a snippet**, used in that snippet and in a later one | `DataFramePluginSpike.kt:232#private fun probeSnippetDeclaredSchema` |

Invariant 1 refuses to run if no `-Xplugin=…dataframe-plugin.jar` reached the compiler
(`DataFramePluginSpike.kt:94#endsWith("dataframe-plugin.jar")`); invariant 2 refuses if
`dataframe-core` is not resolvable (`DataFramePluginSpike.kt:105#dataframe-core is not on the classpath`).

**Probe B's measured result**: `DataFrame<Any>` and `<empty compile time schema>`
(`DataFramePluginSpike.kt:161#empty compile time schema`).

**Probe D is the interesting negative.** The type crosses; the accessors do not. The cause is
`TopLevelExtensionsGenerator`, which discovers schemas via
`session.predicateBasedProvider.getSymbolsByPredicate(annotated(DataSchema))`, and K2's
predicate-based provider indexes **source declarations only** — so a `@DataSchema` compiled into a
directory, which is exactly what `SharedTypes` produces, is invisible to it. An `import` cannot help
because the callables were never created:
`DataFramePluginSpike.kt:165#getSymbolsByPredicate(annotated(DataSchema))`.

**Probe E is what made `typed()` designable.** E1 and E2 both firing means Field need only emit the
interface and let the plugin generate every accessor; E1 alone would have meant emitting the accessors
by hand too (`DataFramePluginSpike.kt:227#both true`).

### The route upstream designed for outside-the-module schemas, and its status

`ImportedSchemasGenerator` + `ImportedSchemasService`, gated on the plugin's `schemasPath` option
pointing at a directory of schema JSON produced by a build-time generator:
`DataFramePluginSpike.kt:171#ImportedSchemasService`.

**Not wired in Field.** State it as an open path, because that is what it is: `typed()` covers the
live-coding case without a build step, and `schemasPath` is where to start if schemas defined outside
a box are ever wanted. It is explicitly *not* reachable by declaring the schema and hoping.

Upstream's own REPL work targets the Jupyter kernel, where cross-cell persistence is done by runtime
codegen of real top-level `@DataSchema` interfaces with the plugin's top-level generator switched off
to avoid duplicating them (KT-77437) — `DataFramePluginSpike.kt:46#(KT-77437)`. Field's `typed()` is the
same shape of answer, minus the "switch the generator off" half, because Field wants the generator on
for the within-execution case.

---

## 3. `typed()`

`DataFrameSchemas.kt:50#fun <T> DataFrame<T>.typed`. Reads the frame's **runtime** columns — right by
construction, because the data is already loaded — renders each column's element type through
`ExportedTypeNames.render` and publishes one line into the calling box's preamble:
`DataFrameSchemas.kt:67#ExportedTypeNames.render(it.type(), loader)`.

Three design choices, all of which are about surviving a live-coding loop rather than working once:

1. **Preamble, not an inline eval.** Execution is inside the box's own engine and the compile
   dispatcher is a single thread, so evaluating another snippet from there risks deadlock; and
   `checkPreambleAndExecute` only evaluates lines that *changed*, so an unchanged schema is free on
   re-run — `DataFrameSchemas.kt:34#only evaluates lines that *changed*`.
2. **One line, no block comment, no inheritance marker.** The preamble is line-oriented unless a block
   is marked, and omitting the inheritance marker keeps the schema in this box rather than pushing it
   onto every child — `DataFrameSchemas.kt:36#One line, no`.
3. **Replace by name.** Matching is on the `interface <name> {` prefix, not the whole line, which is
   what makes a re-run with a *changed* column set rewrite the declaration instead of accumulating a
   second one — `DataFrameSchemas.kt:100#val marker = "interface $name {"`.

Redeclaring is safe here where it is not for `SharedTypes`: nothing is loaded into a classloader, so a
shape change is ordinary REPL shadowing rather than an unrepeatable event
(`DataFrameSchemas.kt:41#ordinary REPL shadowing`).

Degradations, both logged rather than silent: a column group or frame column would need a nested
interface, so it is declared `Any?` via `ExportedTypeNames.OPAQUE` and the log names which ones landed
that way (`DataFrameSchemas.kt:68#it.second == ExportedTypeNames.OPAQUE`); and no box in context is a warning and a
no-op (`DataFrameSchemas.kt:58#no box in context`).

### The fragment loop — the correction worth recording

`typed()` publishes to the preamble, which reads like "you must run the box twice". You must not,
because Field re-runs setup blocks before **every** `//%-` fragment, not once per execution:
`runLaunchSetupBlocks(box)` sits *inside* the `for (breakIndex in breaks)` loop —
`execution.kt:1400#runLaunchSetupBlocks(box) }`, defined at
`execution.kt:1488#private suspend fun runLaunchSetupBlocks`, and it runs preamble, then introduction,
then attic.

So `typed()` in the first fragment is visible to the second in a single run, and **removing the marker
stops the box compiling** — with one fragment the schema is written only after that fragment was
already compiled.

`d7d8dccc2`'s own commit message records that this correction came from Marc, against the agent's
reading of the fragment loop. It is recorded here rather than left in a commit message because it is a
fact about Field's execution model that generalises well past DataFrame: **a `//%-` marker is a
preamble re-entry point, not just a text split.**

---

## 4. `DataFrameVisualizer`

`src/jvmMain/kotlin/afield/visualization/DataFrameVisualizer.kt`, 148 lines, `package afield`.

`DataFrame` is a third-party interface and cannot implement `ToHTML` the way `Table` and `Img` do,
which is what `ValuePrinterRegistry` (compact `.h`) and `H2ValueRegistry` (`.h2`) exist for:
`DataFrameVisualizer.kt:62#ValuePrinterRegistry.register(DataFrame::class.java)` and
`DataFrameVisualizer.kt:69#H2ValueRegistry.register(DataFrame::class.java)`.

- **`.h`** renders frame / column / row / `GroupBy` through the **existing** `table()` and `facts()`
  primitives, so DataFrame output matches every other table rather than introducing a second style.
- **The head is read by index**, 10 rows (`DataFrameVisualizer.kt:51#HEAD_ROWS: Int = 10`), one cell at
  a time — never `toList()` on the frame and never `take` on a column, which is also ambiguous between
  DataFrame's own `take` and `Iterable.take`: `DataFrameVisualizer.kt:119#private fun headCells`. A
  million-row frame therefore costs what ten rows cost, which matters because `.h` is what a box output
  shows on **every** execution.
- **`.h2`** adapts the frame into a lazily-indexed `AbstractList<Map<String, Any?>>` row view
  (`DataFrameVisualizer.kt:154#private class LazyRows`) and hands that to the h2 explorer, which
  reads a window at a time by index (`h2explorer.kt:104#interface H2Source`, whose `rows(start,
  count, stride)` is how the explorer asks for one). The server holds the frame behind an `.h2`
  token, not a rendering of it. Side benefit: a frame nested inside another value expands with the
  same machinery.
- **`GroupBy` has its own registration** because the registry's lookup was the design risk here, and
  the regression that named it is kept: `DataFrameVisualizerSmokeTest.kt:152#the whole interface closure`.
- `install()` is idempotent and called from `Server.main`; a bare `jvm-main` harness does not run that,
  so tests call it explicitly (`DataFrameVisualizer.kt:58#fun install()`).

### The dispatch bug this page's captures found

`register(DataColumn::class.java)` was **dead code**, and had been for as long as the registry has
existed. `ValuePrinterRegistry.getPrinter` resolved a printer by checking the exact class, then the
superclass chain, then **only the interfaces the runtime class declares directly**. DataFrame's
columns are precisely the shape that defeats that:

    ValueColumnImpl<T> : DataColumnImpl<T>(), ValueColumn<T>
    ValueColumn<T>     : DataColumn<T>

`DataColumn` is one interface further out than the lookup looked, so a column fell through to
`toString()` and rendered as `ValueColumnImpl age 0 15 1 30 2 45 3 22`. `FrameColumnImpl` has the same
shape. Fixed by walking the whole interface closure, breadth-first, at every step of the superclass
chain — `ValueVisualizer.kt:97#internal fun getPrinter` and
`ValueVisualizer.kt:108#private fun findInInterfaceClosure`. `H2ValueRegistry.adapt` had the identical
bug and got the identical fix (`LazyValueVisualizer.kt:73#internal fun adapt`), deliberately kept
character-for-character parallel: these two registries are the pair of extension points a third-party
type reaches Field through, and having them disagree about what "registered for this type" means is
the bug twice.

Two things about *how* it was found are the reusable part.

**No test noticed, because the assertion was vacuous.** `testRowAndColumnRender` asserted
`column.contains("Alice")`, and `ValueColumnImpl name Alice Bob Cara` contains Alice. It is the same
vacuity the class KDoc already warns about one function earlier, for the lambda-invocation count that
passed with zero — and it recurred anyway, in the same file, which is the argument for asserting on
something a fallback *cannot* produce rather than on something it happens to contain. The assertion
now requires the column's declared element type, which only `columnHead` emits, and the absence of the
impl class name: `DataFrameVisualizerSmokeTest.kt:203#it was **vacuous**`.

**The mutation check, because a strengthened assertion that was never seen to fail is not evidence.**
Reverting `getPrinter` to the direct-interfaces-only walk and re-running
`kotlin.dataframe-visualizer.smoke` fails with the intended diagnosis — *"a column fell through to
toString() — the registry did not reach DataColumn through ValueColumn, so register(DataColumn) is
dead"* — and restoring it passes. Measured 2026-08-22 on port 9483: FAIL in 4157 ms, PASS in 4132 ms.

**Blast radius — and the reason is structural, not arithmetical.** The change can only turn a value
that previously fell through into one that dispatches; it cannot move a value from one registered
printer to another, because any match the old walk found is still found first. The old walk was *exact
class → superclass chain → the runtime class's **directly declared** interfaces*, so the keys the new
walk can newly reach are exactly the registered **interfaces** that sat more than one hop out. A key
that is a *class* was already reachable, at every step, by the superclass-chain walk both versions
share (`ValueVisualizer.kt:97#internal fun getPrinter`). **No class-keyed registration's dispatch can
have changed — whatever the total registrations happen to be.**

That distinction is doing all the work, so the totals are stated separately and derived rather than
counted. Compiled source carries 4<!--count:valuePrinterInterfaceKeys--> interface-keyed
registrations, all DataFrame's and all in one file
(`DataFrameVisualizer.kt:62#ValuePrinterRegistry.register(DataFrame::class.java)` and the three beside
it), and 51<!--count:valuePrinterClassKeys--> class-keyed ones across `FourVisualizers.kt`,
`PolytopeVisualizers.kt`, `four/synth/Visualizers.kt`, `JoltPhysicsWorld.kt`, `fmchirp.kt` and
`four/fabricated/PathTraceInstrument.kt`. Every
one of the latter goes through the reified wrapper `registerValuePrinter<T>`
(`ValueVisualizer.kt:2578#inline fun <reified T : Any> registerValuePrinter`), which is why a grep for
`ValuePrinterRegistry.register` sees only the first group — and why this paragraph, until 2026-08-23,
claimed there were four registrations altogether.

Both numbers are pinned to the code rather than remembered: the `<!--count:…-->` markers above are
checked by `ProseNumeralContractTest.kt:291#fun everyMarkedNumeralMatchesItsDerivation`, and the
structural claim itself — that the interface-keyed set is *exactly* DataFrame's four, and that the two
sets partition the whole population with nothing left over — by
`ProseNumeralContractTest.kt:404#fun everyValuePrinterRegistrationInTheTreeKeysAClassExceptDataFrames`.
So a fifth interface-keyed registration fails the build with a message asking for this argument to be
reread, rather than quietly making the paragraph false. The mechanism is written up at
`citation_contracts.md:191### Pinning a number written in prose`.

The one box-side registration in the tree, `dev_docs/viz_demo.kt:58#registerValuePrinter<Point3D>`, is
outside both counts — it is a documentation box rather than compiled source — and is an exact-class
match on a data class, so it is unaffected either way.

### The smoke test, and the vacuous assertion it replaced

`kotlin.dataframe-visualizer.smoke` (`DataFrameVisualizerSmokeTest.kt:9#kotlin.dataframe-visualizer.smoke`)
asserts *dispatch and laziness*, not markup, so it is not a change-detector on the table renderer.
5,000 rows (`DataFrameVisualizerSmokeTest.kt:38#ROWS = 5_000`); the compact form must not contain the
last row and must be under 4,000 characters (`DataFrameVisualizerSmokeTest.kt:105#rendered.length < 4_000`);
and `.h2` is paged the way the client pages it, through
`ValueVisualizer.renderLazyFragment` at offsets 2,000 and `ROWS - 5`
(`DataFrameVisualizerSmokeTest.kt:182#renderLazyFragment(token, "list", 2_000, 0)`).

The KDoc records why the assertion is on output content: an earlier version counted lambda invocations
on an added column and **passed with a count of zero**, because `add` materialises its column eagerly
and cell access never re-invokes the lambda. The assertion was vacuous
(`DataFrameVisualizerSmokeTest.kt:30#the assertion was`). Worth keeping in view whenever a laziness
claim is tested by counting.

---

## 5. What it costs every other box

Field builds one `KotlinInterface2` per box, so anything added to the compilation state is paid per
box, not per DataFrame user. `kotlin.dataframe-plugin-cost.probe`
(`DataFramePluginCostProbe.kt:8#kotlin.dataframe-plugin-cost.probe`), run with `--arg on` / `--arg off`:

- toggling the plugin moves per-engine cost by **~4%**: 7449 ms against 7785 ms over three engines,
  and `dataframe-core` adds four entries to a 471-entry classpath —
  `DataFramePluginCostProbe.kt:27#7449ms vs 7785ms`;
- engine cost is dominated by **warm-up**, not by either addition: first engine ~4.6 s to construct and
  ~13 s to first-evaluate, second and third ~200 ms and ~1–3 s
  (`DataFramePluginCostProbe.kt:30#the first engine costs`);
- the alarming number that started it — a 243-box materialize test apparently going from a 39–43 s pass
  to a 284 s failure — was **machine contention**, two Field JVMs and a Gradle daemon; run alone the
  same test passes in ~103 s (`DataFramePluginCostProbe.kt:23#examples.agent-transcripts.materialize`).

If either cost ever does dominate, the two need different fixes and the probe says which:
make the plugin opt-in per document, versus attaching `dataframe-core` on demand the way `@DependsOn`
already attaches maven artifacts (`DataFramePluginCostProbe.kt:38#Fix: make it opt-in per document`).

---

## 6. The page's captures, and the one that does not reproduce itself

Three assets, all driven through the real runner (`FIELD_DOCS_RUNNER_PORT=9483
tools/build_zensical_docs.py --page dataframe.md`), final run `failures=0 regenerated=3`.

`tools/docs_capture_audit.py --page dataframe.md --repeat 2 --port 9483` — run 1 70.3 s, run 2
53.7 s, both exit 0, and **all three match the committed asset**. Two land inside the measured noise
band. One is classified `unstable`:

    [unstable] public/docs/assets/dataframe-typed-columns.png
        matches the committed asset, but does not reproduce itself
        (0.0333% px changed, maxΔ225, paletteΔ0.0333%)

That is worth naming exactly rather than leaving as a verdict, because the verdict sounds like a
content problem and it is not. Differencing run 1 against the committed asset: **644 changed pixels
of 1,932,000, all of them inside the box x∈[118,131], y∈[38,83]** — a 14×46 region at the very start
of line 1. Cropping and looking at it: it is the editor's **text caret**, drawn in run 1 and not in
run 2 or in the committed asset. Run 2 is byte-identical to what is committed
(`runsVsHead: ["0.0333% px changed", "identical"]`).

So the instability is a blinking cursor, and it is a property of `capture="editor"` rather than of
this page. It trips the audit because the classifier's two thresholds are an *area* limit (1% of
pixels — 0.033% passes easily) and a *delta* limit (maxΔ 19 — a caret against a dark editor is 225),
and a caret is precisely the small-area/high-delta shape that satisfies one and fails the other.
Recorded, not retuned: the thresholds are shared by every page and a docs branch should not move
them to make its own figure green.

## 7. Known gaps, as of 2026-08-22

1. **`schemasPath` / `ImportedSchemasGenerator` is unwired.** Named above. Open path, not defect.
1b. **`ValuePrinterRegistry` / `H2ValueRegistry` still resolve by `Class`, so a printer registered for
   a *generic* type is registered for its erasure.** `register(DataFrame::class.java)` cannot
   distinguish `DataFrame<Person>` from `DataFrame<*>`, which is fine for these four printers and
   would not be for a printer that wanted to specialise on a type argument. Noted, not needed.
   (conjecture, 2026-08-22: nothing has asked for it.)
2. **`DemoTranscriptExecSmokeTest`'s comment on this transcript was stale** and is fixed on this
   branch (`DemoTranscriptExecSmokeTest.kt:98#All FOUR boxes are executed`). `d7d8dccc2` replaced the
   transcript's fourth box (`dataframe_boundary`, deliberately not executed, documenting a compile
   error) with `dataframe_live` (the `typed()` demo, which *is* executed —
   `dataframe_typed_columns.yaml:138#box: dataframe_live`) and updated
   `public/docs/agent_transcripts.md`, but left the classification comment describing the old shape.
   Worth noting where that comment lives: `EXECUTE` / `COMPILE_ONLY` are `internal` rather than
   private precisely so `AgentTranscriptIndexGuard` can read them and hold the public index page to
   the verification level they declare — so a stale *comment* beside a correct *set* is the one part
   of that arrangement nothing can check.
3. **`public/docs/agent_transcripts.md`'s paragraph on this transcript is inaccurate in two places**
   and is deliberately *not* edited here, because a concurrent agent may be registering a transcript in
   that file tonight. It says box 4 "runs twice" and that "the second execution prints
   `ages: [15, 30, 45]`", where the transcript executes box 4 exactly **once** and the two halves are
   two `//%-` *fragments* of one execution
   (`dataframe_typed_columns.yaml:138#box: dataframe_live` is the only `execute` op naming it); and it
   writes `typed("People")` where the box writes `typed("Peach")`. Both are one-sentence edits for
   whoever next owns that file.
4. **`checkCodebaseReference` is red on `d7d8dccc2` itself**, independently of this branch. Measured
   2026-08-22 in a clean worktree: `visualization.md` generates 10 files / 323 symbols against the 9 /
   320 committed — `DataFrameVisualizer.kt` sits under the published source root
   `StdlibReferenceGenerator.kt:115#kotlin/afield/visualization` and is not in `EXCLUDE_PREFIXES`, so the
   commit that added it needed a `./gradlew generateCodebaseReference` and did not get one. `index.md`
   and `dev_docs/kdoc_worklist.md` drift with it. Not regenerated here on purpose: a docs-only branch
   regenerating that artifact collides with every sibling branch.

## 8. One observation for whoever regenerates the codebase reference

`DataFrameVisualizer.kt`'s row in `public/docs/reference/index.md` comes out with a **blank summary**,
because the file's KDoc block is flush against `object DataFrameVisualizer` and therefore documents the
object rather than the file — the rule is in `StdlibReferenceGenerator.kt:622#documents that`. The
object's own prose does appear on `visualization.md`; only the index row is empty. A one-line file
overview above the block would fill it. Deliberately not done here, so that the drift this branch
causes stays the single `Used in:` change named in the worklog rather than a second, larger edit to a
generated artifact.

## Two rendering defects, and what found them both

Neither of these was found by a test. Both were found by looking at what a box actually drew, and both
are the same shape: a value reached the renderer and dispatched to a generic fallback instead of a table.
They are recorded here rather than on `public/docs/dataframe.md`, which is a page about using DataFrames.

**A column rendered as its own `toString()`, for eight months.** `ValuePrinterRegistry` resolved a printer
by exact class, then the superclass chain, then only the interfaces the *runtime class declares directly*.
DataFrame's columns defeat that: `ValueColumnImpl : DataColumnImpl(), ValueColumn` and
`ValueColumn : DataColumn`, so `DataColumn` sat one interface further out than the lookup reached,
`register(DataColumn::class.java)` was dead code, and a column drew as
`ValueColumnImpl age 0 15 1 30 2 45 3 22`. The registry now walks the whole interface closure
(`ValueVisualizer.kt:97`), and the smoke test asserts the part a `toString()` cannot fake — the column's
element type (`DataFrameVisualizerSmokeTest.kt:203`). `GroupBy` was the case that *named* this risk when
the feature landed and it keeps its own regression (`DataFrameVisualizerSmokeTest.kt:152`); the column case
was the same hazard, known and documented, shipping one live instance of itself.

**Every row of a `.h2` frame rendered as the string `Map(size=3)`.** `.h2` adapts a frame into `LazyRows`,
a `List<Map<String, Any?>>`, and the explorer's `ListSource` had three modes — GENERIC, VECTOR,
NUMERIC_STREAM. A list of maps fell to GENERIC, where each element becomes `preview()`, which for a `Map`
is its size. A RECORD mode now sits beside VECTOR, which already did this job for a uniform element shape:
sample the prefix, and if every element is a `Map` with the same key sequence, those keys are the columns
(`h2explorer.kt:180#fun recordCells`). It generalises past DataFrame — any `List<Map<String, Any?>>` is a
common Field shape.

**The cause of the second one was a mis-stated mechanism, which is the transferable part.** Both this
file and the public page said `.h2` streamed through `renderListContinuation`. It does not:
`renderListContinuation` (`ValueVisualizer.kt:2269`) serves `/lazy-value-fragment`, which expands a value
in place, while `.h2` opens an iframe onto `/h2-explorer` fed by the `__h2_explorer__` channel. The smoke
test drove the former believing it was the latter, so it asserted *paging* — window offsets, the last
window reaching the last row — against a renderer the widget never opens, and the *cells* had no oracle at
all. `h2explorer.kt` had no tests of any kind. It now has `H2ExplorerRecordRowsTest`.

**A capture-harness bug hid it from the docs build too.** The `.h2` iframe carries `loading="lazy"`, so in
a headless box capture the frame was never scrolled into view and never fetched — and its untouched
`about:blank` document reports `readyState === "complete"`, so `iframeReady` read it as READY while it was
empty. The frame's own "connecting…" never blocked the shot either, because `about:blank` has no `#status`
element for `blockedStatusText` to inspect. The box-capture prep now promotes a deferred frame to
`loading="eager"`, scrolls it in, and refuses to call an un-navigated frame ready
(`docs/screenshot.kt:500#materialiseDeferredFrame`). The existing readiness logic was correct; it was never
reached.

**A timing scare worth not repeating.** A 243-box test appeared to go from a 39–43 s pass to a 284 s
failure when the plugin landed. The cause was two Field JVMs and a Gradle daemon competing for the
machine; run alone the same test passes in ~103 s. Treat any timing taken on a busy machine as noise
until it is reproduced idle.
