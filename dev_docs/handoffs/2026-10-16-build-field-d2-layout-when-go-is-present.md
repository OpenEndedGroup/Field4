# Build field-d2-layout when Go is present

- **Handoff:** `ad17f46a-e572-41a5-b72b-48857370af56` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** human, 2026-10-16 09:20
- **Close with:** `python tools/docs_notes.py handoff-close ad17f46a "what happened"`

Decided 2026-10-16 (Marc, handoff 99e14fea; see `dev_docs/handoffs/2026-10-16-decide-how-field-d2-layout-gets-built-or-shipped.md`): build `native/field-d2-layout` only when a Go toolchain is present.

Work:

1. Add a Gradle task (e.g. `buildD2LayoutHelper`) that runs `go build` into the location `D2Layout`'s probe looks in, when `go` resolves on PATH. When it doesn't, skip with one line ("Go not found; PetriLayout.TALA needs it, install Go and re-run ./gradlew buildD2LayoutHelper") and succeed. Make it up-to-date-aware on the Go sources.
2. Wire it into `buildDocsNativePrerequisites` (and anywhere else native helpers are prepared) as a skip-if-absent step, so it never fails a build.
3. The mtime-keyed probe (7d34daa3) already picks up a helper built under a running Field. Keep `D2LayoutProbeTest` green.
4. Change the petri_nets.md paragraph to name the task: with Go installed, `./gradlew buildD2LayoutHelper` enables TALA; without it, the fallback layout is used.
