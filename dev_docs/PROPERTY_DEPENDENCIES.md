# Property dependencies: recording box-to-box reads

Status (2026-10-09): recording, persistence and the canvas layer are in, and so is re-execution
through `propUp` / `propDown` / `propUpDown` (the last section).

## What is recorded

Every inherited read in Field goes through one walk, `ContextResolution.search`:

| Box code | Delegate / call | Mechanism |
| --- | --- | --- |
| `prop<T>(speed)`, `rootProp<T>(speed)` | `Inheritance<T>` | `INHERITANCE` |
| `@exported val speed = ...` (read in a child) | `InheritanceFromContext<T>` | `INHERITANCE_FROM_CONTEXT` |
| `fromContext<Renderer>()` | `ContextResolution.walk` | `FROM_CONTEXT` |
| `fromContextWithNameOrNull<T>("x")` | `ContextResolution.walk` | `FROM_CONTEXT_NAMED` |
| FieldML `prop` | `inheritedPropertyRaw` | `INHERITANCE` |

`search` hands each non-explain outcome to `PropertyDependencies.recordResolution`. The
inherited delegates' setters call `PropertyDependencies.recordWrite`. Three kinds of entry:

- **READ**: the reader's code got a value that another box held.
- **MISS**: the walk found nothing (a `MissingInheritedPropertyException`, or a nullable `prop`
  that came back null). Misses from `fromContextWithNameOrNull` are not recorded: that call is an
  optional probe, and `magic.kt` and `Module.kt` use it as one for every buffer and acceleration
  structure.
- **WRITE**: the reader's code wrote an inherited property into a different box. This is the
  `rootProp` case, `root.speed = 4.0`. Without it the only supplier visible would be the root.

The **reader** is `Box.context` (the executing box), falling back to the box the walk started
from; the two differ for `named("other").speed`. Reads of one's own value and writes into one's
own box are not dependencies and are not recorded.

Not recorded:

- `explain` walks, `why.<name>`, the Monaco hover. `ContextResolution.peek` is the non-recording
  walk, for tools.
- Plain `Delegation` reads (`box.someProperty` of a non-inherited property on another box) and
  raw `getPropertyRaw` calls. These do not go through the walk. Recording `Delegation` would put
  a thread-local read on every property access in Field, and the walk itself reads `parents`
  through it, so it would need an exclusion list to stay truthful. Revisit it if a real document
  needs it.

One `Edge` is kept per (reader, supplier, name, mechanism, access), holding a count, a
session-only count and first/last-seen times. It is not an event log. A box that draws every
frame reads `renderer` sixty times a second, and what the record has to answer is who depends on
whom.

## Derived views

`PropertyDependencies.flows(store, rootId)` merges the edges into producer → consumer pairs:

- a READ from box H is a flow H → reader, unless H is the document root or the metaroot;
- a READ of `name` from H, when some other box W WROTE `name` into H, is also a flow W → reader.

`upstreamOf(box)` gives the producers of one box. `rememberedSuppliers(store, reader, name)` gives
the boxes that supplied `name` to `reader` on any earlier read. That second one is the question
upstream execution asks.

A MISS that a later READ of the same (reader, name) answered is left out of the graph.

## Forgetting: a completed run replaces the reader's dependencies

When a box finishes a successful full run (`run` or `launch`, not a selected fragment), every
access it made before that run began and did not repeat during it becomes **historical**
(`PropertyDependencies.retireReadsBefore`, called from `ReactiveRuns.runFinished`). An edit that
removes a read therefore takes effect at the next run: changing the value no longer reruns the box.

- Historical edges stay in the record and on the layer (grey, dashed), to explain what happened.
  They do not rerun the reader, they do not order a cascade, a historical MISS is not drawn as a
  hole, and they come last among a `propUp` pull's remembered suppliers.
- Any later access revives an edge. A read made by a `frames {}` loop or a callback after the run
  ends is therefore live, even though the run itself did not make it. The gap is accepted: if the
  value changes between the rerun and the loop's next read, the box is not rerun, and the loop picks
  the new value up itself.
- A failed, stopped or cancelled run forgets nothing (its reads are incomplete), and neither does
  stopping a box or running a fragment.
- Scenarios: `down_dropped_read`, `down_read_in_frames_kept`, `dropped_read_after_restart`; unit
  test `PropertyDependenciesTest.aCompletedFullRunRetiresTheReadsItDidNotRepeat`.

## Persistence

