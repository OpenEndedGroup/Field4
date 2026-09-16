# Audio embedding projections: frame counts and fit cost

Displaced from `public/docs/audio_ai_speech_embeddings.md` and
`public/docs/audio_ai_spectral_embeddings.md` on 2026-09-16. Those pages keep the settings a reader
acts on; the arithmetic behind them lives here.

## `near_drowning.wav`, the recording both pages use

It runs 273.82 seconds. Everything below follows from that single number and the operation's own
rates, so a different recording rescales all of it.

| Operation | Native rate | Native frames | Frames at a 40 ms hop |
| --- | ---: | ---: | ---: |
| `analysis.embed_speech` (emotion2vec, WavLM Base+) | 50 Hz, 20 ms grid | 13,690 | 6,845 |
| `analysis.embed_spectral` (librosa, 882-sample hop at 22.05 kHz) | 25 Hz, 40 ms hop | 6,846 | 6,846 |

The spectral hop is already 40 ms, which is why its two columns agree. The docs page states the
6,846 figure with a `<!--derive: 273.82/0.04-->` marker, so `DerivedArithmeticGuard` checks it;
`dev_docs/derived_arithmetic_sweep.md` rows 92–93 record that pair. **None of the speech-side
counts are asserted anywhere.** They are arithmetic on the duration, not a measurement, and no test
pins them.

## Why `maxFrames` is the expensive knob

Both pages project through Smile. Exact classical MDS is quadratic in memory and cubic in compute
in the number of points; an exact t-SNE fit is quadratic in memory. So on this recording:

- the full raw speech frame count is the worst case a reader can ask for by accident;
- `maxFrames = 7_000` retains the complete 25 Hz pooled path at a 40 ms hop, and its pairwise
  matrix has roughly a quarter of the entries of the raw-frame version;
- a ten-second window at a 40 ms hop is about 250 points, which is comfortable for exact pairwise
  distances.

## Open

- **13,690 against 13,691.** 273.82 s at 50 Hz is 13,691, and the docs page said 13,690. One frame
  either way is chunk-boundary rounding in the worker or a rounded duration, but nobody has read
  the count out of a real result. The table above carries the page's figure, not the arithmetic's.
  Settle it by printing `frameTimes.size` from one cached `analysis.embed_speech` result.
- Nothing times any of this. The "roughly a quarter" and the choice of 7,000 as the practical
  ceiling are arithmetic and judgement, not a benchmark. If either figure is going to be quoted
  again, it wants a timing in `SpeechEmbeddingMds`'s test neighbourhood first.
