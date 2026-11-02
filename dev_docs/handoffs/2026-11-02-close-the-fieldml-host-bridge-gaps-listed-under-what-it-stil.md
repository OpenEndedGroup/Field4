# Close the FieldML host bridge gaps listed under What it still will not do

- **Handoff:** `1a9a4316-31b6-4609-86f0-1c4bb9c6706a` (project)
- **Filed from:** https://openendedgroup.com/field4/fieldml_host_bridge/
- **Filed by:** human, 2026-11-02 10:31
- **Close with:** `python tools/docs_notes.py handoff-close 1a9a4316 "what happened"`

`public/docs/fieldml_host_bridge.md`, section "What it still will not do", is a list of known gaps in the FieldML host bridge (rule 25):

> - A JVM `Map` comes back as a FieldML record, so passing one to `hostCall` as one argument needs `hostCall1`, which never spreads. Lists work the same way.
> - A callback written inside a helper has untyped parameters. ... Use `hostAs "Vec3"` there.
> - A lambda's result is not checked against what Kotlin wants. It is converted ... but a result of the wrong shape fails when it runs.
> - `internal` Kotlin members cannot be reached by their source names, because the compiler renames them.
> - Buffers, kernels and anchors, which FieldML cannot build and which have no constructor to call, are out of reach.

Also in "Source macros": "Nothing protects you from a value too large for the declared type; that truncates silently".

Fix in code where feasible (`afield.lang.fieldml.core.FieldMLHostBridge`, `afield.lang.fieldml.type.FieldMLHostTypes`, `Inferencer`): type-check lambda results against the expected Kotlin return type; infer helper-parameter types from use (or report a pointed error suggesting `hostAs`); resolve `internal` members by their mangled names; range-check `setProp` into a narrower declared slot and report overflow. Each item fixed is deleted from the page in the same change; whatever remains is genuine design and should be restated as plain behaviour in the relevant section rather than a "still will not" list.
