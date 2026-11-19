# Speech with tiny-voca: notes

User page: `public/docs/spectral_examples.md`, "Speech: a box that talks". Code:
`src/jvmMain/kotlin/afield/audio/synth/Speech.kt` (`speak`, `Speech`, `TinyVoca`) and
`SpeechPhonemizer.kt` (JVM port of dizione). Smoke: `SpeechSmokeTest` (`speech.tiny-voca.smoke`).
Demo: `dev_docs/agents/demos/speech/`.

## Licenses

- tiny-voca weights and code: Apache-2.0 (HF `n4ze3m/tiny-voca`, revision `1e73262226db`).
- dizione code and `data/*.txt` tables: Apache-2.0 (commit `8cd889e4`).
- `phonemizer/lexicon.tsv`: derived from CMUdict (BSD-style; `phonemizer/LICENSE.cmudict`).

Nothing is committed: all files are `OnnxModel`s fetched by sha256 into
`~/.afield/onnx/models/tiny-voca/` (about 27 MB).

## Measured

`SpeechSmokeTest`, Apple-silicon Mac, CPU provider, ORT defaults, warm session, a two-sentence
5.9 s utterance: median of 5 runs **110x real time** (phonemizer included). Session construction
236 ms. The first call in a JVM also parses the 125k-word lexicon (about a second), so a cold
`realTimeFactor` reads low (2x was seen on a cold docs-capture run); do not quote a cold one.

## Where the port differs from Python dizione

No reference comparison was run: installing dizione into a Python venv was blocked in the nightly
sandbox. What is ported and what is not is listed in the `SpeechPhonemizer` KDoc. The token
sequence of one dictionary sentence is pinned in the smoke test. Next step: a token-by-token diff
against `dizione.phonemize` over a few hundred sentences.

## Timing

`durations[i]` is the length of token i in 256-sample frames, and the frames sum to the audio
length, so a running sum is the clock. Word start = first phone's start, word end = last phone's end;
`<sp>` and marks belong to no word, so pauses are gaps between words.

## Pitch

The model takes only a scalar `pitch_shift` (log F0; one semitone is ln 2 / 12) and `pitch_range`.
A time-varying contour is `Speech.sungOn(Impulse(param))`, i.e. the speech's LPC envelope on
another carrier, not the model's own vocoder.
