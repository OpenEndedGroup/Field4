# Drop the maintainer test command from sound.md's transcription section

- **Handoff:** `5ddbaefb-26da-4a06-bebe-d91b611eb6f5` (project)
- **Filed from:** https://openendedgroup.com/field4/sound/
- **Filed by:** human, 2026-10-02 09:11
- **Close with:** `python tools/docs_notes.py handoff-close 5ddbaefb "what happened"`

`sound.md` ("From audio back to a pattern") ends with a fence running `tools/run_field_tests.py --id synth.pat.transcription` to regenerate the three transcription figures. That is a maintainer command, not something a reader does. Remove the fence (or move it into a `??? aside`) and update `tools/completion-corpus/rank-carets.tsv` if it pins it. The language pass left the fence untouched because fence bytes are pinned.
