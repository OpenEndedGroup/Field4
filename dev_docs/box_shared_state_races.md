# The box property bag is shared by every thread — and how the `resyncAll` CME worked

A `java.util.ConcurrentModificationException` was reported out of transcript replay three times
across 2026-07-26 → 2026-07-29, by three different agents, in files none of them had touched. It
was never diagnosed; commit `4eae0270` improved its *reporting* and explicitly declined to fix it.
This is the diagnosis, and the reason the fix is where it is.

## 1. The report, and why it was unreadable

Two of the three sightings quoted the same stack to the character:

```
java.util.ConcurrentModificationException
  at Box.resyncAll:1241 <- OperationTranscript.applyMacroOperation:1343 <- runMacro:453
```

The reason nobody got further is that **`Box.resyncAll:1241` is not a source line.**
`src/jvmMain/kotlin/afield/Boxes.kt` is around 1240 lines long, and `resyncAll` is near line 610.

Kotlin gives inlined lambda bodies from *other* files synthetic line numbers appended past the end
of the caller's file, and records the real mapping in a JSR-45 `SourceDebugExtension` (SMAP)
attribute. Raw `StackTraceElement` rendering — which is what `describeOrigin`
(`src/jvmMain/kotlin/afield/operation_transcript.kt`) prints, and what any `printStackTrace` prints —
shows the synthetic number. **Any frame in this codebase whose line number exceeds its file's length
is an inlined stdlib call, and the SMAP will tell you which one.**

Decoding it, with `javap` against the compiled `afield/Box.class`:

```
$ javap -p -c -l build/classes/kotlin/jvm/main/afield/Box.class
  public final void resyncAll();
    LineNumberTable:
      line 608: 0
      line 1240: 7
      line 1241: 24        <-- the reported line
      line 1241: 35
      ...
    LocalVariableTable:
      24  177  5  $i$f$filterTo          I
      21  180  3  $this$filterTo$iv$iv   Ljava/util/Map;
```

and the SMAP itself:

```
$ javap -p -v build/classes/kotlin/jvm/main/afield/Box.class   # SourceDebugExtension
  *F
  + 4 Maps.kt
  kotlin/collections/MapsKt__MapsKt
  *L
  669#4,2:1241            <-- output line 1241 == Maps.kt line 669
  *S KotlinDebug
  608#1:1241,2            <-- inlined at Boxes.kt:608
```

So the reported frame is `kotlin.collections.MapsKt__MapsKt:669` — the `for (element in this)` of
the inlined `Map.filterTo` — inlined at the first statement of `resyncAll`:

```kotlin
properties.filter { (name, _) -> uiResyncProperties.any { it.matches(name) } }
```

The exception came out of a `LinkedHashMap` entry-set iterator, thrown because another thread
structurally modified the same box's property bag mid-walk.

## 2. Why the bag was being modified mid-walk

`Box.properties` was a plain `mutableMapOf<String, Any?>()`. It is touched by essentially every
thread in the process:

| thread | what it does to a box's bag | where |
| --- | --- | --- |
| macro / transcript replay (`Dispatchers.IO`) | `setPropertyRaw` per operation, then `resyncAll()` | `operation_transcript.kt`, the `create` verb |
| the main runloop's 500 ms loop | `doResync()` + `syncExecutionStatus()`, and every ~5 s `refreshFileProxyBoxes` and an autosave that *iterates* every bag | `Server.kt` (`inMain { while (true) { … delay(500) … } }`), `saving.kt` |
| the file-proxy refresh | writes `file_proxy_error`, `contents`, `contents_language`, `default_property` | `server/fileproxy.kt` |
| the AI auto-namer (`Runloop.miscPool` → `inMain`) | writes `ai4_auto_name`, `ai4_auto_name_signature` — **new keys on a box a transcript has just created** | `ai/core/Ai4AutoBoxNamer.kt` |
| box execution | output/error/state properties | `execution.kt` |

And then the part that makes this much wider than "somebody forgot to lock a write":

> **Readers are writers.** `Delegation.getValue` (`src/jvmMain/kotlin/afield/stdlib/stdlib.kt`)
> *writes the default value back into the bag* on the first read of an unset property. So merely
> reading `box.actions` — declared `Delegation(defaultValue = { HashMap() })` in
> `stdlib/extensions.kt` — inserts a key, from whatever thread happened to read it, and bumps the
> backing map's `modCount`.

