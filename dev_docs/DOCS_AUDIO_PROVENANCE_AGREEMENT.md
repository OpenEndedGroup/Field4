# Docs audio provenance: what is checked, and to what tolerance

Every `audio="…"` fence in `public/docs` claims a producer. Two guards make those claims
load-bearing rather than decorative. This note holds the method and the measurements;
`public/docs/sound.md` keeps only what a reader of that page needs, which is that the
*"Voice: …"* captions cannot silently drift.

## `SoundProvenanceGuardTest` — the three clips on `sound.md`

`SoundRenderPathDocs` declares a `Voice` per figure. The guard reads `public/docs/sound.md`
itself, pairs each audio fence with its figure, and requires:

- every fence names a figure the renderer knows, and every figure appears on the page exactly
  once — a figure with no fence is dead code, a fence with no figure cannot render;
- the figure's declared `Voice.pageLabel` appears **verbatim within `NEARBY_CHARS` after its
  fence**, so a reader can see the caption and the player at the same time. This is the
  assertion that stops the prose and the code drifting apart;
- no two `Voice` values share a label, or the check above proves nothing;
- the committed `.wav` is decoded and correlated against a fresh stand-in render of the same
  pattern.

### The correlation figures, and that they are printed rather than asserted

A figure claiming the stand-in must match it; one claiming samples must not; a mixed figure's
fallback events must be audibly present. As last read off the page these measured:

| figure claims | measured correlation against a stand-in render |
|---|---|
| the stand-in | 1.0000 |
| real Dirt-Samples (the sample-backed events of two figures) | 0.0565 and 0.1588 |
| mixed — fallback events present | 0.8074 |

**These four values are printed by the run, not asserted by it.** The assertion is against
`DocsAudioProvenance.REPRODUCIBILITY_FLOOR`, not against these numbers, so they carry no guard
and no timestamp; treat them as the last observation rather than as a contract. This is why they
are here and not in the page's prose.

The correlation deliberately needs no sample library: `patRenderStereo` is in the source tree, so
a CI box with no 400MB third-party download still catches the day a "real drums" figure becomes
bursts of white noise. A reader would not, because the caption under it would go on saying
samples. What it cannot do is verify that a sample-backed asset came from the Tidal library
*specifically* — it verifies that it did not come from the synth, which is the failure mode the
fallback creates.

## `DocsAudioProvenanceGuardTest` — every clip in the docs

The same question asked of the whole corpus, on every build. It sweeps every page for `audio=`
fences, re-renders each asset through the same `renderWav(name, path)` contract the docs build
drives over the backdoor, and correlates the result against the committed bytes.

- **Nothing ships unclassified.** `EXPECTED_FENCE_COUNT` is pinned, so a new clip fails the build
  until somebody states which family it belongs to.
- **Every committed clip still matches what its producer renders today** — bit-stable through
  peak normalisation and 16-bit quantisation. This is the assertion that catches a published
  figure whose producing code changed underneath it. `56b124131` is the last time two of them had
  to be repaired by hand.
- **The undeliverable list is pinned by key and by reason**, so it cannot become a place to put
  problems unnoticed. An entry needs a reason over 40 characters, and its asset must still exist.
  It holds `assets/banner.mp4`.

The corpus audit prints its whole table: `run_field_tests.py --id synth.docs.audio.provenance`.

### The family counts are pinned here, and `sound.md` restates them

`DocsAudioProvenanceGuardTest` asserts both the total and the per-family split:

| family | pinned count |
|---|---|
| `STAND_IN_SYNTH` | 37 |
| `DIRT_SAMPLES` | 1 |
| `MIXED_SAMPLES_AND_STAND_IN` | 1 |
| `DOUGH_DSP` | 7 |
| `FAUST` | 3 |
| `DOUGH_WASM` | 2 |
| **total (`EXPECTED_FENCE_COUNT`)** | **51** |

The guard's failure message says, in as many words, that `sound.md` states these counts in
prose — which makes the page a second copy with no assertion behind it.

## Open

- **`sound.md`'s census has now gone stale twice**, and nothing catches it. It was re-measured on
  2026-09-16 by `bd64dcdad` against 51 across eight pages, having read "forty-three clips across
  five pages" — and a concurrent pass that same night independently corrected it to 49 across
  seven, which was *also* wrong by the time it was written, because `pat_spatial.md` had just
  added the `DOUGH_WASM` family. Two passes, one night, two different wrong answers to a question
  the guard already knows the answer to.

    **The guard does not read those numbers off the page**, so this will happen again the next time
    `EXPECTED_FENCE_COUNT` moves. An assertion that scrapes the page's own table against the family
    map is the fix and would be cheap: the table's first column is the count and its second names
    the family. Until then, anyone editing that table should read `EXPECTED_FENCE_COUNT` rather
    than counting.
- **"All of them currently score 1.0000" is printed, not asserted.** The page carried this as a
  count — "all forty-one", corrected to "all fifty-one" by `bd64dcdad` — and the count was the
  third number in the same paragraph with nothing behind it. The assertion is against
  `REPRODUCIBILITY_FLOOR`; the tally of how many currently sit at 1.0000 is output. The sentence is
  gone from the page rather than corrected again.
- **`marf.md` has no clip, and the figures on it are geometry.** A `MarfReader`'s output is a
  control signal rather than a note stream, so the clip worth having is one voice whose gain, pan
  and filter are three readers over one bank. That needs a registered offline renderer for the
  control-signal path, which does not exist. Moved here from the page on 2026-09-20: which
  renderers the docs build happens to have is not something a reader of `marf.md` acts on.
