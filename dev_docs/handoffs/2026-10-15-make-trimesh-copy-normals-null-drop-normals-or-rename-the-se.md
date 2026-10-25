# Make TriMesh.copy(normals = null) drop normals, or rename the sentinel

- **Handoff:** `11152db5-933a-4a74-ab71-7b3806da66b7` (project)
- **Filed from:** https://openendedgroup.com/field4/geometry_from_disk/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close 11152db5 "what happened"`

geometry_from_disk.md troubleshooting table says:

> To drop them, `withoutAttributes()` — note that `copy(normals = null)` **keeps** them, because `null` there means "unchanged".

A misleading API. Fix so `copy(normals = null)` clears normals (or remove the nullable-as-unchanged parameter). Delete the warning clause from the table in the same change.
