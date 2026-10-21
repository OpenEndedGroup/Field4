# Check Manifold FFM descriptors against manifoldc.h

- **Handoff:** `d02d5c3f-bb8f-4992-83d9-c1fbf355c0e1` (project)
- **Filed from:** https://openendedgroup.com/field4/manifold/
- **Filed by:** human, 2026-10-20 11:11
- **Close with:** `python tools/docs_notes.py handoff-close d02d5c3f "what happened"`

`public/docs/manifold.md` ends with an aside (now titled "Agents: writing FFM descriptors by hand") that exists only to warn about a hazard nothing checks:

> Nothing checks them. A `size_t` declared as an `int`, a struct return declared as a pointer, an argument in the wrong position — all of them compile, link, and return *something*. The failure mode is a plausible wrong number, not an error.
>
> Two places in this API where `manifoldc.h` is the only warning. `manifold_slice` and `manifold_project` return `ManifoldPolygons*`, **not** `ManifoldCrossSection*`; ... the first read aborts the process inside libc++ with `mutex lock failed` and no Java stack at all. And `manifold_ray_cast` reports its hit distance as a **fraction of the origin-to-end segment** ...

Fix in code: add a test (beside `src/jvmMain/kotlin/afield/geometry/solid/manifold/ManifoldSmokeTest.kt`, or a jvmTest) that checks every FFM descriptor in `ManifoldNative.kt` against the prototypes in the installed `manifoldc.h` (return/argument kinds and order), and make the `manifold_slice`/`manifold_project` return type and the `manifold_ray_cast` fraction impossible to misuse in the Kotlin wrapper (typed handle, distance converted to world units at the boundary). Delete the aside from `manifold.md` in the same change.