That is why the failure looked random and why it favoured the `create` verb: a *freshly created* box
has an almost-empty bag, so nearly every property another thread touches on it is a first touch, and
therefore an insert. The window in the `create` verb is only a few statements wide — it connects the
box (queuing four `needsResync` entries and bumping `topologyGeneration`) and then walks that same
box's bag two lines later.

It is also why the reproducer needed no exotic setup, and why the same family surfaced in three
different suites: `examples.demo-transcript.exec`, `examples.agent-transcripts.materialize` and the
older `rewrite_strategies_demo` all replay transcripts against a live server with the 500 ms loop and
the auto-namer running.

## 3. The fix: `PropertyBag`, not a `try`/`catch`

`resyncAll` is one of about thirty walks of a box's bag — `saving.kt` (twice), `history.kt`'s undo
diffing, `workspace/transcript.kt`, `visualization/ValueVisualizer.kt`, `server/spreadsheet.kt`,
`execution.kt`, `Ai4AutoBoxNamer.kt`, `resyncAllExcept`. Snapshotting inside `resyncAll` would have
fixed one of them and left `LinkedHashMap.get` racing a concurrent resize besides.

So `src/jvmMain/kotlin/afield/PropertyBag.kt` provides the guarantee once:

* every single-key operation is serialised on one monitor;
* `keys`, `values` and `entries` return **snapshots**, so every `filter` / `forEach` / `map` /
  `toList` over a bag iterates a private copy and cannot be invalidated by anything.

Two obvious alternatives do not work, and the reasons are worth keeping:

* **`ConcurrentHashMap`** would silently reorder the properties of every saved document, because
  `SavingContext.toTransfer` builds each persisted box's `LinkedHashMap` in bag-iteration order. It
  also forbids the null values the bag holds.
* **`Collections.synchronizedMap`** documents its iterators as *unprotected*, so the CME survives it
  untouched. (Its per-key methods do help, which is exactly the trap: it looks like a fix.)

The views **iterate a snapshot but write through to the live map**, and the second half of that was a
correction. The first cut returned bare copies and shipped with the consequence written down as an
accepted risk: a `keys.remove(name)` or `entries.iterator().remove()` that used to work would update
the copy and be *silently lost*. That was worse than what it replaced. Nothing in the tree does either
today — which is exactly why nothing would ever have caught it, and it is the same shape of defect as a
guard that compares an expression to itself. So `remove` / `removeAll` / `retainAll` / `removeIf` /
`iterator().remove()` / `MutableEntry.setValue` all reach the backing map now. `removeIf` in particular
is `java.util.Collection`'s default — `iterator()` plus `remove()` — and `agent_backdoor.kt` uses exactly
that shape on a topology set, so a snapshot iterator that refused removal would have broken it outright.
The only refusals left are the two `java.util.Map`'s own views refuse (`keys.add`, `entries.add`) and
mutation through `values`, and all three throw naming the alternative.

**What it costs**, measured on a 66-entry bag (GraalVM 25, arm64, third round after warm-up, with a
throwaway benchmark that was not committed):

| | plain `LinkedHashMap` | `PropertyBag` | |
| --- | --- | --- | --- |
| single-key get | 7.56 ns | 12.24 ns | +4.7 ns — one uncontended monitor |
| `Box.getPropertyRaw` (the real read path) | — | 14.11 ns | |
| whole-bag walk (`resyncAll()` vs the same `filter`) | 64.8 µs | 65.7 µs | **+1.3 %** |

The snapshot copy — the thing that looks expensive — is 1.3 % of a bag walk, because matching ~70
resync regexes per property dominates it by three orders of magnitude. And end-to-end,
`examples.demo-transcript.exec` ran 84.7 s / 84.2 s / 83.9 s with the change against 92.7 s / 85.5 s /
79.6 s / 82.0 s recorded at base the night before: inside the noise.

## 4. The same defect, one field along

`resyncAll` was one instance. The pattern behind it is:

