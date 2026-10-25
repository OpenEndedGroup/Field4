# Make DoughAudioSink lossless at buffer sizes not a multiple of 128

- **Handoff:** `ac922167-6718-4403-8219-596aae8734be` (project)
- **Filed from:** https://openendedgroup.com/field4/sound_paths/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close ac922167 "what happened"`

Rule 25 sharp edge on public/docs/sound_paths.md:

> It renders in whole 128-frame blocks and copies out only what was asked for, so it drops samples at any engine buffer size that is not a multiple of 128. The default is 512.

Fix `DoughAudioSink` (Dough.kt, afield/audio/synth) to keep the remainder of a rendered 128-frame block and serve it on the next `render` call, so any buffer size is lossless. Add a unit test at e.g. 441 frames. Delete the sentence in the same change.
