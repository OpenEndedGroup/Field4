# Close the open boundary loops on a thickened SolidCraft sheet

- **Handoff:** `d689edbd-4d49-40be-8e04-406df0a0cbb5` (project)
- **Filed from:** https://openendedgroup.com/field4/solidcraft/
- **Filed by:** human, 2026-11-02 10:32
- **Close with:** `python tools/docs_notes.py handoff-close d689edbd "what happened"`

solidcraft.md, "Surfaces":

> The thickened sheet is 2 deep, though its welded mesh still reports two open boundary loops.

A rule-25 sharp edge: `thicken` of a boundary patch should be a closed solid, but `mesh(...).toTriMesh().weld()` reports two open boundary loops. Find whether the open loops come from SolidCraft's tessellation (unsewn side faces), from Field's mesh bridge (face seams not shared), or from `weld()` tolerance, fix it there, and delete the clause in the same change (recapture `assets/solidcraft/surfaces.png`).
