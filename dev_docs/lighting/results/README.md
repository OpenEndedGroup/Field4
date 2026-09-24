# Evidence, 24 September 2026

`capabilities.json` and `fallback-capabilities.json` are reports from real Field logical devices on
the RTX 4090. They distinguish physical support from enablement. `measurements.json` is the
100-frame visibility/shading interval distribution from the clip-producing smoke run;
`fallback-measurements.json` is the disabled-ray run. The repeated final checks also passed;
small timing differences between invocations are expected. `verification.json` records final
standalone completion markers and live-document lifecycle checks.

The Vulkan loader reported 1.4.350; the selected physical device reported 1.4.341. Field requests
Vulkan 1.3 in Instance.kt. Application Java was GraalVM CE 25.0.2+10; Gradle 9.5.1 bootstrapped under
Corretto 17.0.19; LWJGL was 3.4.3. The full vulkaninfo and build/run logs remain under the ignored
`build/lighting-experiment/` directory. No system packages or driver were upgraded.

`ngx-runtime.txt` is feature discovery, not DLSS evaluation. `rtxdi-submodules.txt` records the
sample dependency revisions; the leading minus marks the uninitialized Windows-only NVAPI checkout.
No vendor binaries are included here.

Public PNGs and the four-second MP4 are in `public/docs/assets/direct_lighting/`. The PNG readbacks
use explicit Reinhard/gamma display conversion; the native window screenshot uses Field's existing
presentation. The clip contains 120 actual raw-buffer readback frames at fixed simulation steps.
It records moving illumination, not a measurement of interactive frame pacing.

The HTML docs build succeeded (`No issues found`; built local references resolve). The transcript
bundle/index gates passed. The broader transcript-layout guard has the two unrelated failures
listed in `../IMPLEMENTATION.md`; no full certification is claimed.
The separate asset guard also reports two existing orphan capture fences in datalog.md and
petri_nets.md; the new lighting assets have no missing, orphaned or placeholder findings.

`rtxdi-measurements.json` contains the actual Field SDK run: 16-trial scene-mean estimates for
reference/initial/temporal/spatial/full, static/moving history rejection, allocation bytes and
96-frame per-stage distributions for six light/candidate configurations. `rtxdi-program.json`
records source/SPIR-V/DXC hashes and SDK pin; `rtxdi-verification.json` records the completion marker
and zero VUID verdict. The corresponding `rtxdi-*.png` and `rtxdi-moving.mp4` are real GPU captures.
These are raw estimators without a denoiser, at 256x192, on the same 4090/driver.