> **mutable state reachable from the box graph, *iterated* on one thread and *structurally mutated* on
> another — where the mutation is often hidden inside an apparently read-only call.**

The threads that matter: the main runloop (`Server.kt`'s 500 ms `inMain` loop → `doResync()`,
`syncExecutionStatus()`, `refreshFileProxyBoxes`, autosave), macro/transcript replay on
`Dispatchers.IO`, the AI auto-namer on `Runloop.miscPool`, box-execution coroutines, and Ktor's
websocket frame threads. One thing worth knowing before reading the table: **`doResync()` is called
from both the runloop *and* `runMacro` on `Dispatchers.IO`** (`operation_transcript.kt`), so "walked by
doResync" already means "walked from two threads" without any other actor being involved.

Rather than keep finding these one at a time, here is the enumeration, with a verdict for every field.
Everything marked **fixed** has a red-at-base test; everything marked **safe** says why.

### Reachable from `Box`

| field | walked by | mutated by | verdict |
| --- | --- | --- | --- |
| `properties` | `resyncAll`, `resyncAllExcept`, saving, history diffing, workspace transcript, `ValueVisualizer`, spreadsheet, auto-namer, `Mesh`, `descriptions` (~30 sites) | everything, including *readers* via `Delegation`'s default write-back | **fixed** — `PropertyBag` (§3) |
| `children`, `parents`, `nested` | `doResync`'s `set-topology` branches; `visitParents` → `gather` → `doResync`'s `"actions"` branch, preamble/shader/actor lookups; saving's `is Set<*>` normalisers | `connect`/`disconnect` from replay; `nestInto`; `history.kt`'s topology restore; `agent_backdoor`'s tombstone `removeIf` | **fixed** — `CopyOnWriteArraySet`, normalised in `setPropertyRaw` |
| `currentRunningStates` | `syncExecutionStatus` every 500 ms, `clearRunningStates` | `Phased`, `ReturnValueRegistry`, `execution.kt`'s launch coroutines, `KotlinJs`, `FieldMLInterface`, the backdoor | **fixed** — `ConcurrentHashMap` |
| `runGeneration` | `.keys.toList()` in `NanoInterface`, `ForthInterface`, `FieldMLInterface` | the same execution threads | **fixed** — `ConcurrentHashMap` |
| `jobs` (`BoxJobList`) | `jobs.any { it.isActive }` from `syncJobRunningState` (runloop, every 500 ms), `Server.kt` ×2, `agent_backdoor` ×2; `jobs.toList()` on stop | `removeCompleted` from `job.invokeOnCompletion`, i.e. **whatever dispatcher finished the job** | **fixed, twice** — see below. The most instructive entry in the table, because the class was clearly *written* to be thread-safe. |
| `actions` | `gather("actions")` merges every ancestor's copy with `putAll` — from `doResync` on the runloop | box code (`root.actions["reset"] = …` in `execution.kt`, the slideshow and alignment installers) | **fixed** — `ConcurrentHashMap`; was already a `HashMap`, so no order was lost |
| `shaderTemplate` | `gather("shaderTemplate")` (`four/util/templating.kt`), same `putAll` walk | the shader update path | **fixed** — `ConcurrentHashMap`. Its own KDoc already warned that it "reads it from a background thread, so mutating it during a live update is not safe" — a known hazard left standing, and the `gather` walk made it a CME and not just a torn read. |
| `onFrameChange`, `onViewportChange`, `onNameChange`, `onTopologyChange` | `for (callback in box.onXChange.values)`, run from whichever thread sets `frame`/`viewport`/`name` or calls `connect`/`disconnect` | box code registering callbacks | **fixed** — `ConcurrentHashMap`; callback order was already unspecified |
| `markers` | **never iterated** (the `ps.markers` hits are `ProfilerState`, a homonym) | `Box.__m`'s `computeIfAbsent`, from box code | **fixed anyway** — `ConcurrentHashMap`. Not a CME risk; but concurrent `computeIfAbsent` on a `HashMap` can lose an entry, and on CHM it is atomic, which is what the caller wanted. |
| `cachedLaunchReturns` | never iterated | execution threads | **fixed anyway** — `ConcurrentHashMap`, for the get-during-resize hazard and so it is not the odd one out |
| `layerFrames` | `box.layerFrames.keys` (`Server.kt`), and `doResync`'s map-serialisation branch | the layers websocket handler | **racy in principle, not fixed** — both sides are in the layers handler / runloop, and its iteration order is *persisted*, so a `ConcurrentHashMap` would rewrite saved documents. Needs the order question answered first. |
| `decorationState` | its own `onGet` does `inMain { setDecorations(box, v.values.toList()) }` — i.e. iterated on the runloop | box code | **racy, not fixed** — same reason: decoration order may be visually load-bearing and I could not verify that cheaply. Best next candidate. |
| `customMarking`, `code_decorationCallbackTable`, `stackAutoShaderSources` | no iteration site found (keyed `put`/`get`/`remove`/`clear` only); `stackAutoShaderSources` is already concurrent by design | box code | **safe today**, on "never iterated". Re-check if an iteration is ever added. |
| `ki.context.vars`, `ki.context.functions` | `descriptions.kt`, `execution.kt`'s `collectDefinedSymbols`, `KotlinInterface2` ×4, spreadsheet ×2, `ai_macro`, `Ai4Installer` | the compiler, during REPL compilation | **diagnosed, not fixed** — plain `HashMap`s, genuinely walked from the auto-namer/AI paths while compilation writes them. Deliberately out of scope: this is the REPL/completion subsystem, its blast radius is the K2 engine, and it deserves its own pass. |

