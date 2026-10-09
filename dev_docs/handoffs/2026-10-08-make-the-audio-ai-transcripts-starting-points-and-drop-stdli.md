# Make the Audio AI transcripts starting points and drop stdlib imports from their fences

- **Handoff:** `ee38c55f-a2fb-41df-a027-859b4267cf56` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_analysis/
- **Filed by:** human, 2026-10-08 12:10
- **Close with:** `python tools/docs_notes.py handoff-close ee38c55f "what happened"`

The Audio AI pages `audio_ai_gesture_board.md`, `audio_ai_analysis.md` and
`audio_ai_speech_embeddings.md` each pointed the reader at "the loadable `<name>` operation
transcript" (`dev_docs/agents/transcripts/audio_ai_*.yaml`) as their starting document. A reader
cannot open a transcript; the language sweep removed those sentences.

Work:
1. Make each of these transcripts (and the sibling `audio_ai_codec`, `audio_ai_corpus_microscope`,
   `audio_ai_muscriptor`, `audio_ai_spectral_embeddings`) a welcome-screen starting point, and link
   it from its page.
2. The runner fences on these pages import `afield.stdlib.*` / `afield.stdlib.noir.audioai.*`
   (rule 7). Make the Audio AI API available from the box preamble, or fold the imports, then
   update the fences (rank-carets pins their bytes) and recapture.
3. The analysis page's figure is drawn from synthetic sine data. Decide whether a real capture
   (worker running, Nancy excerpt) should replace it, under a capture profile if it needs the worker.
