# Add ::ClassName type tests to graphPaths patterns

- **Handoff:** `d67005da-88d5-4a5d-ac9c-0a67944fae44` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** human, 2026-11-24 22:12
- **Close with:** `python tools/docs_notes.py handoff-close d67005da "what happened"`

## The notes that led here

- `a727d5c5` (public/docs/visualizers.md:1745): should we extend this language to be able to select by type? with and and or?
  > Private properties are left out, as graphPaths leaves them out by default. Pass includePrivate = true to tree or column to list them; their paths then need graphPaths(pattern, GraphWalkOptions(includePrivate = true)). A row has no path when nothing reaches it by name: an element of a primitive array, a map key that is an object, the second of two map keys that print alike.

Decided with Marc in handoff 89a2f3dc (closed 2026-11-24).

Add a type test to the path language shared by `graphPaths`, `tree` and `column`:

- Spelling `::ClassName` on a segment: `**::FLine`, `points::FLine`, `*::Vec3`.
- Short unqualified class names by default; a fully qualified name is also accepted.
- Kotlin `is` semantics: subclasses and interfaces match.
- "or" already exists: `|` separates whole alternatives (`splitAlternatives` in
  `src/jvmMain/kotlin/afield/data/query/graph/GraphQuery.kt`). No `&`: a name and `::Type` in one
  segment is the "and".
- `globMatches`/`segmentMatches` look only at names today; the walk must hand each value to the
  segment test.
- It rhymes with the typed lenses (`PathSet.ofType<T>()`, `lenses<T>()`, `Any?.graphLenses<T>()`
  in the same file): resolve `::T` with the same semantics.
- Check `tree.html`'s search, which reads the same patterns.

Tests go beside `GraphQueryCoreTest` / `PathOperatorTest`. Docs afterwards: in `visualizers.md`
"Trees and columns", a short example selecting every `FLine` in a nested structure, shown next to
`graphLenses<FLine>()`.
