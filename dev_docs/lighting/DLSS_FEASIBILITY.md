# DLSS feasibility, 24 September 2026

The actual native NGX feature-discovery probe reports support for SR, RR and FG on this Linux RTX 4090.
This is stronger than finding a shared library and weaker than a rendered sample: no DLSS evaluation
or reconstruction image/clip has been produced. RTXDI and Field direct lighting do not depend on DLSS.

Pinned public SDK: NVIDIA/DLSS `374959484e79a640feaba44c93ac8cfb0a03f5b5`, binary version 310.9.1,
NGX API enum 0x15 (1.5.0). Driver/runtime installed: 610.43.02, including `/usr/lib/libnvidia-ngx.so.1`
and optical-flow runtime. Public SDK contains native Linux `libnvsdk_ngx.a` and `libnvidia-ngx-dlss`,
`dlssd`, `dlssg` shared libraries. These are kept outside the product under `build/`; no runtime installed or driver upgraded.

| Feature | Public API / platform evidence | Inputs and ownership | Result here |
| --- | --- | --- | --- |
| Super Resolution | NGX Vulkan init/create/evaluate, `NVSDK_NGX_Feature_SuperSampling`; Linux documented in SR guide | Linear color, depth, motion, jitter, exposure, render/output size, reset flag; application owns images and command submission | `GetFeatureRequirements`: result=0x1, supportFlags=0, minArch=352, minOS=any. No evaluated frame. |
| Ray Reconstruction | `NVSDK_NGX_Feature_RayReconstruction`, public Vulkan helpers and Linux `dlssd` | Noisy color, depth, motion, normals/roughness, diffuse/specular albedo and hit-distance inputs per mode; reset/jitter/exposure and camera transforms | result=0x1, supportFlags=0, minArch=352, minOS=any. No evaluated frame. |
| Frame Generation | `NVSDK_NGX_Feature_FrameGeneration`, public Vulkan helpers and Linux `dlssg` | Consecutive color, depth, motion, HUD-less/UI resources; feature-specific scheduling, frame identity and lifetime through presentation | result=0x1, supportFlags=0, minArch=400, minOS=any. No evaluated frame or presentation adapter. |
| DLSS 5 neural rendering | No neural-rendering feature enum, Vulkan entrypoint or Linux neural-rendering binary found in this pinned public SDK | Public input/ownership contract for this target not established | Blocked at public API availability; no binding invented and no 4090 support inferred. |

The FG guide's top-level requirements still name Windows, while its linking section names Linux and
the SDK now ships a Linux FG binary. Streamline's FG guide 2.14.1 similarly mixes Windows integration
requirements with Linux Vulkan optical-flow guidance. Report this discrepancy rather than declaring
native FG unsupported or production-ready. The direct NGX runtime query above answers availability
on this machine; actual evaluation and presentation remain untested. The 4090 is in the FG guide's
single-generated-frame table; multi-frame generation is separately listed for RTX 50 series.

Use `NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements` and the corresponding device
extension query BEFORE creating an integration device, followed by feature requirements/capability
parameters. Creation/evaluation records GPU work into the caller's command buffer. Inputs must be in
sampled/readable state, output storage-writable, and lifetimes cover GPU completion; restore pipeline
state after evaluation. FG also needs an explicit scheduling/presentation integration: do not insert it
as an ordinary color filter or assume optical-flow support supplies presentation synchronization.

The standalone discovery probe in `tools/lighting/ngx_probe.cpp` uses a custom project identifier and
the SDK's actual declarations. It creates only a Vulkan instance and queries each physical device;
it does not create a feature or edit the driver. Reproduce after obtaining this SDK:

```bash
g++ -std=c++17 tools/lighting/ngx_probe.cpp \
  -Ibuild/lighting-experiment/upstream/DLSS/include \
  build/lighting-experiment/upstream/DLSS/lib/Linux_x86_64/libnvsdk_ngx.a \
  -lvulkan -ldl -lpthread -o build/lighting-experiment/ngx_probe
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json \
  build/lighting-experiment/ngx_probe build/lighting-experiment/upstream/DLSS/lib/Linux_x86_64/rel
```

SDK access is public at this commit, under NVIDIA's RTX SDK license. This is not an open-source
redistribution assumption; no binaries are bundled. The DLSS repository says its sample is in release
archives, not in the repository. No sample evaluation is claimed. A later optional adapter should retain
source output and bypass, explicitly translate Field motion conventions, and compare thin lines,
particles, flat/unusual colors and deliberate cuts without smoothing away intended changes.
There is no established custom/user-trained-weight path in this SDK.

Sources checked: [SDK and exact revision](https://github.com/NVIDIA/DLSS/tree/374959484e79a640feaba44c93ac8cfb0a03f5b5),
[developer entry point](https://developer.nvidia.com/rtx/dlss),
[Streamline FG guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_G.md),
[SDK license](https://github.com/NVIDIA/DLSS/blob/374959484e79a640feaba44c93ac8cfb0a03f5b5/LICENSE.txt).
The SR, RR and FG PDF guides in that exact SDK were read locally. KosmicKrisp's
[build/runtime documentation](https://docs.mesa3d.org/drivers/kosmickrisp.html) does not establish these vendor features.
