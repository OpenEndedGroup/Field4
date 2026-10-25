# Petri TALA layout cache should notice a newly built d2 helper

- **Handoff:** `7d34daa3-5163-4ac5-9b6a-307488955b5e` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_cheatsheet/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 7d34daa3 "what happened"`

The last row of the Kotlin entry-points table on `public/docs/petri_cheatsheet.md` reads:

> | `clearPetriLayoutCache()` | re-solve, after building that helper under a running Field |

This exists only because the TALA layout cache (`afield/simulation/petri/PetriLayoutSolver.kt`) remembers the FORCE fallback it used while `native/field-d2-layout` was absent, so building the helper under a running Field does not take effect until the user clears the cache by hand.

Fix in code: key the cached layout on helper availability (or don't cache a fallback result / re-probe the helper binary's mtime), so a newly built helper is picked up on the next draw. Then delete that table row (and `clearPetriLayoutCache()` from the public surface if nothing else needs it) in the same change.
