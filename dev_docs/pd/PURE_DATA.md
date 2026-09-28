# Pure Data (libpd) in Field — design notes and measurements

Added 2026-09-28. User-facing page: `public/docs/pure_data.md`. Loadable scene:
`dev_docs/pd/pure_data_demo.yaml` (replay with the backdoor:
`macro write pure_data_demo --file dev_docs/pd/pure_data_demo.yaml` then `macro run pure_data_demo`).

## Pieces

| file | what |
| --- | --- |
| `tools/build_libpd_native.py`, `./gradlew buildLibpdNative` | shallow clone of libpd tag `0.16.1` + `pure-data` submodule into `~/Library/Caches/Field4/native-downloads/libpd/` (per-user, shared by worktrees, survives `clean`), verified against pinned commits (libpd `ba0dc632…`, pure-data `f009fd8d…`), cmake build with `PD_MULTI=ON` into `build/field-libpd/build/libs/libpd-multi.dylib`. ~1 min cold on an M-series Mac; `otool -L` shows only libSystem. |
| `src/jvmMain/kotlin/afield/pd/PdNative.kt` | `PdLibrary` (locate: `-Dafield.pd.library`, `AFIELD_PD_LIBRARY`, then `build/field-libpd/…`) and `PdNative`: FFM downcalls for `z_libpd.h`, upcall stubs for the print/bang/float/symbol/list/message hooks. No wrapper library. |
| `src/jvmMain/kotlin/afield/pd/PdInstance.kt` | `PdInstance` (one `t_pdinstance`), `PdPatch`, `PdNode : StereoNode` — the seam entry. |
| `src/jvmMain/kotlin/afield/pd/PdPatchGraph.kt` | the patch text as a graph (object indices as Pd counts them, subpatches as one object), line descriptions, FLine drawing, `statementsOnly`. |
| `src/jvmMain/kotlin/afield/pd/PdLanguage.kt` | `PdExecution`: Pd as a Main Language; message lines as fragments; LineNotes provider. |
| `tools/build_zensical_docs.py` | `libpd_library_path` and the `libpd` entry in `NATIVE_PREREQUISITES`, so a docs fence can say `requires="libpd"`. |
| `src/jvmMain/kotlin/afield/four/synth/PdBox.kt` | box API in the box import set: `pd(text|file)`, `PdLaunch`, `pdControls`, `pdGuiControls`, `pdWaveform`, `renderPdWav`, the return-value handler, and `pdBox(name)` / `Box.pdSend(receiver, values…)` for sending from Kotlin to a *Pd box's* running patch. |
| `src/jvmMain/kotlin/afield/pd/PdObjects.kt` | typo hints: reads `pd-objects.txt`, which `build_libpd_native.py` writes beside the library from every `class_new` / `class_addcreator` name in the pinned `src` and `extra` (297 names at 0.16.1, internals removed), and suggests the signal/control twin, a case fix, or the nearest names within two edits (one for ≤3 letters). |

## Thread discipline

libpd is not thread-safe. In a `PD_MULTI` build the *current* instance is a thread-local inside libpd,
so every call is made under the `PdInstance`'s own lock after `libpd_set_instance`.

- Messages to Pd are queued lock-free (`ConcurrentLinkedQueue` of closures) and drained by whichever
  thread calls `render` — the `FieldAudioEngine` thread live, the caller offline — at the top of the
  next block, in order. A slider on the main thread never enters libpd.
- libpd's hooks fire on the rendering thread, inside `libpd_process_float` or a send. They only queue
  (and must not throw: an exception through an FFM upcall aborts the VM, so every hook is wrapped).
  `poll()` delivers them on the thread that asks. The box launch loop polls every main-loop frame, so
  receive lambdas run on `field-runloop-main` — observed live, see below.
- Structural calls (open/close patch, arrays) take the render lock briefly.
- `close()` takes the lock, frees the instance, then closes the arena holding the upcall stubs. A
  render that arrives after close outputs silence rather than touching freed memory, so the order in
  which the audio registration and the instance are closed does not matter.

Print output arrives in fragments; lines are reassembled on `\n` in Kotlin rather than with libpd's
`z_print_util` concatenator.

## Measurements (jvmTest `afield.pd.PdTest`, 13 tests, and `PdPatchGraphTest`, 6)

All run against the built library on 2026-09-28; they print `SKIPPED` and pass when it is absent.

- `[osc~ 440] → [*~ 0.25] → [dac~]`, 48 kHz, 1 s: **440** upward zero crossings (asserted ±1), peak
  0.25 and RMS 0.25/√2 (asserted ±0.002), L == R bit-for-bit.
- Rendering 300 frames as 1+63+100+136 equals one 300-frame render sample-for-sample (Pd works in
  64-frame blocks; the remainder carries over).
- `[r in] → [+ 1] → [s out]`: send 41 → receive 42.0; nothing is delivered on the sender's stack
  before `flush()`/`render`.
- Arrays: write at an offset, read back, resize; a patch's `[tabplay~]` plays back a ramp Field
  wrote, exact to 1e-6 per sample.
- `[adc~] → [*~ 0.5]` / `[*~ -1] → [dac~]` with two inputs: output equals the scaled input on the
  same samples (1e-6), so there is no block of latency inside libpd.
