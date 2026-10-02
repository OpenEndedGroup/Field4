# Csound in Field — design notes

Landed from nightly 2026-10-02 (branch `nightly/2026-10-02/1-csound-bridge`). User page:
`public/docs/csound.md`. Demo: `dev_docs/agents/transcripts/csound_demo.yaml`.

## Shape

The libpd bridge (`afield.pd`, `afield/four/synth/PdBox.kt`) is the template, piece for piece:

| Piece | Csound | Pd counterpart |
| --- | --- | --- |
| native build | `tools/build_csound_native.py`, `./gradlew buildCsoundNative` | `build_libpd_native.py` |
| discovery | `CsoundLibrary` (`-Dafield.csound.library`, `AFIELD_CSOUND_LIBRARY`, `build/field-csound/build/…`, installed framework / `.so`) | `PdLibrary` |
| FFM | `CsoundNative` — downcalls only | `PdNative` (has upcall hooks) |
| engine | `CsoundInstance` | `PdInstance` |
| audio node | `CsoundNode` (a `StereoNode`) | `PdNode` |
| box API | `csound(orc, sco)` → `CsoundLaunch`, `csoundControls`, `csoundOffline` | `pd(...)`, `pdControls`, `pdWaveform` |
| return value | `registerCsoundLaunchReturnValue` | `registerPdLaunchReturnValue` |

Not done: Csound as a box *Main Language* (`PdExecution` / `LuaLanguage`), line notes, completion.

## Build

Csound 6.18.1 from the GitHub tag tarball, sha256 pinned in the script, cached under
`native_download_cache("csound")`. Everything optional is off (frontends, utilities, drivers,
interfaces, OSC/DSSI/FluidSynth plugins). On macOS the product is `CsoundLib64.framework`; the target
name is `CsoundLib64` there, `libcsound64` on Linux. It links Homebrew's libsndfile by absolute path
(`/opt/homebrew/opt/libsndfile/lib/libsndfile.1.dylib`), so this build is not redistributable. A
Linux build is untested; Windows is refused by the script (use an installed `csound64.dll`).
System flex/bison (bison 2.3 on macOS) are enough for 6.18. Csound 7 needs bison 3.

`CsoundNative.ensureInitialised` calls `csoundInitialize(NO_SIGNAL_HANDLER | NO_ATEXIT)`, because the
JVM owns both, and points `OPCODE6DIR64` at an empty temp directory so an installed Csound's plugin
opcodes are never loaded into this library.

## Threading (AGENTS.md "Runloop vs audio loop")

- `csoundPerformKsmps` runs only inside `CsoundInstance.render`, under the instance lock. Live, that
  is the `FieldAudioEngine` thread via `CsoundNode`; offline, the caller.
- `event`/`compile` are queued and delivered at the top of the next `render`.
- Control channels go straight to `csoundSetControlChannel` / `csoundGetControlChannel`, which
  Csound guards itself. Channel name C strings are cached in the instance's shared arena.
- Messages: `csoundCreateMessageBuffer(cs, 0)` and polling. No upcall stubs, so nothing can throw
  through native frames on the audio thread. `poll` joins fragments into lines.
- `CsoundLaunch.every` runs on the launch's main-thread loop (the same loop that polls).

## Rate header

The constructor passes `-n -d -m0 --sample-rate --ksmps --nchnls --nchnls_i --0dbfs=1` as options,
which override anything the orchestra sets. Output is read from `spout` and divided by `0dbfs`.

## Errors and line numbers

`CsoundCompileError.problems` are placed by scanning the printed lines after an `error:` (or
`syntax error`, `INIT ERROR`, `not found`) line for `line N` / `Line: N` within the next five lines.
Syntax errors print ` line 3:` on the following line, unknown opcodes `Line: 2` four lines down.
`normalise` keeps leading blank lines (only the indentation is trimmed), so a box's
`csound("""⏎instr 1…` reports lines of the string as written, and `inferFaustLineOffset` maps them
to box lines.

## Quirks observed

- A score ends a few k-blocks after its last event: `i1 0 1` renders 48128 frames at 48 kHz,
  ksmps 32.
- With no score, Csound stops at once. `f0 z` keeps it running, which is `csound()`'s default.
- `rms(a1)` reads in channel units: 0.2 sine → 0.1407 measured live through `FieldAudioEngine`.

## Verification (2026-10-02)

- `./gradlew jvmTest --tests 'afield.csound.*'`: 12/12 tests, none skipped.
- Backdoor, port 9611: every box of the demo was run. `offline sine` printed 439 crossings and peak
  0.250. `drone` launched and printed `level 0.033` once a second from the audio thread until it
  was stopped. `an error` was marked at `code:4`. A scratch box registered `cs.node` with
  `FieldAudioEngine` and saw 100864 frames rendered in 2.01 s.
