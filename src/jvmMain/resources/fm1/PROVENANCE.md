# fm1-sim.wasm

M-VAVE FM-1 browser simulator, compiled from the open firmware source.

- Source: https://github.com/m-vave/fm1_open, commit `4c7496daac04a95ff10d1afdcc8f9ead2dbabc41`
  ("FM1 Open V14"). That repository is the corresponding source for this binary.
- License: the APP sources are `GPL-3.0-only WITH GPL-3.0-linking-exception` (LICENSE.md in the
  repo); the bundled voice engines are MIT (Open303, DaisySP, stmlib, emu2149, SEA) and Apache-2.0
  (MSFA/Dexed FM). See the repo's THIRD_PARTY.md. The closed `sdk/lib/libfm1_engine.a` is **not**
  linked into this module; the web build never uses it.
- Built 2026-11-14 on macOS arm64 with Homebrew clang 23.1.3 + `wasm-ld` 23.1.5 against the WASI SDK
  27 sysroot and compiler runtime, with the flags of the repo's `tools/Build-Web.ps1`:

```
clang --target=wasm32-wasip1 --sysroot=$WASI/share/wasi-sysroot -resource-dir=$WASI/lib/clang/20 \
  -std=c11 -O2 -fwrapv -fno-fast-math -ffp-contract=off -DFM1_HOST_TEST -DFM1_OPEN_WEB \
  -Wall -Wextra -Werror -Wno-unused-function -Wno-array-parameter -Wno-unused-command-line-argument \
  -mexec-model=reactor -Iweb/engine -Iweb/synth -Ithird_party/synth -Isdk/include -Iapp/synth -Iapp \
  $(find app web third_party -name '*.c' ! -path 'app/board/*') \
  --ld-path=wasm-ld -Wl,--strip-all -Wl,-z,stack-size=131072 \
  -Wl,--initial-memory=4194304 -Wl,--max-memory=33554432 -o fm1-sim.wasm
```

The module has no imports. It renders stereo at a fixed 44,100 Hz in 128-frame blocks
(`sim_render`), and is driven through the `sim_*` exports in `web/engine/Simulator.c`.
