# Corpus microscope: where the descriptors live

Displaced from `public/docs/audio_ai_corpus_microscope.md` on 2026-09-16 by the documentation voice
pass. The storage split sat inside that page's `## What each window is measured by` section, between
the descriptor-group table and the code a reader came for; it is a map of the implementation, not
something a reader setting `CorpusDistanceWeights` acts on.

## The three tiers

- **Descriptor vectors** — the pooled per-window vectors stay in content-addressed NPZ assets. The
  page used to call them "full 71-dimensional vectors". **That count is pinned nowhere in this
  repository**: `71` appears in neither `CorpusOperations.kt` nor `CorpusOperationsTest.kt`, and the
  descriptor worker that decides the width is not in this tree. It was a transcribed number with no
  guard and no timestamp, so the page now describes the five groups instead of counting their
  dimensions. If the width is ever worth stating publicly, read it out of a real
  `embedCorpus` result and assert it.
- **SQLite** — segment identity and source adjacency (each window's previous/next neighbour in its
  own recording). This is what makes `segmentCorpus` re-runnable and what `corpusPreview` hydrates
  against.
- **The browser** — bounded metadata, audio handles and short splice-preview handles only. Waveform
  envelopes load at the resolution of the visible window, which is why hover, zoom and playback
  cursors stay aligned across two different recordings: the client is never holding a whole
  recording's samples.

Windows are recorded as boundaries and offsets throughout. The audio is never rewritten — that part
is on the page, because it is the reason a user can trust the splice previews.

## Open

- **Nothing asserts the descriptor width.** See above. A one-line assertion in
  `CorpusOperationsTest` against a fixture `embedCorpus` result would make any public statement of
  it guarded.
