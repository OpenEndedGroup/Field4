# Querying object graphs

Most of the time you know exactly which field you want, and you write
`rig.osc.filter.cutoff`. But sometimes you don't. You want *every* cutoff in a
rig, or every string in a scene, or the four gains that happen to be above a
threshold, and you want to move them all at once. Writing that by hand means
knowing the shape of the graph in advance, which is precisely what you don't
have while you're still building it.

`graphPaths` treats any live object as a tree of addressable slots and lets you
ask for them by pattern, the way XPath asks a document. It is plain reflection
over whatever you already have: no base class to inherit, no annotations, no
registration.

## A graph, addressed

Give `graphPaths` a pattern and it hands back a `PathSet`: an ordered collection
of *live lenses*. Each one is an address plus a getter, and a setter too if the
slot is mutable. Nothing is copied, so reading a path twice can legitimately
give two answers.

<!--
FIELD_TEST
id: docs.graph_query.pathset
runner: backdoor-box
tags: [docs, graphquery, tohtml]
timeoutSec: 60
waitSec: 1.5
expect:
  outputContains:
    - "PathSet"
    - "carrier/freq"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/graph_query/pathset.png" alt="an output panel labelled main holding a PathSet of 9, as a two-column table of path and value: carrier is an Osc, carrier/freq 220, carrier/gain 0.800, carrier/label sine, modulator is an Osc, modulator/freq 440, modulator/gain 0.200, modulator/label saw, and name rig. Paths are in blue, values in white" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="graph-query-pathset" w="760" h="320"}
:::static
class Osc(var freq: Float, var gain: Float, var label: String)
class Rig(val carrier: Osc, val modulator: Osc, var name: String)

val rig = Rig(Osc(220f, 0.8f, "sine"), Osc(440f, 0.2f, "saw"), "rig")

display("main", rig.graphPaths("**"))
```

`**` matched everything. Interior edges (`carrier`) are listed alongside leaves
(`carrier/freq`), and properties come out in primary-constructor order rather
than alphabetically: you read them in the order you declared them.

## The slash shorthand

A path already separates its segments with `/`, so the operator that starts a
query may as well be spelled the same way. `rig / "**/freq"` *is*
`rig.graphPaths("**/freq")`: same walk, same live refs, less to type.

```diffstatic {data-lang=kotlin}
// the same query, twice
(rig / "**/freq").whereValueIs<Float> { it > 300f }
rig.graphPaths("**/freq").whereValueIs<Float> { it > 300f }

// and the pattern can be split across several `/` if that reads better —
// this still reports `carrier/freq`, not a bare `freq`
rig / "carrier" / "freq"
```

Those parentheses on the first line are not optional: `.` binds tighter than
`/`, so `rig / "**/freq".whereValueIs<Float> { ... }` would be looking for that
method on the *string*.

`/` is defined on `Any?`, which is a wide place to put an operator. Ordinary
division is untouched: a member `div` always beats an extension, so every
numeric type, and every vector, quaternion or signal type that spells `/` its
own way, keeps its own arithmetic. Between two extensions the more specific
receiver wins, so `kotlin.io.path`'s `path / "sub"` still joins a path. The one
new case is a `String` on the right of `/`. `"a" / "b"` type-checks and hands
back an empty `PathSet`, so a `/` you got wrong shows up a step later than a
compile error would. The operator has nowhere to put a `GraphWalkOptions`
either: when you need a depth cap, private properties or a custom
`descendInto`, call `graphPaths` and say so.

## Patterns and predicates

Two wildcards: `*` matches exactly one segment, `**` matches any number of
segments including zero. A `*` *inside* a segment globs within that one name, so
`"*Alpha"` finds `contourAlpha` and `"osc*"` finds `osc1` and `osc2`. It never
crosses a `/`; that is `**`'s job. Names are matched ignoring case, so
`"cutoff"` reaches `cutOff`. If one object really does hold two names differing
only in case, one pattern matches both of them, and they remain two distinct
slots.

A `|` separates whole patterns and unions what they find:

```diffstatic {data-lang=kotlin}
// everything that reads like an alpha, plus every w component, at any depth
look / "**/*alpha | **/w"
```

One walk answers both branches, so each slot appears once even when two branches
would have claimed it, and the order is the graph's rather than the pattern's.
Because `|` separates *whole* patterns, `"**/freq|gain"` means `"**/freq"` or
`"gain"`, not two leaves under `**`. Repeat the prefix on both sides.

Patterns say where a slot lives. To filter on what it holds, add a predicate.

```diffstatic_runner {data-lang=kotlin screenshot="assets/graph_query/predicates.png" alt="the same panel and table headings, now holding a PathSet of 1: the single row modulator/freq 440. Everything the unfiltered query returned has been narrowed to one slot" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="graph-query-predicates" w="760" h="300"}
:::static
class Osc(var freq: Float, var gain: Float, var label: String)
class Rig(val carrier: Osc, val modulator: Osc, var name: String)

