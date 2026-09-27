# Ray Reconstruction

Ray Reconstruction turns the raw samples from [RTXDI](direct_lighting.md) into a temporally reconstructed image. It is another choice of finish for the lighting experiment: you can compare it directly with the existing spatial filter and with spatial filtering followed by DLAA.

![Field RTXDI lighting reconstructed at 1920 × 1080](assets/ray_reconstruction/ray_reconstruction.png)

## Open the experiment

Open **Starting points → ray_reconstruction**. The scene has a moving area light, a moving camera, and four interchangeable paths, all at 1920 × 1080:

- **0: Raw** shows RTXDI's unfiltered light samples.
- **1: Spatial** uses Field's surface-guided spatial filter.
- **2: Spatial + DLAA** adds native-resolution neural antialiasing to that filter.
- **3: RR** gives the raw light samples and surface guides directly to Ray Reconstruction.

Stop the camera and light independently. **Cut / reset history** jumps the animation and discards old samples. The raw image remains available throughout; RR does not run after the spatial filter, and an extra DLAA pass does not run after RR.

![The ray reconstruction box's controls: the path slider set to 3 of Raw/Spatial/Spatial + DLAA/RR, Move light and Move camera both checked, and a hold-to-press Cut / reset history button](assets/ray_reconstruction/controls.png)

<video controls muted loop playsinline preload="metadata" src="assets/ray_reconstruction/comparison.mp4" width="3840" height="2160" style="max-width:100%;height:auto;aspect-ratio:16/9"></video>

Top left: raw. Top right: spatial. Bottom left: spatial + DLAA. Bottom right: RR. The four panels follow the same animation, with a camera cut halfway through and an instance edit later. Use fullscreen to inspect disocclusion and shadow boundaries.

## Use it in a renderer

The adapter records into Field's command buffer and consumes Field-owned images. `DlssReconstruction(..., rayReconstruction=true)` selects RR. `evaluate` then requires a `RayReconstructionGuides` value in addition to color, depth, motion, and frame state.

```diffstatic {data-lang=kotlin}
val rr = DlssReconstruction(renderer, size, size, ReconstructionMode.DLAA,
    rayReconstruction = true)

// Outside the render pass, after producing these sampled images:
rr.evaluate(command, ReconstructionInputs(rawLight, depth, motion), frame,
    RayReconstructionGuides(diffuseAlbedo, specularAlbedo, normalRoughness,
        camera.modelView(), projection.evaluateUniform()))
```

The guides have to describe the light you are handing over. This experiment's RTXDI bridge is Lambertian, so its specular albedo is zero and its roughness is one — read those as this bridge's values, not as the two you can leave out when you have no glossy data. Glossy and path-traced RR are still outside this example.

Normals are world-space unit vectors, packed with linear roughness in alpha. Diffuse and specular albedo are linear RGB. Depth uses Vulkan's standard 0–1 projection, with background at one. Motion points from the current pixel to its previous position, in input pixels, without jitter. Camera matrices are unjittered. Fabricate now applies raster jitter separately so its motion vectors retain that meaning. Specular hit distance is optional and is not fabricated for this diffuse scene.

## Cost and limits

On this RTX 4090, native 1080p RR costs **6.22 ms median / 6.23 ms p95** — against 1.34 ms for the DLAA pass after spatial filtering, which is the alternative finish you are choosing between. The SDK reports about 94 MB of shared allocations on top of Field's lighting and guide images. Those timings are at this resolution and sample budget and exclude presentation, so re-measure rather than scaling them.

RR is not the closest still image here. Against a 4,096-candidate static reference, RGB MSE is 0.000150 for raw lighting, 0.000047 for RR, and 0.0000091 for the spatial filter — so in this simple diffuse scene the cheap filter wins on a frozen frame. Movement, disocclusion, and your intended look are why you would still compare the paths. The [measurement file](assets/ray_reconstruction/measurements.json) records the per-pass timings, SDK memory and errors behind all of that.

RR can make noisy illumination substantially smoother, but history can also soften intentional discontinuities. Reset after a cut or semantic edit, and inspect motion as well as still frames. This first bridge carries opaque mono direct lighting and nothing else: alpha coverage, transmission, glossy reflections and latent pigment channels each need a signal contract that does not exist yet.

Use the same optional [NGX installation](neural_reconstruction.md#optional-installation) as DLAA/SR. RR availability is queried separately, including its required extensions before device creation. Ordinary rendering remains available when the SDK or feature is absent.

The capstone [From modules to reconstruction](reconstruction_plumbing.md#extend-the-graph-to-ray-reconstruction) exposes the five-guide conversion and command ordering in the **rr_plumbing** Starting Point, ready to adapt to your own render targets.

For multi-bounce lighting from Field’s existing path tracer, see [Path-traced reconstruction](path_traced_reconstruction.md).
