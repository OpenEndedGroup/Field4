# Rename hardware-only OpticalFlow class

- **Handoff:** `57f98c0f-6054-4df4-80d3-de5d81b739ea` (project)
- **Filed from:** https://openendedgroup.com/field4/optical_flow/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 57f98c0f "what happened"`

Rule 25 sharp edge on `public/docs/optical_flow.md` ("Constructing the operation"):

> Constructing `OpticalFlow(...)` directly still means hardware only, and it fails on a device without the extension.

The class name `OpticalFlow` reads as the general entry point but is the NVIDIA-hardware backend (beside `PyramidOpticalFlow`). Rename it (e.g. `HardwareOpticalFlow`, keeping a deprecated alias), update the backend table and fences/starting points, and delete this sentence in the same change. Related: the two backends report cost on different scales ("a rejection threshold tuned on one backend does not transfer"); consider a normalized cost channel so that warning can go too.
