# Sound path map: what is checked, and to what tolerance

Displaced from `public/docs/sound_paths.md`, which is a map a reader uses to pick a render path.
This is the record of how that map is held to the code, and of the tolerances behind the numbers
the page quotes.

Owners: `SoundPathMap` (`src/jvmMain/kotlin/afield/four/synth/SoundPathMap.kt`), its guard
`SoundPathMapGuardTest`, the `synth.sound.path.map` smoke, and `DocsAudioProvenance`.

## The five things the map guard asserts

1. **The counts.** `SOURCE_COUNT = 8`, `SINK_COUNT = 5`, `INPUT_COUNT = 4`, `SEAM_COUNT = 11`,
   each re-derived by grouping `PATHS` rather than recounted by hand. A path added *beside* the
   seam instead of *on* it grows `PATHS` while leaving `SEAM_COUNT` alone, and the failure message
   says so rather than inviting an edit to the constant. `SOURCE_COUNT` went from 7 to 8 on
   2026-09-16 with `pat-spatial`.
2. **Undeclared paths, derived from marker literals.** Five literals in the tree can only mean a
   new way in or out of the process: `alcOpenDevice(`, `alcCaptureOpenDevice(`,
   `fun renderWav(name: String, outPath: String)`, `WavFloatArrayWriter.encode` and
   `override fun generateStereo(`. Every file carrying one must be attributed to a declared path,
   and a new hit names itself in the failure. `SoundPathMap`'s own KDoc records why the fifth
   marker was added and what the set still cannot see; it is the detailed account and this note
   does not duplicate it.

    The fifth marker — the seam one — has now earned its keep twice. It was added on 2026-09-12
    after `FaustPoly.kt` sat undeclared for a month: a pattern-driven Faust instrument opens no
    device and writes no WAV, so the first four literals all missed it while every check stayed
    green. `pat-spatial` arrived on 2026-09-16 with the same profile and the seam marker reported
    `PatSpatial.kt` *and* the control node in `PatSpatialEngineSmoke.kt` before anybody noticed the
    path was undeclared. The same hole, the second time, caught in a day rather than a month. It
    still cannot see a source off the seam entirely — another `sonic-pi`, another whole-buffer free
    function.
3. **Stale declarations.** The same sweep in reverse: a declared file that no longer carries its
   marker fails, because that is a citation that has rotted.
4. **Every citation on the page resolves.** Each path declares an entry-point *symbol*, not a line
   number, and the guard greps the file for it, so a rename fails the build.
5. **The page names every declared path**, file and symbol, verbatim. An omitted row fails.

Negative controls sit at the bottom of `SoundPathMapGuardTest`: a marker-derived guard can fail by
matching nothing (a mistyped literal) or by matching everything (a literal too loose), and both are
provoked on purpose.

## The comparison figures

The shared input is `pat("bd ~ sd ~, hh*8") with gain(0.9)`, four cycles at 2 cps, rendered by
`patRenderStereoDirt(..., library = DirtRender.requirePinnedLibrary(), strict = true)`: real
`bd:0`, `sd:0` and `hh:0` one-shots from tidalcycles/Dirt-Samples at the commit pinned in
`src/jvmMain/resources/afield/four/synth/dirt-samples-pinned.json`, fetched by
`./gradlew fetchDocsDirtSamples`. Until 2026-10-01 it was `patRenderStereo`, the `patVoice`
stand-in, whose noise hats put the dry centroid at 10.9 kHz. There is no fallback to it: without the
pinned subset, the smoke, the docs capture and `DocsAudioProvenanceGuardTest` all fail with the
fetch command in the message.

Measured on the new input (`run_field_tests.py --id synth.sound.path.map` prints all of these):

| figure | centroid | vs dry | peak before normalization | rms before normalization |
| --- | --- | --- | --- | --- |
| `same-input-dry` | 2856 Hz | — | 0.723 | 0.1605 |
| `same-input-signal-graph` | 652 Hz | 0.23× | 0.685 | 0.1293 |
| `same-input-faust` | 783 Hz | 0.27× | 0.676 | 0.1551 |

