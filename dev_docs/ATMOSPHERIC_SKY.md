# Atmospheric sky — notes

User page: `public/docs/atmospheric_sky.md`. Code: `src/jvmMain/resources/afield/four/sky/atmosphere.glsl`
(served as `#include "sky/atmosphere.glsl"` by `builtInShaderInclude` in `shaders.kt`), CPU twin
`afield.graphics.sky.Atmosphere`, test `AtmosphereTest`. Demo transcript
`dev_docs/agents/transcripts/atmospheric_sky.yaml`; capture variants in `docs_capture/atmospheric_sky_*.yaml`.

## Findings (2026-11-09 nightly)
- Sliders keep their value across a re-run of the same box (same label), and fire their callback with the
  stored value. So `run <box> --file variant.kt` after the first run does NOT change the picture: the
  remembered slider values overwrite the new `var`s. Capture variants therefore replay a whole transcript
  into a fresh document, one per state.
- Exposure scale: with `sunColor = 20`, noon wants exposure ~2, sunset ~4, civil twilight (-4°) ~40,
  space (albedo 0.3) ~0.6. Output is `1 - exp(-L * exposure)` written linear; it looked right on the
  macOS swapchain, no extra gamma.
- Single scattering only: horizon slightly dark and deep twilight too dark. Next steps, in value order:
  Hillaire transmittance LUT (compute, 256x64) to replace the nested light march; multiscattering LUT;
  sky-view LUT; feed `skyRadiance` into `PathTraceEnvironment` (it currently only takes an image —
  render a lat-long RTT of the sky and hand it over) for a sun-elevation-driven path-traced scene.