`<document>/dependencies.edn`, version 1: `{:version 1 :edges [{"reader" .. "supplier" ..
"name" .. "mechanism" .. "access" .. "count" .. "firstSeen" .. "lastSeen" ..}]}`, plus
`"historical" true` on a retired edge (absent means live, so older files load unchanged). It is loaded on
first use (the first read recorded, or the layer's first request). It is written from the autosave
loop in `Server.server()`:

- a new edge is written at the next tick (≤ 5 s);
- if only counts or times moved, it is written at most every 60 s;
- everything is written on shutdown (`DocumentShutdownFlush`).

Writes go to the misc pool through a temp file and a rename. Scratch and read-only documents
record in memory and never write. Before each write, edges are pruned if their reader has left
the document or their supplier was tombstoned.

The file is not part of history (`backups/`, `versions.edn`). Duplicating a document copies it,
which is correct because box uids survive duplication.

Off switch: `-Dafield.dependencyTelemetry=false` or `AFIELD_DEPENDENCY_TELEMETRY=off`.

## The layer

`src/jsMain/kotlin/afield/app/canvas/dependencyLayer.kt`. Palette: **Toggle Dependency Layer** (remembered
in `localStorage`) and **Clear Dependency Record**. While it is on, the page polls
`dependency-graph` every 750 ms and redraws inside `rebuildConnectionGroup`, so the layer follows
pan, zoom and drags. Encoding:

- amber: read in the last 2 s;
- blue: read this session;
- grey and dashed: remembered from disk only, or historical (the reader no longer makes the read);
- red `? name` beside a box: an unanswered read, with dashed red arrows from its remembered
  suppliers.

`window.dependencyLayerState()` reports what was received and drawn.

## Re-execution: `propUp`, `propDown`, `propUpDown`

`afield/stdlib/reactiveProps.kt` (the macros and the `ReactiveInheritance` delegate) and
`afield/ReactiveRuns.kt` (the scheduling). Each macro declares the same inherited slot `prop` does.
The preamble line is `var Box.x by ReactiveInheritance<T>(Reactive.UP)` and children gather it.
On top of that slot:

- **Up**: a read that finds no value runs the box that supplies it, then reads again. The reader's
  declaration decides this.
- **Down**: a write that changes the value (by `equals`) reruns every box that read the old
  value, after the writing run has finished. The writer's declaration decides this.

### Up is a nested pump

Box code evaluates on the runloop thread, and the delegate getter is not a suspend function. So
a read cannot suspend while a supplier runs, and blocking it would deadlock: `Framer.dispatch`
only queues, and the reader's own launch is the launch queue's current entry. Instead,
`ReactiveRuns.pull` does two things:

1. It launches the supplier **straight onto the runloop**. `launchAll` skips `enqueueLaunch` when
   `ReactiveRuns.launchImmediately` is set.
2. It **pumps the runloop from inside the getter** (`Runloop.runLoop.main.frame()`) until the
   supplier's `executeInBox` has finished.

Ordering is by construction: a supplier runs exactly when, and only if, its value is read.
C → B → A unwinds as nested pumps.

Suppliers are tried nearest first:
1. the box being asked, if it isn't the reader (`named("b").q`);
2. the box the gathered preamble's `/*from:uid*/` tag names;
3. boxes that supplied the name on earlier reads (`PropertyDependencies`);
4. ancestors whose preamble or code declares the name.

Guards:

- A box blocked in a pull is never pulled. That case is a cycle, and pulling it would re-enter its
  engine mid-evaluation (the self-box re-entry failure). The read fails and the message names the
  cycle.
- A run of a blocked box that the pump reaches waits until the pull returns
  (`ReactiveRuns.awaitNotBlocked`). It is called at the top of `executeInBox`, and in
  `executeAllRequest` before the engine is replaced.
- A second pull of a supplier already being pulled joins the first, so the supplier doesn't run
  twice. This happens when another box's animation frame, running inside the first pull's pump,
  asks for the same value.
- A supplier is pulled at most once per name per run of the reader. Outside a run, it is not
  pulled again within 2 s.
- The thread's `Box.context`, `thisSession` and `thisSessionInfo` are restored after every pumped
  step.
- A queued launch with no request id (autorun, `begin()`) is **skipped** if a pull ran that box
  successfully after the launch was queued. At startup, B's autorun can reach the queue first and
  pull A. Without the skip, A's own autorun would then set A up a second time (a renderer, its
  window).

### Down is a cascade after the write

