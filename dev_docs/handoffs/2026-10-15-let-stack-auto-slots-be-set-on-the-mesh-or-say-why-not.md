# Let stack.auto slots be set on the mesh, or say why not

- **Handoff:** `e40ec45e-95a6-4119-a673-d9798f87973d` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close e40ec45e "what happened"`

Rule 25 sharp edge on `public/docs/gpu_state.md`:

> A `stack.auto` slot is a property of the compiled shader; the mesh only carries push constants. `sim.force = …` is a receiver-type mismatch naming both `ModulePointer` overloads, which is a confusing way to learn that distinction and easy to hit, because everything else in this box is set on the mesh.

Fix: either let a `ComputeMesh`/`Mesh` forward a string assignment of a `stack.auto` slot name to its module, or give the assignment a dedicated error that says "stack.auto slots are set on the module (simModule.force)". Delete the paragraph in the same change.