val rig = Rig(Osc(220f, 0.8f, "sine"), Osc(440f, 0.2f, "saw"), "rig")

// every freq at any depth, but only the ones above 300
display("main", rig.graphPaths("**/freq").whereValueIs<Float> { it > 300f })
```

`whereValueIs<T>` only ever shows the predicate values of type `T`, so a graph
holding strings and booleans next to your floats can't trip it up. The rest of
the family: `where` (the whole ref — path, owner and value), `whereValue`,
`ownedBy`, `nonNull`, `atDepth`, `excluding`, and `writable()`. They compose,
and each returns a new set rather than mutating the receiver.

## Typed lenses for any leaf type

Numbers aren't special. `lenses<T>()` narrows a `PathSet` to the slots holding a
`T` and gives you typed reads and writes: strings, booleans, enums, vectors,
whatever your graph is made of.

```diffstatic_runner {data-lang=kotlin screenshot="assets/graph_query/lenses.png" alt="an output panel holding a LensSet of Mode with 2 rows, and a third column beyond path and value: lamp/mode is a Mode and writable, strip/mode is a Mode and writable" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="graph-query-lenses" w="760" h="300"}
:::static
enum class Mode { OFF, ON }
class Light(var name: String, var on: Boolean, var mode: Mode, var level: Float)
class Room(val lamp: Light, val strip: Light)

val room = Room(
    Light("lamp", true, Mode.ON, 0.4f),
    Light("strip", false, Mode.OFF, 0.9f)
)

// flip every boolean in the graph, then read back the enums
room.graphPaths("**").lenses<Boolean>().update { !it }

display("main", room.graphPaths("**").lenses<Mode>())
```

`update` is read-modify-write per slot; `set` assigns one value everywhere;
`set(list)` assigns positionally. Each returns how many slots it actually wrote,
so a read-only slot is reported rather than silently skipped.

## Naming a path is cheap

A pattern with no wildcards doesn't traverse anything: it walks straight down
the segments you named, one property read each. A pattern with a literal prefix
descends that prefix directly and then searches only the subtree below it. On a
graph of any size that is a large difference, and you can measure it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/graph_query/cost.png" alt="an output panel holding one line of text, the two measurements the example prints: naming a path: 4 edge reads — walking all 4443 slots: 1666 edge reads" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="graph-query-cost" w="760" h="220"}
:::static
var reads = 0

class Node(val label: String) : LiveGraphInspectable {
    val kids = linkedMapOf<String, Node>()
    var leaf: Float = 1f
    override fun liveGraphChildren(): Iterable<LiveGraphEdge> =
        kids.map { (name, child) -> LiveGraphEdge(name) { reads++; child } } +
            LiveGraphEdge("leaf", { reads++; leaf }, { v -> leaf = (v as Number).toFloat() })
}

fun build(depth: Int, breadth: Int): Node {
    val node = Node("n")
    if (depth > 0) repeat(breadth) { i -> node.kids["k$i"] = build(depth - 1, breadth) }
    return node
}

val root = build(depth = 3, breadth = 5)

reads = 0
root.graphPaths("k1/k2/k3/leaf")
val named = reads

reads = 0
val all = root.graphPaths("**")
val everything = reads

display("main", "naming a path: $named edge reads — " +
        "walking all ${all.size} slots: $everything edge reads")
```

