# Let corpus microscope neighbours be placed on a timeline

- **Handoff:** `b72e3cb8-3975-4b53-8a17-b8fe62bf540f` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_corpus_microscope/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close b72e3cb8 "what happened"`

Rule 25 sharp edge on `public/docs/audio_ai_corpus_microscope.md`, end of "Query, await and visualize":

> Each card also lists the window's timestamps in its source and the ids of the windows before and after
> it. Placing a neighbour on a timeline is not supported.

The sentence exists only to warn about a missing capability. Either add a way to place a corpus
neighbour (its source asset and window) onto a Field timeline / arrangement from the neighbour card or
from `CorpusPreview` in code, or decide it is out of scope. In the same change, delete the sentence
"Placing a neighbour on a timeline is not supported." from the page (and, if implemented, replace it
with a short example of the new call).
