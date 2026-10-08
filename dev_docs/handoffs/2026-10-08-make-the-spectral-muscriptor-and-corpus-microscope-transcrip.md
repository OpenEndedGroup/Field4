# Make the spectral, MuScriptor and corpus-microscope transcripts starting points and drop stdlib imports from their fences

- **Handoff:** `a6f6a258-4e97-4067-abf8-b24d088f9c47` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_spectral_embeddings/
- **Filed by:** human, 2026-10-08 15:11
- **Close with:** `python tools/docs_notes.py handoff-close a6f6a258 "what happened"`

Extends handoff ee38c55f to three more Audio AI pages: `audio_ai_spectral_embeddings.md`,
`audio_ai_muscriptor.md`, `audio_ai_corpus_microscope.md`.

- Each page's fences import `afield.stdlib.noir.audioai.*` (and `afield.stdlib.AudioVisualizer*`);
  rule 7 says no `afield.stdlib.*` imports. Make the names resolve in a box without them, edit the
  fences, and recapture.
- Each page used to end by telling the reader to "load the `audio_ai_*` operation transcript". The
  language pass replaced that with see-also links; publish the three transcripts
  (`audio_ai_spectral_embeddings`, `audio_ai_muscriptor`, `audio_ai_corpus_microscope`) as starting
  points on the welcome screen and link them from the pages.
- The long workflow fences (100+ lines of future chaining) are frightening for a first read; fold
  the job-tracking boilerplate (rule 8).