- MIDI: `noteOn(0, 64, 90)` reaches `[notein]` (pitch 64), `controlChange(0, 7, 100)` reaches
  `[ctlin 7]`, and a list `60 100` into `[noteout 1]` arrives in `onMidi` as channel **0** — libpd
  counts channels from 0 where Pd's objects count from 1.
- Four threads sending 2000 numbers each while a fifth renders: all 8000 arrive, in per-sender
  order (four runs).
- Two instances with the same `[r in]`/`[s out]` names are isolated.
- A patch with `[osc 440]` reports `Pd couldn't create [osc 440] — did you mean [osc~ 440]?` on line 1
  of its source (0-based; the editor shows it on the object's own line). `PdObjectsTest` (6) pins the
  suggestions against a fixed catalog and checks the built one names `osc~`, `r`, `t`, `hsl`, `expr~`.
- A headless `hsl` still forwards what it receives (and does **not** clip it to its range — 5000 in,
  5000 out, for a 100–1000 slider).

Live, through the backdoor on port 9530 (a Kotlin box `pd(...)` with a `[metro 250]` counter sent to
`[s tick]`): `pong 42.0` and `Pd: got: 42` in the box output, ticks arriving at four per second on
thread `field-runloop-main` (so the audio engine was pulling blocks in real time), and none after
`stop`. The demo scene's six boxes were each run: see the worklog of that night.

Live, 2026-09-28 morning, on port 9610 (a fresh replay, `applied=13 skipped=0 errors=0`): the typo box
sends Pd's two console lines, then the error on line 3 with the hint, drawn red. `pdBox("drone")
.pdSend("freq", 277)` and then `123` from a scratch box were read back exactly from the drone's
`[r freq]` line note; the launched arpeggio box stepped the same note through 165, 220, 330, 440, 330
and held after `stop`.

## Traps hit

- **The editor clears a box's errors whenever the box prints.** Pd's problem was first painted, then
  Pd's own console echo (`verbose(0): osc 440`, `error: ... couldn't create`) arrived as normal output
  and wiped it; and the problem's 0-based line was passed where the editor counts 1-based from
  `Execute.line`, so it would have landed a line early. `PdExecution.open` now delivers the console
  first and paints problems last, at `firstLine + line + 1`.

- **`val (left, _) = …` at the top level of a box never completes** under the K2 REPL: no compile
  error, no output, and the backdoor reports "Execution did not complete". `val (left, right) = …`
  works. This is a REPL defect, not a Pd one; the demo avoids it and says so in a comment.
- Pd 0.56 prefixes each line of a creation error with `error: ` and sometimes `verbose(0): `, then
  prints `... couldn't create` on its own line. `PdPatch.problemsIn` strips both prefixes.
- A `.pd` file is a sequence of messages: a bare `freq 440;` statement in one would be *sent* while it
  loads. The Pd box language keeps message lines and `//` comments in the box, so
  `PdPatchGraph.statementsOnly` blanks every statement that does not start with `#` (continuation
  lines of a `#` statement are kept) before Pd sees the text, line for line.
- `audio(pd.node)` with no `gain` is ambiguous between the `Float` and `Param` overloads — spell
  `gain = 1f`.
- Adding a path to `SoundPathMap` moves prose numerals on `sound_paths.md`, `sound.md` and
  `chain_pattern_particles.md` that no guard reads; they were moved by hand (nine sources, twelve of
  eighteen on the seam).

## Not done

- MIDI is bound (note, controller, program, bend; not aftertouch, poly aftertouch or raw bytes) but
  not connected to Field's MIDI devices.
- Compiled externals are not loaded (Pd's `extra` is compiled in).
- Only one figure on `pure_data.md` needs libpd (`requires="libpd"`); `buildDocsNativePrerequisites`
  builds it. There is no audio clip on the page, so nothing was added to `DocsAudioProvenance`.
- Windows/Linux: the build script and library naming are written for them and untested.

## Docs captures (added 2026-09-28, second pass)

`public/docs/pure_data.md` has five pipeline captures under `public/docs/assets/pure_data/`:
`offline-saw.png`, `drone-patch.png`, `typo.png`, `line-note.png` and `send-from-kotlin.png`.

- `typo.png` is an ordinary `capture="editor"` fence with `data-lang=pd`. The runner puts it under a
  `mainLanguage` root because `tools/field_languages.py` lists `pd`, and it carries
  `expect-error=true`.
- The two line-note figures are `capture="ui"` drives. Their setup transcripts are in
  `dev_docs/agents/transcripts/docs_capture/pd_*.yaml`: they launch the Pd box and run a message line
  or the Kotlin sender. **Trap:** the runner's end-of-drive capture re-opens the editor, which resets
  the cursor, and the note goes with it. So the still has to be taken inside the script, via
  `clip-selector=".monaco-editor-container"`. The editor is shortened to 600px first, so the note (at
  its foot) is not a page below the patch.
- `PdPatchGraph.toFLines` spaces ports as Pd does: the first flush left, the last flush right. The
  count for each box is the larger of `KNOWN_PORTS` and what the patch wires. Before this, every port
  was drawn a few pixels from the left edge.
