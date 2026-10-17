# Bringing in another language: the MorphoHDL bridge

Moved here on 2026-10-11 from `public/docs/morpho_examples.md`, which is now the user page for
MorphoHDL; the search study went to `public/docs/morpho_search.md`. This is the plumbing that
connects a foreign runtime to a Field box, in the order it has to be built, and the file that holds
each piece. Simulation, layout and drawing are on the user page and are not repeated here, because
none of them is plumbing: they are what this particular language turned out to lack.

## 1. Host the foreign runtime

Field already depends on `org.graalvm.polyglot:js` and `:wasm`, so morpho's compiler needed a host
rather than a port. That is the first decision to make and it sets the size of everything after it.

`MorphoRuntime` (`MorphoGraal.kt:32#object MorphoRuntime {`) owns one GraalJS context and puts every
guest call behind a single lock
(`MorphoGraal.kt:95#internal fun <T> withJs(block: () -> T): T = lock.withLock {`), because a Graal
context is not safe for concurrent access and Field will execute two boxes at once. ES module
imports need real files, so a packaged run unpacks the vendored tree to a temp directory on first
use, while a development checkout reads `src/jvmMain/resources` in place. That second path is what
makes editing a driver script and re-running pick up the change with no rebuild.

Two scripts live on the guest side, and together they are the largest item in the bridge after the
tests.

`morpho-field-driver.mjs` stands in for upstream's `grower.js`, whose constructor builds morpho's
own 2D layout engine. The expansion *schedule* in that file was worth keeping and the layout was
not, so the schedule is reproduced in the driver and the gate counts it arrives at are pinned
against morpho's Python reference by `MorphoBridgeTest`. The same script flattens the compiler's
graph into typed arrays, so a netlist crosses into the JVM in a handful of bulk copies rather than
around 10<sup>5</sup> individual polyglot member reads.

`morpho-field-layout.mjs` drives the Barnes–Hut kernel, and drives it from JavaScript rather than
from Kotlin for a reason that will decide the shape of any similar job. `main.wasm` publishes its
buffers as `_get_<name>()` and `_len_<name>__<type>()` offset-and-length pairs, a convention meant
to be wrapped in typed-array views. With views a sync is one bulk `.set()`. Through the polyglot
buffer API it is roughly 25,000 single-element crossings per frame.

So budget the boundary, not the language. Neither script does anything a reader of MorphoHDL would
recognise as MorphoHDL, and between them they are 446 lines.

## 2. Copy the result into Kotlin

Whatever the foreign runtime produces has to become a Kotlin value before Field can hold it. For a
circuit that is `MorphoNetlist` (`MorphoNetlist.kt:14#class MorphoNetlist(`): packed per-cell
metadata, one array per relation, and morpho's own cell, net and pin numbering carried through
unchanged.

Keeping upstream's indices is the choice that matters here, and it is not the convenient one.
`nCells` counts every cell the compiler ever allocated, including those a later expansion
deactivated, so callers have to ask `isActive` before believing anything; nets 0 and 1 are reserved
for the constants and have no driver. Compacting at the boundary instead would buy tidier Kotlin and
cost the ability to compare notes with the other implementation. With one numbering across the seam,
a Kotlin bug and a JS bug cannot disagree about which cell is cell 40.

## 3. Register it as a main language

This is the plumbing proper. It is the same for every language, and it is about sixty lines.

You need an implementation of `BoxExecution` (`execution.kt:60#interface BoxExecution {`), here
`MorphoExecution` (`MorphoInterface.kt:61#class MorphoExecution(val inside: Box) : BoxExecution {`);
one `registerMainLanguageRuntime` call
(`MainLanguageRegistry.kt:26#fun registerMainLanguageRuntime(runtime: MainLanguageRuntime) {`) from
an `@RunsAt(early)` installer (`MorphoInterface.kt:398#fun installMorpho() {`); a `TopologyWatcher`
(`LanguageRuntimeSupport.kt:18#class TopologyWatcher(`) started from an `@RunsAt(plugins)` one; and a
persistent `Box.isMorphoRoot` property, so the choice survives a reload. The toggle that turns a
root's children into boxes of your language is `applyRuntimeToggle`
(`LanguageRuntimeSupport.kt:43#fun applyRuntimeToggle(`). Call it
(`MorphoInterface.kt:416#fun morphoTopologyNeedsUpdating(values: Collection<Box>) {`) rather than
writing your own root propagation. The whole block is a near-copy of the one in
`afield/forth/ForthInterface.kt`.

A language that fails to register raises nothing. It simply never appears and Field carries on,
which is why `MorphoRegistrationTest` drives the real registry rather than asserting that the
installer was called.

## 4. Say what running the language means

The remaining ~360 lines of `MorphoInterface.kt` are not registration. They answer the questions
registration leaves open, and they are where a bridge stops being generic.

**Which program?** MorphoHDL declares no bus widths, so a source file alone does not say what to
build. Field adds one directive, `# main ripple_adder(8, 8, 1)`, riding in a `#` comment because
morpho's tokenizer discards comments entirely. Field can annotate a program the language never
sees, and the same file still parses in upstream's demo.

**Execute or launch?** Execute grows the circuit to completion and reports it. Launch grows a few
cells per frame and relaxes the layout between expansions, which is the mode a language built on
recursive division deserves. Both write the result to `Box.morphoCircuit`
(`MorphoInterface.kt:393#var Box.morphoCircuit by Delegation<MorphoCircuit?>()`), which is the
handoff a neighbouring Kotlin box reads.

**Where do error lines come from?** Guest exceptions carry the guest's line numbers and the box
needs the user's. Mapping between them is small, unavoidable, and specific to whatever you host.

