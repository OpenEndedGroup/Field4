# Speech: a box that talks — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`) and
run the boxes in order. The first run fetches about 27 MB (the tiny-voca model and its phonemizer
data) into `~/.afield/onnx/models/tiny-voca/`; after that each `speak(...)` takes tens of
milliseconds.

| box | what it does |
| --- | --- |
| **1 — Speak** | `speak("...")` into an audio player, with the measured real-time factor |
| **2 — Word timing** | the level of the speech, with a mark at the start of each word |
| **3 — Words play the drums** | `speech.pattern { ... }`: a kick on stressed words, a hat on the rest, mixed with the voice |
| **4 — Sung on a pitch you choose (live)** | returns `speech.sungOn(Impulse(pitch))`, which FieldAudioEngine plays on the audio thread |
| **5 — Analyse what was said** | the LPC envelope of each word's stressed vowel |

`build.yaml` beside this file is the `OperationTranscript` that built the document. See the
"Speech" section of [`public/docs/spectral_examples.md`](../../../../public/docs/spectral_examples.md).
