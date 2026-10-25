# applyToMesh: work from a main, or fail with a clear message

- **Handoff:** `02e2e712-bae3-437a-8829-149b7848d7b6` (project)
- **Filed from:** https://openendedgroup.com/field4/capture_limits/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close 02e2e712 "what happened"`

Rule 25 sharp edge. The page: "`MeshInstance.applyToMesh` cannot run from a `main` in the first place ... it throws `Cannot invoke \"afield.document.graph.Box.getProperties()\"` before any capture is attempted."

Fix: let `defaultBuffer` take an explicit renderer when there is no box context, or throw an error naming the missing box context instead of an NPE. Delete the paragraph in the same change.