## 5. Give the editor a grammar

One Monaco module under `src/commonMain/resources`, registered under an AMD name
(`morpho.js:16#define("bithero/morpho"`) and added to `FIELD_LANGUAGE_MODULES`
(`bootstrapMonaco.js:49#"bithero/morpho"`). Your Monaco language id has to *be* the box's
`code_language` string, lower-cased, and an alias will not stand in for it. That is the string the
editor passes to `setModelLanguage`, which resolves mime types and then ids and never aliases, and
which falls back to `plaintext` for an id nobody registered. A mismatch gives you a working box with
no highlighting and no error anywhere.

Reusing a lookalike grammar is the tempting shortcut and it misleads. MorphoHDL is Python-shaped
without being Python: `@morpho` is the only decorator there is, `SPLIT` and `CAT` are primitives
rather than library calls, and Python's grammar renders a truth table like `0b1001_0110` as a plain
number.

Completion is a second list, and morpho spent its first weeks missing from it. `MorphoExecution`
implements `complete`
(`MorphoInterface.kt:311#completionRequest: CompletionRequest, reply: (Message) -> Unit) {`)
by parsing the buffer with morpho's own parser, so it offers whatever cells the user has just
written instead of a fixed list — and none of it ran, because Field's suggest widget gates on
`DEFAULT_LANGUAGES` (`fieldSuggest.js:8#const DEFAULT_LANGUAGES = new Set([`) and morpho was absent.

Both mistakes were live at once. The grammar was registered as
`morpho` while boxes ask for `morphohdl`, so the model was `plaintext` and the id in the completion
list would not have matched anyway. Two lists, two silent failures, and a `complete` implementation
that had never once been called. `SmallLanguageAnatomyTest` now derives every main language's
`code_language` from its registration and fails if the lower-cased form is not a grammar Monaco has
or is not in `DEFAULT_LANGUAGES`, so the next language cannot repeat either half. If you are
following this template, put your language in both lists at this step and open the widget to check
that it answers.

## 6. Hold the bridge to the original

A bridge can be wrong in three separate places, so pin all three: the seam, where your Kotlin
either agrees with the original implementation or does not; the language itself, meaning the
directive, the growth loop and the output; and the registration, which is a different claim from
"implemented".

Pin the seam *two* ways. Integer arithmetic on its own will pass a circuit that is correct and twice
the size it should be, so this bridge also pins gate counts against `tiny_morpho.py`. Keep the
language check free of a browser and a server so it stays a unit test, and keep figures that are
measurements rather than assertions out of the test run: here they are in `MorphoMeasurements`,
under `./gradlew jvmMeasurements`.

## Two invariants that live only in morpho's own code

Step 6 is where you find out that the implementation you are bridging has invariants written down
nowhere but its own code. Two of morpho's are like that, and the optimized Wallace multiplier is the
design that depends on both.

The first is that liveness is per **slot**, not per cell. Morpho's LUT cells are broadcast: one
truth table applied across many independent bit slices, and constant propagation retires slices
*individually*. A cell can be active while one of its slots has been bypassed, its output net left
driverless and its input pins disconnected, so a live cell can hold a LUT input pin whose net index
is `-1`. The invariant a bridge naturally assumes,

> an active cell has connected pins

is false here.

The second is what a disconnected pin *means*. `optimizeLUT` in `primitives.js`, the function that
disconnects it, begins its reindexing with:

```javascript
if (w < 0) w = 0; // Treat disconnected inputs as constant 0
```

A disconnected port reads as constant zero, because constant propagation prunes a port *without*
rewriting the cell's truth table: the arity and the table stay as they were, and everyone downstream
reads the pruned port as 0. Read it as a don't-care instead, which is what it looks like, and the
multiplier still compiles and still simulates: it just answers `29376512` for `62754 * 62244`
instead of `3906059976`. Upstream's convention is the one that makes all six exhaustive multiplier
checks pass.

The general form: a netlist's field names describe the representation, not the *invariants*, and the
invariants live in whatever last mutated the graph. If something in a netlist looks impossible, read
the pass that produced it rather than the schema it produced it into.

A bridge is also a second reader of its host's shared infrastructure, and for this one that shows up
at the single point where morpho gives a box back. Reverting a language root to Kotlin *is*
`setMainLanguageFor(roots, null)`, and `Box.mainLanguage` is a `Delegation<String?>`; assigning null
to one of those **erases** the property rather than storing a null, so erased and never-set read
back identically. Absent is what keeps a reverted root out of the saved document, and
`the Set Main Language Kotlin action reverts a morpho root` drives the shipped
**"Set Main Language: Kotlin"** action through `Action.target`, the way the command palette does,
then asserts the property is absent rather than present-and-null.

## What it cost

Getting a language *registered* in Field is nearly free: registration proper is sixty lines, and
those sixty were a copy. What costs is the other three things — carrying the language's data across
a process boundary, deciding what running the language should *do*, and reading the other
implementation carefully enough not to disagree with it by accident. Steps 1, 2 and 6 are most of
the bridge between them.

`dev_docs/MORPHO_MEASUREMENTS.md` has the file-by-file counts for both halves: the plumbing, and
what morpho left to its host.

## Which harness holds what

The checks are `MorphoBridgeTest`, `MorphoLanguageTest`, `MorphoRegistrationTest` and
`MorphoSearchTest`, 39 tests between them. The numbers that are measurements rather than
assertions come from `MorphoMeasurements` and `MorphoSearchMeasurements`, which are excluded
from `jvmTest` and run under `./gradlew jvmMeasurements`. The search layer's design notes, and
what to try next on the adder, are in `dev_docs/morpho_circuit_search.md`.
