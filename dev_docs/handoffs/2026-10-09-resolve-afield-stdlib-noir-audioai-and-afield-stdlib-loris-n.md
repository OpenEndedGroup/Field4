# Resolve afield.audio.ai and afield.audio.analysis.loris names in a box without imports

- **Handoff:** `0dafa9ae-7910-4715-aae1-ee93aa51d3d1` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_analysis/; also affects audio_ai_gesture_board.md, audio_ai_speech_embeddings.md, audio_ai_codec.md, audio_ai_spectral_embeddings.md, audio_ai_muscriptor.md, audio_ai_corpus_microscope.md, loris.md
- **Filed by:** human, 2026-10-09 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 0dafa9ae "what happened"`

Split out of handoffs ee38c55f and a6f6a258 (2026-10-09), whose starting-point halves are done.

The box preamble (`Boxes.kt`, the import block every box gets) imports `afield.stdlib.*` but no
subpackage, so the Audio AI pages' fences import `afield.audio.ai.*` and
`afield.ui.visualization.AudioVisualizer*`, and `loris.md` tells the reader to write
`import afield.audio.analysis.loris.spectralWhirlpool`. Rule 7 says no `afield.stdlib.*` imports in box code.

Decide how those names reach a box: add the packages to the preamble (check the import-ambiguity
count the completion harvest prints, and `checkCompletionCorpusFixtures`), or re-export what pages
use. Then drop the imports from the fences (rank-carets pins their bytes), drop loris.md's import
sentence, and recapture.

Left with it from ee38c55f/a6f6a258, page work rather than code:
- fold the long job-tracking boilerplate in the spectral, MuScriptor and corpus-microscope workflow
  fences (rule 8);
- decide whether audio_ai_analysis.md's figure, drawn from synthetic sine data, should be a real
  capture (worker running, Nancy excerpt) under a capture profile.
