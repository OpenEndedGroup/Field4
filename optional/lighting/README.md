# Optional lighting toolchains

`RTXNS/` is a Git submodule pinned to NVIDIA RTX Neural Shading SDK 1.5.0,
`f309ae68677a67060cc6ded2d0af925a3931435f`. Its source belongs here, outside Gradle's disposable
`build/` tree. The SDK remains under its upstream license; follow its license terms when using or
redistributing SDK components.

## Learned shaders

From the Field checkout:

```sh
./gradlew buildLearnedShaders
```

This explicit optional task:

1. Initializes the recorded RTXNS submodule if absent, without changing an existing checkout's
   revision or overwriting local edits.
2. Downloads Slang 2026.10, the version selected by this SDK, and checks the archive SHA-256 from
   `toolchains.json`. Hashes come from the official Slang release asset metadata.
3. Caches the compiler through `tools/native_download_cache.py`, the same per-user cache used by
   Ceres/TetGen. `AFIELD_NATIVE_DOWNLOAD_CACHE` overrides its root. Downloads and the source
   checkout survive Gradle `clean` and are reused on later invocations.
4. Compiles both scalar and cooperative-vector variants before replacing either working binary.
   Outputs and provenance are `build/lighting/learned-shader/{learned-scalar.spv,learned-coop.spv,learned-build.json}`.
5. Writes `build/lighting/slangc.path`, a checkout-local pointer to the cached compiler. Field's
   existing `SlangCompiler` uses it when no compiler is found on `PATH`. `AFIELD_SLANGC` and
   `-Dafield.slangc` still take precedence; runtime compilation never downloads anything.

Normal `build`, `check`, and Field startup do not run this task or download the SDK. No CMake sample
configure, DXC download, system package or driver change is needed for Field's inference shaders.
The compiler and NVIDIA SDK binaries are not copied into the repository.

After the first preparation, `./gradlew buildLearnedShaders --offline` works from the initialized
submodule and verified cache. Missing/corrupt cache data produces an explicit error without networking.
The build recipe selects pinned compiler archives for Linux x86-64/AArch64, Windows x86-64, and macOS
x86-64/Apple Silicon. Only Linux x86-64 has been executed here. A compiler platform does not imply
cooperative-vector support on that GPU; Field still queries enabled Vulkan features and retains its
ordinary arithmetic path.

Field already supports live Slang through `vertex_fragment`, `vertex_s`, `fragment_s`, and
`compute_s`. This task prepares a compiler those existing paths can reuse; it adds no new runtime.
The learned kernels are separately compiled straight to SPIR-V with `sm_6_6`, a scalar/cooperative
define, and `spvCooperativeVectorNV` for the cooperative variant. The ordinary `compute_s` route
currently emits GLSL first, so it is not interchangeable with that kernel recipe.

To inspect upstream samples, initialize their nested submodules separately:

```sh
git submodule update --init --recursive optional/lighting/RTXNS
```

Field's shader build needs only the root SDK pin and compiler, so it does not fetch the sample
renderer's transitive dependencies. The earlier instrumented upstream smoke checkout under
`build/lighting-experiment/upstream/RTXNS` is historical evidence, not a build dependency.

Update the RTXNS gitlink and `toolchains.json` together when deliberately upgrading. Keep the Slang
version consistent with the pinned SDK's CMake configuration and revalidate shader layout/numerics.

Public walkthrough: [A learned shader function](../../public/docs/learned_shader.md).
