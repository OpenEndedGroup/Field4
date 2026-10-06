# Make the SolidSL and mesh-repair demo documents starting points

- **Handoff:** `fd513703-57ff-4f96-b2bf-38718243c2d5` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/geometry_from_disk/
- **Filed by:** human, 2026-10-06 21:15
- **Close with:** `python tools/docs_notes.py handoff-close fd513703 "what happened"`

Three openable documents are named by repository path instead of being linked as starting points (DOCS_WRITING_RULES rule 19):

- `public/docs/solid_notation.md`, "Open this": `dev_docs/agents/demos/solidsl_inverse/metadata.edn` (the fitting document). The first document, `assets/solidsl_gallery/metadata.edn`, is embedded as an offline iframe but also has no starting point.
- `public/docs/geometry_from_disk.md`, "Open this to see it": `public/docs/assets/mesh_repair_demo/` (diagnose + repair boxes).

Register each as a welcome-screen starting point and replace the path in the prose with a link to it.
