# Let the DLSS adapter accept Field's direct-lighting motion convention

- **Handoff:** `304cbbae-5800-477f-8bd4-8ee6ed771c42` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_reconstruction/
- **Filed by:** human, 2026-10-16 07:49
- **Close with:** `python tools/docs_notes.py handoff-close 304cbbae "what happened"`

Refiled from the code batch on 2026-10-16. It is the DLSS adapter, which runs only on NVIDIA; this session was on macOS, so it could not be verified.


`neural_reconstruction.md` warns:

> Field's direct-lighting motion texture stores current-minus-previous **UV** displacement, so it needs both a sign and unit conversion before this adapter can use it. Merely passing that texture through would produce incorrect history.

This is a sharp edge (rule 25): two Field subsystems disagree on motion-vector convention, and the type system lets one be passed to the other. Fix in code: either have `ReconstructionInputs` accept a motion texture tagged with its convention (as `OpenDlssNrInputs` already does with `motionCurrentMinusPrevious` / `motionInPixels`) and convert inside the adapter, or provide a conversion pass for `DirectLighting`'s motion output. Delete the paragraph from `public/docs/neural_reconstruction.md` in the same change.

## Completed on Linux/NVIDIA, 2026-10-17

Motion convention tags now supply NGX sign/unit scales; the real GPU comparison passed against pixel-motion history, with an untagged negative control.

Evidence and reproduction: [Linux/NVIDIA verification](../lighting/ELSEWHERE_20261017.md). The originating annotation store is absent on this host; this tracked record carries the outcome for closing there after landing.
