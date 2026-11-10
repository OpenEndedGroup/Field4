# Sky environment (atmosphere as IBL + key light)

`afield.graphics.sky.SkyEnvironment` bakes `Atmosphere.radiance` on the CPU into a 128x64
RGBA32F `StreamingTexture` (equirect, the convention of `directionToEquirect` in
`standardRenderer.kt` at rotation 0) and drives a bundle's directional key light from
`Atmosphere.sunLight` (transmittance to the top of the atmosphere).

Method and traps:
- The environment texture is bound once, in the lighting module's `textureResolver`. A live sky
  therefore has to rewrite the *same* image: `setSun` bakes on a worker, then on the renderer's
  queue thread waits for `graphQueue` to go idle and calls `uploadNow()`.
- The sun disc is excluded from the bake (`sunAngularRadius = 0`): a 1-texel 20 000 spike is
  noise to IBL. The key light carries it.
- One mip only, so `textureLod(roughness)` has nothing to blur: rough dielectrics reflect the sky
  sharply. Next step: GGX-prefilter mips (`LoadedTexture.ggxPrefilterMips` is CPU and private)
  or a compute prefilter into a mipped storage image.
- Parity: CPU bake texels equal `radiance` along `equirectDirection` exactly, and
  `equirectDirection` inverts the shader's mapping (`AtmosphereTest`). No GPU bake exists, so no
  GPU-vs-CPU texel parity is claimed.
- The path tracer loads its environment from an EXR path; it does not take this texture yet.
- Captures: fabricate-scene `sky-environment-{high,sunset,twilight}`; demo
  `dev_docs/agents/transcripts/sky_environment.yaml`.
