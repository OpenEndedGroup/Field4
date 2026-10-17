# Let the DLSS adapter accept Field's direct-lighting motion convention

- **Handoff:** `bc203f1c-a29e-4de9-9a35-a1960c97c893` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_reconstruction/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close bc203f1c "what happened"`

`neural_reconstruction.md` warns:

> Field's direct-lighting motion texture stores current-minus-previous **UV** displacement, so it needs both a sign and unit conversion before this adapter can use it. Merely passing that texture through would produce incorrect history.

This is a sharp edge (rule 25): two Field subsystems disagree on motion-vector convention, and the type system lets one be passed to the other. Fix in code: either have `ReconstructionInputs` accept a motion texture tagged with its convention (as `OpenDlssNrInputs` already does with `motionCurrentMinusPrevious` / `motionInPixels`) and convert inside the adapter, or provide a conversion pass for `DirectLighting`'s motion output. Delete the paragraph from `public/docs/neural_reconstruction.md` in the same change.

## Completed on Linux/NVIDIA, 2026-10-17

Motion convention tags now supply NGX sign/unit scales; the real GPU comparison passed against pixel-motion history, with an untagged negative control.

Evidence and reproduction: [Linux/NVIDIA verification](../lighting/ELSEWHERE_20261017.md). The originating annotation store is absent on this host; this tracked record carries the outcome for closing there after landing.
