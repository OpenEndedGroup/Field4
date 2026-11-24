# Decide whether graphPaths patterns select by type with and/or

- **Handoff:** `89a2f3dc-5b9e-4b33-b453-322b66f99db4` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** agent:claude, 2026-11-24 19:19
- **Close with:** `python tools/docs_notes.py handoff-close 89a2f3dc "what happened"`

## The notes that led here

- `a727d5c5` (public/docs/visualizers.md:1745): should we extend this language to be able to select by type? with and and or?
  > Private properties are left out, as graphPaths leaves them out by default. Pass includePrivate = true to tree or column to list them; their paths then need graphPaths(pattern, GraphWalkOptions(includePrivate = true)). A row has no path when nothing reaches it by name: an element of a primitive array, a map key that is an object, the second of two map keys that print alike.

Asked (note a727d5c5, visualizers.md "Trees and columns"): should the `graphPaths` pattern language gain selection by type, combined with and / or?

Why it is bigger than a docs edit: it is a new feature of the path language that `tree`, `column` and `graphPaths` share, and a design decision about syntax.

Proposed approach: a type predicate segment, e.g. `**:FLine` or `**[is FLine]`, plus `&` / `|` between predicates in one segment, resolved against the runtime class (simple name, with fully qualified as fallback). Alternatives: a Kotlin-side filter (`graphPaths(pattern) { it is FLine }`) with no syntax change; or `GraphWalkOptions(typeFilter = ...)`.

Questions for the human: string syntax or Kotlin lambda (or both)? Simple names or qualified? Should supertypes/interfaces match? Does and/or apply to path names too, or only types?

Page afterwards: a short example in "Trees and columns" selecting, say, every `FLine` in a nested structure.
