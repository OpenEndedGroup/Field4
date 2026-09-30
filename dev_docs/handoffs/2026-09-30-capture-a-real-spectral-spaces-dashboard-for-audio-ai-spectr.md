# Capture a real spectral-spaces dashboard for audio_ai_spectral_embeddings

- **Handoff:** `cb4120be-4e73-4748-bc29-08e92db68d31` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_spectral_embeddings/
- **Filed by:** agent:claude, 2026-09-30 20:47
- **Close with:** `python tools/docs_notes.py handoff-close cb4120be "what happened"`

## The notes that led here

- `c8b4b82e` (public/docs/audio_ai_spectral_embeddings.md:3): need a screenshot
  > A resonance sliding upward, a hiss that thickens, a room closing in around a voice: none of that needs a trained model to notice, only a record of which frequencies are loud from one moment to the next. analysis.embed_spectral keeps that record in several interchangeable forms rather than settling on one.

## What was asked

Note c8b4b82e on `public/docs/audio_ai_spectral_embeddings.md` (the opening paragraph) says "need a
screenshot". The page has no figure. Its one code block, `public/docs/audio_ai_spectral_embeddings.md:43`,
is a `diffstatic` fence, so the docs runner shows it but never runs it.

## Why this needs more than a docs edit

A real capture of the page's workflow depends on things the docs runner doesn't have:

- `FieldAudioClient()` talking to the external audio service, which queues a librosa
  `analysis.embed_spectral` job (`public/docs/audio_ai_spectral_embeddings.md:51`, `:82`);
- `~/Downloads/near_drowning.wav`, or an asset already imported under that name (`:52`, `:65-70`);
- the downloaded MessagePack artifact. `FieldAudioClient.spectralEmbeddingFrames` goes through
  `downloadBytes(result.spectralEmbeddingMessagePack())`
  (`src/jvmMain/kotlin/afield/stdlib/noir/audioai/SpectralEmbeddingProjection.kt:402`).

The only runnable model-free alternative is the transcript box
`public/docs/assets/agent_transcripts/audio_ai_spectral_embeddings/spectral_embedding_visualizer_gallery_code_.kt`.
It is not usable as a docs capture. It writes the `tsne_0..3` series out as sine waves and labels
them `projection_method = "t-SNE"`, so a capture of it would show a made-up projection. AGENTS.md §3
("A capture exercises the real code, or it does not exist") rules that out. The same file also
hand-writes energy, flux, centroid and flatness curves.

The other audio_ai pages that do have figures (`audio_ai_codec.md:9`, `audio_ai_analysis.md:33`,
`audio_ai_gesture_board.md:21`) use `diffstatic_runner` fences that render without the service.

## Possible approaches

1. **Let the docs runner reach the audio service** for a capture profile, as with the
   `linux-nvidia` retained captures. Run the real fence against a committed, licensed audio file
   (not `~/Downloads`), and retain the PNG on machines that lack the service. This gives the most
   faithful figure but needs new infrastructure.
2. **Build `SpectralEmbeddingFrames` in the JVM from a synthetic or committed sound.** Field would
   compute the mel distribution, log-mel and MFCC itself (the `spectral_examples` transcripts
   already compute mel spectra). A `diffstatic_runner` fence would then run the real
   `frames.tsne(...)`, `withSpectralProjection` and `spectralEmbeddingPreviewDashboardHtml`. The
   projection and dashboard would be real computation, but the features would not be librosa's,
   and the figure caption would have to say so. This needs a public constructor or helper, which
   means a small API addition.
3. **Commit a small real `embed_spectral` MessagePack artifact** produced by the service as a docs
   fixture. The fence would call `decodeSpectralEmbeddingMessagePack(bytes, ...)` directly
   (`SpectralEmbeddingProjection.kt:110`), then run the real t-SNE and dashboard. Everything
   downstream of librosa would run live, and the artifact itself would be real service output.
   This is probably the cheapest honest option.

Separately, either fix the gallery transcript's synthetic t-SNE axes or relabel them so nothing
passes them off as a projection.

## Questions for a human

- Which approach: (1) service-backed retained capture, (2) Field-native features, or (3) a
  committed real artifact?
- Can `near_drowning.wav` (or its derived artifact) be committed, or should the page switch to a
  different recording?
- Should the gallery transcript's sine "t-SNE" series be removed or relabelled?

## What the page needs afterwards

Add a runner fence (or a retained capture) that produces the three-distance dashboard, and put the
figure near the top, after the opening paragraphs or right after "Complete Field workflow". Give it
alt text naming the three distances, and caption where the features came from if it isn't the
live service.
