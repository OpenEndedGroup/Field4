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

The one behaviour change is that removing through `keys.iterator()`, or `setValue` on an entry from
`entries`, now updates the copy and is lost. Nothing in the tree does either — every removal goes
through `properties.remove(name)` — and a snapshot that goes stale is strictly less dangerous than
an iterator that throws.

## 4. The same defect, one field along

Auditing outwards from `resyncAll` found two more unsynchronised maps on `Box` in the same position —
written from every execution thread, iterated on the main runloop:

* `currentRunningStates`, walked by `syncExecutionStatus` every 500 ms and written by `Phased`,
  `ReturnValueRegistry`, the launch coroutines in `execution.kt`, `KotlinJs`, `FieldMLInterface` and
  the agent backdoor;
* `runGeneration`, walked as `runGeneration.keys.toList()` by `NanoInterface`, `ForthInterface` and
  `FieldMLInterface`.

Neither holds nulls and neither has an order anyone reads, so both (and `cachedLaunchReturns`, which
is never iterated but shares the get-during-resize hazard) are now `ConcurrentHashMap`. These were
**not** sighted in the wild; they are the same shape, found by the same audit, and each has a
red-at-base stress test.

`Box.markers` and `Box.properties`-adjacent maps on `FLine` are a different story: neither is walked
from two threads today. They are the obvious next thing to check if a CME reappears.

## 5. The tests

`src/jvmTest/kotlin/afield/BoxPropertyBagConcurrencyTest.kt`. They drive production code only — a
real `Box`, real `setPropertyRaw`, real `resyncAll`/`resyncAllExcept`, real `currentRunningStates`.

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
not depend on winning a race to go red. The remaining cases pin the two constraints that ruled out
`ConcurrentHashMap` for the bag (insertion order, null values), and every stress case asserts its
own iteration counters so it cannot pass by not running.

## 6. If you are reading this because a CME came back

1. **Decode the line number before anything else.** If it exceeds the file's length, it is an inlined
   stdlib frame; `javap -p -c -l` plus the `SourceDebugExtension` gets you the real one, as in §1.
2. Ask what is being *iterated*, and then which thread inserts into it. The insert is very often a
   `Delegation` default write-back from a reader, not an obvious write.
3. Prefer making the container safe over guarding the call site: the call sites outnumber the
   containers by about thirty to one.
