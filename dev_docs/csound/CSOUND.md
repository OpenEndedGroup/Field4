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

## Data plane (matches `afield.audio.pd`)

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
6.18.1. `CsoundVocabulary.describe` spells type letters as arguments (`aSig`, `[ij=-1]`, …).

Descriptions come from the Csound Reference Manual's DocBook source, csound/manual tag 6.18.0 (no
6.18.1 tag exists), fetched and checksummed by `tools/fetch_csound_manual.py` into the native
download cache. `readManual` takes each page's `refpurpose`, `synopsis` lines and the
`<emphasis>name</emphasis> -- text` paragraphs of Initialization/Performance (first sentence, at most
eight), keyed by `refname` first and the synopsis `<command>` second. The second key catches `chn`
documenting `chn_k`/`chn_a`/`chn_S`, and pages with a stale refname (`randi.xml` says `rand`).
Coverage: 1183 of 1220 library opcodes. The 37 without a description are listed in the JSON's
`manual.undescribed`. Licence: GNU FDL 1.2 or later. The manual's `copying.txt` (notice plus
licence text) is copied to `src/commonMain/resources/csound-manual-COPYING.txt` by the generator.
`CsoundLanguageTest` checks names and signatures against libcsound, the descriptions against the
cached manual, that `readManual` is deterministic, and pins aftouch, poscil, butterlp and chn_k.
Regenerating twice gives a byte-identical file. The editor hovers an opcode with its description,
syntax and manual link (`csound.js`).

## Verification (2026-10-02)

- jvmTest `afield.audio.csound.*`: all green, none skipped (CsoundTest 12, CsoundDataPlaneTest 8,
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

- 37 opcodes have no manual entry and show their signature alone.
- Line-note event counts are static (from the score text).
- `invalue` strings are not supported (no buffer size from Csound).
- The macOS build links Homebrew libsndfile by absolute path, so it is not redistributable. Linux
  is untested.
- The Field-into-fx path is verified numerically offline (`renderSignalsOffline`, same code path)
  and ran live without error, but its live output was not measured.
- Csound MIDI is in only; MIDI out (`midiout`) is not routed to Field.

## `chn_k` defaults and the docs-fence gate (2026-10-03)

- **Csound 6 does not apply a `chn_k` default.** `idflt` is stored as a host hint
  (`csoundGetControlChannelHints`) and the channel stays 0. `CsoundInstance` therefore writes each
  declared `chn_k` default once at construction (`applyDeclaredDefaults`), never on a live
  recompile. Since 2026-10-04 the declared channels come from `csoundListChannels` (exposed as
  `CsoundInstance.channels()`), not a regex over the orchestra: a `chn_k Sname, ...` with a string
  variable for the name now gets its default, and a commented-out `chn_k` is not seen. Pinned by
  `CsoundTest.channelDefaultsComeFromCsoundsChannelList`; negative control: the old regex left
  `gain` at 0.0 (expected 0.5). The listed input/output bits accumulate (`chnget` and `chnset`
  add theirs), so they say how a channel is used, not its declared `imode`. Before this, the docs page's Pluck fence and the demo's **pluck** box played a
  `butterlp` at 0 Hz, which is silence, until a Kotlin box wrote `cutoff`. Pinned by
  `CsoundTest.aChnKChannelStartsAtItsDeclaredDefault`.
- **`CsoundDocsFencesTest`** renders every `data-lang=csound` fence in `public/docs/csound.md` the
  way `CsoundExecution` does and requires a peak above -60 dBFS, or, for `expect-error=true`, a
  refusal placed on an orchestra line. It skips without libcsound, like every Csound test; the
  fence-count check runs everywhere. The docs runner (`mode=always`) also fails on a fence that
  does not compile, but an editor capture cannot tell a silent fence from a playing one: this test
  is the part that can. Negative controls on 2026-10-03: `plucx` for `pluck` turned both red;
  restored, both green.
- **Main Language** registration was already complete in 75a095d7d. Verified through the backdoor:
  `action @root set-main-language-csound` (and setting `mainLanguage` directly) makes a connected
  child holding plain `.csd` text launch as Csound; a sibling Kotlin box read its `chnset` rms
  (0.11) and the default `cutoff` (1800) from the running instance; relaunching replaced it; a
  typo was marked on its own line (4).