### Reachable from `SessionInformation` / file scope

| field | walked by | mutated by | verdict |
| --- | --- | --- | --- |
| `sessions` (per document) | `doResync`'s `enqueue`, `syncExecutionStatus`, `setFrameAnimated`, `history.kt`, `_sendToPeerSessions`, decorations, `stdlib` — ~12 fan-out sites, mostly on the runloop | the websocket frame loop's dead-socket sweep (`sessions.entries.removeIf { … detachDocumentSession(…) }`) on a **Ktor IO thread**, and the backdoor's attach/detach | **fixed** — `ConcurrentHashMap.newKeySet()`. Fan-out order across clients is not meaningful. |
| `visibleLayers` | `contains` only, in `updateBoxVisibility` | `clear()` then `addAll()` in the layers handler | **racy, not fixed** — no iteration, so no CME; but the clear/addAll pair is not atomic, so a concurrent `contains` can see an empty set and hide a box for one frame. Cosmetic, and a real bug; separate fix. |
| `boxes`, `pendingSidecarSaves`, `pendingSidecarSaveVersions`, `propertySyncSequences`, `pendingPropertySyncOrigins` | many | many | **safe** — already `ConcurrentHashMap` / `newKeySet` |
| `documents`, `sessions` (global), `welcomeEventSessions`, `selectionContextSessions`, `sessionOutputPressure` | the 500 ms loop, `syncExecutionStatus`, the auto-namer, `_send` | connect/disconnect | **safe** — already `ConcurrentHashMap` at both levels |
| `needsResync` | `doResync`'s `removeAll { … }`, the backdoor's startup sweeps | everything | **safe** — ktor `ConcurrentSet`; note its `removeAll { }` predicate body is where the *other* races were observed from |
| `actionTable` | **no iteration site** | keyed writes from installers | **safe today**, on "never iterated" |
| `Box.ioPersistentProperties`, `uiResyncProperties`, `ioSeparateProperties` | `.map { it.toRegex() }` / `.any { … }` on every save and every `setPropertyRaw` | only at startup, by installers | **safe** — effectively immutable after boot; a `var` holding an immutable value, reassigned wholesale rather than mutated |

