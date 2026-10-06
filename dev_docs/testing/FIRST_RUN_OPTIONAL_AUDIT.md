# First-run optional-feature audit — 2026-10-05

Audited after syncing upstream through the landing queue to main `f9c40bd9a`, retaining the
previous Windows launcher repairs. Work was isolated from the primary checkout's browser/GPU
topic branch and untracked `graphify-out/`.

The inventory lives in `tools/first-run/src/main/kotlin/afield/setup/OptionalExtras.kt`.
Its first screen keeps Vulkan/Python and Enter to skip; `more` reveals all 27 choices. Names and
numbers select the same entries. Every entry has a checked-in guide. Faust, ordinary audio,
Kotlin/JS and browser drawing stay on the bundled path; ONNX Runtime is bundled too, with vision
model weights downloaded on first use. Voice gained an actual setup guide in
`python/fieldvoice/README.md`.

Preparation tasks offered with separate BUILD confirmation: Ceres, TetGen, paint, libpd,
Csound, Syphon, learned/training shaders and the pinned Dirt audio sample subset. System package installation retains INSTALL and
LFS recovery retains PULL. Browser-only mode still needs native toolchains when a selected
optional library uses them. Windows commands use the batch wrapper through cmd.exe; native
builds require Developer PowerShell/MSVC. Unsupported preparations are filtered before command
construction. Worker/model/SDK/interpreter/permission steps remain explicit feature recipes.

Checks report missing tools on PATH, checkout free space, compiler LFS pointers and local native
artifacts where their standard output/override paths are known. They do not load libraries,
import optional Python packages, start services, run GPU probes or claim inference success.
Saved `extras` are requested choices; `preparedExtras` records successful task invocation in
this run. Core completion remains separate from optional runtime availability.

## Verification

- Standalone Kotlin/JVM tests: 13 passed. Covered package plans, Ubuntu/Debian t64 transitions,
  Rust 2024 toolchain checks, native prerequisites in browser-only mode, selection validation, Windows wrapper arguments,
  unsupported-platform filtering, model setup isolation, native override/output paths, and
  every inventory guide/preparation task existing in this checkout.
- Real Linux terminal: chose browser-only + TetGen, skipped system package installation and
  approved BUILD. The actual pinned source download, CMake compile and Gradle task succeeded;
  setup found `libfield_tetgen_jvm.so` and saved `preparedExtras=tetgen`. No TetGen runtime result
  is inferred from compilation.
- Real Linux terminal: compact menu -> more -> Syphon. Full inventory appeared; unsupported
  Syphon supplied guidance, proposed no package installation and no BUILD, and saved the choice.
- No console: full read-only inventory, no properties file created. The real
  `./field.sh --setup --server-only` path also completed without a console.
- HTML docs build in mode=never passed. Existing captures were retained; no media was regenerated.

Native Windows/macOS menu execution and installation of every optional dependency were not
performed here. Cross-platform planner tests are not evidence of those runtime results. No
system packages, models, API keys or user setup preferences were changed during verification;
terminal checks used isolated properties files under `/tmp`.

The paint crate declares edition 2024. BUILD now checks Cargo and rustc versions before
starting paint preparation, rejecting pre-1.85 tools even when their executables are present.
The edition threshold is documented in the [official Rust 1.85 release](https://blog.rust-lang.org/2025/02/20/Rust-1.85.0/);
current stable toolchains are advised because transitive dependencies can impose higher requirements.

Git LFS detection now accepts a successful `git lfs version` when `git-lfs` itself is not
on PATH, matching the launcher and Git for Windows’s bundled-command resolution.

TetGen, libpd, Csound and Dirt-Samples Gradle preparation tasks previously hardcoded `python3`.
They now select `python` on Windows, matching the installer’s checks and Ceres’s existing
platform choice. Separate guide-chat/template services are also listed, with no automatic startup.

After the platform-Python task repair, an explicit Linux rerun of `buildTetgenNative` and
`fetchDocsDirtSamples` passed. The latter verified all 114 pinned files from the existing shared
cache (no sample files were downloaded). The Git exec-path fallback was exercised on Linux with
a PATH containing Git but no git-lfs executable; the real subcommand was recognized correctly.
