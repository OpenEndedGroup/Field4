# Implement KHR_texture_transform (then sheen, transmission) in the StandardRenderer glTF bridge

- **Handoff:** `ab2a9eff-67c7-4ea7-ab56-8f93baa780e6` (project)
- **Filed from:** https://openendedgroup.com/field4/fabricate_renderer/
- **Filed by:** human, 2026-10-16 08:13
- **Close with:** `python tools/docs_notes.py handoff-close ab2a9eff "what happened"`

Follow-up from the code batch on 2026-10-16: `registerGltf(path)` now logs (warning,gltf) the file's extensionsUsed that the StandardRenderer does not evaluate, which is the brief's minimum. Still to do: implement KHR_texture_transform in the StandardRenderer material bridge (PathTraceGltf.kt already parses it, so its reading can be reused), then sheen and transmission, shrinking the page sentence as each lands. Update STANDARD_RENDERER_GLTF_EXTENSIONS in FabricateScene.kt too.


`public/docs/fabricate_renderer.md`, "glTF loading and animation":

> It does not yet handle texture transforms, physical transmission/refraction, sheen, BRDF LUT integration, cubemap PMREM, or clearcoat roughness/normal texture variants.

The page's own showcase asset (ToyCar, intro paragraph) "carries transmission, clearcoat, sheen, ... texture transforms", so the headline demo shows features the renderer drops. This is a "still does not" list (rule 25). Fix in the StandardRenderer glTF material bridge (`src/jvmMain/kotlin/afield/graphics/scene/fabricated/`): implement KHR_texture_transform first (cheapest, and visible on ToyCar), then sheen/transmission. Or, at minimum, log once per import which glTF extensions were ignored. Shrink or delete the sentence in the same change as each feature lands.
