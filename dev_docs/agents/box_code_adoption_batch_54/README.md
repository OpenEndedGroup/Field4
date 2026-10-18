# Expose seeded paint controls and keep particle rows readable

Frozen findings: `c958776f78cf-01`, `58fe3dbd8e31-01`.

Expose stroke count, integration steps, step length and the three flow frequencies together at the top of the native paint flow box. Keep seed 4, the existing brush loading/wipe and original defaults. Actual import/ktfmt covers all three transcript Kotlin bodies and all six Kotlin documentation states. The schema's three particle rows retain one particle per line while argument spacing and leading zeroes are normalized.

Four real native paint executions write diagnostic PNGs: baseline, revised defaults, changed stroke-count/step-length, and restored defaults. The three default images are pixel-identical at 900 × 643; the edited settings produce different pixels. Native canvases in the fixture close after saving their actual output. The actual revised image was visually inspected.

The combined schema declarations and particle values execute in Field before and after; the real GpuSchema packs all three values into CPU ByteBuffers with identical bytes and ABI hash. No Vulkan buffer allocation or GPU drawing is claimed, and no public capture was replaced.

Checkpoint: 338/405 addressed, 67 open. Corpus-wide mechanical coverage remains incomplete. Full guards are delegated to the lander.
