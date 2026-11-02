# Make SolidCraft JOIN of freeform half-sweeps work or fail clearly

- **Handoff:** `68f34575-fac2-4e11-8d7e-b01d2d5683a5` (project)
- **Filed from:** https://openendedgroup.com/field4/solidcraft/
- **Filed by:** human, 2026-11-02 10:32
- **Close with:** `python tools/docs_notes.py handoff-close 68f34575 "what happened"`

solidcraft.md, "What a sweep does with its profile":

> For a plain ring, sweep two halves and `JOIN` the second; the join removes the caps where they touch. In the SolidCraft version Field pins, that join works on arc paths but fails on a knot's freeform halves.

A rule-25 sharp edge: a known-bad case of the pinned SolidCraft. Fix it in code: check whether the v0.30.0 pin (just landed) still fails the JOIN of two freeform half-sweeps; if fixed, add a smoke case to `SolidCraftBuilderSmokeTest` and delete the last sentence; if not, report upstream and/or have the builder reject or explain it with a clear error. Delete the sentence in the same change.
