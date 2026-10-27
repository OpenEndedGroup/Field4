# Move demo_document recapture recipe into an Agents aside

- **Handoff:** `f690c9d7-e3e7-4357-bf19-9aa51ffe9b51` (project)
- **Filed from:** https://openendedgroup.com/field4/demo_document/
- **Filed by:** human, 2026-10-27 18:20
- **Close with:** `python tools/docs_notes.py handoff-close f690c9d7 "what happened"`

`public/docs/demo_document.md`, section "Making it again", is addressed to maintainers, not readers (rule 26):

> Field's documentation build makes the film. It replays `demo_document.yaml` into an empty document in this checkout's Field desktop app, then runs the script above ... The build only films it on macOS with `FIELD_DOCS_CAPTURE_PROFILES=macos-electron`, because the spiral's frames only reach a page in Field's desktop app. On any other machine the build keeps the film it already has.

It is followed by a byte-pinned `diffstatic {data-lang=bash}` fence (the `FIELD_DOCS_CAPTURE_PROFILES=macos-electron FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page demo_document.md` recapture command). Moving the paragraph and that fence into the existing `??? aside "Agents: how the film is made"` would indent the fence, so a prose pass cannot do it.

Fix: move the paragraph and the recapture fence into the `Agents:` aside (re-pinning the fence bytes in `tools/completion-corpus/rank-carets.tsv` if it is listed), leaving "Making it again" with only the reader-facing part: opening the document by replaying the transcript with the backdoor, and the feel-benchmark pointer.
