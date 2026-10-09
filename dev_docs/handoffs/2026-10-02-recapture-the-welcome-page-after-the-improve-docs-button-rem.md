# Recapture the welcome page after the Improve-docs button removal

- **Handoff:** `54e2a1a7-bb97-45da-8f8a-c53941b07fed` (project)
- **Filed from:** https://openendedgroup.com/field4/installation/
- **Filed by:** human, 2026-10-02 03:09
- **Close with:** `python tools/docs_notes.py handoff-close 54e2a1a7 "what happened"`

The welcome-page capture on `installation.md` (fence `name="welcome-page"`, `assets/welcome/welcome-page.png`) predates f545170e1, which removed the "Improve annotated docs" button. Its `alt` text still names that button. Recapture with `FIELD_DOCS_RUNNER_MODE=always` for this fence, check the PNG at column width, and update the fence's `alt` to match what the new still shows. The fence bytes are pinned, so this is a fence edit plus recapture.
