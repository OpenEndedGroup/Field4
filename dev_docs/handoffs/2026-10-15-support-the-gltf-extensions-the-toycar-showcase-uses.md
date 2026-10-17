# Support the glTF extensions the ToyCar showcase uses

- **Handoff:** `123a29a0-f721-43f9-ad5b-23cc8411ba7e` (project)
- **Filed from:** https://openendedgroup.com/field4/fabricate_renderer/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 123a29a0 "what happened"`

`public/docs/fabricate_renderer.md`, "glTF loading and animation":

> It does not yet handle texture transforms, physical transmission/refraction, sheen, BRDF LUT integration, cubemap PMREM, or clearcoat roughness/normal texture variants.

The page's own showcase asset (ToyCar, intro paragraph) "carries transmission, clearcoat, sheen, ... texture transforms", so the headline demo shows features the renderer drops. This is a "still does not" list (rule 25). Fix in the StandardRenderer glTF material bridge (`src/jvmMain/kotlin/afield/graphics/scene/fabricated/`): implement KHR_texture_transform first (cheapest, and visible on ToyCar), then sheen/transmission. Or, at minimum, log once per import which glTF extensions were ignored. Shrink or delete the sentence in the same change as each feature lands.
