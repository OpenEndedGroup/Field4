# Csound in Field: design notes

Nightly 2026-10-02 (branch `nightly/2026-10-02/1-csound-bridge`), round two after review: a
first-class box language with a data plane to match libpd's. User page: `public/docs/csound.md`.
Demo: `dev_docs/agents/transcripts/csound_demo.yaml`.

## Files

| Piece | File |
| --- | --- |
| native build | `tools/build_csound_native.py`, `./gradlew buildCsoundNative` |
| discovery + FFM (downcalls, plus four upcalls) | `afield/csound/CsoundNative.kt` |
| engine + data plane + audio node | `afield/csound/CsoundInstance.kt` (`CsoundInstance`, `CsoundNode`) |
| `.csd` reading | `afield/csound/CsdDocument.kt` |
| box language, Alt-Enter, completion, line notes | `afield/csound/CsoundLanguage.kt` |
| generated vocabulary | `afield/csound/CsoundVocabulary.kt`, `src/commonMain/resources/csound-vocabulary.json`, `./gradlew generateCsoundVocabulary` |
| highlighting | `src/commonMain/resources/csound.js` (`bithero/csound`, in `FIELD_LANGUAGE_MODULES`; `csound` in fieldSuggest's `DEFAULT_LANGUAGES`) |
| Kotlin-box API | `afield/four/synth/CsoundBox.kt` (`csound`, `csoundOf`, `csoundControls`, `csoundOffline`, `renderSignalsOffline`) |
| docs prerequisite | `csound` in `tools/build_zensical_docs.py` `NATIVE_PREREQUISITES`, `tools/field_languages.py` |
| tests | `src/jvmTest/kotlin/afield/csound/` (`CsoundTest`, `CsoundDataPlaneTest`, `CsoundLanguageTest`, `CsoundBoxApiTest`) |

## Decisions

- **The box holds a `.csd`, in `code`.** Not separate orc/sco properties. The `.csd` is the format
  Csound users have written since the late 1990s, it keeps the score next to the instruments it plays
  (line notes count events across the two), and it is one property, as a Pd box's patch is.
  `CsdDocument` records each section's first line, which is how Csound's line numbers and the
  cursor's line become box lines. Text with no `<CsInstruments>` is a bare orchestra.
- **One Csound per launch.** It plays through `FieldAudioEngine` as a `CsoundNode` and is freed on
  stop. `csoundPerformKsmps` runs only inside `CsoundInstance.render`, under the instance lock.
- **Orchestra and score go in as CSD text** (`csoundCompileCsdText`), then `csoundStart`. Csound
  then counts orchestra lines from where the CSD put them (two lines of wrapping), for the first
  compile and for every live `csoundCompileOrc` after it, hence `CSD_ORC_LINE`.
- **No `-ffast-math`.** Csound marks a named p1 with a NaN, and fast-math lets clang assume NaNs
  cannot happen, so every `i "Name"` event became "instr 0 undefined". Reproduced in plain C against
  the library. The build script presets `HAS_FAST_MATH=OFF`.

## Alt-Enter dispatch (`CsoundExecution.dispatchFor`, pure and unit-tested)

| Cursor or selection | Action |
| --- | --- |
| inside `instr … endin` | `compileNow(that instrument's text)`; errors placed from its `instr` line |
| a selection of whole instruments | `compileNow(selection)` |
| orchestra, outside any instrument | `compileNow(line or selection)`, as global code |
| `<CsScore>` | `scoreNow(text)`, which is `csoundReadScore`; start times relative to now |
| `<CsOptions>` | nothing; the box says so |

`compileNow` and `scoreNow` take the perform lock, so a live compile holds the next audio block for
as long as it takes (milliseconds).

## Data plane (matches `afield.pd`)

| Direction | Mechanism | Thread |
| --- | --- | --- |
| k-channels both ways | `csoundSet/GetControlChannel` | any; Csound locks the bus |
| S-channels both ways | `csoundSet/GetStringChannel` (+ `GetChannelDatasize`) | any |
| channel hints | `csoundGetControlChannelHints` (struct read at fixed offsets) | under lock |
| `outvalue` → Kotlin | output-channel upcall stores; `poll` delivers on main (k: once per poll on change; S: on change) | upcall on render thread, callback on main |
| Kotlin → `invalue` | input-channel upcall reads a map; numbers only (no buffer size is passed for S) | render thread |
| score events | `csoundInputMessage`, queued to the next block | render thread |
| tables | `GetTable`, `TableLength`, `TableCopyIn` | under lock |
| MIDI in | host-implemented MIDI (`-+rtmidi=hostbased -M0`), read upcall drains a byte queue | render thread |
| audio in | `CsoundNode.connect(name, signal)` → FIFO → `csoundSetAudioChannel` per k-block | audio thread |
| audio out | `csoundGetAudioChannel` per k-block → FIFO → `audioOut(name)`; `outL`/`outR` from `spout` | audio thread |

No upcall throws: each catches everything, because an exception through an FFM upcall kills the VM.

**Latency.** Audio into Csound (a-channels and `spin`) is delayed by exactly `ksmps` frames,
pre-filled silence in the FIFO, so that a render of any length always has a whole block of input.
`CsoundDataPlaneTest` checks that a pass-through channel is the input delayed by exactly 32 frames.
Output is in step with the requested block.

## Vocabulary

`csoundNewOpcodeList` returns `opcodeListEntry {char *opname, *outypes, *intypes; int flags}` (32
bytes on 64-bit). Names not starting with a letter are dropped. 1220 opcodes and 1906 signatures for
6.18.1. `CsoundVocabulary.describe` spells type letters as arguments (`aSig`, `[ij=-1]`, …). There
are no descriptions: libcsound carries none. `CsoundLanguageTest` regenerates the vocabulary when
libcsound is present and fails on drift.

## Verification (2026-10-02)

- jvmTest `afield.csound.*`: all green, none skipped (CsoundTest 12, CsoundDataPlaneTest 8,
  CsoundLanguageTest 7, CsoundBoxApiTest 3). Filter test: a Field 1 kHz unit sine through `butterlp
  500` gives rms 0.17115, theory 0.17150.
- Headless browser on 9611: `bithero/csound` loads, `csoundGrammarState()` reports 1220 opcodes, and
  `monaco.editor.tokenize` gives keyword, type (opcode), variable, variable.parameter, string,
  comment, tag and score-operator tokens. The first cut used `next: "pop"`, which Monarch rejects
  (`'@pop'` is the spelling); the per-module loader kept the editor alive, and the probe caught it.
  Real Ctrl-Space in the live editor shows `poscil`/`poscil3` with signatures.
- Docs runner captures `public/docs/assets/csound/{editor,completion,error}.png`, inspected.
- Live through the backdoor: every demo box run one at a time.

## Not done / limits

- No per-opcode descriptions in completion or hover (libcsound has none, and copying the manual
  would be hand-typed vocabulary).
- Line-note event counts are static (from the score text).
- `invalue` strings are not supported (no buffer size from Csound).
- The macOS build links Homebrew libsndfile by absolute path, so it is not redistributable. Linux
  is untested.
- The Field-into-fx path is verified numerically offline (`renderSignalsOffline`, same code path)
  and ran live without error, but its live output was not measured.
- Csound MIDI is in only; MIDI out (`midiout`) is not routed to Field.
