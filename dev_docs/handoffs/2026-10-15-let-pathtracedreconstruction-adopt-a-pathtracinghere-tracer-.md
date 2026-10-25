# Let PathTracedReconstruction adopt a pathTracingHere tracer without manual unscheduling

- **Handoff:** `babb5727-0817-4a42-aed9-603cf044e944` (project)
- **Filed from:** https://openendedgroup.com/field4/path_traced_reconstruction/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close babb5727 "what happened"`

`public/docs/path_traced_reconstruction.md` ("Put a material shader in the box") tells the reader to follow `pathTracingHere(...)` with two manual removals before handing the tracer to RR:

```
// pathTracingHere installs standalone passes. Hand scheduling over to RR:
renderer.computeList.others.remove(tracer.name, tracer)
renderer.renderList.preTargets.remove("${tracer.name}-output")
lab = PathTracedReconstruction(tracer).install()
```

This is an ordering/ownership trap the API exposes: forget the removals and the tracer is dispatched twice. Fix in code: have `PathTracedReconstruction.install()` take over (unschedule) the standalone passes a `pathTracingHere` tracer installed, or give `pathTracingHere` an `installed = false` option. Then update the fence (and the starting-point transcript) to drop the two `remove` lines and the comment, in the same change.
