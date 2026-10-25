# Make String / String a compile error instead of an empty PathSet

- **Handoff:** `e90d8f01-4570-46b4-a61c-0ddd50d8b435` (project)
- **Filed from:** https://openendedgroup.com/field4/graph_query/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close e90d8f01 "what happened"`

`public/docs/graph_query.md`, aside "What `/` does to ordinary division", says:

> The one new case is a `String` on the right: `"a" / "b"` type-checks and returns an empty `PathSet`, so a mistyped `/` shows up a step later than a compile error would.

That is a sharp edge (rule 25): `operator fun Any?.div(pattern: String)` in
`src/jvmMain/kotlin/afield/data/query/graph/PathOperator.kt` also catches `String / String`
(and other leaf receivers that cannot have slots). Fix in code: add a more specific
`@Deprecated(level = ERROR)` overload `operator fun String.div(pattern: String)` (and, if cheap,
for `Number`/`Boolean` receivers) so the mistake is a compile error with a message pointing at
`graphPaths`. Delete that sentence from the aside in the same change (the rest of the aside, that
member `div` wins, can stay).
