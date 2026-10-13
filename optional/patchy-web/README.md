# Optional Patchy Web build

`field-bridge.patch` is the complete Field change to the upstream Patchy source at the
revision in `pin.json`. It contains the iframe RPC and PSD byte bridge; it does not contain
Patchy itself. Regenerate it from a Patchy checkout with:

```sh
git diff --binary b81377425c54ff9eca30e180d3cd9e6f11eb17da HEAD -- \
  CMakeLists.txt src/app/field_web_bridge.cpp src/app/field_web_bridge.hpp \
  src/app/main.cpp src/ui/dialog_utils.cpp src/ui/main_window_ai_selection.cpp \
  > /path/to/Field4/optional/patchy-web/field-bridge.patch
```

Select **Patchy layered painting editor** in first-run setup and approve **BUILD**, or run
`./gradlew buildPatchyWeb` (`.\gradlew.bat buildPatchyWeb` on Windows). The normal Field
build never invokes this task. Python 3, Git and CMake are required. The task creates its
own Python environment with pinned `aqtinstall` and Ninja, fetches the pinned Emscripten
SDK and Qt 6.10.3 host and WebAssembly kits, and builds Patchy with CMake. The Qt and
Emscripten downloads alone occupy several GB. The first build can take a long time.

Everything lives under `~/.field4/patchy-web/`, including the temporary Patchy source
checkout. The served files are in `site/`, with `field-patchy.json` recording the source
revision, patch hash, tool versions and asset hashes. Field discovers this site on its next
launch. `AFIELD_PATCHY_WEB_ROOT` overrides discovery for development builds.

The WebAssembly site is the same on macOS, Linux and Windows; the Qt host tools differ.
The recipe selects Qt's macOS `clang_64`, Linux `gcc_64`, or Windows
`win64_msvc2022_64` kit alongside `wasm_multithread`. Windows needs CMake on PATH;
setup can offer Python and CMake through winget. A developer can check the pin and patch
without toolchain downloads using `python3 tools/build_patchy_web.py --check-patch`.

The staged site carries Patchy's MIT license and third-party notice, including the
libheif license. Its source and build recipe remain available here and upstream.

The manual `first-run` GitHub Actions workflow checks the patch on all three operating
systems. Its `patchy` checkbox additionally runs the full optional WebAssembly build and
checks the staged asset hashes. Routine first-run CI leaves that large build off.
