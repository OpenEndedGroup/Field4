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
- Path tracer: `PathTraceEnvironment.fromAtmosphere(sunElevation, sunAzimuth, 512, 256)` extends
  the existing float-array environment (`PathTraceEnvironment(w, h, rgba)`); `PathTraceDemo.install`
  gained an overload taking a `PathTraceEnvironment`. Same equirect convention (v = acos(y)/pi).
  The sun disc is folded into its single texel by `Atmosphere.addSunDisc` (power-conserving,
  `AtmosphereTest.sunDiscKeepsItsPowerInOneTexel`), so the tracer's env CDF + MIS samples it:
  shadows come out clean. Remaining speckle source is caustics (diffuse -> mirror -> sun), which no
  light sampling can reach; `indirectClamp = 10f` removes them (seen: speckles at 45/4 deg without
  it, none with it at 1024 spp). The sun texel at 512 wide is ~0.7 deg, larger than the real 0.53 deg.
- The tracer runs on this Mac (M-series, `COMPUTE_BVH` backend, 1280x720, 1024 spp in ~1 min).
  Its environment is a snapshot: a moving sun means a new environment and tracer.
- Path-traced captures: `public/docs/assets/path_tracing/atmosphere_{high,sunset,twilight}.png`
  (native-window, static images, not runner fences).
- Captures: fabricate-scene `sky-environment-{high,sunset,twilight}`; demo
  `dev_docs/agents/transcripts/sky_environment.yaml`.
