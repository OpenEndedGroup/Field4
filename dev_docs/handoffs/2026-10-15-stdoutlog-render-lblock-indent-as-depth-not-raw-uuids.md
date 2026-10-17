# StdoutLog: render lblock indent as depth, not raw UUIDs

- **Handoff:** `3a909930-5870-4e95-b80c-cc3cce55d605` (project)
- **Filed from:** https://openendedgroup.com/field4/logging/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close 3a909930 "what happened"`

Rule 25 sharp edge. The page explains: "`StdoutLog` does not: it concatenates the indent into the line as-is ... so on a plain terminal a line logged inside an `lblock` is prefixed by a raw 36-character UUID."

Fix in `afield/ai/diagnostics/Logging.kt` (`StdoutLog`): convert the indent tokens to a visual depth (e.g. two spaces per level) before printing. Delete that paragraph in the same change; the figure footnote about the raw indent can stay.