Three things this table is not: it is not a claim that the unfixed rows are harmless (two of them are
real bugs I chose not to fix blind); it is not exhaustive outside `afield.Box` /
`afield.SessionInformation` (`FLine.properties`, `Mesh`, the renderer's own state were not audited);
and "safe today, on never-iterated" is a verdict with a shelf life — adding one `forEach` invalidates it.

### `BoxJobList`: what "looks thread-safe" costs

Worth its own section, because it is the entry a reviewer would have waved through. Every method took
`lock` and bumped `modCount`. Two independent bugs survived that:

1. **The iterator was never covered.** `AbstractMutableList`'s iterator checks `modCount` *outside* the
   lock, so `jobs.any { it.isActive && !it.isCancelled }` — the runloop, every 500 ms, for every box —
   raced any job completing on any dispatcher. Fixed with a snapshot iterator whose `remove()` routes
   back through the locked path. This is the `Collections.synchronizedList` trap: per-method locking
   buys you nothing for iteration, and it *looks* like it does.
2. **`add(element)` was not atomic.** `AbstractList.add(E)` is `add(size(), element)` — it reads the size
   outside the lock, then takes it. A job completing in between shrinks the list and the index is stale,
   so the ordinary `box.jobs.add(job)` that every launch performs throws
   `IndexOutOfBoundsException`. The stress test hit `Index: 1501, Size: 1500` in under a second. Nobody
   was looking for this one; the reproducer for (1) found it.

The lesson to carry: when you see a hand-rolled synchronized collection, check the *inherited* members.
The bugs are in what the author did not write.

## 5. The tests

`src/jvmTest/kotlin/afield/BoxPropertyBagConcurrencyTest.kt` (5),
`PropertyBagViewTest.kt` (13) and `BoxTopologyConcurrencyTest.kt` (4). They drive production code only —
a real `Box`, a real `SessionInformation`, real `setPropertyRaw` / `resyncAll` / `doResync` / `gather` /
`connect` / `disconnect` / `currentRunningStates` / `actions`.

At `4f55fc4e` (before the fix, with the tests present) the multithreaded case fails within
milliseconds and names the exact frame from the nightly reports:

```
concurrent resync + property writes raised java.util.ConcurrentModificationException
after 0 walks / 29 writes, at:
    java.base/java.util.LinkedHashMap$LinkedHashIterator.nextNode(LinkedHashMap.java:1024)
    java.base/java.util.LinkedHashMap$LinkedEntryIterator.next(LinkedHashMap.java:1059)
    java.base/java.util.LinkedHashMap$LinkedEntryIterator.next(LinkedHashMap.java:1056)
    afield.Box.resyncAll(Boxes.kt:1241)
```

`iterationSeesASnapshotNotALiveView` is deterministic rather than probabilistic, so the suite does
not depend on winning a race to go red. The topology cases throw on their **first** iteration
(`resyncs=0`, `gathers=0` in the failure message), which is as close to deterministic as a race test
gets. The remaining cases pin the constraints that ruled out the alternatives — insertion order, null
values, and the write-through view contract.

### Two ways these tests nearly lied, both worth knowing

**A race test whose churn loop stops churning.** The first `doResync` reproducer alternated
`connect`/`disconnect` across *different* indices, so after ~300 iterations every `connect` hit a box
that was already connected and every `disconnect` a box that was not — both of which return early
without touching the set (`if (!accessor().contains(to))`). It reported **18,093,377 "edges" and
passed**: eight figures of loop iterations containing essentially zero structural modifications. The
fix was to the test: each churn thread now owns a disjoint range and alternates on the same box.

**A window too short to see.** The same reproducer with 24 children also passed at base, because the
vulnerable window is the *length of the walk* and 24 elements is nanoseconds. The property-bag
reproducer had worked immediately only because `resyncAll` matches ~70 regexes per property, making its
walk 65 µs. Sets of 1,200 make the topology walks reproduce on the first try. If you write one of these,
size the collection, then check the red — do not infer it.

## 6. If you are reading this because a CME came back

1. **Decode the line number before anything else.** If it exceeds the file's length, it is an inlined
   stdlib frame; `javap -p -c -l` plus the `SourceDebugExtension` gets you the real one, as in §1.
2. Ask what is being *iterated*, and then which thread mutates it. The mutation is very often hidden:
   a `Delegation` default write-back from an apparent *reader*, a `putAll` inside `gather`, a coroutine
   completion handler, or Ktor's dead-socket sweep.
3. Check §4's table — the field may already have a verdict, including "racy, not fixed".
4. Prefer making the container safe over guarding the call site: the call sites outnumber the
   containers by about thirty to one.
5. Remember that `doResync()` runs on both the runloop and `Dispatchers.IO`, so "only the runloop
   touches this" is usually wrong.
