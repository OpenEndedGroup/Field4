# Let corpus microscope neighbours be placed as audio-clip boxes

- **Handoff:** `bd5254f0-eb1e-4fb7-bdaf-46657baf8df2` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_corpus_microscope/
- **Filed by:** human, 2026-10-16 09:17
- **Close with:** `python tools/docs_notes.py handoff-close bd5254f0 "what happened"`

Decided 2026-10-16 (Marc, handoff c3e10c8a; see `dev_docs/handoffs/2026-10-16-decide-what-placing-a-corpus-neighbour-on-a-timeline-means.md`): placing a corpus-microscope neighbour means making a **box-level audio clip**.

Work:

1. Add a "place" control to the neighbour cards (`CorpusMicroscope.kt` / `CorpusOperations.kt`, which today only preview).
2. "Place" creates a new box beside the microscope box, named after the source and window. Its `code` launches the source asset trimmed to the window's start and end, written the way a box is written (the box-idiom rules, so `checkBoxIdioms` passes). Generated code the user can edit, not a hidden player.
3. Add a test: placing a neighbour creates one box whose code, run, plays the source asset over that window (check the launched start/end, not audio output).
4. Document it on audio_ai_corpus_microscope.md with a real capture of a placed clip.
