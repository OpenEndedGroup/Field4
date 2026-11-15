# FM-1 simulator: decisions and open items

`FM1Sim` (`src/jvmMain/kotlin/afield/audio/synth/FM1Sim.kt`) hosts the M-VAVE FM-1's open-firmware
browser simulator (`src/jvmMain/resources/fm1/fm1-sim.wasm`, built from github.com/m-vave/fm1_open
at `4c7496d`) in a GraalVM js+wasm context. The user page is `public/docs/fm1.md`; this file holds
what a user does not need: why things are the way they are, and what was left and why.

## What the second round did (2026-11-14)

- **MIDI in, by track.** Every track listens on a channel (`sim_midi_setting(track, 1)`); notes,
  CCs, bend and program changes go through `sim_midi` (bytes written to `sim_file()`). The
  firmware's input queue holds 64 messages per block; a 64-note chord in one block would overflow
  it and the firmware resets its MIDI state. Nothing in Field sends that much.
- **Scheduling on the simulator's block clock.** Calls carry an absolute due block and sit in a
  priority queue drained before each `sim_render`. Timing resolution is one simulator block
  (128 frames, 2.9 ms): the firmware renders whole blocks and has no sub-block event time, so this
  is the floor without changing the C.
- **Synchronous reads.** `tracks()`, `screen()`, `exportProject()`, `voices()` take a lock that the
  audio thread also holds while it renders, so they run between two blocks. GraalVM forbids
  *simultaneous* entry from two threads, not sequential entry; the lock makes it sequential. A
  read holds the audio thread for the time of one memory copy (115 kB for the screen).
- **Start/stop are MIDI FA/FC**, not the PLAY toggle. Note: if a track's MIDI clock-out is ever
  turned on, `groove_midi_clock_accept` rejects an incoming Start, so `start()` would stop working.
  Nothing in Field turns clock-out on.
- **Factory projects: 16**, not the 4 the first page said (`sim_factory_name` names 0..15).
- **Patterns** via `fm1NotesForArc` / `FM1PatternFeeder` (`FM1Patterns.kt`), the shape of
  `faustNotesForArc` / `FaustPatternFeeder`. Drum names map to the 808 notes in
  `web/engine/sound.c` (`drum_notes`). Pitch is rounded: the DX7 core has a per-note `cents`
  argument but the MIDI path always passes the tuning table's value.
- **Signals turn controllers** (`modulate`), sampled once per audio block inside `FM1SimNode`, on
  the audio thread, with the real `SynthContext`, so it obeys the AGENTS.md rule about synth
  graphs being audio-thread-owned.
- **DX7 voices** (`FM1Voice.kt`): VCED/VMEM codec matching `groove_fm_unpack`/`groove_fm_pack`,
  single-voice SysEx (163 bytes, accepted by `app/transfer.c`), 32-voice banks into
  `sim_library_write`. Values past a parameter's range are held at its end because the firmware
  would otherwise reject the whole voice (the only failure the clamp guards).
- **MIDI out** via `sim_midi_capture` and per-track mode (`sim_midi_edit(track, 0, …)`: 0 off,
  1 local + MIDI, 2 MIDI only). The web build mirrors to ports 1 and 2; Field keeps port 1.
- **Resampling** is Catmull-Rom four-point instead of linear. Exact at 44.1 kHz (tested), two
  simulator frames of extra latency. This changed every rendered sample, so the two original
  clips were recaptured.
- **Real-time cost**: `FM1SoundDocs.realtimeFactor()` prints the factor in the
  `synth.fm1.sound` smoke test.

- **Field audio on the sampler's pads** (`FM1Samples.kt`, `FM1Sim.sample`). The bank layout comes
  from `app/samples.c`: a 512-byte header (`FM1S`, version 1, size, CRC-32 over the bank with bytes
  12..19 as zero, commit word 0, rate 32000, sixteen `offset/frames/bytes/name[16]` entries packed in
  order, entries 8..15 empty), then SEA5 blocks of 228 bytes per 320 frames. Compression uses the
  firmware's own `sim_sample_encode`, so Field does not carry a second SEA5 encoder. A bank is
  capped at `GROOVE_SAMPLE_LIMIT` (446,464 bytes, about 19.5 s), not the region's full 466,944,
  because a larger header size makes `groove_fm_library_init` block the DX7 voice library and
  `tuning_blocked` refuse tuning writes. The new bank is written to `sim_flash() + 69632` with
  `writeBufferLong` (polyglot buffers have no bulk write), then `sim_sample_bank` re-reads it. If the
  firmware refuses it, the old bytes go back, because `groove_samples_init` has already unbound every
  pad. The factory bank rebuilds byte for byte through `FM1SampleBank.parse`/`bytes`. The pad
  notes are `sample_notes` in `web/synth/sample_metadata.inc`.