A change made during a run is held until that run finishes; a failed run's changes are dropped.
A change made outside any run (a `frames {}` loop, a slider callback) goes at once. Bursts are
coalesced for 15 ms. `ReactiveRuns.Downstream` then drains, off the runloop, through the ordinary
launch queue, one rerun at a time, each awaited:

- **Who waits.** A reader is waiting while its last read of the value, and its last run, began
  before the change. This uses the shared `PropertyDependencies.clock`, not milliseconds.
  - A box that has read the value since (the puller of a `propUpDown` value) is up to date and
    doesn't rerun.
  - Only boxes whose read is live (not historical) are rerun, which includes reads remembered in
    `dependencies.edn`. A box that never read it is not.
- **Order.** The next box is the first waiting one that no other waiting box can reach through
  the recorded flows, followed transitively (`ReactiveRuns.orderNext`). A diamond's bottom runs
  once, after both sides; D that reads x and B's y runs after B.
- **Cycles.** A box reruns at most 3 times per cascade; at that point the cascade stops and logs
  on `warning,autorun`. A reader whose rerun fails is not retried in that cascade.

### What counts as a declaration

Two decisions read a box's code as text rather than through the macro: whether an ancestor
supplies a name (`suppliersFor`), and whether a box still declares a name Down
(`ReactiveRuns.isDeclaredDown`, which forgets a declaration once the code stops making it).
Both match `propUp|propDown|propUpDown<T>(name` followed by `,` or `)` — a default value
counts — as a whole word, after `ReactiveRuns.withoutComments` has blanked `//` and nesting
`/* */` comments (a `//` inside a string literal is not a comment). So commenting a declaration
out retires it, as it retires the macro expansion. A declaration always carries its type: the
macro refuses `propDown(name)`, so no record is ever made for that spelling.

Every per-box record in `ReactiveRuns` (Down declarations, the last run start, pull repeat
windows, a successful pull's stamp) is keyed by document file *and* uid (`ReactiveRuns.docKey`).
Two open documents can hold the same uid — a copied document keeps its uids — and keyed by uid
alone, asking one document's box forgot the other's declaration, and a run in one made the
other's reader look up to date.

### Testing

- `tools/reactive_props_scenarios.py` drives a live Field through the backdoor: one fresh
  document per scenario, then a Kotlin check box that asserts on `ReactiveRuns.recentEvents()`
  (every start, end, pull, join, skip, cascade and cap, in order) and on the boxes' `result`
  properties. It runs `startup` and `down_after_restart` across a real restart.
- `ReactivePropsTest` (jvmTest) covers ordering, supplier discovery, change noting, and a pull
  with no runloop.

### Known limits

- The pump drains `main.frame()` only, not `Runloop.preRun` / `postRun`. During a pull, `frames {}`
  loops advance (`up_pump_keeps_others_alive`). A native window keeps rendering too, because a
  renderer launched with `start(renderer::run)` is a coroutine that `main.frame()` services. Measured
  2026-10-12: `renderer.frameCounter` advanced 39 frames during a 321 ms pull of a freshly compiled
  supplier. `Runloop.frame` does not advance inside the pump.
- A supplier that blocks the thread (`Thread.sleep`, a busy loop) blocks the pump, and the window
  freezes for as long as it blocks. Running that supplier directly freezes the window for just as
  long. The pull's timeout (`-Dafield.reactiveProps.pullTimeoutMs`, default 120 s) only catches a
  supplier that never starts or finishes asynchronously.
- Stop during a pull of a blocking supplier (measured 2026-10-12, an 8 s supplier, Stop sent at
  2.5 s through `stop-box`). Stopping the reader does not stop the supplier: both run to the end,
  and the reader is then reported cancelled. Stopping the supplier lets it run to the end and
  reports it failed ("pulled … failed"), yet the reader still reads the value the supplier wrote
  and ends ok. Neither wait can be interrupted, as with any box that blocks the runloop.
- Only Kotlin boxes can be pulled.
- An explicit launch (with a request id) queued behind a pull of the same box still runs: a chord
  that includes both the reader and its supplier can set the supplier up twice.
- Down writes from a `frames {}` loop cascade on every change; that is the contract, but a value
  changed every frame keeps its readers rerunning.
- `rootProp` has no reactive variant yet. Telemetry already finds the writer of a root value, so
  `rootPropUp` would mostly be a macro.
- Switches: `-Dafield.reactiveProps=false` / `AFIELD_REACTIVE_PROPS=off`.

Tests: `PropertyDependenciesTest`, `ReactivePropsTest` (jvmTest), and
`tools/reactive_props_scenarios.py` (live).