Three things are *not* part of a literal prefix, and each falls back to the walk.
A segment holding a within-segment glob (`"osc*"`) has to be compared against the
names that are actually there. A literal segment spelled with the wrong case may
have a sibling differing only in case, which a single descent could not report,
so it defers to the walk. And a pattern with more than one `|` branch has no
single path to descend: it walks once and filters by every branch.

That example also shows `LiveGraphInspectable`: implement it and you decide what
your object's children are, instead of letting reflection guess. Custom edges
are merged ahead of reflected ones, so you can rename or hide as much as you
like and still get everything else for free.

If you're going to run the same query repeatedly, compile it once with
`GraphQuery.compile(pattern)` and call `resolve(root)` on the result — the
pattern is parsed a single time and the plan is reusable.

## Driving numbers: `params()`

`params()` is the synth-flavoured head on the generic layer. It narrows a
`PathSet` to the slots that can be *driven* (native `Param`s, and any writable
numeric field) and gives you set-at-once operations over the group.

In that second category a plain `Float` or `Int` property
on an ordinary object gains base/supplier/modulation semantics, so anything with
numbers in it can be driven like a synth parameter.

```diffstatic_runner {data-lang=kotlin screenshot="assets/graph_query/paramset.png" alt="an output panel holding a ParamSet of 6, with columns path, base and state: a/freq 110, a/gain 0.100, b/freq 220, b/gain 0.500, c/freq 440, c/gain 0.900, and every one of the six marked scalar" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="graph-query-paramset" w="760" h="300"}
:::static
class Osc(var freq: Float, var gain: Float)
class Rig(val a: Osc, val b: Osc, val c: Osc)

val rig = Rig(Osc(220f, 0.5f), Osc(440f, 0.5f), Osc(880f, 0.5f))

// spread the gains evenly, then halve every freq
rig.params("**/gain").rewrite { ramp(0.1f..0.9f) }
rig.params("**/freq").scale(0.5f)

display("main", rig.params("**"))
```

`ParamSet` carries the group operations you would expect: `set`, `offset`,
`scale`, `clamp`, `random`, `choose`. It also has a `rewrite { }` scope for
shaping a set relative to itself, with `ramp`, `normalize`, `jitter`, and the
`mean`/`min`/`max` of the group.

Modulation and suppliers work the same way across the whole set:

```diffstatic {data-lang=kotlin}
// one shared LFO added on top of every cutoff — `matches` is to `params()`
// what `/` is to `graphPaths()`
(rig matches "**/cutoff").modulate(sine * 40f, mode = ParamModulationMode.ADD)

// or a per-entry expression, with the group's statistics in scope
rig.params("**/freq").supply { entry, index, group ->
    group.mean + sin(time * (0.2f + index * 0.05f)) * 12f
}
```

Those attach to the runloop and keep evaluating, so the underlying fields move
on their own. `clearModulation()` and `clearSuppliers()` detach them again.

## Notes on traversal

Some behaviours to know about before you point this at something large:

- **Descent stops at the obvious leaves** — numbers, strings, booleans, enums,
  primitive arrays, functions, class objects — and at anything in the JDK's own
  packages, so you get your graph rather than the JVM's. `GraphWalkOptions`
  `descendInto` refines that policy: it receives both the value and the default
  decision, so `{ v, byDefault -> byDefault && v !is Osc }` prunes without
  losing the built-in guards.
- **Cycles are safe.** Detection is path-scoped: a node is skipped only when it
  already appears on the current ancestor chain. A node shared by several edges
  is therefore reachable under *each* of its addresses, while `a → b → a` still
  terminates. `maxDepth` and `maxRefs` bound the rest.
- **Collections are transparent.** Maps are keyed by their stringified key,
  lists and arrays by index, and mutable ones are writable.
- **Non-public classes work.** Your own `private` and `internal` model classes
  are read and written like any other.
- **A throwing getter won't derail a walk**, but reading that one slot
  propagates the real exception rather than reporting null.

A pattern addresses slots; it cannot follow an edge and then follow it again. When the question is
recursive (what runs downstream of this box, what does nothing feed into), [Datalog in a
Box](datalog.md) is the next page, and it can take `graphPaths` output as its facts.
