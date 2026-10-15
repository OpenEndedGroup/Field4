# Give the two lathes one axis convention

- **Handoff:** `32962885-06c9-4ba9-bdb1-7cd9943eb867` (project)
- **Filed from:** https://openendedgroup.com/field4/solid_notation/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 32962885 "what happened"`

Rule 25 sharp edge on public/docs/solid_notation.md ("Axis conventions"):

> !!! warning "Two lathes, ninety degrees apart"
> Field has two of these and they do not agree. Check which one you have before you port a profile between them. (`afield.geometry.mesh.revolveMesh` revolves `(radius, y)` about Y; `afield.geometry.solid.manifold.Section.revolved`, and so `Profile.revolved`, revolves `(radius, z)` about Z.) ... `Profile.revolved` takes no axis argument, because Manifold's revolve has none.

Fix in code: give the lathes one convention or an explicit axis — e.g. an `axis` parameter on `Profile.revolved` (rotate the Manifold result) and/or on `revolveMesh`, with a shared default, so a profile ports unchanged. Delete the warning in the same change (keep a one-line axis bullet in "Axis conventions").