- **Building and swapping firmware** (`FM1Firmware.kt`, `FM1Sim.loadFirmware`). The WASI SDK 27
  release's own clang 20 and `wasm-ld` build the firmware in about 4 seconds warm (11 s cold); the
  built-in module was built with Homebrew clang 23 against the same SDK's sysroot, so the bytes
  differ (295,723 bytes against the shipped 288,774), but the result plays the factory projects. The SDK
  archives are pinned by the SHA-256 GitHub publishes for each release asset (arm64-macos verified
  against a local download, x86_64-windows against `tools/Build-Web.ps1`'s own pin). A swap copies
  the whole 536,576-byte flash (`GROOVE_FLASH_BYTES`) to the new module, boots it with
  `sim_boot(0)` so it reads the library, samples and settings back out, then imports the live
  project (unsaved edits included), re-applies each track's channel and MIDI-out mode, the knob,
  MIDI capture, and sends `FA` if the sequence was playing. Both modules are entered only under the
  render lock, so the audio thread sees one or the other, never neither. `FM1Sim.FIRMWARE_EXPORTS`
  lists the `sim_*` exports Field calls; a module lacking any is refused by name at load. The
  `synth.fm1.firmware` smoke test (tagged `network`) builds the source, checks a broken build's
  message names the file, and that the `firmware-swap` clip is identical to the unswapped one
  before the swap.

- **StackSL hooks** (`FM1Hooks.kt`, `FM1Sim.hook`, `fm1/fm1-glue.js`, `fm1/glsl_prelude.hpp`). Five
  edits of the firmware source (`FM1Hooks.PATCHES`): an imported `field.track(track, left, right,
  frames, clock)`, a frame clock bumped at the top of `render()`, a call after each track's voice in
  both `render_stereo` and the mono path of `render` (the firmware picks per block; a hook in only
  one goes silent whenever the mix turns mono), and `sim_field_hooks(mask)` so unhooked tracks never
  leave the module. The hookable build is cached in `~/.field4/fm1/fm1-sim-hooks-<key>.wasm`, the
  key a digest of the commit, patches and flags. The JS glue instantiates the firmware with
  `field.track` when the module imports it, copies the block into the hook's own memory (so a bad
  hook cannot write over the firmware), calls it, and copies back; a trap drops the hook and keeps
  the message. All voices use 2^24 as 1.0 (303, pluck and `voices.c` scale by 16777216 before
  `fm1_mix128`). StackSL's GLSL becomes C++ by two rewrites (constructors `vec4(`→`vec4_(`, `f` on
  float literals) plus the prelude: clang `ext_vector_type` gives swizzles and arithmetic, overloads
  give GLSL's built-ins, structs with `operator*` give the `mat2`/`mat3` StackSL's noise library
  multiplies by. C was tried first and fails on the matrices. A hook builds in ~0.1 s (0.13 s
  measured in a box, 0.31 s for the first, which also loads the hookable firmware).

## Not done, and why

- **Per-track stems.** The firmware mixes the eight tracks (and their reverb/delay sends) inside
  `render_stereo`; `sim_render` returns the mix only. Stems need a firmware change (an export of
  the per-track scratch buffers) and a rebuild, or eight simulators each soloing one track, which
  costs eight times the CPU and loses the shared effects.
- **MIDI clock both ways.** FM-1 → Field: turn on clock-out (setting 5) and read `F8` from
  `drainMidi()`; but that disables `start()` (above). Field → FM-1: set clock source (setting 6,
  which also switches the clock mode to MIDI) and send `F8` 24 times a beat on the block clock.
  `cps` (from the project's bpm) covers the common case of a Field pattern in time with the FM-1's
  sequencer, so the clock was left.
- **Microtones.** The project format carries a 200-byte tuning block (`GROOVE_TUNING_BLOCK_BYTES`)
  that `importProject` keeps. Writing one from Field means decoding `tuning_tables_valid` in
  `app/project.c`.
- **Comparison with the hardware.** Still none; nobody here has an FM-1.
- **Recording into a pad live.** `sample(pad, audio, rate)` takes a finished buffer. Taking a
  box's output as it plays would need a tap on the audio thread that hands frames to a
  non-audio thread, then one `sample` call at the end. Writing the bank holds the audio thread for
  tens of milliseconds, so it cannot happen block by block.
- **Per-pad tune, level, length and choke from Field.** These are the sampler engine's pots
  (`sampledrum_set`), reached only through the panel's EDIT page; the simulator exports no setter.
  `encoder()` on that page reaches them, but by screen position, not by name.
- **A panel drawn like the device.** `fm1Panel` (`FM1Panel.kt`) shows the LCD live, redrawn when
  `sim_frame_revision` moves (at most `maxFps` a second), with the panel buttons, 27 keys and
  encoders as rows of `controlGroup` hold-keys. Clicks on the drawn screen itself, dials that turn by
  dragging, and the 41 LEDs (`sim_state` bytes 168..208) laid out where the device has them would be a
  custom HTML output (an image map over the PNG, with its own `send`), not more Kotlin.
