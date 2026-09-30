# Add a customizable third tier of search roots to FileManager plain-name resolution

- **Handoff:** `d9a3a84a-3ac2-40b8-a35a-c9b00f0e15e9` (project)
- **Filed from:** https://openendedgroup.com/field4/files_and_paths/
- **Filed by:** agent:claude, 2026-09-30 20:39
- **Close with:** `python tools/docs_notes.py handoff-close d9a3a84a "what happened"`

## The notes that led here

- `488a9012` (public/docs/files_and_paths.md:9): And a customizable list of other places third
  > workspace second.

## What was asked

Note 488a9012 on `public/docs/files_and_paths.md:9` ("it looks in the document first and the workspace
second") says: "And a customizable list of other places third."

## Why it is bigger than a docs edit

The resolver has no such list today, so writing that sentence would be false. In
`src/jvmMain/kotlin/afield/stdlib/FileManager.kt`:

- `searchRoots()` returns only the document's `files/` followed by `currentProjectFileRoots(create = false)`,
  meaning the `_projects/<name>/files/` of each project that contains the document.
- `resolveRelative()` walks those roots, and when the file is missing it falls back to
  `documentFilesDirectory`.
- Roots registered with `FileManager.add` / `FileManager[name] = ...` (the `namedDirectories` map) are
  reached only through an explicit `%name/...` prefix, in `resolveMappedPath`. A plain name never
  searches them. Globs use the same `searchRoots()`, so they don't search them either.

Adding a third tier means changing how resolution behaves, so it is engineering work, not a docs edit.

## Proposed approach

Add an ordered, process-global search list on the `FileManager` companion, for example
`FileManager.searchPath: MutableList<Path>` or `FileManager.addSearchRoot(name, dir)`, and append it
to `searchRoots()` after the project roots. The `source` field would read `"search:<name>"` so that
`fileManagerAccessHistory` shows which root answered. Two details:

- Bump `version` whenever the list changes, so `refreshGlobalStateIfNeeded` drops cached resolutions.
- Keep the missing-file fallback on the document's `files/`. A new file should never be written
  into a shared search directory.

Alternatives:

- Mark registered named roots as searchable with a flag, `FileManager.add(name, dir, search = true)`,
  so one registry serves both `%name` and plain-name lookup.
- A per-document list stored in a root-box property, persisted in `metadata.edn`. It travels with
  the document, but it reopens the machine-specific-path problem the page opens with.

## Questions for the human

- Should the list be process-global (like named roots) or per-document/per-workspace?
- Should it be the named-roots registry with a `search` flag, or a separate list?
- Should a glob also search the extra roots?

## What the page needs once it is done

Rewrite the intro sentence to name three tiers. Add a third item to "Where a plain name is looked
for". In "Roots of your own", show how to put a root on the search list, and update the glob comment.
