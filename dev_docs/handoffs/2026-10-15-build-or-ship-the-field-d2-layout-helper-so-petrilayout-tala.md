# Build or ship the field-d2-layout helper so PetriLayout.TALA works out of the box

- **Handoff:** `6f0799bc-11c7-4a6e-8e2d-ee5b9b38a0b0` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 6f0799bc "what happened"`

petri_nets.md, "Drawing a net", says: "TALA needs a helper program, `native/field-d2-layout`, written in Go, which Field does not build or ship. Without it `PetriLayout.TALA` falls back to `FORCE` ... When a box asks for TALA and the helper is missing, Field prints the command that builds it."

This is a sharp edge (rule 25). Fix: have the build (a Gradle task alongside buildDocsNativePrerequisites) build or fetch the helper, or bundle it, so TALA is available whenever Field is. Delete the paragraph in the same change (keep only `D2Layout.available` if a fallback remains).
