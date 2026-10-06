# First-run welcome tool

A standalone Kotlin/JVM 17 application, built independently of Field with its wrapper JVM.
It uses the standard Kotlin plugin, JDK classes and bundled JLine/Jansi terminal libraries: no compiler fork, LFS JAR, native bridge,
GPU, Python, JSON library, or GUI toolkit is required to build it.

`./field.sh --setup` / `field.bat --setup` prepares and launches it in the user's terminal.
The launchers also show it on the first interactive launch. The tool never installs packages
without the exact `INSTALL` response, uses argument vectors rather than a shell, and never
requests credentials or AI keys. A package manager may request its normal administrator
credentials. Git LFS recovery has its own explicit `PULL` response. Optional source builds require a
separate `BUILD` response after their task arguments and prerequisites are shown.

It stores choices, not a claim that optional features are configured. Python prerequisites
still require selecting an interpreter and installing JEP in Field's welcome page; Vulkan
diagnostic tools do not install a GPU driver. Without a terminal, it reports prerequisites
and changes neither packages nor preferences.

Run its focused tests with `./gradlew -p tools/first-run test`.

## Demo the welcome

Run `./field.sh --setup` (Windows: `.\field.bat --setup`). It exits after setup without
launching Field. Choose a launch mode, then use Up/Down and Space to select features.
Enter reviews the package and build plan; Enter at the `INSTALL` and `BUILD` prompts
leaves those actions for later. Setup still saves your choices.

Widen the terminal to at least 120 columns for the original wordmark used by `Server.kt`.
Smaller windows get compact lettering. The shared artwork is compiled directly into the
standalone tool, with no extra runtime dependency. The artwork uses cyan
in color-capable terminals. Windows Terminal
supports the color treatment. Pipes, legacy Windows consoles, `TERM=dumb`, `CLICOLOR=0`
and `NO_COLOR` get plain output.

## Optional feature inventory

`OptionalExtras.kt` is the shared menu and preparation inventory. It covers Vulkan, Python/JEP,
computer vision, voice, Ceres, TetGen, paint, libpd, Csound, Manifold, LuaJIT, Syphon,
Slang/learned shaders, NGX, OpenDLSS-NR, Kratos, particle tracking, transport training,
StreamDiffusion, audio AI, agent tools, docs, AI providers, OpenXR, ONNX models, guide/template services and the pinned Dirt audio samples.
The complete checkbox list opens immediately. Up/Down moves, Space toggles, Enter reviews;
Page Up/Down scrolls, `a` selects available features, `n` clears and `q` cancels without saving.
Prerequisites appear on the right of each row. Missing tools disable and strike through a
feature; paint checks Cargo/rustc >=1.85 and an active rustup toolchain. Unsupported platform
entries are omitted, as are NVIDIA-only features when `nvidia-smi -L` fails. Syphon is omitted
from this installer menu. Install missing prerequisites and rerun setup to enable features.

In plain/limited terminals, numbered checkboxes accept numbers or names to toggle, then Enter
to review. Numbers retain their catalog meanings even when entries are omitted. Previously
requested, still-available features start checked. Each selected feature points to its guide.
The terminal selector uses [JLine](https://jline.org/docs/terminal/); Unix uses its `stty` provider,
and Windows uses bundled Jansi. No system terminal library needs to be installed.

Setup can run the existing Ceres, TetGen, paint, libpd, Csound, Syphon and learned-shader
preparation tasks, plus the pinned sample download. It checks their required tools again after any package installation.
Platform restrictions are enforced before constructing a command: Syphon is macOS-only,
and the Csound source-build script is not offered on Windows. Windows native builds need
a Visual Studio Developer PowerShell with `cl.exe` on PATH; the default app launch does not.

Python groups are shown as recipes using `uv sync --inexact` so an existing environment is not
pruned. Configure JEP in the interpreter Field actually uses. Worker environments, model weights,
GPU SDKs, containers, permissions and credentials follow their feature guides; setup does not
silently download models or start services. `extras` records requested choices, and
`preparedExtras` records successful task invocations from this setup run. Neither is a runtime
probe or a promise that a feature is available. Without a console, the menu and prerequisite
report are read-only, and no properties file is written.

Kotlin, JavaScript, Faust, ordinary audio, ONNX Runtime and browser drawing ship with Field.
They do not require selecting an extra; model files and optional GPU inference libraries are
separate. Compiler/runtime downloads remain the launchers’ responsibility.
