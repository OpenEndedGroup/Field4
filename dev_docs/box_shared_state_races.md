# Box shared-state concurrency contract

Field boxes are accessed by the main runloop, macro/transcript operations, execution coroutines,
websocket handlers, file refresh, autosave, and AI helpers. Shared mutable state reachable from a box
must therefore provide its concurrency guarantee at the owning container, not at one call site.

## Property bags

`Box.properties` is a `PropertyBag`, not a plain `MutableMap`.

- Single-key operations are serialized.
- `keys`, `values`, and `entries` iterate snapshots, so a concurrent structural write cannot
  invalidate an active walk.
- Mutating operations on supported views write through to the live bag.
- Insertion order and null values are preserved because document serialization depends on them.

Readers can still become writers: `Delegation.getValue` writes a default value into the bag when an
unset delegated property is first read. Treat any first access through `Delegation` as a possible
structural mutation.

Do not replace `PropertyBag` with `ConcurrentHashMap`: it rejects null values and does not preserve
the persisted property order. Do not use `Collections.synchronizedMap`: callers can still obtain and
iterate an unprotected live view.

## Box-owned collections

| field | current concurrency contract |
| --- | --- |
| `properties` | `PropertyBag`; snapshot iteration, ordered, nullable, write-through supported views |
| `children`, `parents`, `nested` | `CopyOnWriteArraySet`; normalized in `setPropertyRaw` |
| `currentRunningStates`, `runGeneration`, `cachedLaunchReturns`, `markers` | `ConcurrentHashMap` |
| `jobs` | `BoxJobList`; locked mutations and snapshot iteration |
| `actions`, `shaderTemplate` | `ConcurrentHashMap`; iteration order is not a contract |
| frame/name/topology callback maps | `ConcurrentHashMap`; callback order is unspecified |
| `stackAutoShaderSources` | concurrent by design |

`BoxJobList` must keep both parts of its contract: its iterator is a snapshot whose `remove()` routes
through the locked live list, and `add(element)` is atomic rather than the inherited
`add(size(), element)` sequence.

## Session and file-scope collections

| field | current concurrency contract |
| --- | --- |
| per-document `sessions` | `ConcurrentHashMap.newKeySet()` |
| global documents/session registries | concurrent maps and sets |
| pending sidecar saves and property-sync maps | `ConcurrentHashMap` / concurrent key sets |
| `needsResync` | Ktor concurrent set |
| persistent/resync property-pattern lists | immutable values replaced wholesale during startup |

## Known ownership gaps

These containers do not yet provide a complete cross-thread guarantee:

| field | current limitation |
| --- | --- |
| `layerFrames` | Iteration order is persisted, so changing the container requires an explicit ordering decision. |
| `decorationState` | Decoration order can affect presentation; mutation and runloop iteration need one ordered ownership model. |
| `visibleLayers` | `clear()` followed by `addAll()` is not an atomic replacement, so a concurrent reader can observe the empty intermediate state. |
| Kotlin REPL context variables/functions | Compiler writes can overlap completion and AI reads; ownership belongs to the compiler/completion subsystem. |

These are concrete ownership gaps, not permission to rerun a failing test. A failure involving one of
them should identify the container and concurrent actors, then add deterministic regression coverage
with the fix.

## Verification

The concurrency regression surface is:

- `BoxPropertyBagConcurrencyTest`
- `PropertyBagViewTest`
- `BoxTopologyConcurrencyTest`
- the `BoxJobList` concurrency tests
- transcript and runtime smoke tests that exercise real server scheduling

The tests use barriers, owned churn ranges, snapshot/view assertions, and sufficiently large
collections to create a controlled overlap. They must not depend on an unbounded loop eventually
winning a scheduling race.

Run the focused JVM tests while editing these containers, then run the normal repository check and
runtime smoke surface:

```sh
./gradlew jvmTest --tests 'afield.BoxPropertyBagConcurrencyTest' \
  --tests 'afield.PropertyBagViewTest' \
  --tests 'afield.BoxTopologyConcurrencyTest'
./gradlew check
uv run --project . --group backdoor python tools/run_field_tests.py --all
```

## Diagnosing a concurrent collection failure

1. Identify the collection being iterated and every thread that can structurally mutate it.
2. Include hidden writes such as delegated-default insertion, `putAll`, completion callbacks, and
   dead-session cleanup.
3. Put the guarantee at the owning collection when multiple call sites walk it.
4. Preserve ordering, nullability, and live-view mutation semantics required by callers.
5. Add a deterministic test that proves both the concurrency guarantee and the preserved collection
   contract.

Kotlin inlined collection operations can report synthetic line numbers beyond the end of the source
file. When that happens, inspect the class `SourceDebugExtension` with `javap -p -v` to map the frame
back through its SMAP entry before assigning the failure to the apparent source line.