Signal-graph against Faust waveform similarity **0.9899** (it was 0.9584 on the stand-in): most of
what either filter leaves is the kick, about 83% of the dry buffer's energy sits below 200 Hz, and
both lowpasses pass that nearly unchanged.

The three clips are checked as a shape, not against stored values: both
filters must take the spectral centroid below 0.6× the dry buffer's, the two filtered renders must
still correlate above 0.5 with each other, and the gain agreement must stay under
`SoundPathMap.SEAM_AGREEMENT_CEILING` = `1e-6`. `DocsAudioProvenance` separately re-renders all
three committed `.wav`s through the same `renderWav` contract the docs build uses and correlates
them against the bytes on disk, so a figure the code has stopped producing fails as well.

`DocsAudioProvenanceGuardTest.EXPECTED_FENCE_COUNT` pins the number of `audio="…"` fences the docs
publish, so a new clip cannot ship without a stated provenance.

### Loudness, and which buffer each number came off

`writeStereo16` is called with `normalizePeak = 0.9f`, so every committed clip has the identical
peak sample value of 29490 and the three players compare timbre rather than level.

The centroids and the rolloff figures on the page are measured on the **pre-normalization**
buffers, not on the committed `.wav`s: `brightnessHz(audio.mono)` runs on the rendered buffer and
`writeStereo16` is called afterwards. Peak normalization is a single scalar multiply, so the
centroid, the slopes and the 0.9584 similarity are identical either way — which is why the page
does not distinguish them.

The levels before that scalar are in the table above. On the stand-in input the dry beat peaked at
2.005, past full scale because ten synth voices a cycle summed; the Dirt one-shots peak at 0.723.
Printed by the smoke, not asserted.

The gain leg of the seam check, also displaced from the page on 2026-09-16: the same buffer
multiplied by 0.5 by the graph (`Signal * 0.5f`) and by Faust (`process = _,_ : *(0.5), *(0.5);`)
over 44100 samples gives a **worst per-sample disagreement of 0.000e+00** — bit-equal, against a
ceiling of `1e-6`.

Byte-level agreement, for whoever next doubts the clips share an input: in the committed WAVs the
dry beat reaches its maximum at frame 44194 and the Faust render at 44203, nine samples later, the
first-order filter's delay on a kick. The two-pole render peaks on a different kick, at 55225.
Measured off the committed files with numpy on 2026-10-01; the smoke does not print it.

## The spatial loopback smoke

`audio.spatial.loopback` (`SpatialAudioLoopbackSmoke`) drives the real OpenAL mixer through a
loopback device, so it needs the bundled OpenAL Soft library and no physical output. It asserts,
all with `check`:

| what | tolerance |
| --- | --- |
| a nested thread-local context is distinct from the outer one | exact inequality, both non-null |
| `renderSamples` on a non-loopback engine is refused | `IllegalArgumentException` |
| a second thread's call on an owned engine is refused | the refusal is observed, not the exception type |
| HRTF is actually enabled on the bundled library | `hrtfEnabled`, with `hrtfStatus` in the message |
| head rotation exchanges the ears | each ear leads by more than 1.08× in its own orientation |
| Doppler under listener motion | rest tone within 8 Hz of 1000; approach above 1.07× rest |
| source and buffer cleanup | `sourceCount == 0 && bufferCount == 0` |
| context and LWJGL capabilities restored after nested use | outer context current, thread context 0 |

What it does not establish: how an individual listener perceives an HRTF through their own
headphones. Nothing in this tree measures that, and no page should imply it.

## Open

- **Is offline loopback rendering a sixth sink?** `SINK_COUNT = 5` counts `spatial-openal` once,
  but the loopback mode is a genuinely different way for samples to leave the process — it opens
  its device with `SOFTLoopback.alcLoopbackOpenDeviceSOFT(`, which is **not** one of the five
  marker literals. The file is already declared for `spatial-openal`, so the sweep stays green
  either way and the question is a modelling one, not a hole. Decide it in `SoundPathMap`, not on
  the page.
- **Sonic Pi → Field is unexercised.** `link_audio_publish_set!` and `linkAudioIn(channelName)`
  exist on both sides and nothing in this tree drives them end to end. Stated on the page as a
  limitation, which is where a reader needs it; a probe in the shape of the Field → Sonic Pi one
  would close it.
