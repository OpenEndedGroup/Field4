# Fingerprint the launched property in profiling captures

- **Handoff:** `9d9aabaa-9567-49a0-b393-baf02b10ca03` (project)
- **Filed from:** https://openendedgroup.com/field4/profiling_timeline/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 9d9aabaa "what happened"`

Rule 25 sharp edge on public/docs/profiling_timeline.md, section "What the numbers mean":

> The source revision is a fingerprint of the box's `code` property captured with its launch context. The source button opens **current** code; it does not restore the captured revision. Use **Hot code** for sampled source lines. Alternate-property launches are identified by their strand, but the fingerprint still refers to the `code` property.

Fix in `afield/app/diagnostics/CoroutineProfiling.kt` / `ProfileOrigin` (launch site in `afield/runtime/lifecycle/Phased.kt`): fingerprint the property that was actually launched, and either let the source button show the captured revision or mark it as differing. Delete the caveat sentences in the same change.
